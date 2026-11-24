package icu.nullptr.polyglot.translate.providers

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import icu.nullptr.polyglot.module
import icu.nullptr.polyglot.translate.RequestControl
import icu.nullptr.polyglot.translate.TranslationRequest
import icu.nullptr.polyglot.translate.TranslationResult
import icu.nullptr.polyglot.translate.Translator
import icu.nullptr.polyglot.translate.cache.CacheKeyBuilder
import icu.nullptr.polyglot.translate.cache.TranslationCache
import icu.nullptr.polyglot.translate.openai.EndpointCapabilities
import icu.nullptr.polyglot.translate.openai.OpenAIEndpointNormalizer
import icu.nullptr.polyglot.translate.openai.OpenAIErrorCategory
import icu.nullptr.polyglot.translate.openai.OpenAIErrorClassifier
import icu.nullptr.polyglot.translate.openai.OpenAIRequestException
import icu.nullptr.polyglot.translate.openai.OpenAIResponseParser
import icu.nullptr.polyglot.translate.openai.OpenAISseReader
import icu.nullptr.polyglot.translate.openai.ParamAdaptation
import icu.nullptr.polyglot.translate.openai.ThinkingCleaner
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CancellationException
import java.util.concurrent.ThreadLocalRandom

object OpenAICompatibleTranslator : Translator {
    const val DEFAULT_ENDPOINT = OpenAIEndpointNormalizer.DEFAULT_CHAT_COMPLETIONS_URL
    const val DEFAULT_MODEL = "gpt-4o-mini"
    const val DEFAULT_TEMPERATURE = 0.3
    const val DEFAULT_MAX_TOKENS = 2048
    const val THINKING_RETRY_MAX_TOKENS = 4096
    const val PROBE_MAX_TOKENS = 16

    private const val DEFAULT_SYSTEM_PROMPT = "You are a professional, authentic machine translation engine."
    private const val DEFAULT_USER_PROMPT_PREFIX = "Translate the following subtitle text into {{to}}."
    private const val USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    data class ClientConfig(
        val endpoint: String,
        val apiKey: String,
        val model: String,
        val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
        val userPrompt: String = "",
        val maxRetries: Int = 2,
    )

    @Volatile
    var cache: TranslationCache? = null

    internal var connectionFactory: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as HttpURLConnection
    }

    internal var sleeper: (Long) -> Unit = { delayMs ->
        if (delayMs > 0L) Thread.sleep(delayMs)
    }

    override fun translate(request: TranslationRequest): TranslationResult =
        translateWithConfig(request, readRuntimeConfig(), cache)

    fun probeConnectivity(timeoutMs: Int = 12_000): String =
        probeConnectivityWithConfig(timeoutMs, readRuntimeConfig())

    fun translateWithConfig(
        request: TranslationRequest,
        config: ClientConfig,
        activeCache: TranslationCache? = cache,
    ): TranslationResult {
        if (request.texts.isEmpty() || request.texts.all { it.isBlank() }) {
            return TranslationResult(request.texts)
        }

        val apiKey = config.apiKey.trim()
        require(apiKey.isNotEmpty()) { "OpenAI-compatible API key is not configured" }

        val endpoint = OpenAIEndpointNormalizer.normalize(config.endpoint)
        val model = config.model.trim().ifEmpty { DEFAULT_MODEL }

        if (EndpointCapabilities.isAuthTripped(endpoint, model, apiKey)) {
            throw OpenAIRequestException(
                category = OpenAIErrorCategory.FATAL_AUTH_BILLING,
                statusCode = 401,
                message = "OpenAI authentication/billing circuit breaker is active for $model",
            )
        }
        if (EndpointCapabilities.isModelCoolingDown(endpoint, model)) {
            throw OpenAIRequestException(
                category = OpenAIErrorCategory.PARAM_OR_MODEL_REJECT,
                statusCode = 404,
                isModelUnavailable = true,
                message = "OpenAI model $model is temporarily unavailable (cooldown active)",
            )
        }

        val alignedByOriginalIndex = LinkedHashMap<Int, String>(request.texts.size)
        val missingOriginalIndices = ArrayList<Int>(request.texts.size)
        val keyByOriginalIndex = HashMap<Int, String>(request.texts.size)

        if (activeCache != null) {
            val keysToLookup = ArrayList<String>(request.texts.size)
            request.texts.forEachIndexed { index, text ->
                if (text.isNotBlank()) {
                    val key = CacheKeyBuilder.build(
                        provider = "openai",
                        endpoint = endpoint,
                        model = model,
                        sourceLanguage = request.sourceLanguage,
                        targetLanguage = request.targetLanguage,
                        text = text,
                    )
                    keyByOriginalIndex[index] = key
                    keysToLookup.add(key)
                }
            }
            val hits = activeCache.getBatch(keysToLookup)
            request.texts.forEachIndexed { index, text ->
                if (text.isNotBlank()) {
                    val hit = hits[keyByOriginalIndex[index]]
                    if (CacheKeyBuilder.isCacheableTranslation(hit)) {
                        alignedByOriginalIndex[index] = hit!!
                    } else {
                        missingOriginalIndices.add(index)
                    }
                }
            }
        } else {
            request.texts.forEachIndexed { index, text ->
                if (text.isNotBlank()) {
                    missingOriginalIndices.add(index)
                }
            }
        }

        if (missingOriginalIndices.isEmpty()) {
            return TranslationResult.aligned(request.texts, alignedByOriginalIndex)
        }

        val batchTexts = missingOriginalIndices.map { request.texts[it] }
        val slotToOriginalIndex = missingOriginalIndices.toIntArray()

        val translatedSlots = executeBatchWithGuards(
            batchTexts = batchTexts,
            slotToOriginalIndex = slotToOriginalIndex,
            request = request,
            endpoint = endpoint,
            apiKey = apiKey,
            model = model,
            config = config,
        )

        val newCacheEntries = LinkedHashMap<String, String>(translatedSlots.size)
        for ((slot, translated) in translatedSlots) {
            if (slot in slotToOriginalIndex.indices && CacheKeyBuilder.isCacheableTranslation(translated)) {
                val origIdx = slotToOriginalIndex[slot]
                val clean = translated.trim()
                alignedByOriginalIndex[origIdx] = clean
                keyByOriginalIndex[origIdx]?.let { cacheKey ->
                    newCacheEntries[cacheKey] = clean
                }
            }
        }

        if (activeCache != null && newCacheEntries.isNotEmpty()) {
            activeCache.putBatch(newCacheEntries)
        }

        return TranslationResult.aligned(request.texts, alignedByOriginalIndex)
    }

    fun probeConnectivityWithConfig(
        timeoutMs: Int,
        config: ClientConfig,
    ): String {
        val apiKey = config.apiKey.trim()
        require(apiKey.isNotEmpty()) { "OpenAI-compatible API key is not configured" }

        val endpoint = OpenAIEndpointNormalizer.normalize(config.endpoint)
        val model = config.model.trim().ifEmpty { DEFAULT_MODEL }

        var physicalAttempts = 0
        while (physicalAttempts < 3) {
            physicalAttempts++
            val caps = EndpointCapabilities.get(endpoint, model)
            val thinkingParams = if (caps.thinkingParamUnsupported) {
                emptyMap()
            } else {
                EndpointCapabilities.buildThinkingDisableParams(model)
            }

            val payload = JsonObject().apply {
                addProperty("model", model)
                addProperty("stream", false)
                if (!caps.temperatureUnsupported) {
                    addProperty("temperature", 0.0)
                }
                val tokenField = if (caps.useMaxCompletionTokens) "max_completion_tokens" else "max_tokens"
                addProperty(tokenField, PROBE_MAX_TOKENS)
                for ((k, v) in thinkingParams) {
                    add(k, v)
                }
                add(
                    "messages",
                    JsonArray().apply {
                        add(
                            JsonObject().apply {
                                addProperty("role", "system")
                                addProperty("content", "You are a concise assistant.")
                            },
                        )
                        add(
                            JsonObject().apply {
                                addProperty("role", "user")
                                addProperty("content", "ping")
                            },
                        )
                    },
                )
            }

            val connectTimeout = minOf(timeoutMs.coerceAtLeast(1_000), 8_000)
            val readTimeout = minOf(timeoutMs.coerceAtLeast(1_000), 12_000)

            try {
                val completion = performHttpCall(
                    endpoint = endpoint,
                    apiKey = apiKey,
                    payloadJson = payload.toString(),
                    stream = false,
                    connectTimeoutMs = connectTimeout,
                    readTimeoutMs = readTimeout,
                    forceCloseSocket = false,
                    sentThinkingParams = thinkingParams.isNotEmpty(),
                    sentResponseFormat = false,
                    sentStop = false,
                    expectedCount = 0,
                    slotToOriginalIndex = null,
                    control = null,
                )

                val cleaned = ThinkingCleaner.clean(completion.content)
                if (cleaned.text.isBlank() && !cleaned.hadThinking && !completion.sawReasoning && !completion.hasChoices) {
                    throw OpenAIRequestException(
                        category = OpenAIErrorCategory.INVALID_RESULT,
                        statusCode = 200,
                        message = "OpenAI connectivity probe returned empty response",
                    )
                }

                EndpointCapabilities.clearAuthFailure(endpoint, model, apiKey)
                EndpointCapabilities.recordStreamSuccess(endpoint, model)
                return cleaned.text.ifBlank { "OK ($model)" }
            } catch (e: OpenAIRequestException) {
                if (e.category == OpenAIErrorCategory.FATAL_AUTH_BILLING) {
                    EndpointCapabilities.recordAuthFailure(endpoint, model, apiKey)
                    throw e
                }
                if (e.isModelUnavailable) {
                    EndpointCapabilities.recordModelUnavailable(endpoint, model)
                    throw e
                }
                if (e.adaptations.isNotEmpty() && physicalAttempts < 3) {
                    applyAdaptations(endpoint, model, e.adaptations)
                    continue
                }
                throw e
            }
        }

        throw IllegalStateException("OpenAI connectivity probe exhausted parameter adaptations")
    }

    private fun executeBatchWithGuards(
        batchTexts: List<String>,
        slotToOriginalIndex: IntArray,
        request: TranslationRequest,
        endpoint: String,
        apiKey: String,
        model: String,
        config: ClientConfig,
    ): Map<Int, String> {
        val control = request.control
        val maxSlowRetries = config.maxRetries.coerceIn(0, 3)
        val maxPhysicalAttempts = (maxSlowRetries + 3).coerceAtMost(6)

        var slowRetryCount = 0
        var invalidResultRetries = 0
        var forceNonStreamForThisCall = false
        var forceCloseSocket = false
        var forceDisableThinking = false
        var maxTokensBudget = computeMaxTokens(batchTexts.size)
        var lastError: Throwable? = null

        for (physicalAttempt in 1..maxPhysicalAttempts) {
            if (control != null && (control.isCancelled() || !control.tryBeginAttempt())) {
                throw CancellationException("Translation cancelled before HTTP attempt")
            }

            val caps = EndpointCapabilities.get(endpoint, model)
            val useStream = request.enableStreaming && !forceNonStreamForThisCall && !caps.streamUnsupported
            val thinkingParams = if (caps.thinkingParamUnsupported) {
                emptyMap()
            } else {
                val baseParams = EndpointCapabilities.buildThinkingDisableParams(model)
                if (baseParams.isNotEmpty() || !forceDisableThinking) {
                    baseParams
                } else {
                    mapOf("enable_thinking" to com.google.gson.JsonPrimitive(false))
                }
            }
            val includeStop = !caps.stopUnsupported && !caps.useMaxCompletionTokens

            val requestBody = buildBatchRequestBody(
                batchTexts = batchTexts,
                request = request,
                model = model,
                config = config,
                stream = useStream,
                maxTokens = maxTokensBudget,
                useMaxCompletionTokens = caps.useMaxCompletionTokens,
                includeTemperature = !caps.temperatureUnsupported,
                includeStop = includeStop,
                thinkingParams = thinkingParams,
            )

            val (connectTimeoutMs, readTimeoutMs) = resolveTimeouts(
                requestedTimeoutMs = request.timeoutMs,
                isPriority = request.isPriority,
                stream = useStream,
            )

            try {
                val completion = performHttpCall(
                    endpoint = endpoint,
                    apiKey = apiKey,
                    payloadJson = requestBody,
                    stream = useStream,
                    connectTimeoutMs = connectTimeoutMs,
                    readTimeoutMs = readTimeoutMs,
                    forceCloseSocket = forceCloseSocket,
                    sentThinkingParams = thinkingParams.isNotEmpty(),
                    sentResponseFormat = false,
                    sentStop = includeStop,
                    expectedCount = batchTexts.size,
                    slotToOriginalIndex = slotToOriginalIndex,
                    control = control,
                )

                val parsed = OpenAIResponseParser.parseBatch(completion.content, batchTexts.size)
                if (parsed.indexed.isEmpty()) {
                    val isThinkingOnly = parsed.hadThinking || parsed.hadUnclosedThink || completion.sawReasoning
                    if (useStream && completion.wasSseStream) {
                        EndpointCapabilities.recordStreamEmptyOrMidError(endpoint, model)
                    }
                    throw OpenAIRequestException(
                        category = OpenAIErrorCategory.INVALID_RESULT,
                        statusCode = 200,
                        isThinkingOnly = isThinkingOnly,
                        message = if (isThinkingOnly) {
                            "OpenAI returned thinking process only without final subtitle translation"
                        } else {
                            "OpenAI returned HTTP 200 with 0 valid translations"
                        },
                    )
                }

                EndpointCapabilities.clearAuthFailure(endpoint, model, apiKey)
                EndpointCapabilities.recordStreamSuccess(endpoint, model)
                return parsed.indexed
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (rawErr: Throwable) {
                if (control?.isCancelled() == true) {
                    throw CancellationException("Translation cancelled during HTTP attempt").apply {
                        initCause(rawErr)
                    }
                }

                val classified = OpenAIErrorClassifier.classifyTransportError(rawErr)
                lastError = classified

                when (classified.category) {
                    OpenAIErrorCategory.FATAL_AUTH_BILLING -> {
                        EndpointCapabilities.recordAuthFailure(endpoint, model, apiKey)
                        throw classified
                    }

                    OpenAIErrorCategory.FATAL_NETWORK_CONFIG -> {
                        // Deterministic network/URL/TLS/cleartext policy failure: fail fast with 0 inner retries.
                        throw classified
                    }

                    OpenAIErrorCategory.PARAM_OR_MODEL_REJECT -> {
                        if (classified.isModelUnavailable) {
                            EndpointCapabilities.recordModelUnavailable(endpoint, model)
                            throw classified
                        }
                        if (classified.adaptations.isNotEmpty() && physicalAttempt < maxPhysicalAttempts) {
                            applyAdaptations(endpoint, model, classified.adaptations)
                            if (ParamAdaptation.DISABLE_STREAM in classified.adaptations) {
                                forceNonStreamForThisCall = true
                            }
                            // In-place protocol adaptation does not consume slowRetryCount.
                            continue
                        }
                        throw classified
                    }

                    OpenAIErrorCategory.INVALID_RESULT -> {
                        if (invalidResultRetries < 1 && physicalAttempt < maxPhysicalAttempts) {
                            invalidResultRetries++
                            forceNonStreamForThisCall = true
                            if (classified.isThinkingOnly) {
                                forceDisableThinking = true
                                maxTokensBudget = maxOf(maxTokensBudget * 2, THINKING_RETRY_MAX_TOKENS)
                                    .coerceAtMost(16_384)
                            }
                            continue
                        }
                        throw classified
                    }

                    OpenAIErrorCategory.RATE_LIMIT_OVERLOAD -> {
                        if (classified.retryAfterExceededCap || slowRetryCount >= maxSlowRetries || physicalAttempt >= maxPhysicalAttempts) {
                            throw classified
                        }
                        val delayMs = classified.retryAfterMs ?: computeRateLimitBackoffMs(slowRetryCount)
                        slowRetryCount++
                        waitBeforeRetry(delayMs, control)
                    }

                    OpenAIErrorCategory.SERVER_5XX_OR_STALE_SOCKET -> {
                        if (useStream && classified.statusCode in 500..599) {
                            EndpointCapabilities.recordStreamEmptyOrMidError(endpoint, model)
                        }
                        if (slowRetryCount >= maxSlowRetries || physicalAttempt >= maxPhysicalAttempts) {
                            throw classified
                        }
                        forceCloseSocket = true
                        val delayMs = if (slowRetryCount == 0) 1_000L else 2_500L
                        slowRetryCount++
                        waitBeforeRetry(delayMs, control)
                    }
                }
            }
        }

        throw lastError ?: IllegalStateException("OpenAI translation failed after exhausting attempts")
    }

    private fun performHttpCall(
        endpoint: String,
        apiKey: String,
        payloadJson: String,
        stream: Boolean,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        forceCloseSocket: Boolean,
        sentThinkingParams: Boolean,
        sentResponseFormat: Boolean,
        sentStop: Boolean,
        expectedCount: Int,
        slotToOriginalIndex: IntArray?,
        control: RequestControl?,
    ): OpenAISseReader.RawCompletion {
        val connection = connectionFactory(URL(endpoint))
        control?.onConnection(connection)
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.instanceFollowRedirects = true
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty(
                "Accept",
                if (stream) "text/event-stream, application/json" else "application/json",
            )
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            if (forceCloseSocket) {
                connection.setRequestProperty("Connection", "close")
            }

            val bodyBytes = payloadJson.toByteArray(Charsets.UTF_8)
            connection.outputStream.use { output ->
                output.write(bodyBytes)
                output.flush()
            }
            control?.onRequestBodySent()

            val status = connection.responseCode
            val contentType = connection.getHeaderField("Content-Type").orEmpty()

            if (status in 200..299) {
                return connection.inputStream.use { input ->
                    OpenAISseReader.readResponse(
                        inputStream = input,
                        contentType = contentType,
                        requestedStream = stream,
                        expectedCount = expectedCount,
                        slotToOriginalIndex = slotToOriginalIndex,
                        control = control,
                    )
                }
            }

            val retryAfterHeader = connection.getHeaderField("Retry-After")
            val errorBody = connection.errorStream
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { reader ->
                    val buf = CharArray(8192)
                    val len = reader.read(buf)
                    if (len > 0) String(buf, 0, len) else ""
                }
                .orEmpty()

            throw OpenAIErrorClassifier.classifyHttpError(
                statusCode = status,
                rawBody = errorBody,
                contentType = contentType,
                retryAfterHeader = retryAfterHeader,
                wasStreaming = stream,
                sentThinkingParams = sentThinkingParams,
                sentResponseFormat = sentResponseFormat,
                sentStop = sentStop,
            )
        } finally {
            control?.onConnection(null)
            runCatching { connection.disconnect() }
        }
    }

    internal fun buildBatchRequestBody(
        batchTexts: List<String>,
        request: TranslationRequest,
        model: String,
        config: ClientConfig,
        stream: Boolean,
        maxTokens: Int,
        useMaxCompletionTokens: Boolean,
        includeTemperature: Boolean,
        includeStop: Boolean,
        thinkingParams: Map<String, com.google.gson.JsonElement>,
    ): String {
        val count = batchTexts.size
        val baseSystem = config.systemPrompt.trim().ifEmpty { DEFAULT_SYSTEM_PROMPT }
        val srcDesc = request.sourceLanguage.trim().takeIf { it.isNotEmpty() && !it.equals("auto", ignoreCase = true) }
            ?: "the source language"
        val tgtDesc = request.targetLanguage.trim().ifEmpty { "zh-Hans" }

        val systemMessage = buildString {
            append(baseSystem)
            append("\nRules:\n")
            append("1. Translate subtitle items from ").append(srcDesc).append(" into ").append(tgtDesc)
                .append(" naturally, accurately, and concisely.\n")
            append("2. Maintain strict 1-to-1 slot alignment: input has ").append(count)
                .append(" items (i = 0..").append(count - 1).append("); you MUST return a JSON array of exactly ")
                .append(count).append(" objects in the format [{\"i\":0,\"t\":\"...\"}].\n")
            append("3. NEVER merge adjacent items, NEVER skip any item, NEVER split one item into multiple objects.\n")
            append("4. Context blocks are READ-ONLY for pronoun/terminology disambiguation; DO NOT include context in output.\n")
            append("5. Output ONLY valid JSON without markdown code fences or explanations.")

            extractCustomUserHint(config.userPrompt, tgtDesc)?.let { customHint ->
                append("\nAdditional instruction: ").append(customHint)
            }
        }

        val inputItemsJson = JsonArray().apply {
            batchTexts.forEachIndexed { idx, text ->
                add(
                    JsonObject().apply {
                        addProperty("i", idx)
                        addProperty("s", text)
                    },
                )
            }
        }.toString()

        val userMessage = buildString(inputItemsJson.length + 256) {
            if (request.contextBefore.isNotBlank()) {
                append("[Context Before - READ ONLY, DO NOT TRANSLATE]\n")
                append(request.contextBefore.trim())
                append("\n\n")
            }
            if (request.context.isNotBlank() &&
                request.context != "YouTube subtitle timeline" &&
                request.context != "YouTube subtitle overlay"
            ) {
                append("[Scene Note]\n")
                append(request.context.trim())
                append("\n\n")
            }
            append("[Subtitles to Translate into ").append(tgtDesc)
                .append(" (").append(count).append(" items) - Respond in JSON]\n")
            append(inputItemsJson)
            if (request.contextAfter.isNotBlank()) {
                append("\n\n[Context After - READ ONLY, DO NOT TRANSLATE]\n")
                append(request.contextAfter.trim())
            }
        }

        val messages = JsonArray().apply {
            add(
                JsonObject().apply {
                    addProperty("role", "system")
                    addProperty("content", systemMessage)
                },
            )
            add(
                JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", userMessage)
                },
            )
        }

        return JsonObject().apply {
            addProperty("model", model)
            addProperty("stream", stream)
            if (includeTemperature) {
                addProperty("temperature", DEFAULT_TEMPERATURE)
            }
            val tokenKey = if (useMaxCompletionTokens) "max_completion_tokens" else "max_tokens"
            addProperty(tokenKey, maxTokens)
            if (includeStop) {
                add(
                    "stop",
                    JsonArray().apply {
                        add("<|endoftext|>")
                        add("<|im_start|>")
                    },
                )
            }
            for ((key, value) in thinkingParams) {
                add(key, value)
            }
            add("messages", messages)
        }.toString()
    }

    private fun extractCustomUserHint(rawUserPrompt: String, targetLanguage: String): String? {
        val trimmed = rawUserPrompt.trim()
        if (trimmed.isEmpty() || trimmed.startsWith(DEFAULT_USER_PROMPT_PREFIX)) {
            return null
        }
        val cleaned = trimmed
            .replace("{{origin}}", "")
            .replace("{{to}}", targetLanguage)
            .trim()
        return cleaned.takeIf { it.isNotEmpty() }
    }

    private fun applyAdaptations(
        endpoint: String,
        model: String,
        adaptations: Set<ParamAdaptation>,
    ) {
        for (adaptation in adaptations) {
            when (adaptation) {
                ParamAdaptation.DISABLE_STREAM -> EndpointCapabilities.markStreamUnsupported(endpoint, model)
                ParamAdaptation.USE_MAX_COMPLETION_TOKENS -> EndpointCapabilities.markUseMaxCompletionTokens(endpoint, model)
                ParamAdaptation.DROP_THINKING_PARAMS -> EndpointCapabilities.markThinkingParamUnsupported(endpoint, model)
                ParamAdaptation.DROP_TEMPERATURE -> EndpointCapabilities.markTemperatureUnsupported(endpoint, model)
                ParamAdaptation.DROP_RESPONSE_FORMAT -> EndpointCapabilities.markResponseFormatUnsupported(endpoint, model)
                ParamAdaptation.DROP_STOP -> EndpointCapabilities.markStopUnsupported(endpoint, model)
            }
        }
    }

    private fun computeMaxTokens(batchCount: Int): Int =
        maxOf(DEFAULT_MAX_TOKENS, batchCount * 160).coerceAtMost(8192)

    private fun resolveTimeouts(
        requestedTimeoutMs: Int,
        isPriority: Boolean,
        stream: Boolean,
    ): Pair<Int, Int> {
        val safeRequested = requestedTimeoutMs.coerceAtLeast(200)
        val connectCap = if (isPriority) 10_000 else 12_000
        val streamReadCap = if (isPriority) 18_000 else 30_000
        val nonStreamReadCap = if (isPriority) 45_000 else 60_000

        val connectTimeout = minOf(safeRequested, connectCap)
        val readTimeout = if (stream) {
            minOf(safeRequested, streamReadCap)
        } else {
            minOf(safeRequested, nonStreamReadCap)
        }
        return connectTimeout to readTimeout
    }

    private fun computeRateLimitBackoffMs(attemptIndex: Int): Long {
        val exp = (1_500L * (1L shl attemptIndex.coerceAtMost(4))).coerceAtMost(12_000L)
        val jitter = ThreadLocalRandom.current().nextLong(0L, 601L)
        return exp + jitter
    }

    private fun waitBeforeRetry(delayMs: Long, control: RequestControl?) {
        if (delayMs <= 0L) return
        if (control?.isCancelled() == true) {
            throw CancellationException("Translation cancelled during retry backoff")
        }
        sleeper(delayMs)
        if (control?.isCancelled() == true) {
            throw CancellationException("Translation cancelled after retry backoff")
        }
    }

    private fun readRuntimeConfig(): ClientConfig =
        ClientConfig(
            endpoint = module.config.openAiEndpoint,
            apiKey = module.config.openAiApiKey,
            model = module.config.openAiModel,
            systemPrompt = module.config.openAiSystemPrompt,
            userPrompt = module.config.openAiUserPrompt,
            maxRetries = module.config.maxRetries,
        )
}

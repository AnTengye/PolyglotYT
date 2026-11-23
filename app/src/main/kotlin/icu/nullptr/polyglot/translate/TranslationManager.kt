package icu.nullptr.polyglot.translate

import android.content.Context
import icu.nullptr.polyglot.captions.CaptionCue
import icu.nullptr.polyglot.captions.engine.BlockCacheLookup
import icu.nullptr.polyglot.captions.engine.BlockTranslationException
import icu.nullptr.polyglot.captions.engine.BlockTranslationOutcome
import icu.nullptr.polyglot.captions.engine.BlockTranslator
import icu.nullptr.polyglot.captions.engine.EngineRequestControl
import icu.nullptr.polyglot.captions.engine.FailureKind
import icu.nullptr.polyglot.captions.engine.IndexedCue
import icu.nullptr.polyglot.captions.engine.PlannedBlock
import icu.nullptr.polyglot.captions.engine.SubtitleChunker
import icu.nullptr.polyglot.core.ConfigManager
import icu.nullptr.polyglot.isModuleInitialized
import icu.nullptr.polyglot.module
import icu.nullptr.polyglot.translate.cache.CacheKeyBuilder
import icu.nullptr.polyglot.translate.cache.SqliteTranslationDiskStore
import icu.nullptr.polyglot.translate.cache.TranslationCache
import icu.nullptr.polyglot.translate.cache.TranslationDiskStore
import icu.nullptr.polyglot.translate.openai.EndpointCapabilities
import icu.nullptr.polyglot.translate.openai.OpenAIErrorCategory
import icu.nullptr.polyglot.translate.openai.OpenAIRequestException
import icu.nullptr.polyglot.translate.providers.MicrosoftTranslator
import icu.nullptr.polyglot.translate.providers.OpenAICompatibleTranslator
import icu.nullptr.polyglot.translate.providers.YouTubeCommentTranslator
import icu.nullptr.polyglot.util.logW
import icu.nullptr.polyglot.youtube.CaptionHook
import java.io.File
import java.net.HttpURLConnection
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

object TranslationManager {
    const val TAG = "TranslationManager"
    const val MIN_TIMEOUT_MS = 5_000
    const val BASE_RETRY_DELAY_MS = 750L
    const val MICROSOFT_MAX_CUES_PER_BLOCK = 12
    const val GOOGLE_MAX_CUES_PER_BLOCK = 1
    const val CACHE_DB_FILE_NAME = "translation_cache.db"

    data class RuntimeConfig(
        val enabled: Boolean = true,
        val provider: String = ConfigManager.PROVIDER_GOOGLE,
        val targetLanguage: String = "zh-Hans",
        val requestTimeoutMs: Int = 45_000,
        val maxRetries: Int = 2,
        val persistentCacheEnabled: Boolean = true,
        val microsoftEndpoint: String = MicrosoftTranslator.DEFAULT_AZURE_ENDPOINT,
        val microsoftApiKey: String = "",
        val microsoftRegion: String = "",
        val openAiEndpoint: String = OpenAICompatibleTranslator.DEFAULT_ENDPOINT,
        val openAiApiKey: String = "",
        val openAiModel: String = OpenAICompatibleTranslator.DEFAULT_MODEL,
        val openAiStreaming: Boolean = true,
        val openAiSystemPrompt: String = "You are a professional, authentic machine translation engine.",
        val openAiUserPrompt: String = "",
    )

    private val cacheLock = Any()
    private val cacheClearEpoch = AtomicLong(0L)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    val sharedCache = TranslationCache(
        diskStore = null,
        maxMemoryEntries = TranslationCache.DEFAULT_L1_MAX_ENTRIES,
    )

    @Volatile
    internal var runtimeConfigProvider: () -> RuntimeConfig = ::defaultRuntimeConfig

    @Volatile
    internal var diskStoreOverride: TranslationDiskStore? = null

    @Volatile
    private var hostSqliteStore: SqliteTranslationDiskStore? = null

    @Volatile
    private var boundHostContext: Context? = null

    private val diskWriteExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PolyglotYT-CacheWrite").apply { isDaemon = true }
    }

    private val legacyExecutor: ExecutorService = Executors.newFixedThreadPool(2, LegacyThreadFactory)

    fun bindHostRuntime(context: Context, config: ConfigManager) {
        boundHostContext = context.applicationContext ?: context
        config.onClearTranslationCache = {
            clearCache()
            CaptionHook.onTranslationCacheCleared()
        }
    }

    fun currentRuntimeConfig(): RuntimeConfig = runtimeConfigProvider()

    fun maxCuesPerBlock(provider: String = currentRuntimeConfig().provider): Int =
        when (normalizeProviderId(provider)) {
            ConfigManager.PROVIDER_OPENAI -> Int.MAX_VALUE
            ConfigManager.PROVIDER_MICROSOFT -> MICROSOFT_MAX_CUES_PER_BLOCK
            else -> GOOGLE_MAX_CUES_PER_BLOCK
        }

    fun immediateMaxCuesPerBlock(provider: String = currentRuntimeConfig().provider): Int =
        when (normalizeProviderId(provider)) {
            ConfigManager.PROVIDER_OPENAI, ConfigManager.PROVIDER_MICROSOFT ->
                SubtitleChunker.DEFAULT_IMMEDIATE_MAX_CUES
            else -> GOOGLE_MAX_CUES_PER_BLOCK
        }

    /**
     * Zero-IO L1 memory lookup safe to call directly on Xposed UI / hook threads.
     */
    fun lookupMemoryOnly(
        text: String,
        sourceLanguage: String,
        config: RuntimeConfig = currentRuntimeConfig(),
    ): String? {
        val normalized = CaptionCue.normalize(text)
        if (normalized.isEmpty()) return null
        val key = buildCacheKey(normalized, sourceLanguage, config)
        return sharedCache.getMemoryOnly(key)
    }

    /**
     * Batch zero-IO L1 memory lookup returning `normalizedOriginal -> translated`.
     */
    fun lookupMemoryOnlyBatch(
        cues: Iterable<CaptionCue>,
        sourceLanguage: String,
        config: RuntimeConfig = currentRuntimeConfig(),
    ): Map<String, String> {
        val hits = LinkedHashMap<String, String>()
        for (cue in cues) {
            val norm = cue.normalizedText()
            if (norm.isEmpty() || norm in hits) continue
            val hit = sharedCache.getMemoryOnly(buildCacheKey(norm, sourceLanguage, config))
            if (hit != null) {
                hits[norm] = hit
            }
        }
        return hits
    }

    /**
     * Background L1 + (optional) L2 SQLite lookup for [DualQueueScheduler] `Polyglot-CacheIO`.
     * Never call on the Android UI thread when L2 persistence is active.
     */
    fun lookupBlockCache(
        block: PlannedBlock,
        sourceLanguage: String,
        config: RuntimeConfig = currentRuntimeConfig(),
    ): Map<Int, String> {
        if (block.cues.isEmpty()) return emptyMap()
        val activeDisk = resolveActiveDiskStore(config)
        sharedCache.diskStore = activeDisk

        val keyByLocalIdx = ArrayList<Pair<Int, String>>(block.cues.size)
        val keys = ArrayList<String>(block.cues.size)
        for (localIdx in block.cues.indices) {
            val text = block.cues[localIdx].normalizedText
            if (text.isBlank()) continue
            val key = buildCacheKey(text, sourceLanguage, config)
            keyByLocalIdx += localIdx to key
            keys += key
        }
        if (keys.isEmpty()) return emptyMap()

        val foundByKey = if (activeDisk != null) {
            sharedCache.getBatch(keys)
        } else {
            val memOnly = LinkedHashMap<String, String>()
            for (k in keys) {
                sharedCache.getMemoryOnly(k)?.let { memOnly[k] = it }
            }
            memOnly
        }
        if (foundByKey.isEmpty()) return emptyMap()

        val byLocal = LinkedHashMap<Int, String>(foundByKey.size)
        for ((localIdx, key) in keyByLocalIdx) {
            foundByKey[key]?.let { translated ->
                byLocal[localIdx] = translated
            }
        }
        return byLocal
    }

    fun blockCacheLookup(sourceLanguageProvider: () -> String): BlockCacheLookup =
        BlockCacheLookup { block ->
            lookupBlockCache(block, sourceLanguageProvider())
        }

    fun blockTranslator(sourceLanguageProvider: () -> String): BlockTranslator =
        BlockTranslator { block, cuesToTranslate, isPriority, engineControl ->
            translateBlock(
                block = block,
                cuesToTranslate = cuesToTranslate,
                sourceLanguage = sourceLanguageProvider(),
                isPriority = isPriority,
                engineControl = engineControl,
            )
        }

    /**
     * Stores verified final cue translations into L1 immediately and, when `persistentCacheEnabled`
     * is true, asynchronously persists to L2 SQLite without blocking `Focus`/`Prefetch` threads.
     */
    fun cacheTranslations(
        entries: Map<IndexedCue, String>,
        sourceLanguage: String,
        config: RuntimeConfig = currentRuntimeConfig(),
    ) {
        if (entries.isEmpty()) return
        val mapped = LinkedHashMap<String, String>(entries.size)
        for ((cue, translated) in entries) {
            if (!CacheKeyBuilder.isCacheableTranslation(translated)) continue
            val key = buildCacheKey(cue.normalizedText, sourceLanguage, config)
            mapped[key] = translated.trim()
        }
        if (mapped.isEmpty()) return

        sharedCache.putMemoryOnly(mapped)
        val disk = resolveActiveDiskStore(config) ?: return
        val capturedClearEpoch = cacheClearEpoch.get()
        try {
            diskWriteExecutor.execute {
                synchronized(cacheLock) {
                    if (cacheClearEpoch.get() != capturedClearEpoch) return@execute
                    val currentCfg = currentRuntimeConfig()
                    if (!currentCfg.persistentCacheEnabled) return@execute
                    runCatching { disk.putBatch(mapped) }
                        .onFailure { e -> safeWarn("Failed to persist translation batch", e) }
                }
            }
        } catch (_: RejectedExecutionException) {
            // Ignore shutdown races in test harnesses.
        }
    }

    fun clearCache() {
        synchronized(cacheLock) {
            cacheClearEpoch.incrementAndGet()
            sharedCache.diskStore = resolveDiskStoreForClear()
            sharedCache.clear()
            EndpointCapabilities.clear()
            deleteHostCacheDbIfDetached()
        }
    }

    internal fun flushPendingDiskWrites() {
        runCatching {
            diskWriteExecutor.submit {}.get(5, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    fun translateBlock(
        block: PlannedBlock,
        cuesToTranslate: List<IndexedCue>,
        sourceLanguage: String,
        isPriority: Boolean,
        engineControl: EngineRequestControl,
        config: RuntimeConfig = currentRuntimeConfig(),
    ): BlockTranslationOutcome {
        if (cuesToTranslate.isEmpty()) {
            return BlockTranslationOutcome(emptyMap())
        }

        val resolved = LinkedHashMap<Int, String>(cuesToTranslate.size)
        val pendingPositions = ArrayList<Int>(cuesToTranslate.size)
        for (i in cuesToTranslate.indices) {
            val norm = cuesToTranslate[i].normalizedText
            if (norm.isBlank()) continue
            val hit = lookupMemoryOnly(norm, sourceLanguage, config)
            if (hit != null) {
                resolved[i] = hit
            } else {
                pendingPositions += i
            }
        }
        if (pendingPositions.isEmpty()) {
            return BlockTranslationOutcome(resolved)
        }

        val textsToSend = pendingPositions.map { cuesToTranslate[it].normalizedText }
        val bridgedControl = object : RequestControl {
            override fun tryBeginAttempt(): Boolean = engineControl.tryBeginAttempt()

            override fun isCancelled(): Boolean = engineControl.isCancelled()

            override fun onConnection(connection: HttpURLConnection?) {
                engineControl.onConnection(connection)
            }

            override fun onRequestBodySent() {
                engineControl.onRequestBodySent()
            }

            override fun onProvisional(index: Int, translated: String) {
                if (index in pendingPositions.indices) {
                    engineControl.onProvisional(pendingPositions[index], translated)
                }
            }
        }

        val request = TranslationRequest(
            texts = textsToSend,
            sourceLanguage = normalizedSourceLanguage(sourceLanguage),
            targetLanguage = config.targetLanguage,
            context = if (block.isImmediate) "YouTube subtitle overlay" else "YouTube subtitle timeline",
            timeoutMs = config.requestTimeoutMs.coerceAtLeast(MIN_TIMEOUT_MS),
            contextBefore = block.contextBefore,
            contextAfter = block.contextAfter,
            isPriority = isPriority,
            enableStreaming = config.openAiStreaming,
            control = bridgedControl,
        )

        val result = executeProviderRequest(request, config, bridgedControl)
        for ((subIdx, translated) in result.indexedTexts) {
            if (subIdx in pendingPositions.indices && CacheKeyBuilder.isCacheableTranslation(translated)) {
                resolved[pendingPositions[subIdx]] = translated.trim()
            }
        }
        return BlockTranslationOutcome(resolved)
    }

    fun translateAsync(
        text: String,
        context: String,
        sourceLanguage: String,
        onTranslated: (original: String, translated: String) -> Unit,
    ): Boolean {
        val original = CaptionCue.normalize(text)
        if (original.isEmpty()) return false

        val config = currentRuntimeConfig()
        lookupMemoryOnly(original, sourceLanguage, config)?.let { hit ->
            onTranslated(original, hit)
            return true
        }

        val requestKey = buildCacheKey(original, sourceLanguage, config)
        if (!inFlight.add(requestKey)) {
            return false
        }

        return try {
            legacyExecutor.execute {
                try {
                    val translated = translateSingleWithCache(
                        text = original,
                        context = context,
                        sourceLanguage = sourceLanguage,
                        timeoutMs = config.requestTimeoutMs.coerceAtLeast(MIN_TIMEOUT_MS),
                        config = config,
                    )
                    if (translated.isNotBlank()) {
                        onTranslated(original, translated)
                    }
                } catch (e: Throwable) {
                    safeWarn("Caption translation failed, length=${original.length}", e)
                } finally {
                    inFlight.remove(requestKey)
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            inFlight.remove(requestKey)
            false
        }
    }

    fun translateForConnectivityTest(
        text: String,
        context: String,
        sourceLanguage: String,
        timeoutMs: Int,
    ): String {
        val normalized = CaptionCue.normalize(text)
        if (normalized.isBlank()) return ""
        val config = currentRuntimeConfig()
        val request = TranslationRequest(
            texts = listOf(normalized),
            sourceLanguage = normalizedSourceLanguage(sourceLanguage),
            targetLanguage = config.targetLanguage,
            context = context,
            timeoutMs = timeoutMs.coerceAtLeast(MIN_TIMEOUT_MS),
            isPriority = true,
            enableStreaming = false,
        )
        return when (normalizeProviderId(config.provider)) {
            ConfigManager.PROVIDER_OPENAI ->
                OpenAICompatibleTranslator.translateWithConfig(
                    request = request,
                    config = toOpenAiClientConfig(config, maxRetries = 0),
                    activeCache = null,
                ).texts.firstOrNull().orEmpty()
            ConfigManager.PROVIDER_MICROSOFT ->
                MicrosoftTranslator.translate(request).texts.firstOrNull().orEmpty()
            else ->
                YouTubeCommentTranslator.translate(request).texts.firstOrNull().orEmpty()
        }
    }

    fun buildCacheKey(
        normalizedText: String,
        sourceLanguage: String,
        config: RuntimeConfig = currentRuntimeConfig(),
    ): String {
        val providerId = normalizeProviderId(config.provider)
        val endpoint = when (providerId) {
            ConfigManager.PROVIDER_OPENAI -> config.openAiEndpoint
            ConfigManager.PROVIDER_MICROSOFT -> config.microsoftEndpoint
            else -> YouTubeCommentTranslator.ENDPOINT
        }
        val model = if (providerId == ConfigManager.PROVIDER_OPENAI) config.openAiModel else ""
        return CacheKeyBuilder.build(
            provider = providerId,
            endpoint = endpoint,
            model = model,
            sourceLanguage = normalizedSourceLanguage(sourceLanguage),
            targetLanguage = config.targetLanguage,
            text = normalizedText,
        )
    }

    private fun executeProviderRequest(
        request: TranslationRequest,
        config: RuntimeConfig,
        control: RequestControl,
    ): TranslationResult {
        if (!control.tryBeginAttempt()) {
            throw CancellationException("Session retired or cancelled before provider call")
        }

        return when (normalizeProviderId(config.provider)) {
            ConfigManager.PROVIDER_OPENAI -> {
                try {
                    OpenAICompatibleTranslator.translateWithConfig(
                        request = request,
                        config = toOpenAiClientConfig(
                            config = config,
                            maxRetries = config.maxRetries.coerceIn(0, 1),
                        ),
                        activeCache = null,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: OpenAIRequestException) {
                    throw mapOpenAiException(e)
                } catch (e: IllegalArgumentException) {
                    // e.g. blank API key
                    throw BlockTranslationException(
                        message = e.message ?: "Invalid OpenAI configuration",
                        kind = FailureKind.FATAL_CONFIG,
                        cause = e,
                    )
                }
            }

            ConfigManager.PROVIDER_MICROSOFT ->
                executeNonOpenAiWithLightRetry(request, config, control, MicrosoftTranslator)

            else ->
                executeNonOpenAiWithLightRetry(request, config, control, YouTubeCommentTranslator)
        }
    }

    private fun executeNonOpenAiWithLightRetry(
        request: TranslationRequest,
        config: RuntimeConfig,
        control: RequestControl,
        provider: Translator,
    ): TranslationResult {
        val maxAttempts = 1 + config.maxRetries.coerceIn(0, 1)
        var lastError: Throwable? = null
        for (attempt in 0 until maxAttempts) {
            if (control.isCancelled() || (attempt > 0 && !control.tryBeginAttempt())) {
                throw CancellationException("Translation cancelled")
            }
            try {
                if (attempt == 0) {
                    control.onRequestBodySent()
                }
                return provider.translate(request)
            } catch (e: Throwable) {
                lastError = e
                if (control.isCancelled()) throw CancellationException("Translation cancelled")
                if (attempt + 1 < maxAttempts) {
                    Thread.sleep(BASE_RETRY_DELAY_MS * (attempt + 1))
                }
            }
        }
        val err = lastError ?: IllegalStateException("Translation failed")
        throw classifyGenericProviderException(err)
    }

    private fun translateSingleWithCache(
        text: String,
        context: String,
        sourceLanguage: String,
        timeoutMs: Int,
        config: RuntimeConfig,
    ): String {
        val cacheKey = buildCacheKey(text, sourceLanguage, config)
        val disk = resolveActiveDiskStore(config)
        sharedCache.diskStore = disk
        val cached = if (disk != null) sharedCache.get(cacheKey) else sharedCache.getMemoryOnly(cacheKey)
        if (!cached.isNullOrBlank()) {
            return cached
        }

        val request = TranslationRequest(
            texts = listOf(text),
            sourceLanguage = normalizedSourceLanguage(sourceLanguage),
            targetLanguage = config.targetLanguage,
            context = context,
            timeoutMs = timeoutMs,
            isPriority = true,
            enableStreaming = false,
        )
        val translated = executeProviderRequest(request, config, object : RequestControl {}).texts.firstOrNull().orEmpty()
        if (CacheKeyBuilder.isCacheableTranslation(translated)) {
            val entry = mapOf(cacheKey to translated.trim())
            if (disk != null) {
                sharedCache.putBatch(entry)
            } else {
                sharedCache.putMemoryOnly(entry)
            }
        }
        return translated
    }

    internal fun mapOpenAiException(e: OpenAIRequestException): BlockTranslationException {
        val kind = when (e.category) {
            OpenAIErrorCategory.FATAL_AUTH_BILLING -> FailureKind.FATAL_CONFIG
            OpenAIErrorCategory.RATE_LIMIT_OVERLOAD -> FailureKind.RATE_LIMIT
            OpenAIErrorCategory.INVALID_RESULT -> FailureKind.INVALID_RESULT
            OpenAIErrorCategory.PARAM_OR_MODEL_REJECT ->
                if (e.isModelUnavailable || e.statusCode == 404) {
                    FailureKind.FATAL_CONFIG
                } else {
                    FailureKind.TRANSIENT
                }
            OpenAIErrorCategory.SERVER_5XX_OR_STALE_SOCKET -> FailureKind.TRANSIENT
        }
        return BlockTranslationException(
            message = e.message ?: "OpenAI request failed",
            kind = kind,
            retryAfterMs = (e.retryAfterMs ?: 0L).coerceAtLeast(0L),
            cause = e,
        )
    }

    private fun classifyGenericProviderException(error: Throwable): BlockTranslationException {
        if (error is BlockTranslationException) return error
        val msg = (error.message ?: "").lowercase(Locale.ROOT)
        val kind = when {
            msg.contains("http 401") || msg.contains("http 402") || msg.contains("http 403") ->
                FailureKind.FATAL_CONFIG
            msg.contains("http 429") || msg.contains("too many requests") ->
                FailureKind.RATE_LIMIT
            else -> FailureKind.TRANSIENT
        }
        return BlockTranslationException(
            message = error.message ?: error.javaClass.simpleName,
            kind = kind,
            cause = error,
        )
    }

    private fun toOpenAiClientConfig(
        config: RuntimeConfig,
        maxRetries: Int,
    ): OpenAICompatibleTranslator.ClientConfig =
        OpenAICompatibleTranslator.ClientConfig(
            endpoint = config.openAiEndpoint,
            apiKey = config.openAiApiKey,
            model = config.openAiModel,
            systemPrompt = config.openAiSystemPrompt,
            userPrompt = config.openAiUserPrompt,
            maxRetries = maxRetries,
        )

    private fun resolveActiveDiskStore(config: RuntimeConfig): TranslationDiskStore? {
        if (!config.persistentCacheEnabled) {
            sharedCache.diskStore = null
            return null
        }
        diskStoreOverride?.let { overrideStore ->
            sharedCache.diskStore = overrideStore
            return overrideStore
        }
        if (!isModuleInitialized) {
            return null
        }

        val existing = hostSqliteStore
        if (existing != null) {
            sharedCache.diskStore = existing
            return existing
        }

        synchronized(cacheLock) {
            hostSqliteStore?.let {
                sharedCache.diskStore = it
                return it
            }
            val ctx = boundHostContext ?: runCatching { module.hostContext }.getOrNull() ?: return null
            val dbFile = module.cacheDir.resolve(CACHE_DB_FILE_NAME)
            return runCatching {
                SqliteTranslationDiskStore(
                    context = ctx,
                    dbFile = dbFile,
                ).also { created ->
                    hostSqliteStore = created
                    sharedCache.diskStore = created
                }
            }.onFailure { e ->
                safeWarn("Unable to initialize SQLite translation cache", e)
            }.getOrNull()
        }
    }

    private fun resolveDiskStoreForClear(): TranslationDiskStore? {
        diskStoreOverride?.let { return it }
        return hostSqliteStore
    }

    private fun deleteHostCacheDbIfDetached() {
        if (diskStoreOverride != null || hostSqliteStore != null || !isModuleInitialized) return
        runCatching {
            val dbFile = module.cacheDir.resolve(CACHE_DB_FILE_NAME)
            SqliteTranslationDiskStore.deleteDatabaseFiles(dbFile)
        }
    }

    private fun normalizeProviderId(provider: String): String =
        when (provider.trim().lowercase(Locale.ROOT)) {
            "openai", "openai-compatible", "custom" -> ConfigManager.PROVIDER_OPENAI
            "microsoft" -> ConfigManager.PROVIDER_MICROSOFT
            else -> ConfigManager.PROVIDER_GOOGLE
        }

    private fun normalizedSourceLanguage(language: String): String =
        language.trim().ifEmpty { "auto" }

    private fun defaultRuntimeConfig(): RuntimeConfig {
        if (!isModuleInitialized) return RuntimeConfig()
        val cfg = module.config
        return RuntimeConfig(
            enabled = cfg.enabled,
            provider = cfg.provider,
            targetLanguage = cfg.targetLanguage,
            requestTimeoutMs = cfg.requestTimeoutMs,
            maxRetries = cfg.maxRetries,
            persistentCacheEnabled = cfg.persistentCacheEnabled,
            microsoftEndpoint = cfg.microsoftEndpoint,
            microsoftApiKey = cfg.microsoftApiKey,
            microsoftRegion = cfg.microsoftRegion,
            openAiEndpoint = cfg.openAiEndpoint,
            openAiApiKey = cfg.openAiApiKey,
            openAiModel = cfg.openAiModel,
            openAiStreaming = cfg.openAiStreaming,
            openAiSystemPrompt = cfg.openAiSystemPrompt,
            openAiUserPrompt = cfg.openAiUserPrompt,
        )
    }

    private fun safeWarn(message: String, tr: Throwable? = null) {
        if (isModuleInitialized) {
            logW(TAG, message, tr)
        }
    }

    private object LegacyThreadFactory : java.util.concurrent.ThreadFactory {
        private val counter = AtomicInteger(0)

        override fun newThread(runnable: Runnable): Thread =
            Thread(runnable, "PolyglotYT-LegacyTranslator-${counter.incrementAndGet()}").apply {
                isDaemon = true
            }
    }
}

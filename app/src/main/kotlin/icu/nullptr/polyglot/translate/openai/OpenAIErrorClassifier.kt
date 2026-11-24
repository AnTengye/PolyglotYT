package icu.nullptr.polyglot.translate.openai

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.EOFException
import java.io.IOException
import java.net.MalformedURLException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URISyntaxException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.security.cert.CertPathValidatorException
import java.util.Locale
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLProtocolException

enum class OpenAIErrorCategory {
    FATAL_AUTH_BILLING,
    FATAL_NETWORK_CONFIG,
    PARAM_OR_MODEL_REJECT,
    RATE_LIMIT_OVERLOAD,
    SERVER_5XX_OR_STALE_SOCKET,
    INVALID_RESULT,
}

enum class ParamAdaptation {
    DISABLE_STREAM,
    USE_MAX_COMPLETION_TOKENS,
    DROP_THINKING_PARAMS,
    DROP_TEMPERATURE,
    DROP_RESPONSE_FORMAT,
    DROP_STOP,
}

class OpenAIRequestException(
    val category: OpenAIErrorCategory,
    val statusCode: Int = 0,
    val retryAfterMs: Long? = null,
    val retryAfterExceededCap: Boolean = false,
    val isThinkingOnly: Boolean = false,
    val isModelUnavailable: Boolean = false,
    val isDnsResolutionError: Boolean = false,
    val adaptations: Set<ParamAdaptation> = emptySet(),
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

object OpenAIErrorClassifier {
    const val MAX_RETRY_AFTER_MS = 30_000L

    private val HTML_TITLE_REGEX = Regex("<title>([^<]+)</title>", RegexOption.IGNORE_CASE)
    private val HTML_TAG_REGEX = Regex("<[^>]*>")
    private val WHITESPACE_REGEX = Regex("\\s+")

    fun classifyHttpError(
        statusCode: Int,
        rawBody: String,
        contentType: String = "",
        retryAfterHeader: String? = null,
        wasStreaming: Boolean = false,
        sentThinkingParams: Boolean = false,
        sentResponseFormat: Boolean = false,
        sentStop: Boolean = false,
    ): OpenAIRequestException {
        val extractedMessage = extractReadableMessage(rawBody, contentType, statusCode)
        val lower = "${rawBody.take(2000)}\n$extractedMessage".lowercase(Locale.ROOT)
        val isHtmlWaf = contentType.contains("text/html", ignoreCase = true) ||
            lower.contains("cloudflare") ||
            lower.contains("just a moment") ||
            lower.contains("attention required")

        // 1. Check explicit rate-limit / overload signals first (some relays return 400/503 for rate limits).
        if (statusCode == 429 || containsRateLimitSignal(lower)) {
            val (retryMs, exceeded) = parseRetryAfter(retryAfterHeader)
            return OpenAIRequestException(
                category = OpenAIErrorCategory.RATE_LIMIT_OVERLOAD,
                statusCode = statusCode,
                retryAfterMs = retryMs,
                retryAfterExceededCap = exceeded,
                message = "OpenAI rate limited ($statusCode): $extractedMessage",
            )
        }

        // 2. Auth / billing fatal errors (0 retries).
        if (statusCode == 401 || statusCode == 402 ||
            (statusCode == 403 && !isHtmlWaf) ||
            containsAuthOrBillingSignal(lower)
        ) {
            return OpenAIRequestException(
                category = OpenAIErrorCategory.FATAL_AUTH_BILLING,
                statusCode = statusCode,
                message = "OpenAI auth/quota error ($statusCode): $extractedMessage",
            )
        }

        // 3. Upstream 5xx or Cloudflare 52x / WAF gateway errors.
        if (statusCode >= 500 && statusCode != 501) {
            return OpenAIRequestException(
                category = OpenAIErrorCategory.SERVER_5XX_OR_STALE_SOCKET,
                statusCode = statusCode,
                message = "OpenAI upstream server error ($statusCode): $extractedMessage",
            )
        }

        // 4. Client / model / parameter rejections (400, 404, 405, 406, 422, 501).
        if (statusCode in 400..499 || statusCode == 501) {
            if (containsModelUnavailableSignal(statusCode, lower)) {
                return OpenAIRequestException(
                    category = OpenAIErrorCategory.PARAM_OR_MODEL_REJECT,
                    statusCode = statusCode,
                    isModelUnavailable = true,
                    message = "OpenAI model unavailable ($statusCode): $extractedMessage",
                )
            }

            if (containsContentPolicySignal(lower)) {
                return OpenAIRequestException(
                    category = OpenAIErrorCategory.PARAM_OR_MODEL_REJECT,
                    statusCode = statusCode,
                    message = "OpenAI content policy rejected ($statusCode): $extractedMessage",
                )
            }

            val adaptations = detectParamAdaptations(
                statusCode = statusCode,
                lower = lower,
                wasStreaming = wasStreaming,
                sentThinkingParams = sentThinkingParams,
                sentResponseFormat = sentResponseFormat,
                sentStop = sentStop,
            )
            return OpenAIRequestException(
                category = OpenAIErrorCategory.PARAM_OR_MODEL_REJECT,
                statusCode = statusCode,
                adaptations = adaptations,
                message = "OpenAI request rejected ($statusCode): $extractedMessage",
            )
        }

        return OpenAIRequestException(
            category = OpenAIErrorCategory.SERVER_5XX_OR_STALE_SOCKET,
            statusCode = statusCode,
            message = "OpenAI HTTP $statusCode: $extractedMessage",
        )
    }

    fun classifyEmbeddedError(
        errorElement: JsonObject,
        wasStreaming: Boolean,
    ): OpenAIRequestException {
        val nestedStatus = errorElement.get("status")?.takeIf { it.isJsonPrimitive }?.let {
            runCatching { it.asInt }.getOrNull()
        } ?: errorElement.get("code")?.takeIf { it.isJsonPrimitive }?.let {
            runCatching { it.asInt }.getOrNull()
        } ?: 0

        val codeStr = errorElement.get("code")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val typeStr = errorElement.get("type")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val msgStr = errorElement.get("message")?.takeIf { it.isJsonPrimitive }?.asString
            ?: errorElement.toString()
        val combined = "$codeStr $typeStr $msgStr"

        val syntheticStatus = when {
            nestedStatus in 400..599 -> nestedStatus
            containsRateLimitSignal(combined.lowercase(Locale.ROOT)) -> 429
            containsAuthOrBillingSignal(combined.lowercase(Locale.ROOT)) -> 401
            else -> 502
        }

        return classifyHttpError(
            statusCode = syntheticStatus,
            rawBody = combined,
            wasStreaming = wasStreaming,
        )
    }

    fun classifyTransportError(error: Throwable): OpenAIRequestException {
        if (error is OpenAIRequestException) return error

        val chain = buildCauseChain(error)
        val combinedLower = chain
            .mapNotNull { it.message }
            .joinToString("\n")
            .lowercase(Locale.ROOT)
        val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName

        // 1. Android Network Security Policy blocking cleartext HTTP (deterministic config error).
        if (containsCleartextBlockedSignal(combinedLower)) {
            return OpenAIRequestException(
                category = OpenAIErrorCategory.FATAL_NETWORK_CONFIG,
                message = "OpenAI cleartext HTTP blocked by Android network security policy (use HTTPS endpoint): $detail",
                cause = error,
            )
        }

        // 2. Malformed URL, invalid port, or unsupported protocol/service (deterministic config error).
        if (chain.any {
                it is MalformedURLException ||
                    it is URISyntaxException ||
                    it is IllegalArgumentException ||
                    it is UnknownServiceException
            }
        ) {
            return OpenAIRequestException(
                category = OpenAIErrorCategory.FATAL_NETWORK_CONFIG,
                message = "OpenAI invalid endpoint URL or port configuration: $detail",
                cause = error,
            )
        }

        // 3. TLS / SSL certificate or handshake rejection (deterministic config/endpoint error).
        if (chain.any {
                it is SSLHandshakeException ||
                    it is SSLPeerUnverifiedException ||
                    it is SSLProtocolException ||
                    it is CertPathValidatorException
            }
        ) {
            return OpenAIRequestException(
                category = OpenAIErrorCategory.FATAL_NETWORK_CONFIG,
                message = "OpenAI TLS/SSL handshake failed: $detail",
                cause = error,
            )
        }

        // 4. DNS host resolution failure (skip inner retry amplification; mark DNS so scheduler can still recover on reconnect).
        if (chain.any { it is UnknownHostException }) {
            return OpenAIRequestException(
                category = OpenAIErrorCategory.FATAL_NETWORK_CONFIG,
                isDnsResolutionError = true,
                message = "OpenAI host unreachable (DNS resolution failed): $detail",
                cause = error,
            )
        }

        val category = when (error) {
            is EOFException,
            is SocketTimeoutException,
            is SocketException,
            is IOException,
            -> OpenAIErrorCategory.SERVER_5XX_OR_STALE_SOCKET
            else -> OpenAIErrorCategory.SERVER_5XX_OR_STALE_SOCKET
        }
        return OpenAIRequestException(
            category = category,
            message = "OpenAI network error: $detail",
            cause = error,
        )
    }

    private fun buildCauseChain(root: Throwable): List<Throwable> {
        val out = ArrayList<Throwable>(4)
        var current: Throwable? = root
        while (current != null && out.size < 8 && current !in out) {
            out += current
            current = current.cause
        }
        return out
    }

    private fun containsCleartextBlockedSignal(lower: String): Boolean =
        "cleartext http traffic" in lower ||
            "cleartext communication" in lower ||
            "not permitted by network security policy" in lower

    fun parseRetryAfter(headerValue: String?): Pair<Long?, Boolean> {
        val trimmed = headerValue?.trim().orEmpty()
        if (trimmed.isEmpty()) return null to false
        val seconds = trimmed.toDoubleOrNull() ?: return null to false
        if (seconds <= 0.0 || !seconds.isFinite()) return null to false
        val ms = (seconds * 1000.0).toLong()
        return if (ms > MAX_RETRY_AFTER_MS) {
            ms to true
        } else {
            ms.coerceAtLeast(100L) to false
        }
    }

    internal fun extractReadableMessage(rawBody: String, contentType: String, statusCode: Int): String {
        val trimmed = rawBody.trim()
        if (trimmed.isEmpty()) return "HTTP $statusCode"

        if (contentType.contains("text/html", ignoreCase = true) ||
            trimmed.startsWith("<!DOCTYPE", ignoreCase = true) ||
            trimmed.startsWith("<html", ignoreCase = true)
        ) {
            val title = HTML_TITLE_REGEX.find(trimmed)?.groupValues?.getOrNull(1).orEmpty()
            val cleaned = (title.ifBlank { trimmed })
                .replace(HTML_TAG_REGEX, " ")
                .replace(WHITESPACE_REGEX, " ")
                .trim()
                .take(160)
            return cleaned.ifEmpty { "Upstream HTML error ($statusCode)" }
        }

        if (trimmed.startsWith("{")) {
            runCatching {
                val json = JsonParser.parseString(trimmed).asJsonObject
                val errField = json.get("error")
                when {
                    errField != null && errField.isJsonObject -> {
                        val msg = errField.asJsonObject.get("message")?.takeIf { it.isJsonPrimitive }?.asString
                        if (!msg.isNullOrBlank()) return msg.trim().take(240)
                    }
                    errField != null && errField.isJsonPrimitive -> {
                        val msg = errField.asString
                        if (msg.isNotBlank()) return msg.trim().take(240)
                    }
                }
                val topMsg = json.get("message")?.takeIf { it.isJsonPrimitive }?.asString
                if (!topMsg.isNullOrBlank()) return topMsg.trim().take(240)
            }
        }

        return trimmed.replace(WHITESPACE_REGEX, " ").take(240)
    }

    private fun detectParamAdaptations(
        statusCode: Int,
        lower: String,
        wasStreaming: Boolean,
        sentThinkingParams: Boolean,
        sentResponseFormat: Boolean,
        sentStop: Boolean,
    ): Set<ParamAdaptation> {
        val adaptations = LinkedHashSet<ParamAdaptation>()

        if ("max_completion_tokens" in lower ||
            ("max_tokens" in lower && ("unsupported" in lower || "not supported" in lower || "instead" in lower))
        ) {
            adaptations.add(ParamAdaptation.USE_MAX_COMPLETION_TOKENS)
        }

        if ("temperature" in lower && ("unsupported" in lower || "not supported" in lower || "only the default" in lower || "invalid" in lower)) {
            adaptations.add(ParamAdaptation.DROP_TEMPERATURE)
        }

        if (sentThinkingParams && (
                "enable_thinking" in lower ||
                    "thinking_budget" in lower ||
                    "reasoning_effort" in lower ||
                    "thinking" in lower ||
                    "extra_forbidden" in lower ||
                    "unknown parameter" in lower ||
                    "unrecognized request argument" in lower ||
                    "additional properties" in lower
                )
        ) {
            adaptations.add(ParamAdaptation.DROP_THINKING_PARAMS)
        }

        if (sentResponseFormat && ("response_format" in lower || "json_object" in lower || "json_schema" in lower)) {
            adaptations.add(ParamAdaptation.DROP_RESPONSE_FORMAT)
        }

        if (sentStop && ("'stop'" in lower || ("\"stop\"" in lower && "unsupported" in lower))) {
            adaptations.add(ParamAdaptation.DROP_STOP)
        }

        if (wasStreaming && (
                statusCode in setOf(405, 406, 501) ||
                    "stream" in lower ||
                    (adaptations.isEmpty() && statusCode in setOf(400, 422))
                )
        ) {
            adaptations.add(ParamAdaptation.DISABLE_STREAM)
        }

        return adaptations
    }

    private fun containsRateLimitSignal(lower: String): Boolean =
        "rate limit" in lower ||
            "rate_limit" in lower ||
            "too many requests" in lower ||
            "too many concurrent" in lower ||
            "concurrent request" in lower ||
            "cooling down" in lower ||
            "overloaded" in lower ||
            "capacity" in lower ||
            "限流" in lower

    private fun containsAuthOrBillingSignal(lower: String): Boolean =
        "invalid_api_key" in lower ||
            "invalid api key" in lower ||
            "incorrect api key" in lower ||
            "insufficient_quota" in lower ||
            "insufficient credit" in lower ||
            "credit balance is too low" in lower ||
            "payment required" in lower ||
            "account_deactivated" in lower

    private fun containsModelUnavailableSignal(statusCode: Int, lower: String): Boolean =
        statusCode == 404 ||
            "model_not_found" in lower ||
            "no available channel" in lower ||
            "model does not exist" in lower ||
            "not supported" in lower && "model" in lower && "max_tokens" !in lower && "temperature" !in lower && "stream" !in lower

    private fun containsContentPolicySignal(lower: String): Boolean =
        "content_policy" in lower ||
            "content policy" in lower ||
            "data_inspection_failed" in lower ||
            "content_filter" in lower ||
            "policy violation" in lower
}

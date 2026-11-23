package icu.nullptr.polyglot.translate.cache

import icu.nullptr.polyglot.translate.openai.OpenAIEndpointNormalizer
import java.security.MessageDigest
import java.util.Locale

object CacheKeyBuilder {
    const val CACHE_VERSION = "v1"
    const val FAILURE_PLACEHOLDER = "[翻译失败]"

    private val OPENAI_PROVIDERS = setOf("openai", "openai-compatible", "custom")

    fun build(
        provider: String,
        endpoint: String,
        model: String,
        sourceLanguage: String,
        targetLanguage: String,
        text: String,
    ): String {
        val normProvider = normalizeProvider(provider)
        val normEndpoint = normalizeEndpoint(normProvider, endpoint)
        val normModel = if (normProvider in OPENAI_PROVIDERS) model.trim() else ""
        val normSrc = sourceLanguage.trim().ifEmpty { "auto" }.lowercase(Locale.ROOT)
        val normTgt = targetLanguage.trim().lowercase(Locale.ROOT)
        val normText = normalizeText(text)

        val payload = buildString(normText.length + 128) {
            appendPart(CACHE_VERSION)
            appendPart(normProvider)
            appendPart(normEndpoint)
            appendPart(normModel)
            appendPart(normSrc)
            appendPart(normTgt)
            appendPart(normText)
        }
        return sha256Hex(payload)
    }

    fun normalizeText(text: String): String =
        text.replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()

    fun isCacheableTranslation(value: String?): Boolean {
        if (value == null) return false
        val trimmed = value.trim()
        return trimmed.isNotEmpty() && trimmed != FAILURE_PLACEHOLDER
    }

    fun sha256Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val chars = CharArray(bytes.size * 2)
        val hex = "0123456789abcdef"
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            chars[i * 2] = hex[v ushr 4]
            chars[i * 2 + 1] = hex[v and 0x0F]
        }
        return String(chars)
    }

    private fun normalizeProvider(provider: String): String {
        val lower = provider.trim().lowercase(Locale.ROOT)
        return if (lower in OPENAI_PROVIDERS) "openai" else lower
    }

    private fun normalizeEndpoint(normalizedProvider: String, endpoint: String): String =
        if (normalizedProvider == "openai") {
            OpenAIEndpointNormalizer.canonicalKeyUrl(endpoint)
        } else {
            endpoint.trim().trimEnd('/')
        }

    private fun StringBuilder.appendPart(part: String) {
        append(part.length)
        append(':')
        append(part)
        append('|')
    }
}

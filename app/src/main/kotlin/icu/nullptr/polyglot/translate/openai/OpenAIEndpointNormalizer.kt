package icu.nullptr.polyglot.translate.openai

import java.net.URI
import java.util.Locale

object OpenAIEndpointNormalizer {
    const val DEFAULT_CHAT_COMPLETIONS_URL = "https://api.openai.com/v1/chat/completions"

    private val VERSIONED_PATH_SUFFIX = Regex("(?i).*/(?:v\\d+(?:beta\\d*)?|openai)$")

    fun normalize(rawEndpoint: String): String {
        val trimmed = rawEndpoint.trim()
        if (trimmed.isEmpty()) {
            return DEFAULT_CHAT_COMPLETIONS_URL
        }

        val querySplitIndex = trimmed.indexOf('?')
        val basePart = (if (querySplitIndex >= 0) trimmed.substring(0, querySplitIndex) else trimmed).trimEnd('/')
        val querySuffix = if (querySplitIndex >= 0) trimmed.substring(querySplitIndex) else ""

        if (basePart.isEmpty()) {
            return DEFAULT_CHAT_COMPLETIONS_URL
        }

        val parsedPath = runCatching { URI(basePart).path?.trimEnd('/').orEmpty() }.getOrElse {
            val schemeIdx = basePart.indexOf("://")
            val slashIdx = if (schemeIdx >= 0) basePart.indexOf('/', schemeIdx + 3) else basePart.indexOf('/')
            if (slashIdx >= 0) basePart.substring(slashIdx).trimEnd('/') else ""
        }

        val completedBase = when {
            parsedPath.endsWith("/chat/completions", ignoreCase = true) -> basePart
            parsedPath.isEmpty() -> "$basePart/v1/chat/completions"
            VERSIONED_PATH_SUFFIX.matches(parsedPath) -> "$basePart/chat/completions"
            else -> "$basePart/v1/chat/completions"
        }

        return completedBase + querySuffix
    }

    fun canonicalKeyUrl(rawEndpoint: String): String {
        val normalized = normalize(rawEndpoint)
        return runCatching {
            val uri = URI(normalized)
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return@runCatching normalized
            val host = uri.host?.lowercase(Locale.ROOT) ?: return@runCatching normalized
            val portPart = if (uri.port != -1 &&
                !((scheme == "https" && uri.port == 443) || (scheme == "http" && uri.port == 80))
            ) {
                ":${uri.port}"
            } else {
                ""
            }
            val path = uri.rawPath?.trimEnd('/').orEmpty()
            "$scheme://$host$portPart$path"
        }.getOrDefault(normalized)
    }
}

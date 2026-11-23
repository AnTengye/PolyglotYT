package icu.nullptr.polyglot.captions

data class CaptionCue(
    val videoId: String = "",
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val windowId: Int = UNKNOWN_WINDOW_ID,
) {
    fun cacheKey(): String = "${videoId}|${windowId}|${startMs}|${endMs}|${normalizedText()}"

    fun normalizedText(): String = normalize(text)

    fun durationMs(): Long =
        if (startMs >= 0L && endMs >= startMs) endMs - startMs else 0L

    companion object {
        const val UNKNOWN_TIME_MS = -1L
        const val UNKNOWN_WINDOW_ID = -1

        private val whitespace = Regex("\\s+")
        private val lineBreak = Regex("\\r?\\n")

        fun normalize(text: String): String =
            text.replace(whitespace, " ").trim()

        fun normalizeLines(text: String): List<String> =
            if ('\n' !in text && '\r' !in text) {
                val single = normalize(text)
                if (single.isEmpty()) emptyList() else listOf(single)
            } else {
                text.split(lineBreak)
                    .map(::normalize)
                    .filter { it.isNotEmpty() }
            }
    }
}

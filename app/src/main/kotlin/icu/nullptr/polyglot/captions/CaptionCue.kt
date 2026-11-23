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

        /**
         * Returns `true` when [cueText] strictly contains [fragment] (`cueText.length > fragment.length`)
         * on valid word, punctuation, or CJK character boundaries (preventing mid-word hits like `"he"` in `"the"`).
         */
        fun containsBoundedFragment(cueText: String, fragment: String): Boolean {
            if (fragment.isEmpty() || cueText.length <= fragment.length) return false

            var start = cueText.indexOf(fragment, 0)
            while (start >= 0) {
                val end = start + fragment.length
                val validLeft = start == 0 ||
                    isCjkChar(fragment[0]) ||
                    isCjkChar(cueText[start - 1]) ||
                    !isWordChar(fragment[0]) ||
                    !isWordChar(cueText[start - 1])
                val validRight = end == cueText.length ||
                    isCjkChar(fragment[fragment.length - 1]) ||
                    isCjkChar(cueText[end]) ||
                    !isWordChar(fragment[fragment.length - 1]) ||
                    !isWordChar(cueText[end])
                if (validLeft && validRight) {
                    return true
                }
                start = cueText.indexOf(fragment, start + 1)
            }
            return false
        }

        private fun isWordChar(ch: Char): Boolean =
            (ch.isLetterOrDigit() || ch == '_' || ch == '\'') && !isCjkChar(ch)

        private fun isCjkChar(ch: Char): Boolean =
            when (Character.UnicodeScript.of(ch.code)) {
                Character.UnicodeScript.HAN,
                Character.UnicodeScript.HIRAGANA,
                Character.UnicodeScript.KATAKANA,
                Character.UnicodeScript.HANGUL,
                -> true
                else -> false
            }
    }
}

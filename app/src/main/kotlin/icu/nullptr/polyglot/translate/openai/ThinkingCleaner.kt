package icu.nullptr.polyglot.translate.openai

object ThinkingCleaner {

    data class CleanResult(
        val text: String,
        val hadThinking: Boolean,
        val hadUnclosedThink: Boolean,
    )

    private val SPECIAL_STOP_MARKERS = arrayOf("<|endoftext|>", "<|im_start|>", "<|im_end|>")
    private val POSSIBLE_TAG_PREFIXES = arrayOf("<think>", "</think>", "<thinking>", "</thinking>")

    /**
     * O(N) single-pass forward scanner that strips special chat markers, paired `<think>...</think>`
     * and `<thinking>...</thinking>` blocks, orphan closing `</think>`/`</thinking>` prefixes,
     * and unclosed trailing `<think>`/`<thinking>` drafts without regex backtracking.
     */
    fun clean(raw: String): CleanResult {
        if (raw.isEmpty()) {
            return CleanResult("", hadThinking = false, hadUnclosedThink = false)
        }

        val truncated = truncateAtSpecialMarkers(raw)
        var hadThinking = false
        var hadUnclosedThink = false
        val out = StringBuilder(truncated.length)

        var cursor = 0
        while (cursor < truncated.length) {
            val openMatch = findNextOpenTag(truncated, cursor)
            if (openMatch == null) {
                out.append(truncated, cursor, truncated.length)
                break
            }

            if (openMatch.index > cursor) {
                out.append(truncated, cursor, openMatch.index)
            }
            hadThinking = true

            val contentStart = openMatch.index + openMatch.openTag.length
            val closeIndex = truncated.indexOf(openMatch.closeTag, contentStart, ignoreCase = true)
            if (closeIndex < 0) {
                hadUnclosedThink = true
                break
            }
            cursor = closeIndex + openMatch.closeTag.length
        }

        var working = out.toString()
        val orphanEnd = findLastOrphanCloseEnd(working)
        if (orphanEnd > 0) {
            hadThinking = true
            working = working.substring(orphanEnd)
        }

        return CleanResult(
            text = working.trim(),
            hadThinking = hadThinking,
            hadUnclosedThink = hadUnclosedThink,
        )
    }

    /**
     * Holds back a trailing partial `<thi...` or `</thi...` suffix during SSE streaming
     * so split tags across chunk boundaries never flash into provisional output.
     */
    fun stripTrailingPartialTag(streamingRaw: String): String {
        val lastLt = streamingRaw.lastIndexOf('<')
        if (lastLt < 0) return streamingRaw
        val tailLen = streamingRaw.length - lastLt
        if (tailLen > 11) return streamingRaw
        val tail = streamingRaw.substring(lastLt)
        val isPrefix = POSSIBLE_TAG_PREFIXES.any { tag ->
            tail.length < tag.length && tag.startsWith(tail, ignoreCase = true)
        }
        return if (isPrefix) streamingRaw.substring(0, lastLt) else streamingRaw
    }

    private fun truncateAtSpecialMarkers(text: String): String {
        var cut = text.length
        for (marker in SPECIAL_STOP_MARKERS) {
            val idx = text.indexOf(marker)
            if (idx in 0 until cut) {
                cut = idx
            }
        }
        return if (cut == text.length) text else text.substring(0, cut)
    }

    private data class OpenTagMatch(
        val index: Int,
        val openTag: String,
        val closeTag: String,
    )

    private fun findNextOpenTag(text: String, startIndex: Int): OpenTagMatch? {
        val thinkIdx = text.indexOf("<think>", startIndex, ignoreCase = true)
        val thinkingIdx = text.indexOf("<thinking>", startIndex, ignoreCase = true)
        return when {
            thinkIdx < 0 && thinkingIdx < 0 -> null
            thinkIdx >= 0 && (thinkingIdx < 0 || thinkIdx < thinkingIdx) ->
                OpenTagMatch(thinkIdx, "<think>", "</think>")
            else ->
                OpenTagMatch(thinkingIdx, "<thinking>", "</thinking>")
        }
    }

    private fun findLastOrphanCloseEnd(text: String): Int {
        val closeThink = text.lastIndexOf("</think>", ignoreCase = true)
        val closeThinking = text.lastIndexOf("</thinking>", ignoreCase = true)
        val endThink = if (closeThink >= 0) closeThink + "</think>".length else -1
        val endThinking = if (closeThinking >= 0) closeThinking + "</thinking>".length else -1
        return maxOf(endThink, endThinking)
    }
}

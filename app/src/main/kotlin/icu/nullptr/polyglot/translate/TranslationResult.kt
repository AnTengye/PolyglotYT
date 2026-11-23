package icu.nullptr.polyglot.translate

data class TranslationResult(
    val texts: List<String>,
    val indexedTexts: Map<Int, String> = defaultIndexedTexts(texts),
) {
    fun missingIndices(originals: List<String>): List<Int> =
        originals.indices.filter { index ->
            originals[index].isNotBlank() && indexedTexts[index].isNullOrBlank()
        }

    companion object {
        fun aligned(
            originals: List<String>,
            translatedByIndex: Map<Int, String>,
        ): TranslationResult {
            val cleanIndexed = LinkedHashMap<Int, String>(originals.size)
            val alignedTexts = originals.mapIndexed { index, original ->
                if (original.isBlank()) {
                    cleanIndexed[index] = original
                    original
                } else {
                    val translated = translatedByIndex[index]?.trim().orEmpty()
                    if (translated.isNotEmpty()) {
                        cleanIndexed[index] = translated
                    }
                    translated
                }
            }
            return TranslationResult(
                texts = alignedTexts,
                indexedTexts = cleanIndexed,
            )
        }

        private fun defaultIndexedTexts(texts: List<String>): Map<Int, String> {
            val map = LinkedHashMap<Int, String>(texts.size)
            texts.forEachIndexed { index, text ->
                if (text.isNotEmpty()) {
                    map[index] = text
                }
            }
            return map
        }
    }
}

package icu.nullptr.polyglot.translate.openai

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import icu.nullptr.polyglot.translate.cache.CacheKeyBuilder
import java.util.Locale

object OpenAIResponseParser {

    data class ParsedBatch(
        val indexed: Map<Int, String>,
        val hadThinking: Boolean,
        val hadUnclosedThink: Boolean,
    )

    private val ARRAY_CONTAINER_KEYS = arrayOf(
        "translations",
        "items",
        "results",
        "data",
        "subtitles",
        "events",
        "output",
    )

    private val INDEX_KEYS = arrayOf("i", "index", "id", "idx")

    private val TARGET_TEXT_KEYS = arrayOf(
        "t",
        "translatedText",
        "translation",
        "text",
        "content",
        "result",
        "output",
        "response",
        "message",
        "targetText",
        "target",
        "dst",
        "译文",
        "翻译",
        "中文文本",
        "当前译文",
        "翻译结果",
    )

    private val SOURCE_KEY_PATTERNS = arrayOf("source", "original", "src", "原", "原文")
    private val NON_TEXT_KEYS = setOf("i", "id", "idx", "index", "s")
    private val NUMBERED_LINE_PREFIX = Regex("^\\s*(?:\\[(\\d{1,3})]|\\((\\d{1,3})\\)|(\\d{1,3})\\s*[.。、:：)])\\s*(.+)$")
    private val FENCE_HEADER = Regex("^```(?:json)?\\s*", RegexOption.IGNORE_CASE)
    private val FENCE_FOOTER = Regex("\\s*```$")

    fun parseBatch(rawContent: String, expectedCount: Int): ParsedBatch {
        if (expectedCount <= 0) {
            return ParsedBatch(emptyMap(), hadThinking = false, hadUnclosedThink = false)
        }

        // Level 1: Strip stop tokens, <think>/<thinking> blocks, and markdown code fences.
        val cleanResult = ThinkingCleaner.clean(rawContent)
        val cleaned = stripCodeFences(cleanResult.text)
        if (cleaned.isBlank()) {
            return ParsedBatch(
                indexed = emptyMap(),
                hadThinking = cleanResult.hadThinking,
                hadUnclosedThink = cleanResult.hadUnclosedThink,
            )
        }

        // Level 2 & 3: Direct JSON array / wrapper object / polymorphic item extraction.
        tryExtractJson(cleaned, expectedCount)?.takeIf { it.isNotEmpty() }?.let { aligned ->
            return ParsedBatch(aligned, cleanResult.hadThinking, cleanResult.hadUnclosedThink)
        }

        // Level 4a: Embedded JSON array/object extraction when prose surrounds JSON.
        val bracketStart = cleaned.indexOf('[')
        val bracketEnd = cleaned.lastIndexOf(']')
        if (bracketStart >= 0 && bracketEnd > bracketStart) {
            tryExtractJson(cleaned.substring(bracketStart, bracketEnd + 1), expectedCount)
                ?.takeIf { it.isNotEmpty() }
                ?.let { aligned ->
                    return ParsedBatch(aligned, cleanResult.hadThinking, cleanResult.hadUnclosedThink)
                }
        }

        val braceStart = cleaned.indexOf('{')
        val braceEnd = cleaned.lastIndexOf('}')
        if (braceStart >= 0 && braceEnd > braceStart) {
            tryExtractJson(cleaned.substring(braceStart, braceEnd + 1), expectedCount)
                ?.takeIf { it.isNotEmpty() }
                ?.let { aligned ->
                    return ParsedBatch(aligned, cleanResult.hadThinking, cleanResult.hadUnclosedThink)
                }
        }

        // Level 4b: Plaintext single-item or numbered/exact-count line fallback.
        val fallbackAligned = parsePlaintextFallback(cleaned, expectedCount)
        return ParsedBatch(
            indexed = fallbackAligned,
            hadThinking = cleanResult.hadThinking,
            hadUnclosedThink = cleanResult.hadUnclosedThink,
        )
    }

    /**
     * Zero-regex brace/quote scanner that extracts already-closed JSON items from an in-flight
     * SSE accumulation buffer after stripping `<think>` blocks and trailing partial tags.
     */
    fun extractProvisionalItems(rawStreamContent: String, expectedCount: Int): Map<Int, String> {
        if (expectedCount <= 0 || rawStreamContent.isEmpty()) return emptyMap()

        val safeRaw = ThinkingCleaner.stripTrailingPartialTag(rawStreamContent)
        val cleaned = stripCodeFences(ThinkingCleaner.clean(safeRaw).text)
        if (cleaned.isEmpty()) return emptyMap()

        val arrayStart = findArrayOpenBracket(cleaned)
        if (arrayStart < 0) return emptyMap()

        val results = LinkedHashMap<Int, String>()
        val rawObjects = ArrayList<JsonObject>()
        var inString = false
        var escaped = false
        var braceDepth = 0
        var objStart = -1
        var strStart = -1
        var stringSeqIndex = 0

        for (pos in (arrayStart + 1) until cleaned.length) {
            val ch = cleaned[pos]
            if (escaped) {
                escaped = false
                continue
            }
            if (inString) {
                if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                    if (braceDepth == 0 && strStart >= 0) {
                        val token = cleaned.substring(strStart, pos + 1)
                        val parsedStr = runCatching { JsonParser.parseString(token).asString.trim() }.getOrNull()
                        val slot = stringSeqIndex++
                        if (slot in 0 until expectedCount && CacheKeyBuilder.isCacheableTranslation(parsedStr)) {
                            results[slot] = parsedStr!!
                        }
                        strStart = -1
                    }
                }
                continue
            }

            when (ch) {
                '"' -> {
                    inString = true
                    if (braceDepth == 0) {
                        strStart = pos
                    }
                }
                '{' -> {
                    if (braceDepth == 0) {
                        objStart = pos
                    }
                    braceDepth++
                }
                '}' -> {
                    if (braceDepth > 0) {
                        braceDepth--
                        if (braceDepth == 0 && objStart >= 0) {
                            val objSlice = cleaned.substring(objStart, pos + 1)
                            runCatching { JsonParser.parseString(objSlice) }
                                .getOrNull()
                                ?.takeIf { it.isJsonObject }
                                ?.asJsonObject
                                ?.let { rawObjects.add(it) }
                            objStart = -1
                        }
                    }
                }
                ']' -> {
                    if (braceDepth == 0) break
                }
            }
        }

        if (rawObjects.isNotEmpty()) {
            val indexShift = resolveIndexShift(rawObjects, expectedCount)
            rawObjects.forEachIndexed { pos, obj ->
                val rawIdx = extractExplicitIndex(obj)
                val targetIdx = if (rawIdx != null) rawIdx + indexShift else pos
                if (targetIdx in 0 until expectedCount) {
                    val value = extractTextFromObject(obj)
                    if (CacheKeyBuilder.isCacheableTranslation(value)) {
                        results[targetIdx] = value!!.trim()
                    }
                }
            }
        }

        return results
    }

    private fun findArrayOpenBracket(cleaned: String): Int {
        val firstNonWs = cleaned.indexOfFirst { !it.isWhitespace() }
        if (firstNonWs < 0) return -1
        if (cleaned[firstNonWs] == '[') return firstNonWs
        if (cleaned[firstNonWs] == '{') {
            return cleaned.indexOf('[', firstNonWs + 1)
        }
        return -1
    }

    private fun tryExtractJson(candidate: String, expectedCount: Int): Map<Int, String>? {
        val root = runCatching { JsonParser.parseString(candidate) }.getOrNull() ?: return null
        return extractFromElement(root, expectedCount)
    }

    private fun extractFromElement(element: JsonElement, expectedCount: Int): Map<Int, String> {
        if (element.isJsonArray) {
            return extractFromArray(element.asJsonArray, expectedCount)
        }
        if (element.isJsonObject) {
            val obj = element.asJsonObject
            for (key in ARRAY_CONTAINER_KEYS) {
                val child = obj.get(key)
                if (child != null && child.isJsonArray) {
                    val extracted = extractFromArray(child.asJsonArray, expectedCount)
                    if (extracted.isNotEmpty()) return extracted
                }
            }

            // Support numeric-keyed map object: {"0": "...", "1": "..."}
            val numericMap = extractNumericKeyedObject(obj, expectedCount)
            if (numericMap.isNotEmpty()) return numericMap

            if (expectedCount == 1) {
                val single = extractTextFromObject(obj)
                if (CacheKeyBuilder.isCacheableTranslation(single)) {
                    return mapOf(0 to single!!.trim())
                }
            }
        }
        return emptyMap()
    }

    private fun extractNumericKeyedObject(obj: JsonObject, expectedCount: Int): Map<Int, String> {
        val entries = ArrayList<Pair<Int, String>>()
        for ((key, value) in obj.entrySet()) {
            val intKey = key.trim().toIntOrNull() ?: continue
            val text = extractScalarOrObjectText(value) ?: continue
            if (CacheKeyBuilder.isCacheableTranslation(text)) {
                entries.add(intKey to text.trim())
            }
        }
        if (entries.isEmpty()) return emptyMap()

        val hasZero = entries.any { it.first == 0 }
        val shift = if (!hasZero && entries.all { it.first in 1..expectedCount } && entries.any { it.first == expectedCount }) -1 else 0
        val aligned = LinkedHashMap<Int, String>()
        for ((rawKey, text) in entries) {
            val idx = rawKey + shift
            if (idx in 0 until expectedCount) {
                aligned[idx] = text
            }
        }
        return aligned
    }

    private fun extractFromArray(array: JsonArray, expectedCount: Int): Map<Int, String> {
        if (array.isEmpty) return emptyMap()

        // Single-element structured block expansion (when model packed N lines into ["1. ...\n2. ..."]).
        if (array.size() == 1 && expectedCount > 1 && array[0].isJsonPrimitive) {
            val singleBlock = array[0].asString
            if ('\n' in singleBlock) {
                val expanded = parsePlaintextFallback(singleBlock, expectedCount)
                if (expanded.isNotEmpty()) return expanded
            }
        }

        val objectItems = array.mapNotNull { if (it != null && it.isJsonObject) it.asJsonObject else null }
        val indexShift = resolveIndexShift(objectItems, expectedCount)

        val result = LinkedHashMap<Int, String>(expectedCount)
        for (pos in 0 until array.size()) {
            val item = array[pos] ?: continue
            when {
                item.isJsonPrimitive -> {
                    if (pos in 0 until expectedCount) {
                        val text = primitiveToString(item.asJsonPrimitive).trim()
                        if (CacheKeyBuilder.isCacheableTranslation(text)) {
                            result[pos] = text
                        }
                    }
                }
                item.isJsonObject -> {
                    val obj = item.asJsonObject
                    val explicitIdx = extractExplicitIndex(obj)
                    val targetIdx = if (explicitIdx != null) explicitIdx + indexShift else pos
                    if (targetIdx in 0 until expectedCount) {
                        val text = extractTextFromObject(obj)
                        if (CacheKeyBuilder.isCacheableTranslation(text)) {
                            result[targetIdx] = text!!.trim()
                        }
                    }
                }
            }
        }
        return result
    }

    private fun resolveIndexShift(objects: List<JsonObject>, expectedCount: Int): Int {
        if (objects.isEmpty()) return 0
        val explicitIndices = objects.mapNotNull { extractExplicitIndex(it) }
        if (explicitIndices.isEmpty()) return 0
        val hasZero = explicitIndices.any { it == 0 }
        return if (!hasZero && explicitIndices.all { it in 1..expectedCount } && explicitIndices.contains(expectedCount)) -1 else 0
    }

    private fun extractExplicitIndex(obj: JsonObject): Int? {
        for (key in INDEX_KEYS) {
            val el = obj.get(key) ?: continue
            if (el.isJsonPrimitive) {
                val prim = el.asJsonPrimitive
                if (prim.isNumber) return runCatching { prim.asInt }.getOrNull()
                if (prim.isString) return prim.asString.trim().toIntOrNull()
            }
        }
        return null
    }

    private fun extractScalarOrObjectText(element: JsonElement?): String? {
        if (element == null || element.isJsonNull) return null
        if (element.isJsonPrimitive) {
            val text = primitiveToString(element.asJsonPrimitive).trim()
            return text.takeIf { it.isNotEmpty() }
        }
        if (element.isJsonObject) {
            return extractTextFromObject(element.asJsonObject)
        }
        return null
    }

    private fun extractTextFromObject(obj: JsonObject, depth: Int = 0): String? {
        if (depth > 2) return null

        for (key in TARGET_TEXT_KEYS) {
            val candidate = obj.get(key) ?: continue
            val extracted = extractValueAtDepth(candidate, depth + 1)
            if (!extracted.isNullOrBlank()) return extracted
        }

        // Fallback: pick first non-source, non-index string field (never return "原文"/"source"/"s").
        for ((key, value) in obj.entrySet()) {
            if (isExcludedFallbackKey(key)) continue
            val extracted = extractValueAtDepth(value, depth + 1)
            if (!extracted.isNullOrBlank()) return extracted
        }

        return null
    }

    private fun extractValueAtDepth(element: JsonElement?, depth: Int): String? {
        if (element == null || element.isJsonNull) return null
        if (element.isJsonPrimitive) {
            val str = primitiveToString(element.asJsonPrimitive).trim()
            return str.takeIf { it.isNotEmpty() }
        }
        if (element.isJsonObject && depth <= 2) {
            return extractTextFromObject(element.asJsonObject, depth)
        }
        return null
    }

    internal fun isSourceKey(key: String): Boolean {
        val trimmed = key.trim()
        val lower = trimmed.lowercase(Locale.ROOT)
        if (lower == "s") return true
        return SOURCE_KEY_PATTERNS.any { pattern ->
            lower.contains(pattern) || trimmed.contains(pattern)
        }
    }

    private fun isExcludedFallbackKey(key: String): Boolean {
        val lower = key.trim().lowercase(Locale.ROOT)
        return lower in NON_TEXT_KEYS || isSourceKey(key)
    }

    private fun primitiveToString(primitive: JsonPrimitive): String =
        when {
            primitive.isString -> primitive.asString
            primitive.isNumber -> primitive.asNumber.toString()
            primitive.isBoolean -> primitive.asBoolean.toString()
            else -> ""
        }

    private fun parsePlaintextFallback(cleaned: String, expectedCount: Int): Map<Int, String> {
        val trimmed = cleaned.trim()
        if (trimmed.isEmpty()) return emptyMap()

        if (expectedCount == 1) {
            if (trimmed.startsWith("[") || trimmed.startsWith("{")) return emptyMap()
            val single = stripNumberPrefix(trimmed)
            return if (CacheKeyBuilder.isCacheableTranslation(single)) {
                mapOf(0 to single)
            } else {
                emptyMap()
            }
        }

        val rawLines = trimmed.split('\n')
            .map { it.trim() }
            .filter { line ->
                line.isNotEmpty() &&
                    !(line.startsWith("【") && line.endsWith("】")) &&
                    !(line.startsWith("[") && line.endsWith("]") && !line.startsWith("[0") && !line.startsWith("[1"))
            }

        // Prefer explicit numbered lines ("1. ...", "[0] ...") to preserve exact index alignment.
        val numberedEntries = ArrayList<Pair<Int, String>>()
        for (line in rawLines) {
            val match = NUMBERED_LINE_PREFIX.matchEntire(line) ?: continue
            val rawNum = (match.groupValues[1].ifEmpty { match.groupValues[2].ifEmpty { match.groupValues[3] } })
                .toIntOrNull() ?: continue
            val body = match.groupValues[4].trim()
            if (CacheKeyBuilder.isCacheableTranslation(body)) {
                numberedEntries.add(rawNum to body)
            }
        }
        if (numberedEntries.isNotEmpty()) {
            val hasZero = numberedEntries.any { it.first == 0 }
            val shift = if (!hasZero && numberedEntries.all { it.first in 1..expectedCount }) -1 else 0
            val aligned = LinkedHashMap<Int, String>()
            for ((num, body) in numberedEntries) {
                val idx = num + shift
                if (idx in 0 until expectedCount) {
                    aligned[idx] = body
                }
            }
            if (aligned.isNotEmpty()) return aligned
        }

        if (rawLines.size == expectedCount && !trimmed.startsWith("[") && !trimmed.startsWith("{")) {
            val aligned = LinkedHashMap<Int, String>(expectedCount)
            rawLines.forEachIndexed { idx, line ->
                if (CacheKeyBuilder.isCacheableTranslation(line)) {
                    aligned[idx] = line
                }
            }
            return aligned
        }

        return emptyMap()
    }

    private fun stripNumberPrefix(line: String): String {
        val match = NUMBERED_LINE_PREFIX.matchEntire(line)
        return if (match != null) match.groupValues[4].trim() else line.trim()
    }

    internal fun stripCodeFences(text: String): String {
        var working = text.trim()
        if (working.startsWith("```")) {
            working = working.replaceFirst(FENCE_HEADER, "")
                .replaceFirst(FENCE_FOOTER, "")
                .trim()
        }
        if ("```" in working) {
            working = working
                .replace(Regex("```json", RegexOption.IGNORE_CASE), "")
                .replace("```", "")
                .trim()
        }
        return working
    }
}

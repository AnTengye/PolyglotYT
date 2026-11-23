package icu.nullptr.polyglot.translate.openai

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import icu.nullptr.polyglot.translate.RequestControl
import java.io.BufferedReader
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.CancellationException

object OpenAISseReader {
    const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024 // 2 MiB cap to protect host YouTube heap

    data class RawCompletion(
        val content: String,
        val sawReasoning: Boolean,
        val finishReason: String?,
        val hasChoices: Boolean,
        val wasSseStream: Boolean,
    )

    fun readResponse(
        inputStream: InputStream,
        contentType: String,
        requestedStream: Boolean,
        expectedCount: Int = 0,
        slotToOriginalIndex: IntArray? = null,
        control: RequestControl? = null,
    ): RawCompletion {
        val limited = LimitedInputStream(inputStream, MAX_RESPONSE_BYTES)
        val lowerCt = contentType.lowercase()

        if ("text/html" in lowerCt) {
            val htmlSnippet = limited.bufferedReader(Charsets.UTF_8).use { it.readText() }
            throw OpenAIErrorClassifier.classifyHttpError(
                statusCode = 502,
                rawBody = htmlSnippet,
                contentType = contentType,
                wasStreaming = requestedStream,
            )
        }

        val isExplicitJson = "application/json" in lowerCt && "text/event-stream" !in lowerCt
        if (isExplicitJson || (!requestedStream && "text/event-stream" !in lowerCt)) {
            val bodyText = limited.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val trimmed = bodyText.trimStart()
            if (trimmed.startsWith("data:")) {
                return parseSseLines(
                    reader = bodyText.reader().buffered(),
                    requestedStream = requestedStream,
                    expectedCount = expectedCount,
                    slotToOriginalIndex = slotToOriginalIndex,
                    control = control,
                )
            }
            return parseNonStreamJson(bodyText, contentType, requestedStream)
        }

        return BufferedReader(InputStreamReader(limited, Charsets.UTF_8)).use { reader ->
            parseSseLines(
                reader = reader,
                requestedStream = requestedStream,
                expectedCount = expectedCount,
                slotToOriginalIndex = slotToOriginalIndex,
                control = control,
            )
        }
    }

    private fun parseSseLines(
        reader: BufferedReader,
        requestedStream: Boolean,
        expectedCount: Int,
        slotToOriginalIndex: IntArray?,
        control: RequestControl?,
    ): RawCompletion {
        var sawSseData = false
        var hasChoices = false
        var sawReasoning = false
        var finishReason: String? = null
        val contentBuilder = StringBuilder()
        val nonSseFallback = StringBuilder()
        val emittedSlots = HashSet<Int>()

        while (true) {
            if (control?.isCancelled() == true) {
                throw CancellationException("Translation stream cancelled")
            }

            val line = reader.readLine() ?: break
            val trimmed = line.trim()

            if (!sawSseData) {
                if (!trimmed.startsWith("data:")) {
                    if (nonSseFallback.length < 262_144) {
                        nonSseFallback.append(line).append('\n')
                    }
                    continue
                }
                sawSseData = true
                nonSseFallback.setLength(0)
            }

            if (trimmed.isEmpty() || trimmed.startsWith(":") || trimmed.startsWith("event:")) {
                continue
            }
            if (!trimmed.startsWith("data:")) {
                continue
            }

            val payload = trimmed.substring(5).trim()
            if (payload.isEmpty()) continue
            if (payload == "[DONE]") {
                break
            }

            val chunkObj = runCatching { JsonParser.parseString(payload) }
                .getOrNull()
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?: continue

            checkForEmbeddedError(chunkObj, wasStreaming = true)

            val choices = chunkObj.get("choices")?.takeIf { it.isJsonArray }?.asJsonArray
            if (choices != null && !choices.isEmpty) {
                hasChoices = true
                val firstChoice = choices[0]?.takeIf { it.isJsonObject }?.asJsonObject
                val fr = firstChoice?.get("finish_reason")?.takeIf { it.isJsonPrimitive }?.asString
                if (!fr.isNullOrBlank()) {
                    finishReason = fr
                }

                val delta = firstChoice?.get("delta")?.takeIf { it.isJsonObject }?.asJsonObject
                    ?: firstChoice?.get("message")?.takeIf { it.isJsonObject }?.asJsonObject

                if (delta != null) {
                    if (hasReasoningField(delta)) {
                        sawReasoning = true
                    }
                    val deltaText = extractContentText(delta.get("content"))
                    if (deltaText.isNotEmpty()) {
                        contentBuilder.append(deltaText)
                        if (control != null && expectedCount > 0 && !control.isCancelled()) {
                            val closedItems = OpenAIResponseParser.extractProvisionalItems(
                                rawStreamContent = contentBuilder.toString(),
                                expectedCount = expectedCount,
                            )
                            for ((slot, translated) in closedItems) {
                                if (emittedSlots.add(slot)) {
                                    val originalIdx = if (slotToOriginalIndex != null && slot in slotToOriginalIndex.indices) {
                                        slotToOriginalIndex[slot]
                                    } else {
                                        slot
                                    }
                                    if (!control.isCancelled()) {
                                        control.onProvisional(originalIdx, translated)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (!sawSseData) {
            val raw = nonSseFallback.toString().trim()
            if (raw.isEmpty()) {
                return RawCompletion(
                    content = "",
                    sawReasoning = false,
                    finishReason = null,
                    hasChoices = false,
                    wasSseStream = false,
                )
            }
            // Fallback 1: Gateway ignored stream=true and returned plain Chat Completion JSON.
            return parseNonStreamJson(raw, contentType = "", requestedStream = requestedStream)
        }

        return RawCompletion(
            content = contentBuilder.toString(),
            sawReasoning = sawReasoning,
            finishReason = finishReason,
            hasChoices = hasChoices,
            wasSseStream = true,
        )
    }

    internal fun parseNonStreamJson(
        bodyText: String,
        contentType: String,
        requestedStream: Boolean,
    ): RawCompletion {
        val trimmed = bodyText.trim()
        if (trimmed.isEmpty()) {
            throw OpenAIRequestException(
                category = OpenAIErrorCategory.INVALID_RESULT,
                statusCode = 200,
                message = "OpenAI returned empty HTTP 200 body",
            )
        }
        if (trimmed.startsWith("<!DOCTYPE", ignoreCase = true) || trimmed.startsWith("<html", ignoreCase = true)) {
            throw OpenAIErrorClassifier.classifyHttpError(
                statusCode = 502,
                rawBody = trimmed,
                contentType = contentType.ifEmpty { "text/html" },
                wasStreaming = requestedStream,
            )
        }

        val root = runCatching { JsonParser.parseString(trimmed) }
            .getOrNull()
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: throw OpenAIRequestException(
                category = OpenAIErrorCategory.INVALID_RESULT,
                statusCode = 200,
                message = "OpenAI returned malformed JSON response",
            )

        checkForEmbeddedError(root, wasStreaming = requestedStream)

        val choices = root.get("choices")?.takeIf { it.isJsonArray }?.asJsonArray
        if (choices == null || choices.isEmpty) {
            return RawCompletion(
                content = "",
                sawReasoning = false,
                finishReason = null,
                hasChoices = false,
                wasSseStream = false,
            )
        }

        val choice = choices[0]?.takeIf { it.isJsonObject }?.asJsonObject
        val finishReason = choice?.get("finish_reason")?.takeIf { it.isJsonPrimitive }?.asString
        val message = choice?.get("message")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: choice?.get("delta")?.takeIf { it.isJsonObject }?.asJsonObject

        val content = extractContentText(message?.get("content"))
        val sawReasoning = message != null && hasReasoningField(message)

        return RawCompletion(
            content = content,
            sawReasoning = sawReasoning,
            finishReason = finishReason,
            hasChoices = true,
            wasSseStream = false,
        )
    }

    private fun checkForEmbeddedError(obj: JsonObject, wasStreaming: Boolean) {
        val errorEl = obj.get("error") ?: return
        if (errorEl.isJsonNull) return
        if (errorEl.isJsonObject) {
            throw OpenAIErrorClassifier.classifyEmbeddedError(errorEl.asJsonObject, wasStreaming)
        }
        if (errorEl.isJsonPrimitive) {
            val msg = errorEl.asString.trim()
            if (msg.isNotEmpty() && msg != "false") {
                throw OpenAIErrorClassifier.classifyHttpError(
                    statusCode = 502,
                    rawBody = msg,
                    wasStreaming = wasStreaming,
                )
            }
        }
    }

    internal fun extractContentText(contentElement: JsonElement?): String {
        if (contentElement == null || contentElement.isJsonNull) return ""
        if (contentElement.isJsonPrimitive) {
            val prim = contentElement.asJsonPrimitive
            return if (prim.isString) prim.asString else prim.toString()
        }
        if (contentElement.isJsonArray) {
            val sb = StringBuilder()
            for (part in contentElement.asJsonArray) {
                if (part == null || part.isJsonNull) continue
                if (part.isJsonPrimitive && part.asJsonPrimitive.isString) {
                    sb.append(part.asString)
                } else if (part.isJsonObject) {
                    val partObj = part.asJsonObject
                    val type = partObj.get("type")?.takeIf { it.isJsonPrimitive }?.asString
                    if (type == "thinking" || partObj.get("thought")?.takeIf { it.isJsonPrimitive }?.asBoolean == true) {
                        continue
                    }
                    val text = partObj.get("text")?.takeIf { it.isJsonPrimitive }?.asString
                    if (text != null) {
                        sb.append(text)
                    }
                }
            }
            return sb.toString()
        }
        return ""
    }

    private fun hasReasoningField(messageOrDelta: JsonObject): Boolean {
        for (field in arrayOf("reasoning_content", "reasoning")) {
            val el = messageOrDelta.get(field)
            if (el != null && el.isJsonPrimitive && el.asString.isNotBlank()) {
                return true
            }
        }
        val details = messageOrDelta.get("reasoning_details")
        if (details != null && details.isJsonArray && !details.asJsonArray.isEmpty) {
            return true
        }
        return false
    }

    private class LimitedInputStream(
        delegate: InputStream,
        private val maxBytes: Int,
    ) : FilterInputStream(delegate) {
        private var bytesRead = 0

        override fun read(): Int {
            val b = super.read()
            if (b != -1) {
                incrementAndCheck(1)
            }
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val count = super.read(b, off, len)
            if (count > 0) {
                incrementAndCheck(count)
            }
            return count
        }

        private fun incrementAndCheck(delta: Int) {
            bytesRead += delta
            if (bytesRead > maxBytes) {
                throw IOException("OpenAI response exceeded maximum allowed size ($maxBytes bytes)")
            }
        }
    }
}

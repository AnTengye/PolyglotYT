package icu.nullptr.polyglot.translate.openai

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAIEndpointAndErrorTest {

    @Test
    fun `OpenAIEndpointNormalizer handles bare hosts, versioned relays, and query params`() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            OpenAIEndpointNormalizer.normalize(""),
        )
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            OpenAIEndpointNormalizer.normalize("https://api.deepseek.com/"),
        )
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            OpenAIEndpointNormalizer.normalize("https://open.bigmodel.cn/api/paas/v4"),
        )
        assertEquals(
            "https://ark.cn-beijing.volces.com/api/v3/chat/completions",
            OpenAIEndpointNormalizer.normalize("https://ark.cn-beijing.volces.com/api/v3/"),
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            OpenAIEndpointNormalizer.normalize("https://generativelanguage.googleapis.com/v1beta/openai"),
        )
        assertEquals(
            "https://example.openai.azure.com/openai/deployments/gpt4/chat/completions?api-version=2024-06-01",
            OpenAIEndpointNormalizer.normalize(
                "https://example.openai.azure.com/openai/deployments/gpt4/chat/completions/?api-version=2024-06-01",
            ),
        )
    }

    @Test
    fun `OpenAIErrorClassifier classifies auth, Retry-After cap, Cloudflare HTML, and adaptations`() {
        val authErr = OpenAIErrorClassifier.classifyHttpError(
            statusCode = 401,
            rawBody = """{"error":{"message":"Incorrect API key provided","code":"invalid_api_key"}}""",
        )
        assertEquals(OpenAIErrorCategory.FATAL_AUTH_BILLING, authErr.category)

        val waf403 = OpenAIErrorClassifier.classifyHttpError(
            statusCode = 403,
            rawBody = "<html><head><title>Just a moment... | Cloudflare</title></head></html>",
            contentType = "text/html",
        )
        assertEquals(OpenAIErrorCategory.PARAM_OR_MODEL_REJECT, waf403.category)
        assertTrue(waf403.message!!.contains("Just a moment"))

        val rlShort = OpenAIErrorClassifier.classifyHttpError(
            statusCode = 429,
            rawBody = """{"error":{"message":"Rate limit reached"}}""",
            retryAfterHeader = "2.5",
        )
        assertEquals(OpenAIErrorCategory.RATE_LIMIT_OVERLOAD, rlShort.category)
        assertEquals(2500L, rlShort.retryAfterMs)
        assertFalse(rlShort.retryAfterExceededCap)

        val rlTooLong = OpenAIErrorClassifier.classifyHttpError(
            statusCode = 429,
            rawBody = "Too many requests",
            retryAfterHeader = "45",
        )
        assertTrue(rlTooLong.retryAfterExceededCap)

        val param400 = OpenAIErrorClassifier.classifyHttpError(
            statusCode = 400,
            rawBody = """{"error":{"message":"Unsupported parameter: 'max_tokens' is not supported with this model. Use 'max_completion_tokens' instead."}}""",
            wasStreaming = false,
        )
        assertEquals(OpenAIErrorCategory.PARAM_OR_MODEL_REJECT, param400.category)
        assertTrue(ParamAdaptation.USE_MAX_COMPLETION_TOKENS in param400.adaptations)

        val modelMissing = OpenAIErrorClassifier.classifyHttpError(
            statusCode = 503,
            rawBody = """{"error":{"message":"No available channel for model gpt-99"}}""",
        )
        // 503 goes to server retry unless 4xx/404; verify 400 no available channel is modelUnavailable:
        val relayNoChannel = OpenAIErrorClassifier.classifyHttpError(
            statusCode = 400,
            rawBody = """{"error":{"message":"no available channel for model gpt-99"}}""",
        )
        assertTrue(relayNoChannel.isModelUnavailable)

        val embeddedRl = OpenAIErrorClassifier.classifyEmbeddedError(
            JsonParser.parseString("""{"code":429,"message":"Too many concurrent requests"}""").asJsonObject,
            wasStreaming = true,
        )
        assertEquals(OpenAIErrorCategory.RATE_LIMIT_OVERLOAD, embeddedRl.category)
    }
}

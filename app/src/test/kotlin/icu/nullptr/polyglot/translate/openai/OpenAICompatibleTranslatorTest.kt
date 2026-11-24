package icu.nullptr.polyglot.translate.openai

import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import icu.nullptr.polyglot.translate.RequestControl
import icu.nullptr.polyglot.translate.TranslationRequest
import icu.nullptr.polyglot.translate.cache.BoundedMemoryDiskStore
import icu.nullptr.polyglot.translate.cache.TranslationCache
import icu.nullptr.polyglot.translate.providers.OpenAICompatibleTranslator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class OpenAICompatibleTranslatorTest {

    private lateinit var server: HttpServer
    private var baseUrl: String = ""
    private val recordedSleeps = CopyOnWriteArrayList<Long>()

    @Before
    fun setUp() {
        EndpointCapabilities.clear()
        OpenAICompatibleTranslator.cache = null
        recordedSleeps.clear()
        OpenAICompatibleTranslator.sleeper = { ms -> recordedSleeps.add(ms) }

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun tearDown() {
        server.stop(0)
        EndpointCapabilities.clear()
        OpenAICompatibleTranslator.cache = null
    }

    @Test
    fun `SSE streaming batch translates 1-to-1, strips think, ignores reasoning_content, and fires provisional callbacks`() {
        var capturedRequestJson = ""
        var capturedPath = ""
        var capturedUa = ""

        server.createContext("/v1/chat/completions") { exchange ->
            capturedPath = exchange.requestURI.path
            capturedUa = exchange.requestHeaders.getFirst("User-Agent").orEmpty()
            capturedRequestJson = exchange.requestBody.bufferedReader(Charsets.UTF_8).readText()

            exchange.responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.bufferedWriter(Charsets.UTF_8).use { writer ->
                val frames = listOf(
                    """data: {"choices":[{"delta":{"reasoning_content":"internal thought should be ignored"}}]}""",
                    """data: {"choices":[{"delta":{"content":"<think>drafting "}}]}""",
                    """data: {"choices":[{"delta":{"content":"subtitle</think>[{\"i\":0,\"t\":\"你好\"},"}}]}""",
                    """data: {"choices":[{"delta":{"content":"{\"i\":1,\"t\":\"世界\"}]"}}]}""",
                    """data: [DONE]""",
                )
                for (frame in frames) {
                    writer.write(frame)
                    writer.write("\n\n")
                    writer.flush()
                }
            }
        }

        val provisionals = CopyOnWriteArrayList<Pair<Int, String>>()
        val bodySentCalled = AtomicBoolean(false)
        val connectionSeen = AtomicBoolean(false)

        val control = object : RequestControl {
            override fun onConnection(connection: HttpURLConnection?) {
                if (connection != null) connectionSeen.set(true)
            }

            override fun onRequestBodySent() {
                bodySentCalled.set(true)
            }

            override fun onProvisional(index: Int, translated: String) {
                provisionals.add(index to translated)
            }
        }

        val request = TranslationRequest(
            texts = listOf("Hello", "", "World"),
            sourceLanguage = "en",
            targetLanguage = "zh-Hans",
            contextBefore = "Speaker greeted audience.",
            contextAfter = "Applause follows.",
            enableStreaming = true,
            control = control,
        )

        val result = OpenAICompatibleTranslator.translateWithConfig(
            request = request,
            config = OpenAICompatibleTranslator.ClientConfig(
                endpoint = baseUrl, // bare host auto-completes to /v1/chat/completions
                apiKey = "sk-test-key",
                model = "deepseek-chat",
            ),
        )

        assertEquals("/v1/chat/completions", capturedPath)
        assertTrue(capturedUa.contains("Mozilla/5.0"))
        assertTrue(connectionSeen.get())
        assertTrue(bodySentCalled.get())
        // Notice original index 1 was blank "", so slot 0 -> index 0 ("你好"), slot 1 -> index 2 ("世界")!
        assertEquals(listOf("你好", "", "世界"), result.texts)
        assertEquals(listOf(0 to "你好", 2 to "世界"), provisionals.toList())

        val reqObj = JsonParser.parseString(capturedRequestJson).asJsonObject
        assertEquals(0.3, reqObj.get("temperature").asDouble, 0.001)
        assertTrue(reqObj.get("stream").asBoolean)
        assertNotNull(reqObj.get("thinking"))
        val userMsg = reqObj.getAsJsonArray("messages")[1].asJsonObject.get("content").asString
        assertTrue(userMsg.contains("Context Before"))
        assertTrue(userMsg.contains("Context After"))
        assertTrue(userMsg.contains("JSON"))
    }

    @Test
    fun `pseudo-streaming JSON passthrough and 400 stream rejection fallback work seamlessly`() {
        val hitCount = AtomicInteger(0)
        server.createContext("/v1/chat/completions") { exchange ->
            val current = hitCount.incrementAndGet()
            val req = JsonParser.parseString(exchange.requestBody.bufferedReader().readText()).asJsonObject
            if (current == 1) {
                assertTrue(req.get("stream").asBoolean)
                respondJson(exchange, 400, """{"error":{"message":"streaming is not supported on this relay"}}""")
            } else {
                assertFalse(req.get("stream").asBoolean)
                respondJson(
                    exchange,
                    200,
                    """{"choices":[{"message":{"content":"[{\"i\":0,\"t\":\"回退成功\"}]"},"finish_reason":"stop"}]}""",
                )
            }
        }

        val res = OpenAICompatibleTranslator.translateWithConfig(
            request = TranslationRequest(
                texts = listOf("Fallback test"),
                sourceLanguage = "en",
                targetLanguage = "zh-Hans",
                enableStreaming = true,
            ),
            config = OpenAICompatibleTranslator.ClientConfig(
                endpoint = "$baseUrl/v1",
                apiKey = "sk-test",
                model = "custom-relay-model",
            ),
        )

        assertEquals(listOf("回退成功"), res.texts)
        assertEquals(2, hitCount.get())
        assertTrue(EndpointCapabilities.get("$baseUrl/v1", "custom-relay-model").streamUnsupported)
    }

    @Test
    fun `ThinkingOnly response automatically retries with doubled max_tokens and succeeds`() {
        val calls = AtomicInteger(0)
        server.createContext("/v1/chat/completions") { exchange ->
            val attempt = calls.incrementAndGet()
            val req = JsonParser.parseString(exchange.requestBody.bufferedReader().readText()).asJsonObject
            if (attempt == 1) {
                respondJson(
                    exchange,
                    200,
                    """{"choices":[{"message":{"content":"","reasoning_content":"long reasoning spent all tokens"},"finish_reason":"length"}]}""",
                )
            } else {
                assertTrue(req.get("max_tokens").asInt >= OpenAICompatibleTranslator.THINKING_RETRY_MAX_TOKENS)
                respondJson(
                    exchange,
                    200,
                    """{"choices":[{"message":{"content":"[{\"i\":0,\"t\":\"恢复后的译文\"}]"},"finish_reason":"stop"}]}""",
                )
            }
        }

        val res = OpenAICompatibleTranslator.translateWithConfig(
            request = TranslationRequest(
                texts = listOf("Recover me"),
                sourceLanguage = "en",
                targetLanguage = "zh-Hans",
                enableStreaming = false,
            ),
            config = OpenAICompatibleTranslator.ClientConfig(
                endpoint = baseUrl,
                apiKey = "sk-test",
                model = "deepseek-reasoner",
            ),
        )

        assertEquals(listOf("恢复后的译文"), res.texts)
        assertEquals(2, calls.get())
    }

    @Test
    fun `error retries handle 502 with Connection close, 429 Retry-After, and 401 zero-retry fail-fast`() {
        val step = AtomicInteger(0)
        var sawConnectionCloseOnRetry = false

        server.createContext("/v1/chat/completions") { exchange ->
            when (step.incrementAndGet()) {
                1 -> respondJson(exchange, 502, "<html><title>502 Bad Gateway</title></html>", "text/html")
                2 -> {
                    sawConnectionCloseOnRetry =
                        exchange.requestHeaders.getFirst("Connection")?.equals("close", ignoreCase = true) == true
                    exchange.responseHeaders.add("Retry-After", "1")
                    respondJson(exchange, 429, """{"error":{"message":"Rate limit"}}""")
                }
                else -> respondJson(
                    exchange,
                    200,
                    """{"choices":[{"message":{"content":"[{\"i\":0,\"t\":\"最终成功\"}]"}}]}""",
                )
            }
        }

        val result = OpenAICompatibleTranslator.translateWithConfig(
            request = TranslationRequest(
                texts = listOf("Retry test"),
                sourceLanguage = "en",
                targetLanguage = "zh-Hans",
                enableStreaming = false,
            ),
            config = OpenAICompatibleTranslator.ClientConfig(
                endpoint = baseUrl,
                apiKey = "sk-ok",
                model = "gpt-4o-mini",
                maxRetries = 2,
            ),
        )

        assertEquals(listOf("最终成功"), result.texts)
        assertTrue(sawConnectionCloseOnRetry)
        assertEquals(listOf(1000L, 1000L), recordedSleeps.toList())

        // Verify 401 fails fast with 0 retries:
        val authCalls = AtomicInteger(0)
        server.removeContext("/v1/chat/completions")
        server.createContext("/v1/chat/completions") { exchange ->
            authCalls.incrementAndGet()
            respondJson(exchange, 401, """{"error":{"message":"Invalid API key"}}""")
        }

        try {
            OpenAICompatibleTranslator.translateWithConfig(
                request = TranslationRequest(listOf("Auth fail"), "en", "zh-Hans", enableStreaming = false),
                config = OpenAICompatibleTranslator.ClientConfig(baseUrl, "sk-bad", "gpt-4o-mini", maxRetries = 3),
            )
            fail("Expected OpenAIRequestException")
        } catch (e: OpenAIRequestException) {
            assertEquals(OpenAIErrorCategory.FATAL_AUTH_BILLING, e.category)
            assertEquals(1, authCalls.get())
        }
    }

    @Test
    fun `partial cache hit sends only uncached items over network and populates L1+L2 cache`() {
        val cache = TranslationCache(diskStore = BoundedMemoryDiskStore())
        val config = OpenAICompatibleTranslator.ClientConfig(
            endpoint = baseUrl,
            apiKey = "sk-cache",
            model = "gpt-4o-mini",
        )

        val networkPayloads = CopyOnWriteArrayList<String>()
        server.createContext("/v1/chat/completions") { exchange ->
            val body = exchange.requestBody.bufferedReader().readText()
            networkPayloads.add(body)
            if (networkPayloads.size == 1) {
                respondJson(
                    exchange,
                    200,
                    """{"choices":[{"message":{"content":"[{\"i\":0,\"t\":\"缓存一\"}]","role":"assistant"}}]}""",
                )
            } else {
                respondJson(
                    exchange,
                    200,
                    """{"choices":[{"message":{"content":"[{\"i\":0,\"t\":\"新翻二\"},{\"i\":1,\"t\":\"新翻三\"}]"}}]}""",
                )
            }
        }

        val first = OpenAICompatibleTranslator.translateWithConfig(
            request = TranslationRequest(listOf("One"), "en", "zh-Hans", enableStreaming = false),
            config = config,
            activeCache = cache,
        )
        assertEquals(listOf("缓存一"), first.texts)

        val second = OpenAICompatibleTranslator.translateWithConfig(
            request = TranslationRequest(listOf("Two", "One", "Three"), "en", "zh-Hans", enableStreaming = false),
            config = config,
            activeCache = cache,
        )
        assertEquals(listOf("新翻二", "缓存一", "新翻三"), second.texts)
        assertEquals(2, networkPayloads.size)
        // Second network call should NOT contain "One" in input items since it hit cache!
        assertFalse(networkPayloads[1].contains("\"s\":\"One\""))

        // Third call with all 3 items hits cache completely (0 additional network calls)!
        val third = OpenAICompatibleTranslator.translateWithConfig(
            request = TranslationRequest(listOf("One", "Two", "Three"), "en", "zh-Hans", enableStreaming = false),
            config = config,
            activeCache = cache,
        )
        assertEquals(listOf("缓存一", "新翻二", "新翻三"), third.texts)
        assertEquals(2, networkPayloads.size)
    }

    @Test
    fun `probeConnectivityWithConfig verifies real completion and adapts gpt-5 max_completion_tokens`() {
        val probeCalls = AtomicInteger(0)
        server.createContext("/v1/chat/completions") { exchange ->
            probeCalls.incrementAndGet()
            val req = JsonParser.parseString(exchange.requestBody.bufferedReader().readText()).asJsonObject
            assertTrue(req.has("max_completion_tokens"))
            assertFalse(req.has("max_tokens"))
            respondJson(
                exchange,
                200,
                """{"choices":[{"message":{"content":"","reasoning_content":"pong thought"},"finish_reason":"length"}]}""",
            )
        }

        val reply = OpenAICompatibleTranslator.probeConnectivityWithConfig(
            timeoutMs = 5_000,
            config = OpenAICompatibleTranslator.ClientConfig(
                endpoint = baseUrl,
                apiKey = "sk-probe",
                model = "gpt-5-mini",
            ),
        )
        assertEquals("OK (gpt-5-mini)", reply)
        assertEquals(1, probeCalls.get())
    }

    @Test
    fun `pseudo-streaming JSON on stream=true and forced-SSE on stream=false both succeed in single request`() {
        val calls = AtomicInteger(0)
        server.createContext("/v1/chat/completions") { exchange ->
            val n = calls.incrementAndGet()
            if (n == 1) {
                // Client asked stream=true, relay returned plain JSON
                respondJson(
                    exchange,
                    200,
                    """{"choices":[{"message":{"content":[{"type":"text","text":"[{\"i\":0,\"t\":\"伪流式直通\"}]"}]}}]}""",
                )
            } else {
                // Client asked stream=false, relay forced text/event-stream
                respondJson(
                    exchange,
                    200,
                    "data: {\"choices\":[{\"delta\":{\"content\":\"[{\\\"i\\\":0,\\\"t\\\":\\\"强制SSE聚合\\\"}]\"}}]}\n\ndata: [DONE]\n\n",
                    "text/event-stream",
                )
            }
        }

        val cfg = OpenAICompatibleTranslator.ClientConfig(baseUrl, "sk-dual", "gpt-4o-mini")
        val r1 = OpenAICompatibleTranslator.translateWithConfig(
            TranslationRequest(listOf("A"), "en", "zh-Hans", enableStreaming = true),
            cfg,
        )
        assertEquals(listOf("伪流式直通"), r1.texts)
        assertEquals(1, calls.get())

        val r2 = OpenAICompatibleTranslator.translateWithConfig(
            TranslationRequest(listOf("B"), "en", "zh-Hans", enableStreaming = false),
            cfg,
        )
        assertEquals(listOf("强制SSE聚合"), r2.texts)
        assertEquals(2, calls.get())
    }

    @Test
    fun `mid-stream SSE error frame triggers fallback to non-streaming`() {
        val calls = AtomicInteger(0)
        server.createContext("/v1/chat/completions") { exchange ->
            if (calls.incrementAndGet() == 1) {
                respondJson(
                    exchange,
                    200,
                    "data: {\"error\":{\"status\":502,\"message\":\"upstream stream aborted\"}}\n\ndata: [DONE]\n\n",
                    "text/event-stream",
                )
            } else {
                respondJson(
                    exchange,
                    200,
                    """{"choices":[{"message":{"content":"[{\"i\":0,\"t\":\"流报错后恢复\"}]"}}]}""",
                )
            }
        }

        val res = OpenAICompatibleTranslator.translateWithConfig(
            TranslationRequest(listOf("Stream err"), "en", "zh-Hans", enableStreaming = true),
            OpenAICompatibleTranslator.ClientConfig(baseUrl, "sk-mid", "gpt-4o-mini", maxRetries = 2),
        )
        assertEquals(listOf("流报错后恢复"), res.texts)
        assertEquals(2, calls.get())
    }

    @Test
    fun `RequestControl tryBeginAttempt false aborts before sending HTTP request`() {
        val calls = AtomicInteger(0)
        server.createContext("/v1/chat/completions") { exchange ->
            calls.incrementAndGet()
            respondJson(exchange, 200, "{}")
        }

        val deniedControl = object : RequestControl {
            override fun tryBeginAttempt(): Boolean = false
        }

        try {
            OpenAICompatibleTranslator.translateWithConfig(
                TranslationRequest(
                    texts = listOf("Should not send"),
                    sourceLanguage = "en",
                    targetLanguage = "zh-Hans",
                    control = deniedControl,
                ),
                OpenAICompatibleTranslator.ClientConfig(baseUrl, "sk-deny", "gpt-4o-mini"),
            )
            fail("Expected CancellationException")
        } catch (_: java.util.concurrent.CancellationException) {
            assertEquals(0, calls.get())
        }
    }

    @Test
    fun `cleartext policy and TLS handshake errors fail fast with zero inner retries and do not trip auth breaker`() {
        val originalFactory = OpenAICompatibleTranslator.connectionFactory
        val attempts = AtomicInteger(0)
        try {
            OpenAICompatibleTranslator.connectionFactory = {
                attempts.incrementAndGet()
                throw java.io.IOException("Cleartext HTTP traffic to 127.0.0.1 not permitted")
            }
            val cfg = OpenAICompatibleTranslator.ClientConfig(
                endpoint = "http://127.0.0.1:20181/v1",
                apiKey = "sk-cleartext",
                model = "ali-tp/qwen3.8-flash",
                maxRetries = 3,
            )
            try {
                OpenAICompatibleTranslator.translateWithConfig(
                    TranslationRequest(listOf("Hello"), "en", "zh-Hans", enableStreaming = true),
                    cfg,
                )
                fail("Expected OpenAIRequestException")
            } catch (e: OpenAIRequestException) {
                assertEquals(OpenAIErrorCategory.FATAL_NETWORK_CONFIG, e.category)
                assertEquals("Must fail fast with zero inner retries", 1, attempts.get())
                assertTrue("Must not sleep before failing fast", recordedSleeps.isEmpty())
                assertFalse(
                    "Network config failure must not trip auth circuit breaker",
                    EndpointCapabilities.isAuthTripped(
                        OpenAIEndpointNormalizer.normalize(cfg.endpoint),
                        cfg.model,
                        cfg.apiKey,
                    ),
                )
            }
        } finally {
            OpenAICompatibleTranslator.connectionFactory = originalFactory
        }
    }

    private fun respondJson(
        exchange: HttpExchange,
        status: Int,
        body: String,
        contentType: String = "application/json; charset=utf-8",
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}

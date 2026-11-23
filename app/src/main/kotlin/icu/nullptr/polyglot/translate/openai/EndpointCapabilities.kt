package icu.nullptr.polyglot.translate.openai

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import icu.nullptr.polyglot.translate.cache.CacheKeyBuilder
import java.util.Locale

object EndpointCapabilities {
    const val MAX_TRACKED_ENDPOINTS = 128
    const val MODEL_COOLDOWN_MS = 60_000L
    const val AUTH_CIRCUIT_BREAKER_MS = 15_000L

    private val OPENAI_MAX_COMPLETION_MODELS = Regex("(?i)(?:^|/)(?:o1|o3|o4|gpt-5|gpt-6)(?:[-.]|$)")
    private val OPENAI_NO_TEMPERATURE_MODELS = Regex("(?i)(?:^|/)(?:o1|o3|o4)(?:[-.]|$)")

    data class Snapshot(
        val streamUnsupported: Boolean,
        val useMaxCompletionTokens: Boolean,
        val thinkingParamUnsupported: Boolean,
        val temperatureUnsupported: Boolean,
        val responseFormatUnsupported: Boolean,
        val stopUnsupported: Boolean,
        val modelCooldownUntilMs: Long,
    )

    private class MutableState(
        var streamUnsupported: Boolean = false,
        var consecutiveStreamFailures: Int = 0,
        var useMaxCompletionTokens: Boolean = false,
        var thinkingParamUnsupported: Boolean = false,
        var temperatureUnsupported: Boolean = false,
        var responseFormatUnsupported: Boolean = false,
        var stopUnsupported: Boolean = false,
        var modelCooldownUntilMs: Long = 0L,
    ) {
        fun snapshot(): Snapshot = Snapshot(
            streamUnsupported = streamUnsupported,
            useMaxCompletionTokens = useMaxCompletionTokens,
            thinkingParamUnsupported = thinkingParamUnsupported,
            temperatureUnsupported = temperatureUnsupported,
            responseFormatUnsupported = responseFormatUnsupported,
            stopUnsupported = stopUnsupported,
            modelCooldownUntilMs = modelCooldownUntilMs,
        )
    }

    private val lock = Any()
    private val states = object : LinkedHashMap<String, MutableState>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableState>?): Boolean =
            size > MAX_TRACKED_ENDPOINTS
    }
    private val authCircuitUntil = object : LinkedHashMap<String, Long>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
            size > MAX_TRACKED_ENDPOINTS
    }

    fun get(endpoint: String, model: String): Snapshot =
        synchronized(lock) {
            getOrCreateLocked(endpoint, model).snapshot()
        }

    fun isModelCoolingDown(endpoint: String, model: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        synchronized(lock) {
            val state = states[endpointKey(endpoint, model)] ?: return false
            state.modelCooldownUntilMs > nowMs
        }

    fun isAuthTripped(
        endpoint: String,
        model: String,
        apiKey: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean =
        synchronized(lock) {
            val until = authCircuitUntil[authKey(endpoint, model, apiKey)] ?: return false
            if (until <= nowMs) {
                authCircuitUntil.remove(authKey(endpoint, model, apiKey))
                false
            } else {
                true
            }
        }

    fun recordAuthFailure(
        endpoint: String,
        model: String,
        apiKey: String,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        synchronized(lock) {
            authCircuitUntil[authKey(endpoint, model, apiKey)] = nowMs + AUTH_CIRCUIT_BREAKER_MS
        }
    }

    fun clearAuthFailure(endpoint: String, model: String, apiKey: String) {
        synchronized(lock) {
            authCircuitUntil.remove(authKey(endpoint, model, apiKey))
        }
    }

    fun recordModelUnavailable(
        endpoint: String,
        model: String,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        synchronized(lock) {
            getOrCreateLocked(endpoint, model).modelCooldownUntilMs = nowMs + MODEL_COOLDOWN_MS
        }
    }

    fun markStreamUnsupported(endpoint: String, model: String) {
        synchronized(lock) {
            getOrCreateLocked(endpoint, model).streamUnsupported = true
        }
    }

    fun recordStreamEmptyOrMidError(endpoint: String, model: String) {
        synchronized(lock) {
            val state = getOrCreateLocked(endpoint, model)
            state.consecutiveStreamFailures++
            if (state.consecutiveStreamFailures >= 2) {
                state.streamUnsupported = true
            }
        }
    }

    fun recordStreamSuccess(endpoint: String, model: String) {
        synchronized(lock) {
            val state = getOrCreateLocked(endpoint, model)
            state.consecutiveStreamFailures = 0
            state.modelCooldownUntilMs = 0L
        }
    }

    fun markUseMaxCompletionTokens(endpoint: String, model: String) {
        synchronized(lock) {
            val state = getOrCreateLocked(endpoint, model)
            state.useMaxCompletionTokens = true
            state.stopUnsupported = true
        }
    }

    fun markThinkingParamUnsupported(endpoint: String, model: String) {
        synchronized(lock) {
            getOrCreateLocked(endpoint, model).thinkingParamUnsupported = true
        }
    }

    fun markTemperatureUnsupported(endpoint: String, model: String) {
        synchronized(lock) {
            getOrCreateLocked(endpoint, model).temperatureUnsupported = true
        }
    }

    fun markResponseFormatUnsupported(endpoint: String, model: String) {
        synchronized(lock) {
            getOrCreateLocked(endpoint, model).responseFormatUnsupported = true
        }
    }

    fun markStopUnsupported(endpoint: String, model: String) {
        synchronized(lock) {
            getOrCreateLocked(endpoint, model).stopUnsupported = true
        }
    }

    fun clear() {
        synchronized(lock) {
            states.clear()
            authCircuitUntil.clear()
        }
    }

    /**
     * Vendor-specific low-latency thinking suppression parameters for subtitle translation.
     */
    fun buildThinkingDisableParams(model: String): Map<String, JsonElement> {
        val lower = model.trim().lowercase(Locale.ROOT)
        return when {
            // Always-on reasoning models that 400 if asked to disable thinking
            "deepseek-reasoner" in lower || "deepseek-r1" in lower || "qwq" in lower -> emptyMap()
            "deepseek" in lower -> mapOf(
                "thinking" to JsonObject().apply { addProperty("type", "disabled") },
            )
            "qwen" in lower || "glm" in lower || "mimo" in lower -> mapOf(
                "enable_thinking" to JsonPrimitive(false),
            )
            "gemma" in lower -> mapOf(
                "thinking_budget" to JsonPrimitive(0),
            )
            "gpt-5" in lower || "gpt-6" in lower -> mapOf(
                "reasoning_effort" to JsonPrimitive("none"),
            )
            else -> emptyMap()
        }
    }

    fun endpointKey(endpoint: String, model: String): String {
        val canonicalUrl = OpenAIEndpointNormalizer.canonicalKeyUrl(endpoint)
        val normModel = model.trim().lowercase(Locale.ROOT)
        return CacheKeyBuilder.sha256Hex("$canonicalUrl\n$normModel")
    }

    private fun authKey(endpoint: String, model: String, apiKey: String): String {
        val canonicalUrl = OpenAIEndpointNormalizer.canonicalKeyUrl(endpoint)
        val normModel = model.trim().lowercase(Locale.ROOT)
        return CacheKeyBuilder.sha256Hex("$canonicalUrl\n$normModel\n${apiKey.trim()}")
    }

    private fun getOrCreateLocked(endpoint: String, model: String): MutableState {
        val key = endpointKey(endpoint, model)
        return states.getOrPut(key) {
            val trimmedModel = model.trim()
            val usesMaxCompletion = OPENAI_MAX_COMPLETION_MODELS.containsMatchIn(trimmedModel)
            MutableState(
                useMaxCompletionTokens = usesMaxCompletion,
                temperatureUnsupported = OPENAI_NO_TEMPERATURE_MODELS.containsMatchIn(trimmedModel),
                stopUnsupported = usesMaxCompletion,
            )
        }
    }
}

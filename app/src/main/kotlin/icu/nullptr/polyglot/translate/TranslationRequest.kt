package icu.nullptr.polyglot.translate

data class TranslationRequest(
    val texts: List<String>,
    val sourceLanguage: String,
    val targetLanguage: String,
    val context: String = "",
    val timeoutMs: Int = 45_000,
    val contextBefore: String = "",
    val contextAfter: String = "",
    val isPriority: Boolean = true,
    val enableStreaming: Boolean = true,
    val control: RequestControl? = null,
)

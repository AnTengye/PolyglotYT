package icu.nullptr.polyglot.captions.engine

import icu.nullptr.polyglot.captions.CaptionCue
import java.net.HttpURLConnection

enum class BlockState {
    WAITING,
    RUNNING,
    READY,
    FAILED,
}

enum class FailureKind {
    TRANSIENT,
    RATE_LIMIT,
    INVALID_RESULT,
    FATAL_CONFIG,
}

enum class PublicationMode {
    OPEN,
    RETIRED,
    STOPPED,
}

internal object MonotonicClock {
    private val originNs: Long = System.nanoTime()

    fun nowMs(): Long = (((System.nanoTime() - originNs) / 1_000_000L) + 1L).coerceAtLeast(1L)
}

data class IndexedCue(
    val cueIndex: Int,
    val startMs: Long,
    val endMs: Long,
    val normalizedText: String,
    val windowId: Int = CaptionCue.UNKNOWN_WINDOW_ID,
)

data class PlannedBlock(
    val blockIndex: Int,
    val id: String,
    val cues: List<IndexedCue>,
    val startMs: Long,
    val endMs: Long,
    val isAuthored: Boolean,
    val contextBefore: String = "",
    val contextAfter: String = "",
    val continuedBefore: Boolean = false,
    val continuedAfter: Boolean = false,
    val isImmediate: Boolean = false,
) {
    fun covers(posMs: Long): Boolean =
        if (endMs > startMs) posMs in startMs until endMs else posMs == startMs
}

data class BlockTranslationOutcome(
    val indexedTranslations: Map<Int, String>,
) {
    companion object {
        fun fromList(translations: List<String>): BlockTranslationOutcome =
            BlockTranslationOutcome(
                indexedTranslations = buildMap {
                    translations.forEachIndexed { index, text ->
                        if (text.isNotBlank()) {
                            put(index, text)
                        }
                    }
                },
            )
    }
}

data class BlockTranslationPlan(
    val blockId: String,
    val translationsByCueIndex: Map<Int, String>,
    val translationsByText: Map<String, String>,
    val partial: Boolean,
    val missingLocalIndices: List<Int> = emptyList(),
)

interface EngineRequestControl {
    val sessionEpoch: Long
        get() = 0L

    fun tryBeginAttempt(): Boolean
    fun isCancelled(): Boolean
    fun onConnection(connection: HttpURLConnection?)
    fun onRequestBodySent()
    fun onProvisional(localIndex: Int, translated: String)
}

class BlockTranslationException(
    message: String,
    val kind: FailureKind,
    val retryAfterMs: Long = 0L,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

fun interface BlockTranslator {
    @Throws(Exception::class)
    fun translate(
        block: PlannedBlock,
        cuesToTranslate: List<IndexedCue>,
        isPriority: Boolean,
        control: EngineRequestControl,
    ): BlockTranslationOutcome
}

fun interface BlockCacheLookup {
    fun lookup(block: PlannedBlock): Map<Int, String>?

    fun lookup(sessionEpoch: Long, block: PlannedBlock): Map<Int, String>? = lookup(block)
}

interface TranslationPublishListener {
    fun onProvisional(sessionEpoch: Long, generation: Int, cue: IndexedCue, translated: String) {}
    fun onBlockPublished(
        sessionEpoch: Long,
        generation: Int,
        block: PlannedBlock,
        plan: BlockTranslationPlan,
    ) {}
    fun onCacheableEntries(sessionEpoch: Long, entries: Map<IndexedCue, String>) {}
}

class BlockAttempt(
    val blockIndex: Int,
    val priority: Boolean,
    val generation: Int,
) {
    @Volatile
    var cancelled: Boolean = false

    @Volatile
    var sent: Boolean = false

    @Volatile
    var dispatched: Boolean = false

    @Volatile
    var connection: HttpURLConnection? = null
}

class BlockSlot(val block: PlannedBlock) {
    @Volatile
    var state: BlockState = BlockState.WAITING

    @Volatile
    var accepted: BlockTranslationPlan? = null

    @Volatile
    var provisional: Map<Int, String> = emptyMap()

    val resolvedLocalTranslations: MutableMap<Int, String> = LinkedHashMap()
    var attempts: Int = 0
    var gapFillAttempted: Boolean = false
    var retryAtElapsedMs: Long = 0L
    var cacheChecked: Boolean = false
    var cacheReading: Boolean = false
    var lastFailureReason: String = ""
    var activeAttempt: BlockAttempt? = null
}

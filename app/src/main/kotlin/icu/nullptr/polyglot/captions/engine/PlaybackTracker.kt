package icu.nullptr.polyglot.captions.engine

import icu.nullptr.polyglot.captions.CaptionCue
import kotlin.math.max
import kotlin.math.min

data class PlaybackObservation(
    val seekDetected: Boolean,
    val seekStorm: Boolean,
    val positionMs: Long,
    val seekSerial: Long,
)

/**
 * Tracks playback progress from native subtitle anchors without requiring a custom player clock hook.
 * Linearly extrapolates up to [EXTRAPOLATION_LEASE_MS] (auto-freezing during pause or long silence),
 * detects backward/forward seeks, and gates prefetching during rapid scrub storms.
 */
class PlaybackTracker(
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private var hasAnchor: Boolean = false
    private var anchorPosMs: Long = 0L
    private var anchorEndMs: Long = CaptionCue.UNKNOWN_TIME_MS
    private var anchorElapsedMs: Long = 0L
    private var confirmedPosMs: Long = 0L
    private var confirmedElapsedMs: Long = 0L
    private var speed: Float = 1.0f
    private var paused: Boolean = false
    private var seekSerial: Long = 0L
    private var lastSeekElapsedMs: Long = -1L
    private var prefetchPausedUntilElapsedMs: Long = 0L

    @Synchronized
    fun onAnchor(
        startMs: Long,
        endMs: Long = CaptionCue.UNKNOWN_TIME_MS,
        nowElapsedMs: Long = clockMs(),
        playbackSpeed: Float = 1.0f,
        isPaused: Boolean = false,
    ): PlaybackObservation {
        val validSpeed = playbackSpeed.takeIf { it.isFinite() && it in 0.1f..4.0f } ?: 1.0f
        if (startMs < 0L) {
            paused = isPaused
            speed = validSpeed
            return PlaybackObservation(
                seekDetected = false,
                seekStorm = false,
                positionMs = estimatePositionMs(nowElapsedMs),
                seekSerial = seekSerial,
            )
        }

        var seek = false
        var storm = false
        if (hasAnchor) {
            val elapsedDelta = (nowElapsedMs - confirmedElapsedMs).coerceAtLeast(0L)
            val backwardSeek = startMs < confirmedPosMs - SEEK_BACKWARD_THRESHOLD_MS
            val forwardThreshold = max(SEEK_FORWARD_MIN_MS, elapsedDelta * 4L + 500L)
            val forwardSeek = startMs - confirmedPosMs > forwardThreshold
            seek = backwardSeek || forwardSeek
            if (seek) {
                storm = recordSeekLocked(nowElapsedMs)
            }
        }

        anchorPosMs = startMs
        anchorEndMs = endMs
        anchorElapsedMs = nowElapsedMs
        confirmedPosMs = startMs
        confirmedElapsedMs = nowElapsedMs
        speed = validSpeed
        paused = isPaused
        hasAnchor = true

        return PlaybackObservation(
            seekDetected = seek,
            seekStorm = storm,
            positionMs = startMs,
            seekSerial = seekSerial,
        )
    }

    @Synchronized
    fun noteExplicitSeek(
        targetPosMs: Long,
        nowElapsedMs: Long = clockMs(),
    ): PlaybackObservation {
        val pos = targetPosMs.coerceAtLeast(0L)
        val storm = recordSeekLocked(nowElapsedMs)
        anchorPosMs = pos
        anchorEndMs = CaptionCue.UNKNOWN_TIME_MS
        anchorElapsedMs = nowElapsedMs
        confirmedPosMs = pos
        confirmedElapsedMs = nowElapsedMs
        hasAnchor = true
        return PlaybackObservation(
            seekDetected = true,
            seekStorm = storm,
            positionMs = pos,
            seekSerial = seekSerial,
        )
    }

    @Synchronized
    fun setPaused(isPaused: Boolean, nowElapsedMs: Long = clockMs()) {
        if (paused == isPaused) return
        if (isPaused) {
            if (hasAnchor) {
                anchorPosMs = estimatePositionMs(nowElapsedMs)
                anchorElapsedMs = nowElapsedMs
            }
            paused = true
        } else {
            paused = false
            if (hasAnchor) {
                anchorElapsedMs = nowElapsedMs
            }
        }
    }

    @Synchronized
    fun isPaused(): Boolean = paused

    @Synchronized
    fun estimatePositionMs(nowElapsedMs: Long = clockMs()): Long {
        if (!hasAnchor) return 0L
        if (paused) return anchorPosMs

        val elapsed = (nowElapsedMs - anchorElapsedMs).coerceAtLeast(0L)
        val boundedElapsed = min(elapsed, EXTRAPOLATION_LEASE_MS)
        val projected = anchorPosMs + (boundedElapsed * speed).toLong()
        val ceiling = if (anchorEndMs > anchorPosMs) {
            anchorEndMs + ANCHOR_END_OVERSHOOT_MS
        } else {
            anchorPosMs + EXTRAPOLATION_LEASE_MS
        }
        return min(projected, ceiling).coerceAtLeast(anchorPosMs)
    }

    @Synchronized
    fun isPrefetchPaused(
        landingBlockReady: Boolean,
        nowElapsedMs: Long = clockMs(),
    ): Boolean {
        if (nowElapsedMs >= prefetchPausedUntilElapsedMs) return false
        if (landingBlockReady &&
            lastSeekElapsedMs >= 0L &&
            nowElapsedMs - lastSeekElapsedMs >= SEEK_SETTLED_MS
        ) {
            return false
        }
        return true
    }

    @Synchronized
    fun prefetchResumeDelayMs(
        landingBlockReady: Boolean,
        nowElapsedMs: Long = clockMs(),
    ): Long {
        if (!isPrefetchPaused(landingBlockReady, nowElapsedMs)) return 0L
        val stormLeft = (prefetchPausedUntilElapsedMs - nowElapsedMs).coerceAtLeast(0L)
        if (!landingBlockReady || lastSeekElapsedMs < 0L) return stormLeft
        val settleLeft = (lastSeekElapsedMs + SEEK_SETTLED_MS - nowElapsedMs).coerceAtLeast(0L)
        return min(stormLeft, settleLeft)
    }

    @Synchronized
    fun seekSerial(): Long = seekSerial

    @Synchronized
    fun reset() {
        hasAnchor = false
        anchorPosMs = 0L
        anchorEndMs = CaptionCue.UNKNOWN_TIME_MS
        anchorElapsedMs = 0L
        confirmedPosMs = 0L
        confirmedElapsedMs = 0L
        speed = 1.0f
        paused = false
        seekSerial = 0L
        lastSeekElapsedMs = -1L
        prefetchPausedUntilElapsedMs = 0L
    }

    private fun recordSeekLocked(nowElapsedMs: Long): Boolean {
        seekSerial++
        val storm = lastSeekElapsedMs >= 0L &&
            nowElapsedMs - lastSeekElapsedMs <= SEEK_STORM_WINDOW_MS
        if (storm) {
            prefetchPausedUntilElapsedMs = max(
                prefetchPausedUntilElapsedMs,
                nowElapsedMs + SEEK_STORM_PAUSE_MS,
            )
        }
        lastSeekElapsedMs = nowElapsedMs
        return storm
    }

    companion object {
        const val EXTRAPOLATION_LEASE_MS = 3_000L
        const val ANCHOR_END_OVERSHOOT_MS = 1_500L
        const val SEEK_BACKWARD_THRESHOLD_MS = 250L
        const val SEEK_FORWARD_MIN_MS = 2_500L
        const val SEEK_STORM_WINDOW_MS = 3_000L
        const val SEEK_STORM_PAUSE_MS = 5_000L
        const val SEEK_SETTLED_MS = 1_500L
    }
}

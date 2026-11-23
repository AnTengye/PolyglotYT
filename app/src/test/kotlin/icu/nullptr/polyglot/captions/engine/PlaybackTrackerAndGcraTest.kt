package icu.nullptr.polyglot.captions.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTrackerAndGcraTest {

    @Test
    fun `playback tracker extrapolates within 3000ms lease and freezes past lease or when paused`() {
        var now = 10_000L
        val tracker = PlaybackTracker { now }

        val obs = tracker.onAnchor(startMs = 5_000L, endMs = 9_000L, nowElapsedMs = now)
        assertFalse(obs.seekDetected)
        assertEquals(5_000L, tracker.estimatePositionMs())

        now += 1_200L
        assertEquals(6_200L, tracker.estimatePositionMs())

        // Past 3000ms lease without new anchor -> stops extrapolating at +3000ms
        now += 4_000L // total +5200ms since anchor
        assertEquals(8_000L, tracker.estimatePositionMs())

        // Anchor with shorter endMs caps overshoot at endMs + 1500ms
        tracker.onAnchor(startMs = 20_000L, endMs = 20_800L, nowElapsedMs = now)
        now += 2_900L
        assertEquals(22_300L, tracker.estimatePositionMs())

        // Pausing freezes position immediately
        tracker.onAnchor(startMs = 30_000L, endMs = 35_000L, nowElapsedMs = now)
        now += 500L
        tracker.setPaused(true, now)
        val frozenPos = tracker.estimatePositionMs(now)
        assertEquals(30_500L, frozenPos)
        now += 2_000L
        assertEquals(frozenPos, tracker.estimatePositionMs(now))
    }

    @Test
    fun `backward and forward seek detection ignores minor ASR overlap and normal silent gaps`() {
        var now = 1_000L
        val tracker = PlaybackTracker { now }

        tracker.onAnchor(startMs = 10_000L, endMs = 12_000L, nowElapsedMs = now)

        // -150ms ASR overlap is within 250ms tolerance -> NOT a seek
        now += 200L
        val minorOverlap = tracker.onAnchor(startMs = 9_850L, endMs = 12_500L, nowElapsedMs = now)
        assertFalse(minorOverlap.seekDetected)

        // -400ms backward jump -> IS a seek
        now += 100L
        val backSeek = tracker.onAnchor(startMs = 9_400L, endMs = 11_000L, nowElapsedMs = now)
        assertTrue(backSeek.seekDetected)
        assertFalse(backSeek.seekStorm)

        // 8 seconds of normal silent playback (wall clock advances 8s, position advances 8s) -> NOT a seek
        now += 8_000L
        val afterSilence = tracker.onAnchor(startMs = 17_400L, endMs = 19_000L, nowElapsedMs = now)
        assertFalse(afterSilence.seekDetected)

        // Forward jump of +20s in 100ms wall clock -> IS a seek
        now += 100L
        val forwardSeek = tracker.onAnchor(startMs = 37_400L, endMs = 40_000L, nowElapsedMs = now)
        assertTrue(forwardSeek.seekDetected)
    }

    @Test
    fun `seek storm freezes prefetch for 5s and unfreezes early at 1500ms once landing ready`() {
        var now = 5_000L
        val tracker = PlaybackTracker { now }

        val first = tracker.noteExplicitSeek(10_000L, now)
        assertTrue(first.seekDetected)
        assertFalse(first.seekStorm)
        assertFalse(tracker.isPrefetchPaused(landingBlockReady = false, nowElapsedMs = now))

        // Second seek within 3000ms triggers Seek Storm
        now += 800L
        val second = tracker.noteExplicitSeek(45_000L, now)
        assertTrue(second.seekDetected)
        assertTrue(second.seekStorm)
        assertTrue(tracker.isPrefetchPaused(landingBlockReady = false, nowElapsedMs = now))

        // At +1000ms (< 1500ms settled), still paused even if landingBlockReady = true
        now += 1_000L
        assertTrue(tracker.isPrefetchPaused(landingBlockReady = true, nowElapsedMs = now))

        // At +1600ms (>= 1500ms settled): if landing is NOT ready, still paused; if ready, unfreezes early!
        now += 600L
        assertTrue(tracker.isPrefetchPaused(landingBlockReady = false, nowElapsedMs = now))
        assertFalse(tracker.isPrefetchPaused(landingBlockReady = true, nowElapsedMs = now))

        // Past 5000ms, unfreezes unconditionally
        now += 3_500L
        assertFalse(tracker.isPrefetchPaused(landingBlockReady = false, nowElapsedMs = now))
    }

    @Test
    fun `gcra rate limiter allows initial burst of 2 and paces steady state at rpm interval`() {
        val limiter = GcraRateLimiter(rpm = 30, burst = 2) // interval = 2000ms, tolerance = 2000ms

        assertEquals(0L, limiter.tryAcquire(1_000L))
        assertEquals(0L, limiter.tryAcquire(1_000L))

        // Third immediate call must wait 2000ms (until t = 3000ms)
        val wait = limiter.tryAcquire(1_000L)
        assertEquals(2_000L, wait)
        assertEquals(1_500L, limiter.waitMs(1_500L))

        // At t = 3000ms, permit is granted
        assertEquals(0L, limiter.tryAcquire(3_000L))
    }
}

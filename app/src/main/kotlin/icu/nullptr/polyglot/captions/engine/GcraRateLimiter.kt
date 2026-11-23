package icu.nullptr.polyglot.captions.engine

/**
 * Lightweight O(1) Generic Cell Rate Algorithm (GCRA) rate limiter for background prefetching.
 * Tracks only a single theoretical arrival timestamp [nextTatMs] so prefetch bursts are bounded
 * without starving foreground realtime caption requests.
 */
class GcraRateLimiter(
    rpm: Int = DEFAULT_RPM,
    burst: Int = DEFAULT_BURST,
) {
    private val intervalMs: Long = if (rpm > 0) (60_000L / rpm).coerceAtLeast(1L) else 0L
    private val burstToleranceMs: Long =
        if (intervalMs > 0L) (burst.coerceAtLeast(1) - 1L) * intervalMs else 0L

    private var nextTatMs: Long = Long.MIN_VALUE

    @Synchronized
    fun waitMs(nowElapsedMs: Long): Long {
        if (intervalMs <= 0L) return 0L
        val tat = if (nextTatMs == Long.MIN_VALUE) nowElapsedMs else maxOf(nextTatMs, nowElapsedMs)
        val allowAt = tat - burstToleranceMs
        return (allowAt - nowElapsedMs).coerceAtLeast(0L)
    }

    /**
     * Attempts to consume one prefetch permit at [nowElapsedMs].
     * @return `0L` if permitted immediately, or positive wait duration in milliseconds if throttled.
     */
    @Synchronized
    fun tryAcquire(nowElapsedMs: Long): Long {
        if (intervalMs <= 0L) return 0L
        val tat = if (nextTatMs == Long.MIN_VALUE) nowElapsedMs else maxOf(nextTatMs, nowElapsedMs)
        val allowAt = tat - burstToleranceMs
        if (nowElapsedMs < allowAt) {
            return allowAt - nowElapsedMs
        }
        nextTatMs = tat + intervalMs
        return 0L
    }

    @Synchronized
    fun reset() {
        nextTatMs = Long.MIN_VALUE
    }

    companion object {
        const val DEFAULT_RPM = 30
        const val DEFAULT_BURST = 1
    }
}

package icu.nullptr.polyglot.captions.engine

import icu.nullptr.polyglot.captions.CaptionCue
import java.io.Closeable
import java.net.HttpURLConnection
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * Dual-queue generation-safe subtitle translation scheduler.
 *
 * - Physically isolates foreground (`focusExecutor`, concurrency = 2) and background prefetch
 *   (`prefetchExecutor`, concurrency = 2), plus single-slot `pendingFocus` replacement without
 *   charging retry attempts when both foreground lanes are occupied.
 * - Gates cold-start bootstrap to `focusIndex + 1` (`<= 20s`) until `everReady`, and bounds
 *   steady-state prefetch to `+30s` under an O(1) [GcraRateLimiter] and Seek Storm debounce.
 * - Separates same-video Seek (`generation++`: unsent non-landing work cancels immediately;
 *   already-sent work finishes for cache reuse while suppressing stale provisional UI updates)
 *   from video/config replacement (`sessionEpoch++`: asynchronously disconnects active sockets
 *   on `cleanupExecutor` without blocking the caller thread).
 */
class DualQueueScheduler(
    private val translator: BlockTranslator,
    private val cacheLookup: BlockCacheLookup? = null,
    private val listener: TranslationPublishListener = object : TranslationPublishListener {},
    private val clockMs: () -> Long = MonotonicClock::nowMs,
    val playbackTracker: PlaybackTracker = PlaybackTracker(clockMs),
    val prefetchRateLimiter: GcraRateLimiter = GcraRateLimiter(),
    private val maxRetriesPerBlock: Int = DEFAULT_MAX_RETRIES_PER_BLOCK,
    private val focusDeadlineMs: Long = DEFAULT_FOCUS_DEADLINE_MS,
    private val prefetchDeadlineMs: Long = DEFAULT_PREFETCH_DEADLINE_MS,
    private val focusExecutor: ExecutorService = newFixedDaemonPool(MAX_FOCUS_CONCURRENCY, "Polyglot-Focus"),
    private val prefetchExecutor: ExecutorService = newFixedDaemonPool(MAX_PREFETCH_CONCURRENCY, "Polyglot-Prefetch"),
    private val cacheExecutor: ExecutorService = newFixedDaemonPool(1, "Polyglot-CacheIO"),
    private val cleanupExecutor: ExecutorService = newFixedDaemonPool(1, "Polyglot-Cleanup"),
    private val timerExecutor: ScheduledExecutorService = newScheduledDaemonPool("Polyglot-Timer"),
    private val enableTimerWakeup: Boolean = true,
    private val logger: ((String, Throwable?) -> Unit)? = null,
) : Closeable {

    private val stateLock = Any()
    private val epochGenerator = AtomicLong(0L)
    private var activeSession: SessionState = SessionState(epoch = epochGenerator.incrementAndGet())
    private var scheduledWakeup: ScheduledFuture<*>? = null
    private var scheduledWakeupAtMs: Long = Long.MAX_VALUE

    fun currentSessionEpoch(): Long = synchronized(stateLock) { activeSession.epoch }

    fun currentGeneration(): Int = synchronized(stateLock) { activeSession.generation }

    fun isCurrentGeneration(sessionEpoch: Long, generation: Int): Boolean =
        synchronized(stateLock) {
            activeSession.epoch == sessionEpoch &&
                activeSession.mode == PublicationMode.OPEN &&
                activeSession.generation == generation
        }

    /**
     * Advances `sessionEpoch`, revokes the previous session (`STOPPED`), and asynchronously
     * disconnects all in-flight HTTP connections on `cleanupExecutor` without blocking the caller.
     */
    fun onNewSession(reason: String = "reset"): Long {
        val connectionsToDisconnect: List<HttpURLConnection>
        val newEpoch: Long
        synchronized(stateLock) {
            val old = activeSession
            old.mode = PublicationMode.STOPPED
            old.generation++
            old.pendingFocus = null
            for (slot in old.slots) {
                slot.activeAttempt?.cancelled = true
                slot.activeAttempt = null
                slot.provisional = emptyMap()
            }
            connectionsToDisconnect = synchronized(old.connections) {
                old.connections.toList().also { old.connections.clear() }
            }
            playbackTracker.reset()
            cancelWakeupLocked()
            newEpoch = epochGenerator.incrementAndGet()
            activeSession = SessionState(epoch = newEpoch)
        }
        disconnectAsync(connectionsToDisconnect, reason)
        return newEpoch
    }

    /**
     * Softly retires the current session (`RETIRED`): already-sent HTTP requests may finish reading
     * and emit cacheable entries, while unsent/retry/fallback attempts are rejected immediately.
     */
    fun retireCurrentSession(reason: String = "retire"): Long {
        val unsentConnections = ArrayList<HttpURLConnection>()
        val newEpoch: Long
        synchronized(stateLock) {
            val old = activeSession
            if (old.mode == PublicationMode.OPEN) {
                old.mode = PublicationMode.RETIRED
            }
            old.generation++
            old.pendingFocus = null
            for (slot in old.slots) {
                val attempt = slot.activeAttempt ?: continue
                if (!attempt.sent) {
                    attempt.cancelled = true
                    attempt.connection?.let { unsentConnections += it }
                    slot.activeAttempt = null
                }
                slot.provisional = emptyMap()
            }
            cancelWakeupLocked()
            newEpoch = epochGenerator.incrementAndGet()
            activeSession = SessionState(epoch = newEpoch)
        }
        disconnectAsync(unsentConnections, reason)
        return newEpoch
    }

    /**
     * Registers a subtitle timeline (from `TimelineBuildHook`), automatically resetting the session
     * if the timeline belongs to a new video/track rather than an incremental prefix append.
     */
    fun submitTimeline(
        cues: List<CaptionCue>,
        isAutoGenerated: Boolean? = null,
        sourceLanguage: String = "auto",
        maxCuesPerBlock: Int = Int.MAX_VALUE,
        onSessionEpochAssigned: ((Long) -> Unit)? = null,
    ): List<PlannedBlock> {
        val planned = SubtitleChunker.planTimeline(
            cues = cues,
            isAutoGenerated = isAutoGenerated,
            sourceLanguage = sourceLanguage,
            maxCuesPerBlock = maxCuesPerBlock,
        )
        if (planned.isEmpty()) return emptyList()

        val newIndexedCues = planned.flatMap { it.cues }
        val connectionsToDisconnect = ArrayList<HttpURLConnection>()
        val assignedEpoch: Long

        synchronized(stateLock) {
            val cur = activeSession
            val replaceSession = cur.timelineCues.isNotEmpty() &&
                !isTimelineContinuation(cur.timelineCues, newIndexedCues)

            val targetSession = if (replaceSession) {
                cur.mode = PublicationMode.STOPPED
                cur.generation++
                cur.pendingFocus = null
                for (slot in cur.slots) {
                    slot.activeAttempt?.cancelled = true
                    slot.activeAttempt = null
                }
                synchronized(cur.connections) {
                    connectionsToDisconnect += cur.connections
                    cur.connections.clear()
                }
                playbackTracker.reset()
                cancelWakeupLocked()
                SessionState(epoch = epochGenerator.incrementAndGet()).also { activeSession = it }
            } else {
                retirePendingFocusLocked(cur)
                cur
            }

            val existingBySignature = HashMap<String, BlockSlot>(targetSession.slots.size)
            val preservedImmediateSlots = ArrayList<BlockSlot>()
            for (slot in targetSession.slots) {
                if (!slot.block.isImmediate) {
                    existingBySignature[blockSignature(slot.block)] = slot
                } else if (!replaceSession &&
                    slot.state == BlockState.WAITING &&
                    slot.accepted == null &&
                    slot.activeAttempt == null
                ) {
                    preservedImmediateSlots += slot
                }
            }

            targetSession.slots.clear()
            for (block in planned) {
                val reused = existingBySignature[blockSignature(block)]
                val slot = when {
                    reused != null &&
                        reused.block.blockIndex == block.blockIndex &&
                        reused.activeAttempt != null -> reused
                    reused != null && reused.accepted != null ->
                        BlockSlot(block).also { preserved ->
                            preserved.state = if (reused.state == BlockState.RUNNING) {
                                BlockState.READY
                            } else {
                                reused.state
                            }
                            preserved.accepted = reused.accepted
                            preserved.resolvedLocalTranslations.putAll(reused.resolvedLocalTranslations)
                            preserved.cacheChecked = true
                        }
                    else ->
                        BlockSlot(block).also { fresh ->
                            fresh.cacheChecked = (cacheLookup == null)
                        }
                }
                targetSession.slots += slot
                targetSession.recentHistoryCues.addAll(block.cues.takeLast(MAX_HISTORY_CUES))
                trimHistoryLocked(targetSession)
            }
            for (immSlot in preservedImmediateSlots) {
                targetSession.slots += immSlot
            }
            targetSession.timelineCues = newIndexedCues
            targetSession.nextGlobalCueIndex = (newIndexedCues.lastOrNull()?.cueIndex ?: -1) + 1
            assignedEpoch = targetSession.epoch
        }

        onSessionEpochAssigned?.invoke(assignedEpoch)
        disconnectAsync(connectionsToDisconnect, "timeline_replaced")
        tick()
        return planned
    }

    /**
     * Submits incremental cues (from `OverlayUpdateHook` on Shorts / Live streams without a full
     * timeline) directly into the foreground priority queue after rolling deduplication.
     */
    fun submitImmediateCues(
        cues: List<CaptionCue>,
        maxCuesPerBlock: Int = SubtitleChunker.DEFAULT_IMMEDIATE_MAX_CUES,
        updatePlaybackPosition: Boolean = true,
    ): List<PlannedBlock> {
        val collapsed = SubtitleChunker.collapseRollingCues(cues)
        if (collapsed.isEmpty()) return emptyList()

        if (updatePlaybackPosition) {
            val firstValidStart = collapsed.firstOrNull { cue ->
                isPlausibleImmediateStartMs(cue.startMs)
            }
            if (firstValidStart != null) {
                onPlaybackPosition(
                    startMs = firstValidStart.startMs,
                    endMs = firstValidStart.endMs,
                )
            }
        }

        val addedBlocks: List<PlannedBlock>
        synchronized(stateLock) {
            val session = activeSession
            if (session.mode != PublicationMode.OPEN) return emptyList()

            val unseen = collapsed.filter { cue ->
                val norm = CaptionCue.normalize(cue.text)
                norm.isNotEmpty() && session.knownCueTexts.add(norm)
            }
            if (unseen.isEmpty()) return emptyList()

            addedBlocks = SubtitleChunker.buildImmediateBlocks(
                cues = unseen,
                recentHistory = session.recentHistoryCues.map { it.normalizedText },
                startBlockIndex = session.slots.size,
                startCueIndex = session.nextGlobalCueIndex,
                maxCuesPerBlock = maxCuesPerBlock,
            )
            for (block in addedBlocks) {
                val slot = BlockSlot(block).also { it.cacheChecked = (cacheLookup == null) }
                session.slots += slot
                session.nextGlobalCueIndex = (block.cues.lastOrNull()?.cueIndex ?: session.nextGlobalCueIndex) + 1
                session.recentHistoryCues.addAll(block.cues)
                trimHistoryLocked(session)
            }
        }

        if (addedBlocks.isNotEmpty()) {
            tick()
        }
        return addedBlocks
    }

    /**
     * Updates playback progress from a rendered/overlay subtitle anchor and handles Seek / Seek Storm.
     */
    fun onPlaybackPosition(
        startMs: Long,
        endMs: Long = CaptionCue.UNKNOWN_TIME_MS,
        playbackSpeed: Float = 1.0f,
        isPaused: Boolean = false,
    ): PlaybackObservation {
        val now = clockMs()
        val observation = playbackTracker.onAnchor(
            startMs = startMs,
            endMs = endMs,
            nowElapsedMs = now,
            playbackSpeed = playbackSpeed,
            isPaused = isPaused,
        )
        if (observation.seekDetected) {
            handleSeek(observation.positionMs)
        }
        tick()
        return observation
    }

    /**
     * Explicitly notifies the scheduler of a user seek to [targetPosMs].
     */
    fun onExplicitSeek(targetPosMs: Long): PlaybackObservation {
        val now = clockMs()
        val observation = playbackTracker.noteExplicitSeek(targetPosMs, now)
        handleSeek(observation.positionMs)
        tick()
        return observation
    }

    fun setPaused(isPaused: Boolean) {
        playbackTracker.setPaused(isPaused, clockMs())
        if (!isPaused) {
            tick()
        }
    }

    /**
     * Evaluates cache warmup, `pendingFocus` promotion, foreground/prefetch admission, and timers.
     * Safe to invoke from any thread or deterministic test harness.
     */
    fun tick() {
        val now = clockMs()
        val posMs = playbackTracker.estimatePositionMs(now)

        val cacheTasks = ArrayList<CacheTask>()
        val focusJobs = ArrayList<TranslationJob>()
        val prefetchJobs = ArrayList<TranslationJob>()
        var nextWakeDelayMs = Long.MAX_VALUE

        synchronized(stateLock) {
            val s = activeSession
            if (s.mode != PublicationMode.OPEN || s.slots.isEmpty()) return

            val focusIndex = findLandingTimelineIndex(s.slots, posMs)
            val landingSlot = if (focusIndex >= 0) s.slots[focusIndex] else null
            if (landingSlot != null && landingSlot.accepted != null && landingSlot.state == BlockState.READY) {
                s.everReady = true
            }

            // Retire stale pendingFocus if landing moved or session became terminal.
            val existingPending = s.pendingFocus
            if (existingPending != null) {
                val pendingBlock = s.slots.getOrNull(existingPending.attempt.blockIndex)?.block
                val stillRelevant = !existingPending.attempt.cancelled &&
                    !s.terminal &&
                    ((focusIndex >= 0 && existingPending.attempt.blockIndex == focusIndex) || pendingBlock?.isImmediate == true)
                if (!stillRelevant) {
                    retirePendingFocusLocked(s)
                }
            }

            // Collect cache lookups for focusIndex and eligible ahead blocks first.
            if (cacheLookup != null) {
                collectCacheLookupsLocked(s, focusIndex, posMs, now, cacheTasks)
            }

            if (s.terminal) return
            if (now < s.providerRetryAtMs) {
                nextWakeDelayMs = min(nextWakeDelayMs, s.providerRetryAtMs - now)
                scheduleWakeupLocked(now, nextWakeDelayMs)
                return
            }

            var focusDispatched = countDispatchedLocked(s, priority = true)
            var prefetchDispatched = countDispatchedLocked(s, priority = false)

            // Promote retained pendingFocus immediately if a foreground slot freed up.
            val promotable = s.pendingFocus
            if (promotable != null &&
                focusDispatched < MAX_FOCUS_CONCURRENCY &&
                isSlotEligibleLocked(s, promotable.attempt.blockIndex, now)
            ) {
                s.pendingFocus = null
                markDispatchedLocked(s, promotable.attempt)
                focusJobs += promotable
                focusDispatched++
            }

            val focusUnderWay = landingSlot == null ||
                landingSlot.accepted != null ||
                landingSlot.state == BlockState.FAILED ||
                landingSlot.activeAttempt?.sent == true
            val prefetchPaused = playbackTracker.isPrefetchPaused(
                landingBlockReady = landingSlot?.accepted != null,
                nowElapsedMs = now,
            )
            if (prefetchPaused) {
                val resumeDelay = playbackTracker.prefetchResumeDelayMs(
                    landingBlockReady = landingSlot?.accepted != null,
                    nowElapsedMs = now,
                )
                if (resumeDelay > 0L) {
                    nextWakeDelayMs = min(nextWakeDelayMs, resumeDelay)
                }
            }
            val allowAhead = focusUnderWay && !prefetchPaused && !playbackTracker.isPaused()

            // Pass 1: Immediate micro-batches & current focusIndex
            val candidateOrder = buildCandidateIndices(s.slots, focusIndex)
            for (idx in candidateOrder) {
                val slot = s.slots[idx]
                val block = slot.block
                val isFocus = (focusIndex >= 0 && idx == focusIndex) || block.isImmediate

                if (!isFocus) {
                    if (block.startMs > posMs + PREFETCH_WINDOW_MS) continue
                    if (!allowAhead) continue
                    if (prefetchDispatched >= MAX_PREFETCH_CONCURRENCY) continue
                    if (!s.everReady &&
                        (focusIndex < 0 || idx != focusIndex + 1 || block.startMs > posMs + BOOTSTRAP_NEXT_MS)
                    ) {
                        continue
                    }
                }

                if (slot.state != BlockState.WAITING || slot.activeAttempt != null) continue
                if (!slot.cacheChecked || slot.cacheReading) continue
                if (now < slot.retryAtElapsedMs) {
                    nextWakeDelayMs = min(nextWakeDelayMs, slot.retryAtElapsedMs - now)
                    continue
                }

                if (slot.attempts > maxRetriesPerBlock) {
                    slot.state = if (slot.accepted != null) BlockState.READY else BlockState.FAILED
                    continue
                }

                if (!isFocus) {
                    val rateWaitMs = prefetchRateLimiter.tryAcquire(now)
                    if (rateWaitMs > 0L) {
                        nextWakeDelayMs = min(nextWakeDelayMs, rateWaitMs)
                        break
                    }
                }

                val attempt = BlockAttempt(
                    blockIndex = idx,
                    priority = isFocus,
                    generation = s.generation,
                )
                slot.activeAttempt = attempt
                slot.state = BlockState.RUNNING

                val job = TranslationJob(
                    session = s,
                    slot = slot,
                    attempt = attempt,
                )
                if (isFocus && focusDispatched >= MAX_FOCUS_CONCURRENCY) {
                    retirePendingFocusLocked(s)
                    s.pendingFocus = job
                } else {
                    markDispatchedLocked(s, attempt)
                    if (isFocus) {
                        focusJobs += job
                        focusDispatched++
                    } else {
                        prefetchJobs += job
                        prefetchDispatched++
                    }
                }
            }

            if (nextWakeDelayMs < Long.MAX_VALUE) {
                scheduleWakeupLocked(now, nextWakeDelayMs)
            }
        }

        for (task in cacheTasks) {
            submitSafe(cacheExecutor) { runCacheLookup(task) }
        }
        for (job in focusJobs) {
            submitSafe(focusExecutor) { runTranslationJob(job) }
        }
        for (job in prefetchJobs) {
            submitSafe(prefetchExecutor) { runTranslationJob(job) }
        }
    }

    fun slotStatesSnapshot(): List<Pair<String, BlockState>> =
        synchronized(stateLock) {
            activeSession.slots.map { it.block.id to it.state }
        }

    override fun close() {
        onNewSession("scheduler_closed")
        focusExecutor.shutdownNow()
        prefetchExecutor.shutdownNow()
        cacheExecutor.shutdownNow()
        cleanupExecutor.shutdownNow()
        timerExecutor.shutdownNow()
    }

    private fun handleSeek(newPositionMs: Long) {
        val toDisconnect = ArrayList<HttpURLConnection>()
        synchronized(stateLock) {
            val s = activeSession
            if (s.mode != PublicationMode.OPEN) return
            s.generation++

            val pending = s.pendingFocus
            if (pending != null) {
                val pendingBlock = s.slots.getOrNull(pending.attempt.blockIndex)?.block
                if (pendingBlock == null || (!pendingBlock.isImmediate && !pendingBlock.covers(newPositionMs))) {
                    retirePendingFocusLocked(s)
                }
            }

            for (slot in s.slots) {
                val attempt = slot.activeAttempt ?: continue
                if (!attempt.sent && !slot.block.isImmediate && !slot.block.covers(newPositionMs)) {
                    attempt.cancelled = true
                    attempt.connection?.let { toDisconnect += it }
                    if (attempt.dispatched) {
                        slot.attempts = (slot.attempts - 1).coerceAtLeast(0)
                    }
                    slot.activeAttempt = null
                    slot.provisional = emptyMap()
                    slot.state = if (slot.accepted != null) BlockState.READY else BlockState.WAITING
                }
            }
        }
        disconnectAsync(toDisconnect, "seek_unsent_cancel")
    }

    private fun collectCacheLookupsLocked(
        s: SessionState,
        focusIndex: Int,
        posMs: Long,
        now: Long,
        out: MutableList<CacheTask>,
    ) {
        for (idx in buildCandidateIndices(s.slots, focusIndex)) {
            val slot = s.slots[idx]
            val block = slot.block
            val isFocus = (focusIndex >= 0 && idx == focusIndex) || block.isImmediate
            if (!isFocus && block.startMs > posMs + PREFETCH_WINDOW_MS) continue
            if (!isFocus && !s.everReady && (focusIndex < 0 || idx > focusIndex + 1)) continue
            if (slot.state == BlockState.WAITING &&
                slot.activeAttempt == null &&
                !slot.cacheChecked &&
                !slot.cacheReading &&
                now >= slot.retryAtElapsedMs
            ) {
                slot.cacheReading = true
                out += CacheTask(
                    session = s,
                    generation = s.generation,
                    slot = slot,
                )
            }
        }
    }

    private fun runCacheLookup(task: CacheTask) {
        val lookupFn = cacheLookup ?: return
        val s = task.session
        synchronized(stateLock) {
            if (s != activeSession || s.mode != PublicationMode.OPEN) {
                task.slot.cacheReading = false
                return
            }
        }

        val cached: Map<Int, String>? = try {
            lookupFn.lookup(s.epoch, task.slot.block)
        } catch (e: Throwable) {
            logger?.invoke("Cache lookup failed for ${task.slot.block.id}", e)
            null
        }

        var planToPublish: BlockTranslationPlan? = null
        var publishGeneration = task.generation
        synchronized(stateLock) {
            task.slot.cacheReading = false
            if (s != activeSession || s.mode != PublicationMode.OPEN || task.slot !in s.slots) {
                return
            }
            task.slot.cacheChecked = true
            if (!cached.isNullOrEmpty()) {
                for ((localIdx, text) in cached) {
                    if (localIdx in task.slot.block.cues.indices && text.isNotBlank()) {
                        task.slot.resolvedLocalTranslations[localIdx] = text
                    }
                }
                if (task.slot.resolvedLocalTranslations.isNotEmpty()) {
                    val plan = buildPlan(task.slot.block, task.slot.resolvedLocalTranslations)
                    task.slot.accepted = plan
                    if (!plan.partial) {
                        task.slot.state = BlockState.READY
                        val focusIdx = findLandingTimelineIndex(
                            s.slots,
                            playbackTracker.estimatePositionMs(clockMs()),
                        )
                        if (focusIdx >= 0 && !task.slot.block.isImmediate && task.slot.block.blockIndex == focusIdx) {
                            s.everReady = true
                        }
                    }
                    planToPublish = plan
                    publishGeneration = s.generation
                }
            }
        }

        if (planToPublish != null) {
            safeListenerCall {
                listener.onBlockPublished(s.epoch, publishGeneration, task.slot.block, planToPublish!!)
            }
        }
        tick()
    }

    private fun runTranslationJob(job: TranslationJob) {
        val s = job.session
        val slot = job.slot
        val attempt = job.attempt
        val block = slot.block

        val missingLocalIndices: List<Int>
        synchronized(stateLock) {
            if (!isJobExecutableLocked(s, slot, attempt)) {
                releaseCancelledAttemptLocked(slot, attempt)
                return
            }
            missingLocalIndices = block.cues.indices.filter { it !in slot.resolvedLocalTranslations }
            if (missingLocalIndices.isEmpty()) {
                val completePlan = buildPlan(block, slot.resolvedLocalTranslations)
                slot.accepted = completePlan
                slot.activeAttempt = null
                slot.state = BlockState.READY
                return
            }
        }

        val cuesToTranslate = missingLocalIndices.map { block.cues[it] }
        val deadlineMs = if (attempt.priority) focusDeadlineMs else prefetchDeadlineMs
        val control = JobRequestControl(s, slot, attempt, missingLocalIndices)
        val watchdog = armDeadlineWatchdog(attempt, deadlineMs)

        try {
            if (control.isCancelled()) {
                onJobCancelled(s, slot, attempt)
                return
            }

            val outcome = translator.translate(
                block = block,
                cuesToTranslate = cuesToTranslate,
                isPriority = attempt.priority,
                control = control,
            )

            val freshLocal = extractValidLocalMap(missingLocalIndices, outcome)
            if (freshLocal.isEmpty()) {
                throw BlockTranslationException(
                    message = "Empty translation result for ${block.id}",
                    kind = FailureKind.INVALID_RESULT,
                )
            }

            val firstCommit = commitTranslations(s, slot, attempt, freshLocal, keepRunningForGapFill = true)
            firstCommit.cacheEntries.takeIf { it.isNotEmpty() }?.let { entries ->
                safeListenerCall { listener.onCacheableEntries(s.epoch, entries) }
            }
            firstCommit.publishedPlan?.let { plan ->
                safeListenerCall { listener.onBlockPublished(s.epoch, firstCommit.generation, block, plan) }
            }

            // Single bounded gap-fill pass if partial translations left uncovered cue indices.
            if (firstCommit.remainingMissingIndices.isNotEmpty() && firstCommit.allowGapFill) {
                runBoundedGapFill(s, slot, attempt, firstCommit.remainingMissingIndices)
            }
        } catch (e: Throwable) {
            if (control.isCancelled() || isIntentionalCancel(attempt)) {
                onJobCancelled(s, slot, attempt)
            } else {
                onJobFailed(s, slot, attempt, e)
            }
        } finally {
            watchdog?.cancel(false)
            control.onConnection(null)
            if (isSessionActive(s)) {
                tick()
            }
        }
    }

    private fun runBoundedGapFill(
        s: SessionState,
        slot: BlockSlot,
        parentAttempt: BlockAttempt,
        remainingIndices: List<Int>,
    ) {
        val block = slot.block
        val gapCues = remainingIndices.map { block.cues[it] }
        val gapControl = JobRequestControl(s, slot, parentAttempt, remainingIndices)
        try {
            if (!gapControl.tryBeginAttempt()) {
                finalizeAfterGapFill(s, slot, parentAttempt)
                return
            }
            val gapOutcome = translator.translate(
                block = block,
                cuesToTranslate = gapCues,
                isPriority = parentAttempt.priority,
                control = gapControl,
            )
            val recoveredLocal = extractValidLocalMap(remainingIndices, gapOutcome)
            if (recoveredLocal.isNotEmpty()) {
                val secondCommit = commitTranslations(
                    s = s,
                    slot = slot,
                    attempt = parentAttempt,
                    freshLocal = recoveredLocal,
                    keepRunningForGapFill = false,
                )
                secondCommit.cacheEntries.takeIf { it.isNotEmpty() }?.let { entries ->
                    safeListenerCall { listener.onCacheableEntries(s.epoch, entries) }
                }
                secondCommit.publishedPlan?.let { plan ->
                    safeListenerCall {
                        listener.onBlockPublished(s.epoch, secondCommit.generation, block, plan)
                    }
                }
            } else {
                finalizeAfterGapFill(s, slot, parentAttempt)
            }
        } catch (e: Throwable) {
            logger?.invoke("Bounded gap-fill failed for ${block.id}", e)
            finalizeAfterGapFill(s, slot, parentAttempt)
        }
    }

    private fun finalizeAfterGapFill(s: SessionState, slot: BlockSlot, attempt: BlockAttempt) {
        synchronized(stateLock) {
            if (slot.activeAttempt == attempt) {
                slot.activeAttempt = null
                slot.provisional = emptyMap()
                slot.state = if (slot.accepted != null) BlockState.READY else BlockState.WAITING
                if (slot.state == BlockState.READY && s == activeSession && shouldMarkEverReadyLocked(s, slot, attempt)) {
                    s.everReady = true
                }
            }
        }
    }

    private fun commitTranslations(
        s: SessionState,
        slot: BlockSlot,
        attempt: BlockAttempt,
        freshLocal: Map<Int, String>,
        keepRunningForGapFill: Boolean,
    ): CommitResult {
        val block = slot.block
        val cacheEntries = LinkedHashMap<IndexedCue, String>(freshLocal.size)
        for ((localIdx, text) in freshLocal) {
            cacheEntries[block.cues[localIdx]] = text
        }

        synchronized(stateLock) {
            if (s.mode == PublicationMode.STOPPED) {
                releaseCancelledAttemptLocked(slot, attempt)
                return CommitResult.EMPTY
            }
            // A RETIRED session still saves cacheable entries via onCacheableEntries, without UI publish.
            if (s != activeSession || s.mode == PublicationMode.RETIRED) {
                if (slot.activeAttempt == attempt) {
                    slot.activeAttempt = null
                }
                return CommitResult(
                    publishedPlan = null,
                    cacheEntries = cacheEntries,
                    generation = attempt.generation,
                    remainingMissingIndices = emptyList(),
                    allowGapFill = false,
                )
            }
            if (attempt.cancelled) {
                releaseCancelledAttemptLocked(slot, attempt)
                return CommitResult.EMPTY
            }

            slot.resolvedLocalTranslations.putAll(freshLocal)
            slot.provisional = emptyMap()
            val plan = buildPlan(block, slot.resolvedLocalTranslations)
            slot.accepted = plan

            val remaining = plan.missingLocalIndices
            val willGapFill = keepRunningForGapFill &&
                remaining.isNotEmpty() &&
                !slot.gapFillAttempted &&
                attempt.generation == s.generation &&
                !s.terminal
            if (willGapFill) {
                slot.gapFillAttempted = true
                slot.state = BlockState.RUNNING
            } else {
                if (slot.activeAttempt == attempt) {
                    slot.activeAttempt = null
                }
                slot.state = BlockState.READY
                if (shouldMarkEverReadyLocked(s, slot, attempt)) {
                    s.everReady = true
                }
            }

            return CommitResult(
                publishedPlan = plan,
                cacheEntries = cacheEntries,
                generation = attempt.generation,
                remainingMissingIndices = remaining,
                allowGapFill = willGapFill,
            )
        }
    }

    private fun onJobCancelled(s: SessionState, slot: BlockSlot, attempt: BlockAttempt) {
        synchronized(stateLock) {
            releaseCancelledAttemptLocked(slot, attempt)
        }
    }

    private fun releaseCancelledAttemptLocked(slot: BlockSlot, attempt: BlockAttempt) {
        if (slot.activeAttempt != attempt) return
        slot.activeAttempt = null
        slot.provisional = emptyMap()
        if (!attempt.sent && attempt.dispatched) {
            slot.attempts = (slot.attempts - 1).coerceAtLeast(0)
        }
        slot.state = if (slot.accepted != null) BlockState.READY else BlockState.WAITING
    }

    private fun onJobFailed(
        s: SessionState,
        slot: BlockSlot,
        attempt: BlockAttempt,
        error: Throwable,
    ) {
        logger?.invoke("Block translation failed: ${slot.block.id}", error)
        val (kind, retryAfterMs) = classifyFailure(error)
        val now = clockMs()

        synchronized(stateLock) {
            if (s.mode != PublicationMode.OPEN || slot.activeAttempt != attempt) return
            if (attempt.cancelled) {
                releaseCancelledAttemptLocked(slot, attempt)
                return
            }

            slot.activeAttempt = null
            slot.provisional = emptyMap()
            slot.lastFailureReason = error.message ?: error.javaClass.simpleName

            if (kind == FailureKind.FATAL_CONFIG) {
                s.terminal = true
                slot.state = if (slot.accepted != null) BlockState.READY else BlockState.FAILED
                return
            }

            val backoffMs = when {
                slot.attempts >= 3 -> BACKOFF_3_MS
                slot.attempts == 2 -> BACKOFF_2_MS
                else -> BACKOFF_1_MS
            }
            val delayMs = max(backoffMs, retryAfterMs)
            slot.retryAtElapsedMs = max(slot.retryAtElapsedMs, now + delayMs)

            if (kind == FailureKind.RATE_LIMIT) {
                val providerDelay = max(MIN_PROVIDER_RETRY_MS, retryAfterMs)
                s.providerRetryAtMs = max(s.providerRetryAtMs, now + providerDelay)
            }

            slot.state = when {
                slot.accepted != null -> BlockState.READY
                slot.attempts > maxRetriesPerBlock -> BlockState.FAILED
                else -> BlockState.WAITING
            }
            if (slot.state == BlockState.WAITING) {
                scheduleWakeupLocked(now, delayMs)
            }
        }
    }

    private fun classifyFailure(error: Throwable): Pair<FailureKind, Long> {
        if (error is BlockTranslationException) {
            return error.kind to error.retryAfterMs.coerceAtLeast(0L)
        }
        val msg = (error.message ?: "").lowercase(Locale.ROOT)
        return when {
            msg.contains("http 401") ||
                msg.contains("http 402") ||
                msg.contains("invalid_api_key") ||
                msg.contains("insufficient_quota") -> FailureKind.FATAL_CONFIG to 0L
            msg.contains("http 429") ||
                msg.contains("rate limit") ||
                msg.contains("too many requests") -> FailureKind.RATE_LIMIT to 0L
            else -> FailureKind.TRANSIENT to 0L
        }
    }

    private fun extractValidLocalMap(
        requestedLocalIndices: List<Int>,
        outcome: BlockTranslationOutcome,
    ): Map<Int, String> {
        val out = LinkedHashMap<Int, String>()
        for ((idxInRequest, translated) in outcome.indexedTranslations) {
            if (idxInRequest in requestedLocalIndices.indices && translated.isNotBlank()) {
                val blockLocalIdx = requestedLocalIndices[idxInRequest]
                out[blockLocalIdx] = translated.trim()
            }
        }
        return out
    }

    private fun buildPlan(
        block: PlannedBlock,
        localMap: Map<Int, String>,
    ): BlockTranslationPlan {
        val byCueIndex = LinkedHashMap<Int, String>(localMap.size)
        val byText = LinkedHashMap<String, String>(localMap.size)
        val missing = ArrayList<Int>()

        for (localIdx in block.cues.indices) {
            val cue = block.cues[localIdx]
            val translated = localMap[localIdx]
            if (!translated.isNullOrBlank()) {
                byCueIndex[cue.cueIndex] = translated
                byText[cue.normalizedText] = translated
            } else {
                missing += localIdx
            }
        }
        return BlockTranslationPlan(
            blockId = block.id,
            translationsByCueIndex = byCueIndex,
            translationsByText = byText,
            partial = missing.isNotEmpty(),
            missingLocalIndices = missing,
        )
    }

    private fun isJobExecutableLocked(
        s: SessionState,
        slot: BlockSlot,
        attempt: BlockAttempt,
    ): Boolean =
        s == activeSession &&
            s.mode == PublicationMode.OPEN &&
            !s.terminal &&
            !attempt.cancelled &&
            slot.activeAttempt == attempt

    private fun isSessionActive(s: SessionState): Boolean =
        synchronized(stateLock) { s == activeSession && s.mode == PublicationMode.OPEN }

    private fun shouldMarkEverReadyLocked(
        s: SessionState,
        slot: BlockSlot,
        attempt: BlockAttempt,
    ): Boolean {
        if (s.everReady) return true
        val focusIdx = findLandingTimelineIndex(s.slots, playbackTracker.estimatePositionMs(clockMs()))
        val attemptIsImmediate = slot.block.isImmediate
        if (attempt.priority && (!attemptIsImmediate || focusIdx < 0)) return true
        if (focusIdx < 0) return false
        return s.slots[focusIdx].accepted != null
    }

    private fun isIntentionalCancel(attempt: BlockAttempt): Boolean = attempt.cancelled

    private fun countDispatchedLocked(s: SessionState, priority: Boolean): Int {
        var count = 0
        for (slot in s.slots) {
            val a = slot.activeAttempt
            if (a != null && a.dispatched && a.priority == priority) {
                count++
            }
        }
        return count
    }

    private fun markDispatchedLocked(s: SessionState, attempt: BlockAttempt) {
        val slot = s.slots[attempt.blockIndex]
        slot.attempts++
        slot.state = BlockState.RUNNING
        attempt.dispatched = true
    }

    private fun retirePendingFocusLocked(s: SessionState) {
        val pending = s.pendingFocus ?: return
        s.pendingFocus = null
        val slot = s.slots.getOrNull(pending.attempt.blockIndex) ?: return
        if (slot.activeAttempt == pending.attempt) {
            slot.activeAttempt = null
        }
        if (slot.state == BlockState.RUNNING) {
            slot.state = if (slot.accepted != null) BlockState.READY else BlockState.WAITING
        }
    }

    private fun isSlotEligibleLocked(s: SessionState, index: Int, now: Long): Boolean {
        val slot = s.slots.getOrNull(index) ?: return false
        return !s.terminal &&
            now >= s.providerRetryAtMs &&
            now >= slot.retryAtElapsedMs &&
            slot.attempts <= maxRetriesPerBlock
    }

    /**
     * Binary-searches ONLY the contiguous non-immediate timeline prefix of [slots] so that
     * appended `isImmediate` micro-batches (which may carry `startMs = 0L` or out-of-order timestamps)
     * can never break binary search ordering or stall remaining timeline translation.
     *
     * Returns `-1` when [slots] contains only immediate blocks and no timeline blocks.
     */
    private fun findLandingTimelineIndex(slots: List<BlockSlot>, posMs: Long): Int {
        var timelineEnd = slots.lastIndex
        while (timelineEnd >= 0 && slots[timelineEnd].block.isImmediate) {
            timelineEnd--
        }
        if (timelineEnd < 0) return -1

        var lo = 0
        var hi = timelineEnd
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (slots[mid].block.startMs <= posMs) {
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (hi < 0) return 0
        if (!slots[hi].block.covers(posMs) && hi + 1 <= timelineEnd) {
            return hi + 1
        }
        return hi
    }

    private fun buildCandidateIndices(slots: List<BlockSlot>, focusIndex: Int): List<Int> {
        val timelineHint = if (focusIndex >= 0) (slots.size - focusIndex).coerceAtLeast(0) else 0
        val order = ArrayList<Int>(timelineHint + 4)
        if (focusIndex >= 0 && !slots[focusIndex].block.isImmediate) {
            order += focusIndex
        }
        for (i in slots.indices) {
            if (slots[i].block.isImmediate && slots[i].state == BlockState.WAITING) {
                order += i
            }
        }
        if (focusIndex >= 0) {
            for (i in (focusIndex + 1) until slots.size) {
                if (!slots[i].block.isImmediate) {
                    order += i
                }
            }
        }
        return order
    }

    private fun isPlausibleImmediateStartMs(startMs: Long): Boolean {
        if (startMs < 0L || startMs > MAX_PLAUSIBLE_PLAYBACK_MS) return false
        return synchronized(stateLock) {
            val tl = activeSession.timelineCues
            if (tl.isEmpty()) {
                true
            } else {
                val minStart = (tl.first().startMs - TIMELINE_ANCHOR_TOLERANCE_MS).coerceAtLeast(0L)
                val maxEnd = tl.maxOf { max(it.startMs, it.endMs) } + TIMELINE_ANCHOR_TOLERANCE_MS
                startMs in minStart..maxEnd
            }
        }
    }

    private fun isTimelineContinuation(oldCues: List<IndexedCue>, newCues: List<IndexedCue>): Boolean {
        if (oldCues.isEmpty() || newCues.isEmpty()) return false
        if (isPrefixExtension(oldCues, newCues)) return true

        // Live sliding window continuation: window shifts forward while preserving overlap with old tail.
        val oldLast = oldCues.last()
        if (newCues.first().startMs >= oldCues.first().startMs &&
            newCues.last().startMs >= oldLast.startMs
        ) {
            val tailMatched = newCues.any { candidate ->
                candidate.normalizedText == oldLast.normalizedText &&
                    kotlin.math.abs(candidate.startMs - oldLast.startMs) <= 200L
            }
            if (tailMatched) return true
        }
        return false
    }

    private fun isPrefixExtension(oldCues: List<IndexedCue>, newCues: List<IndexedCue>): Boolean {
        if (newCues.size < oldCues.size) return false
        val checkCount = min(oldCues.size, 8)
        for (i in 0 until checkCount) {
            if (oldCues[i].normalizedText != newCues[i].normalizedText ||
                kotlin.math.abs(oldCues[i].startMs - newCues[i].startMs) > 200L
            ) {
                return false
            }
        }
        val lastOldIdx = oldCues.lastIndex
        return oldCues[lastOldIdx].normalizedText == newCues[lastOldIdx].normalizedText
    }

    private fun blockSignature(block: PlannedBlock): String =
        buildString {
            append(block.isAuthored).append('|')
            for (cue in block.cues) {
                append(cue.startMs).append(':').append(cue.normalizedText).append('\n')
            }
        }

    private fun trimHistoryLocked(s: SessionState) {
        while (s.recentHistoryCues.size > MAX_HISTORY_CUES) {
            s.recentHistoryCues.removeAt(0)
        }
    }

    private fun armDeadlineWatchdog(attempt: BlockAttempt, deadlineMs: Long): ScheduledFuture<*>? {
        if (!enableTimerWakeup || deadlineMs <= 0L) return null
        return try {
            timerExecutor.schedule(
                {
                    attempt.cancelled = true
                    attempt.connection?.let { conn -> disconnectAsync(listOf(conn), "deadline") }
                },
                deadlineMs,
                TimeUnit.MILLISECONDS,
            )
        } catch (_: RejectedExecutionException) {
            null
        }
    }

    private fun scheduleWakeupLocked(now: Long, delayMs: Long) {
        if (!enableTimerWakeup || delayMs <= 0L || delayMs == Long.MAX_VALUE) return
        val targetAt = now + delayMs
        if (scheduledWakeup != null && scheduledWakeup?.isDone == false && targetAt >= scheduledWakeupAtMs) {
            return
        }
        scheduledWakeup?.cancel(false)
        scheduledWakeupAtMs = targetAt
        scheduledWakeup = try {
            timerExecutor.schedule(
                {
                    synchronized(stateLock) {
                        scheduledWakeupAtMs = Long.MAX_VALUE
                    }
                    tick()
                },
                delayMs,
                TimeUnit.MILLISECONDS,
            )
        } catch (_: RejectedExecutionException) {
            scheduledWakeupAtMs = Long.MAX_VALUE
            null
        }
    }

    private fun cancelWakeupLocked() {
        scheduledWakeup?.cancel(false)
        scheduledWakeup = null
        scheduledWakeupAtMs = Long.MAX_VALUE
    }

    private fun disconnectAsync(connections: List<HttpURLConnection>, reason: String) {
        if (connections.isEmpty()) return
        submitSafe(cleanupExecutor) {
            for (conn in connections) {
                runCatching { conn.disconnect() }
                    .onFailure { e -> logger?.invoke("Failed to disconnect ($reason)", e) }
            }
        }
    }

    private fun submitSafe(executor: ExecutorService, task: () -> Unit) {
        try {
            executor.execute(task)
        } catch (e: RejectedExecutionException) {
            logger?.invoke("Task rejected by executor", e)
        }
    }

    private inline fun safeListenerCall(block: () -> Unit) {
        runCatching(block).onFailure { e -> logger?.invoke("Listener callback error", e) }
    }

    private inner class JobRequestControl(
        private val session: SessionState,
        private val slot: BlockSlot,
        private val attempt: BlockAttempt,
        private val requestedLocalIndices: List<Int>,
    ) : EngineRequestControl {

        override val sessionEpoch: Long
            get() = session.epoch

        override fun tryBeginAttempt(): Boolean =
            synchronized(stateLock) {
                !attempt.cancelled &&
                    session == activeSession &&
                    session.mode == PublicationMode.OPEN &&
                    !session.terminal
            }

        override fun isCancelled(): Boolean {
            if (attempt.cancelled) return true
            val mode = session.mode
            if (mode == PublicationMode.STOPPED) return true
            if (mode == PublicationMode.RETIRED && !attempt.sent) return true
            return false
        }

        override fun onConnection(connection: HttpURLConnection?) {
            val prev = attempt.connection
            if (prev != null) {
                session.connections.remove(prev)
            }
            attempt.connection = connection
            if (connection != null) {
                session.connections.add(connection)
                if (isCancelled()) {
                    session.connections.remove(connection)
                    disconnectAsync(listOf(connection), "cancelled_on_attach")
                }
            }
        }

        override fun onRequestBodySent() {
            val wasSent = attempt.sent
            attempt.sent = true
            if (!wasSent && attempt.priority && isSessionActive(session)) {
                tick()
            }
        }

        override fun onProvisional(localIndex: Int, translated: String) {
            val trimmed = translated.trim()
            if (trimmed.isEmpty() || localIndex !in requestedLocalIndices.indices) return
            val blockLocalIdx = requestedLocalIndices[localIndex]
            val cue = slot.block.cues[blockLocalIdx]

            var publish = false
            synchronized(stateLock) {
                if (!attempt.cancelled &&
                    session == activeSession &&
                    session.mode == PublicationMode.OPEN &&
                    session.generation == attempt.generation &&
                    slot.activeAttempt == attempt &&
                    blockLocalIdx !in slot.resolvedLocalTranslations
                ) {
                    val updated = LinkedHashMap(slot.provisional)
                    if (updated[blockLocalIdx] != trimmed) {
                        updated[blockLocalIdx] = trimmed
                        slot.provisional = updated
                        publish = true
                    }
                }
            }

            if (publish) {
                safeListenerCall {
                    listener.onProvisional(session.epoch, attempt.generation, cue, trimmed)
                }
            }
        }
    }

    private class SessionState(val epoch: Long) {
        @Volatile
        var mode: PublicationMode = PublicationMode.OPEN

        @Volatile
        var generation: Int = 0

        @Volatile
        var terminal: Boolean = false

        var everReady: Boolean = false
        var providerRetryAtMs: Long = 0L
        val slots: MutableList<BlockSlot> = ArrayList()
        var pendingFocus: TranslationJob? = null
        var timelineCues: List<IndexedCue> = emptyList()
        var nextGlobalCueIndex: Int = 0
        val knownCueTexts: MutableSet<String> = HashSet()
        val recentHistoryCues: MutableList<IndexedCue> = ArrayList()
        val connections: MutableSet<HttpURLConnection> = Collections.synchronizedSet(HashSet())
    }

    private class CacheTask(
        val session: SessionState,
        val generation: Int,
        val slot: BlockSlot,
    )

    private class TranslationJob(
        val session: SessionState,
        val slot: BlockSlot,
        val attempt: BlockAttempt,
    )

    private class CommitResult(
        val publishedPlan: BlockTranslationPlan?,
        val cacheEntries: Map<IndexedCue, String>,
        val generation: Int,
        val remainingMissingIndices: List<Int>,
        val allowGapFill: Boolean,
    ) {
        companion object {
            val EMPTY = CommitResult(
                publishedPlan = null,
                cacheEntries = emptyMap(),
                generation = 0,
                remainingMissingIndices = emptyList(),
                allowGapFill = false,
            )
        }
    }

    companion object {
        const val MAX_FOCUS_CONCURRENCY = 2
        const val MAX_PREFETCH_CONCURRENCY = 2
        const val PREFETCH_WINDOW_MS = 30_000L
        const val BOOTSTRAP_NEXT_MS = 20_000L

        const val DEFAULT_MAX_RETRIES_PER_BLOCK = 3
        const val DEFAULT_FOCUS_DEADLINE_MS = 45_000L
        const val DEFAULT_PREFETCH_DEADLINE_MS = 75_000L

        const val BACKOFF_1_MS = 1_200L
        const val BACKOFF_2_MS = 5_000L
        const val BACKOFF_3_MS = 15_000L
        const val MIN_PROVIDER_RETRY_MS = 5_000L
        const val MAX_PLAUSIBLE_PLAYBACK_MS = 48L * 60L * 60L * 1_000L
        private const val TIMELINE_ANCHOR_TOLERANCE_MS = 60_000L
        private const val MAX_HISTORY_CUES = 6

        private fun newFixedDaemonPool(threads: Int, prefix: String): ExecutorService {
            val counter = AtomicInteger(0)
            return Executors.newFixedThreadPool(threads) { runnable ->
                Thread(runnable, "$prefix-${counter.incrementAndGet()}").apply {
                    isDaemon = true
                }
            }
        }

        private fun newScheduledDaemonPool(name: String): ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, name).apply {
                    isDaemon = true
                }
            }
    }
}

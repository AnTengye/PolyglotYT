package icu.nullptr.polyglot.captions

import icu.nullptr.polyglot.moduleOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class CaptionSession(
    private val maxObservedCues: Int = DEFAULT_MAX_OBSERVED_CUES,
    private val configSignatureProvider: () -> String = ::defaultConfigSignature,
) {
    constructor() : this(DEFAULT_MAX_OBSERVED_CUES, ::defaultConfigSignature)

    private val stateLock = Any()
    private val translations = ConcurrentHashMap<String, TranslationEntry>()
    private val renderedTexts = ConcurrentHashMap<String, Long>()
    private val cueMap = LinkedHashMap<String, CaptionCue>()
    private val observedCueCounter = AtomicInteger(0)
    private val epoch = AtomicLong(1L)

    @Volatile
    private var lastConfigSignature: String = configSignatureProvider()

    @Volatile
    private var cueSnapshot: CueIndexSnapshot = CueIndexSnapshot.EMPTY

    @Volatile
    private var snapshotDirty: Boolean = false

    @Volatile
    private var lastAnchorIndex: Int = -1

    private var timelineCues: List<CaptionCue> = emptyList()
    private var lastTimelineSignature: String? = null

    val sessionEpoch: Long
        get() {
            ensureConfigUpToDate()
            return epoch.get()
        }

    fun currentEpoch(): Long = sessionEpoch

    fun advanceEpoch(): Long = epoch.incrementAndGet()

    fun translationFor(original: String?): String? {
        if (original == null) return null
        ensureConfigUpToDate()

        val normalized = normalize(original)
        if (normalized.isEmpty()) return null

        translations[normalized]?.let { entry ->
            recordAnchorForNormalized(normalized)
            return entry.translated
        }

        if ('\n' in original || '\r' in original) {
            return lookupMultiLineTranslation(original)?.translated
        }

        return null
    }

    fun translatedCueContaining(fragment: String?): CaptionTranslation? {
        if (fragment == null) return null
        ensureConfigUpToDate()

        if ('\n' in fragment || '\r' in fragment) return null

        val normalized = normalize(fragment)
        if (normalized.isEmpty()) return null

        // If this text already has its own direct translation, let exact lookup render it.
        if (translations.containsKey(normalized)) {
            recordAnchorForNormalized(normalized)
            return null
        }

        val snap = currentSnapshot()
        if (snap.cues.isEmpty()) return null

        // If this text is itself a known standalone cue (even if not yet translated),
        // never let a longer neighboring cue swallow or overwrite it.
        val exactIdx = snap.nearestIndexForText(normalized, lastAnchorIndex)
        if (exactIdx != null) {
            lastAnchorIndex = exactIdx
            return null
        }

        val anchor = lastAnchorIndex
        if (anchor in snap.cues.indices) {
            for (offset in 0..NEIGHBORHOOD_RADIUS) {
                val forwardIdx = anchor + offset
                if (forwardIdx in snap.cues.indices) {
                    matchContainingAt(snap, forwardIdx, normalized)?.let { return it }
                }
                if (offset > 0) {
                    val backwardIdx = anchor - offset
                    if (backwardIdx in snap.cues.indices) {
                        matchContainingAt(snap, backwardIdx, normalized)?.let { return it }
                    }
                }
            }
            return null
        }

        val headEnd = minOf(snap.cues.lastIndex, NEIGHBORHOOD_RADIUS * 2)
        for (idx in 0..headEnd) {
            matchContainingAt(snap, idx, normalized)?.let { return it }
        }
        val tailStart = maxOf(headEnd + 1, snap.cues.size - NEIGHBORHOOD_RADIUS)
        for (idx in tailStart..snap.cues.lastIndex) {
            matchContainingAt(snap, idx, normalized)?.let { return it }
        }
        return null
    }

    fun resolveTranslation(rawText: CharSequence?): CaptionTranslation? {
        val raw = rawText?.toString() ?: return null
        ensureConfigUpToDate()

        val normalized = normalize(raw)
        if (normalized.isEmpty()) return null

        translations[normalized]?.let { entry ->
            recordAnchorForNormalized(normalized)
            return CaptionTranslation(normalized, entry.translated)
        }

        if ('\n' in raw || '\r' in raw) {
            lookupMultiLineTranslation(raw)?.let { return it }
        }

        return translatedCueContaining(normalized)
    }

    fun putTranslation(original: String?, translated: String?) {
        putFinalTranslation(original, translated, sessionEpoch)
    }

    fun putFinalTranslation(
        original: String?,
        translated: String?,
        expectedEpoch: Long = sessionEpoch,
    ): Boolean {
        if (original == null || translated == null) return false
        ensureConfigUpToDate()
        if (expectedEpoch != epoch.get()) return false

        val key = normalize(original)
        val cleanTranslated = translated.trim()
        if (key.isEmpty() || cleanTranslated.isEmpty()) return false

        translations[key] = TranslationEntry(cleanTranslated, isFinal = true)
        trimTranslationsIfNeeded()
        return true
    }

    fun putProvisionalTranslation(
        original: String?,
        translated: String?,
        expectedEpoch: Long = sessionEpoch,
    ): Boolean {
        if (original == null || translated == null) return false
        ensureConfigUpToDate()
        if (expectedEpoch != epoch.get()) return false

        val key = normalize(original)
        val cleanTranslated = translated.trim()
        if (key.isEmpty() || cleanTranslated.isEmpty()) return false

        var updated = false
        translations.compute(key) { _, existing ->
            if (existing != null && existing.isFinal) {
                existing
            } else {
                updated = true
                TranslationEntry(cleanTranslated, isFinal = false)
            }
        }
        if (updated) {
            trimTranslationsIfNeeded()
        }
        return updated
    }

    fun putFinalTranslations(
        entries: Map<String, String>,
        expectedEpoch: Long = sessionEpoch,
    ): Int {
        var count = 0
        for ((original, translated) in entries) {
            if (putFinalTranslation(original, translated, expectedEpoch)) {
                count++
            }
        }
        return count
    }

    fun putProvisionalTranslations(
        entries: Map<String, String>,
        expectedEpoch: Long = sessionEpoch,
    ): Int {
        var count = 0
        for ((original, translated) in entries) {
            if (putProvisionalTranslation(original, translated, expectedEpoch)) {
                count++
            }
        }
        return count
    }

    fun isProvisionalTranslation(original: String?): Boolean {
        if (original == null) return false
        ensureConfigUpToDate()
        val key = normalize(original)
        if (key.isEmpty()) return false
        return translations[key]?.let { !it.isFinal } ?: false
    }

    fun invalidateTranslations(): Long {
        synchronized(stateLock) {
            translations.clear()
            return epoch.incrementAndGet()
        }
    }

    fun clear() {
        synchronized(stateLock) {
            translations.clear()
            renderedTexts.clear()
            cueMap.clear()
            timelineCues = emptyList()
            lastTimelineSignature = null
            cueSnapshot = CueIndexSnapshot.EMPTY
            snapshotDirty = false
            lastAnchorIndex = -1
            observedCueCounter.set(0)
            lastConfigSignature = configSignatureProvider()
            epoch.incrementAndGet()
        }
    }

    fun observeCue(cue: CaptionCue): Boolean {
        ensureConfigUpToDate()
        if (cue.normalizedText().isEmpty()) return false

        synchronized(stateLock) {
            return insertCueLocked(cue)
        }
    }

    fun observeNewCues(cues: Iterable<CaptionCue>): List<CaptionCue> {
        ensureConfigUpToDate()
        val incoming = cues.toList()
        if (incoming.isEmpty()) return emptyList()

        val newCues = synchronized(stateLock) {
            insertCuesLocked(incoming)
        }

        if (incoming.size <= LIVE_OVERLAY_BATCH_LIMIT) {
            val lastCue = incoming.lastOrNull { it.normalizedText().isNotEmpty() }
            if (lastCue != null) {
                updateAnchorForCue(lastCue)
            }
        }

        return newCues
    }

    fun observeTimelineCues(cues: Iterable<CaptionCue>): TimelineObservation {
        ensureConfigUpToDate()
        val incoming = cues.filter { it.normalizedText().isNotEmpty() }
        if (incoming.isEmpty()) {
            return TimelineObservation(
                epoch = epoch.get(),
                replacedTrack = false,
                allCues = emptyList(),
                newCues = emptyList(),
                timelineSignature = "",
            )
        }

        synchronized(stateLock) {
            val signature = computeTimelineSignature(incoming)
            val previous = timelineCues

            if (previous.isEmpty()) {
                val hadUnrelatedOverlayCues = cueMap.isNotEmpty() && !hasAnyOverlap(cueMap.values, incoming)
                if (hadUnrelatedOverlayCues) {
                    resetSessionStateLocked()
                }
                timelineCues = incoming
                lastTimelineSignature = signature
                val newCues = insertCuesLocked(incoming)
                return TimelineObservation(
                    epoch = epoch.get(),
                    replacedTrack = hadUnrelatedOverlayCues,
                    allCues = incoming,
                    newCues = newCues,
                    timelineSignature = signature,
                )
            }

            if (signature == lastTimelineSignature || isSameTimeline(previous, incoming)) {
                val newCues = insertCuesLocked(incoming)
                return TimelineObservation(
                    epoch = epoch.get(),
                    replacedTrack = false,
                    allCues = incoming,
                    newCues = newCues,
                    timelineSignature = signature,
                )
            }

            if (isIncrementalExtension(previous, incoming)) {
                timelineCues = incoming
                lastTimelineSignature = signature
                val newCues = insertCuesLocked(incoming)
                return TimelineObservation(
                    epoch = epoch.get(),
                    replacedTrack = false,
                    allCues = incoming,
                    newCues = newCues,
                    timelineSignature = signature,
                )
            }

            if (isAnnotationSecondaryWindow(previous, incoming)) {
                val newCues = insertCuesLocked(incoming)
                return TimelineObservation(
                    epoch = epoch.get(),
                    replacedTrack = false,
                    allCues = previous,
                    newCues = newCues,
                    timelineSignature = lastTimelineSignature.orEmpty(),
                    isSecondaryWindow = true,
                )
            }

            if (isForwardSegmentContinuation(previous, incoming)) {
                val merged = mergeForwardSegment(previous, incoming)
                val mergedSignature = computeTimelineSignature(merged)
                timelineCues = merged
                lastTimelineSignature = mergedSignature
                val newCues = insertCuesLocked(incoming)
                return TimelineObservation(
                    epoch = epoch.get(),
                    replacedTrack = false,
                    allCues = merged,
                    newCues = newCues,
                    timelineSignature = mergedSignature,
                )
            }

            if (isSecondaryWindow(previous, incoming)) {
                val newCues = insertCuesLocked(incoming)
                return TimelineObservation(
                    epoch = epoch.get(),
                    replacedTrack = false,
                    allCues = previous,
                    newCues = newCues,
                    timelineSignature = lastTimelineSignature.orEmpty(),
                    isSecondaryWindow = true,
                )
            }

            if (hasSameCueAnchorOverlap(previous, incoming)) {
                val newCues = insertCuesLocked(incoming)
                if (incoming.size >= previous.size) {
                    timelineCues = incoming
                    lastTimelineSignature = signature
                    return TimelineObservation(
                        epoch = epoch.get(),
                        replacedTrack = false,
                        allCues = incoming,
                        newCues = newCues,
                        timelineSignature = signature,
                    )
                }
                return TimelineObservation(
                    epoch = epoch.get(),
                    replacedTrack = false,
                    allCues = previous,
                    newCues = newCues,
                    timelineSignature = lastTimelineSignature.orEmpty(),
                    isSecondaryWindow = true,
                )
            }

            if (isPrimaryPromotingSecondary(previous, incoming)) {
                timelineCues = incoming
                lastTimelineSignature = signature
                val newCues = insertCuesLocked(incoming)
                return TimelineObservation(
                    epoch = epoch.get(),
                    replacedTrack = false,
                    allCues = incoming,
                    newCues = newCues,
                    timelineSignature = signature,
                )
            }

            resetSessionStateLocked()
            timelineCues = incoming
            lastTimelineSignature = signature
            val newCues = insertCuesLocked(incoming)
            return TimelineObservation(
                epoch = epoch.get(),
                replacedTrack = true,
                allCues = incoming,
                newCues = newCues,
                timelineSignature = signature,
            )
        }
    }

    fun updateAnchorByTime(positionMs: Long): Int {
        if (positionMs < 0L) return lastAnchorIndex
        val snap = currentSnapshot()
        if (snap.cues.isEmpty()) return -1

        val idx = findCueIndexByTime(snap.cues, positionMs)
        if (idx >= 0) {
            lastAnchorIndex = idx
        }
        return idx
    }

    fun observeRenderedText(text: CharSequence?): Boolean {
        val raw = text?.toString().orEmpty()
        val normalized = normalize(raw)
        if (normalized.isEmpty()) return false

        recordAnchorForNormalized(normalized)
        if (renderedTexts.size > MAX_RENDERED_TEXTS) {
            renderedTexts.clear()
        }
        val now = System.currentTimeMillis()
        val previous = renderedTexts.put(normalized, now)
        return previous == null || now - previous > RENDERED_TEXT_LOG_INTERVAL_MS
    }

    fun observedCueCount(): Int = observedCueCounter.get()

    fun storedCueCount(): Int = synchronized(stateLock) { cueMap.size }

    fun allObservedCues(): List<CaptionCue> = currentSnapshot().cues

    fun currentTimelineCues(): List<CaptionCue> = synchronized(stateLock) { timelineCues }

    fun findNearestObservedCue(normalizedText: String): CaptionCue? {
        if (normalizedText.isEmpty()) return null
        val snap = currentSnapshot()
        val idx = snap.nearestIndexForText(normalizedText, lastAnchorIndex) ?: return null
        return snap.cues.getOrNull(idx)
    }

    fun hasObservedCueNearTime(
        normalizedText: String,
        targetStartMs: Long,
        toleranceMs: Long = 10_000L,
    ): Boolean {
        if (normalizedText.isEmpty() || targetStartMs < 0L) return false
        return currentSnapshot().hasOccurrenceNearTime(normalizedText, targetStartMs, toleranceMs)
    }

    fun isKnownStandaloneCue(text: String?): Boolean {
        if (text == null) return false
        val normalized = normalize(text)
        if (normalized.isEmpty()) return false
        return currentSnapshot().hasExactCue(normalized)
    }

    private fun lookupMultiLineTranslation(rawText: String): CaptionTranslation? {
        val lines = CaptionCue.normalizeLines(rawText)
        if (lines.size < 2) return null

        val translatedLines = ArrayList<String>(lines.size)
        for (line in lines) {
            val entry = translations[line] ?: return null
            translatedLines += entry.translated
        }
        recordAnchorForNormalized(lines.last())
        return CaptionTranslation(
            original = lines.joinToString("\n"),
            translated = translatedLines.joinToString("\n"),
        )
    }

    private fun matchContainingAt(
        snap: CueIndexSnapshot,
        index: Int,
        normalizedFragment: String,
    ): CaptionTranslation? {
        val cueText = snap.cueTexts[index]
        if (!CaptionCue.containsBoundedFragment(cueText, normalizedFragment)) {
            return null
        }
        val entry = translations[cueText] ?: return null
        lastAnchorIndex = index
        return CaptionTranslation(cueText, entry.translated)
    }

    private fun recordAnchorForNormalized(normalized: String) {
        val snap = currentSnapshot()
        snap.nearestIndexForText(normalized, lastAnchorIndex)?.let { idx ->
            lastAnchorIndex = idx
        }
    }

    private fun updateAnchorForCue(cue: CaptionCue) {
        val snap = currentSnapshot()
        if (snap.cues.isEmpty()) return

        if (cue.startMs >= 0L) {
            val byTime = findCueIndexByTime(snap.cues, cue.startMs)
            if (byTime >= 0) {
                val normalized = cue.normalizedText()
                lastAnchorIndex = snap.nearestIndexForText(normalized, byTime) ?: byTime
                return
            }
        }
        val normalized = cue.normalizedText()
        snap.nearestIndexForText(normalized, lastAnchorIndex)?.let { idx ->
            lastAnchorIndex = idx
        }
    }

    private fun findCueIndexByTime(cues: List<CaptionCue>, positionMs: Long): Int {
        var low = 0
        var high = cues.lastIndex
        var best = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val start = cues[mid].startMs
            if (start < 0L) {
                low = mid + 1
                continue
            }
            if (start <= positionMs) {
                best = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return if (best >= 0) best else 0
    }

    private fun currentSnapshot(): CueIndexSnapshot {
        if (!snapshotDirty) return cueSnapshot
        synchronized(stateLock) {
            if (!snapshotDirty) return cueSnapshot
            val built = CueIndexSnapshot.from(cueMap.values)
            cueSnapshot = built
            if (lastAnchorIndex >= built.cues.size) {
                lastAnchorIndex = built.cues.lastIndex
            }
            snapshotDirty = false
            return built
        }
    }

    private fun ensureConfigUpToDate() {
        val currentSig = configSignatureProvider()
        if (currentSig == lastConfigSignature) return

        synchronized(stateLock) {
            val refreshedSig = configSignatureProvider()
            if (refreshedSig != lastConfigSignature) {
                lastConfigSignature = refreshedSig
                translations.clear()
                epoch.incrementAndGet()
            }
        }
    }

    private fun resetSessionStateLocked() {
        translations.clear()
        renderedTexts.clear()
        cueMap.clear()
        timelineCues = emptyList()
        lastTimelineSignature = null
        cueSnapshot = CueIndexSnapshot.EMPTY
        snapshotDirty = false
        lastAnchorIndex = -1
        observedCueCounter.set(0)
        epoch.incrementAndGet()
    }

    private fun insertCuesLocked(cues: List<CaptionCue>): List<CaptionCue> {
        val newCues = ArrayList<CaptionCue>()
        for (cue in cues) {
            if (cue.normalizedText().isEmpty()) continue
            if (insertCueLocked(cue)) {
                newCues += cue
            }
        }
        return newCues
    }

    private fun insertCueLocked(cue: CaptionCue): Boolean {
        val key = cue.cacheKey()
        if (cueMap.containsKey(key)) return false

        val cap = maxObservedCues.coerceAtLeast(MIN_OBSERVED_CUES_CAP)
        while (cueMap.size >= cap) {
            val eldestKey = cueMap.entries.firstOrNull()?.key ?: break
            cueMap.remove(eldestKey)
            if (lastAnchorIndex > 0) {
                lastAnchorIndex--
            }
        }

        cueMap[key] = cue
        observedCueCounter.incrementAndGet()
        snapshotDirty = true
        return true
    }

    private fun trimTranslationsIfNeeded() {
        val maxEntries = (maxObservedCues * 2).coerceAtLeast(MIN_TRANSLATION_CAP)
        if (translations.size <= maxEntries) return
        val iterator = translations.keys.iterator()
        var toRemove = translations.size - maxEntries
        while (toRemove > 0 && iterator.hasNext()) {
            iterator.next()
            iterator.remove()
            toRemove--
        }
    }

    private fun computeTimelineSignature(cues: List<CaptionCue>): String {
        val first = cues.first()
        val middle = cues[cues.size / 2]
        val last = cues.last()
        return buildString {
            append(cues.size)
            append('|').append(first.startMs).append(':').append(first.normalizedText())
            append('|').append(middle.startMs).append(':').append(middle.normalizedText())
            append('|').append(last.startMs).append(':').append(last.normalizedText())
        }
    }

    private fun isSameTimeline(previous: List<CaptionCue>, incoming: List<CaptionCue>): Boolean {
        if (previous.size != incoming.size) return false
        return sameCueAnchor(previous.first(), incoming.first()) &&
            sameCueAnchor(previous.last(), incoming.last()) &&
            sameCueAnchor(previous[previous.size / 2], incoming[incoming.size / 2])
    }

    private fun isIncrementalExtension(previous: List<CaptionCue>, incoming: List<CaptionCue>): Boolean {
        if (previous.isEmpty() || incoming.isEmpty()) return false

        // Prefix append: same start cue and previous tail still aligns at same index.
        if (incoming.size > previous.size && sameCueAnchor(previous.first(), incoming.first())) {
            val checkIdx = if (previous.size >= 2) previous.lastIndex - 1 else 0
            if (sameCueAnchor(previous[checkIdx], incoming[checkIdx])) {
                return true
            }
        }

        // Live sliding window: timeline shifts forward while preserving overlap with previous tail.
        val prevLast = previous.last()
        if (prevLast.startMs >= 0L && incoming.first().startMs >= previous.first().startMs) {
            val tailMatch = incoming.any { candidate -> sameCueAnchor(prevLast, candidate) }
            if (tailMatch && incoming.last().startMs >= prevLast.startMs) {
                return true
            }
        }

        return false
    }

    private fun isForwardSegmentContinuation(
        previous: List<CaptionCue>,
        incoming: List<CaptionCue>,
    ): Boolean {
        if (previous.isEmpty() || incoming.isEmpty()) return false
        val prevFirstStart = previous.first().startMs
        val prevLast = previous.last()
        val incFirstStart = incoming.first().startMs
        val incLastStart = incoming.last().startMs
        if (prevFirstStart < 0L || prevLast.startMs < 0L || incFirstStart < 0L || incLastStart < 0L) {
            return false
        }
        val prevEndMs = maxOf(prevLast.startMs, prevLast.endMs)
        return incFirstStart > prevFirstStart &&
            incFirstStart >= prevLast.startMs &&
            incLastStart > prevLast.startMs &&
            incFirstStart >= prevEndMs - FORWARD_SEGMENT_OVERLAP_TOLERANCE_MS
    }

    private fun mergeForwardSegment(
        previous: List<CaptionCue>,
        incoming: List<CaptionCue>,
    ): List<CaptionCue> {
        val merged = ArrayList<CaptionCue>(previous.size + incoming.size)
        merged.addAll(previous)
        val seenKeys = previous.asSequence().map { it.cacheKey() }.toHashSet()
        for (cue in incoming) {
            if (seenKeys.add(cue.cacheKey())) {
                merged.add(cue)
            }
        }
        val cap = maxObservedCues.coerceAtLeast(MIN_OBSERVED_CUES_CAP)
        return if (merged.size > cap) {
            merged.subList(merged.size - cap, merged.size).toList()
        } else {
            merged
        }
    }

    private fun isAnnotationSecondaryWindow(
        previous: List<CaptionCue>,
        incoming: List<CaptionCue>,
    ): Boolean {
        if (previous.size < 2 || incoming.isEmpty()) return false
        if (incoming.size >= previous.size && incoming.size > 3) return false
        if (previous.all { isAnnotationCue(it.normalizedText()) }) return false
        if (!incoming.all { isAnnotationCue(it.normalizedText()) }) return false
        val prevStart = previous.first().startMs
        val incStart = incoming.first().startMs
        if (prevStart >= 0L && incStart >= 0L && incStart < prevStart - SECONDARY_WINDOW_TAIL_TOLERANCE_MS) {
            return false
        }
        return true
    }

    private fun isSecondaryWindow(
        previous: List<CaptionCue>,
        incoming: List<CaptionCue>,
    ): Boolean {
        if (previous.size < 2 || incoming.isEmpty() || incoming.size >= previous.size) return false
        val prevStart = previous.first().startMs
        val incStart = incoming.first().startMs
        if (prevStart < 0L || incStart < 0L) return false
        val prevEnd = previous.maxOf { maxOf(it.startMs, it.endMs) }
        val incEnd = incoming.maxOf { maxOf(it.startMs, it.endMs) }
        if (prevEnd < prevStart || incEnd < incStart) return false

        val withinSpan = incStart >= prevStart && incEnd <= prevEnd + SECONDARY_WINDOW_TAIL_TOLERANCE_MS
        if (!withinSpan) return false

        if (incoming.all { isAnnotationCue(it.normalizedText()) }) {
            return true
        }
        if (previous.size < 4 || incoming.size > MAX_NON_ANNOTATION_SECONDARY_CUES) {
            return false
        }

        val isSparseWindow = incoming.size * 4 <= previous.size
        if (!isSparseWindow) return false
        return incoming.size <= 3 || incStart >= prevStart + SECONDARY_WINDOW_MID_START_MS
    }

    private fun isPrimaryPromotingSecondary(
        previous: List<CaptionCue>,
        incoming: List<CaptionCue>,
    ): Boolean {
        if (previous.isEmpty() || previous.size > 3 || incoming.size < 4) return false
        if (previous.size * 3 > incoming.size) return false
        val prevStart = previous.first().startMs
        val incStart = incoming.first().startMs
        if (prevStart < 0L || incStart < 0L) return false
        val prevEnd = previous.maxOf { maxOf(it.startMs, it.endMs) }
        val incEnd = incoming.maxOf { maxOf(it.startMs, it.endMs) }
        return prevStart >= incStart && prevEnd <= incEnd + SECONDARY_WINDOW_TAIL_TOLERANCE_MS
    }

    private fun hasSameCueAnchorOverlap(
        previous: List<CaptionCue>,
        incoming: List<CaptionCue>,
    ): Boolean {
        val prevAnchors = HashSet<String>(previous.size * 2)
        for (cue in previous) {
            if (cue.startMs >= 0L) {
                prevAnchors.add("${cue.startMs}|${cue.normalizedText()}")
            }
        }
        if (prevAnchors.isEmpty()) return false
        return incoming.any { cue ->
            cue.startMs >= 0L && "${cue.startMs}|${cue.normalizedText()}" in prevAnchors
        }
    }

    private fun isAnnotationCue(normalizedText: String): Boolean {
        if (normalizedText.isEmpty()) return false
        return (normalizedText.startsWith("[") && normalizedText.endsWith("]")) ||
            (normalizedText.startsWith("(") && normalizedText.endsWith(")")) ||
            normalizedText.contains('♪') ||
            normalizedText.contains('♫')
    }

    private fun hasAnyOverlap(existing: Collection<CaptionCue>, incoming: List<CaptionCue>): Boolean {
        val incomingTexts = incoming.asSequence().map { it.normalizedText() }.toHashSet()
        return existing.any { it.normalizedText() in incomingTexts }
    }

    private fun sameCueAnchor(a: CaptionCue, b: CaptionCue): Boolean =
        a.startMs == b.startMs && a.normalizedText() == b.normalizedText()

    private fun normalize(text: String): String =
        CaptionCue.normalize(text)

    private data class TranslationEntry(
        val translated: String,
        val isFinal: Boolean,
    )

    private class CueIndexSnapshot(
        val cues: List<CaptionCue>,
        val cueTexts: List<String>,
        private val positionsByNormalizedText: Map<String, IntArray>,
    ) {
        fun hasExactCue(normalized: String): Boolean =
            positionsByNormalizedText.containsKey(normalized)

        fun hasOccurrenceNearTime(
            normalized: String,
            targetStartMs: Long,
            toleranceMs: Long,
        ): Boolean {
            val positions = positionsByNormalizedText[normalized] ?: return false
            for (idx in positions) {
                val startMs = cues[idx].startMs
                if (startMs >= 0L && kotlin.math.abs(startMs - targetStartMs) <= toleranceMs) {
                    return true
                }
            }
            return false
        }

        fun nearestIndexForText(normalized: String, currentAnchor: Int): Int? {
            val positions = positionsByNormalizedText[normalized] ?: return null
            if (positions.size == 1 || currentAnchor < 0) {
                return positions[0]
            }
            var bestIdx = positions[0]
            var bestDist = kotlin.math.abs(bestIdx - currentAnchor)
            for (i in 1 until positions.size) {
                val candidate = positions[i]
                val dist = kotlin.math.abs(candidate - currentAnchor)
                if (dist < bestDist) {
                    bestDist = dist
                    bestIdx = candidate
                }
            }
            return bestIdx
        }

        companion object {
            val EMPTY = CueIndexSnapshot(emptyList(), emptyList(), emptyMap())

            fun from(source: Collection<CaptionCue>): CueIndexSnapshot {
                if (source.isEmpty()) return EMPTY
                val cues = ArrayList<CaptionCue>(source.size)
                val texts = ArrayList<String>(source.size)
                val tempPositions = HashMap<String, MutableList<Int>>(source.size * 4 / 3 + 1)
                var index = 0
                for (cue in source) {
                    val normalized = cue.normalizedText()
                    cues.add(cue)
                    texts.add(normalized)
                    if (normalized.isNotEmpty()) {
                        tempPositions.getOrPut(normalized) { ArrayList(1) }.add(index)
                    }
                    index++
                }
                val indexMap = HashMap<String, IntArray>(tempPositions.size * 4 / 3 + 1)
                for ((text, list) in tempPositions) {
                    indexMap[text] = list.toIntArray()
                }
                return CueIndexSnapshot(cues, texts, indexMap)
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_OBSERVED_CUES = 4_000
        const val NEIGHBORHOOD_RADIUS = 12
        private const val MIN_OBSERVED_CUES_CAP = 16
        private const val MIN_TRANSLATION_CAP = 64
        private const val LIVE_OVERLAY_BATCH_LIMIT = 8
        private const val MAX_RENDERED_TEXTS = 512
        private const val RENDERED_TEXT_LOG_INTERVAL_MS = 30_000L
        private const val FORWARD_SEGMENT_OVERLAP_TOLERANCE_MS = 500L
        private const val SECONDARY_WINDOW_TAIL_TOLERANCE_MS = 15_000L
        private const val SECONDARY_WINDOW_MID_START_MS = 5_000L
        private const val MAX_NON_ANNOTATION_SECONDARY_CUES = 8

        private fun defaultConfigSignature(): String =
            moduleOrNull()?.config?.translationConfigSignature().orEmpty()
    }

    data class CaptionTranslation(
        val original: String,
        val translated: String,
    )

    data class TimelineObservation(
        val epoch: Long,
        val replacedTrack: Boolean,
        val allCues: List<CaptionCue>,
        val newCues: List<CaptionCue>,
        val timelineSignature: String,
        val isSecondaryWindow: Boolean = false,
    )
}

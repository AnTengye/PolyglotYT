package icu.nullptr.polyglot.captions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionSessionTest {

    @Test
    fun provisionalAndFinalTranslations_respectFinalPrecedenceAndEpochGuard() {
        var configSig = "openai|zh-Hans|https://api.openai.com/v1/chat/completions|gpt-4o-mini"
        val session = CaptionSession(configSignatureProvider = { configSig })
        val epoch1 = session.sessionEpoch

        assertTrue(session.putProvisionalTranslation("Hello world", "你好（流式1）", epoch1))
        assertTrue(session.isProvisionalTranslation("Hello world"))
        assertEquals("你好（流式1）", session.translationFor("Hello world"))

        // Newer provisional can update earlier provisional.
        assertTrue(session.putProvisionalTranslation("Hello world", "你好世界（流式2）", epoch1))
        assertEquals("你好世界（流式2）", session.translationFor("Hello world"))

        // Final translation overwrites provisional and locks against late provisional updates.
        assertTrue(session.putFinalTranslation("Hello world", "你好，世界！", epoch1))
        assertFalse(session.isProvisionalTranslation("Hello world"))
        assertEquals("你好，世界！", session.translationFor("Hello world"))
        assertFalse(session.putProvisionalTranslation("Hello world", "过期流式分片", epoch1))
        assertEquals("你好，世界！", session.translationFor("Hello world"))

        // Advancing epoch rejects stale writes from previous tasks.
        session.clear()
        val epoch2 = session.sessionEpoch
        assertTrue(epoch2 > epoch1)
        assertFalse(session.putProvisionalTranslation("New line", "旧世代流式", epoch1))
        assertFalse(session.putFinalTranslation("New line", "旧世代终态", epoch1))
        assertNull(session.translationFor("New line"))
    }

    @Test
    fun configSignatureChange_invalidatesCachedTranslationsAndIncrementsEpoch() {
        var configSig = "openai|zh-Hans|https://api.openai.com/v1/chat/completions|gpt-4o-mini"
        val session = CaptionSession(configSignatureProvider = { configSig })
        session.observeCue(CaptionCue(startMs = 0L, endMs = 1_000L, text = "Good morning"))
        val initialEpoch = session.sessionEpoch
        session.putFinalTranslation("Good morning", "早上好", initialEpoch)
        assertEquals("早上好", session.translationFor("Good morning"))

        // Simulate user switching targetLanguage or model in settings.
        configSig = "openai|ja|https://api.openai.com/v1/chat/completions|gpt-4o-mini"
        assertNull(session.translationFor("Good morning"))
        assertTrue(session.sessionEpoch > initialEpoch)
        // Observed timeline cues remain available for re-translation.
        assertEquals(1, session.storedCueCount())
    }

    @Test
    fun observeTimelineCues_distinguishesDuplicateIncrementalAppendAndNewVideoReplacement() {
        val session = CaptionSession(configSignatureProvider = { "google|zh-Hans||" })
        val video1Part1 = listOf(
            CaptionCue(startMs = 0L, endMs = 2_000L, text = "Welcome to episode one"),
            CaptionCue(startMs = 2_000L, endMs = 4_000L, text = "Today we build an engine"),
        )
        val firstObs = session.observeTimelineCues(video1Part1)
        assertFalse(firstObs.replacedTrack)
        assertEquals(2, firstObs.newCues.size)
        session.putFinalTranslation("Welcome to episode one", "欢迎来到第一集", firstObs.epoch)

        // Exact duplicate timeline rebuild keeps translations and same epoch.
        val dupObs = session.observeTimelineCues(video1Part1)
        assertFalse(dupObs.replacedTrack)
        assertEquals(firstObs.epoch, dupObs.epoch)
        assertTrue(dupObs.newCues.isEmpty())
        assertEquals("欢迎来到第一集", session.translationFor("Welcome to episode one"))

        // Incremental prefix extension on same track appends only new cues without wiping translations.
        val video1Part2 = video1Part1 + CaptionCue(
            startMs = 4_000L,
            endMs = 6_500L,
            text = "Let us inspect the timeline",
        )
        val appendObs = session.observeTimelineCues(video1Part2)
        assertFalse(appendObs.replacedTrack)
        assertEquals(firstObs.epoch, appendObs.epoch)
        assertEquals(1, appendObs.newCues.size)
        assertEquals("Let us inspect the timeline", appendObs.newCues.single().normalizedText())
        assertEquals("欢迎来到第一集", session.translationFor("Welcome to episode one"))

        // Switching to Video 2 (same language, completely different timeline) replaces session & bumps epoch.
        val video2Cues = listOf(
            CaptionCue(startMs = 500L, endMs = 2_500L, text = "Different video starts here"),
            CaptionCue(startMs = 2_500L, endMs = 5_000L, text = "Completely new topic"),
        )
        val replaceObs = session.observeTimelineCues(video2Cues)
        assertTrue(replaceObs.replacedTrack)
        assertTrue(replaceObs.epoch > appendObs.epoch)
        assertNull(session.translationFor("Welcome to episode one"))
        assertEquals(2, session.storedCueCount())
    }

    @Test
    fun multiLineRollupAndBoundedNeighborhoodLookup_workInConstantBoundedScope() {
        val session = CaptionSession(configSignatureProvider = { "openai|zh-Hans|url|m" })

        val cues = (0 until 200).map { idx ->
            CaptionCue(
                startMs = idx * 1_000L,
                endMs = (idx + 1) * 1_000L,
                text = "Cue number $idx with unique phrase token_$idx end",
            )
        }
        session.observeTimelineCues(cues)
        session.putFinalTranslation(cues[10].text, "第10条译文")
        session.putFinalTranslation(cues[11].text, "第11条译文")
        session.putFinalTranslation(cues[150].text, "第150条远端译文")

        // 1) Multi-line roll-up (\n) stitches line-by-line O(1) translations.
        val rollupRaw = "${cues[10].text}\n${cues[11].text}"
        assertEquals("第10条译文\n第11条译文", session.translationFor(rollupRaw))

        // Anchor is now at cue #11; fragment inside +/-12 neighborhood (cue #10) is matched.
        val nearFrag = session.translatedCueContaining("unique phrase token_10")
        assertNotNull(nearFrag)
        assertEquals("第10条译文", nearFrag?.translated)

        // Fragment at cue #150 is far outside +/-12 neighborhood from anchor #10 -> NOT scanned.
        assertNull(session.translatedCueContaining("unique phrase token_150"))

        // Simulate user Seeking to 148s via live OverlayUpdateHook batch -> anchor jumps to #148.
        session.observeNewCues(listOf(cues[148]))
        val postSeekFrag = session.translatedCueContaining("unique phrase token_150")
        assertNotNull(postSeekFrag)
        assertEquals("第150条远端译文", postSeekFrag?.translated)
    }

    @Test
    fun standaloneTranslatedShortCue_isNotShadowedByLongerNeighboringCue() {
        val session = CaptionSession(configSignatureProvider = { "google|zh-Hans||" })
        val shortCue = CaptionCue(startMs = 1_000L, endMs = 2_000L, text = "Yes")
        val longCue = CaptionCue(startMs = 2_000L, endMs = 4_000L, text = "Yes, I completely agree")
        session.observeTimelineCues(listOf(shortCue, longCue))
        session.putFinalTranslation("Yes", "是的")
        session.putFinalTranslation("Yes, I completely agree", "是的，我完全同意")

        // "Yes" has its own exact translation, so translatedCueContaining("Yes") must return null
        // rather than matching "Yes, I completely agree" and blanking out the short cue.
        assertNull(session.translatedCueContaining("Yes"))
        assertEquals("是的", session.translationFor("Yes"))
    }

    @Test
    fun boundedCapacity_evictsOldestObservedCuesWhenCapExceeded() {
        val session = CaptionSession(
            maxObservedCues = 20,
            configSignatureProvider = { "google|zh-Hans||" },
        )
        for (i in 0 until 35) {
            session.observeCue(
                CaptionCue(startMs = i * 1_000L, endMs = (i + 1) * 1_000L, text = "Line $i"),
            )
        }

        assertEquals(35, session.observedCueCount())
        assertEquals(20, session.storedCueCount())
        val remainingTexts = session.allObservedCues().map { it.normalizedText() }
        assertFalse("Line 0" in remainingTexts)
        assertTrue("Line 34" in remainingTexts)
    }

    @Test
    fun repeatedIdenticalShortCues_preserveNearestAnchorInsteadOfJumpingToStart() {
        val session = CaptionSession(configSignatureProvider = { "google|zh-Hans||" })
        val cues = (0 until 200).map { idx ->
            val text = if (idx == 3 || idx == 180) "Yeah." else "Unique sentence at $idx with marker_$idx"
            CaptionCue(startMs = idx * 1_000L, endMs = (idx + 1) * 1_000L, text = text)
        }
        session.observeTimelineCues(cues)
        session.putFinalTranslation("Yeah.", "是的。")
        session.putFinalTranslation(cues[179].text, "第179句译文")
        session.putFinalTranslation(cues[182].text, "第182句译文")

        // Establish anchor at #179, then render repeated cue "Yeah." at #180.
        assertEquals("第179句译文", session.translationFor(cues[179].text))
        assertEquals("是的。", session.translationFor("Yeah."))

        // Anchor must stay at #180 (not jump back to #3), so #182 fragment is still within +/-12!
        val near182 = session.translatedCueContaining("marker_182")
        assertNotNull(near182)
        assertEquals("第182句译文", near182?.translated)
    }

    @Test
    fun liveSlidingWindowTimeline_preservesTranslationsWithoutTrackReset() {
        val session = CaptionSession(configSignatureProvider = { "google|zh-Hans||" })
        val window1 = listOf(
            CaptionCue(startMs = 10_000L, endMs = 12_000L, text = "Live cue A"),
            CaptionCue(startMs = 12_000L, endMs = 14_000L, text = "Live cue B"),
        )
        val obs1 = session.observeTimelineCues(window1)
        session.putFinalTranslation("Live cue B", "直播字幕B", obs1.epoch)

        // Sliding window drops A and appends C while overlapping on B.
        val window2 = listOf(
            CaptionCue(startMs = 12_000L, endMs = 14_000L, text = "Live cue B"),
            CaptionCue(startMs = 14_000L, endMs = 16_000L, text = "Live cue C"),
        )
        val obs2 = session.observeTimelineCues(window2)
        assertFalse(obs2.replacedTrack)
        assertEquals(obs1.epoch, obs2.epoch)
        assertEquals("直播字幕B", session.translationFor("Live cue B"))
        assertEquals(1, obs2.newCues.size)
        assertEquals("Live cue C", obs2.newCues.single().normalizedText())
    }
}

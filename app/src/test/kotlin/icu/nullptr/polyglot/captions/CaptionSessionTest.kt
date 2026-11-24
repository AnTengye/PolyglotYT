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
    fun untranslatedStandaloneShortCue_andMidWordSubstring_neverMatchNeighboringTranslatedCues() {
        val session = CaptionSession(configSignatureProvider = { "google|zh-Hans||" })
        val untranslatedShort = CaptionCue(startMs = 1_000L, endMs = 2_000L, text = "Yes")
        val translatedLong = CaptionCue(startMs = 2_000L, endMs = 4_000L, text = "Yes, I completely agree")
        val translatedHero = CaptionCue(startMs = 4_000L, endMs = 6_000L, text = "the hero arrives now")
        session.observeTimelineCues(listOf(untranslatedShort, translatedLong, translatedHero))

        // Only the neighbors are translated so far; "Yes" is still untranslated!
        session.putFinalTranslation("Yes, I completely agree", "是的，我完全同意")
        session.putFinalTranslation("the hero arrives now", "英雄现在登场")

        assertTrue(session.isKnownStandaloneCue("Yes"))
        assertNull("Untranslated standalone cue must NOT be swallowed by neighbor", session.translatedCueContaining("Yes"))
        assertNull("Mid-word 'he' must NOT match 'the' or 'hero'", session.translatedCueContaining("he"))

        // Genuine word-bounded fragment (not a standalone cue) still matches its parent cue:
        val validFrag = session.translatedCueContaining("hero arrives now")
        assertNotNull(validFrag)
        assertEquals("英雄现在登场", validFrag?.translated)
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

    @Test
    fun reproA_secondaryWindowAndForwardDashSegment_preservePrimaryTimelineAndTranslations() {
        val session = CaptionSession(configSignatureProvider = { "openai|zh-Hans|u|m" })
        val primary = (0 until 10).map { i ->
            CaptionCue(
                startMs = i * 3_000L,
                endMs = (i + 1) * 3_000L,
                text = "Primary dialogue sentence $i.",
            )
        }
        val obs1 = session.observeTimelineCues(primary)
        assertFalse(obs1.replacedTrack)
        assertFalse(obs1.isSecondaryWindow)
        session.putFinalTranslation("Primary dialogue sentence 0.", "主对话第0句。", obs1.epoch)
        session.putFinalTranslation("Primary dialogue sentence 5.", "主对话第5句。", obs1.epoch)

        // 1) Secondary window ([Music], [Applause]) arrives mid-playback -> MUST NOT wipe primary timeline!
        val secondaryMusic = listOf(
            CaptionCue(startMs = 5_000L, endMs = 8_000L, text = "[Music]"),
            CaptionCue(startMs = 20_000L, endMs = 23_000L, text = "[Applause]"),
        )
        val secObs = session.observeTimelineCues(secondaryMusic)
        assertFalse("Secondary window must not replace primary track", secObs.replacedTrack)
        assertTrue("Secondary window must be flagged as secondary", secObs.isSecondaryWindow)
        assertEquals(obs1.epoch, secObs.epoch)
        assertEquals(10, session.currentTimelineCues().size)
        assertEquals("主对话第0句。", session.translationFor("Primary dialogue sentence 0."))
        assertEquals(2, secObs.newCues.size)

        // 2) Non-overlapping forward DASH segment (30s..42s) -> merges forward without wiping!
        val dashSeg2 = (10 until 14).map { i ->
            CaptionCue(
                startMs = i * 3_000L,
                endMs = (i + 1) * 3_000L,
                text = "Primary dialogue sentence $i.",
            )
        }
        val seg2Obs = session.observeTimelineCues(dashSeg2)
        assertFalse("Forward DASH segment must not wipe session", seg2Obs.replacedTrack)
        assertFalse(seg2Obs.isSecondaryWindow)
        assertEquals(obs1.epoch, seg2Obs.epoch)
        assertEquals(14, seg2Obs.allCues.size)
        assertEquals(4, seg2Obs.newCues.size)
        assertEquals("主对话第0句。", session.translationFor("Primary dialogue sentence 0."))
        assertEquals("主对话第5句。", session.translationFor("Primary dialogue sentence 5."))

        // 3) Genuine switch to a completely new 8-cue video starting at 0s -> MUST replace track!
        val newVideo = (0 until 8).map { i ->
            CaptionCue(
                startMs = i * 3_000L,
                endMs = (i + 1) * 3_000L,
                text = "Brand new video line $i.",
            )
        }
        val switchObs = session.observeTimelineCues(newVideo)
        assertTrue("Genuine video switch must replace track", switchObs.replacedTrack)
        assertTrue(switchObs.epoch > obs1.epoch)
        assertNull(session.translationFor("Primary dialogue sentence 0."))
    }

    @Test
    fun primaryTimelineArrivingAfterTinySecondaryWindow_promotesWithoutWiping() {
        val session = CaptionSession(configSignatureProvider = { "openai|zh-Hans|u|m" })
        val secondaryFirst = listOf(
            CaptionCue(startMs = 5_000L, endMs = 8_000L, text = "[Music]"),
        )
        val obsSec = session.observeTimelineCues(secondaryFirst)
        session.putFinalTranslation("[Music]", "[音乐]", obsSec.epoch)

        val primarySecond = (0 until 8).map { i ->
            CaptionCue(startMs = i * 3_000L, endMs = (i + 1) * 3_000L, text = "Main line $i.")
        }
        val obsMain = session.observeTimelineCues(primarySecond)
        assertFalse(obsMain.replacedTrack)
        assertFalse(obsMain.isSecondaryWindow)
        assertEquals(obsSec.epoch, obsMain.epoch)
        assertEquals(8, session.currentTimelineCues().size)
        assertEquals("[音乐]", session.translationFor("[Music]"))
    }

    @Test
    fun genuineVideoSwitchFromLongToShorterOrMidStartVideo_stillReplacesTrack() {
        val session = CaptionSession(configSignatureProvider = { "openai|zh-Hans|u|m" })

        // Video A: 60 cues (0..180s)
        val longVideo = (0 until 60).map { i ->
            CaptionCue(startMs = i * 3_000L, endMs = (i + 1) * 3_000L, text = "Long video sentence $i.")
        }
        val obsLong = session.observeTimelineCues(longVideo)
        session.putFinalTranslation("Long video sentence 0.", "长视频第0句。", obsLong.epoch)

        // Switch to Video B: 15 cues (0..45s) — must replace track, NOT be misclassified as secondary window!
        val mediumVideo = (0 until 15).map { i ->
            CaptionCue(startMs = i * 3_000L, endMs = (i + 1) * 3_000L, text = "Medium video line $i.")
        }
        val obsMedium = session.observeTimelineCues(mediumVideo)
        assertTrue("Switching from 60-cue video to 15-cue video must replace track", obsMedium.replacedTrack)
        assertFalse(obsMedium.isSecondaryWindow)
        assertNull(session.translationFor("Long video sentence 0."))

        // Switch to Video C: 12 cues starting at 6s (after 6s intro music) — must replace track!
        val midStartVideo = (0 until 12).map { i ->
            CaptionCue(startMs = 6_000L + i * 3_000L, endMs = 9_000L + i * 3_000L, text = "Intro-delayed video $i.")
        }
        val obsMidStart = session.observeTimelineCues(midStartVideo)
        assertTrue("Switching to a video starting at 6s must replace track", obsMidStart.replacedTrack)
        assertFalse(obsMidStart.isSecondaryWindow)
    }

    @Test
    fun forwardSegmentContinuation_rejectsBackwardTimestampInversion() {
        val session = CaptionSession(configSignatureProvider = { "openai|zh-Hans|u|m" })
        val seg1 = listOf(
            CaptionCue(startMs = 0L, endMs = 5_000L, text = "Seg1 line 0."),
            CaptionCue(startMs = 11_000L, endMs = 11_200L, text = "Seg1 line 1."),
        )
        session.observeTimelineCues(seg1)

        // Incoming starts BEFORE seg1.last().startMs (10_800 < 11_000) -> must NOT append out-of-order!
        val inverted = listOf(
            CaptionCue(startMs = 10_800L, endMs = 13_000L, text = "Inverted line 0."),
            CaptionCue(startMs = 13_000L, endMs = 16_000L, text = "Inverted line 1."),
        )
        val obs = session.observeTimelineCues(inverted)
        assertTrue("Backward-overlapping unrelated segment must replace instead of inverting order", obs.replacedTrack)
    }

    @Test
    fun annotationSecondaryWindowBeyondCurrentDashSegment_doesNotCorruptSubsequentForwardDashSegments() {
        val session = CaptionSession(configSignatureProvider = { "openai|zh-Hans|u|m" })
        val seg1 = (0 until 6).map { i ->
            CaptionCue(startMs = i * 3_000L, endMs = (i + 1) * 3_000L, text = "DASH seg1 line $i.")
        }
        val obs1 = session.observeTimelineCues(seg1)
        session.putFinalTranslation("DASH seg1 line 0.", "第一段第0句。", obs1.epoch)

        // Secondary [Music] window arrives with timestamp 45s (beyond seg1's 18s end!) ->
        // MUST be treated as secondary window, NOT appended as forward continuation!
        val secObs = session.observeTimelineCues(
            listOf(CaptionCue(startMs = 45_000L, endMs = 48_000L, text = "[Music]")),
        )
        assertFalse(secObs.replacedTrack)
        assertTrue("Future [Music] must be classified as secondary window", secObs.isSecondaryWindow)
        assertEquals(6, session.currentTimelineCues().size)

        // Subsequent DASH segment 2 (18s..24s) MUST still merge forward cleanly without wiping seg1!
        val seg2 = listOf(
            CaptionCue(startMs = 18_000L, endMs = 21_000L, text = "DASH seg2 line 6."),
            CaptionCue(startMs = 21_000L, endMs = 24_000L, text = "DASH seg2 line 7."),
        )
        val obs2 = session.observeTimelineCues(seg2)
        assertFalse("DASH seg2 must merge forward without wiping seg1", obs2.replacedTrack)
        assertFalse(obs2.isSecondaryWindow)
        assertEquals(obs1.epoch, obs2.epoch)
        assertEquals(8, session.currentTimelineCues().size)
        assertEquals("第一段第0句。", session.translationFor("DASH seg1 line 0."))
    }
}

package icu.nullptr.polyglot.captions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionCueTest {

    @Test
    fun normalize_collapsesWhitespaceAndTrims() {
        assertEquals("Hello world!", CaptionCue.normalize("  Hello \n\t  world!  "))
        assertEquals("", CaptionCue.normalize("   \n\t  "))
    }

    @Test
    fun normalizeLines_splitsMultiLineAndSkipsBlankLines() {
        val lines = CaptionCue.normalizeLines("  first   line \r\n\n  second\tline  \n ")
        assertEquals(listOf("first line", "second line"), lines)
        assertTrue(CaptionCue.normalizeLines("   \n  ").isEmpty())
    }

    @Test
    fun durationAndCacheKey_computeExpectedValues() {
        val cue = CaptionCue(
            videoId = "vid123",
            startMs = 1_200L,
            endMs = 3_700L,
            text = "  Testing   cue ",
            windowId = 2,
        )
        assertEquals(2_500L, cue.durationMs())
        assertEquals("vid123|2|1200|3700|Testing cue", cue.cacheKey())

        val unknownEnd = CaptionCue(startMs = 1_000L, endMs = CaptionCue.UNKNOWN_TIME_MS, text = "x")
        assertEquals(0L, unknownEnd.durationMs())
    }

    @Test
    fun containsBoundedFragment_enforcesWordPunctuationAndCjkBoundaries() {
        assertFalse(CaptionCue.containsBoundedFragment("the hero is here", "he"))
        assertTrue(CaptionCue.containsBoundedFragment("he is here", "he"))
        assertTrue(CaptionCue.containsBoundedFragment("is it he?", "he"))
        assertTrue(CaptionCue.containsBoundedFragment("Hello world, how are you?", "Hello world,"))
        assertTrue(CaptionCue.containsBoundedFragment("Hello world, how are you?", "how are you?"))
        assertFalse(CaptionCue.containsBoundedFragment("Exact same line", "Exact same line"))
        assertTrue(CaptionCue.containsBoundedFragment("今天天气真的非常好", "天气真的"))
    }
}

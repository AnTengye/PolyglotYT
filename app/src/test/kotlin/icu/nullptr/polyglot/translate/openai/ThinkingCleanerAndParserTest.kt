package icu.nullptr.polyglot.translate.openai

import icu.nullptr.polyglot.translate.TranslationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkingCleanerAndParserTest {

    @Test
    fun `ThinkingCleaner strips paired think and thinking blocks with leading whitespace`() {
        val raw = "  \n<think>step 1\nstep 2</think>\n<THINKING>more thoughts</THINKING>[{\"i\":0,\"t\":\"你好\"}]"
        val res = ThinkingCleaner.clean(raw)
        assertTrue(res.hadThinking)
        assertFalse(res.hadUnclosedThink)
        assertEquals("[{\"i\":0,\"t\":\"你好\"}]", res.text)
    }

    @Test
    fun `ThinkingCleaner handles orphan closing tag from distilled reasoning models`() {
        val raw = "Let me translate this carefully.\n</think>\n[{\"i\":0,\"t\":\"早上好\"}]"
        val res = ThinkingCleaner.clean(raw)
        assertTrue(res.hadThinking)
        assertFalse(res.hadUnclosedThink)
        assertEquals("[{\"i\":0,\"t\":\"早上好\"}]", res.text)
    }

    @Test
    fun `ThinkingCleaner truncates unclosed think draft and stop markers`() {
        val unclosed = "<think>draft translation that ran out of max_tokens..."
        val res1 = ThinkingCleaner.clean(unclosed)
        assertTrue(res1.hadThinking)
        assertTrue(res1.hadUnclosedThink)
        assertEquals("", res1.text)

        val withStop = "[{\"i\":0,\"t\":\"测试\"}]<|im_end|>hallucinated turn"
        val res2 = ThinkingCleaner.clean(withStop)
        assertEquals("[{\"i\":0,\"t\":\"测试\"}]", res2.text)
    }

    @Test
    fun `ThinkingCleaner holds back trailing partial tag during streaming`() {
        assertEquals("hello ", ThinkingCleaner.stripTrailingPartialTag("hello <thi"))
        assertEquals("hello ", ThinkingCleaner.stripTrailingPartialTag("hello </thinki"))
        assertEquals("hello < custom > world", ThinkingCleaner.stripTrailingPartialTag("hello < custom > world"))
    }

    @Test
    fun `OpenAIResponseParser aligns indexed objects and preserves missing holes without shifting`() {
        val raw = """
            ```json
            [{"i":0,"t":"第一句"},{"i":2,"t":"第三句"}]
            ```
        """.trimIndent()
        val parsed = OpenAIResponseParser.parseBatch(raw, expectedCount = 3)
        assertEquals(mapOf(0 to "第一句", 2 to "第三句"), parsed.indexed)

        val originals = listOf("First", "Second", "Third")
        val aligned = TranslationResult.aligned(originals, parsed.indexed)
        assertEquals(listOf("第一句", "", "第三句"), aligned.texts)
        assertEquals(listOf(1), aligned.missingIndices(originals))
    }

    @Test
    fun `OpenAIResponseParser keeps slot 0 missing when 0-based partial response omits index 0`() {
        val raw = """[{"i":1,"t":"世界"}]"""
        val parsed = OpenAIResponseParser.parseBatch(raw, expectedCount = 2)
        assertEquals(mapOf(1 to "世界"), parsed.indexed)
    }

    @Test
    fun `OpenAIResponseParser normalizes full 1-based indexed array`() {
        val raw = """[{"i":1,"t":"甲"},{"i":2,"t":"乙"}]"""
        val parsed = OpenAIResponseParser.parseBatch(raw, expectedCount = 2)
        assertEquals(mapOf(0 to "甲", 1 to "乙"), parsed.indexed)
    }

    @Test
    fun `OpenAIResponseParser unwraps container object and excludes source keys`() {
        val raw = """
            Here is the JSON output:
            {"translations": [
                {"原文": "Good morning", "最终结果": "早上好"},
                {"source": "Thank you", "custom_target": "谢谢"}
            ]}
        """.trimIndent()
        val parsed = OpenAIResponseParser.parseBatch(raw, expectedCount = 2)
        assertEquals(mapOf(0 to "早上好", 1 to "谢谢"), parsed.indexed)
    }

    @Test
    fun `OpenAIResponseParser expands single-element structured block and numbered plaintext`() {
        val singleBlock = """["1. 你回来啦。\n2. 欢迎回家。"]"""
        val parsed1 = OpenAIResponseParser.parseBatch(singleBlock, expectedCount = 2)
        assertEquals(mapOf(0 to "你回来啦。", 1 to "欢迎回家。"), parsed1.indexed)

        val numberedPlain = "1、第一行译文\n2、第二行译文"
        val parsed2 = OpenAIResponseParser.parseBatch(numberedPlain, expectedCount = 2)
        assertEquals(mapOf(0 to "第一行译文", 1 to "第二行译文"), parsed2.indexed)
    }

    @Test
    fun `OpenAIResponseParser incrementally extracts only closed items outside think block`() {
        val chunk1 = "<think>analyzing context..."
        assertTrue(OpenAIResponseParser.extractProvisionalItems(chunk1, 2).isEmpty())

        val chunk2 = "<think>done</think>[{\"i\":0,\"t\":\"首句已闭合\"},{\"i\":1,\"t\":\"半句未闭"
        assertEquals(
            mapOf(0 to "首句已闭合"),
            OpenAIResponseParser.extractProvisionalItems(chunk2, 2),
        )

        val chunk3 = "$chunk2\"}]"
        assertEquals(
            mapOf(0 to "首句已闭合", 1 to "半句未闭"),
            OpenAIResponseParser.extractProvisionalItems(chunk3, 2),
        )
    }
}

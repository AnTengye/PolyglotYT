package icu.nullptr.polyglot.translate.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TranslationCacheTest {

    @Test
    fun `CacheKeyBuilder excludes apiKey and normalizes endpoint and whitespace`() {
        val key1 = CacheKeyBuilder.build(
            provider = "openai-compatible",
            endpoint = "https://API.OpenAI.com/v1/",
            model = "gpt-4o-mini",
            sourceLanguage = "EN",
            targetLanguage = "zh-Hans",
            text = "  Hello world!\r\n",
        )
        val key2 = CacheKeyBuilder.build(
            provider = "openai",
            endpoint = "https://api.openai.com/v1/chat/completions",
            model = "gpt-4o-mini",
            sourceLanguage = "en",
            targetLanguage = "zh-hans",
            text = "Hello world!",
        )
        val differentModelKey = CacheKeyBuilder.build(
            provider = "openai",
            endpoint = "https://api.openai.com/v1/chat/completions",
            model = "deepseek-chat",
            sourceLanguage = "en",
            targetLanguage = "zh-hans",
            text = "Hello world!",
        )

        assertEquals(64, key1.length)
        assertEquals(key1, key2)
        assertNotEquals(key1, differentModelKey)
    }

    @Test
    fun `TranslationCache enforces L1 LRU limit, backfills from L2, and rejects silent blanks`() {
        var fakeNow = 1_000L
        val disk = BoundedMemoryDiskStore(maxEntries = 10, maxBytes = 10_000L, nowMs = { fakeNow })
        val cache = TranslationCache(diskStore = disk, maxMemoryEntries = 3)

        cache.putBatch(
            mapOf(
                "k1" to "译1",
                "k2" to "译2",
                "k3" to "译3",
                "empty" to "   ",
                "failed" to "[翻译失败]",
            ),
        )

        assertEquals(3, cache.memorySize())
        assertEquals(3, disk.size())
        assertNull(cache.get("empty"))
        assertNull(cache.get("failed"))

        // Touch k1 in L1 so k2 becomes the eldest entry in L1
        assertEquals("译1", cache.getMemoryOnly("k1"))
        cache.put("k4", "译4")

        assertEquals(3, cache.memorySize())
        assertNull(cache.getMemoryOnly("k2")) // Evicted from L1!
        assertEquals("译1", cache.getMemoryOnly("k1"))

        // Querying getBatch("k2") recovers it from L2 disk store and backfills L1!
        fakeNow += 500L
        val recovered = cache.getBatch(listOf("k2", "missing"))
        assertEquals(mapOf("k2" to "译2"), recovered)
        assertEquals("译2", cache.getMemoryOnly("k2"))
    }

    @Test
    fun `BoundedMemoryDiskStore evicts oldest updated_at entries by count and byte budget`() {
        var now = 10_000L
        val store = BoundedMemoryDiskStore(maxEntries = 3, maxBytes = 1024L, nowMs = { now })

        store.putBatch(mapOf("a" to "111"))
        now += 10
        store.putBatch(mapOf("b" to "222"))
        now += 10
        store.putBatch(mapOf("c" to "333"))

        // Touch "a" so "b" becomes oldest updated_at
        now += 10
        assertEquals(mapOf("a" to "111"), store.getBatch(listOf("a")))

        now += 10
        store.putBatch(mapOf("d" to "444"))
        assertEquals(3, store.size())
        assertEquals(mapOf("a" to "111", "c" to "333", "d" to "444"), store.getBatch(listOf("a", "b", "c", "d")))

        // Byte-budget eviction test
        val tightByteStore = BoundedMemoryDiskStore(maxEntries = 100, maxBytes = 30L, nowMs = { now++ })
        tightByteStore.putBatch(mapOf("k1" to "1234567890", "k2" to "abcdefghij"))
        assertTrue(tightByteStore.totalByteSize() <= 30L)

        val third = "ABCDEFGHIJ"
        tightByteStore.putBatch(mapOf("k3" to third))
        assertTrue(tightByteStore.totalByteSize() <= 30L)
        assertFalse("k1" in tightByteStore.getBatch(listOf("k1", "k2", "k3")))
    }

    @Test
    fun `TranslationCache fails open when diskStore throws and cleans SQLite sidecar files`() {
        val brokenStore = object : TranslationDiskStore {
            override fun getBatch(keys: Collection<String>): Map<String, String> {
                throw IllegalStateException("Simulated corrupt SQLite page")
            }

            override fun putBatch(entries: Map<String, String>) {
                throw IllegalStateException("Simulated disk full")
            }

            override fun clear() {
                throw IllegalStateException("Simulated I/O error")
            }
        }

        val cache = TranslationCache(diskStore = brokenStore, maxMemoryEntries = 16)
        cache.put("k1", "内存中仍可用")
        assertEquals("内存中仍可用", cache.get("k1"))
        assertEquals(emptyMap<String, String>(), cache.getBatch(listOf("missing")))
        cache.clear()
        assertEquals(0, cache.memorySize())

        val tempDir = Files.createTempDirectory("polyglot-sqlite-test").toFile()
        try {
            val dbFile = File(tempDir, "translation_cache.db")
            val walFile = File(tempDir, "translation_cache.db-wal")
            val shmFile = File(tempDir, "translation_cache.db-shm")
            dbFile.writeText("corrupt")
            walFile.writeText("wal")
            shmFile.writeText("shm")

            SqliteTranslationDiskStore.deleteDatabaseFiles(dbFile)
            assertFalse(dbFile.exists())
            assertFalse(walFile.exists())
            assertFalse(shmFile.exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }
}

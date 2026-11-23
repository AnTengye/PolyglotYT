package icu.nullptr.polyglot.translate.cache

interface TranslationDiskStore {
    fun getBatch(keys: Collection<String>): Map<String, String>

    fun putBatch(entries: Map<String, String>)

    fun clear()

    fun close() {}
}

/**
 * Bounded `updated_at`-ordered store sharing the exact same row-count and byte-budget
 * eviction contract as [SqliteTranslationDiskStore], usable for deterministic JVM unit tests
 * or fallback in-process persistence simulation.
 */
class BoundedMemoryDiskStore(
    private val maxEntries: Int = TranslationCache.DEFAULT_L2_MAX_ENTRIES,
    private val maxBytes: Long = TranslationCache.DEFAULT_L2_MAX_BYTES,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : TranslationDiskStore {

    private data class Record(
        val value: String,
        val byteSize: Int,
        var updatedAt: Long,
        var seq: Long,
    )

    private val lock = Any()
    private val rows = HashMap<String, Record>()
    private var totalBytes: Long = 0L
    private var sequence: Long = 0L

    override fun getBatch(keys: Collection<String>): Map<String, String> {
        if (keys.isEmpty()) return emptyMap()
        synchronized(lock) {
            val now = nowMs()
            val result = LinkedHashMap<String, String>()
            for (key in keys) {
                val record = rows[key] ?: continue
                if (!CacheKeyBuilder.isCacheableTranslation(record.value)) {
                    totalBytes -= record.byteSize
                    rows.remove(key)
                    continue
                }
                record.updatedAt = now
                record.seq = ++sequence
                result[key] = record.value
            }
            return result
        }
    }

    override fun putBatch(entries: Map<String, String>) {
        if (entries.isEmpty()) return
        synchronized(lock) {
            val now = nowMs()
            for ((key, rawValue) in entries) {
                if (key.isBlank() || !CacheKeyBuilder.isCacheableTranslation(rawValue)) continue
                val value = rawValue.trim()
                val byteSize = entryByteSize(key, value)
                if (byteSize > maxBytes) continue

                rows.remove(key)?.let { previous ->
                    totalBytes -= previous.byteSize
                }
                rows[key] = Record(
                    value = value,
                    byteSize = byteSize,
                    updatedAt = now,
                    seq = ++sequence,
                )
                totalBytes += byteSize
            }
            evictIfNeededLocked()
        }
    }

    override fun clear() {
        synchronized(lock) {
            rows.clear()
            totalBytes = 0L
        }
    }

    fun size(): Int = synchronized(lock) { rows.size }

    fun totalByteSize(): Long = synchronized(lock) { totalBytes }

    private fun evictIfNeededLocked() {
        if (rows.size <= maxEntries && totalBytes <= maxBytes) return
        val ordered = rows.entries.sortedWith(
            compareBy<Map.Entry<String, Record>> { it.value.updatedAt }
                .thenBy { it.value.seq },
        )
        for (entry in ordered) {
            if (rows.size <= maxEntries && totalBytes <= maxBytes) break
            rows.remove(entry.key)?.let { removed ->
                totalBytes -= removed.byteSize
            }
        }
        if (totalBytes < 0L) totalBytes = 0L
    }

    companion object {
        internal fun entryByteSize(key: String, value: String): Int =
            key.toByteArray(Charsets.UTF_8).size + value.toByteArray(Charsets.UTF_8).size
    }
}

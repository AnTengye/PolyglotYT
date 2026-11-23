package icu.nullptr.polyglot.translate.cache

class TranslationCache(
    @Volatile var diskStore: TranslationDiskStore? = null,
    private val maxMemoryEntries: Int = DEFAULT_L1_MAX_ENTRIES,
) {
    private val memoryLock = Any()
    private val memoryLru = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > maxMemoryEntries
    }

    fun getMemoryOnly(key: String): String? {
        if (key.isBlank()) return null
        synchronized(memoryLock) {
            val cached = memoryLru[key] ?: return null
            if (!CacheKeyBuilder.isCacheableTranslation(cached)) {
                memoryLru.remove(key)
                return null
            }
            return cached
        }
    }

    fun get(key: String): String? {
        if (key.isBlank()) return null
        return getBatch(listOf(key))[key]
    }

    fun getBatch(keys: Collection<String>): Map<String, String> {
        if (keys.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>(keys.size)
        val misses = ArrayList<String>()

        synchronized(memoryLock) {
            for (key in keys) {
                if (key.isBlank() || key in result) continue
                val inMemory = memoryLru[key]
                if (CacheKeyBuilder.isCacheableTranslation(inMemory)) {
                    result[key] = inMemory!!
                } else {
                    if (inMemory != null) memoryLru.remove(key)
                    misses.add(key)
                }
            }
        }

        val store = diskStore
        if (misses.isNotEmpty() && store != null) {
            val fromDisk = runCatching { store.getBatch(misses) }.getOrDefault(emptyMap())
            if (fromDisk.isNotEmpty()) {
                synchronized(memoryLock) {
                    for ((key, value) in fromDisk) {
                        if (CacheKeyBuilder.isCacheableTranslation(value)) {
                            val clean = value.trim()
                            memoryLru[key] = clean
                            result[key] = clean
                        }
                    }
                }
            }
        }

        return result
    }

    fun putMemoryOnly(entries: Map<String, String>) {
        if (entries.isEmpty()) return
        synchronized(memoryLock) {
            for ((key, value) in entries) {
                if (key.isNotBlank() && CacheKeyBuilder.isCacheableTranslation(value)) {
                    memoryLru[key] = value.trim()
                }
            }
        }
    }

    fun put(key: String, value: String) {
        putBatch(mapOf(key to value))
    }

    fun putBatch(entries: Map<String, String>) {
        if (entries.isEmpty()) return
        val cleanEntries = LinkedHashMap<String, String>(entries.size)
        for ((key, value) in entries) {
            if (key.isNotBlank() && CacheKeyBuilder.isCacheableTranslation(value)) {
                cleanEntries[key] = value.trim()
            }
        }
        if (cleanEntries.isEmpty()) return

        synchronized(memoryLock) {
            memoryLru.putAll(cleanEntries)
        }

        runCatching {
            diskStore?.putBatch(cleanEntries)
        }
    }

    fun clear() {
        synchronized(memoryLock) {
            memoryLru.clear()
        }
        runCatching {
            diskStore?.clear()
        }
    }

    fun memorySize(): Int = synchronized(memoryLock) { memoryLru.size }

    companion object {
        const val DEFAULT_L1_MAX_ENTRIES = 2048
        const val DEFAULT_L2_MAX_ENTRIES = 10_000
        const val DEFAULT_L2_MAX_BYTES = 32L * 1024L * 1024L
    }
}

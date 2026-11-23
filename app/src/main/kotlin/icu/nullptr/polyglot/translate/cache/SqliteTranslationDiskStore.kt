package icu.nullptr.polyglot.translate.cache

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import icu.nullptr.polyglot.util.logW
import java.io.File

class SqliteTranslationDiskStore(
    private val context: Context,
    private val dbFile: File = File(context.cacheDir, DEFAULT_RELATIVE_DB_PATH),
    private val maxEntries: Int = TranslationCache.DEFAULT_L2_MAX_ENTRIES,
    private val maxBytes: Long = TranslationCache.DEFAULT_L2_MAX_BYTES,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : TranslationDiskStore {

    private val lock = Any()
    private var helper: CacheDbHelper = createHelper()

    override fun getBatch(keys: Collection<String>): Map<String, String> {
        val uniqueKeys = keys.asSequence().filter { it.isNotBlank() }.distinct().toList()
        if (uniqueKeys.isEmpty()) return emptyMap()

        return synchronized(lock) {
            withRecovery(emptyMap()) { db ->
                val found = LinkedHashMap<String, String>(uniqueKeys.size)
                val now = nowMs()

                for (chunk in uniqueKeys.chunked(SQL_IN_CHUNK_SIZE)) {
                    val placeholders = chunk.joinToString(",") { "?" }
                    db.rawQuery(
                        "SELECT cache_key, translated_text FROM $TABLE_NAME WHERE cache_key IN ($placeholders)",
                        chunk.toTypedArray(),
                    ).use { cursor ->
                        val keyCol = cursor.getColumnIndexOrThrow("cache_key")
                        val valCol = cursor.getColumnIndexOrThrow("translated_text")
                        while (cursor.moveToNext()) {
                            val k = cursor.getString(keyCol)
                            val v = cursor.getString(valCol)
                            if (k != null && CacheKeyBuilder.isCacheableTranslation(v)) {
                                found[k] = v
                            }
                        }
                    }
                }

                if (found.isNotEmpty()) {
                    db.beginTransactionNonExclusive()
                    try {
                        db.compileStatement(
                            "UPDATE $TABLE_NAME SET updated_at = ? WHERE cache_key = ?",
                        ).use { stmt ->
                            for (hitKey in found.keys) {
                                stmt.bindLong(1, now)
                                stmt.bindString(2, hitKey)
                                stmt.executeUpdateDelete()
                            }
                        }
                        db.setTransactionSuccessful()
                    } finally {
                        db.endTransaction()
                    }
                }

                found
            }
        }
    }

    override fun putBatch(entries: Map<String, String>) {
        val validEntries = entries.filter { (key, value) ->
            key.isNotBlank() && CacheKeyBuilder.isCacheableTranslation(value)
        }
        if (validEntries.isEmpty()) return

        synchronized(lock) {
            withRecovery(Unit) { db ->
                val now = nowMs()
                db.beginTransactionNonExclusive()
                try {
                    db.compileStatement(
                        """
                        INSERT OR REPLACE INTO $TABLE_NAME (cache_key, translated_text, byte_size, updated_at)
                        VALUES (?, ?, ?, ?)
                        """.trimIndent(),
                    ).use { stmt ->
                        for ((key, rawValue) in validEntries) {
                            val cleanValue = rawValue.trim()
                            val byteSize = BoundedMemoryDiskStore.entryByteSize(key, cleanValue).toLong()
                            if (byteSize > maxBytes) continue
                            stmt.bindString(1, key)
                            stmt.bindString(2, cleanValue)
                            stmt.bindLong(3, byteSize)
                            stmt.bindLong(4, now)
                            stmt.executeInsert()
                        }
                    }
                    trimToLimits(db)
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }
    }

    override fun clear() {
        synchronized(lock) {
            withRecovery(Unit) { db ->
                db.execSQL("DELETE FROM $TABLE_NAME")
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            runCatching { helper.close() }
        }
    }

    private fun trimToLimits(db: SQLiteDatabase) {
        var count = 0
        var totalBytes = 0L
        db.rawQuery(
            "SELECT COUNT(*), COALESCE(SUM(byte_size), 0) FROM $TABLE_NAME",
            null,
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                count = cursor.getInt(0)
                totalBytes = cursor.getLong(1)
            }
        }

        if (count <= maxEntries && totalBytes <= maxBytes) return

        val excessCount = (count - maxEntries).coerceAtLeast(0)
        if (excessCount > 0) {
            db.execSQL(
                """
                DELETE FROM $TABLE_NAME WHERE cache_key IN (
                    SELECT cache_key FROM $TABLE_NAME
                    ORDER BY updated_at ASC, rowid ASC
                    LIMIT $excessCount
                )
                """.trimIndent(),
            )
            db.rawQuery("SELECT COALESCE(SUM(byte_size), 0) FROM $TABLE_NAME", null).use { cursor ->
                totalBytes = if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            }
        }

        while (totalBytes > maxBytes) {
            val keysToDelete = ArrayList<String>(EVICTION_BATCH_SIZE)
            var freedBytes = 0L
            db.rawQuery(
                """
                SELECT cache_key, byte_size FROM $TABLE_NAME
                ORDER BY updated_at ASC, rowid ASC
                LIMIT $EVICTION_BATCH_SIZE
                """.trimIndent(),
                null,
            ).use { cursor ->
                while (cursor.moveToNext() && totalBytes - freedBytes > maxBytes) {
                    keysToDelete.add(cursor.getString(0))
                    freedBytes += cursor.getLong(1)
                }
            }
            if (keysToDelete.isEmpty()) break
            val placeholders = keysToDelete.joinToString(",") { "?" }
            db.execSQL(
                "DELETE FROM $TABLE_NAME WHERE cache_key IN ($placeholders)",
                keysToDelete.toTypedArray(),
            )
            totalBytes -= freedBytes
        }
    }

    private inline fun <T> withRecovery(fallback: T, block: (SQLiteDatabase) -> T): T =
        try {
            block(helper.writableDatabase)
        } catch (firstError: SQLiteException) {
            safeWarn("SQLite cache error, rebuilding database: ${firstError.message}", firstError)
            rebuildDatabase()
            try {
                block(helper.writableDatabase)
            } catch (secondError: Throwable) {
                safeWarn("SQLite cache recovery failed, bypassing L2 cache", secondError)
                fallback
            }
        } catch (unexpected: Throwable) {
            safeWarn("Unexpected L2 cache error, bypassing L2 cache", unexpected)
            fallback
        }

    private fun rebuildDatabase() {
        runCatching { helper.close() }
        deleteDatabaseFiles(dbFile)
        helper = createHelper()
    }

    private fun createHelper(): CacheDbHelper {
        dbFile.parentFile?.mkdirs()
        val errorHandler = DatabaseErrorHandler { corruptedDb ->
            runCatching { corruptedDb.close() }
            deleteDatabaseFiles(dbFile)
        }
        return CacheDbHelper(context, dbFile.absolutePath, errorHandler)
    }

    private class CacheDbHelper(
        context: Context,
        absoluteDbPath: String,
        errorHandler: DatabaseErrorHandler,
    ) : SQLiteOpenHelper(context, absoluteDbPath, null, DATABASE_VERSION, errorHandler) {

        override fun onConfigure(db: SQLiteDatabase) {
            super.onConfigure(db)
            runCatching { db.enableWriteAheadLogging() }
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_NAME (
                    cache_key TEXT PRIMARY KEY NOT NULL,
                    translated_text TEXT NOT NULL,
                    byte_size INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_${TABLE_NAME}_updated_at ON $TABLE_NAME(updated_at)",
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
            onCreate(db)
        }

        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            onUpgrade(db, oldVersion, newVersion)
        }
    }

    companion object {
        private const val TAG = "SqliteTranslationDiskStore"
        private const val DATABASE_VERSION = 1
        private const val TABLE_NAME = "translation_cache"
        private const val SQL_IN_CHUNK_SIZE = 400
        private const val EVICTION_BATCH_SIZE = 128
        const val DEFAULT_RELATIVE_DB_PATH = "polyglotyt/translation_cache.db"

        internal fun deleteDatabaseFiles(dbFile: File) {
            val basePath = dbFile.absolutePath
            listOf(
                dbFile,
                File("$basePath-wal"),
                File("$basePath-shm"),
                File("$basePath-journal"),
            ).forEach { file ->
                runCatching { file.delete() }
            }
        }

        private fun safeWarn(message: String, tr: Throwable? = null) {
            runCatching { logW(TAG, message, tr) }
        }
    }
}

package icu.nullptr.polyglot.core

import android.content.Context
import java.io.File

class FileManager(context: Context) {
    private val filesDir = context.filesDir.resolve("polyglotyt")

    val configDir: File = filesDir.resolve("config")
    val dexKitDir: File = filesDir.resolve("dexkit")
    val cacheDir: File = context.cacheDir.resolve("polyglotyt")

    init {
        configDir.mkdirs()
        dexKitDir.mkdirs()
        cacheDir.mkdirs()
    }

    fun ensureCacheDir(): File = cacheDir.apply { mkdirs() }
}

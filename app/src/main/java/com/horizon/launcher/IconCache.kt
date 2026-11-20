package com.horizon.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Finished app icons, kept in memory and on disk so Home appears instantly after a restart.
 * The key includes the app's install path (which changes on every update) and the current
 * icon style, so an updated app or a new shape/pack/theme simply gets a fresh icon.
 */
class IconCache(context: Context) {
    private val dir = File(context.cacheDir, "icons").apply { mkdirs() }
    private val memory = ConcurrentHashMap<String, Bitmap>()

    fun getOrPut(key: String, render: () -> Bitmap): Bitmap {
        memory[key]?.let { return it }
        val file = fileFor(key)
        if (file.exists()) {
            runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull()?.let {
                memory[key] = it
                return it
            }
        }
        val bmp = render()
        memory[key] = bmp
        runCatching {
            val tmp = File(dir, file.name + ".tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            tmp.renameTo(file)
        }
        return bmp
    }

    /** Forget icons for apps that are gone or styles no longer used. */
    fun prune(keep: Collection<String>) {
        val keepKeys = keep.toHashSet()
        memory.keys.retainAll(keepKeys)
        val keepFiles = keepKeys.mapTo(HashSet()) { fileFor(it).name }
        dir.listFiles()?.forEach { if (it.name !in keepFiles) it.delete() }
    }

    fun clear() {
        memory.clear()
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun fileFor(key: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(dir, "$hash.png")
    }
}

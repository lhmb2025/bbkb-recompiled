package dev.bbkb.ime.personaldictionary.storage

import android.content.Context
import androidx.core.util.AtomicFile
import dev.bbkb.ime.personaldictionary.util.LogUtil
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.Charset

/**
 * File I/O utilities for the Personal Dictionary subsystem.
 *
 * Provides safe file read/write operations; writes go through [AtomicFile] (synced, then renamed
 * into place) for persistence.
 */
object FileUtils {
    @Throws(IOException::class)
    fun readAndroidAssetToString(context: Context, path: String): String {
        require(path.isNotEmpty()) { "filepath must not be empty" }
        context.resources.assets.open(path).use { inputStream ->
            return inputStream.bufferedReader().use { it.readText() }
        }
    }

    fun readFileToString(path: String): String {
        require(path.isNotEmpty()) { "filename must not be empty" }
        val file = File(path)
        if (!file.exists()) {
            LogUtil.d("FileUtils", "File does not exist, returning empty string: $path")
            return ""
        }
        return try {
            file.readText(Charset.defaultCharset())
        } catch (e: IOException) {
            LogUtil.e("FileUtils", "Failed to read file: $path - ${e.message}")
            ""
        }
    }

    /**
     * Replaces [directory]/[filename] with [content] through [AtomicFile]: the text goes to
     * `<filename>.new`, is synced to disk and is then renamed over the file, so a reader (and the
     * next load) sees the whole old file or the whole new one, even after a crash or a power cut.
     * The `.new` file gets the process's default mode, which for an app process is owner-only.
     */
    @Throws(Exception::class, IOException::class)
    fun writeStringToFile(filename: String, content: String, directory: File) {
        require(filename.isNotEmpty()) { "filename must not be empty" }

        val destFile = File(directory, filename)
        val bytes = content.toByteArray(Charset.defaultCharset())
        val atomic = AtomicFile(destFile)
        var out: FileOutputStream? = null
        try {
            out = atomic.startWrite()
            out.write(bytes)
            atomic.finishWrite(out)
        } catch (e: IOException) {
            atomic.failWrite(out)
            throw e
        }
        // finishWrite only logs a failed rename, which leaves the text in the .new file.
        val pending = File(destFile.path + ".new")
        if (pending.exists()) {
            pending.delete()
            throw IOException("Error trying to rename file $pending to $destFile")
        }
    }
}

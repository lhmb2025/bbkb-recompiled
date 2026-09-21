package dev.bbkb.ime.personaldictionary.storage

import android.content.Context
import android.os.Build
import dev.bbkb.ime.personaldictionary.util.LogUtil
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * File I/O utilities for the Personal Dictionary subsystem.
 *
 * Provides safe file read/write operations with atomic rename for persistence.
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

    @Throws(Exception::class, IOException::class)
    fun writeStringToFile(filename: String, content: String, directory: File) {
        require(filename.isNotEmpty()) { "filename must not be empty" }
        
        val tempFile = if (Build.VERSION.SDK_INT >= 26) {
            Files.createTempFile(
                directory.toPath(),
                filename,
                "tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
            ).toFile()
        } else {
            File.createTempFile(filename, "tmp", directory)
        }
        
        tempFile.writeText(content, Charset.defaultCharset())
        
        val destFile = File(directory, filename)
        if (!tempFile.renameTo(destFile)) {
            throw IOException("Error trying to rename file $tempFile to $destFile")
        }
    }
}

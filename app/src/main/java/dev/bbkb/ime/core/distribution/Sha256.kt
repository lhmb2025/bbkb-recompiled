package dev.bbkb.ime.core.distribution

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Streaming SHA-256 as lowercase hex, so APKs (tens of MB) are never read into memory. */
object Sha256 {

    private const val BUFFER_BYTES = 64 * 1024

    private val HEX = "0123456789abcdef".toCharArray()

    fun of(file: File): String = file.inputStream().use { of(it) }

    /** Does not close [stream]. */
    fun of(stream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return hex(digest.digest())
    }

    fun of(bytes: ByteArray): String =
        hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun hex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0f]
        }
        return String(out)
    }

    /** Case- and whitespace-insensitive; blank never matches ("no hash" is not "any hash"). */
    fun matches(expected: String?, actual: String?): Boolean {
        val a = expected?.trim().orEmpty()
        val b = actual?.trim().orEmpty()
        return a.isNotEmpty() && b.isNotEmpty() && a.equals(b, ignoreCase = true)
    }
}

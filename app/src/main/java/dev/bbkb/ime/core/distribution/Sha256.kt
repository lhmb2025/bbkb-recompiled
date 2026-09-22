package dev.bbkb.ime.core.distribution

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Streaming SHA-256, rendered as lowercase hex.
 *
 * Streaming is the point: a language pack is a few MB but an APK is tens, and the whole reason
 * the hash exists is to check a file that just came off the network — reading it into a
 * `ByteArray` to hash it would defeat the memory profile of streaming it to disk in the first
 * place.
 */
object Sha256 {

    private const val BUFFER_BYTES = 64 * 1024

    private val HEX = "0123456789abcdef".toCharArray()

    /** Lowercase hex SHA-256 of [file]'s contents. */
    fun of(file: File): String = file.inputStream().use { of(it) }

    /** Lowercase hex SHA-256 of everything readable from [stream]. Does not close the stream. */
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

    /** Lowercase hex SHA-256 of [bytes]. */
    fun of(bytes: ByteArray): String =
        hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** Lowercase hex rendering of [bytes]. */
    fun hex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0f]
        }
        return String(out)
    }

    /**
     * Whether two hex digests are the same, ignoring case and surrounding whitespace. Blank
     * values never match anything — "no hash" is not "any hash".
     */
    fun matches(expected: String?, actual: String?): Boolean {
        val a = expected?.trim().orEmpty()
        val b = actual?.trim().orEmpty()
        return a.isNotEmpty() && b.isNotEmpty() && a.equals(b, ignoreCase = true)
    }
}

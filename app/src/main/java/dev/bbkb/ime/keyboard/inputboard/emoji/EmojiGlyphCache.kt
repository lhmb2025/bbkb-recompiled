package dev.bbkb.ime.keyboard.inputboard.emoji

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Remembers which emoji the system font can draw, so a re-parse of the emojibase data does not
 * call `Paint.hasGlyph` 1.9k to 3.8k times again (audit P-2).
 *
 * The file is keyed by the caller's key (build fingerprint + dataset identity) and by the exact
 * list of emoji asked about; any mismatch, short read or I/O error means "no cache" and every
 * emoji is checked again. Only positive answers are trusted: the emoji font can be updated
 * without a new build fingerprint (updatable system fonts), and an update only ever adds glyphs,
 * so the emoji the cache marks unsupported are re-checked on every load and a newly drawable one
 * appears at once. That keeps the cache from ever hiding an emoji the font can draw.
 */
internal object EmojiGlyphCache {

    const val FILE_NAME = "emojibase-glyphs.bin"
    private const val VERSION = 1

    /**
     * Whether each of [emojis] has a glyph, from the cache in [dir] where it can be trusted and
     * from [hasGlyph] otherwise. Rewrites the cache when anything changed. [dir] null disables it.
     */
    fun flags(dir: File?, key: String, emojis: List<String>, hasGlyph: (String) -> Boolean): BooleanArray {
        val file = dir?.let { File(it, FILE_NAME) }
        val fullKey = "$key|${emojis.size}|${emojis.hashCode()}"
        val cached = file?.let { read(it, fullKey, emojis.size) }
        val result = BooleanArray(emojis.size)
        var changed = cached == null
        for (i in emojis.indices) {
            if (cached != null && cached[i]) {
                result[i] = true
            } else {
                result[i] = hasGlyph(emojis[i])
                if (result[i]) changed = true
            }
        }
        if (changed && file != null) write(file, fullKey, result)
        return result
    }

    internal fun read(file: File, key: String, size: Int): BooleanArray? = try {
        if (!file.isFile) null else DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            if (input.readInt() != VERSION || input.readUTF() != key || input.readInt() != size) {
                null
            } else {
                val bytes = ByteArray((size + 7) / 8)
                input.readFully(bytes)
                if (input.read() != -1) null  // trailing bytes: not a file this code wrote
                else BooleanArray(size) { (bytes[it ushr 3].toInt() ushr (it and 7)) and 1 == 1 }
            }
        }
    } catch (e: Exception) {
        null
    }

    internal fun write(file: File, key: String, flags: BooleanArray) {
        // Write a sibling and rename it over the old file, so a concurrent reader (the custom
        // symbol page builds its own provider) never sees half a file.
        var tmp: File? = null
        try {
            tmp = File.createTempFile(FILE_NAME, ".tmp", file.parentFile)
            DataOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { out ->
                out.writeInt(VERSION)
                out.writeUTF(key)
                out.writeInt(flags.size)
                val bytes = ByteArray((flags.size + 7) / 8)
                for (i in flags.indices) {
                    if (flags[i]) bytes[i ushr 3] = (bytes[i ushr 3].toInt() or (1 shl (i and 7))).toByte()
                }
                out.write(bytes)
            }
            if (tmp.renameTo(file)) tmp = null
        } catch (e: Exception) {
            // A cache that cannot be written only costs the next load its hasGlyph calls.
        } finally {
            tmp?.delete()
        }
    }
}

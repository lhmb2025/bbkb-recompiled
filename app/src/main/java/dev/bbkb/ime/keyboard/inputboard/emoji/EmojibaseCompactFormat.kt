package dev.bbkb.ime.keyboard.inputboard.emoji

import java.io.BufferedReader

/**
 * Reader for the compact emojibase asset written by the `generateEmojibaseCompact` task in
 * app/build.gradle from emojibase-data en/data.json. A skin carries only its emoji.
 *
 * Format (UTF-8, '\n' line ends):
 *
 *     line 1:  EBC1 <TAB> emojibase-data <package version> en/data.json sha256=<hex of data.json>
 *     then one line per data.json entry, in data.json order, 13 TAB-separated fields:
 *        0 emoji   1 hexcode   2 label   3 tags   4 shortcodes   5 group   6 subgroup
 *        7 version 8 order     9 type   10 gender 11 tone       12 skins (their emoji only)
 *
 * List fields join items with U+001F; an empty field is a null list. A missing group, subgroup or
 * order is written as 0; a missing type, gender or tone is an empty field. `version` is
 * Double.toString. The header text after the TAB is the dataset identity that keys [EmojiGlyphCache].
 */

internal object EmojibaseCompactFormat {

    const val ASSET = "emoji/emojibase-en.tsv"

    private const val MAGIC = "EBC1"
    private const val FIELD_COUNT = 13
    private const val LIST_SEP = '\u001f'

    /** The parsed entries plus the source identity from the header (keys the glyph cache). */
    class Parsed(val entries: List<EmojiData>, val identity: String)

    /** @throws IllegalArgumentException on a malformed file, so the caller can fall back to the JSON. */
    fun read(reader: BufferedReader): Parsed {
        val header = reader.readLine() ?: throw IllegalArgumentException("empty emojibase asset")
        val tab = header.indexOf('\t')
        require(tab > 0 && header.substring(0, tab) == MAGIC) { "not an $MAGIC emojibase asset" }
        val identity = header.substring(tab + 1)

        val entries = ArrayList<EmojiData>(2048)
        val fields = arrayOfNulls<String>(FIELD_COUNT)
        var lineNo = 1
        while (true) {
            val line = reader.readLine() ?: break
            lineNo++
            if (line.isEmpty()) continue
            split(line, fields, lineNo)
            entries.add(
                EmojiData(
                    emoji = fields[0]!!,
                    codepoints = fields[1]!!,
                    description = fields[2]!!,
                    tags = list(fields[3]!!),
                    aliases = list(fields[4]!!),
                    group = fields[5]!!.toInt(),
                    subgroup = fields[6]!!.toInt(),
                    emojiVersion = fields[7]!!.toDouble(),
                    order = fields[8]!!.toInt(),
                    type = nullableInt(fields[9]!!),
                    gender = nullableInt(fields[10]!!),
                    tone = nullableInt(fields[11]!!),
                    // The skin's hexcode, label and tone are not in the asset: only its emoji is read.
                    skins = list(fields[12]!!)?.map { EmojiData.EmojiSkin(emoji = it, hexcode = "") }
                )
            )
        }
        require(entries.isNotEmpty()) { "emojibase asset has no entries" }
        return Parsed(entries, identity)
    }

    /** Splits on TAB without a regex; exactly [FIELD_COUNT] fields or the line is rejected. */
    private fun split(line: String, out: Array<String?>, lineNo: Int) {
        var start = 0
        for (i in 0 until FIELD_COUNT - 1) {
            val end = line.indexOf('\t', start)
            require(end >= 0) { "line $lineNo: expected $FIELD_COUNT fields" }
            out[i] = line.substring(start, end)
            start = end + 1
        }
        require(line.indexOf('\t', start) < 0) { "line $lineNo: more than $FIELD_COUNT fields" }
        out[FIELD_COUNT - 1] = line.substring(start)
    }

    private fun list(field: String): List<String>? {
        if (field.isEmpty()) return null
        val items = ArrayList<String>(4)
        var start = 0
        while (true) {
            val end = field.indexOf(LIST_SEP, start)
            if (end < 0) {
                items.add(field.substring(start))
                return items
            }
            items.add(field.substring(start, end))
            start = end + 1
        }
    }

    private fun nullableInt(field: String): Int? = if (field.isEmpty()) null else field.toInt()
}

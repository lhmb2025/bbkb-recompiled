package dev.bbkb.ime.keyboard

import dev.bbkb.ime.core.layout.DrawnKeyboard

/**
 * Reads a built on-screen keyboard into the [DrawnKeyboard] the engine's on-screen KDB is built
 * from: the letters the user sees, after every `!text/` reference, language table and chosen
 * layout has been resolved.
 */
object DrawnKeyboards {

    /**
     * [alphabet], an on-screen alphabet keyboard, with [shifted] its Shift layer (null when there is
     * none to read). Rows are the keyboard's keys grouped by their top edge; the last row (the space
     * bar's) only counts its positions, and a row of nothing but digits (the number row some
     * keyboards draw) is left out. A position is a key whose code is a character other than space,
     * Enter and Tab, or a key that types a text (its [Key.getOutputText], else its label: a Hindi
     * `क्ष` key).
     */
    @JvmStatic
    fun read(alphabet: Keyboard, shifted: Keyboard?): DrawnKeyboard? {
        val rows = positionRows(alphabet)
        if (rows.size < 2) return null
        val letterRows = rows.dropLast(1)
        val shiftedRows = shifted?.let { positionRows(it).dropLast(1) }?.takeIf { s -> s.map { it.size } == letterRows.map { it.size } }
        val width = alphabet.mOccupiedWidth.takeIf { it > 0 }?.toFloat() ?: return null
        val out = letterRows.mapIndexed { r, row ->
            row.mapIndexed { i, key ->
                DrawnKeyboard.Position(codesOf(key, shiftedRows?.get(r)?.get(i)), (key.x + key.width / 2f) / width)
            }
        }
        return DrawnKeyboard(alphabet.mId.keyboardLayoutSet(), out, rows.last().size)
    }

    private fun positionRows(keyboard: Keyboard): List<List<Key>> =
        keyboard.keys.groupBy { it.y }.toSortedMap().values
            .map { row -> row.filter(::isPosition).sortedBy { it.x } }
            .filter { row -> row.isNotEmpty() && !row.all { Character.isDigit(primaryOf(it)) } }

    private fun isPosition(key: Key): Boolean = when (val code = key.code) {
        ' '.code, '\n'.code, '\t'.code -> false
        TEXT_CODE -> !textOf(key).isNullOrEmpty()
        else -> code > 0
    }

    /** The code point a position stands for: its code, or the first of the text it types. */
    private fun primaryOf(key: Key): Int =
        if (key.code == TEXT_CODE) textOf(key)?.takeIf { it.isNotEmpty() }?.codePointAt(0) ?: 0 else key.code

    /** What a text key types: its output text, or its label when the text is the label itself. */
    private fun textOf(key: Key): String? = key.outputText?.takeIf { it.isNotEmpty() } ?: key.label

    /** The position's own code, the Shift layer's where that is another letter, then the long-press letters. */
    private fun codesOf(key: Key, shiftedKey: Key?): IntArray {
        val primary = primaryOf(key)
        val codes = LinkedHashSet<Int>()
        codes += primary
        val shift = shiftedKey?.let(::primaryOf) ?: 0
        if (shift > 0 && isLetter(shift) && Character.toLowerCase(shift) != Character.toLowerCase(primary)) {
            codes += shift
        }
        key.moreKeys?.forEach { more ->
            val code = more.code.takeIf { it > 0 }
                ?: more.outputText?.takeIf { it.codePointCount(0, it.length) == 1 }?.codePointAt(0)
                ?: return@forEach
            if (isLetter(code)) codes += code
        }
        return codes.toIntArray()
    }

    private fun isLetter(codePoint: Int): Boolean =
        Character.isLetter(codePoint) || when (Character.getType(codePoint)) {
            Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt() -> true
            else -> false
        }

    /** A key that types a text rather than one character (`Constants.CODE_OUTPUT_TEXT`). */
    private const val TEXT_CODE = -4
}

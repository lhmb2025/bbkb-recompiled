package dev.bbkb.ime.core.keyevent

import android.view.KeyEvent
import java.io.InputStream

/**
 * What each physical letter key produces under one of the engine's script hardware layouts.
 *
 * The phones this app runs on send the standard letter key codes whatever their keycaps say, and
 * their system key map spells those keys in Latin. A KEY2 with Arabic keycaps therefore typed
 * Latin on an Arabic keyboard (the original app relied on BlackBerry firmware to supply the
 * letter). The letters themselves already exist, once, as the engine's hardware layouts in
 * `assets/kdb/<name>_pkb.xml`: three `<area>` rows of 108dp key boxes in QWERTY order, each key
 * carrying its `keyCodes`. This class reads one of those files and answers "which letters sit on
 * the key that Android calls KEYCODE_Q", so the app and the engine can never disagree about a
 * keycap.
 *
 * The grid is the physical keypad: row 1 Q–P (ten keys), row 2 A–L then backspace, row 3 Alt,
 * Z–M, an extra key (the KEY2's `$` key, which carries a letter on Arabic and Cyrillic keycaps),
 * enter. A layout whose keys carry only Latin letters or plain `keyLabel`s (the generated QWERTY
 * boxes for languages typed on Latin keys) is not a script layout and parses to null.
 *
 * Hangul is stored in the files as conjoining jamo for the engine; the cells here hold the
 * compatibility jamo the on-screen keyboard and the physical-key tables use.
 */
class HardwareScriptLayout private constructor(
    private val cells: Map<Int, Cell>,
    /** Row 3, column 8: the key past M, or null when the layout leaves it empty. */
    val extraKey: Cell?,
) {

    /** One physical key: its unshifted letter first, then the further letters printed on it. */
    class Cell(val codes: IntArray) {
        val letter: Int get() = codes[0]
        val hasAlternates: Boolean get() = codes.size > 1
        /** The letters after the first: the second legend on a doubled keycap, accented forms. */
        val alternates: IntArray get() = codes.copyOfRange(1, codes.size)
    }

    /** The cell under an Android letter key code (`KEYCODE_A`..`KEYCODE_Z`), or null. */
    fun cell(keyCode: Int): Cell? = cells[keyCode]

    /** The cell whose unshifted letter is [codePoint], or null. */
    fun cellForLetter(codePoint: Int): Cell? =
        cells.values.firstOrNull { it.letter == codePoint } ?: extraKey?.takeIf { it.letter == codePoint }

    /** How many of the 26 letter keys carry something. */
    val letterKeyCount: Int get() = cells.size

    companion object {
        private val ROWS = arrayOf("QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM")
        private const val EXTRA_KEY_ROW = 2
        private const val EXTRA_KEY_COLUMN = 8
        private const val DEFAULT_KEY_WIDTH = 108

        private val AREA = Regex("<area>(.*?)</area>", RegexOption.DOT_MATCHES_ALL)
        private val KEY = Regex("<key\\b([^>]*)/>")
        private val ATTRIBUTE = Regex("(\\w+)=\"([^\"]*)\"")

        @JvmStatic
        fun parse(input: InputStream): HardwareScriptLayout? = parse(input.reader(Charsets.UTF_8).readText())

        /** Null when [xml] is not a script layout (see the class doc) or has no rows at all. */
        @JvmStatic
        fun parse(xml: String): HardwareScriptLayout? {
            val cells = HashMap<Int, Cell>()
            var extraKey: Cell? = null
            var script = false
            AREA.findAll(xml).forEachIndexed { row, area ->
                if (row >= ROWS.size) return@forEachIndexed
                for (key in KEY.findAll(area.value)) {
                    val attributes = ATTRIBUTE.findAll(key.groupValues[1])
                        .associate { it.groupValues[1] to it.groupValues[2] }
                    if (attributes["keyType"] == "function") continue
                    val codes = attributes["keyCodes"]?.let { parseCodes(it) } ?: continue
                    if (codes.isEmpty()) continue
                    val width = attributes["keyWidth"]?.let { dp(it) }?.takeIf { it > 0 } ?: DEFAULT_KEY_WIDTH
                    val left = attributes["keyLeft"]?.let { dp(it) } ?: continue
                    val column = (left + width / 2) / width
                    val cell = Cell(codes)
                    val letter = codes[0]
                    if (Character.isLetter(letter) && !isLatinOrCommon(letter)) script = true
                    val keyCode = keyCodeAt(row, column)
                    when {
                        keyCode != 0 -> cells[keyCode] = cell
                        row == EXTRA_KEY_ROW && column == EXTRA_KEY_COLUMN -> extraKey = cell
                    }
                }
            }
            return if (script) HardwareScriptLayout(cells, extraKey) else null
        }

        /** The Android key code of the letter at [row]/[column] of the QWERTY grid, or 0. */
        private fun keyCodeAt(row: Int, column: Int): Int {
            // Row 3 starts with the Alt key, so its letters begin one column in.
            val index = if (row == 2) column - 1 else column
            val letters = ROWS[row]
            if (index < 0 || index >= letters.length) return 0
            return KeyEvent.KEYCODE_A + (letters[index] - 'A')
        }

        private fun dp(value: String): Int = value.removeSuffix("dp").trim().toIntOrNull() ?: 0

        private fun parseCodes(value: String): IntArray =
            value.split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { toCompatibilityJamo(it.removePrefix("0x").removePrefix("0X").toInt(16)) }
                .toIntArray()

        private fun isLatinOrCommon(codePoint: Int): Boolean = when (Character.UnicodeScript.of(codePoint)) {
            Character.UnicodeScript.LATIN, Character.UnicodeScript.COMMON, Character.UnicodeScript.INHERITED -> true
            else -> false
        }

        /** Compatibility jamo (U+3131..) for the conjoining choseong U+1100..U+1112, in order. */
        private val CHOSEONG_TO_COMPATIBILITY = intArrayOf(
            0x3131, 0x3132, 0x3134, 0x3137, 0x3138, 0x3139, 0x3141, 0x3142, 0x3143, 0x3145,
            0x3146, 0x3147, 0x3148, 0x3149, 0x314A, 0x314B, 0x314C, 0x314D, 0x314E,
        )

        /**
         * The engine's Korean layout is written in conjoining jamo (`ᄇ`); the keyboard, its tables
         * and `HangulInputProcessor` speak compatibility jamo (`ㅂ`), which is also what the
         * on-screen keys emit. Everything else passes through unchanged.
         */
        @JvmStatic
        fun toCompatibilityJamo(codePoint: Int): Int = when (codePoint) {
            in 0x1100..0x1112 -> CHOSEONG_TO_COMPATIBILITY[codePoint - 0x1100]
            in 0x1161..0x1175 -> 0x314F + (codePoint - 0x1161)
            else -> codePoint
        }
    }
}

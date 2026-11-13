package dev.bbkb.ime.core.layout

import android.view.KeyEvent
import java.util.Locale

/**
 * Rewrites the engine's KDB key letters from a letter layout, so taps and swipes decode against the
 * letters the keys actually type. A text rewrite: every byte the layout does not change is copied
 * through; a changed key has only `keyLabel`, `keyCodes` and `keyType` rewritten in place. Geometry
 * and the `<keyboard>` header are never touched.
 *
 * Loader rules (`kdb_load_xml.c`, `key_codes_of`): a single ASCII-letter `keyLabel` is the primary
 * (lower-cased) and `keyCodes` then holds only alternates; otherwise the first `keyCodes` entry is the
 * primary. Non-ASCII-letter `keyLabel`s are ignored, so non-ASCII primaries must be codes. Alternates
 * are the Shift legend (when not plain upper case), multitap letters and [LetterLayout.Key.engineKeys];
 * a named key's own KDB alternates are replaced, not merged. Codes are lower case; Hangul is stored as
 * conjoining jamo ([toConjoining]).
 *
 * `keyType`: letters and combining marks are `regional` (tap correction and swipe), symbols
 * `nonRegional`; the type flips only when a key moves between letter and non-letter.
 *
 * Positions ([Template]): PKB row 1 Q to P, row 2 A to L, row 3 Alt then Z to M plus the extra key
 * past M (`KEYCODE_4`), by key centre on the grid (same rules as `letter_layouts_gen.py`). VKB: the
 * first three rows left to right, Q to P, A to L (+ semicolon as a tenth key), Z to M; the fourth row
 * has no positions. [forDrawnKeyboard] instead fills every non-function key from [DrawnKeyboard] rows.
 */
object LetterLayoutKdb {
    /** The keyboard a KDB file describes, which decides how its keys map to key codes. */
    enum class Template(
        /** File suffix under `assets/kdb/`. */
        val fileSuffix: String,
    ) {
        /** Physical keypad: positions by key centre on the grid. */
        PKB("_pkb.xml"),

        /** On-screen keyboard: positions by order within each letter row. */
        VKB("_vkb.xml"),
    }

    /** The prefix of the engine variant a layout's generated KDBs are registered under. */
    const val VARIANT_PREFIX: String = "layout:"

    /** One variant name serves both keyboards: the engine also keys files by id, and PKB (secondaryId 6) never shares ids with VKB. */
    @JvmStatic
    @JvmOverloads
    @Suppress("UNUSED_PARAMETER")
    fun variantName(layout: LetterLayout, template: Template = Template.PKB): String = VARIANT_PREFIX + layout.id

    /** Prefix for built-in documents' variants; a layout id cannot contain `:`, so no [variantName] collides. */
    const val BUILT_IN_VARIANT_PREFIX: String = VARIANT_PREFIX + "builtin:"

    /** The variant the keypad KDBs built from the built-in [layout] are registered under. */
    @JvmStatic
    fun builtInVariantName(layout: LetterLayout): String = BUILT_IN_VARIANT_PREFIX + layout.id

    /**
     * On-screen KDB [template] with [rows]' code points (primary first) on its keys, left to right.
     * Extra template rows are kept. Null when a row's position count differs or rows are missing.
     */
    @JvmStatic
    fun forDrawnKeyboard(template: String, rows: List<List<IntArray>>): String? {
        val templateRows = screenKeys(template)
        if (templateRows.size < rows.size) return null
        val edits = ArrayList<Edit>()
        for ((r, row) in rows.withIndex()) {
            val keys = templateRows[r]
            if (keys.size != row.size) return null
            for ((i, codes) in row.withIndex()) {
                val engineCodes = codes.map(::engineCode).distinct()
                edits += rewriteKeyCodes(keys[i], engineCodes) ?: continue
            }
        }
        if (edits.isEmpty()) return template
        val out = StringBuilder(template)
        edits.sortedByDescending { it.start }.forEach { out.replace(it.start, it.end, it.text) }
        return out.toString()
    }

    /** Non-function key centres of on-screen KDB [xml] per `<area>`, as fractions of `defaultLayoutWidth`. */
    @JvmStatic
    fun screenRows(xml: String): List<List<Float>> {
        val width = Regex("defaultLayoutWidth\\s*=\\s*\"(\\d+)\"").find(COMMENT.replace(xml, ""))
            ?.groupValues?.get(1)?.toFloatOrNull()?.takeIf { it > 0 } ?: 1080f
        return screenKeys(xml).map { row ->
            row.map { key ->
                val left = key.attributes["keyLeft"]?.let { dp(it.value) } ?: 0
                val keyWidth = key.attributes["keyWidth"]?.let { dp(it.value) } ?: DEFAULT_KEY_WIDTH
                (left + keyWidth / 2f) / width
            }
        }
    }

    private val KEYBOARD_ELEMENT = Regex("<keyboard\\b[^>]*>")
    private val PRIMARY_ID = Regex("(\\bprimaryId\\s*=\\s*\")(\\d+)(\")")
    private val SECONDARY_ID = Regex("(\\bsecondaryId\\s*=\\s*\")(\\d+)(\")")

    /** `primaryId` (language) and `secondaryId` (6 keypad, 1 on-screen) of [xml]'s `<keyboard>`, or null. */
    @JvmStatic
    fun engineIds(xml: String): Pair<Int, Int>? {
        val keyboard = KEYBOARD_ELEMENT.find(COMMENT.replace(xml) { " ".repeat(it.value.length) }) ?: return null
        val element = xml.substring(keyboard.range)
        val primary = PRIMARY_ID.find(element)?.groupValues?.get(2)?.toIntOrNull() ?: return null
        val secondary = SECONDARY_ID.find(element)?.groupValues?.get(2)?.toIntOrNull() ?: return null
        return primary to secondary
    }

    /** [xml] with its `<keyboard>` element's ids replaced, every other byte as it was; null without them. */
    @JvmStatic
    fun withEngineIds(xml: String, primaryId: Int, secondaryId: Int): String? {
        val keyboard = KEYBOARD_ELEMENT.find(COMMENT.replace(xml) { " ".repeat(it.value.length) }) ?: return null
        var element = xml.substring(keyboard.range)
        for ((attribute, value) in listOf(PRIMARY_ID to primaryId, SECONDARY_ID to secondaryId)) {
            val digits = attribute.find(element)?.groups?.get(2)?.range ?: return null
            element = element.replaceRange(digits, value.toString())
        }
        return xml.replaceRange(keyboard.range, element)
    }

    /** [xml] with every key [layout] names given the layout's letters (see the class doc); [xml] itself when unchanged. */
    @JvmStatic
    @JvmOverloads
    fun rewrite(xml: String, layout: LetterLayout, template: Template = Template.PKB): String {
        val edits = ArrayList<Edit>()
        for (key in keys(xml, template)) {
            val entry = layout.keys[key.keyCode] ?: continue
            edits += rewriteKey(key, entry) ?: continue
        }
        if (edits.isEmpty()) return xml
        val out = StringBuilder(xml)
        edits.sortedByDescending { it.start }.forEach { out.replace(it.start, it.end, it.text) }
        return out.toString()
    }

    /** The engine's symbol list per position key code, primary first (Hangul as conjoining jamo). */
    @JvmStatic
    @JvmOverloads
    fun codesByKeyCode(xml: String, template: Template = Template.PKB): Map<Int, IntArray> =
        keys(xml, template).associate { it.keyCode to it.codes }

    /** Each position's primary, Hangul as compatibility jamo: what [LetterLayout.changesLetters] compares against. */
    @JvmStatic
    @JvmOverloads
    fun lettersByKeyCode(xml: String, template: Template = Template.PKB): Map<Int, Int> =
        codesByKeyCode(xml, template).filterValues { it.isNotEmpty() }.mapValues { toCompatibility(it.value[0]) }

    /** One `name="value"` inside a key element, as offsets into the whole file. */
    private class Attribute(
        /** Start of the leading white space, so removing [spanStart] until [end] is clean. */
        val spanStart: Int,
        val valueStart: Int,
        val valueEnd: Int,
        /** Just past the closing quote. */
        val end: Int,
        val value: String,
    )

    /** A key element at a position the template names. */
    private class KdbKey(
        val keyCode: Int,
        /** Just past `<key`. */
        val nameEnd: Int,
        val attributes: Map<String, Attribute>,
        /** What the loader reads: primary first. */
        val codes: IntArray,
        /** Whether the primary came from `keyLabel` (and so `keyCodes` holds alternates only). */
        val labelForm: Boolean,
    )

    private class Edit(val start: Int, val end: Int, val text: String)

    private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val AREA = Regex("<area\\b[^>]*>(.*?)</area\\s*>", RegexOption.DOT_MATCHES_ALL)
    private val KEY = Regex("<key\\b[^>]*>")
    private val ATTRIBUTE = Regex("(\\s+)([A-Za-z_][\\w.-]*)\\s*=\\s*\"([^\"]*)\"")

    private val ROWS = arrayOf("QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM")

    /** On-screen letter rows by position; the `spanish` rows' tenth middle key is `KEYCODE_SEMICOLON`. */
    private val VKB_ROWS: Array<IntArray> = arrayOf(
        ROWS[0].map { KeyEvent.KEYCODE_A + (it - 'A') }.toIntArray(),
        (ROWS[1].map { KeyEvent.KEYCODE_A + (it - 'A') } + KeyEvent.KEYCODE_SEMICOLON).toIntArray(),
        ROWS[2].map { KeyEvent.KEYCODE_A + (it - 'A') }.toIntArray(),
    )
    private const val EXTRA_KEY_ROW = 2
    private const val EXTRA_KEY_COLUMN = 8
    private const val DEFAULT_KEY_WIDTH = 108

    /** The engine keeps at most 16 symbols per key (`ET9_KDB_MAX_KEY_CODES`); the rest would be lost. */
    private const val MAX_KEY_CODES = 16

    /** The engine's symbols are 16-bit (`ET9SYMB`); the loader drops anything larger. */
    private const val MAX_ENGINE_CODE = 0xFFFF

    private fun keys(xml: String, template: Template): List<KdbKey> {
        // Blank comments at equal length (offsets hold) so a commented-out key does not count.
        val masked = COMMENT.replace(xml) { " ".repeat(it.value.length) }
        val keys = ArrayList<KdbKey>()
        AREA.findAll(masked).forEachIndexed { row, area ->
            if (row >= ROWS.size) return@forEachIndexed
            // On-screen keys are placed once the whole row is read; keypad keys by grid column.
            val rowKeys = ArrayList<Pair<Int, KdbKey>>()
            val body = area.groups[1]!!.range
            for (match in KEY.findAll(masked, body.first)) {
                if (match.range.first > body.last) break
                val element = match.range
                val attributes = HashMap<String, Attribute>()
                for (a in ATTRIBUTE.findAll(masked.substring(element.first, element.last + 1))) {
                    val base = element.first
                    val name = a.groups[2]!!.value
                    val value = a.groups[3]!!
                    attributes.putIfAbsent(
                        name,
                        Attribute(
                            spanStart = base + a.range.first,
                            valueStart = base + value.range.first,
                            valueEnd = base + value.range.last + 1,
                            end = base + a.range.last + 1,
                            value = value.value,
                        ),
                    )
                }
                if (attributes["keyType"]?.value == "function" || attributes.containsKey("keyName")) continue
                val left = attributes["keyLeft"]?.let { dp(it.value) } ?: continue
                val width = attributes["keyWidth"]?.let { dp(it.value) }?.takeIf { it > 0 } ?: DEFAULT_KEY_WIDTH
                val keyCode = when (template) {
                    Template.PKB -> keyCodeAt(row, (left + width / 2) / width).takeIf { it != 0 } ?: continue
                    Template.VKB -> 0
                }
                val label = attributes["keyLabel"]?.value?.singleOrNull()?.takeIf { it in 'A'..'Z' || it in 'a'..'z' }
                val codes = ArrayList<Int>()
                if (label != null) codes += label.lowercaseChar().code
                attributes["keyCodes"]?.let { codes += parseCodes(it.value) }
                rowKeys += left to KdbKey(keyCode, element.first + "<key".length, attributes, codes.distinct().toIntArray(), label != null)
            }
            when (template) {
                Template.PKB -> rowKeys.mapTo(keys) { it.second }
                Template.VKB -> {
                    val positions = VKB_ROWS[row]
                    rowKeys.sortedBy { it.first }.forEachIndexed { index, (_, key) ->
                        if (index < positions.size) {
                            keys += KdbKey(positions[index], key.nameEnd, key.attributes, key.codes, key.labelForm)
                        }
                    }
                }
            }
        }
        return keys
    }

    /** Every `<area>`'s keys that are not function keys, left to right; see [screenRows]. */
    private fun screenKeys(xml: String): List<List<KdbKey>> {
        val masked = COMMENT.replace(xml) { " ".repeat(it.value.length) }
        return AREA.findAll(masked).map { area ->
            val body = area.groups[1]!!.range
            val row = ArrayList<Pair<Int, KdbKey>>()
            for (match in KEY.findAll(masked, body.first)) {
                if (match.range.first > body.last) break
                val element = match.range
                val attributes = HashMap<String, Attribute>()
                for (a in ATTRIBUTE.findAll(masked.substring(element.first, element.last + 1))) {
                    val base = element.first
                    val value = a.groups[3]!!
                    attributes.putIfAbsent(
                        a.groups[2]!!.value,
                        Attribute(base + a.range.first, base + value.range.first, base + value.range.last + 1, base + a.range.last + 1, value.value),
                    )
                }
                if (attributes["keyType"]?.value == "function" || attributes.containsKey("keyName")) continue
                val left = attributes["keyLeft"]?.let { dp(it.value) } ?: continue
                val label = attributes["keyLabel"]?.value?.singleOrNull()?.takeIf { it in 'A'..'Z' || it in 'a'..'z' }
                val codes = ArrayList<Int>()
                if (label != null) codes += label.lowercaseChar().code
                attributes["keyCodes"]?.let { codes += parseCodes(it.value) }
                if (codes.isEmpty()) attributes["keyLabel"]?.value?.takeIf { it.isNotEmpty() }?.let { codes += it.codePointAt(0) }
                row += left to KdbKey(0, element.first + "<key".length, attributes, codes.distinct().toIntArray(), label != null)
            }
            row.sortedBy { it.first }.map { it.second }
        }.toList()
    }

    /** The key code at [row]/[column] of the keypad grid, [KeyEvent.KEYCODE_4] for the extra key, or 0. */
    private fun keyCodeAt(row: Int, column: Int): Int {
        if (row == EXTRA_KEY_ROW && column == EXTRA_KEY_COLUMN) return KeyEvent.KEYCODE_4
        // Row 3 starts with the Alt key, so its letters begin one column in.
        val index = if (row == EXTRA_KEY_ROW) column - 1 else column
        val letters = ROWS[row]
        if (index < 0 || index >= letters.length) return 0
        return KeyEvent.KEYCODE_A + (letters[index] - 'A')
    }

    private fun dp(value: String): Int? = value.trim().removeSuffix("dp").trim().toIntOrNull()

    /** The loader's reading of a `keyCodes` list: hex, optional `0x`, blanks and empty fields skipped. */
    private fun parseCodes(value: String): List<Int> =
        value.split(',').mapNotNull { field ->
            val digits = field.trim().removePrefix("0x").removePrefix("0X")
            digits.takeWhile { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }.toIntOrNull(16)?.takeIf { it > 0 }
        }

    /** The edits that give [key] the letters of [entry], or null when it already has them (or cannot). */
    private fun rewriteKey(key: KdbKey, entry: LetterLayout.Key): List<Edit>? {
        val codes = LinkedHashSet<Int>().apply { add(engineCode(entry.keySpec)) }
        if (entry.shift != 0 && entry.shift != Character.toUpperCase(entry.keySpec)) codes += engineCode(entry.shift)
        singleCodePoints(entry.multitap).forEach { codes += engineCode(it) }
        entry.engineKeys.forEach { codes += engineCode(it) }
        return rewriteKeyCodes(key, codes.toList())
    }

    /** The edits that give [key] engine [codes] (primary first), or null when it already has them (or cannot). */
    private fun rewriteKeyCodes(key: KdbKey, codes: List<Int>): List<Edit>? {
        val primary = codes.firstOrNull() ?: return null
        // An unholdable primary leaves the key as is: dropping its letter would make a decoder dead spot.
        if (primary <= 0 || primary > MAX_ENGINE_CODE) return null
        val wanted = codes.filter { it in 1..MAX_ENGINE_CODE }.distinct().take(MAX_KEY_CODES)

        val labelForm = primary in 'a'.code..'z'.code
        // Flip only on a letter/non-letter move, so deliberate types (a regional danda) survive.
        val oldType = key.attributes["keyType"]
        val oldIsLetter = key.codes.firstOrNull()?.let(::isLetter) ?: false
        val newType = when {
            isLetter(primary) == oldIsLetter -> null
            oldType?.value == "nonRegional" && isLetter(primary) -> "regional"
            oldType?.value == "regional" && !isLetter(primary) -> "nonRegional"
            else -> null
        }
        if (key.codes.toList() == wanted && key.labelForm == labelForm && newType == null) return null

        val edits = ArrayList<Edit>()
        if (oldType != null && newType != null) edits += Edit(oldType.valueStart, oldType.valueEnd, newType)
        // Replace at the first old attribute's spot (after keyType if none) to keep attribute order.
        val label = key.attributes["keyLabel"]
        val list = key.attributes["keyCodes"]
        val removed = listOfNotNull(label, list).sortedBy { it.spanStart }
        val text = buildString {
            if (labelForm) {
                append(" keyLabel=\"").append(primary.toChar().uppercaseChar()).append('"')
                if (wanted.size > 1) append(" keyCodes=\"").append(hexList(wanted.drop(1))).append('"')
            } else {
                append(" keyCodes=\"").append(hexList(wanted)).append('"')
            }
        }
        if (removed.isEmpty()) {
            val at = oldType?.end ?: key.nameEnd
            edits += Edit(at, at, text)
        } else {
            edits += Edit(removed[0].spanStart, removed[0].end, text)
            removed.drop(1).forEach { edits += Edit(it.spanStart, it.end, "") }
        }
        return edits
    }

    private fun hexList(codes: List<Int>): String =
        codes.joinToString(", ") { "0x" + String.format(Locale.ROOT, "%04X", it) }

    /** What the engine keeps for a layout's code point: lower case, Hangul in conjoining jamo. */
    private fun engineCode(codePoint: Int): Int = toConjoining(Character.toLowerCase(codePoint))

    /** Letters and combining marks sit on regional keys; see the class doc. */
    private fun isLetter(codePoint: Int): Boolean =
        Character.isLetter(codePoint) || when (Character.getType(codePoint)) {
            Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt() -> true
            else -> false
        }

    /** Multitap entries that are a single code point; ligatures (lam-alef) have no KDB code. */
    private fun singleCodePoints(entries: Array<String>): List<Int> =
        entries.filter { it.isNotEmpty() && it.codePointCount(0, it.length) == 1 }.map { it.codePointAt(0) }

    /** Compatibility jamo (U+3131..) of the conjoining choseong U+1100..U+1112, in order. */
    private val CHOSEONG_COMPATIBILITY = intArrayOf(
        0x3131, 0x3132, 0x3134, 0x3137, 0x3138, 0x3139, 0x3141, 0x3142, 0x3143, 0x3145,
        0x3146, 0x3147, 0x3148, 0x3149, 0x314A, 0x314B, 0x314C, 0x314D, 0x314E,
    )
    private const val COMPATIBILITY_VOWELS = 0x314F
    private const val CONJOINING_VOWELS = 0x1161
    private const val VOWEL_COUNT = 21

    /** Compatibility jamo to the conjoining jamo the Korean KDB holds (`ㅂ` to `ᄇ`); others, clusters included, unchanged. */
    @JvmStatic
    fun toConjoining(codePoint: Int): Int {
        val consonant = CHOSEONG_COMPATIBILITY.indexOf(codePoint)
        if (consonant >= 0) return 0x1100 + consonant
        if (codePoint in COMPATIBILITY_VOWELS until COMPATIBILITY_VOWELS + VOWEL_COUNT) {
            return CONJOINING_VOWELS + (codePoint - COMPATIBILITY_VOWELS)
        }
        return codePoint
    }

    /** The compatibility jamo for a conjoining one; the inverse of [toConjoining]. */
    @JvmStatic
    fun toCompatibility(codePoint: Int): Int = when (codePoint) {
        in 0x1100 until 0x1100 + CHOSEONG_COMPATIBILITY.size -> CHOSEONG_COMPATIBILITY[codePoint - 0x1100]
        in CONJOINING_VOWELS until CONJOINING_VOWELS + VOWEL_COUNT -> COMPATIBILITY_VOWELS + (codePoint - CONJOINING_VOWELS)
        else -> codePoint
    }
}

package dev.bbkb.ime.core.layout

import android.view.KeyEvent
import java.util.Locale

/**
 * The engine's key letters, rewritten from a letter layout.
 *
 * The engine decodes taps and swipes against its own keyboard description, a KDB file
 * (`assets/kdb/<name>_pkb.xml`, or the device variant's copy): three `<area>` rows of key boxes in
 * QWERTY order, each key carrying the letters it stands for. A custom layout changes what the
 * physical keys type but not those files, so without this the engine would decode a swipe across
 * the keypad in the keypad's old letters. [rewrite] produces the KDB the engine should have while a
 * layout is active: every key at a position the layout names gets the layout's letters, every
 * other byte stays as it was. [LetterLayoutEngineSync] registers the result with the engine.
 *
 * ## What a key's letters are, in a KDB
 *
 * The owned loader (`xt9kdb-scaffold/cpp/src/kdb_load_xml.c`, `key_codes_of`) reads a key's symbol
 * list, primary first, from one of two forms:
 * - `keyLabel="Q"`: a single ASCII letter, read as its lower case, is the primary; a `keyCodes` list
 *   on the same key then holds only alternates (the Nordic `keyLabel="A" keyCodes="0x00E5, 0x00E6"`).
 * - otherwise `keyCodes="0x0635, 0x0636"`: the first code is the primary, the rest alternates.
 *
 * A rewritten key uses the first form when its new primary is an ASCII letter (`keyLabel`
 * upper-case, as every shipped KDB writes it, plus `keyCodes` only when there are alternates) and
 * the second for anything else, because the loader ignores a `keyLabel` that is not an ASCII
 * letter: a Dvorak apostrophe or an Arabic letter must be a code. The primary is the layout's
 * `keySpec`; the alternates are its Shift legend when that is not simply the upper case (ض on the
 * ص key) and then its double-tap letters. Codes are lower-cased, as the engine keeps letters
 * case-free, and Hangul compatibility jamo become the conjoining jamo the Korean KDB holds
 * ([toConjoining]; [toCompatibility] reads them back). A named key's letters come from the layout
 * alone: the KDB's own alternates on that key (å and æ on the Danish A) are replaced, not merged.
 *
 * The key's `keyType` follows the files' own convention when the layout moves a key between a
 * letter and a non-letter: letters (and combining marks) sit on `regional` keys, which take part in
 * tap correction and swipe decoding, while symbols are `nonRegional`, like the `$` key. So a letter
 * put on the KEY2's `$` key makes it regional, as the Arabic and Cyrillic KDBs already have it, and
 * Dvorak's punctuation on a letter position makes that key non-regional; a key that stays a letter
 * (or stays a symbol) keeps whatever type its file gave it. Geometry (`keyLeft`, `keyTop`,
 * `keyWidth`, `keyHeight`, bias) and the `<keyboard>` header are never touched.
 *
 * ## What stays byte-identical
 *
 * This is a text rewrite, not a re-serialisation: comments, white space, attribute order and every
 * key the layout does not name are copied through untouched, and so is a named key whose letters
 * and type already are what the layout says. A key that changes has only its `keyLabel`, `keyCodes`
 * and `keyType` attributes rewritten, in place.
 *
 * Positions follow the keyboard the file describes ([Template]). On the physical keypad
 * ([Template.PKB]): row 1 Q–P, row 2 A–L, row 3 Alt then Z–M and the extra key past M (the KEY2's
 * `$` key, named by `KEYCODE_4`), function keys skipped; the column is the key's centre over its
 * width. The generator (`xt9kdb-scaffold/tools/letter_layouts_gen.py`) reads the same files by the
 * same rules when it writes the built-in documents. On the on-screen keyboard ([Template.VKB]) the
 * keys of each of the first three rows, left to right and function keys skipped, are Q–P, A–L
 * then the semicolon key for a tenth key (the `spanish` rows), and Z–M; the fourth row (comma,
 * space, period) has no positions. A layout key that names some other key code (Space, a digit,
 * punctuation keys) has no position in a KDB and changes nothing here.
 *
 * ## The on-screen keyboard
 *
 * The engine picks an on-screen KDB by the language's ids alone, so a Colemak board on an English
 * keyboard would be decoded against `qwerty_vkb.xml`'s QWERTY letters, in rows of the wrong length
 * (Colemak has ten keys in the middle row). [forOnScreenKeyboard] builds the KDB the engine should
 * load instead: the geometry of the layout's rows template (`assets/kdb/<template>_vkb.xml`, whose
 * rows match the drawn ones), the layout's letters, and the ids of the KDB it stands in for, so the
 * engine finds it for that language.
 */
object LetterLayoutKdb {

    /** The geometry a KDB file describes, which decides how its keys map to key codes. */
    enum class Template(
        /** The suffix of the KDB files of this kind under `assets/kdb/`. */
        val fileSuffix: String,
    ) {
        /** The physical keypad: `<name>_pkb.xml`, positions by key centre on the keypad's grid. */
        PKB("_pkb.xml"),

        /** The on-screen keyboard: `<name>_vkb.xml`, positions by order within each letter row. */
        VKB("_vkb.xml"),
    }

    /** The prefix of the engine variant a layout's generated KDBs are registered under. */
    const val VARIANT_PREFIX: String = "layout:"

    /**
     * The engine variant name for [layout]'s generated KDBs. One name for both keyboards: the
     * engine keys a variant's files by their ids as well, and the physical keypad's files
     * (secondaryId 6) never share ids with the on-screen keyboard's.
     */
    @JvmStatic
    @JvmOverloads
    @Suppress("UNUSED_PARAMETER")
    fun variantName(layout: LetterLayout, template: Template = Template.PKB): String = VARIANT_PREFIX + layout.id

    /** The on-screen KDB a rows template's geometry comes from: `qwerty_vkb.xml`, `spanish_vkb.xml`. */
    @JvmStatic
    fun templateFile(rowsTemplate: String): String = rowsTemplate + Template.VKB.fileSuffix

    /**
     * The on-screen KDB the engine should load for [target]'s language while the on-screen
     * keyboard is drawn from [layout]: [template] (the KDB named by the layout's rows template,
     * [templateFile]) with the layout's letters and [target]'s `primaryId` and `secondaryId`. Null
     * when either file carries no ids.
     */
    @JvmStatic
    fun forOnScreenKeyboard(template: String, target: String, layout: LetterLayout): String? {
        val (primaryId, secondaryId) = engineIds(target) ?: return null
        val stamped = withEngineIds(template, primaryId, secondaryId) ?: return null
        return rewrite(stamped, layout, Template.VKB)
    }

    private val KEYBOARD_ELEMENT = Regex("<keyboard\\b[^>]*>")
    private val PRIMARY_ID = Regex("(\\bprimaryId\\s*=\\s*\")(\\d+)(\")")
    private val SECONDARY_ID = Regex("(\\bsecondaryId\\s*=\\s*\")(\\d+)(\")")

    /**
     * The `primaryId` and `secondaryId` of [xml]'s `<keyboard>` element: what the engine selects a
     * KDB by (the language, and 6 for the physical keypad or 1 for the on-screen keyboard). Null
     * when the element or either id is missing.
     */
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

    /**
     * [xml], a KDB file of [template], with the letters of every key [layout] names replaced by the
     * layout's; see the class doc for exactly what changes. Returns [xml] itself when nothing does.
     */
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

    /**
     * The symbol list the engine reads for each letter-carrying key of [xml], primary first, by the
     * key code of its position. Hangul stays in the conjoining jamo the file holds.
     */
    @JvmStatic
    @JvmOverloads
    fun codesByKeyCode(xml: String, template: Template = Template.PKB): Map<Int, IntArray> =
        keys(xml, template).associate { it.keyCode to it.codes }

    /**
     * The letter the engine has on each position of [xml]: its primary, in the compatibility jamo a
     * letter layout uses for Hangul. What [LetterLayout.changesLetters] compares a layout against.
     */
    @JvmStatic
    @JvmOverloads
    fun lettersByKeyCode(xml: String, template: Template = Template.PKB): Map<Int, Int> =
        codesByKeyCode(xml, template).filterValues { it.isNotEmpty() }.mapValues { toCompatibility(it.value[0]) }

    // ── reading the file ─────────────────────────────────────────────────────

    /** One `name="value"` inside a key element, as offsets into the whole file. */
    private class Attribute(
        /** Where the attribute's leading white space starts: removing [spanStart] until [end] removes it cleanly. */
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

    /**
     * The on-screen letter rows by position, left to right: the `spanish` rows' tenth middle key is
     * the one right of L on a QWERTY board, which a layout names `KEYCODE_SEMICOLON`.
     */
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
        // Comments are blanked (same length, so offsets hold) before anything is matched: a
        // commented-out key must not count as one.
        val masked = COMMENT.replace(xml) { " ".repeat(it.value.length) }
        val keys = ArrayList<KdbKey>()
        AREA.findAll(masked).forEachIndexed { row, area ->
            if (row >= ROWS.size) return@forEachIndexed
            // The on-screen rows are numbered left to right, so their keys are placed once the
            // whole row is read; the keypad's are placed by their own grid column.
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

    // ── rewriting one key ────────────────────────────────────────────────────

    /** The edits that give [key] the letters of [entry], or null when it already has them (or cannot). */
    private fun rewriteKey(key: KdbKey, entry: LetterLayout.Key): List<Edit>? {
        val primary = engineCode(entry.keySpec)
        // A primary the engine cannot hold leaves the key as it was: dropping its letter
        // altogether would make the key a dead spot for the decoder.
        if (primary <= 0 || primary > MAX_ENGINE_CODE) return null
        val codes = LinkedHashSet<Int>().apply { add(primary) }
        if (entry.shift != 0 && entry.shift != Character.toUpperCase(entry.keySpec)) codes += engineCode(entry.shift)
        singleCodePoints(entry.multitap).forEach { codes += engineCode(it) }
        val wanted = codes.filter { it in 1..MAX_ENGINE_CODE }.take(MAX_KEY_CODES)

        val labelForm = primary in 'a'.code..'z'.code
        // The type changes only when the layout moves a key between letter and non-letter, so a
        // key the files deliberately typed otherwise (a regional danda) keeps its type.
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
        // The letters: both attributes come out, the new ones go in where the first of them was
        // (after keyType when the key had neither), so attribute order stays as the files have it.
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

    /**
     * The double-tap entries of a key that are one code point each, which is all a KDB key code can
     * be: a ligature entry (lam-alef, a letter with geresh) has no single code to give the engine.
     */
    private fun singleCodePoints(entries: Array<String>): List<Int> =
        entries.filter { it.isNotEmpty() && it.codePointCount(0, it.length) == 1 }.map { it.codePointAt(0) }

    // ── Hangul ───────────────────────────────────────────────────────────────

    /** Compatibility jamo (U+3131..) of the conjoining choseong U+1100..U+1112, in order. */
    private val CHOSEONG_COMPATIBILITY = intArrayOf(
        0x3131, 0x3132, 0x3134, 0x3137, 0x3138, 0x3139, 0x3141, 0x3142, 0x3143, 0x3145,
        0x3146, 0x3147, 0x3148, 0x3149, 0x314A, 0x314B, 0x314C, 0x314D, 0x314E,
    )
    private const val COMPATIBILITY_VOWELS = 0x314F
    private const val CONJOINING_VOWELS = 0x1161
    private const val VOWEL_COUNT = 21

    /**
     * The conjoining jamo the engine's Korean KDB holds for a compatibility jamo (`ㅂ` → `ᄇ`, `ㅏ` →
     * `ᅡ`); anything else, including the compatibility consonant clusters that have no initial
     * form, is returned unchanged. The inverse of [toCompatibility].
     */
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

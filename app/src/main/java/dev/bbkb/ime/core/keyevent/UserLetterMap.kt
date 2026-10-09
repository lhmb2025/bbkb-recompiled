package dev.bbkb.ime.core.keyevent

import android.view.KeyEvent
import dev.bbkb.ime.core.device.detection.KeypadLayoutDetector
import dev.bbkb.ime.core.locale.LocaleUtils
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

/**
 * A letter map for the physical keys that the user imported: what each key types, with and
 * without Shift, under Alt, on a double tap and on a long press.
 *
 * It is the `bbkb-layout` document, kind `pkb`:
 *
 * ```json
 * {
 *   "format": "bbkb-layout",
 *   "version": 1,
 *   "kind": "pkb",
 *   "id": "swap-q-w",
 *   "name": "Q and W swapped",
 *   "bind": { "locales": ["en"], "keypadLayout": "qwerty" },
 *   "keys": {
 *     "KEYCODE_Q": { "base": "w", "shift": "W", "alt": "#", "multitap": ["ŵ"], "moreKeys": ["ŵ", "ẃ"] },
 *     "KEYCODE_W": { "scanCode": 17, "base": "q" }
 *   }
 * }
 * ```
 *
 * Keys are named by their Android key code, which is what the phone reports whatever its keycaps
 * say (see [HardwareScriptLayout]); `scanCode` narrows a key to one physical switch when two send
 * the same key code. `base`, `shift` and `alt` are one character each; `multitap` is what a double
 * (triple…) tap cycles through after `base`, and `moreKeys` is what holding the key offers. `shift`
 * defaults to the upper case of `base`. A key the map does not name types what it typed before.
 *
 * `bind.locales` names the keyboards the map is for, by language (`["en"]`, `["de", "fr_CH"]`); an
 * empty list means every keyboard. `bind.keypadLayout` is the keypad the map was written for; it
 * does nothing on a phone with a different one.
 *
 * Parsing is strict on purpose: the file is hand-written or passed around, and a typo that quietly
 * did nothing ("KEYCODE_O" for "KEYCODE_0", a two-letter `base`) is worse than a refusal that says
 * which key and which field. [parse] throws [InvalidLetterMapException] with that message.
 */
class UserLetterMap private constructor(
    val id: String,
    val name: String,
    /** The locales as written, in document order. Empty means every keyboard. */
    val locales: List<String>,
    val keypadLayout: String,
    /** By Android key code, in document order. */
    val keys: Map<Int, Key>,
) {

    /** One physical key. Code points; 0 (or an empty array) where the file names nothing. */
    class Key internal constructor(
        val keyCode: Int,
        /** The scan code the key must also report, or -1 for any. */
        val scanCode: Int,
        val base: Int,
        /** 0 for "the upper case of [base]". */
        val shift: Int,
        val alt: Int,
        val multitap: IntArray,
        val moreKeys: IntArray,
    ) {
        /** Whether an event with [eventScanCode] is this key. -1 on either side matches anything. */
        fun matches(eventScanCode: Int): Boolean =
            scanCode < 0 || eventScanCode < 0 || scanCode == eventScanCode

        /** What the key types: [base], or with Shift the `shift` legend, else upper-case [base]. */
        fun letter(shifted: Boolean): Int = when {
            !shifted -> base
            shift != 0 -> shift
            else -> Character.toUpperCase(base)
        }
    }

    val keyCount: Int get() = keys.size

    /** The entry for [keyCode] when it applies to an event with [scanCode] (-1: unknown). */
    fun key(keyCode: Int, scanCode: Int): Key? = keys[keyCode]?.takeIf { it.matches(scanCode) }

    /**
     * Whether the map drives the physical keys while a keyboard for [languageCode] (the app's
     * spelling, as [LocaleUtils.languageCode] gives it) is active on a [deviceKeypadLayout]
     * keypad. A null language matches only an unbound map; a null keypad layout is not checked.
     */
    fun appliesTo(languageCode: String?, deviceKeypadLayout: String?): Boolean {
        if (deviceKeypadLayout != null && !deviceKeypadLayout.equals(keypadLayout, ignoreCase = true)) return false
        if (locales.isEmpty()) return true
        if (languageCode.isNullOrEmpty()) return false
        return locales.any { languageOf(it) == languageCode }
    }

    /** The double-tap sequence for a typed [label], or null when the map has none for it. */
    fun multitapFor(label: String?): Array<String>? = sequenceFor(label) { it.multitap }

    /** What holding the key that typed [label] offers, or null when the map has nothing for it. */
    fun moreKeysFor(label: String?): Array<String>? = sequenceFor(label) { it.moreKeys }

    /**
     * Finds the key that typed [label] — by its unshifted letter, else by its Shift letter, in
     * which case the sequence comes back upper-cased the way the script tables do it (й twice
     * gives ё, Й twice gives Ё).
     */
    private fun sequenceFor(label: String?, pick: (Key) -> IntArray): Array<String>? {
        if (label.isNullOrEmpty()) return null
        val codePoint = label.codePointAt(0)
        if (Character.charCount(codePoint) != label.length) return null
        keys.values.firstOrNull { it.base == codePoint }?.let { key ->
            return pick(key).takeIf { it.isNotEmpty() }?.let(::strings)
        }
        keys.values.firstOrNull { it.letter(true) == codePoint && it.letter(true) != it.base }?.let { key ->
            return pick(key).takeIf { it.isNotEmpty() }?.map { Character.toUpperCase(it) }?.toIntArray()?.let(::strings)
        }
        return null
    }

    /**
     * Whether any of the 26 letter keys types something other than [expected] gives for it — the
     * question the touch keypad's swipe decoder needs answered, because it decodes against the
     * engine's own key geometry and would spell a swipe across the keys in the old letters.
     * Alt, multitap and long-press entries do not count; only the unshifted letter does.
     */
    fun changesLetters(expected: (keyCode: Int) -> Int): Boolean = keys.values.any { key ->
        key.keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z &&
            Character.toLowerCase(key.base) != Character.toLowerCase(expected(key.keyCode))
    }

    /** The same map under another id. */
    fun withId(newId: String): UserLetterMap = UserLetterMap(newId, name, locales, keypadLayout, keys)

    /** The document, in the shape [parse] reads, optional fields left out where unset. */
    fun toJson(): JSONObject {
        val keysJson = JSONObject()
        keys.values.forEach { key ->
            val entry = JSONObject()
            if (key.scanCode >= 0) entry.put("scanCode", key.scanCode)
            entry.put("base", string(key.base))
            if (key.shift != 0) entry.put("shift", string(key.shift))
            if (key.alt != 0) entry.put("alt", string(key.alt))
            if (key.multitap.isNotEmpty()) entry.put("multitap", JSONArray(strings(key.multitap).toList()))
            if (key.moreKeys.isNotEmpty()) entry.put("moreKeys", JSONArray(strings(key.moreKeys).toList()))
            keysJson.put(nameOf(key.keyCode), entry)
        }
        return JSONObject().apply {
            put("format", FORMAT)
            put("version", VERSION)
            put("kind", KIND_PKB)
            put("id", id)
            put("name", name)
            put("bind", JSONObject().apply {
                put("locales", JSONArray(locales))
                put("keypadLayout", keypadLayout)
            })
            put("keys", keysJson)
        }
    }

    fun serialize(): String = toJson().toString(2)

    companion object {
        const val FORMAT: String = "bbkb-layout"
        const val VERSION: Int = 1
        const val KIND_PKB: String = "pkb"

        /** Largest file [parse] reads. A whole keypad with long-press lists is a few kilobytes. */
        const val MAX_BYTES: Int = 256 * 1024
        const val MAX_KEYS: Int = 64
        const val MAX_SEQUENCE: Int = 16
        const val MAX_NAME_LENGTH: Int = 64
        const val MAX_LOCALES: Int = 32

        val KEYPAD_LAYOUTS: List<String> =
            listOf(KeypadLayoutDetector.QWERTY, KeypadLayoutDetector.QWERTZ, KeypadLayoutDetector.AZERTY)

        private val ID = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$")
        private val LOCALE = Regex("^[A-Za-z]{2,3}([_-][A-Za-z0-9]{1,8}){0,2}$")
        private val TOP_LEVEL_FIELDS = setOf("format", "version", "kind", "id", "name", "bind", "keys")
        private val BIND_FIELDS = setOf("locales", "keypadLayout")
        private val KEY_FIELDS = setOf("scanCode", "base", "shift", "alt", "multitap", "moreKeys")

        /**
         * The keys a map may name: the letters, the digits and the punctuation keys a hardware
         * keyboard sends. Function keys (Space, Enter, Delete, the modifiers) stay out — their
         * meaning is the keyboard's, not a letter.
         */
        private val KEY_CODES: Map<String, Int> = LinkedHashMap<String, Int>().apply {
            for (c in 'A'..'Z') put("KEYCODE_$c", KeyEvent.KEYCODE_A + (c - 'A'))
            for (d in 0..9) put("KEYCODE_$d", KeyEvent.KEYCODE_0 + d)
            put("KEYCODE_COMMA", KeyEvent.KEYCODE_COMMA)
            put("KEYCODE_PERIOD", KeyEvent.KEYCODE_PERIOD)
            put("KEYCODE_GRAVE", KeyEvent.KEYCODE_GRAVE)
            put("KEYCODE_MINUS", KeyEvent.KEYCODE_MINUS)
            put("KEYCODE_EQUALS", KeyEvent.KEYCODE_EQUALS)
            put("KEYCODE_LEFT_BRACKET", KeyEvent.KEYCODE_LEFT_BRACKET)
            put("KEYCODE_RIGHT_BRACKET", KeyEvent.KEYCODE_RIGHT_BRACKET)
            put("KEYCODE_BACKSLASH", KeyEvent.KEYCODE_BACKSLASH)
            put("KEYCODE_SEMICOLON", KeyEvent.KEYCODE_SEMICOLON)
            put("KEYCODE_APOSTROPHE", KeyEvent.KEYCODE_APOSTROPHE)
            put("KEYCODE_SLASH", KeyEvent.KEYCODE_SLASH)
            put("KEYCODE_AT", KeyEvent.KEYCODE_AT)
            put("KEYCODE_POUND", KeyEvent.KEYCODE_POUND)
            put("KEYCODE_STAR", KeyEvent.KEYCODE_STAR)
            put("KEYCODE_PLUS", KeyEvent.KEYCODE_PLUS)
        }
        private val KEY_NAMES: Map<Int, String> = KEY_CODES.entries.associate { (name, code) -> code to name }

        /** The key code a map names `KEYCODE_Q` by, or null when maps may not name it. */
        @JvmStatic
        fun keyCodeOf(name: String): Int? = KEY_CODES[name]

        @JvmStatic
        fun nameOf(keyCode: Int): String = KEY_NAMES[keyCode] ?: "KEYCODE_$keyCode"

        @JvmStatic
        fun isValidId(id: String?): Boolean = id != null && ID.matches(id)

        /** A fresh id for a map whose file did not carry one. Stable from then on: it is saved. */
        @JvmStatic
        fun newId(): String = "layout-" + UUID.randomUUID().toString().replace("-", "").take(12)

        /**
         * Reads one `bbkb-layout` document. [generateId] names a map whose file has no `id`.
         * @throws InvalidLetterMapException naming the first problem found
         */
        @JvmStatic
        @JvmOverloads
        fun parse(json: String, generateId: () -> String = ::newId): UserLetterMap {
            if (json.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
                fail("the file is larger than ${MAX_BYTES / 1024} KB")
            }
            val root = try {
                JSONObject(json)
            } catch (e: JSONException) {
                fail("not JSON: ${e.message}")
            }
            return fromJson(root, generateId)
        }

        /** [parse] for a document already read as JSON, e.g. one entry of a layouts bundle. */
        @JvmStatic
        @JvmOverloads
        fun fromJson(root: JSONObject, generateId: () -> String = ::newId): UserLetterMap {
            val format = root.optString("format")
            if (format != FORMAT) fail("format is \"$format\", expected \"$FORMAT\"")
            val version = (root.opt("version") as? Int) ?: 0
            if (version < 1 || version > VERSION) fail("version ${root.opt("version")}, this build reads up to $VERSION")
            rejectUnknown(root, TOP_LEVEL_FIELDS, "the layout")
            val kind = root.opt("kind")
            if (kind != KIND_PKB) fail("kind is ${quoted(kind)}, only \"$KIND_PKB\" is supported")

            val id = when (val raw = root.opt("id")) {
                null, JSONObject.NULL -> generateId()
                is String -> raw.also { if (!isValidId(it)) fail("id \"$it\" may only use letters, digits, - and _ (at most 64)") }
                else -> fail("id must be a string")
            }

            val name = (root.opt("name") as? String)?.trim() ?: fail("name is missing or not a string")
            if (name.isEmpty()) fail("name is empty")
            if (name.length > MAX_NAME_LENGTH) fail("name is longer than $MAX_NAME_LENGTH characters")
            if (name.any { Character.isISOControl(it) }) fail("name contains a control character")

            val bind = root.opt("bind") as? JSONObject ?: fail("bind is missing or not an object")
            rejectUnknown(bind, BIND_FIELDS, "bind")
            val localesJson = bind.opt("locales") as? JSONArray ?: fail("bind.locales is missing or not a list")
            if (localesJson.length() > MAX_LOCALES) fail("bind.locales has more than $MAX_LOCALES entries")
            val locales = (0 until localesJson.length()).map { i ->
                val locale = localesJson.opt(i) as? String ?: fail("bind.locales[$i] is not a string")
                if (!LOCALE.matches(locale)) fail("bind.locales[$i] \"$locale\" is not a locale such as \"en\" or \"pt_BR\"")
                locale
            }
            val keypadLayout = (bind.opt("keypadLayout") as? String)?.lowercase()
                ?: fail("bind.keypadLayout is missing or not a string")
            if (keypadLayout !in KEYPAD_LAYOUTS) {
                fail("bind.keypadLayout is \"$keypadLayout\", expected one of ${KEYPAD_LAYOUTS.joinToString(", ")}")
            }

            val keysJson = root.opt("keys") as? JSONObject ?: fail("keys is missing or not an object")
            if (keysJson.length() == 0) fail("keys is empty")
            if (keysJson.length() > MAX_KEYS) fail("keys names ${keysJson.length()} keys, at most $MAX_KEYS")
            val keys = LinkedHashMap<Int, Key>()
            keysJson.keys().forEach { keyName ->
                val keyCode = keyCodeOf(keyName)
                    ?: fail("$keyName is not a key a layout can change (letters, digits and punctuation keys, named like KEYCODE_Q)")
                val entry = keysJson.opt(keyName) as? JSONObject ?: fail("$keyName is not an object")
                keys[keyCode] = parseKey(keyName, keyCode, entry)
            }
            return UserLetterMap(id, name, locales, keypadLayout, keys)
        }

        private fun parseKey(keyName: String, keyCode: Int, entry: JSONObject): Key {
            rejectUnknown(entry, KEY_FIELDS, keyName)
            val scanCode = when (val raw = entry.opt("scanCode")) {
                null, JSONObject.NULL -> -1
                is Int, is Long -> (raw as Number).toLong().let {
                    if (it !in 1L..0xFFFFL) fail("$keyName.scanCode $it is out of range")
                    it.toInt()
                }
                else -> fail("$keyName.scanCode must be a whole number")
            }
            val base = codePoint(entry, "base", keyName) ?: fail("$keyName.base is missing")
            val shift = codePoint(entry, "shift", keyName) ?: 0
            val alt = codePoint(entry, "alt", keyName) ?: 0
            return Key(
                keyCode = keyCode,
                scanCode = scanCode,
                base = base,
                shift = shift,
                alt = alt,
                multitap = sequence(entry, "multitap", keyName),
                moreKeys = sequence(entry, "moreKeys", keyName),
            )
        }

        private fun codePoint(entry: JSONObject, field: String, keyName: String): Int? =
            when (val raw = entry.opt(field)) {
                null, JSONObject.NULL -> null
                is String -> singleCodePoint(raw, "$keyName.$field")
                else -> fail("$keyName.$field must be a string")
            }

        private fun sequence(entry: JSONObject, field: String, keyName: String): IntArray {
            val raw = entry.opt(field)
            if (raw == null || raw == JSONObject.NULL) return IntArray(0)
            val array = raw as? JSONArray ?: fail("$keyName.$field must be a list")
            if (array.length() > MAX_SEQUENCE) fail("$keyName.$field has more than $MAX_SEQUENCE entries")
            return IntArray(array.length()) { i ->
                val value = array.opt(i) as? String ?: fail("$keyName.$field[$i] must be a string")
                singleCodePoint(value, "$keyName.$field[$i]")
            }
        }

        /** The one code point [value] holds, refusing anything that is not exactly one character. */
        private fun singleCodePoint(value: String, where: String): Int {
            if (value.isEmpty()) fail("$where is empty")
            val codePoint = value.codePointAt(0)
            if (Character.charCount(codePoint) != value.length) {
                fail("$where must be a single character, not \"$value\"")
            }
            if (Character.isISOControl(codePoint)) fail("$where is a control character")
            if (Character.getType(codePoint) == Character.SURROGATE.toInt()) fail("$where is half of a character")
            return codePoint
        }

        private fun rejectUnknown(obj: JSONObject, known: Set<String>, where: String) {
            obj.keys().forEach { field ->
                if (field !in known) fail("$where has an unknown field \"$field\"")
            }
        }

        private fun languageOf(locale: String): String? =
            LocaleUtils.constructLocaleFromString(locale)?.let { LocaleUtils.languageCode(it) }

        private fun quoted(value: Any?): String = if (value is String) "\"$value\"" else value.toString()

        private fun string(codePoint: Int): String = String(Character.toChars(codePoint))

        private fun strings(codePoints: IntArray): Array<String> =
            Array(codePoints.size) { string(codePoints[it]) }

        private fun fail(message: String): Nothing = throw InvalidLetterMapException(message)
    }
}

/** A `bbkb-layout` document that this build will not use, with the reason in [message]. */
class InvalidLetterMapException(message: String) : Exception(message)

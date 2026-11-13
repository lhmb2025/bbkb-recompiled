package dev.bbkb.ime.core.layout

import android.view.KeyEvent
import dev.bbkb.ime.core.device.detection.KeypadLayoutDetector
import dev.bbkb.ime.core.locale.LocaleUtils
import java.util.Locale
import java.util.UUID

/**
 * One letter layout: what each physical key types plain, shifted, under Alt, on multitap and on long
 * press. Parsed from a `letter-layout` document ([LetterLayoutXml]), shipped or user-imported
 * ([LetterLayoutRegistry]). Keys are named by Android key code; [Key.scanCode] narrows to one switch
 * when two share a key code. Unnamed keys fall through to normal typing.
 * Immutable: the typing path reads one parsed copy from any thread.
 */
class LetterLayout internal constructor(
    val id: String,
    val name: String,
    val origin: Origin,
    /** Which keyboards the document describes. Never empty. */
    val targets: Set<Target>,
    val binding: Binding,
    /** The on-screen geometry template the letters fill ([LetterLayoutXml.ROWS_TEMPLATES]), or null. */
    val rowsTemplate: String?,
    /** By Android key code, in document order. */
    val keys: Map<Int, Key>,
    /** Offered as a choice but never selected as a default (etched keypad editions such as Thai, Zhuyin). */
    val optIn: Boolean = false,
) {
    enum class Origin { BUILT_IN, USER }

    enum class Target(val token: String) {
        PKB("pkb"),
        VKB("vkb");

        companion object {
            @JvmStatic
            fun of(token: String): Target? = entries.firstOrNull { it.token == token }
        }
    }

    /**
     * The keyboards a layout is for: languages (empty for all), keypad shape, keypad type (`3row`) and
     * layout set, each or [ANY]. The layout set separates same-language keyboards with different keys
     * (Russian vs Russian translit, Chinese stroke vs pinyin).
     */
    class Binding internal constructor(
        /** As written, in document order. */
        val languages: List<String>,
        val keypad: String,
        val keypadType: String,
        /** The subtype's `KeyboardLayoutSet` name, lower case, or [ANY]. */
        val layoutSet: String,
    ) {
        /** Each language entry as (language in the app's spelling, region or ""). */
        private val parsedLanguages: List<Pair<String, String>> = languages.map(::languageAndRegion)

        val isLanguageBound: Boolean get() = languages.isNotEmpty()

        /**
         * Whether every named part matches ([language] as [LocaleUtils.languageCode] spells it, or a
         * full locale like `pt_BR`). A null language matches only an unbound layout; a null keypad,
         * keypad type or layout set never rejects ("unknown" is not "different").
         */
        fun matches(language: String?, keypad: String?, keypadType: String?, layoutSet: String?): Boolean {
            if (this.keypad != ANY && keypad != null && !keypad.equals(this.keypad, ignoreCase = true)) return false
            if (this.keypadType != ANY && keypadType != null && !keypadTypeMatches(keypadType)) return false
            if (this.layoutSet != ANY && layoutSet != null && !layoutSetMatches(layoutSet)) return false
            return matchesLanguage(language)
        }

        fun matchesLanguage(language: String?): Boolean {
            if (languages.isEmpty()) return true
            if (language.isNullOrEmpty()) return false
            val (code, region) = languageAndRegion(language)
            // A bare language on either side covers every region of it; two regions must agree.
            return parsedLanguages.any { (entryCode, entryRegion) ->
                entryCode == code && (entryRegion.isEmpty() || region.isEmpty() || entryRegion == region)
            }
        }

        fun keypadTypeMatches(keypadType: String?): Boolean = when (this.keypadType) {
            ANY -> true
            KEYPAD_TYPE_THREE_ROW -> isThreeRow(keypadType)
            else -> false
        }

        fun layoutSetMatches(layoutSet: String?): Boolean =
            this.layoutSet == ANY || this.layoutSet.equals(layoutSet, ignoreCase = true)
    }

    /** One physical key. Legends are code points (0 = none); list entries may be ligatures (لا, l·l). */
    class Key internal constructor(
        val keyCode: Int,
        /** The scan code the key must also report, or -1 for any. */
        val scanCode: Int,
        val keySpec: Int,
        /** The Shift legend; 0 for "the upper case of [keySpec]". */
        val shift: Int,
        val alt: Int,
        /** What repeated taps cycle through after [keySpec]. Read only. */
        val multitap: Array<String>,
        /** Long-press choices. Read only. */
        val moreKeys: Array<String>,
        /**
         * Extra letters the engine's word matching counts as this key (ñ on Spanish n, å and æ on
         * Danish a). They change nothing typed; they let tap correction and swipe reach those words. Read only.
         */
        val engineKeys: IntArray = NO_CODE_POINTS,
    ) {
        /** Whether an event with [eventScanCode] is this key. -1 on either side matches anything. */
        fun matches(eventScanCode: Int): Boolean =
            scanCode < 0 || eventScanCode < 0 || scanCode == eventScanCode

        /** What the key types: [keySpec], or with Shift the `shift` legend, else upper-case [keySpec]. */
        fun letter(shifted: Boolean): Int = when {
            !shifted -> keySpec
            shift != 0 -> shift
            else -> Character.toUpperCase(keySpec)
        }
    }

    /** First key in document order typing each letter unshifted: the double-tap and hold lookups. */
    private val byLetter: Map<Int, Key> = HashMap<Int, Key>().also { letters ->
        keys.values.forEach { key -> letters.putIfAbsent(key.keySpec, key) }
    }

    val keyCount: Int get() = keys.size

    /** The entry for [keyCode] when it applies to an event with [scanCode] (-1: unknown). */
    fun key(keyCode: Int, scanCode: Int): Key? = keys[keyCode]?.takeIf { it.matches(scanCode) }

    /** The first key, in document order, that types [codePoint] without Shift, or null. */
    fun keyForLetter(codePoint: Int): Key? = byLetter[codePoint]

    fun appliesTo(language: String?, keypad: String?, keypadType: String?, layoutSet: String?): Boolean =
        binding.matches(language, keypad, keypadType, layoutSet)

    /**
     * Whether any A to Z key's unshifted letter differs from [expected]: decides whether the engine
     * needs this layout's letters ([LetterLayoutEngineSync]), since it decodes taps and swipes
     * against its own key letters.
     */
    fun changesLetters(expected: (keyCode: Int) -> Int): Boolean = keys.values.any { key ->
        key.keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z &&
            Character.toLowerCase(key.keySpec) != Character.toLowerCase(expected(key.keyCode))
    }

    fun withId(newId: String): LetterLayout =
        LetterLayout(newId, name, origin, targets, binding, rowsTemplate, keys, optIn)

    /** The same layout under another name. [newName] must be one a document can carry ([LetterLayoutXml.MAX_NAME_LENGTH]). */
    fun withName(newName: String): LetterLayout =
        LetterLayout(id, newName, origin, targets, binding, rowsTemplate, keys, optIn)

    /** This layout with only the keys [keep] accepts (or itself when all are kept). */
    internal fun onlyKeys(keep: (keyCode: Int) -> Boolean): LetterLayout {
        if (keys.keys.all(keep)) return this
        val kept = LinkedHashMap<Int, Key>()
        keys.forEach { (code, key) -> if (keep(code)) kept[code] = key }
        return LetterLayout(id, name, origin, targets, binding, rowsTemplate, kept, optIn)
    }

    companion object {
        /** Shared empty [Key.engineKeys]; empty, so sharing cannot leak a change between keys. */
        internal val NO_CODE_POINTS: IntArray = IntArray(0)

        const val ANY: String = "any"
        const val KEYPAD_TYPE_THREE_ROW: String = "3row"

        /** The keypad shapes a binding may name, besides [ANY]. */
        @JvmField
        val KEYPADS: List<String> =
            listOf(KeypadLayoutDetector.QWERTY, KeypadLayoutDetector.QWERTZ, KeypadLayoutDetector.AZERTY)

        private val ID = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")

        /**
         * The keys a layout may change while typing: letters, digits and punctuation. Space, Enter,
         * Delete and modifiers are excluded so a slip in a hand-written file cannot take them away.
         */
        private val CHARACTER_KEYS: Set<Int> = HashSet<Int>().apply {
            addAll(KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z)
            addAll(KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9)
            addAll(
                listOf(
                    KeyEvent.KEYCODE_COMMA, KeyEvent.KEYCODE_PERIOD, KeyEvent.KEYCODE_GRAVE,
                    KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_LEFT_BRACKET,
                    KeyEvent.KEYCODE_RIGHT_BRACKET, KeyEvent.KEYCODE_BACKSLASH, KeyEvent.KEYCODE_SEMICOLON,
                    KeyEvent.KEYCODE_APOSTROPHE, KeyEvent.KEYCODE_SLASH, KeyEvent.KEYCODE_AT,
                    KeyEvent.KEYCODE_POUND, KeyEvent.KEYCODE_STAR, KeyEvent.KEYCODE_PLUS,
                )
            )
        }

        /** Whether [keyCode] is a key a layout changes while typing; see [CHARACTER_KEYS]. */
        @JvmStatic
        fun isCharacterKey(keyCode: Int): Boolean = keyCode in CHARACTER_KEYS

        /** `[a-z0-9][a-z0-9._-]{0,63}`: what keeps an id usable as a file name. */
        @JvmStatic
        fun isValidId(id: String?): Boolean = id != null && ID.matches(id)

        /** A fresh id for a document without one; saved, so stable from then on. */
        @JvmStatic
        fun newId(): String = "layout-" + UUID.randomUUID().toString().replace("-", "").take(12)

        /** Whether the device profile's keypad type is three-row; keyboard XML matches "3 3row", so both spellings count. */
        @JvmStatic
        fun isThreeRow(keypadType: String?): Boolean =
            keypadType.equals(KEYPAD_TYPE_THREE_ROW, ignoreCase = true) || keypadType == "3"

        /** "iw_IL" → ("iw", "IL"); either spelling of Hebrew, Indonesian and Yiddish comes out as the app's. */
        private fun languageAndRegion(tag: String): Pair<String, String> {
            val locale = LocaleUtils.constructLocaleFromString(tag) ?: return tag.lowercase(Locale.ROOT) to ""
            return LocaleUtils.languageCode(locale) to locale.country.uppercase(Locale.ROOT)
        }

        internal fun string(codePoint: Int): String = String(Character.toChars(codePoint))
    }
}

/** A `letter-layout` document that this build will not use, with the reason in [message]. */
class InvalidLetterLayoutException(message: String) : Exception(message)

package dev.bbkb.ime.core.layout

import android.view.KeyEvent
import dev.bbkb.ime.core.device.detection.KeypadLayoutDetector
import dev.bbkb.ime.core.locale.LocaleUtils
import java.util.Locale
import java.util.UUID

/**
 * One letter layout: what each physical key types, with and without Shift, under Alt, on a
 * double tap and on a long press. Read from a `letter-layout` document ([LetterLayoutXml]),
 * either one the app ships (`assets/letter_layouts/`) or one the user imported
 * (`files/layouts/letter/`); [LetterLayoutRegistry] knows both.
 *
 * Keys are named by their Android key code, which is what the phone reports whatever its keycaps
 * say; [Key.scanCode] narrows a key to one physical switch when two send the same key code. A key
 * the layout does not name falls through to whatever would have typed it without the layout.
 *
 * Immutable: the typing path keeps one parsed copy and reads it from any thread.
 */
class LetterLayout internal constructor(
    val id: String,
    val name: String,
    val origin: Origin,
    /** Which keyboards the document describes. Never empty. */
    val targets: Set<Target>,
    val binding: Binding,
    /**
     * The on-screen geometry template the letters fill (`qwerty`, `spanish`; see
     * [LetterLayoutXml.ROWS_TEMPLATES]), or null when not given.
     */
    val rowsTemplate: String?,
    /** By Android key code, in document order. */
    val keys: Map<Int, Key>,
    /**
     * A shipped layout offered to the keyboards its binding names as a choice, never applied as
     * their default: the layout of an etched keypad edition (Thai, Zhuyin), which a user with that
     * edition picks once. The registry never selects it for a language on its own.
     */
    val optIn: Boolean = false,
) {

    /** Where a layout came from: shipped with the app, or imported by the user. */
    enum class Origin { BUILT_IN, USER }

    /** The keyboards a document may describe, by the token the document spells them with. */
    enum class Target(val token: String) {
        PKB("pkb"),
        VKB("vkb");

        companion object {
            @JvmStatic
            fun of(token: String): Target? = entries.firstOrNull { it.token == token }
        }
    }

    /**
     * The keyboards a layout is for: the languages (empty for every keyboard), the keypad shape
     * (`qwerty`/`qwertz`/`azerty` or `any`), the keypad type (`3row` or `any`) and the keyboard
     * layout set (`translit`, `east_slavic`, … or `any`).
     *
     * The layout set is what tells two keyboards of one language apart when their keys differ:
     * Russian and Russian (translit) share the language `ru` but not their tables, and the
     * Chinese stroke keyboard shares `zh` with pinyin while having no physical-key lists at all.
     */
    class Binding internal constructor(
        /** As written, in document order. */
        val languages: List<String>,
        val keypad: String,
        val keypadType: String,
        /** The subtype's `KeyboardLayoutSet` name, lower case, or [ANY]. */
        val layoutSet: String,
    ) {
        /** Each language entry as (language in the app's spelling, region or ""), for matching. */
        private val parsedLanguages: List<Pair<String, String>> = languages.map(::languageAndRegion)

        val isLanguageBound: Boolean get() = languages.isNotEmpty()

        /**
         * Whether the layout applies while a keyboard for [language] (the app's spelling, as
         * [LocaleUtils.languageCode] gives it; a full locale such as `pt_BR` also works) is active
         * on a [keypad] keypad of [keypadType], built from the layout set [layoutSet]. Every named
         * part must match and `any`/empty matches everything. A null language matches only a
         * layout bound to no language; a null keypad, keypad type or layout set is not held
         * against the layout, because "unknown" is not "different".
         */
        fun matches(language: String?, keypad: String?, keypadType: String?, layoutSet: String?): Boolean {
            if (this.keypad != ANY && keypad != null && !keypad.equals(this.keypad, ignoreCase = true)) return false
            if (this.keypadType != ANY && keypadType != null && !keypadTypeMatches(keypadType)) return false
            if (this.layoutSet != ANY && layoutSet != null && !layoutSetMatches(layoutSet)) return false
            return matchesLanguage(language)
        }

        /** The language part of [matches] alone. */
        fun matchesLanguage(language: String?): Boolean {
            if (languages.isEmpty()) return true
            if (language.isNullOrEmpty()) return false
            val (code, region) = languageAndRegion(language)
            // A bare language on either side covers every region of it; two regions must agree.
            return parsedLanguages.any { (entryCode, entryRegion) ->
                entryCode == code && (entryRegion.isEmpty() || region.isEmpty() || entryRegion == region)
            }
        }

        /** Whether the phone's keypad type ([keypadType]) is the one this binding names. */
        fun keypadTypeMatches(keypadType: String?): Boolean = when (this.keypadType) {
            ANY -> true
            KEYPAD_TYPE_THREE_ROW -> isThreeRow(keypadType)
            else -> false
        }

        /** Whether the current keyboard's layout set ([layoutSet]) is the one this binding names. */
        fun layoutSetMatches(layoutSet: String?): Boolean =
            this.layoutSet == ANY || this.layoutSet.equals(layoutSet, ignoreCase = true)
    }

    /**
     * One physical key. The legends are code points, 0 where the document names nothing; the
     * lists are strings of one or more code points (the tables hold ligatures such as لا and
     * l·l), empty where the document names nothing.
     */
    class Key internal constructor(
        val keyCode: Int,
        /** The scan code the key must also report, or -1 for any. */
        val scanCode: Int,
        /** What the key types. */
        val keySpec: Int,
        /** The Shift legend; 0 for "the upper case of [keySpec]". */
        val shift: Int,
        val alt: Int,
        /** What a double (triple…) tap cycles through after [keySpec]. Read only. */
        val multitap: Array<String>,
        /** What holding the key offers. Read only. */
        val moreKeys: Array<String>,
        /**
         * Letters the engine's word matching also counts as this key, after [keySpec], the Shift
         * legend and the [multitap] letters: ñ on the Spanish n, å and æ on the Danish a. They
         * change nothing the key types; they keep tap correction and swipe reaching the words that
         * use them. Read only.
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

    /** [Binding.matches] for this layout's binding. */
    fun appliesTo(language: String?, keypad: String?, keypadType: String?, layoutSet: String?): Boolean =
        binding.matches(language, keypad, keypadType, layoutSet)

    /**
     * Whether any of the 26 letter keys types something other than [expected] gives for it — the
     * question that decides whether the engine needs this layout's letters
     * ([LetterLayoutEngineSync]): it decodes taps and swipes against its own key letters, and
     * would spell a swipe across the keys in the old ones. Alt, multitap and long-press entries do
     * not count; only the unshifted letter does.
     */
    fun changesLetters(expected: (keyCode: Int) -> Int): Boolean = keys.values.any { key ->
        key.keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z &&
            Character.toLowerCase(key.keySpec) != Character.toLowerCase(expected(key.keyCode))
    }

    /** The same layout under another id. */
    fun withId(newId: String): LetterLayout =
        LetterLayout(newId, name, origin, targets, binding, rowsTemplate, keys, optIn)

    /**
     * This layout with only the keys [keep] accepts, or this layout itself when it accepts them
     * all. The typing path uses it to leave the keyboard's own keys (Enter, Delete, the
     * modifiers) to the keyboard.
     */
    internal fun onlyKeys(keep: (keyCode: Int) -> Boolean): LetterLayout {
        if (keys.keys.all(keep)) return this
        val kept = LinkedHashMap<Int, Key>()
        keys.forEach { (code, key) -> if (keep(code)) kept[code] = key }
        return LetterLayout(id, name, origin, targets, binding, rowsTemplate, kept, optIn)
    }

    companion object {
        /** The [Key.engineKeys] of a key that names none; empty, so sharing it cannot leak a change between keys. */
        internal val NO_CODE_POINTS: IntArray = IntArray(0)

        const val ANY: String = "any"
        const val KEYPAD_TYPE_THREE_ROW: String = "3row"

        /** The keypad shapes a binding may name, besides [ANY]. */
        @JvmField
        val KEYPADS: List<String> =
            listOf(KeypadLayoutDetector.QWERTY, KeypadLayoutDetector.QWERTZ, KeypadLayoutDetector.AZERTY)

        private val ID = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")

        /**
         * The keys a layout changes on the typing path: the letters, the digits and the punctuation
         * keys a hardware keyboard sends, the set the earlier letter-map format allowed. A document
         * may name any key code, but Space, Enter, Delete and the modifiers keep the keyboard's
         * meaning while typing, so a slip in a hand-written file cannot take them away.
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

        /** A fresh id for a layout whose document did not carry one. Stable from then on: it is saved. */
        @JvmStatic
        fun newId(): String = "layout-" + UUID.randomUUID().toString().replace("-", "").take(12)

        /**
         * Whether a phone's keypad type, as the device profile reports it, is a three-row keypad.
         * The keyboard XML matches the same property against "3 3row", so both spellings count.
         */
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

package dev.bbkb.ime.core.layout

import android.content.Context
import android.view.KeyEvent
import dev.bbkb.ime.core.ImeApplication
import dev.bbkb.ime.core.device.detection.KeyEventDeviceClassifier
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.keyevent.ModifierState
import dev.bbkb.ime.core.locale.LocaleUtils
import dev.bbkb.ime.core.locale.ResourceLocaleUtils
import dev.bbkb.ime.core.locale.SubtypeManager
import dev.bbkb.ime.core.settings.util.SettingsManager
import dev.bbkb.ime.core.shared.InputPathDebug
import dev.bbkb.ime.core.shared.Logger
import dev.bbkb.ime.core.shared.StringHelper
import dev.bbkb.ime.keyboard.KeyboardSwitcher
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap

/**
 * Every question the typing path asks about a physical key, answered in one place: what it types
 * (with and without Shift), what holding it for a capital types, what a double tap cycles
 * through, what holding it offers, and what it types under Alt.
 *
 * Each answer walks the same stack:
 *
 *  1. the letter layout the user switched on (Physical keyboard > Physical layouts), while
 *     it is bound to the current keyboard and this phone's keypad;
 *  2. the built-in document for the current keyboard's language, keypad, keypad type and layout
 *     set (`assets/letter-layouts/`), while "Use active language's alphabet for physical keyboard"
 *     ([PREF_KEY]) is on — that switch turns the built-in documents off and leaves a user layout
 *     in charge, as it always has;
 *  3. for Alt only, the device's own Alt tables, which stay in `AuxCharacterResolver` as the tier
 *     under this one (it asks [alt] first);
 *  4. the firmware key map: 0 or null here, and the caller asks the key map as before.
 *
 * A key the user layout names wins outright; a key it does not name falls through to the built-in
 * document, and one neither names to the key map. A list the user layout leaves empty falls through
 * the same way.
 *
 * Three rules keep the built-in documents typing exactly what the old tables and engine layouts
 * typed (attic plan §11.1):
 *
 *  1. A `KEYCODE_4` entry without a `scanCode` applies only while the event's character is a
 *     currency symbol: the KEY2's `$` key, which carries a letter on Arabic, Hebrew and Cyrillic
 *     keycaps. A Titan's 4 key sends the same key code and stays a digit.
 *  2. A built-in document's Latin letter is left to the firmware key map, so AZERTY/QWERTZ key
 *     maps and dead keys keep deciding what a Latin key types; the document still supplies the
 *     key's lists. A user layout's letter always applies: the user said what the key types.
 *  3. The lists for an upper-case letter are the key's lists upper-cased with the keyboard
 *     language's own rules (ß becomes SS, a Turkish i becomes İ, Greek keeps its tonos), less any
 *     entry that comes out equal to the letter itself, as the old tables built them.
 *
 * The currency key keeps the lists the old tables gave the currency symbols (`$` offers € ¥ £ ₱ ¢,
 * and so on), on every keyboard, rearranged around the "Default currency" setting as before.
 *
 * Everything the stack depends on — the active user layout, the built-in documents, the keyboard's
 * locale, layout set and extra languages, the keypad, the switch, the currency setting — comes in
 * through a source that tests replace; [init] points them at the running keyboard.
 */
object PhysicalKeyResolver {

    /** "Use active language's alphabet for physical keyboard"; on by default. */
    const val PREF_KEY = "pref_pkb_active_language_alphabet"
    private const val TAG = "PhysicalKeys"

    /** The built-in documents, as the resolver needs them. [init] hands it the registry's. */
    interface BuiltIns {
        /** The document for a keyboard, as [LetterLayoutRegistry.builtIn] selects it, or null. */
        fun select(language: String, keypad: String?, keypadType: String?, layoutSet: String?): LetterLayout?

        /** Every built-in document that parses. */
        fun all(): List<LetterLayout>
    }

    /** A registry's built-in documents as [BuiltIns]. */
    internal class RegistryBuiltIns(private val registry: LetterLayoutRegistry) : BuiltIns {
        override fun select(language: String, keypad: String?, keypadType: String?, layoutSet: String?) =
            registry.builtIn(language, keypad, keypadType, layoutSet)

        override fun all(): List<LetterLayout> = registry.builtInIds().mapNotNull { registry.builtIn(it) }
    }

    /** Points the sources at the running keyboard: the user's active layout and the shipped documents. */
    @JvmStatic
    fun init(context: Context) {
        val app = context.applicationContext
        userLayoutSource = { LetterLayoutRegistry.activeForTyping(app) }
        builtIns = RegistryBuiltIns(LetterLayoutRegistry(app))
    }

    // ── sources ──────────────────────────────────────────────────────────────

    /** The active user layout, whatever it is bound to. Set by [init]; tests replace it. */
    @Volatile
    internal var userLayoutSource: () -> LetterLayout? = { null }

    /**
     * The built-in documents for the typing path; null (no built-in tier) until [init]. Tests
     * replace it. The Language screen's [supportsLocale] reaches the app's documents without it.
     */
    @Volatile
    internal var builtIns: BuiltIns? = null

    /** The current keyboard's locale; its language picks the documents and its case rules apply. */
    @Volatile
    internal var localeSource: () -> Locale? = { SubtypeManager.getInstance()?.currentSubtypeLocale }

    /** A multi-language keyboard's extra languages, in order; null or empty for one language. */
    @Volatile
    internal var additionalLocalesSource: () -> Collection<Locale>? = {
        SubtypeManager.getInstance()?.currentSubtype?.let { ResourceLocaleUtils.getAdditionalLocales(it) }
    }

    /** This phone's keypad layout (`qwerty`/`qwertz`/`azerty`), or null when unknown. */
    @Volatile
    internal var keypadLayoutSource: () -> String? = { DeviceProfile.current()?.keypadLayout }

    /** This phone's keypad type (`3row`, `4row`, `none`), or null when unknown. */
    @Volatile
    internal var keypadTypeSource: () -> String? = { DeviceProfile.current()?.effectiveKeypadType }

    /** The current keyboard's layout set (`qwerty`, `translit`, `east_slavic`), or null. */
    @Volatile
    internal var layoutSetSource: () -> String? = {
        SubtypeManager.getInstance()?.currentSubtype?.let { ResourceLocaleUtils.getKeyboardLayoutSetName(it) }
    }

    /** The [PREF_KEY] switch, as the loaded settings hold it; on when there are none yet. */
    @Volatile
    internal var alphabetSwitchSource: () -> Boolean = {
        SettingsManager.getInstance()?.settingsValues?.pkbUsesActiveLanguageAlphabet ?: true
    }

    /** The "Default currency" setting; "" leaves the currency key's own symbol alone. */
    @Volatile
    internal var currencySettingSource: () -> String = {
        SettingsManager.getInstance()?.settingsValues?.currencySymbol ?: ""
    }

    /** What the key map types on the 0 key, which the currency rearrangement compares against. */
    @Volatile
    internal var zeroKeySource: () -> String? = {
        DeviceProfile.getKeyCharacterMap()?.get(KeyEvent.KEYCODE_0, 0)?.takeIf { it != 0 }?.toChar()?.toString()
    }

    /** Every source as it is now, to be put back with [Snapshot.restore]. For tests. */
    internal class Snapshot {
        private val user = userLayoutSource
        private val docs = builtIns
        private val locale = localeSource
        private val additional = additionalLocalesSource
        private val keypad = keypadLayoutSource
        private val keypadType = keypadTypeSource
        private val layoutSet = layoutSetSource
        private val alphabet = alphabetSwitchSource
        private val currency = currencySettingSource
        private val zero = zeroKeySource

        fun restore() {
            userLayoutSource = user
            builtIns = docs
            localeSource = locale
            additionalLocalesSource = additional
            keypadLayoutSource = keypad
            keypadTypeSource = keypadType
            layoutSetSource = layoutSet
            alphabetSwitchSource = alphabet
            currencySettingSource = currency
            zeroKeySource = zero
            typingCopy = null
            lastBuiltIn = null
        }
    }

    private fun <T> ask(source: () -> T, fallback: T): T = try {
        source()
    } catch (e: Exception) {
        fallback
    }

    private fun locale(): Locale? = ask(localeSource, null)

    private fun language(locale: Locale?): String? = locale?.let { LocaleUtils.languageCode(it) }

    // ── the two layout tiers ─────────────────────────────────────────────────

    /** The last layout [userLayout] trimmed to the character keys, and the trimmed copy. */
    @Volatile
    private var typingCopy: Pair<LetterLayout, LetterLayout>? = null

    /**
     * The user's letter layout when it drives the physical keys right now — switched on, bound to
     * the current keyboard's language (or to none), its layout set and this phone's keypad — else
     * null. Only its character keys: the typing path never lets a document take Space, Enter or a
     * modifier.
     */
    @JvmStatic
    fun userLayout(): LetterLayout? {
        val layout = ask(userLayoutSource, null) ?: return null
        val language = language(locale())
        val keypad = ask(keypadLayoutSource, null)
        // Almost every layout is for any keypad type and any layout set; only ask the device
        // profile and the subtype when the binding names one.
        val keypadType = if (layout.binding.keypadType == LetterLayout.ANY) null else ask(keypadTypeSource, null)
        val layoutSet = if (layout.binding.layoutSet == LetterLayout.ANY) null else ask(layoutSetSource, null)
        if (!layout.appliesTo(language, keypad, keypadType, layoutSet)) return null
        typingCopy?.let { (source, trimmed) -> if (source === layout) return trimmed }
        return layout.onlyKeys(LetterLayout::isCharacterKey).also { typingCopy = layout to it }
    }

    /** The keyboard the last built-in selection was made for, and what it selected. */
    private class BuiltInChoice(
        val docs: BuiltIns,
        val language: String,
        val keypad: String?,
        val keypadType: String?,
        val layoutSet: String?,
        val layout: LetterLayout?,
    )

    @Volatile
    private var lastBuiltIn: BuiltInChoice? = null

    /**
     * The built-in document for the current keyboard while the [PREF_KEY] switch is on, else
     * null. The registry caches its selection; the last one is kept here as well, because the
     * typing path asks several times per keystroke.
     */
    @JvmStatic
    fun builtInLayout(): LetterLayout? {
        if (!ask(alphabetSwitchSource, true)) return null
        return builtInFor(language(locale()) ?: return null)
    }

    private fun builtInFor(language: String): LetterLayout? {
        val docs = builtIns ?: return null
        val keypad = ask(keypadLayoutSource, null)
        val keypadType = ask(keypadTypeSource, null)
        val layoutSet = ask(layoutSetSource, null)
        lastBuiltIn?.let {
            if (it.docs === docs && it.language == language && it.keypad == keypad &&
                it.keypadType == keypadType && it.layoutSet == layoutSet
            ) return it.layout
        }
        val layout = try {
            docs.select(language, keypad, keypadType, layoutSet)
        } catch (e: Exception) {
            null
        }
        lastBuiltIn = BuiltInChoice(docs, language, keypad, keypadType, layoutSet, layout)
        return layout
    }

    /** A key one of the two tiers names for an event, and whether it came from a built-in document. */
    private class Resolved(val key: LetterLayout.Key, val builtIn: Boolean)

    /**
     * The key the stack names for [event], or null when neither tier does. Rule 1 (the currency
     * key) applies to both tiers; rule 2 (Latin letters stay the key map's) to the built-in one.
     */
    private fun keyFor(event: KeyEvent, currencyCharacter: () -> Int): Resolved? {
        val keyCode = event.keyCode
        val scanCode = event.scanCode
        userLayout()?.key(keyCode, scanCode)?.takeIf { appliesTo(it, currencyCharacter) }?.let {
            return Resolved(it, builtIn = false)
        }
        if (!LetterLayout.isCharacterKey(keyCode)) return null
        val key = builtInLayout()?.key(keyCode, scanCode) ?: return null
        if (!appliesTo(key, currencyCharacter)) return null
        if (Character.UnicodeScript.of(key.keySpec) == Character.UnicodeScript.LATIN) return null
        return Resolved(key, builtIn = true)
    }

    /** Rule 1: a `KEYCODE_4` entry without a scan code is the currency key's, nobody else's. */
    private fun appliesTo(key: LetterLayout.Key, currencyCharacter: () -> Int): Boolean =
        key.keyCode != KeyEvent.KEYCODE_4 || key.scanCode >= 0 ||
            Character.getType(currencyCharacter()) == Character.CURRENCY_SYMBOL.toInt()

    // ── letters ──────────────────────────────────────────────────────────────

    /**
     * The letter [event] types under the user's layout or the built-in document, or 0 when
     * neither names the key, a modifier other than Shift is active, the physical symbol page is
     * mapping the keys, or the event is not from a physical keyboard — every case in which the
     * caller should ask the system key map as before. [interpretedMeta] is the meta state the
     * caller interprets characters against.
     *
     * Shift gives a built-in key's upper case where its script has one, else the document's
     * Shift legend (`characterMapKeys`: ص→ض, the Hebrew finals, Korean doubled consonants, which
     * the generator also fills with the engine layout's second letter where the tables had none);
     * a user key gives its own legend, else the upper case.
     */
    @JvmStatic
    fun letter(event: KeyEvent, interpretedMeta: Int): Int {
        // Rule 1 reads the character the event itself carries, as the old extra-key rule did.
        val resolved = keyFor(event) { event.unicodeChar } ?: return 0
        if (!KeyEventDeviceClassifier.getInstance().isPhysicalKeyboardEvent(event)) return skipped("not a physical keyboard event")
        val switcher = KeyboardSwitcher.getInstance()
        if (switcher != null && switcher.isPhysicalSymbolMappingActive) return skipped("symbol page is mapping the keys")
        val modifiers = ModifierState.builder().event(event).interpretedMeta(interpretedMeta).build()
        if (modifiers.isAltActiveForCharacter || modifiers.isCtrlActive || modifiers.isSymActive) return skipped("Alt, Ctrl or Sym is active")
        if (event.metaState and (KeyEvent.META_META_ON or KeyEvent.META_FUNCTION_ON) != 0) return skipped("Meta or Fn is held")
        val shifted = modifiers.isShiftActive || modifiers.isShiftLockedForLayout ||
            (interpretedMeta and ModifierState.SHIFT_ANY_MASK) != 0
        val key = resolved.key
        val letter = if (resolved.builtIn) builtInLetter(key, shifted) else key.letter(shifted)
        if (InputPathDebug.on()) {
            Logger.info(
                TAG,
                "${if (resolved.builtIn) "built-in" else "user"} layout: keyCode=${event.keyCode} " +
                    "shifted=$shifted -> U+${Integer.toHexString(letter).uppercase()}",
            )
        }
        return letter
    }

    /** A built-in key's letter: with Shift, the upper case where one exists, else the legend. */
    private fun builtInLetter(key: LetterLayout.Key, shifted: Boolean): Int {
        val base = key.keySpec
        if (!shifted) return base
        val upper = Character.toUpperCase(base)
        if (upper != base) return upper
        return if (key.shift != 0) key.shift else base
    }

    /**
     * What holding a key down types when "hold for capital" is on: the letter Shift gives on that
     * key (Й, or ض on the ص key). 0 when no layout names the key, or Shift gives nothing
     * different (most Arabic keys carry one letter). The hold handlers used to read the system key
     * map here, so holding ص replaced it with a Latin "Q" (beta report, 2026-10).
     */
    @JvmStatic
    fun heldLetter(event: KeyEvent): Int {
        val base = letter(event, 0)
        if (base == 0) return 0
        val shifted = letter(event, KeyEvent.META_SHIFT_ON)
        return if (shifted != 0 && shifted != base) shifted else 0
    }

    private fun skipped(reason: String): Int {
        if (InputPathDebug.on()) Logger.info(TAG, "system key map: $reason")
        return 0
    }

    // ── Alt ──────────────────────────────────────────────────────────────────

    /**
     * The Alt character the layouts give the key [keyCode] (with [scanCode], -1 when unknown), or
     * 0 for "ask the device's Alt tables, then the key map". Without an event, rule 1 cannot be
     * checked: a `KEYCODE_4` entry is taken as the currency key's.
     */
    @JvmStatic
    fun alt(keyCode: Int, scanCode: Int): Int {
        userLayout()?.key(keyCode, scanCode)?.alt?.takeIf { it != 0 }?.let { return it }
        if (!LetterLayout.isCharacterKey(keyCode)) return 0
        return builtInLayout()?.key(keyCode, scanCode)?.alt ?: 0
    }

    /** [alt] for an event, with rule 1 checked against the character the key types without modifiers. */
    @JvmStatic
    fun alt(event: KeyEvent): Int {
        val currency = { event.getUnicodeChar(0) }
        userLayout()?.key(event.keyCode, event.scanCode)
            ?.takeIf { it.alt != 0 && appliesTo(it, currency) }?.let { return it.alt }
        if (!LetterLayout.isCharacterKey(event.keyCode)) return 0
        val key = builtInLayout()?.key(event.keyCode, event.scanCode) ?: return 0
        return if (appliesTo(key, currency)) key.alt else 0
    }

    /**
     * [alt] for an event, and only while Alt is what decides the character. The multitap
     * interpreter runs before `AuxCharacterResolver` and asks the system key map for the Alt
     * character itself; this is what makes the layout's Alt character the one it starts from.
     */
    @JvmStatic
    fun altFor(event: KeyEvent, interpretedMeta: Int): Int {
        val alt = alt(event)
        if (alt == 0) return 0
        if (!KeyEventDeviceClassifier.getInstance().isPhysicalKeyboardEvent(event)) return 0
        val modifiers = ModifierState.builder().event(event).interpretedMeta(interpretedMeta).build()
        return if (modifiers.isAltActiveForCharacter) alt else 0
    }

    // ── double tap, hold, Shift legend ───────────────────────────────────────

    /**
     * The double-tap sequence for a typed [letter]: the user layout's `multitapKeys` for the key
     * that typed it, else the built-in document's (the engine layout's doubled keycaps on a cased
     * script: й twice gives ё). Null when neither has one, which is also what tells the multitap
     * interpreter the key does not cycle.
     */
    @JvmStatic
    fun multitap(letter: String?): Array<String>? {
        if (!isOneCodePoint(letter)) return null
        val locale = locale()
        userLayout()?.let { layout -> listFor(layout, letter!!, locale) { it.multitap }?.let { return it } }
        val builtIn = builtInLayout() ?: return null
        return listFor(builtIn, letter!!, locale) { it.multitap }
    }

    /**
     * What holding the key that typed [letter] offers: the user layout's `moreKeys`, else the
     * built-in document's — joined, on a multi-language keyboard, with the same letter's list in
     * each extra language's document, as the old tables joined their locales' texts — else, for
     * a currency symbol, the currency key's list.
     */
    @JvmStatic
    fun moreKeys(letter: String?): Array<String>? {
        if (!isOneCodePoint(letter)) return null
        val label = letter!!
        val locale = locale()
        userLayout()?.let { layout -> listFor(layout, label, locale) { it.moreKeys }?.let { return it } }
        builtInMoreKeys(label, locale)?.let { return it }
        return currencyMoreKeys(label)
    }

    /**
     * The Shift legend the layouts give the key that types [letter] unshifted (`characterMapKeys`),
     * or null when they name none and Shift types the upper case. [letter] itself already answers
     * with it under Shift; this is for anything that has only the letter.
     */
    @JvmStatic
    fun shiftLegend(letter: String?): String? {
        if (!isOneCodePoint(letter)) return null
        val codePoint = letter!!.codePointAt(0)
        userLayout()?.keyForLetter(codePoint)?.shift?.takeIf { it != 0 }?.let { return LetterLayout.string(it) }
        return builtInLayout()?.keyForLetter(codePoint)?.shift?.takeIf { it != 0 }?.let { LetterLayout.string(it) }
    }

    /**
     * Every double-tap sequence the physical keys have right now, by the letter that starts it
     * (both cases): what [multitap] answers, all at once.
     */
    @JvmStatic
    fun multitapTable(): Map<String, Array<String>> {
        val locale = locale()
        val out = LinkedHashMap<String, Array<String>>()
        builtInLayout()?.let { putSequences(out, it, locale) }
        userLayout()?.let { putSequences(out, it, locale) }
        return out
    }

    private fun putSequences(out: MutableMap<String, Array<String>>, layout: LetterLayout, locale: Locale?) {
        val labels = LinkedHashSet<String>()
        for (key in layout.keys.values) {
            if (key.multitap.isEmpty()) continue
            val lower = LetterLayout.string(key.keySpec)
            labels.add(upperCase(lower, locale))
            labels.add(lower)
        }
        for (label in labels) listFor(layout, label, locale) { it.multitap }?.let { out[label] = it }
    }

    private fun builtInMoreKeys(label: String, locale: Locale?): Array<String>? {
        val primary = builtInLayout() ?: return null
        val (key, upper) = keyForLabel(primary, label, locale) ?: return null
        val lists = ArrayList<Array<String>>()
        lists.add(key.moreKeys)
        // A multi-language keyboard's lists join its extra languages' lists for the same letter,
        // duplicates once, in the order the keyboard lists its languages: what the old tables did
        // by resolving each list in every language's text table.
        val extras = ask(additionalLocalesSource, null)
        if (!extras.isNullOrEmpty()) {
            val primaryLanguage = language(locale)
            for (extra in extras) {
                val extraLanguage = language(extra) ?: continue
                if (extraLanguage == primaryLanguage) continue
                val document = builtInFor(extraLanguage) ?: continue
                document.keyForLetter(key.keySpec)?.let { lists.add(it.moreKeys) }
            }
        }
        val joined = if (lists.size == 1) lists[0] else LinkedHashSet<String>().apply { lists.forEach { addAll(it) } }.toTypedArray()
        return finish(joined, label, upper, locale)
    }

    /**
     * The key of [layout] that typed [label]: by its unshifted letter, else — when [label] is the
     * keyboard language's upper case of a key's letter — that key, with `true` for "upper-case
     * the lists". A Shift legend is a letter of its own (ض is not the capital of ص) and finds
     * nothing here, as in the old tables.
     */
    private fun keyForLabel(layout: LetterLayout, label: String, locale: Locale?): Pair<LetterLayout.Key, Boolean>? {
        layout.keyForLetter(label.codePointAt(0))?.let { return it to false }
        return upperIndex(layout, locale)[label]?.let { it to true }
    }

    private fun listFor(
        layout: LetterLayout,
        label: String,
        locale: Locale?,
        pick: (LetterLayout.Key) -> Array<String>,
    ): Array<String>? {
        val (key, upper) = keyForLabel(layout, label, locale) ?: return null
        return finish(pick(key), label, upper, locale)
    }

    /** Rule 3, then "nothing" for an empty list. Always a fresh array. */
    private fun finish(list: Array<String>, label: String, upper: Boolean, locale: Locale?): Array<String>? {
        if (list.isEmpty()) return null
        if (!upper) return list.copyOf()
        val out = ArrayList<String>(list.size)
        for (entry in list) {
            val upperEntry = StringHelper.toUpperCaseWithGreek(entry, true, locale ?: Locale.ROOT, true)
            if (upperEntry != label) out.add(upperEntry)
        }
        return if (out.isEmpty()) null else out.toTypedArray()
    }

    /** A layout's letters by their upper case in one locale, built once per layout and locale. */
    private class UpperIndex(val locale: Locale?, val keys: Map<String, LetterLayout.Key>)

    private val upperIndexes: MutableMap<LetterLayout, UpperIndex> = Collections.synchronizedMap(WeakHashMap())

    private fun upperIndex(layout: LetterLayout, locale: Locale?): Map<String, LetterLayout.Key> {
        upperIndexes[layout]?.let { if (it.locale == locale) return it.keys }
        val keys = HashMap<String, LetterLayout.Key>()
        for (key in layout.keys.values) {
            val lower = LetterLayout.string(key.keySpec)
            val upper = upperCase(lower, locale)
            if (upper != lower) keys.putIfAbsent(upper, key)
        }
        upperIndexes[layout] = UpperIndex(locale, keys)
        return keys
    }

    private fun upperCase(text: String, locale: Locale?): String = text.uppercase(locale ?: Locale.ROOT)

    private fun isOneCodePoint(text: String?): Boolean =
        !text.isNullOrEmpty() && Character.charCount(text.codePointAt(0)) == text.length

    // ── the currency key ─────────────────────────────────────────────────────

    /**
     * What holding a currency symbol offers: the other common currencies. The lists the old
     * physical-key tables gave the symbols their keyboards' currency keys type (`$` on the KEY2,
     * or what "Default currency" puts there), now the same on every keyboard.
     */
    private val CURRENCY_LISTS: Map<String, Array<String>> = mapOf(
        "$" to arrayOf("€", "¥", "£", "₱", "¢"),
        "£" to arrayOf("$", "€", "¥", "₱", "¢"),
        "€" to arrayOf("$", "¥", "£", "₱", "¢"),
        "₺" to arrayOf("$", "€", "¥", "£", "₱", "¢"),
        "₫" to arrayOf("$", "¥", "£", "₱", "¢"),
        "₽" to arrayOf("€", "¥", "£", "₱", "¢"),
        "₩" to arrayOf("€", "¥", "£", "₱", "¢"),
    )

    /** The currency symbols the currency key's list covers. */
    @JvmField
    val CURRENCY_SYMBOLS: Set<String> = CURRENCY_LISTS.keys

    private fun currencyMoreKeys(label: String): Array<String>? {
        val list = CURRENCY_LISTS[label] ?: return null
        return arrangeCurrencyList(label, list, ask(currencySettingSource, ""), ask(zeroKeySource, null))
    }

    /**
     * The currency key's list around the "Default currency" [setting], as the old table arranged
     * it: the setting's own symbol leaves the list (the key already types it), and a symbol typed
     * some other way leads its own list. A list that begins with the 0 key's character is left
     * alone unless the key types the setting's symbol.
     */
    @JvmStatic
    internal fun arrangeCurrencyList(label: String, list: Array<String>, setting: String, zeroKey: String?): Array<String> {
        if (setting.isEmpty() || list.isEmpty()) return list.copyOf()
        if (Character.getType(label.codePointAt(0)) != Character.CURRENCY_SYMBOL.toInt()) return list.copyOf()
        if (list[0] == zeroKey && label != setting) return list.copyOf()
        val out = ArrayList<String>(list.size + 1)
        if (label !in list && setting != label) out.add(label)
        for (entry in list) if (entry != setting) out.add(entry)
        return out.toTypedArray()
    }

    // ── what the rest of the app asks about the layouts ──────────────────────

    /**
     * True while a built-in document in a script of its own (Arabic, Cyrillic, Hebrew, Greek,
     * Hangul) drives the physical keys: what the old `HardwareScriptLayouts.isActive` answered.
     */
    @JvmStatic
    fun isScriptLayoutActive(): Boolean = builtInLayout()?.let { isScriptDocument(it) } ?: false

    private val scriptDocuments: MutableMap<LetterLayout, Boolean> = Collections.synchronizedMap(WeakHashMap())

    /** Whether any key of [layout] types a letter outside the Latin script. */
    private fun isScriptDocument(layout: LetterLayout): Boolean = scriptDocuments.getOrPut(layout) {
        layout.keys.values.any { key ->
            Character.isLetter(key.keySpec) && when (Character.UnicodeScript.of(key.keySpec)) {
                Character.UnicodeScript.LATIN, Character.UnicodeScript.COMMON, Character.UnicodeScript.INHERITED -> false
                else -> true
            }
        }
    }

    /**
     * Whether a keyboard locale (`ar`, `ru`, `iw_IL`, either spelling of Hebrew) has an alphabet
     * of its own on the physical keys: a built-in document for its language types a script letter.
     * The Language screen offers the [PREF_KEY] switch only when one of the enabled keyboards does.
     */
    @JvmStatic
    fun supportsLocale(locale: String?): Boolean {
        if (locale.isNullOrEmpty()) return false
        val parsed = LocaleUtils.constructLocaleFromString(locale) ?: return false
        val language = LocaleUtils.languageCode(parsed)
        val docs = builtIns ?: ImeApplication.getInstance()?.let { RegistryBuiltIns(LetterLayoutRegistry(it)) } ?: return false
        return docs.all().any { it.binding.isLanguageBound && it.binding.matchesLanguage(language) && isScriptDocument(it) }
    }
}

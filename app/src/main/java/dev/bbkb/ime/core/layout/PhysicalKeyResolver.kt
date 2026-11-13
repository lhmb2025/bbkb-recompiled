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
 * Answers every typing-path question about a physical key (letter, Shift, hold, double tap, more keys,
 * Alt) from one stack, first hit wins per key or list:
 *  1. the user's letter layout chosen for the language, when for the physical keys and bound here ([userLayout]);
 *  2. the built-in document (`assets/letter_layouts/`), unless the language chose [LetterLayoutChoice.KEYPAD_LETTERS];
 *  3. Alt only: the device Alt tables in `AuxCharacterResolver`;
 *  4. the firmware key map (0 or null here).
 * Rules: (1) a `KEYCODE_4` entry without `scanCode` applies only while the event's character is a
 * currency symbol (KEY2 `$` key; a Titan's 4 stays a digit). (2) A built-in Latin letter is left to the
 * key map (AZERTY/QWERTZ, dead keys); a user layout's letter always applies. (3) Upper-case lists are
 * upper-cased by the keyboard language's rules, minus entries equal to the letter. Sources are swappable for tests.
 */
object PhysicalKeyResolver {

    private const val TAG = "PhysicalKeys"

    /** The built-in documents; [init] hands it the registry's. */
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

    /** Points the sources at the running keyboard. */
    @JvmStatic
    fun init(context: Context) {
        val app = context.applicationContext
        chosenLayoutSource = { language -> LetterLayoutChoice.forTyping(app, language) }
        keypadLettersSource = { language -> LetterLayoutChoice.keypadLettersChosen(app, language) }
        builtIns = RegistryBuiltIns(LetterLayoutRegistry(app))
    }

    /** The layout chosen for a keyboard language ([LetterLayoutChoice]), whatever it is for. */
    @Volatile
    internal var chosenLayoutSource: (language: String?) -> LetterLayout? = { null }

    /** Whether a language chose the keypad's own letters, which leaves its built-in document out. */
    @Volatile
    internal var keypadLettersSource: (language: String?) -> Boolean = { false }

    /** Null (no built-in tier) until [init]. [supportsLocale] reaches the app's documents without it. */
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
        private val chosen = chosenLayoutSource
        private val keypadLetters = keypadLettersSource
        private val docs = builtIns
        private val locale = localeSource
        private val additional = additionalLocalesSource
        private val keypad = keypadLayoutSource
        private val keypadType = keypadTypeSource
        private val layoutSet = layoutSetSource
        private val currency = currencySettingSource
        private val zero = zeroKeySource

        fun restore() {
            chosenLayoutSource = chosen
            keypadLettersSource = keypadLetters
            builtIns = docs
            localeSource = locale
            additionalLocalesSource = additional
            keypadLayoutSource = keypad
            keypadTypeSource = keypadType
            layoutSetSource = layoutSet
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

    /** The last layout [userLayout] trimmed to the character keys, and the trimmed copy. */
    @Volatile
    private var typingCopy: Pair<LetterLayout, LetterLayout>? = null

    /**
     * The chosen layout's character keys when it drives the physical keys now (for `pkb` and bound to
     * this language, layout set and keypad), else null. Built-in documents chosen here count as user layouts.
     */
    @JvmStatic
    fun userLayout(): LetterLayout? {
        val language = language(locale())
        val keypad = ask(keypadLayoutSource, null)
        val layout = ask({ chosenLayoutSource(language) }, null)
            ?.takeIf { LetterLayout.Target.PKB in it.targets && fits(it, language, keypad) } ?: return null
        typingCopy?.let { (source, trimmed) -> if (source === layout) return trimmed }
        return layout.onlyKeys(LetterLayout::isCharacterKey).also { typingCopy = layout to it }
    }

    /** Whether [layout]'s binding fits the current keyboard in [language] on a [keypad] keypad. */
    private fun fits(layout: LetterLayout, language: String?, keypad: String?): Boolean {
        // Only ask the profile and subtype when the binding names a value.
        val keypadType = if (layout.binding.keypadType == LetterLayout.ANY) null else ask(keypadTypeSource, null)
        val layoutSet = if (layout.binding.layoutSet == LetterLayout.ANY) null else ask(layoutSetSource, null)
        return layout.appliesTo(language, keypad, keypadType, layoutSet)
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
     * The current keyboard's built-in document, null if its language chose the keypad's letters.
     * The last selection is cached here because the typing path asks several times per keystroke.
     */
    @JvmStatic
    fun builtInLayout(): LetterLayout? {
        val language = language(locale()) ?: return null
        if (ask({ keypadLettersSource(language) }, false)) return null
        return builtInFor(language)
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

    /** The key the stack names for [event], or null; rule 1 for both tiers, rule 2 for the built-in. */
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

    /**
     * The letter [event] types from the layouts, or 0 to ask the key map: unnamed key, a modifier other than
     * Shift, the symbol page mapping keys, or not physical. With Shift, a built-in key gives its upper case
     * else its Shift legend (ص→ض); a user key gives its legend else the upper case.
     */
    @JvmStatic
    fun letter(event: KeyEvent, interpretedMeta: Int): Int {
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
     * The letter Shift gives on the key, for "hold for capital" (Й, or ض on ص); 0 when unnamed or Shift
     * gives nothing different. Not the key map: holding ص must not give a Latin "Q".
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

    /**
     * The layouts' Alt character for [keyCode] ([scanCode] -1 if unknown), or 0 for the device tables then the
     * key map. Without an event, rule 1 cannot be checked: a `KEYCODE_4` entry counts as the currency key's.
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
     * [alt], only while Alt decides the character: the multitap interpreter runs before
     * `AuxCharacterResolver`, so it uses this to start from the layout's Alt character.
     */
    @JvmStatic
    fun altFor(event: KeyEvent, interpretedMeta: Int): Int {
        val alt = alt(event)
        if (alt == 0) return 0
        if (!KeyEventDeviceClassifier.getInstance().isPhysicalKeyboardEvent(event)) return 0
        val modifiers = ModifierState.builder().event(event).interpretedMeta(interpretedMeta).build()
        return if (modifiers.isAltActiveForCharacter) alt else 0
    }

    /**
     * The double-tap sequence for [letter] (user `multitapKeys`, else built-in, e.g. й twice gives ё).
     * Null tells the multitap interpreter the key does not cycle.
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
     * Hold list for [letter]: user `moreKeys`, else built-in joined with each extra language's list on a
     * multi-language keyboard, else the currency list for a currency symbol.
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

    /** The Shift legend (`characterMapKeys`) of the key typing [letter] unshifted, or null for plain upper case. */
    @JvmStatic
    fun shiftLegend(letter: String?): String? {
        if (!isOneCodePoint(letter)) return null
        val codePoint = letter!!.codePointAt(0)
        userLayout()?.keyForLetter(codePoint)?.shift?.takeIf { it != 0 }?.let { return LetterLayout.string(it) }
        return builtInLayout()?.keyForLetter(codePoint)?.shift?.takeIf { it != 0 }?.let { LetterLayout.string(it) }
    }

    /** Every current double-tap sequence by starting letter (both cases). */
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
        // Extra languages' lists for the same letter join in keyboard order, duplicates once.
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
     * The key that typed [label]: by unshifted letter, else by the language's upper case (`true`: upper-case
     * the lists). A Shift legend is its own letter (ض is not the capital of ص) and finds nothing.
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

    /** Hold lists of the currency symbols, the same on every keyboard. */
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
     * The currency list around the "Default currency" [setting]: the setting's symbol leaves the list, a
     * symbol typed otherwise leads it. A list starting with the 0 key's character stays unless it is the setting.
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

    /** True while a built-in document in a non-Latin script drives the physical keys. */
    @JvmStatic
    fun isScriptLayoutActive(): Boolean = builtInLayout()?.let { isScriptDocument(it) } ?: false

    private val scriptDocuments: MutableMap<LetterLayout, Boolean> = Collections.synchronizedMap(WeakHashMap())

    /** Whether any key of [layout] types a letter outside the Latin script. */
    @JvmStatic
    internal fun isScriptDocument(layout: LetterLayout): Boolean = scriptDocuments.getOrPut(layout) {
        layout.keys.values.any { key ->
            Character.isLetter(key.keySpec) && when (Character.UnicodeScript.of(key.keySpec)) {
                Character.UnicodeScript.LATIN, Character.UnicodeScript.COMMON, Character.UnicodeScript.INHERITED -> false
                else -> true
            }
        }
    }

    /**
     * Whether a keyboard locale has its own alphabet on the physical keys (a built-in document types a
     * script letter); the Language screen offers the [PREF_KEY] switch only then.
     */
    @JvmStatic
    fun supportsLocale(locale: String?): Boolean {
        if (locale.isNullOrEmpty()) return false
        val parsed = LocaleUtils.constructLocaleFromString(locale) ?: return false
        val language = LocaleUtils.languageCode(parsed)
        val docs = builtIns ?: ImeApplication.getInstance()?.let { RegistryBuiltIns(LetterLayoutRegistry(it)) } ?: return false
        // An opt-in document (an etched keypad's layout) is a choice, not the language's alphabet.
        return docs.all().any { !it.optIn && it.binding.isLanguageBound && it.binding.matchesLanguage(language) && isScriptDocument(it) }
    }
}

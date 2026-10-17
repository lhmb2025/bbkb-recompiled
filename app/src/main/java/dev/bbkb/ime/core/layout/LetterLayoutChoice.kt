package dev.bbkb.ime.core.layout

import android.content.Context
import android.content.SharedPreferences
import android.view.inputmethod.InputMethodSubtype
import dev.bbkb.ime.R
import dev.bbkb.ime.core.layout.LetterLayout.Target
import dev.bbkb.ime.core.locale.LocaleUtils
import dev.bbkb.ime.core.locale.ResourceLocaleUtils
import dev.bbkb.ime.core.locale.RichInputMethodManager
import dev.bbkb.ime.core.settings.PrefsManager
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The letter layouts each keyboard language uses: the "On-screen keyboard" and "Physical keyboard"
 * rows of a keyboard on the Language screen. Two preferences per language, one per keyboard
 * ([prefKey]: [PREF_PREFIX]`vkb_<language>` and [PREF_PREFIX]`pkb_<language>`, the language as
 * [LocaleUtils.languageCode] spells it: `en`, `iw`), each holding a layout id; empty or absent
 * means the keyboard's default. Every keyboard of that language shares the choices.
 *
 *  - **On screen** ([Target.VKB]), the keyboard is built from the layout set named after the
 *    chosen layout ([keyboardLayoutSet]) when the layout is one the app ships for the on-screen
 *    keyboard and a `keyboard_layout_set_<id>.xml` exists for it: QWERTY, AZERTY, QWERTZ, Colemak
 *    and Dvorak. A custom document cannot draw the on-screen keyboard yet, because its rows would
 *    have to be built while the app runs, which is a later step.
 *  - **On the physical keys** ([Target.PKB]; [PhysicalKeyResolver.userLayout]) and in the engine's
 *    KDBs ([LetterLayoutEngineSync]), the chosen layout drives the keys when it is for the
 *    physical keyboard and its binding fits this keyboard and keypad. The choice may instead be
 *    [KEYPAD_LETTERS]: the keys type what the keypad's own key map says (Latin on a Latin keypad),
 *    and the language's own alphabet document is not applied.
 *
 * ## Precedence on the physical keys
 *
 * 1. the per-language physical choice, when it names a layout that drives the physical keys;
 * 2. else, unless the choice is [KEYPAD_LETTERS], the built-in document for the keyboard's
 *    language, keypad and layout set (never one marked opt-in: those are offered here and
 *    applied only when chosen);
 * 3. else the firmware key map.
 *
 * A layout chosen here is followed with the user tier's rules even when it is a built-in document:
 * its letters always apply, where a language's own built-in document leaves Latin letters to the
 * firmware key map.
 *
 * ## What came before
 *
 * [migrate] carries three older settings into these choices, once per process: one preference
 * per language ([PREF_PREFIX]`<language>`) that applied to whichever keyboards its layout was for;
 * the one custom layout switched on for every language in Physical keyboard > Physical layouts
 * ([LetterLayoutRegistry.PREF_ACTIVE_LAYOUT]), which becomes the physical choice of the languages
 * it is bound to; and the "Use active language's alphabet for physical keyboard" switch
 * ([LEGACY_ALPHABET_PREF]), which, when it was off, becomes [KEYPAD_LETTERS] for every enabled
 * language that has an alphabet document.
 *
 * An id names the user's layout of that id (`files/layouts/letter/<id>.xml`) first, else the
 * built-in one: a built-in saved as a file and imported again, perhaps edited, stands in for it.
 */
object LetterLayoutChoice {

    /** Followed by the keyboard and the language: `pref_letter_layout_vkb_en`, `pref_letter_layout_pkb_en`. */
    const val PREF_PREFIX: String = "pref_letter_layout_"

    /** The preference that holds [language]'s choice for [target]. */
    @JvmStatic
    fun prefKey(language: String, target: Target): String = PREF_PREFIX + target.token + "_" + language

    /** The one preference a language had before the choices were separate. */
    private fun legacyPrefKey(language: String): String = PREF_PREFIX + language

    /**
     * The physical choice that says the keys type the keypad's own letters: no layout, and not the
     * language's alphabet document either. Not a layout id (an id cannot contain `:`).
     */
    const val KEYPAD_LETTERS: String = ":keypad"

    /** "Use active language's alphabet for physical keyboard", the switch [migrate] retires. */
    const val LEGACY_ALPHABET_PREF: String = "pref_pkb_active_language_alphabet"

    /** Whether a changed preference [key] is one of these choices (a legacy one included). */
    @JvmStatic
    fun isPrefKey(key: String?): Boolean = key != null && key.length > PREF_PREFIX.length && key.startsWith(PREF_PREFIX)

    /** The language a keyboard in [locale] keeps its choices under, or null. */
    @JvmStatic
    fun languageOf(locale: Locale?): String? = locale?.let { LocaleUtils.languageCode(it) }?.takeIf { it.isNotEmpty() }

    /** The language a [subtype]'s keyboard keeps its choices under, or null. */
    @JvmStatic
    fun languageOf(subtype: InputMethodSubtype?): String? =
        subtype?.let { languageOf(ResourceLocaleUtils.getSubtypeLocale(it)) }

    /** The id chosen for [language]'s [target] keyboard, or `""` for its default. */
    @JvmStatic
    fun chosenId(prefs: SharedPreferences, language: String?, target: Target): String =
        if (language.isNullOrEmpty()) "" else prefs.getString(prefKey(language, target), "") ?: ""

    /**
     * Chooses [id] for [language]'s [target] keyboard (`""`: back to its default). The typing path
     * and the engine pick it up on the next key; the keyboard's preference listener rebuilds the
     * on-screen keyboard.
     */
    @JvmStatic
    fun choose(context: Context, language: String, target: Target, id: String) {
        val editor = PrefsManager.getPrefs(context).edit()
        val key = prefKey(language, target)
        if (id.isEmpty()) editor.remove(key) else editor.putString(key, id)
        editor.apply()
        LetterLayoutRegistry.invalidate()
    }

    /**
     * Carries the older settings into the per-keyboard choices, once per process, from the first
     * read of the choices (see the class doc):
     *
     *  - a legacy single choice ([PREF_PREFIX]`<language>`) becomes the on-screen choice when its
     *    layout draws the on-screen keyboard and the physical one when it is for the physical
     *    keys; one whose layout is gone is dropped;
     *  - the custom layout switched on for every language becomes the physical choice of each
     *    enabled language its binding names (every enabled language, for one bound to none) that
     *    has no physical choice yet;
     *  - the alphabet switch, when off, becomes [KEYPAD_LETTERS] for every enabled language whose
     *    document types another script (Arabic, Cyrillic, …) and that has no physical choice yet.
     *
     * The enabled languages are [enabledLanguages]'s; the tests replace it.
     */
    @JvmStatic
    fun migrate(context: Context) {
        if (migrated) return
        migrated = true
        val prefs = PrefsManager.getPrefs(context)
        val legacy = prefs.all.keys.filter { key ->
            isPrefKey(key) && Target.entries.none { key.startsWith(PREF_PREFIX + it.token + "_") }
        }
        LetterLayoutRegistry.migrateActivePreference(prefs)
        val active = prefs.getString(LetterLayoutRegistry.PREF_ACTIVE_LAYOUT, "") ?: ""
        val alphabetOff = prefs.contains(LEGACY_ALPHABET_PREF) && !prefs.getBoolean(LEGACY_ALPHABET_PREF, true)
        if (legacy.isEmpty() && active.isEmpty() && !prefs.contains(LetterLayoutRegistry.PREF_ACTIVE_LAYOUT) &&
            !prefs.contains(LEGACY_ALPHABET_PREF)
        ) return
        val registry = LetterLayoutRegistry(context)
        val editor = prefs.edit()
        val physical = HashMap<String, String>()
        for (key in legacy) {
            val language = key.removePrefix(PREF_PREFIX)
            val id = prefs.getString(key, "") ?: ""
            val layout = resolve(registry, id)
            if (layout != null) {
                if (drawsOnScreen(context, layout)) editor.putString(prefKey(language, Target.VKB), id)
                if (Target.PKB in layout.targets) physical[language] = id
            }
            editor.remove(key)
        }
        fun hasPhysicalChoice(language: String) =
            language in physical || prefs.contains(prefKey(language, Target.PKB))
        val languages = try { enabledLanguages(context) } catch (e: Exception) { emptyList() }
        if (active.isNotEmpty()) {
            val layout = registry.get(active)
            if (layout != null && Target.PKB in layout.targets) {
                for (language in languages) {
                    if (!hasPhysicalChoice(language) && layout.binding.matchesLanguage(language)) physical[language] = active
                }
            }
        }
        editor.remove(LetterLayoutRegistry.PREF_ACTIVE_LAYOUT)
        if (alphabetOff) {
            // Only a language with a document that types another script: a Latin document leaves
            // its letters to the key map anyway, so the switch never changed it.
            val scriptDocuments = registry.builtInIds().mapNotNull { registry.builtIn(it) }
                .filter { !it.optIn && it.binding.isLanguageBound && PhysicalKeyResolver.isScriptDocument(it) }
            for (language in languages) {
                if (!hasPhysicalChoice(language) && scriptDocuments.any { it.binding.matchesLanguage(language) }) {
                    physical[language] = KEYPAD_LETTERS
                }
            }
        }
        editor.remove(LEGACY_ALPHABET_PREF)
        physical.forEach { (language, id) -> editor.putString(prefKey(language, Target.PKB), id) }
        editor.apply()
        LetterLayoutRegistry.invalidate()
    }

    /** The languages of the keyboards the user has enabled, in the app's spelling; the tests replace it. */
    @JvmStatic
    @Volatile
    internal var enabledLanguages: (Context) -> List<String> = { _ ->
        RichInputMethodManager.getInstance()?.enabledSubtypesOfThisIme.orEmpty()
            .mapNotNull { languageOf(it) }
            .distinct()
    }

    /**
     * Whether [language]'s physical choice is [KEYPAD_LETTERS]: the keys type the keypad's own
     * letters, and the language's alphabet document is not applied.
     */
    @JvmStatic
    fun keypadLettersChosen(context: Context, language: String?): Boolean {
        if (language.isNullOrEmpty()) return false
        migrate(context)
        return chosenId(PrefsManager.getPrefs(context), language, Target.PKB) == KEYPAD_LETTERS
    }

    @Volatile
    private var migrated = false

    /** Forgets that [migrate] ran. Tests only. */
    @JvmStatic
    internal fun resetMigrationForTest() {
        migrated = false
    }

    /** The layout [id] names: the user's of that id, else the built-in one; null for `""` or none. */
    @JvmStatic
    fun resolve(registry: LetterLayoutRegistry, id: String): LetterLayout? {
        if (id.isEmpty()) return null
        return registry.get(id) ?: registry.builtIn(id)
    }

    // ── for the typing path ──────────────────────────────────────────────────

    /** A choice's id when last read, and the layout it named (null for none or unreadable). */
    private class Snapshot(val id: String, val layout: LetterLayout?)

    private val snapshots = ConcurrentHashMap<String, Snapshot>()

    /**
     * The layout chosen for [language]'s [target] keyboard, parsed once per change, so a keystroke
     * never reads a file: like [LetterLayoutRegistry.activeForTyping], the copy is keyed by the id
     * read from the preferences on each call, and [LetterLayoutRegistry.invalidate] drops it.
     */
    @JvmStatic
    fun chosen(context: Context, language: String?, target: Target): LetterLayout? {
        if (language.isNullOrEmpty()) return null
        migrate(context)
        val id = chosenId(PrefsManager.getPrefs(context), language, target)
        val key = target.token + ":" + language
        snapshots[key]?.let { if (it.id == id) return it.layout }
        val layout = if (id.isEmpty() || id == KEYPAD_LETTERS) null else resolve(LetterLayoutRegistry(context), id)
        snapshots[key] = Snapshot(id, layout)
        return layout
    }

    /** The layout chosen for [language]'s physical keys ([chosen] for [Target.PKB]). */
    @JvmStatic
    fun forTyping(context: Context, language: String?): LetterLayout? = chosen(context, language, Target.PKB)

    /** Forgets the typing path's copies. Called by [LetterLayoutRegistry.invalidate]. */
    @JvmStatic
    fun invalidate() {
        snapshots.clear()
    }

    // ── the on-screen keyboard ───────────────────────────────────────────────

    /**
     * The layout set the on-screen keyboard for [subtype] is built from: the id of the layout
     * chosen for its language's on-screen keyboard when that layout draws it ([drawsOnScreen]),
     * else the subtype's own ([ResourceLocaleUtils.getKeyboardLayoutSetName]).
     */
    @JvmStatic
    fun keyboardLayoutSet(context: Context, subtype: InputMethodSubtype): String =
        onScreenLayout(context, languageOf(subtype))?.id ?: ResourceLocaleUtils.getKeyboardLayoutSetName(subtype)

    /** The layout the on-screen keyboard for [language] is drawn from, or null for its own. */
    @JvmStatic
    fun onScreenLayout(context: Context, language: String?): LetterLayout? =
        chosen(context, language, Target.VKB)?.takeIf { drawsOnScreen(context, it) }

    /**
     * Whether [layout] can draw the on-screen keyboard: a built-in document for the on-screen
     * keyboard, with a rows template, whose rows the app ships as `keyboard_layout_set_<id>.xml`
     * (generated from the document by `letter_layouts_gen.py`, or the app's own rows for QWERTY,
     * AZERTY and QWERTZ, which the generator checks against the document). A user's file of the
     * same id is a different document and does not: the shipped rows would not be its letters.
     */
    @JvmStatic
    fun drawsOnScreen(context: Context, layout: LetterLayout): Boolean =
        layout.origin == LetterLayout.Origin.BUILT_IN &&
            Target.VKB in layout.targets &&
            layout.rowsTemplate != null &&
            hasLayoutSet(context, layout.id)

    private val layoutSets = ConcurrentHashMap<String, Boolean>()
    private val RESOURCE_NAME = Regex("[a-z0-9_]+")

    /** Whether the app ships `res/xml/keyboard_layout_set_<id>.xml`. */
    @JvmStatic
    fun hasLayoutSet(context: Context, id: String): Boolean = layoutSets.getOrPut(id) {
        if (!RESOURCE_NAME.matches(id)) return@getOrPut false
        val resources = context.resources
        // The package the layout sets are compiled under, as KeyboardBuilder finds them: the
        // installed package name differs between build types.
        val packageName = resources.getResourcePackageName(R.xml.keyboard_layout_set_qwerty)
        resources.getIdentifier("keyboard_layout_set_$id", "xml", packageName) != 0
    }

    // ── what the Language screen offers ──────────────────────────────────────

    /**
     * One entry of a keyboard's layout list: [layout] null is the keyboard's default (id `""`) or,
     * on the physical keys, the keypad's own letters ([KEYPAD_LETTERS]).
     */
    class Option(val id: String, val layout: LetterLayout?)

    /**
     * What a keyboard in [language] may choose for its [target] keyboard, in order. Each group by
     * name (case-insensitive, ties by id). A user's layout with a built-in's id replaces the
     * built-in, as [resolve] does.
     *
     * For the on-screen keyboard: its default first, then the shipped layouts bound to no language
     * that draw it ([drawsOnScreen]), on a keyboard written in the Latin alphabet ([latin]), less
     * the one that is the keyboard's own layout set ([ownLayoutSet]: a French keyboard's default
     * is AZERTY already).
     *
     * For the physical keys, every row is explicit: the keypad's own letters ([KEYPAD_LETTERS])
     * first; then the language's own alphabet document ([languageDocument], the one the registry
     * selects for the keyboard). A document that types Latin letters (English's, French's) leaves
     * them to the keypad's key map and only adds lists, so on its keyboard it stands in for the
     * keypad's letters and that row is left out. Then the shipped layouts for the
     * physical keys that are bound to no language (on a Latin keyboard) or marked opt-in for this
     * language and the keyboard's layout set ([LetterLayout.optIn]: the layouts of the etched
     * keypad editions); then the user's layouts for the physical keys whose binding names
     * [language] or no language. The default (choice `""`) is the alphabet document when there is
     * one, else the keypad's letters ([defaultPhysicalId]).
     */
    @JvmStatic
    fun options(
        builtIns: List<LetterLayout>,
        custom: List<LetterLayout>,
        language: String,
        target: Target,
        latin: Boolean,
        drawsOnScreen: (LetterLayout) -> Boolean,
        ownLayoutSet: String? = null,
        languageDocument: LetterLayout? = null,
    ): List<Option> {
        val customIds = custom.map { it.id }.toSet()
        val byName = compareBy<LetterLayout, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.id }
        val shipped = builtIns.filter { it.id !in customIds && it.id != languageDocument?.id }.filter { layout ->
            when (target) {
                Target.VKB -> latin && !layout.binding.isLanguageBound && layout.id != ownLayoutSet && drawsOnScreen(layout)
                Target.PKB -> Target.PKB in layout.targets && when {
                    layout.optIn -> layout.binding.matchesLanguage(language) && layout.binding.layoutSetMatches(ownLayoutSet)
                    else -> latin && !layout.binding.isLanguageBound
                }
            }
        }.sortedWith(byName)
        val own = if (target != Target.PKB) emptyList() else custom
            .filter { Target.PKB in it.targets && it.binding.matchesLanguage(language) }
            .sortedWith(byName)
        val first = when (target) {
            Target.VKB -> listOf(Option("", null))
            Target.PKB -> {
                val latinDocument = languageDocument != null && !PhysicalKeyResolver.isScriptDocument(languageDocument)
                (if (latinDocument) emptyList() else listOf(Option(KEYPAD_LETTERS, null))) +
                    listOfNotNull(languageDocument?.let { Option(it.id, it) })
            }
        }
        return first + (shipped + own).map { Option(it.id, it) }
    }

    /** The row the physical list shows chosen for choice `""`: the alphabet document's, else the keypad's letters. */
    @JvmStatic
    fun defaultPhysicalId(languageDocument: LetterLayout?): String = languageDocument?.id ?: KEYPAD_LETTERS
}

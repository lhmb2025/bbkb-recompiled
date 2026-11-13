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
 * Per-language letter layout choices, one preference per keyboard ([prefKey], language as
 * [LocaleUtils.languageCode] spells it); empty means the default. On screen ([Target.VKB]) only
 * built-in layouts with a shipped layout set can draw ([drawsOnScreen]). An id resolves to the
 * user's layout first, else the built-in. Physical-key precedence:
 * 1. the physical choice, when it names a layout that drives the physical keys (letters always apply);
 * 2. else, unless [KEYPAD_LETTERS], the language's built-in document (never an opt-in one);
 * 3. else the firmware key map.
 */
object LetterLayoutChoice {
    /** As in `pref_letter_layout_vkb_en`, `pref_letter_layout_pkb_en`. */
    const val PREF_PREFIX: String = "pref_letter_layout_"

    @JvmStatic
    fun prefKey(language: String, target: Target): String = PREF_PREFIX + target.token + "_" + language

    /** The one preference a language had before the choices were separate. */
    private fun legacyPrefKey(language: String): String = PREF_PREFIX + language

    /** Physical choice: the keypad's own letters, not even the alphabet document. Cannot clash with an id (no `:`). */
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

    /** Chooses [id] (`""` for the default); takes effect on the next key, the preference listener rebuilds the on-screen keyboard. */
    @JvmStatic
    fun choose(context: Context, language: String, target: Target, id: String) {
        val editor = PrefsManager.getPrefs(context).edit()
        val key = prefKey(language, target)
        if (id.isEmpty()) editor.remove(key) else editor.putString(key, id)
        editor.apply()
        LetterLayoutRegistry.invalidate()
    }

    /**
     * Once per process, carries retired settings into the per-keyboard choices: a single
     * [PREF_PREFIX]`<language>` choice splits by target; [LetterLayoutRegistry.PREF_ACTIVE_LAYOUT]
     * becomes the physical choice of each enabled language it binds; [LEGACY_ALPHABET_PREF] off
     * becomes [KEYPAD_LETTERS] for enabled languages with a non-Latin document. Existing choices win.
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
            // Only non-Latin documents: a Latin one leaves letters to the key map, so the switch never changed it.
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

    /** Whether [language]'s physical choice is [KEYPAD_LETTERS]. */
    @JvmStatic
    fun keypadLettersChosen(context: Context, language: String?): Boolean {
        if (language.isNullOrEmpty()) return false
        migrate(context)
        return typingChosenId(context, language, Target.PKB) == KEYPAD_LETTERS
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

    /** A choice's id when last read, and the layout it named (null for none or unreadable). */
    private class Snapshot(val id: String, val layout: LetterLayout?)

    /** By target, then language, so no key string is built per keystroke. */
    private val snapshots = Array(Target.entries.size) { ConcurrentHashMap<String, Snapshot>() }

    /**
     * Cached chosen ids by target, then language: a letter key asks several times (resolver, hold,
     * multitap). Cleared by [prefsWatcher] and [invalidate].
     */
    private val chosenIds = Array(Target.entries.size) { ConcurrentHashMap<String, String>() }
    private val chosenIdsLock = Any()
    private var chosenIdsGeneration = 0

    /** The preferences [prefsWatcher] is registered on (they hold listeners weakly, so we keep a reference). */
    @Volatile
    private var watchedPrefs: SharedPreferences? = null

    private val prefsWatcher = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // null key: clear(), everything changed.
        if (key == null || isPrefKey(key)) forgetChosenIds()
    }

    private fun forgetChosenIds() = synchronized(chosenIdsLock) {
        chosenIdsGeneration++
        chosenIds.forEach { it.clear() }
    }

    /** [chosenId] for the typing path: from the cache, else read once and kept until a change. */
    private fun typingChosenId(context: Context, language: String, target: Target): String {
        val prefs = PrefsManager.getPrefs(context)
        if (prefs !== watchedPrefs) {
            synchronized(chosenIdsLock) {
                if (prefs !== watchedPrefs) {
                    watchedPrefs?.unregisterOnSharedPreferenceChangeListener(prefsWatcher)
                    prefs.registerOnSharedPreferenceChangeListener(prefsWatcher)
                    watchedPrefs = prefs
                    chosenIdsGeneration++
                    chosenIds.forEach { it.clear() }
                }
            }
        }
        val byLanguage = chosenIds[target.ordinal]
        byLanguage[language]?.let { return it }
        val generation = synchronized(chosenIdsLock) { chosenIdsGeneration }
        val id = chosenId(prefs, language, target)
        // Store only if no change happened during the read, or a stale value outlives the clear.
        synchronized(chosenIdsLock) {
            if (generation == chosenIdsGeneration) byLanguage[language] = id
        }
        return id
    }

    /** The chosen layout, parsed once per change and keyed by chosen id, so a keystroke reads no file or preference. */
    @JvmStatic
    fun chosen(context: Context, language: String?, target: Target): LetterLayout? {
        if (language.isNullOrEmpty()) return null
        migrate(context)
        val id = typingChosenId(context, language, target)
        val byLanguage = snapshots[target.ordinal]
        byLanguage[language]?.let { if (it.id == id) return it.layout }
        val layout = if (id.isEmpty() || id == KEYPAD_LETTERS) null else resolve(LetterLayoutRegistry(context), id)
        byLanguage[language] = Snapshot(id, layout)
        return layout
    }

    @JvmStatic
    fun forTyping(context: Context, language: String?): LetterLayout? = chosen(context, language, Target.PKB)

    @JvmStatic
    fun invalidate() {
        snapshots.forEach { it.clear() }
        forgetChosenIds()
    }

    /** The on-screen layout set for [subtype]: the chosen layout's id when it [drawsOnScreen], else the subtype's own. */
    @JvmStatic
    fun keyboardLayoutSet(context: Context, subtype: InputMethodSubtype): String =
        onScreenLayout(context, languageOf(subtype))?.id ?: ResourceLocaleUtils.getKeyboardLayoutSetName(subtype)

    /** The layout the on-screen keyboard for [language] is drawn from, or null for its own. */
    @JvmStatic
    fun onScreenLayout(context: Context, language: String?): LetterLayout? =
        chosen(context, language, Target.VKB)?.takeIf { drawsOnScreen(context, it) }

    /**
     * Whether [layout] can draw the on-screen keyboard: a built-in VKB document with a rows template
     * and a shipped `keyboard_layout_set_<id>.xml` (from `letter_layouts_gen.py`). A user file with
     * the same id does not: the shipped rows would not be its letters.
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
        // Resource package, not the installed package name, which differs between build types.
        val packageName = resources.getResourcePackageName(R.xml.keyboard_layout_set_qwerty)
        resources.getIdentifier("keyboard_layout_set_$id", "xml", packageName) != 0
    }

    /** One entry of a layout list; [layout] null is the default (`""`) or [KEYPAD_LETTERS]. */
    class Option(val id: String, val layout: LetterLayout?)

    /**
     * The Language screen's choices for [language]'s [target] keyboard, groups sorted by name; a user
     * layout with a built-in's id replaces it.
     * VKB: default, then unbound shipped layouts that draw (Latin keyboards only), minus [ownLayoutSet].
     * PKB: [KEYPAD_LETTERS] (omitted when [languageDocument] is Latin, since it stands in for it), the
     * language document, unbound shipped layouts (Latin only) or matching opt-ins, then user layouts.
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

    /** The physical row shown as chosen for `""`: the alphabet document, else [KEYPAD_LETTERS]. */
    @JvmStatic
    fun defaultPhysicalId(languageDocument: LetterLayout?): String = languageDocument?.id ?: KEYPAD_LETTERS
}

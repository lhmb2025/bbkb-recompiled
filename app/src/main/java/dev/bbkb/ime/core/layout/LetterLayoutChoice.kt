package dev.bbkb.ime.core.layout

import android.content.Context
import android.content.SharedPreferences
import android.view.inputmethod.InputMethodSubtype
import dev.bbkb.ime.R
import dev.bbkb.ime.core.locale.LocaleUtils
import dev.bbkb.ime.core.locale.ResourceLocaleUtils
import dev.bbkb.ime.core.settings.PrefsManager
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The letter layout each keyboard language uses: the "Layout" row of a keyboard on the Language
 * screen. One preference per language, [PREF_PREFIX]`<language>` (the language as
 * [LocaleUtils.languageCode] spells it: `en`, `iw`), holding a layout id; empty or absent means the
 * keyboard's own layout. Every keyboard of that language shares the choice.
 *
 * A chosen layout applies to the keyboards it is for:
 *
 *  - **On screen**, the keyboard is built from the layout set named after the layout
 *    ([keyboardLayoutSet]) when the layout is one the app ships for the on-screen keyboard and a
 *    `keyboard_layout_set_<id>.xml` exists for it: QWERTY, AZERTY, QWERTZ, Colemak and Dvorak. A
 *    custom document keeps the
 *    keyboard's own on-screen layout for now, because its rows would have to be built while the
 *    app runs, which is a later step.
 *  - **On the physical keys** ([PhysicalKeyResolver.userLayout]) and in the engine's KDBs
 *    ([LetterLayoutEngineSync]), the layout drives the keys when it is for the physical keyboard
 *    (its targets include `pkb`; Colemak and Dvorak are for the on-screen keyboard only) and its
 *    binding fits this keyboard and keypad.
 *
 * ## Precedence on the physical keys
 *
 * 1. this per-language choice, when it drives the physical keys as above;
 * 2. else the one custom layout switched on in Physical keyboard > Physical layouts
 *    ([LetterLayoutRegistry.PREF_ACTIVE_LAYOUT]), exactly as before this choice existed;
 * 3. else the built-in document for the keyboard's language, keypad and layout set;
 * 4. else the firmware key map.
 *
 * The two choices never compete for a key: for a language whose choice drives the physical keys,
 * the Physical layouts selection is not consulted at all; for every other language (no choice, or
 * one for the on-screen keyboard only, or one bound to another keypad) it applies unchanged. A
 * layout chosen here is followed with the user tier's rules even when it is a built-in document:
 * its letters always apply, where a language's own built-in document leaves Latin letters to the
 * firmware key map.
 *
 * An id names the user's layout of that id (`files/layouts/letter/<id>.xml`) first, else the
 * built-in one: a built-in saved as a file and imported again, perhaps edited, stands in for it.
 */
object LetterLayoutChoice {

    /** Followed by the language: `pref_letter_layout_en`. */
    const val PREF_PREFIX: String = "pref_letter_layout_"

    /** The preference that holds [language]'s choice. */
    @JvmStatic
    fun prefKey(language: String): String = PREF_PREFIX + language

    /** Whether a changed preference [key] is one of these choices. */
    @JvmStatic
    fun isPrefKey(key: String?): Boolean = key != null && key.length > PREF_PREFIX.length && key.startsWith(PREF_PREFIX)

    /** The language a keyboard in [locale] keeps its choice under, or null. */
    @JvmStatic
    fun languageOf(locale: Locale?): String? = locale?.let { LocaleUtils.languageCode(it) }?.takeIf { it.isNotEmpty() }

    /** The language a [subtype]'s keyboard keeps its choice under, or null. */
    @JvmStatic
    fun languageOf(subtype: InputMethodSubtype?): String? =
        subtype?.let { languageOf(ResourceLocaleUtils.getSubtypeLocale(it)) }

    /** The id chosen for [language], or `""` for the keyboard's own layout. */
    @JvmStatic
    fun chosenId(prefs: SharedPreferences, language: String?): String =
        if (language.isNullOrEmpty()) "" else prefs.getString(prefKey(language), "") ?: ""

    /**
     * Chooses [id] for [language] (`""`: back to the keyboard's own layout). The typing path and
     * the engine pick it up on the next key; the keyboard's preference listener rebuilds the
     * on-screen keyboard.
     */
    @JvmStatic
    fun choose(context: Context, language: String, id: String) {
        val editor = PrefsManager.getPrefs(context).edit()
        if (id.isEmpty()) editor.remove(prefKey(language)) else editor.putString(prefKey(language), id)
        editor.apply()
        LetterLayoutRegistry.invalidate()
    }

    /** The layout [id] names: the user's of that id, else the built-in one; null for `""` or none. */
    @JvmStatic
    fun resolve(registry: LetterLayoutRegistry, id: String): LetterLayout? {
        if (id.isEmpty()) return null
        return registry.get(id) ?: registry.builtIn(id)
    }

    // ── for the typing path ──────────────────────────────────────────────────

    /** A language's chosen id when last read, and the layout it named (null for none or unreadable). */
    private class Snapshot(val id: String, val layout: LetterLayout?)

    private val snapshots = ConcurrentHashMap<String, Snapshot>()

    /**
     * The layout chosen for [language], parsed once per change, so a keystroke never reads a file:
     * like [LetterLayoutRegistry.activeForTyping], the copy is keyed by the id read from the
     * preferences on each call, and [LetterLayoutRegistry.invalidate] drops it.
     */
    @JvmStatic
    fun forTyping(context: Context, language: String?): LetterLayout? {
        if (language.isNullOrEmpty()) return null
        val id = chosenId(PrefsManager.getPrefs(context), language)
        snapshots[language]?.let { if (it.id == id) return it.layout }
        val layout = if (id.isEmpty()) null else resolve(LetterLayoutRegistry(context), id)
        snapshots[language] = Snapshot(id, layout)
        return layout
    }

    /** Forgets the typing path's copies. Called by [LetterLayoutRegistry.invalidate]. */
    @JvmStatic
    fun invalidate() {
        snapshots.clear()
    }

    // ── the on-screen keyboard ───────────────────────────────────────────────

    /**
     * The layout set the on-screen keyboard for [subtype] is built from: the id of the layout
     * chosen for its language when that layout draws the on-screen keyboard ([drawsOnScreen]),
     * else the subtype's own ([ResourceLocaleUtils.getKeyboardLayoutSetName]).
     */
    @JvmStatic
    fun keyboardLayoutSet(context: Context, subtype: InputMethodSubtype): String =
        onScreenLayout(context, languageOf(subtype))?.id ?: ResourceLocaleUtils.getKeyboardLayoutSetName(subtype)

    /** The layout the on-screen keyboard for [language] is drawn from, or null for its own. */
    @JvmStatic
    fun onScreenLayout(context: Context, language: String?): LetterLayout? =
        forTyping(context, language)?.takeIf { drawsOnScreen(context, it) }

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
            LetterLayout.Target.VKB in layout.targets &&
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

    /** One entry of a keyboard's Layout list: [layout] null is the keyboard's own layout (id `""`). */
    class Option(val id: String, val layout: LetterLayout?)

    /**
     * What a keyboard in [language] may choose, in order: its own layout first; then the built-in
     * layouts bound to no language (QWERTY, AZERTY, QWERTZ, Colemak, Dvorak) when the keyboard is
     * written in the Latin alphabet ([latin]) and the layout does something on this phone
     * ([usable]), leaving out the one that is the keyboard's own layout set ([ownLayoutSet]: a
     * French keyboard's first entry is AZERTY already); then, on a phone
     * with physical keys, the user's layouts for the physical keys whose binding names [language]
     * or no language. Each group by name. A user's layout with a built-in's id replaces the
     * built-in, as [resolve] does.
     */
    @JvmStatic
    fun options(
        builtIns: List<LetterLayout>,
        custom: List<LetterLayout>,
        language: String,
        latin: Boolean,
        physicalKeys: Boolean,
        usable: (LetterLayout) -> Boolean,
        ownLayoutSet: String? = null,
    ): List<Option> {
        val customIds = custom.map { it.id }.toSet()
        val byName = compareBy<LetterLayout, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.id }
        val shipped = if (!latin) emptyList() else builtIns
            .filter { !it.binding.isLanguageBound && it.id != ownLayoutSet && it.id !in customIds && usable(it) }
            .sortedWith(byName)
        val own = if (!physicalKeys) emptyList() else custom
            .filter { LetterLayout.Target.PKB in it.targets && it.binding.matchesLanguage(language) }
            .sortedWith(byName)
        return listOf(Option("", null)) + (shipped + own).map { Option(it.id, it) }
    }
}

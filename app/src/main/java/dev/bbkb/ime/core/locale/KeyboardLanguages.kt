package dev.bbkb.ime.core.locale

import android.content.Context
import android.view.inputmethod.InputMethodSubtype
import dev.bbkb.ime.core.distribution.PackEntry
import dev.bbkb.ime.core.distribution.Packs
import dev.bbkb.ime.core.locale.multilanguage.LocaleItem
import dev.bbkb.ime.core.locale.multilanguage.MultiLanguageConfig
import dev.bbkb.ime.core.locale.multilanguage.MultiLanguageRepository
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.shared.Logger
import dev.bbkb.ime.core.subtypeswitcher.SideloadedSubtypes

/**
 * The model behind the consolidated Languages screen: the keyboards the user can switch between,
 * the languages they could add, and the edits between the two.
 *
 * "Keyboard" here is one enabled subtype of this IME: a layout language, plus - for a
 * multi-language keyboard - the extra languages it also predicts in. A multi-language keyboard is
 * not a separate feature on this screen; it is a keyboard with extras, and giving a keyboard
 * extras swaps its plain subtype for the combined one.
 *
 * Android is the only store of *which* keyboards are on. Nothing here caches that: every [read]
 * asks the framework, because the user can change the same list in system settings at any time.
 * Edits go through [SubtypeEnabler.setEnabledList], so they need API 34; before that each edit
 * returns false and the screen sends the user to the system list instead.
 */
object KeyboardLanguages {

    private const val TAG = "KeyboardLanguages"

    /** One keyboard the user can switch to. */
    data class Keyboard(
        val subtype: InputMethodSubtype,
        val name: String,
        /** Non-null for a multi-language keyboard: the saved config its subtype was built from. */
        val config: MultiLanguageConfig?,
        /** Whether this keyboard's layout language can lead a multi-language keyboard (Latin). */
        val canHaveExtras: Boolean,
    ) {
        val locale: String get() = subtype.locale
        val extras: List<LocaleItem> get() = config?.getSupportingLocales().orEmpty()

        /** Every language this keyboard types in, layout language first. */
        val locales: List<String> get() = listOf(locale) + extras.map { it.first as String }
    }

    /** What the screen shows at the top: who picks the keyboards, and which are on. */
    data class Snapshot(val followsSystem: Boolean, val keyboards: List<Keyboard>)

    /**
     * A language the user could add. [subtype] is null for a language that has no keyboard until
     * its pack is installed (the installer then registers one); [pack] is the dictionary adding it
     * would download, if any.
     */
    data class Candidate(
        val locale: String,
        val name: String,
        val subtype: InputMethodSubtype?,
        val pack: PackEntry?,
    )

    fun read(context: Context): Snapshot {
        RichInputMethodManager.init(context)
        ResourceLocaleUtils.init(context)
        val rimm = RichInputMethodManager.getInstance()
        rimm.clearSubtypeCaches()
        val repository = MultiLanguageRepository.getInstance(context)
        val configs = repository.getConfigs()
        val effective = rimm.enabledSubtypesOfThisIme
        val explicit = rimm.getMyEnabledInputMethodSubtypeList(false)
        val keyboards = effective.map { subtype ->
            val config = configs.firstOrNull { it.toSubtype()?.hashCode() == subtype.hashCode() }
            val name = config?.getPrimaryLocale()?.toString()
                ?: ResourceLocaleUtils.getSubtypeDisplayName(subtype).ifEmpty { subtype.locale }
            Keyboard(
                subtype = subtype,
                name = name,
                config = config,
                canHaveExtras = repository.getLayoutSetFor(LocaleItem(subtype.locale)) != null,
            )
        }
        return Snapshot(followsSystem = explicit.isEmpty(), keyboards = keyboards)
    }

    /** Every language that could be added, not counting the keyboards already on. */
    fun candidates(context: Context, snapshot: Snapshot, packs: Packs?, missing: (String) -> PackEntry?): List<Candidate> {
        RichInputMethodManager.init(context)
        ResourceLocaleUtils.init(context)
        val info = RichInputMethodManager.getInstance().inputMethodInfoOfThisIme ?: return emptyList()
        val all = (0 until info.subtypeCount).map { info.getSubtypeAt(it) }
        return buildCandidates(
            all = all,
            enabled = snapshot.keyboards.map { it.subtype.hashCode() }.toSet(),
            packs = packs,
            nameOf = { ResourceLocaleUtils.getSubtypeDisplayName(it).ifEmpty { it.locale } },
            missing = missing,
            canOfferSubtypeFor = SideloadedSubtypes::canOfferSubtypeFor,
        )
    }

    /**
     * Pure half of [candidates]. Single-language keyboards that are not on, then catalogue
     * languages that have no keyboard yet but will get one when their pack installs. Sorted by
     * name; the plain-alphabet `zz` keyboard is left out (it is not a language).
     */
    internal fun buildCandidates(
        all: List<InputMethodSubtype>,
        enabled: Set<Int>,
        packs: Packs?,
        nameOf: (InputMethodSubtype) -> String,
        missing: (String) -> PackEntry?,
        canOfferSubtypeFor: (String) -> Boolean,
    ): List<Candidate> {
        val singles = all.filter { !it.containsExtraValueKey("AdditionalLocales") && it.locale != "zz" }
        val keyboardLanguages = singles.map { it.locale.substringBefore('_') }.toSet()
        val fromKeyboards = singles
            .filter { it.hashCode() !in enabled }
            .map { Candidate(it.locale, nameOf(it), it, missing(it.locale)) }
        val fromCatalogue = packs?.items.orEmpty()
            .filter { !it.isVariant }
            .filter { it.locale.substringBefore('_') !in keyboardLanguages }
            .filter { canOfferSubtypeFor(it.locale.substringBefore('_')) }
            .map { Candidate(it.locale, it.name, null, it) }
        return (fromKeyboards + fromCatalogue).sortedBy { it.name.lowercase() }
    }

    /** Where the user's own list is parked while Android picks the keyboards. */
    private const val PREF_SAVED_LIST = "languages_hub_saved_keyboards"

    /**
     * Hand the choice of keyboards to Android's system languages, or take it back.
     *
     * Handing it over discards the explicit list (that is what "follow the system" is to
     * Android), so the user's own list is parked first and restored when they take the choice
     * back; otherwise flipping the switch twice would silently lose every keyboard they added.
     * Parked keyboards that no longer exist (a removed multi-language keyboard) are skipped.
     */
    fun setFollowsSystem(context: Context, follow: Boolean): Boolean {
        val prefs = PrefsManager.getPrefs(context)
        val current = read(context).keyboards.map { it.subtype }
        if (follow) {
            prefs.edit().putString(PREF_SAVED_LIST, current.joinToString(",") { it.hashCode().toString() }).apply()
            return SubtypeEnabler.setEnabledList(context, emptyList())
        }
        val saved = prefs.getString(PREF_SAVED_LIST, null).orEmpty()
            .split(',').mapNotNull { it.toIntOrNull() }.toSet()
        val info = RichInputMethodManager.getInstance().inputMethodInfoOfThisIme
        val restored = if (info == null) emptyList() else
            (0 until info.subtypeCount).map { info.getSubtypeAt(it) }.filter { it.hashCode() in saved }
        return SubtypeEnabler.setEnabledList(context, mergeRestored(current, restored))
    }

    /**
     * The list to switch on when the user takes the choice back: their parked keyboards, plus
     * whatever Android picked meanwhile *unless* the same layout language is already among the
     * parked ones. Android picks plain keyboards, so a parked "English (US) + French" would
     * otherwise come back alongside a second, plain "English (US)" (owner's KEY2, 2026-09-24).
     */
    internal fun mergeRestored(
        current: List<InputMethodSubtype>,
        restored: List<InputMethodSubtype>,
    ): List<InputMethodSubtype> {
        if (restored.isEmpty()) return current.distinctBy { it.hashCode() }
        val parkedLanguages = restored.map { it.locale }.toSet()
        val extra = current.filter { it.hashCode() !in restored.map { r -> r.hashCode() } && it.locale !in parkedLanguages }
        return (restored + extra).distinctBy { it.hashCode() }
    }

    fun add(context: Context, subtype: InputMethodSubtype): Boolean =
        SubtypeEnabler.setEnabledList(context, read(context).keyboards.map { it.subtype } + subtype)

    /** False, doing nothing, for the last keyboard: an empty list would mean "follow the system". */
    fun remove(context: Context, keyboard: Keyboard): Boolean {
        val remaining = read(context).keyboards.map { it.subtype }
            .filter { it.hashCode() != keyboard.subtype.hashCode() }
        if (remaining.isEmpty()) return false
        return SubtypeEnabler.setEnabledList(context, remaining)
    }

    /** What [setExtras] did. */
    enum class ExtrasResult { DONE, NEEDS_SYSTEM_SETTINGS, FAILED }

    /**
     * Give [keyboard] these extra prediction languages (or none), replacing it in the switch list.
     *
     * With extras, the keyboard becomes the multi-language keyboard for its layout language plus
     * [extras]: the config is saved and registered with Android like the old wizard did, then
     * swapped in for [keyboard]. With none, a multi-language keyboard goes back to the plain one.
     * Before API 34 the config is still saved and registered, and [ExtrasResult.NEEDS_SYSTEM_SETTINGS]
     * says the user has to switch the new keyboard on themselves.
     */
    fun setExtras(context: Context, keyboard: Keyboard, extras: List<LocaleItem>): ExtrasResult {
        val repository = MultiLanguageRepository.getInstance(context)
        val primary = keyboard.config?.getPrimaryLocale() ?: LocaleItem(keyboard.locale)
        val layout = keyboard.config?.getKeyboardLayoutSet() ?: repository.getLayoutSetFor(primary)
            ?: return ExtrasResult.FAILED

        keyboard.config?.let { repository.removeConfig(it) }
        val replacement: InputMethodSubtype = if (extras.isEmpty()) {
            plainSubtypeFor(context, primary.first as String) ?: return ExtrasResult.FAILED
        } else {
            val config = MultiLanguageConfig(primary, ArrayList(extras), layout)
            repository.addConfig(config) // false when it already exists; either way it is saved
            config.toSubtype() ?: return ExtrasResult.FAILED
        }
        registerAdditionalSubtypes(context)

        if (!SubtypeEnabler.canEnableDirectly()) return ExtrasResult.NEEDS_SYSTEM_SETTINGS
        val list = read(context).keyboards.map { it.subtype }
            .map { if (it.hashCode() == keyboard.subtype.hashCode()) replacement else it }
            .let { if (it.any { s -> s.hashCode() == replacement.hashCode() }) it else it + replacement }
            .distinctBy { it.hashCode() }
        return if (SubtypeEnabler.setEnabledList(context, list)) ExtrasResult.DONE else ExtrasResult.FAILED
    }

    /** Latin-script languages that can be extras for [keyboard] (not its own layout language). */
    fun extraChoices(context: Context, keyboard: Keyboard): List<LocaleItem> {
        val primary = keyboard.config?.getPrimaryLocale()?.first ?: keyboard.locale
        return MultiLanguageRepository.getInstance(context).getAvailableLocales()
            .filter { it.first != primary }
    }

    /**
     * Re-register every runtime subtype. All three sources, as [RichInputMethodManager] does at
     * startup: registering only the multi-language ones (as the old wizard does) would drop the
     * runtime keyboards for side-loaded languages until the next restart.
     */
    private fun registerAdditionalSubtypes(context: Context) {
        try {
            val rimm = RichInputMethodManager.getInstance()
            rimm.setAdditionalInputMethodSubtypes(rimm.getAdditionalSubtypes(context))
        } catch (failure: RuntimeException) {
            Logger.warn(TAG, "Could not register runtime keyboards: $failure")
        }
    }

    private fun plainSubtypeFor(context: Context, locale: String): InputMethodSubtype? {
        val info = RichInputMethodManager.getInstance().inputMethodInfoOfThisIme ?: return null
        val all = (0 until info.subtypeCount).map { info.getSubtypeAt(it) }
        return SubtypeEnabler.pickSubtypeFor(locale, all)
    }
}

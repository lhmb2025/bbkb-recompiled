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
 * Model behind the Languages screen. A "keyboard" is one enabled subtype: a layout language plus,
 * for a multi-language keyboard, extra prediction languages (adding extras swaps in the combined
 * subtype). Android is the only store of which keyboards are on, so every [read] asks the
 * framework. Edits need API 34 ([SubtypeEnabler.setEnabledList]); before that they return false.
 */
object KeyboardLanguages {

    private const val TAG = "KeyboardLanguages"

    data class Keyboard(
        val subtype: InputMethodSubtype,
        val name: String,
        /** Non-null for a multi-language keyboard. */
        val config: MultiLanguageConfig?,
        /** Whether the layout language can lead a multi-language keyboard (Latin). */
        val canHaveExtras: Boolean,
    ) {
        val locale: String get() = subtype.locale
        val extras: List<LocaleItem> get() = config?.getSupportingLocales().orEmpty()

        /** Layout language first. */
        val locales: List<String> get() = listOf(locale) + extras.map { it.first as String }
    }

    data class Snapshot(val followsSystem: Boolean, val keyboards: List<Keyboard>)

    /**
     * [subtype] is null until the language's pack is installed (the installer registers one);
     * [pack] is what adding it would download, if anything.
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
                canHaveExtras = repository.getAvailableLocalesFor(subtype.locale).isNotEmpty(),
            )
        }
        return Snapshot(followsSystem = explicit.isEmpty(), keyboards = keyboards)
    }

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
     * Pure half of [candidates]: single-language keyboards not on, plus catalogue languages that
     * get a keyboard when their pack installs. Sorted by name; `zz` (plain alphabet) is left out.
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

    /** The user's own list, parked while Android picks the keyboards. */
    private const val PREF_SAVED_LIST = "languages_hub_saved_keyboards"

    /**
     * Following the system discards the explicit list, so it is parked first and restored when
     * the choice is taken back (skipping keyboards that no longer exist).
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
     * Parked keyboards plus Android's picks whose layout language is not already parked (else a
     * parked "English (US) + French" returns next to a plain "English (US)").
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
     * Replaces [keyboard] with its multi-language form for [extras], or the plain one for none.
     * Before API 34 the config is still saved and registered, and
     * [ExtrasResult.NEEDS_SYSTEM_SETTINGS] means the user must switch it on.
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

    /** Languages in [keyboard]'s script that can be its extras. */
    fun extraChoices(context: Context, keyboard: Keyboard): List<LocaleItem> {
        val primary = (keyboard.config?.getPrimaryLocale()?.first ?: keyboard.locale) as String
        return MultiLanguageRepository.getInstance(context).getAvailableLocalesFor(primary)
    }

    /** All runtime subtype sources: registering only the multi-language ones drops side-loaded keyboards. */
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

package dev.bbkb.ime.core.locale

import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.inputmethod.InputMethodSubtype
import dev.bbkb.ime.core.settings.IntentUtils
import dev.bbkb.ime.core.shared.Logger

/**
 * Whether a language is turned on as one of this keyboard's languages, and turning it on.
 *
 * Installing a language pack does not make the language typeable: the user also has to tick it
 * under the keyboard's languages in system settings, and beta reports showed nobody knew that.
 *
 * ### What Android allows
 *
 * Android 14 (API 34) added `InputMethodManager.setExplicitlyEnabledInputMethodSubtypes`, which
 * lets a keyboard set its own enabled-language list without a permission or a prompt (the
 * framework only checks that the caller owns the keyboard). Before 14 the only way is writing
 * `Settings.Secure.ENABLED_INPUT_METHODS`, which needs `WRITE_SECURE_SETTINGS` (adb/root only), so
 * there [enable] returns false and the caller sends the user to [openLanguageSettings].
 *
 * The call **replaces** the list, and any explicit list switches off Android's "use system
 * languages" mode. So the list passed is everything currently in effect (explicit or implicit,
 * i.e. what the user types in today) plus the new language: nothing the user had disappears.
 *
 * Multi-language keyboards (subtypes carrying `AdditionalLocales`) never count as "the language
 * is on": one with a non-Latin primary types in its Latin layout, not in that language.
 */
object SubtypeEnabler {

    private const val TAG = "SubtypeEnabler"

    /** True when this Android lets the keyboard turn its own languages on (API 34+). */
    @JvmStatic
    fun canEnableDirectly(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /** Is a keyboard for [locale]'s language among the languages the user can switch to? */
    @JvmStatic
    fun isEnabled(context: Context, locale: String): Boolean {
        val enabled = enabledSubtypes(context) ?: return false
        val language = languageOf(locale)
        return enabled.any { isSingleLanguage(it) && languageOf(it.locale) == language }
    }

    /** Does this keyboard have a layout for [locale]'s language at all? */
    @JvmStatic
    fun hasKeyboardFor(context: Context, locale: String): Boolean = try {
        RichInputMethodManager.init(context)
        val info = RichInputMethodManager.getInstance().inputMethodInfoOfThisIme
        info != null && pickSubtypeFor(locale, (0 until info.subtypeCount).map { info.getSubtypeAt(it) }) != null
    } catch (unavailable: RuntimeException) {
        false
    }

    /**
     * Turn [locale]'s language on. Returns whether it is on afterwards — true straight away when
     * it already was, false before API 34, when this keyboard has no layout for the language, or
     * when the framework refused.
     */
    @JvmStatic
    fun enable(context: Context, locale: String): Boolean {
        if (isEnabled(context, locale)) return true
        if (!canEnableDirectly()) return false
        return try {
            RichInputMethodManager.init(context)
            val rimm = RichInputMethodManager.getInstance()
            // A pack install may have just registered a runtime subtype for this language; a
            // cached InputMethodInfo from before that would not list it.
            rimm.clearSubtypeCaches()
            val info = rimm.inputMethodInfoOfThisIme ?: return false
            val all = (0 until info.subtypeCount).map { info.getSubtypeAt(it) }
            val target = pickSubtypeFor(locale, all) ?: run {
                Logger.info(TAG, "No keyboard layout for $locale; nothing to turn on")
                return false
            }
            val current = rimm.enabledSubtypesOfThisIme
            setEnabledList(context, current + target)
            val on = isEnabled(context, locale)
            Logger.info(TAG, "Turned on ${target.locale} for $locale: $on")
            on
        } catch (failure: RuntimeException) {
            Logger.warn(TAG, "Could not turn on $locale: $failure")
            false
        }
    }

    /**
     * Make exactly [subtypes] this keyboard's enabled languages (API 34+). An empty list hands the
     * choice back to Android ("use system languages"). Returns false before API 34 or when the
     * framework refused.
     */
    @JvmStatic
    fun setEnabledList(context: Context, subtypes: List<InputMethodSubtype>): Boolean {
        if (!canEnableDirectly()) return false
        return try {
            RichInputMethodManager.init(context)
            val rimm = RichInputMethodManager.getInstance()
            rimm.clearSubtypeCaches()
            val info = rimm.inputMethodInfoOfThisIme ?: return false
            val hashes = subtypes.map { it.hashCode() }.distinct().toIntArray()
            rimm.inputMethodManager.setExplicitlyEnabledInputMethodSubtypes(info.id, hashes)
            rimm.clearSubtypeCaches()
            true
        } catch (failure: RuntimeException) {
            Logger.warn(TAG, "Could not set the enabled languages: $failure")
            false
        }
    }

    /** Open the system screen listing this keyboard's languages, where the user can tick one. */
    @JvmStatic
    fun openLanguageSettings(context: Context) {
        val imeId = try {
            RichInputMethodManager.init(context)
            RichInputMethodManager.getInstance().inputMethodIdOfThisIme
        } catch (unavailable: RuntimeException) {
            null
        }
        val intent = IntentUtils.getInputLanguageSelectionIntent(imeId, Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (missing: RuntimeException) {
            Logger.warn(TAG, "No language settings screen to open: $missing")
        }
    }

    /**
     * The keyboard to turn on for [locale]: an exact locale match first (`ko`), then a regional
     * one (`zh_HK` -> `zh_HK_cangjie`), then any keyboard for the language (`bn` -> `bn_IN`).
     * Multi-language keyboards are never picked.
     */
    @JvmStatic
    fun pickSubtypeFor(locale: String, subtypes: List<InputMethodSubtype>): InputMethodSubtype? {
        val candidates = subtypes.filter { isSingleLanguage(it) && it.locale != "zz" }
        return candidates.firstOrNull { it.locale == locale }
            ?: candidates.firstOrNull { it.locale.startsWith(locale + "_") }
            ?: candidates.firstOrNull { languageOf(it.locale) == languageOf(locale) }
    }

    private fun enabledSubtypes(context: Context): List<InputMethodSubtype>? = try {
        RichInputMethodManager.init(context)
        RichInputMethodManager.getInstance().enabledSubtypesOfThisIme
    } catch (unavailable: RuntimeException) {
        Logger.warn(TAG, "Could not read the enabled languages: $unavailable")
        null
    }

    private fun isSingleLanguage(subtype: InputMethodSubtype): Boolean =
        !subtype.containsExtraValueKey("AdditionalLocales")

    private fun languageOf(locale: String): String = locale.substringBefore('_')
}

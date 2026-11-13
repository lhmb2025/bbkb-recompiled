package dev.bbkb.ime.core.locale

import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.inputmethod.InputMethodSubtype
import dev.bbkb.ime.core.settings.IntentUtils
import dev.bbkb.ime.core.shared.Logger

/**
 * Checks and turns on this keyboard's languages (an installed pack is not typeable until enabled).
 * API 34+ `setExplicitlyEnabledInputMethodSubtypes` needs no permission; earlier needs
 * WRITE_SECURE_SETTINGS, so [enable] returns false and callers use [openLanguageSettings]. The call
 * replaces the list and ends "use system languages", so pass everything in effect plus the new one.
 * Multi-language subtypes never count as "on" and are never picked (they would add extra languages).
 */
object SubtypeEnabler {

    private const val TAG = "SubtypeEnabler"

    /** API 34+. */
    @JvmStatic
    fun canEnableDirectly(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /** Whether a single-language keyboard for [locale]'s language is enabled. */
    @JvmStatic
    fun isEnabled(context: Context, locale: String): Boolean {
        val enabled = enabledSubtypes(context) ?: return false
        val language = languageOf(locale)
        return enabled.any { isSingleLanguage(it) && languageOf(it.locale) == language }
    }

    @JvmStatic
    fun hasKeyboardFor(context: Context, locale: String): Boolean = try {
        RichInputMethodManager.init(context)
        val info = RichInputMethodManager.getInstance().inputMethodInfoOfThisIme
        info != null && pickSubtypeFor(locale, (0 until info.subtypeCount).map { info.getSubtypeAt(it) }) != null
    } catch (unavailable: RuntimeException) {
        false
    }

    /** Whether [locale]'s language is on afterwards (false before API 34, with no layout, or refused). */
    @JvmStatic
    fun enable(context: Context, locale: String): Boolean {
        if (isEnabled(context, locale)) return true
        if (!canEnableDirectly()) return false
        return try {
            RichInputMethodManager.init(context)
            val rimm = RichInputMethodManager.getInstance()
            // A pack install may just have registered a runtime subtype the cached info lacks.
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

    /** API 34+; an empty list means "use system languages". False before 34 or when refused. */
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
     * Exact locale match first (`ko`), then regional (`zh_HK` -> `zh_HK_cangjie`), then any for the
     * language (`bn` -> `bn_IN`). Never a multi-language keyboard.
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

package dev.bbkb.ime.core.layout

import android.content.Context
import android.content.SharedPreferences
import dev.bbkb.ime.core.locale.LocaleUtils
import dev.bbkb.ime.core.locale.SubtypeManager
import dev.bbkb.ime.core.settings.PrefsManager

/**
 * Per-language "Always show the on-screen keyboard": on a phone with physical keys, that language's
 * keyboards show the on-screen keyboard as if the phone had none. One preference per language,
 * [PREF_PREFIX]`<language>` ([LocaleUtils.languageCode]); absent means off. The IME syncs
 * `DeviceProfile`'s language flag from [isOnForCurrentKeyboard] on keyboard and choice changes.
 */
object OnScreenKeyboardChoice {

    const val PREF_PREFIX: String = "pref_always_on_screen_"

    @JvmStatic
    fun prefKey(language: String): String = PREF_PREFIX + language

    @JvmStatic
    fun isPrefKey(key: String?): Boolean = key != null && key.length > PREF_PREFIX.length && key.startsWith(PREF_PREFIX)

    @JvmStatic
    fun isOn(prefs: SharedPreferences, language: String?): Boolean =
        !language.isNullOrEmpty() && prefs.getBoolean(prefKey(language), false)

    @JvmStatic
    fun isOn(context: Context, language: String?): Boolean = isOn(PrefsManager.getPrefs(context), language)

    @JvmStatic
    fun choose(context: Context, language: String, on: Boolean) {
        val editor = PrefsManager.getPrefs(context).edit()
        if (on) editor.putBoolean(prefKey(language), true) else editor.remove(prefKey(language))
        editor.apply()
    }

    /** Whether the current keyboard's language asked for the on-screen keyboard. */
    @JvmStatic
    fun isOnForCurrentKeyboard(context: Context): Boolean {
        val locale = SubtypeManager.getInstance()?.currentSubtypeLocale ?: return false
        return isOn(context, LocaleUtils.languageCode(locale))
    }
}

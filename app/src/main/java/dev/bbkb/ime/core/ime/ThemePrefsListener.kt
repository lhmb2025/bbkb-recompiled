package dev.bbkb.ime.core.ime
import android.content.SharedPreferences
import android.util.Log
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.device.ResourceConfigManager
import dev.bbkb.ime.keyboard.KeyboardBuilder
import dev.bbkb.ime.keyboard.KeyboardColorManager
import dev.bbkb.ime.keyboard.inputboard.UimMenuOrder
import dev.bbkb.ime.BuildConfig
import dev.bbkb.ime.core.BlackBerryIME

/**
 * Reacts to the preference changes that must rebuild or re-theme the live keyboard:
 * UIM on/off, UIM menu order, keyboard height mode, and the three theme keys.
 * Registered by [BlackBerryIME] on the default shared preferences for the service's lifetime.
 */
class ThemePrefsListener(private val ime: BlackBerryIME) : SharedPreferences.OnSharedPreferenceChangeListener {

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        val keyboardSwitcher = ime.getKeyboardSwitcher()
        if ("pref_uim_enabled" == key) {
            val uimEnabled = sharedPreferences.getBoolean("pref_uim_enabled", true)
            val uimManager = keyboardSwitcher.getUnifiedInputBoardManager()
            if (!uimEnabled && uimManager != null) {
                uimManager.hide()
            } else if (uimEnabled && uimManager != null) {
                // The UIM will also show on the next onStartInputView() if shouldShowUim() agrees,
                // so re-enabling never needs a keyboard rebuild.
                if (ime.isInputViewShown()) {
                    uimManager.show(false)
                }
            }
        }

        if (UimMenuOrder.PREF_KEY == key) {
            // The AuxBarManager keeps its own reference to the UIM keyboard; clear it so
            // its no-arg show path re-fetches the recomposed bar from the UIM manager.
            ime.auxBarManager?.clearKeyboardCache()
            keyboardSwitcher.getUnifiedInputBoardManager()?.onMenuOrderChanged()
        }

        if ("pref_keyboard_height_mode" == key) {
            ime.numberPadController?.invalidateKeyboard()
            KeyboardBuilder.clearKeyboardCache()
            ResourceConfigManager.invalidateDimensionCache()
            // AuxBarManager survives input-view recreation and caches its arrow/diacritics/UIM
            // keyboards, which are built at the scaled strip height -- without this they keep
            // the previous height forever.
            ime.auxBarManager?.clearKeyboardCache()
            ime.loadSettings()
            keyboardSwitcher.forceRecreateInputView()
        }

        if (PrefsManager.Keys.KEYBOARD_THEME_MODE == key ||
            PrefsManager.Keys.KEYBOARD_THEME_STYLE == key ||
            PrefsManager.Keys.KEYBOARD_USE_SYSTEM_COLORS == key
        ) {
            if (BuildConfig.DEBUG) Log.e(BlackBerryIME.LOG_TAG, "Theme preference changed! Reloading colors...")
            KeyboardColorManager.setTheme(
                KeyboardColorManager.Style.fromPref(
                    sharedPreferences.getString(PrefsManager.Keys.KEYBOARD_THEME_STYLE, "modern")),
                KeyboardColorManager.Scheme.fromPref(
                    sharedPreferences.getString(PrefsManager.Keys.KEYBOARD_THEME_MODE, "auto")),
                sharedPreferences.getBoolean(PrefsManager.Keys.KEYBOARD_USE_SYSTEM_COLORS, false),
            )
            try {
                // Full input-view recreation (same cascade as a height change): the input boards
                // (FCC, voice, clipboard) gate their layouts on the theme at inflate time, so a
                // repaint-only reload leaves them showing the previous theme's layout until the
                // IME restarts.
                KeyboardBuilder.clearKeyboardCache()
                ime.auxBarManager?.clearKeyboardCache()
                ime.numberPadController?.invalidateKeyboard()
                ime.loadSettings()
                keyboardSwitcher.forceRecreateInputView()
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.e(BlackBerryIME.LOG_TAG, "Failed to reload keyboard after theme change", e)
            }
        }
    }
}

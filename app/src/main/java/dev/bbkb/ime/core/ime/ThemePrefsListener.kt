package dev.bbkb.ime.core.ime
import android.content.SharedPreferences
import android.util.Log
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.device.ResourceConfigManager
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.layout.LetterLayoutChoice
import dev.bbkb.ime.core.layout.LetterLayoutRegistry
import dev.bbkb.ime.core.settings.util.SettingsManager
import dev.bbkb.ime.core.settings.util.SettingsValues
import dev.bbkb.ime.keyboard.KeyboardBuilder
import dev.bbkb.ime.keyboard.KeyboardColorManager
import dev.bbkb.ime.keyboard.inputboard.UimMenuOrder
import dev.bbkb.ime.BuildConfig
import dev.bbkb.ime.core.BlackBerryIME

/**
 * Preference changes that rebuild or re-theme the live keyboard (UIM, PKB bar, menu order, layouts,
 * height mode, theme). Registered by [BlackBerryIME] for the service's lifetime.
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
                // No rebuild needed: the next onStartInputView() shows it too.
                if (ime.isInputViewShown()) {
                    uimManager.show(false)
                }
            }
        }

        if ("show_predictions" == key || "pref_uim_enabled" == key) {
            // Read prefs, not SettingsValues: SettingsManager's listener may not have reloaded yet.
            val hasContent = SettingsManager.pkbBarHasContent(sharedPreferences, ime.resources)
            val uimManager = keyboardSwitcher.getUnifiedInputBoardManager()
            if (SettingsValues.isPkbSuggestionBarHidden(hasContent)) {
                uimManager?.hide()
                ime.auxBarManager?.hideSuggestionBar()
            } else if (hasContent && uimManager != null && ime.isInputViewShown()) {
                uimManager.show(false)
            }
        }

        if (UimMenuOrder.PREF_KEY == key) {
            // AuxBarManager caches the UIM keyboard; clear it so show() re-fetches the new bar.
            ime.auxBarManager?.clearKeyboardCache()
            keyboardSwitcher.getUnifiedInputBoardManager()?.onMenuOrderChanged()
        }

        if (DeviceProfile.PREF_KEYPAD_LAYOUT == key) {
            // Symbol rows, Alt characters and layout bindings all follow the rebuilt profile.
            DeviceProfile.initialize(ime.applicationContext)
            LetterLayoutRegistry.invalidate()
            KeyboardBuilder.clearKeyboardCache()
        }

        if (dev.bbkb.ime.core.layout.OnScreenKeyboardChoice.isPrefKey(key)) {
            ime.refreshLanguageOnScreenKeyboard()
        }

        if (LetterLayoutChoice.isPrefKey(key)) {
            // Rebuilt on the next startInput; old cached keyboards (keyed by layout set) only take memory.
            LetterLayoutRegistry.invalidate()
            KeyboardBuilder.clearKeyboardCache()
        }

        if ("pref_keyboard_height_mode" == key) {
            ime.numberPadController?.invalidateKeyboard()
            KeyboardBuilder.clearKeyboardCache()
            ResourceConfigManager.invalidateDimensionCache()
            // AuxBarManager survives view recreation and caches keyboards built at the old strip height.
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
                // Full recreation: FCC, voice and clipboard pick their layout by theme at inflate time.
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

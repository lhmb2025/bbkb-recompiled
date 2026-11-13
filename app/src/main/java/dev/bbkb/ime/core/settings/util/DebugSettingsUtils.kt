package dev.bbkb.ime.core.settings.util

import android.content.Context
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.locale.SubtypeManager

/** Clears all settings or only the debug preferences. */

object DebugSettingsUtils {
    fun clearAllSettings(context: Context) {
        val prefs = PrefsManager.getPrefs(context)
        prefs.edit().clear().apply()

        SubtypeManager.ensureInitialized(context)
    }

    fun clearDebugSettings(context: Context) {
        val prefs = PrefsManager.getPrefs(context)
        val editor = prefs.edit()

        val debugKeys = listOf(
            "pref_debug_mode",
            "pref_sliding_key_preview",
            "pref_show_on_keypress_mode",
            "pref_horizontal_cursor_move",
            "pref_batched_cursor_moves",
            "pref_multitouch_enabled",
            "pref_physical_keyboard_debug_mode",
            "pref_slideboard_still_boards",
            "pref_always_show_custom_symbol_page_overlay"
        )
        
        for (key in debugKeys) {
            editor.remove(key)
        }
        
        editor.apply()
    }
}

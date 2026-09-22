package dev.bbkb.ime.core.settings.util

import android.content.Context
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.locale.SubtypeManager

/**
 * Provides DownloadResult set of utility functions for managing debug-related settings. This includes methods
 * to clear all settings, reset tutorial data, or remove specific debug preferences.
 */

object DebugSettingsUtils {
    
    // REMOVED: clearTutorialData() - Tutorial system eliminated
    
    /**
     * Clear all user settings and reset to defaults
     * Resets keyboard back to default settings
     */
    fun clearAllSettings(context: Context) {
        val prefs = PrefsManager.getPrefs(context)
        prefs.edit().clear().apply()
        
        // Reinitialize core settings
        SubtypeManager.ensureInitialized(context)
    }
    
    /**
     * Clear specific debug-related settings
     */
    fun clearDebugSettings(context: Context) {
        val prefs = PrefsManager.getPrefs(context)
        val editor = prefs.edit()
        
        // Clear debug mode settings
        val debugKeys = listOf(
            "pref_debug_mode",
            // REMOVED: pref_show_keyboard_overlay - Debug overlay eliminated
            "pref_sliding_key_preview",
            "pref_show_on_keypress_mode",
            "pref_horizontal_cursor_move",
            "pref_batched_cursor_moves",
            "pref_multitouch_enabled",
            "pref_physical_keyboard_debug_mode",
            // REMOVED: Tutorial preference keys - Tutorial system eliminated
            // REMOVED: pref_uim_use_screen_edge_menu - Edge-sliding menu eliminated
            // REMOVED: pref_uim_extend_active_edge_above_keyboard - Edge-sliding menu eliminated
            "pref_slideboard_still_boards",
            "pref_always_show_custom_symbol_page_overlay"
        )
        
        for (key in debugKeys) {
            editor.remove(key)
        }
        
        editor.apply()
    }
}

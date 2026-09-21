package dev.bbkb.ime.core.settings
import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/**
 * Centralized SharedPreferences accessor to avoid repeated 
 * PreferenceManager.getDefaultSharedPreferences() calls throughout the codebase.
 * 
 * This singleton ensures:
 * 1. Single SharedPreferences instance across the app
 * 2. No repeated disk I/O from multiple getDefaultSharedPreferences() calls
 * 3. Uses AndroidX PreferenceManager (not deprecated android.preference.PreferenceManager)
 * 
 * Usage:
 * - Initialize once in BlackBerryIME.onCreate(): PrefsManager.init(this)
 * - Access anywhere: PrefsManager.getPrefs()
 */
object PrefsManager {
    
    @Volatile
    private var prefs: SharedPreferences? = null
    
    private val lock = Any()
    
    /**
     * Initialize the PrefsManager with application context.
     * Should be called once during app startup (e.g., in BlackBerryIME.onCreate()).
     * 
     * @param context Any context - will be converted to applicationContext internally
     */
    fun init(context: Context) {
        if (prefs == null) {
            synchronized(lock) {
                if (prefs == null) {
                    prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
                }
            }
        }
    }
    
    /**
     * Get the shared SharedPreferences instance.
     * 
     * @return SharedPreferences instance
     * @throws IllegalStateException if init() was not called first
     */
    fun getPrefs(): SharedPreferences {
        return prefs ?: throw IllegalStateException(
            "PrefsManager not initialized. Call PrefsManager.init(context) first."
        )
    }
    
    /**
     * Check if PrefsManager has been initialized.
     */
    fun isInitialized(): Boolean = prefs != null
    
    /**
     * Get SharedPreferences, initializing if needed with the provided context.
     * This is a convenience method for cases where you have a context but aren't
     * sure if PrefsManager was initialized.
     * 
     * @param context Context to use for initialization if needed
     * @return SharedPreferences instance
     */
    fun getPrefs(context: Context): SharedPreferences {
        if (prefs == null) {
            init(context)
        }
        return getPrefs()
    }
    
    // Common preference keys as constants
    object Keys {
        const val DEBUG_MODE = "debug_mode"

        /** Theme style: "classic" | "modern" | "material" | "bb10". */
        const val KEYBOARD_THEME_STYLE = "pref_keyboard_theme_style"

        /** Color scheme: "light" | "dark" | "auto". Not applicable to classic/bb10. */
        const val KEYBOARD_THEME_MODE = "pref_keyboard_theme_mode"

        /** Material You wallpaper colors (Android 12+), under modern/material. */
        const val KEYBOARD_USE_SYSTEM_COLORS = "pref_keyboard_use_system_colors"

        const val DEBUG_AUTOFILL = "debug_autofill"
        const val DEBUG_AUTOFILL_LOGGING = "debug_autofill_logging"
        const val KEY_INTERCEPTOR_ENABLED = "key_interceptor_enabled"
    }

    /**
     * One-time theme pref migration. pref_keyboard_theme_mode used to carry style
     * values ("classic", "dynamic") alongside the scheme values; the theme is now
     * split into style (classic/modern/material) + scheme (light/dark/auto) + a
     * system-colors toggle. Legacy values map to their visual equivalents so
     * existing users see no change. Idempotent: once mode holds a scheme value,
     * both branches are dead.
     */
    fun migrateThemePrefs(prefs: SharedPreferences) {
        if (prefs.contains(Keys.KEYBOARD_THEME_STYLE)) return
        when (prefs.getString(Keys.KEYBOARD_THEME_MODE, null)) {
            "classic" -> prefs.edit()
                .putString(Keys.KEYBOARD_THEME_STYLE, "classic")
                .putString(Keys.KEYBOARD_THEME_MODE, "auto")
                .apply()
            "dynamic" -> prefs.edit()
                .putString(Keys.KEYBOARD_THEME_STYLE, "modern")
                .putString(Keys.KEYBOARD_THEME_MODE, "auto")
                .putBoolean(Keys.KEYBOARD_USE_SYSTEM_COLORS, true)
                .apply()
        }
    }
}

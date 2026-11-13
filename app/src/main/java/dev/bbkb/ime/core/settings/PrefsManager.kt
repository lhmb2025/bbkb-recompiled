package dev.bbkb.ime.core.settings
import android.content.Context
import android.content.SharedPreferences
import dev.bbkb.ime.core.device.profile.DeviceProfile

/**
 * The app-wide SharedPreferences: one instance of the default file, read through
 * [ProfileForcedPrefs] so profile-forced settings read as forced everywhere.
 * Call [init] once at startup.
 */
object PrefsManager {
    
    @Volatile
    private var prefs: SharedPreferences? = null
    
    private val lock = Any()

    private const val DEFAULT_PREFS_SUFFIX = "_preferences"

    /** The stored default file (`<package>_preferences`), without the profile's forced values. */
    @JvmStatic
    fun defaultSharedPreferences(context: Context): SharedPreferences =
        // Never change this name: existing users' settings are stored under it.
        context.getSharedPreferences(context.packageName + DEFAULT_PREFS_SUFFIX, Context.MODE_PRIVATE)

    /** Idempotent; uses the application context. */
    fun init(context: Context) {
        if (prefs == null) {
            synchronized(lock) {
                if (prefs == null) {
                    prefs = ProfileForcedPrefs(defaultSharedPreferences(context.applicationContext),
                        DeviceProfile::peekSettingOverride)
                }
            }
        }
    }

    /** @throws IllegalStateException if [init] was not called first */
    fun getPrefs(): SharedPreferences {
        return prefs ?: throw IllegalStateException(
            "PrefsManager not initialized. Call PrefsManager.init(context) first."
        )
    }

    fun isInitialized(): Boolean = prefs != null

    /** [getPrefs], initializing from [context] first if needed. */
    fun getPrefs(context: Context): SharedPreferences {
        if (prefs == null) {
            init(context)
        }
        return getPrefs()
    }

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
     * One-time migration from the old combined theme_mode values ("classic", "dynamic") to
     * style + scheme + system colors, with the same look. Idempotent.
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

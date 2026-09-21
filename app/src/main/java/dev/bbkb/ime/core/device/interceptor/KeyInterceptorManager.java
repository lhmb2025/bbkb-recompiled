package dev.bbkb.ime.core.device.interceptor;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.text.TextUtils;

import androidx.annotation.NonNull;

/**
 * Manager class for the KeyInterceptorService accessibility service.
 * This class provides a centralized API for:
 * - Checking if the accessibility service is enabled
 * - Registering/unregistering the direct callback (low latency)
 * - Opening system settings to enable the service
 * - Feature toggling (enable/disable without uninstalling)
 * @see KeyInterceptorService
 */
public final class KeyInterceptorManager {

    private static final String TAG = "KeyInterceptorManager";

    /** Preference key for enabling/disabling the special key interception feature */
    public static final String PREF_KEY_INTERCEPTOR_ENABLED = "pref_key_interceptor_enabled";
    
    /** Preference key for enabling/disabling pre-processing of all key events */
    public static final String PREF_PREPROCESS_ALL_KEYS = "pref_preprocess_all_key_events";
    
    /** Preference key for enabling unified key mapping pipeline (Phase 2 feature flag) */
    public static final String PREF_USE_UNIFIED_KEY_MAPPING = "pref_use_unified_key_mapping";

    private KeyInterceptorManager() {
        // Private constructor to prevent instantiation
    }

    /**
     * Check if the KeyInterceptorService accessibility service is enabled in system settings.
     * 
     * @param context Application context
     * @return true if the accessibility service is enabled
     */
    public static boolean isServiceEnabled(@NonNull Context context) {
        ComponentName expectedComponent = new ComponentName(context, KeyInterceptorService.class);
        String expectedFlattened = expectedComponent.flattenToString();

        String enabledServicesSetting = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);

        if (TextUtils.isEmpty(enabledServicesSetting)) {
            return false;
        }

        // Check if our service is in the list of enabled services
        String[] enabledServices = enabledServicesSetting.split(":");
        for (String service : enabledServices) {
            if (service.equalsIgnoreCase(expectedFlattened)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Open the system accessibility settings so the user can enable/manage the service.
     * Attempts to open the specific service details page first, falls back to general settings.
     * 
     * @param context Context to start the activity
     */
    public static void openAccessibilitySettings(@NonNull Context context) {
        // Try to open the specific accessibility service details page
        ComponentName componentName = new ComponentName(context, KeyInterceptorService.class);
        
        try {
            // Android 7.0+ supports direct navigation to service details
            Intent detailsIntent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            detailsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            // Add the component name as extra to highlight/navigate to our service
            detailsIntent.putExtra(":settings:fragment_args_key", componentName.flattenToString());
            android.os.Bundle bundle = new android.os.Bundle();
            bundle.putString(":settings:fragment_args_key", componentName.flattenToString());
            detailsIntent.putExtra(":settings:show_fragment_args", bundle);
            context.startActivity(detailsIntent);
        } catch (Exception e) {
            // Fallback to general accessibility settings
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        }
    }

    /**
     * Register a direct callback for key interception (minimal latency).
     * This bypasses the broadcast mechanism entirely.
     * 
     * @param callback Callback to handle intercepted keys
     */
    public static void setCallback(@NonNull KeyInterceptorService.KeyEventCallback callback) {
        KeyInterceptorService.setCallback(callback);
    }

    /**
     * Clear the callback.
     */
    public static void clearCallback() {
        KeyInterceptorService.setCallback(null);
    }

    /**
     * Check if the feature toggle is enabled in preferences.
     * The feature can be disabled even if the accessibility service is enabled.
     * 
     * @param context Application context
     * @return true if the feature is enabled in preferences
     */
    public static boolean isFeatureEnabled(@NonNull Context context) {
        return dev.bbkb.ime.core.settings.PrefsManager.INSTANCE.getPrefs(context)
                .getBoolean(PREF_KEY_INTERCEPTOR_ENABLED, false); // Default to disabled
    }

    /**
     * Set the feature toggle state.
     *
     * <p>Turning the feature OFF takes effect immediately: this method can clear the special-key
     * callback itself, and a user who just switched the feature off should not keep it until the
     * IME's next input session. Turning it ON cannot be applied from here — installing the
     * callback needs the live IME instance, which the settings activity does not have — so it is
     * picked up by {@code HardwareKeyBridge.registerInterceptorCallbacks()}, re-fired from
     * {@code BlackBerryIME.loadSettings()} on the next input session.
     *
     * @param context Application context
     * @param enabled Whether to enable the feature
     */
    public static void setFeatureEnabled(@NonNull Context context, boolean enabled) {
        dev.bbkb.ime.core.settings.PrefsManager.INSTANCE.getPrefs(context)
                .edit()
                .putBoolean(PREF_KEY_INTERCEPTOR_ENABLED, enabled)
                .apply();
        if (!enabled) {
            KeyInterceptorService.setCallback(null);
        }
    }

    /**
     * Check if the pre-process all keys feature is enabled in preferences.
     * 
     * @param context Application context
     * @return true if pre-processing all keys is enabled
     */
    public static boolean isPreprocessAllKeysEnabled(@NonNull Context context) {
        return dev.bbkb.ime.core.settings.PrefsManager.INSTANCE.getPrefs(context)
                .getBoolean(PREF_PREPROCESS_ALL_KEYS, false); // Default to disabled
    }
    
    /**
     * Set the pre-process all keys feature toggle state.
     * 
     * @param context Application context
     * @param enabled Whether to enable the feature
     */
    public static void setPreprocessAllKeysEnabled(@NonNull Context context, boolean enabled) {
        dev.bbkb.ime.core.settings.PrefsManager.INSTANCE.getPrefs(context)
                .edit()
                .putBoolean(PREF_PREPROCESS_ALL_KEYS, enabled)
                .apply();
        // Update the service's preprocess state
        KeyInterceptorService.setPreprocessAllKeysEnabled(enabled);
    }
    
    /**
     * Set the unified key mapping pipeline toggle state.
     * 
     * @param context Application context
     * @param enabled Whether to enable unified key mapping
     */
    public static void setUnifiedKeyMappingEnabled(@NonNull Context context, boolean enabled) {
        dev.bbkb.ime.core.settings.PrefsManager.INSTANCE.getPrefs(context)
                .edit()
                .putBoolean(PREF_USE_UNIFIED_KEY_MAPPING, enabled)
                .apply();
        KeyInterceptorService.setUnifiedKeyMappingEnabled(enabled);
    }
    
}

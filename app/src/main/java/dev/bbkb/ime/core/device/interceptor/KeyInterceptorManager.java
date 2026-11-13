package dev.bbkb.ime.core.device.interceptor;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.text.TextUtils;

import androidx.annotation.NonNull;

/** Static API for the {@link KeyInterceptorService} accessibility service and its feature prefs. */
public final class KeyInterceptorManager {

    private static final String TAG = "KeyInterceptorManager";

    public static final String PREF_KEY_INTERCEPTOR_ENABLED = "pref_key_interceptor_enabled";

    public static final String PREF_PREPROCESS_ALL_KEYS = "pref_preprocess_all_key_events";

    private KeyInterceptorManager() {
        // Static helpers only.
    }

    /** Whether the service is enabled in the system accessibility settings. */
    public static boolean isServiceEnabled(@NonNull Context context) {
        ComponentName expectedComponent = new ComponentName(context, KeyInterceptorService.class);
        String expectedFlattened = expectedComponent.flattenToString();

        String enabledServicesSetting = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);

        if (TextUtils.isEmpty(enabledServicesSetting)) {
            return false;
        }

        String[] enabledServices = enabledServicesSetting.split(":");
        for (String service : enabledServices) {
            if (service.equalsIgnoreCase(expectedFlattened)) {
                return true;
            }
        }

        return false;
    }

    /** Open accessibility settings, highlighting this service where the Settings app supports it. */
    public static void openAccessibilitySettings(@NonNull Context context) {
        ComponentName componentName = new ComponentName(context, KeyInterceptorService.class);
        
        try {
            Intent detailsIntent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            detailsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            detailsIntent.putExtra(":settings:fragment_args_key", componentName.flattenToString());
            android.os.Bundle bundle = new android.os.Bundle();
            bundle.putString(":settings:fragment_args_key", componentName.flattenToString());
            detailsIntent.putExtra(":settings:show_fragment_args", bundle);
            context.startActivity(detailsIntent);
        } catch (Exception e) {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        }
    }

    /** Register the direct (non-broadcast) callback for intercepted keys. */
    public static void setCallback(@NonNull KeyInterceptorService.KeyEventCallback callback) {
        KeyInterceptorService.setCallback(callback);
    }

    public static void clearCallback() {
        KeyInterceptorService.setCallback(null);
    }

    /** The feature pref; it can be off even while the accessibility service is enabled. */
    public static boolean isFeatureEnabled(@NonNull Context context) {
        return dev.bbkb.ime.core.settings.PrefsManager.INSTANCE.getPrefs(context)
                .getBoolean(PREF_KEY_INTERCEPTOR_ENABLED, false);
    }

    /**
     * Turning off clears the callback at once. Turning on needs the live IME, so
     * {@code HardwareKeyBridge.registerInterceptorCallbacks()} picks it up on the next input session.
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

    public static boolean isPreprocessAllKeysEnabled(@NonNull Context context) {
        return dev.bbkb.ime.core.settings.PrefsManager.INSTANCE.getPrefs(context)
                .getBoolean(PREF_PREPROCESS_ALL_KEYS, false);
    }

    public static void setPreprocessAllKeysEnabled(@NonNull Context context, boolean enabled) {
        dev.bbkb.ime.core.settings.PrefsManager.INSTANCE.getPrefs(context)
                .edit()
                .putBoolean(PREF_PREPROCESS_ALL_KEYS, enabled)
                .apply();
        KeyInterceptorService.setPreprocessAllKeysEnabled(enabled);
    }

}

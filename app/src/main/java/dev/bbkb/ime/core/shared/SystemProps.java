package dev.bbkb.ime.core.shared;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The one place that reads {@code android.os.SystemProperties}, by reflection since it is
 * {@code @hide}; non-SDK-interface enforcement can block it, and then every caller gets its
 * default. The keys ({@code debug.et9.*} toggles, {@code ro.*} keypad descriptors) are optional.
 */
public final class SystemProps {

    /**
     * {@code SystemProperties.get(String)}, resolved once. A failure is cached too: enforcement is
     * per process, so a blocked lookup stays blocked.
     */
    private static volatile java.lang.reflect.Method sGet;
    private static volatile boolean sResolved;

    private SystemProps() {}

    /** Reads a system property, or null if unset or unreachable; a set property may still be "". */
    @Nullable
    public static String get(@NonNull String key) {
        return get(key, null);
    }

    /** Reads a system property, returning {@code defaultValue} if it is unset or unreachable. */
    @Nullable
    public static String get(@NonNull String key, @Nullable String defaultValue) {
        final java.lang.reflect.Method get = resolveGet();
        if (get == null) {
            return defaultValue;
        }
        try {
            final String value = (String) get.invoke(null, key);
            return value != null ? value : defaultValue;
        } catch (Exception e) {
            // Blocked, missing, or otherwise unavailable: the caller's default stands.
            return defaultValue;
        }
    }

    @Nullable
    private static java.lang.reflect.Method resolveGet() {
        if (sResolved) {
            return sGet;
        }
        synchronized (SystemProps.class) {
            if (!sResolved) {
                java.lang.reflect.Method get = null;
                try {
                    get = Class.forName("android.os.SystemProperties")
                            .getMethod("get", String.class);
                } catch (Exception e) {
                    // Blocked, missing, or otherwise unavailable.
                }
                sGet = get;
                sResolved = true;
            }
        }
        return sGet;
    }
}

package dev.bbkb.ime.core.shared;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The one place that reads {@code android.os.SystemProperties}.
 *
 * <p>That class is {@code @hide}, so it can only be reached by reflection, and under
 * non-SDK-interface enforcement at targetSdk 36 the reflective lookup can be blocked outright.
 * When it is, every caller silently receives its default -- which is why this must be a single
 * helper rather than a block copy-pasted into each feature: one failure mode, one place to see
 * it, one place to replace when a supported source becomes available.
 *
 * <p>The keys read through here are all {@code debug.et9.*} developer toggles plus the
 * {@code ro.*} keypad descriptors; none is required for correct operation, so returning the
 * caller's default on failure is the intended behaviour.
 */
public final class SystemProps {

    /**
     * {@code SystemProperties.get(String)}, resolved once. The reflective pair
     * ({@code Class.forName} + {@code getMethod}) used to be re-run on every read, and the reads
     * are not rare: {@code HardwareProbe.getSystemKeypadType()} alone makes two per
     * {@code DeviceCapabilities.detect()}, on the IME's cold-start path and again on every
     * settings-screen device query.
     *
     * <p>Caching the failure is deliberate and loses nothing: non-SDK-interface enforcement is a
     * property of the process, so a lookup that was blocked once stays blocked, and the
     * documented behaviour on failure is already "the caller's default stands".
     */
    private static volatile java.lang.reflect.Method sGet;
    private static volatile boolean sResolved;

    private SystemProps() {} // No instantiation

    /**
     * Reads a system property, or returns {@code null} if it is unset or unreachable.
     *
     * <p>Note the property may also be present but empty; callers that treat "" and "unset"
     * differently must check for themselves.
     */
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

    /** The resolved accessor, or null if it is unavailable in this process. Resolved once. */
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

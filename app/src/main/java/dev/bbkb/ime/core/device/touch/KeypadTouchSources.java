package dev.bbkb.ime.core.device.touch;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dev.bbkb.ime.core.device.config.model.TouchKeypadConfig;
import dev.bbkb.ime.core.device.profile.DeviceProfile;

/**
 * The plain read-only API over the keypad touch sources, for whatever needs to show or decide on
 * their state outside the IME (a settings screen, a diagnostics dump): which source the active
 * profile selects, the profile's pin, and each source's last known status.
 *
 * <p>Process-wide: the IME's {@link NativeTouchSource} publishes its status here, and readers
 * that run elsewhere in the process (the settings activity) see the last published value.
 */
public final class KeypadTouchSources {

    private static volatile TouchSourceStatus sNativeStatus;

    private KeypadTouchSources() {}

    /** What {@link TouchSourceSelector} picks for the active device profile. */
    @NonNull
    public static TouchSourceSelector.Selection selection() {
        return DeviceProfile.current().getTouchSourceSelection();
    }

    /** The active profile's {@code <touch-keypad source>} pin, or null when it declares no pad. */
    @Nullable
    public static TouchKeypadConfig.SourcePreference sourcePreference() {
        return selection().preference;
    }

    /**
     * The native source's status: the last one an IME published, or what the selection implies
     * when no IME has published (NOT_APPLICABLE on every device without a declared pad).
     */
    @NonNull
    public static TouchSourceStatus nativeStatus() {
        final TouchSourceStatus published = sNativeStatus;
        return published != null ? published : selection().nativeStatus();
    }

    /** The privileged (Shizuku) reader's status; see the seam note on {@link TouchSourceSelector}. */
    @NonNull
    public static TouchSourceStatus shizukuStatus() {
        return selection().shizukuStatus();
    }

    static void publishNativeStatus(@Nullable TouchSourceStatus status) {
        sNativeStatus = status;
    }
}

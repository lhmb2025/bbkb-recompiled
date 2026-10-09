package dev.bbkb.ime.core.device.touch;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import dev.bbkb.ime.core.device.config.model.TouchKeypadConfig;
import dev.bbkb.ime.core.device.detection.KeyboardDeviceScanner;
import dev.bbkb.ime.core.device.profile.DeviceProfile;
import dev.bbkb.ime.core.device.touch.shizuku.ShizukuTouchEngine;

/**
 * The plain read-only API over the keypad touch sources, for whatever needs to show or decide on
 * their state outside the IME (the Touch surface helper screen and its two link rows, a
 * diagnostics dump): which source the active profile selects, the profile's pin, and each
 * source's live status.
 *
 * <p>Process-wide: the IME's {@link NativeTouchSource} publishes whether it is attached right now,
 * and the Shizuku reader's status is the engine's own, which the IME and the settings activity
 * share because they share the process.
 */
public final class KeypadTouchSources {

    private static volatile TouchSourceStatus sNativeStatus;
    private static volatile ShizukuTouchEngineFacade sShizukuEngine = ShizukuTouchEngineFacade.REAL;

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

    /** Whether the active profile describes a touch surface over its keys. */
    public static boolean declaresTouchKeypad() {
        return DeviceProfile.current().declaresTouchKeypad();
    }

    /**
     * The native source's status. The selection says whether it is chosen and whether the OS has
     * the pad; the IME adds only whether it is attached to a shown window right now (ACTIVE), so
     * a pad enumerated since the IME last published is seen at once.
     */
    @NonNull
    public static TouchSourceStatus nativeStatus() {
        final TouchSourceStatus fromSelection = selection().nativeStatus();
        final TouchSourceStatus published = sNativeStatus;
        if (published != null && published.isActive()
                && fromSelection.state == TouchSourceStatus.State.IDLE) {
            return published;
        }
        return fromSelection;
    }

    /** The Shizuku reader's status: its live state when it is the choice, else why it is not. */
    @NonNull
    public static TouchSourceStatus shizukuStatus() {
        return selection().shizukuStatus(ShizukuTouchSource.statusOf(sShizukuEngine.status()));
    }

    /** The status of whichever source is chosen; the selection's own reason when none is. */
    @NonNull
    public static TouchSourceStatus activeStatus() {
        final TouchSourceSelector.Selection selection = selection();
        switch (selection.choice) {
            case NATIVE: return nativeStatus();
            case SHIZUKU: return shizukuStatus();
            default: return selection.nativeStatus();
        }
    }

    /**
     * Bring the live state up to date for a screen that is about to show it: re-scan for the pad
     * when the native route applies (returning from the Scroll assistant settings), and re-read
     * Shizuku's state when the reader is the choice (an install or a start sends no event).
     * Touches nothing on a device whose profile declares no pad.
     */
    public static void refresh(@NonNull Context context) {
        final TouchSourceSelector.Selection selection = selection();
        if (selection.preference == null) return;
        if (selection.nativeRouteApplies) {
            KeyboardDeviceScanner.getInstance().rescanTouchKeypad();
        }
        if (selection().choice == TouchSourceSelector.Choice.SHIZUKU) {
            sShizukuEngine.refreshStatus(context);
        }
    }

    /** The engine the Shizuku source runs on and the settings act through. */
    @NonNull
    public static ShizukuTouchEngineFacade shizukuEngine() {
        return sShizukuEngine;
    }

    /** Hear the Shizuku reader's status changes (the settings, while a screen shows them). */
    public static void addShizukuListener(@NonNull Context context,
                                          @NonNull ShizukuTouchEngine.StatusListener listener) {
        sShizukuEngine.addStatusListener(context, listener);
    }

    public static void removeShizukuListener(@NonNull ShizukuTouchEngine.StatusListener listener) {
        sShizukuEngine.removeStatusListener(listener);
    }

    static void publishNativeStatus(@Nullable TouchSourceStatus status) {
        sNativeStatus = status;
    }

    /** Run everything on {@code engine} instead of the real one; null puts the real one back. */
    @VisibleForTesting
    public static void useShizukuEngineForTest(@Nullable ShizukuTouchEngineFacade engine) {
        sShizukuEngine = engine != null ? engine : ShizukuTouchEngineFacade.REAL;
    }
}

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
 * Read-only, process-wide view of the keypad touch sources for code outside the IME (Touch surface
 * screen, diagnostics). The IME's {@link NativeTouchSource} publishes its attach state; the Shizuku
 * status is the engine's own, shared because IME and settings share the process.
 */
public final class KeypadTouchSources {

    private static volatile TouchSourceStatus sNativeStatus;
    private static volatile ShizukuTouchEngineFacade sShizukuEngine = ShizukuTouchEngineFacade.REAL;

    private KeypadTouchSources() {}

    @NonNull
    public static TouchSourceSelector.Selection selection() {
        return DeviceProfile.current().getTouchSourceSelection();
    }

    /** Null when the profile declares no pad. */
    @Nullable
    public static TouchKeypadConfig.SourcePreference sourcePreference() {
        return selection().preference;
    }

    public static boolean declaresTouchKeypad() {
        return DeviceProfile.current().declaresTouchKeypad();
    }

    /**
     * The selection decides chosen / pad enumerated; the IME's published status adds only ACTIVE,
     * so a pad enumerated since the last publish is seen at once.
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

    @NonNull
    public static TouchSourceStatus shizukuStatus() {
        return selection().shizukuStatus(ShizukuTouchSource.statusOf(sShizukuEngine.status()));
    }

    /** The chosen source's status, or the selection's reason when none is. */
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
     * Before a screen shows the state: re-scan for the pad when the native route applies, and re-read
     * Shizuku's state when it is chosen (an install or a start sends no event). No-op with no pad.
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

    @NonNull
    public static ShizukuTouchEngineFacade shizukuEngine() {
        return sShizukuEngine;
    }

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

    /** Null restores the real engine. */
    @VisibleForTesting
    public static void useShizukuEngineForTest(@Nullable ShizukuTouchEngineFacade engine) {
        sShizukuEngine = engine != null ? engine : ShizukuTouchEngineFacade.REAL;
    }
}

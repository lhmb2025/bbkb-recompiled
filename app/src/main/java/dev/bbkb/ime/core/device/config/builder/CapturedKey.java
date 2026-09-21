package dev.bbkb.ime.core.device.config.builder;

import androidx.annotation.NonNull;

import java.util.Locale;

/**
 * What one {@link CaptureStep} produced: the raw hardware identity of the key the user actually
 * pressed, or the fact that they skipped it.
 *
 * <p>{@link #repeatIntervalMs} is the auto-repeat cadence measured while the key was held — the
 * mean gap between the repeat events the kernel sent. It is diagnostic only: the config schema
 * has nowhere to put it, so the exporter writes it into the header comment, where it tells whoever
 * reads the file later whether this ROM repeats at all (a key that never repeats is usually one
 * the firmware treats as a function key rather than a character key).
 */
public final class CapturedKey {

    /** No cadence was measured: the key was tapped rather than held, or it does not repeat. */
    public static final int NO_REPEAT = -1;

    @NonNull public final CaptureStep step;
    public final boolean skipped;
    public final int scanCode;
    public final int keyCode;
    public final int deviceId;
    public final int repeatIntervalMs;
    /** Number of auto-repeat events seen while the key was held. */
    public final int repeatCount;

    private CapturedKey(@NonNull CaptureStep step, boolean skipped, int scanCode, int keyCode,
                        int deviceId, int repeatIntervalMs, int repeatCount) {
        this.step = step;
        this.skipped = skipped;
        this.scanCode = scanCode;
        this.keyCode = keyCode;
        this.deviceId = deviceId;
        this.repeatIntervalMs = repeatIntervalMs;
        this.repeatCount = repeatCount;
    }

    public static CapturedKey of(@NonNull CaptureStep step, int scanCode, int keyCode,
                                 int deviceId, int repeatIntervalMs, int repeatCount) {
        return new CapturedKey(step, false, scanCode, keyCode, deviceId, repeatIntervalMs,
                repeatCount);
    }

    public static CapturedKey skipped(@NonNull CaptureStep step) {
        return new CapturedKey(step, true, 0, 0, -1, NO_REPEAT, 0);
    }

    /** True when this key can be written as a {@code <key>} element at all. */
    public boolean isUsable() {
        return !skipped && (scanCode > 0 || keyCode > 0);
    }

    public boolean hasRepeatCadence() {
        return repeatIntervalMs > 0;
    }

    /** Two captures collide when they name the same physical key. */
    public boolean sameKeyAs(@NonNull CapturedKey other) {
        return !skipped && !other.skipped
                && scanCode == other.scanCode && keyCode == other.keyCode;
    }

    @NonNull
    @Override
    public String toString() {
        if (skipped) {
            return step + "=skipped";
        }
        return String.format(Locale.ROOT, "%s{sc=%d, kc=%d, dev=%d, repeat=%dms x%d}",
                step, scanCode, keyCode, deviceId, repeatIntervalMs, repeatCount);
    }
}

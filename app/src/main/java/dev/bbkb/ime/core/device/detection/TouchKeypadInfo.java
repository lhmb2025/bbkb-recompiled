package dev.bbkb.ime.core.device.detection;

import android.view.InputDevice;
import android.view.MotionEvent;

import androidx.annotation.Nullable;

/**
 * Immutable information about capacitive keyboard touch capabilities.
 *
 * Capacitive keyboards (like BlackBerry KEY2) have touch-sensitive keys
 * supporting gestures in addition to physical key presses.
 *
 * Detection criteria:
 * - InputDevice.KEYBOARD_TYPE_ALPHABETIC (value 2)
 * - Has SOURCE_TOUCHPAD (0x100000) in sources
 * - Has valid X and Y motion ranges
 */
public final class TouchKeypadInfo {

    public static final int SOURCE_TOUCH_KEYBOARD =
        InputDevice.SOURCE_KEYBOARD | InputDevice.SOURCE_TOUCHPAD;

    private final int deviceId;
    private final float resolution;         // mm per pixel
    private final float xRangeMax;
    private final float yRangeMax;

    private TouchKeypadInfo(int deviceId, float resolution, float xRangeMax, float yRangeMax) {
        this.deviceId = deviceId;
        this.resolution = resolution;
        this.xRangeMax = xRangeMax;
        this.yRangeMax = yRangeMax;
    }

    /**
     * Create from InputDevice, or null if not a touch keyboard.
     */
    @Nullable
    public static TouchKeypadInfo fromInputDevice(InputDevice device) {
        if (device != null && device.getKeyboardType() != InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
            return null;
        }
        return from(device, SOURCE_TOUCH_KEYBOARD);
    }

    /**
     * Create from a standalone SOURCE_TOUCHPAD device that is NOT an alphabetic keyboard.
     * Used to detect CKB touch overlays reported as a separate input device (e.g. BlackBerry Key2).
     */
    @Nullable
    public static TouchKeypadInfo fromTouchpadDevice(InputDevice device) {
        return from(device, InputDevice.SOURCE_TOUCHPAD);
    }

    /**
     * Audit W1-F: {@link #fromInputDevice} and {@link #fromTouchpadDevice} were byte-identical
     * apart from the alphabetic-keyboard guard and the required source mask.
     */
    @Nullable
    private static TouchKeypadInfo from(InputDevice device, int requiredSources) {
        if (device == null || device.isVirtual()) return null;
        if ((device.getSources() & requiredSources) != requiredSources) return null;

        InputDevice.MotionRange xRange = device.getMotionRange(MotionEvent.AXIS_X);
        InputDevice.MotionRange yRange = device.getMotionRange(MotionEvent.AXIS_Y);
        if (xRange == null || yRange == null) return null;

        float avgResolution = ((xRange.getResolution() + yRange.getResolution()) * 0.15875f) / 2.0f;

        return new TouchKeypadInfo(
            device.getId(),
            avgResolution,
            xRange.getMax(),
            yRange.getMax()
        );
    }

    public int getDeviceId() { return deviceId; }
    public float getResolution() { return resolution; }
    public float getYRangeMax() { return yRangeMax; }

    @Override
    public String toString() {
        return "TouchKeypadInfo{deviceId=" + deviceId +
            ", resolution=" + resolution +
            ", xMax=" + xRangeMax +
            ", yMax=" + yRangeMax + "}";
    }
}

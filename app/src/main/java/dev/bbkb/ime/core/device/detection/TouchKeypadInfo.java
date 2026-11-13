package dev.bbkb.ime.core.device.detection;

import android.view.InputDevice;
import android.view.MotionEvent;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

/**
 * Immutable facts about a capacitive touch keypad: a non-virtual device with SOURCE_TOUCHPAD and
 * X/Y motion ranges, or simply the device a config names ({@link #fromNamedDevice}).
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

    /** Null unless an alphabetic keyboard that is also a touchpad. */
    @Nullable
    public static TouchKeypadInfo fromInputDevice(InputDevice device) {
        if (device != null && device.getKeyboardType() != InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
            return null;
        }
        return from(device, SOURCE_TOUCH_KEYBOARD);
    }

    /** A standalone SOURCE_TOUCHPAD device, such as the KEY2's separate CKB overlay. */
    @Nullable
    public static TouchKeypadInfo fromTouchpadDevice(InputDevice device) {
        return from(device, InputDevice.SOURCE_TOUCHPAD);
    }

    /**
     * The name is the evidence, so no source class or ranges are required (the Titan 2's
     * {@code touchPad}). With no ranges the extents are 0 and {@code TouchKeypadGeometry} uses the profile.
     */
    @Nullable
    public static TouchKeypadInfo fromNamedDevice(InputDevice device) {
        if (device == null || device.isVirtual()) return null;
        final TouchKeypadInfo ranged = from(device, 0);
        return ranged != null ? ranged : new TouchKeypadInfo(device.getId(), 0f, 0f, 0f);
    }

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

    /** A pad known only from extents an evdev reader measured (EVIOCGABS), under its synthetic id; resolution 0. */
    public static TouchKeypadInfo measured(int deviceId, float xRangeMax, float yRangeMax) {
        return new TouchKeypadInfo(deviceId, 0f, xRangeMax, yRangeMax);
    }

    @VisibleForTesting
    public static TouchKeypadInfo forTest(int deviceId, float resolution, float xRangeMax, float yRangeMax) {
        return new TouchKeypadInfo(deviceId, resolution, xRangeMax, yRangeMax);
    }

    public int getDeviceId() { return deviceId; }
    public float getResolution() { return resolution; }
    public float getXRangeMax() { return xRangeMax; }
    public float getYRangeMax() { return yRangeMax; }

    /** Same device and facts, so a rescan that found this changed nothing. */
    public boolean sameAs(@Nullable TouchKeypadInfo other) {
        return other != null
                && other.deviceId == deviceId
                && Float.compare(other.resolution, resolution) == 0
                && Float.compare(other.xRangeMax, xRangeMax) == 0
                && Float.compare(other.yRangeMax, yRangeMax) == 0;
    }

    @Override
    public String toString() {
        return "TouchKeypadInfo{deviceId=" + deviceId +
            ", resolution=" + resolution +
            ", xMax=" + xRangeMax +
            ", yMax=" + yRangeMax + "}";
    }
}

package dev.bbkb.ime.core.device.detection;

import android.view.InputDevice;

import androidx.annotation.Nullable;

/**
 * Immutable information about a connected keyboard device.
 */
public final class KeyboardDeviceInfo {

    private final int deviceId;
    private final String name;
    private final int keyboardType;  // InputDevice.KEYBOARD_TYPE_*
    private final int sources;
    private final boolean isVirtual;
    private final TouchKeypadInfo touchKeypadInfo;  // null if no touch capability

    private KeyboardDeviceInfo(int deviceId, String name, int keyboardType, int sources,
                               boolean isVirtual, TouchKeypadInfo touchKeypadInfo) {
        this.deviceId = deviceId;
        this.name = name;
        this.keyboardType = keyboardType;
        this.sources = sources;
        this.isVirtual = isVirtual;
        this.touchKeypadInfo = touchKeypadInfo;
    }

    /** Factory method from InputDevice */
    @Nullable
    public static KeyboardDeviceInfo fromInputDevice(InputDevice device) {
        if (device == null) return null;

        TouchKeypadInfo touchInfo = TouchKeypadInfo.fromInputDevice(device);

        return new KeyboardDeviceInfo(
            device.getId(),
            device.getName(),
            device.getKeyboardType(),
            device.getSources(),
            device.isVirtual(),
            touchInfo
        );
    }

    // Derived properties
    public boolean isAlphabetic() {
        return keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC;
    }

    public boolean isPhysicalKeyboard() {
        return !isVirtual && isAlphabetic() &&
               (sources & InputDevice.SOURCE_KEYBOARD) != 0;
    }

    public boolean isBlackBerryBuiltIn() {
        return deviceId == 0 && HardwareProbe.isBlackBerryDevice();
    }

    public boolean hasCapacitiveTouch() {
        return touchKeypadInfo != null;
    }

    @Nullable
    public TouchKeypadInfo getTouchKeypadInfo() {
        return touchKeypadInfo;
    }

    // Getters
    public int getDeviceId() { return deviceId; }
    public String getName() { return name; }

    @Override
    public String toString() {
        return "KeyboardDeviceInfo{id=" + deviceId +
            ", name='" + name + '\'' +
            ", type=" + keyboardType +
            ", physical=" + isPhysicalKeyboard() +
            ", touch=" + hasCapacitiveTouch() + "}";
    }
}

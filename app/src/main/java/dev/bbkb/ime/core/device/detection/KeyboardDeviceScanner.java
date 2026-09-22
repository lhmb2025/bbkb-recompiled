package dev.bbkb.ime.core.device.detection;

import android.util.SparseArray;
import android.view.InputDevice;

import androidx.annotation.Nullable;

import dev.bbkb.ime.core.shared.Logger;

import dev.bbkb.ime.core.shared.InputPathDebug;

/**
 * Scans for connected keyboard devices.
 * Singleton with caching for performance.
 */
public final class KeyboardDeviceScanner {

    private static KeyboardDeviceScanner instance;

    // SparseArray, not a boxed-Integer map: these are input device ids. The scan results feed
    // DeviceCapabilities.detect() and therefore isFromTouchKeypad(), the CKB swipe gate.
    private final SparseArray<KeyboardDeviceInfo> cachedDevices = new SparseArray<>(4);
    private volatile KeyboardDeviceInfo primaryKeyboard = null;
    private volatile TouchKeypadInfo primaryTouchKeypad = null;

    public static synchronized KeyboardDeviceScanner getInstance() {
        if (instance == null) {
            instance = new KeyboardDeviceScanner();
        }
        return instance;
    }

    private KeyboardDeviceScanner() {
        scanDevices();
    }

    @Nullable
    public KeyboardDeviceInfo getPrimaryKeyboard() {
        return primaryKeyboard;
    }

    public synchronized boolean hasExternalKeyboard() {
        for (int i = 0; i < cachedDevices.size(); i++) {
            KeyboardDeviceInfo d = cachedDevices.valueAt(i);
            if (d.isPhysicalKeyboard() && !d.isBlackBerryBuiltIn()) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    public TouchKeypadInfo getPrimaryTouchKeypad() {
        return primaryTouchKeypad;
    }

    public synchronized void refresh() {
        cachedDevices.clear();
        primaryKeyboard = null;
        primaryTouchKeypad = null;
        scanDevices();
    }

    private synchronized void scanDevices() {
        int[] deviceIds = InputDevice.getDeviceIds();
        if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "KeyboardDeviceScanner: scanning " + deviceIds.length + " input devices");

        // Pass 1: look for combined keyboard+touchpad devices (standard detection)
        for (int deviceId : deviceIds) {
            InputDevice device = InputDevice.getDevice(deviceId);
            if (device != null) {
                if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "KeyboardDeviceScanner: device[" + deviceId + "] name='" + device.getName()
                        + "' sources=0x" + Integer.toHexString(device.getSources())
                        + " kbType=" + device.getKeyboardType()
                        + " virtual=" + device.isVirtual()
                        + " hasTouchpad=" + ((device.getSources() & InputDevice.SOURCE_TOUCHPAD) != 0));
                KeyboardDeviceInfo info = KeyboardDeviceInfo.fromInputDevice(device);
                if (info != null && info.isPhysicalKeyboard()) {
                    if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "KeyboardDeviceScanner: PHYSICAL keyboard: id=" + deviceId
                            + " name='" + device.getName() + "' hasCapacitiveTouch=" + info.hasCapacitiveTouch());
                    cachedDevices.put(deviceId, info);

                    if (primaryKeyboard == null) {
                        primaryKeyboard = info;
                    }
                    if (primaryTouchKeypad == null && info.hasCapacitiveTouch()) {
                        primaryTouchKeypad = info.getTouchKeypadInfo();
                        if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "KeyboardDeviceScanner: TOUCH KEYPAD (combined): deviceId=" + deviceId + " " + primaryTouchKeypad);
                    }
                }
            }
        }

        // Pass 2: if no combined device found, look for standalone SOURCE_TOUCHPAD devices
        // (e.g. BlackBerry Key2 reports its CKB overlay as a separate touchpad-only device)
        if (primaryTouchKeypad == null) {
            if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "KeyboardDeviceScanner: no combined keyboard+touchpad found, scanning for standalone touchpad devices...");
            for (int deviceId : deviceIds) {
                InputDevice device = InputDevice.getDevice(deviceId);
                if (device == null || device.isVirtual()) continue;
                boolean hasTouchpad = (device.getSources() & InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD;
                if (hasTouchpad) {
                    if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "KeyboardDeviceScanner: standalone TOUCHPAD candidate: id=" + deviceId
                            + " name='" + device.getName() + "' sources=0x" + Integer.toHexString(device.getSources()));
                    TouchKeypadInfo touchInfo = TouchKeypadInfo.fromTouchpadDevice(device);
                    if (touchInfo != null) {
                        primaryTouchKeypad = touchInfo;
                        if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "KeyboardDeviceScanner: TOUCH KEYPAD (standalone): " + touchInfo);
                        break;
                    } else {
                        if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "KeyboardDeviceScanner: standalone TOUCHPAD rejected (no valid motion ranges): id=" + deviceId);
                    }
                }
            }
        }

        if (primaryTouchKeypad == null) {
            if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "KeyboardDeviceScanner: NO touch keypad found -- isFromTouchKeypad() will always return false unless forceTouchKeypad overrides device ID lookup");
        } else {
            if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "KeyboardDeviceScanner: scan complete, touchKeypadDeviceId=" + primaryTouchKeypad.getDeviceId());
        }
    }
}

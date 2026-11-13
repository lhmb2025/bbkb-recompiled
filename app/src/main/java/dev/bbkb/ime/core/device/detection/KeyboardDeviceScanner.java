package dev.bbkb.ime.core.device.detection;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.view.InputDevice;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import dev.bbkb.ime.core.device.config.model.DeviceMatchCriteria;
import dev.bbkb.ime.core.shared.Logger;

import dev.bbkb.ime.core.shared.InputPathDebug;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Cached singleton scan of keyboard and touch keypad devices. Only the touch keypad half is kept
 * live by {@link #registerDeviceListener} (the Titan 2's pad appears only once Scroll assistant is
 * on); see {@link #onInputDevicesChanged()} for why the keyboard half is not.
 */
public final class KeyboardDeviceScanner {

    private static final String TAG = "KeyboardDeviceScanner";

    /** The platform, or a fake in a test. */
    public interface InputDevices {
        int[] ids();

        @Nullable
        InputDevice get(int id);
    }

    public interface TouchKeypadListener {
        void onTouchKeypadChanged(@Nullable TouchKeypadInfo touchKeypad);
    }

    private static final InputDevices PLATFORM = new InputDevices() {
        @Override
        public int[] ids() {
            final int[] ids = InputDevice.getDeviceIds();
            return ids != null ? ids : new int[0];
        }

        @Override
        public InputDevice get(int id) {
            return InputDevice.getDevice(id);
        }
    };

    private static KeyboardDeviceScanner instance;

    private static volatile InputDevices sDevices = PLATFORM;

    private final SparseArray<KeyboardDeviceInfo> cachedDevices = new SparseArray<>(4);
    private volatile KeyboardDeviceInfo primaryKeyboard = null;
    private volatile TouchKeypadInfo primaryTouchKeypad = null;

    private volatile DeviceMatchCriteria.Rule declaredTouchKeypadName = null;

    private final CopyOnWriteArrayList<TouchKeypadListener> touchKeypadListeners =
            new CopyOnWriteArrayList<>();

    private boolean deviceListenerRegistered = false;

    private final InputManager.InputDeviceListener inputDeviceListener =
            new InputManager.InputDeviceListener() {
                @Override public void onInputDeviceAdded(int deviceId)   { onInputDevicesChanged(); }
                @Override public void onInputDeviceRemoved(int deviceId) { onInputDevicesChanged(); }
                @Override public void onInputDeviceChanged(int deviceId) { onInputDevicesChanged(); }
            };

    public static synchronized KeyboardDeviceScanner getInstance() {
        if (instance == null) {
            instance = new KeyboardDeviceScanner();
        }
        return instance;
    }

    private KeyboardDeviceScanner() {
        scanDevices();
    }

    private static InputDevices devices() {
        return sDevices;
    }

    /** Read through the scan's (fakeable) source. */
    @Nullable
    public static InputDevice inputDevice(int deviceId) {
        return devices().get(deviceId);
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

    public void addTouchKeypadListener(TouchKeypadListener listener) {
        if (listener != null) touchKeypadListeners.addIfAbsent(listener);
    }

    public void removeTouchKeypadListener(TouchKeypadListener listener) {
        touchKeypadListeners.remove(listener);
    }

    /**
     * The config's {@code <touch-keypad><input-device/>} name, or null. A named device is the pad
     * whatever sources it reports. A different name re-scans; the same one does nothing.
     */
    public void setDeclaredTouchKeypadName(@Nullable DeviceMatchCriteria.Rule name) {
        synchronized (this) {
            if (String.valueOf(name).equals(String.valueOf(declaredTouchKeypadName))) return;
            declaredTouchKeypadName = name;
        }
        rescanTouchKeypad();
    }

    /** Idempotent; the listener runs on the main thread. */
    public void registerDeviceListener(Context context) {
        synchronized (this) {
            if (deviceListenerRegistered || context == null) return;
            deviceListenerRegistered = true;
        }
        final InputManager inputManager =
                (InputManager) context.getApplicationContext().getSystemService(Context.INPUT_SERVICE);
        if (inputManager == null) {
            synchronized (this) {
                deviceListenerRegistered = false;
            }
            return;
        }
        inputManager.registerInputDeviceListener(inputDeviceListener, new Handler(Looper.getMainLooper()));
    }

    /**
     * Re-scans the touch keypad only. Android reports the built-in keyboard "changed" on every
     * subtype switch, and resetting key config here empties the scancode-role resolver; key config
     * has its own listener in {@code DeviceInputResolver}.
     */
    @VisibleForTesting
    void onInputDevicesChanged() {
        rescanTouchKeypad();
    }

    /**
     * Leaves the keyboard cache alone.
     *
     * @return true when the touch keypad changed (listeners are told)
     */
    public boolean rescanTouchKeypad() {
        final TouchKeypadInfo found;
        final boolean changed;
        synchronized (this) {
            found = findTouchKeypad(devices().ids());
            final TouchKeypadInfo previous = primaryTouchKeypad;
            changed = found == null ? previous != null : !found.sameAs(previous);
            if (changed) primaryTouchKeypad = found;
        }
        if (changed) {
            Logger.info(TAG, "touch keypad changed: " + found);
            for (TouchKeypadListener listener : touchKeypadListeners) {
                listener.onTouchKeypadChanged(found);
            }
        }
        return changed;
    }

    private synchronized void scanDevices() {
        int[] deviceIds = devices().ids();
        if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "KeyboardDeviceScanner: scanning " + deviceIds.length + " input devices");

        for (int deviceId : deviceIds) {
            InputDevice device = devices().get(deviceId);
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
                }
            }
        }

        primaryTouchKeypad = findTouchKeypad(deviceIds);

        if (primaryTouchKeypad == null) {
            if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "KeyboardDeviceScanner: NO touch keypad found -- isFromTouchKeypad() will always return false unless forceTouchKeypad overrides device ID lookup");
        } else {
            if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "KeyboardDeviceScanner: scan complete, touchKeypadDeviceId=" + primaryTouchKeypad.getDeviceId());
        }
    }

    /**
     * The touch keypad among {@code deviceIds}, or null. In order:
     * <ol>
     *   <li>the device the active config names, if present;</li>
     *   <li>the first physical keyboard that is also a touchpad with X/Y ranges;</li>
     *   <li>the first standalone non-virtual {@code SOURCE_TOUCHPAD} with X/Y ranges (KEY2
     *       {@code touch_keypad}).</li>
     * </ol>
     */
    @Nullable
    private TouchKeypadInfo findTouchKeypad(int[] deviceIds) {
        final DeviceMatchCriteria.Rule name = declaredTouchKeypadName;
        if (name != null) {
            for (int deviceId : deviceIds) {
                InputDevice device = devices().get(deviceId);
                if (device == null || device.isVirtual() || !name.matches(device.getName())) continue;
                TouchKeypadInfo named = TouchKeypadInfo.fromNamedDevice(device);
                if (named != null) {
                    if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "KeyboardDeviceScanner: TOUCH KEYPAD (named " + name + "): " + named);
                    return named;
                }
            }
        }

        for (int deviceId : deviceIds) {
            InputDevice device = devices().get(deviceId);
            if (device == null) continue;
            KeyboardDeviceInfo info = KeyboardDeviceInfo.fromInputDevice(device);
            if (info != null && info.isPhysicalKeyboard() && info.hasCapacitiveTouch()) {
                if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "KeyboardDeviceScanner: TOUCH KEYPAD (combined): deviceId=" + deviceId + " " + info.getTouchKeypadInfo());
                return info.getTouchKeypadInfo();
            }
        }

        if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "KeyboardDeviceScanner: no combined keyboard+touchpad found, scanning for standalone touchpad devices...");
        for (int deviceId : deviceIds) {
            InputDevice device = devices().get(deviceId);
            if (device == null || device.isVirtual()) continue;
            boolean hasTouchpad = (device.getSources() & InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD;
            if (hasTouchpad) {
                if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "KeyboardDeviceScanner: standalone TOUCHPAD candidate: id=" + deviceId
                        + " name='" + device.getName() + "' sources=0x" + Integer.toHexString(device.getSources()));
                TouchKeypadInfo touchInfo = TouchKeypadInfo.fromTouchpadDevice(device);
                if (touchInfo != null) {
                    if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "KeyboardDeviceScanner: TOUCH KEYPAD (standalone): " + touchInfo);
                    return touchInfo;
                } else {
                    if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "KeyboardDeviceScanner: standalone TOUCHPAD rejected (no valid motion ranges): id=" + deviceId);
                }
            }
        }
        return null;
    }

    /** Drops the singleton so the next {@link #getInstance()} scans {@code devices}; null restores the platform. */
    @VisibleForTesting
    public static synchronized void resetForTest(@Nullable InputDevices devices) {
        sDevices = devices != null ? devices : PLATFORM;
        instance = null;
    }

    @VisibleForTesting
    public InputManager.InputDeviceListener inputDeviceListenerForTest() {
        return inputDeviceListener;
    }
}

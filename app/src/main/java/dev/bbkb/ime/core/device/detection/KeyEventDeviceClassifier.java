package dev.bbkb.ime.core.device.detection;

import android.content.Context;
import android.util.SparseBooleanArray;
import android.view.InputDevice;
import android.view.KeyEvent;

import dev.bbkb.ime.core.device.config.model.DeviceInputMapping;
import dev.bbkb.ime.core.device.config.resolver.DeviceInputResolver;
import dev.bbkb.ime.core.device.profile.DeviceProfile;
import dev.bbkb.ime.core.shared.Logger;


/**
 * Whether a KeyEvent comes from a physical keyboard, in order: cache, XML force-pkb-device via
 * DeviceInputResolver, SOURCE_KEYBOARD + KEYBOARD_TYPE_ALPHABETIC, device id 0 on a PKB profile.
 */
public final class KeyEventDeviceClassifier {

    private static final String TAG = "KeyEventDeviceClassifier";

    private static KeyEventDeviceClassifier instance;

    // Unboxed: isPhysicalKeyboardEvent runs about twenty times per key event.
    private final SparseBooleanArray deviceIsPhysical = new SparseBooleanArray(4);

    public static synchronized KeyEventDeviceClassifier getInstance() {
        if (instance == null) {
            instance = new KeyEventDeviceClassifier();
        }
        return instance;
    }

    private KeyEventDeviceClassifier() {}

    public boolean isPhysicalKeyboardEvent(KeyEvent event) {
        if (event == null) return false;

        int deviceId = event.getDeviceId();

        final int cached = deviceIsPhysical.indexOfKey(deviceId);
        if (cached >= 0) return deviceIsPhysical.valueAt(cached);

        // resolveInputMapping returns null for a null context, so pass the app context.
        final Context context = DeviceProfile.appContext();
        DeviceInputMapping mapping = (context == null)
                ? null : DeviceInputResolver.resolveInputMapping(context, deviceId);
        if (mapping != null && mapping.forcePkbDevice) {
            deviceIsPhysical.put(deviceId, true);
            Logger.debug(TAG, "Device " + deviceId + " marked as PKB via XML override");
            return true;
        }

        InputDevice device = InputDevice.getDevice(deviceId);
        if (device != null) {
            boolean hasHardwareKeyboard = (device.getSources() & InputDevice.SOURCE_KEYBOARD) != 0;
            boolean isAlphabetic = device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC;
            boolean isNotVirtual = !device.isVirtual();

            if (hasHardwareKeyboard && isAlphabetic && isNotVirtual) {
                deviceIsPhysical.put(deviceId, true);
                Logger.debug(TAG, "Device " + deviceId + " (" + device.getName() + ") detected as PKB via characteristics");
                return true;
            }
        }

        if (deviceId == 0 && DeviceProfile.current().hasPhysicalKeyboard()) {
            deviceIsPhysical.put(deviceId, true);
            return true;
        }

        deviceIsPhysical.put(deviceId, false);
        return false;
    }

    /** Call when the device config changes. */
    public void clearCache() {
        deviceIsPhysical.clear();
    }
}

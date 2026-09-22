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
 * Classifies whether a KeyEvent originates from a physical keyboard.
 *
 * Detection priority:
 * 1. Cached known devices (fast path)
 * 2. XML override (force-pkb-device) via DeviceInputResolver
 * 3. Device characteristics (InputDevice.SOURCE_KEYBOARD + KEYBOARD_TYPE_ALPHABETIC)
 * 4. Legacy device ID 0 check for devices with built-in physical keyboards
 */
public final class KeyEventDeviceClassifier {

    private static final String TAG = "KeyEventDeviceClassifier";

    private static KeyEventDeviceClassifier instance;

    // One id -> isPhysical map, not two HashSet<Integer>: isPhysicalKeyboardEvent is called
    // roughly twenty times per key event across KeyEventProcessor, KeyEventConverter and
    // PhysicalKeyboardStateTracker, and each boxed lookup was a fresh Integer allocation and
    // hash of the same device id.
    private final SparseBooleanArray deviceIsPhysical = new SparseBooleanArray(4);

    public static synchronized KeyEventDeviceClassifier getInstance() {
        if (instance == null) {
            instance = new KeyEventDeviceClassifier();
        }
        return instance;
    }

    private KeyEventDeviceClassifier() {}

    /**
     * Determine if a KeyEvent comes from a physical keyboard.
     */
    public boolean isPhysicalKeyboardEvent(KeyEvent event) {
        if (event == null) return false;

        int deviceId = event.getDeviceId();

        // Check cache first
        final int cached = deviceIsPhysical.indexOfKey(deviceId);
        if (cached >= 0) return deviceIsPhysical.valueAt(cached);

        // Check XML override via per-device resolver. This used to pass a literal null
        // context, and resolveInputMapping returns null immediately for a null context -- so
        // priority 2 of the detection order documented above was permanently dead and
        // <device-type>PKB</device-type> / force-pkb-device could never take effect here.
        final Context context = DeviceProfile.appContext();
        DeviceInputMapping mapping = (context == null)
                ? null : DeviceInputResolver.resolveInputMapping(context, deviceId);
        if (mapping != null && mapping.forcePkbDevice) {
            deviceIsPhysical.put(deviceId, true);
            Logger.debug(TAG, "Device " + deviceId + " marked as PKB via XML override");
            return true;
        }

        // Check device characteristics
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

        // Legacy device ID 0 check: built-in physical keyboard
        if (deviceId == 0 && DeviceProfile.current().hasPhysicalKeyboard()) {
            deviceIsPhysical.put(deviceId, true);
            return true;
        }

        // Cache as non-physical device
        deviceIsPhysical.put(deviceId, false);
        return false;
    }

    /**
     * Clear cached device classifications (e.g., when device config changes).
     */
    public void clearCache() {
        deviceIsPhysical.clear();
    }
}

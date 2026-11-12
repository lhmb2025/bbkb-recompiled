package dev.bbkb.ime.core.device.config.model;

import android.os.Build;
import android.view.InputDevice;

import java.util.ArrayList;
import java.util.List;

/**
 * Container class for device input mapping configuration.
 */
public class DeviceInputConfig {

    public List<DeviceInputMapping> mappings;

    /**
     * True when the user picked this configuration on the Device configuration screen, as
     * opposed to automatic detection finding it. A configuration picked by hand is the phone's
     * profile even when its {@code <match>} rules fail here: that is what picking it is for (a
     * ROM that renames the keypad's driver, a unit the rules did not foresee). Set by
     * {@code CustomDeviceConfigManager}; false for a configuration nobody chose.
     */
    public boolean chosenByUser;

    public DeviceInputConfig() {
        this.mappings = new ArrayList<>();
    }

    /**
     * The profile a hand-picked configuration gives the phone when nothing matched: its first
     * {@code <device>}. Null when the configuration was not picked by hand, holds no device, or
     * identifies its keyboard by USB or Bluetooth ids (that one describes a keyboard that plugs
     * in, and applies only while it is attached).
     */
    public DeviceInputMapping chosenPhoneProfile() {
        if (!chosenByUser || mappings.isEmpty()) {
            return null;
        }
        final DeviceInputMapping first = mappings.get(0);
        final boolean external = first.matchCriteria != null
                && first.matchCriteria.hasVendorOrProductRule();
        return external ? null : first;
    }

    /**
     * Find a mapping for the specified device name. A config whose {@code <match>} sets a
     * {@code vendor-id} or {@code product-id} rule never matches through this form; callers with
     * the keyboard's {@link InputDevice} in hand use {@link #findMappingForInputDevice}.
     * @param deviceName The name of the input device
     * @return DeviceInputMapping if found, null otherwise
     */
    public DeviceInputMapping findMappingForDevice(String deviceName) {
        return findMappingForDevice(deviceName, null, null);
    }

    /**
     * Find a mapping for a connected keyboard, matching its name and its vendor and product ids
     * (as {@link DeviceMatchCriteria#inputDeviceId hex}). This is what makes {@code vendor-id} and
     * {@code product-id} rules matchable: every production caller has the InputDevice.
     *
     * <p>An external keyboard (USB, Bluetooth) is matched only by a profile that
     * {@link DeviceInputMapping#namesInputDevice names the keyboard itself}. A built-in device
     * that no profile matches gets {@link #chosenPhoneProfile()}, when there is one. A profile matched
     * on the phone's build alone (the KEY2's, the Titans') describes that phone's own keys; it
     * used to match every keyboard attached to the phone as well, so a Bluetooth keyboard's 0
     * key was the KEY2's mic key.
     */
    public DeviceInputMapping findMappingForInputDevice(InputDevice device) {
        if (device == null) {
            return null;
        }
        final String deviceName = device.getName();
        if (deviceName == null || deviceName.isEmpty()) {
            return null;
        }
        final String vendorId = DeviceMatchCriteria.inputDeviceId(device.getVendorId());
        final String productId = DeviceMatchCriteria.inputDeviceId(device.getProductId());
        final boolean external = isExternal(device);
        for (DeviceInputMapping mapping : mappings) {
            if (external && !mapping.namesInputDevice()) {
                continue;
            }
            if (mapping.matchesDevice(deviceName, vendorId, productId)) {
                return mapping;
            }
        }
        // Nothing matched. A configuration the user picked by hand still speaks for the phone's
        // own devices, exactly as one matched on the phone's build does; never for a keyboard
        // that is plugged in.
        return external ? null : chosenPhoneProfile();
    }

    /**
     * Whether Android reports the device as external: a USB or Bluetooth device, unless its
     * input configuration says otherwise. {@code InputDevice.isExternal()} is public from
     * Android 10; before that it exists but is hidden, so it is called by reflection, and if
     * that fails the device counts as built-in, which is how every device was treated before.
     */
    public static boolean isExternal(InputDevice device) {
        if (device == null || device.isVirtual()) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return device.isExternal();
        }
        try {
            return Boolean.TRUE.equals(InputDevice.class.getMethod("isExternal").invoke(device));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Find a mapping for the specified device with full matching criteria.
     * @param deviceName The name of the input device
     * @param vendorId Optional vendor ID (hex string)
     * @param productId Optional product ID (hex string)
     * @return DeviceInputMapping if found, null otherwise
     */
    public DeviceInputMapping findMappingForDevice(String deviceName, String vendorId, String productId) {
        if (deviceName == null || deviceName.isEmpty()) {
            return null;
        }

        for (DeviceInputMapping mapping : mappings) {
            // Use the proper matchesDevice method that supports matchCriteria
            if (mapping.matchesDevice(deviceName, vendorId, productId)) {
                return mapping;
            }
        }

        return null;
    }

}

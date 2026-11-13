package dev.bbkb.ime.core.device.config.model;

import android.os.Build;
import android.view.InputDevice;

import java.util.ArrayList;
import java.util.List;

/** One device configuration file: its {@code <device>} profiles in order. */
public class DeviceInputConfig {

    public List<DeviceInputMapping> mappings;

    /**
     * Picked by hand on the Device configuration screen (set by {@code CustomDeviceConfigManager}).
     * Such a configuration is the phone's profile even when its {@code <match>} rules fail.
     */
    public boolean chosenByUser;

    public DeviceInputConfig() {
        this.mappings = new ArrayList<>();
    }

    /**
     * The first {@code <device>} of a hand-picked configuration, used when nothing matched. Null when
     * not hand-picked, empty, or matched by vendor/product id (that applies only while attached).
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

    /** Name-only lookup: vendor/product id rules never match here; use {@link #findMappingForInputDevice}. */
    public DeviceInputMapping findMappingForDevice(String deviceName) {
        return findMappingForDevice(deviceName, null, null);
    }

    /**
     * Matches a keyboard by name and vendor/product ids. An external keyboard matches only a profile
     * that {@link DeviceInputMapping#namesInputDevice names the keyboard itself} (a phone-build profile
     * describes the phone's own keys); an unmatched built-in device gets {@link #chosenPhoneProfile()}.
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
        return external ? null : chosenPhoneProfile();
    }

    /**
     * Whether Android reports the device as external (USB or Bluetooth). {@code isExternal()} is
     * hidden before Android 10, so it is called by reflection there; failure counts as built-in.
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

    /** @param vendorId hex id or null; productId likewise */
    public DeviceInputMapping findMappingForDevice(String deviceName, String vendorId, String productId) {
        if (deviceName == null || deviceName.isEmpty()) {
            return null;
        }

        for (DeviceInputMapping mapping : mappings) {
            if (mapping.matchesDevice(deviceName, vendorId, productId)) {
                return mapping;
            }
        }

        return null;
    }

}

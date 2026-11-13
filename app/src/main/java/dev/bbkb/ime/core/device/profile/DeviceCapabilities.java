package dev.bbkb.ime.core.device.profile;

import androidx.annotation.VisibleForTesting;

import dev.bbkb.ime.core.device.config.model.DeviceInputMapping;
import dev.bbkb.ime.core.device.detection.HardwareProbe;
import dev.bbkb.ime.core.device.detection.KeyboardDeviceInfo;
import dev.bbkb.ime.core.device.detection.KeyboardDeviceScanner;
import dev.bbkb.ime.core.device.detection.KeypadLayoutDetector;
import dev.bbkb.ime.core.device.detection.TouchKeypadInfo;

/** Immutable device capabilities from the hardware probe. */
public final class DeviceCapabilities {
    /** The probe's classification; {@link DeviceProfile.DeviceType} is the coarser PKB/VKB answer. */
    public enum DetectedDeviceType { PKB, VKB, HYBRID, UNKNOWN }

    private final DetectedDeviceType deviceType;
    private final boolean hasPhysicalKeyboard;
    private final String keypadLayout;      // "qwerty", "qwertz" or "azerty"
    /** Which of {@link KeypadLayoutDetector}'s sources produced {@link #keypadLayout}. */
    private final KeypadLayoutDetector.Source keypadLayoutSource;
    private final String keypadVariant;     // "3row", "4row", "none"
    private final KeyboardDeviceInfo primaryKeyboard;
    private final boolean hasTouchKeypad;
    /** The scanned pad; null when none was found (and always null in a {@link #forShape}). */
    private final TouchKeypadInfo touchKeypad;
    private final boolean isBlackBerryDevice;
    private final boolean isEmulator;

    private DeviceCapabilities(DetectedDeviceType deviceType, boolean hasPhysicalKeyboard,
            String keypadLayout, KeypadLayoutDetector.Source keypadLayoutSource,
            String keypadVariant, KeyboardDeviceInfo primaryKeyboard,
            boolean hasTouchKeypad, TouchKeypadInfo touchKeypad, boolean isBlackBerryDevice,
            boolean isEmulator) {
        this.deviceType = deviceType;
        this.hasPhysicalKeyboard = hasPhysicalKeyboard;
        this.keypadLayout = keypadLayout;
        this.keypadLayoutSource = keypadLayoutSource;
        this.keypadVariant = keypadVariant;
        this.primaryKeyboard = primaryKeyboard;
        this.hasTouchKeypad = hasTouchKeypad;
        this.touchKeypad = touchKeypad;
        this.isBlackBerryDevice = isBlackBerryDevice;
        this.isEmulator = isEmulator;
    }

    /** Probes the hardware with no device config in hand; see {@link #detect(DeviceInputMapping)}. */
    public static DeviceCapabilities detect() {
        return detect(null);
    }

    /**
     * Probes the hardware.
     *
     * @param mapping the active config's mapping, or null; its {@code keypadLayout} is the
     *        top-ranked source for {@link #getKeypadLayout()}
     */
    public static DeviceCapabilities detect(DeviceInputMapping mapping) {
        return detect(mapping, null);
    }

    /** As {@link #detect(DeviceInputMapping)}, with the user's keypad layout choice (null or empty for none) ranked first. */
    public static DeviceCapabilities detect(DeviceInputMapping mapping, String userKeypadLayout) {
        KeyboardDeviceScanner scanner = KeyboardDeviceScanner.getInstance();
        // A config that names its pad lets the scanner find it by name.
        scanner.setDeclaredTouchKeypadName(mapping != null && mapping.touchKeypad != null
                ? mapping.touchKeypad.inputDeviceName : null);
        KeyboardDeviceInfo primary = scanner.getPrimaryKeyboard();
        TouchKeypadInfo touch = scanner.getPrimaryTouchKeypad();

        KeypadLayoutDetector.Detection layout = KeypadLayoutDetector.detectLive(
                userKeypadLayout, mapping != null ? mapping.keypadLayout : null, primary);

        return new DeviceCapabilities(
                determineDeviceType(primary),
                primary != null,
                layout.layout,
                layout.source,
                HardwareProbe.getSystemKeypadType(),
                primary,
                touch != null,
                touch,
                HardwareProbe.isBlackBerryDevice(),
                HardwareProbe.isEmulator());
    }

    /** A copy with the touch keypad a live re-scan found. */
    public DeviceCapabilities withTouchKeypad(TouchKeypadInfo touch) {
        return new DeviceCapabilities(deviceType, hasPhysicalKeyboard, keypadLayout,
                keypadLayoutSource, keypadVariant, primaryKeyboard,
                touch != null, touch, isBlackBerryDevice, isEmulator);
    }

    /**
     * Test seam: builds capabilities without probing (a JVM always detects VKB). Other fields take
     * their no-pad values (no primary keyboard, pad id -1); drive pad ids through a mapping with
     * {@code forceTouchKeypad} instead.
     */
    @VisibleForTesting
    public static DeviceCapabilities forShape(DetectedDeviceType deviceType,
            boolean hasPhysicalKeyboard, boolean hasTouchKeypad, boolean isBlackBerryDevice,
            String keypadLayout, String keypadVariant) {
        return new DeviceCapabilities(deviceType, hasPhysicalKeyboard, keypadLayout,
                KeypadLayoutDetector.Source.DEVICE_CONFIG, keypadVariant,
                null, hasTouchKeypad, null, isBlackBerryDevice, false);
    }

    private static DetectedDeviceType determineDeviceType(KeyboardDeviceInfo primary) {
        if (primary == null) return DetectedDeviceType.VKB;
        if (primary.isBlackBerryBuiltIn()) return DetectedDeviceType.PKB;
        return DetectedDeviceType.HYBRID;  // External keyboard
    }

    public DetectedDeviceType getDeviceType() { return deviceType; }
    public boolean hasPhysicalKeyboard() { return hasPhysicalKeyboard; }
    public boolean hasTouchKeypad() { return hasTouchKeypad; }
    /** The scanned pad, or null when none was found. */
    public TouchKeypadInfo getTouchKeypad() { return touchKeypad; }
    public int getTouchKeypadDeviceId() { return touchKeypad != null ? touchKeypad.getDeviceId() : -1; }
    public float getTouchKeypadResolution() { return touchKeypad != null ? touchKeypad.getResolution() : 0f; }
    public float getTouchKeypadYMax() { return touchKeypad != null ? touchKeypad.getYRangeMax() : 0f; }
    public String getKeypadLayout() { return keypadLayout; }
    public KeypadLayoutDetector.Source getKeypadLayoutSource() { return keypadLayoutSource; }
    public String getKeypadVariant() { return keypadVariant; }
    public KeyboardDeviceInfo getPrimaryKeyboard() { return primaryKeyboard; }
    public boolean isBlackBerryDevice() { return isBlackBerryDevice; }
    public boolean isEmulator() { return isEmulator; }

    @Override
    public String toString() {
        return "DeviceCapabilities{type=" + deviceType +
            ", hasPKB=" + hasPhysicalKeyboard +
            ", touchKeypad=" + hasTouchKeypad +
            ", layout=" + keypadLayout + "/" + keypadLayoutSource +
            ", variant=" + keypadVariant +
            ", bb=" + isBlackBerryDevice +
            ", emu=" + isEmulator + "}";
    }
}

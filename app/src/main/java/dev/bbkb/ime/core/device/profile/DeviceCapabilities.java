package dev.bbkb.ime.core.device.profile;

import androidx.annotation.VisibleForTesting;

import dev.bbkb.ime.core.device.config.model.DeviceInputMapping;
import dev.bbkb.ime.core.device.detection.HardwareProbe;
import dev.bbkb.ime.core.device.detection.KeyboardDeviceInfo;
import dev.bbkb.ime.core.device.detection.KeyboardDeviceScanner;
import dev.bbkb.ime.core.device.detection.KeypadLayoutDetector;
import dev.bbkb.ime.core.device.detection.TouchKeypadInfo;

/**
 * Immutable device capabilities. Created once at startup, never changes.
 *
 * <p>Audit W1-F: the 14-setter {@code Builder} had exactly one call site ({@link #detect()}),
 * which set every field, so it bought nothing over a constructor; and three of its fields were
 * write-only — {@code supportsSwipeTyping} / {@code supportsSlideboard} (nothing read them;
 * {@code DeviceProfile} answers both questions from {@code !isPkbDevice()}) and
 * {@code sdkVersion} (read only by {@code isPreMarshmallow()}, itself unreferenced at
 * {@code minSdk = 23}).
 */
public final class DeviceCapabilities {

    /**
     * How the hardware probe classified this device. Named apart from
     * {@link DeviceProfile.DeviceType} (PKB/VKB), which is the coarser question the rest of
     * the app asks; the two enums used to share a simple name in this same package.
     */
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
     * @param mapping the active device config's mapping, or null when none is resolved yet. Its
     *        {@code keypadLayout} is the top-ranked source for {@link #getKeypadLayout()}, which
     *        is why {@code DeviceProfile.initializeForDevice} now resolves the mapping <em>before</em>
     *        calling this rather than after.
     */
    public static DeviceCapabilities detect(DeviceInputMapping mapping) {
        KeyboardDeviceScanner scanner = KeyboardDeviceScanner.getInstance();
        // A config that names its pad lets the scanner find it by name; a config that names none
        // (every one but the Titans') leaves the scan exactly as it was.
        scanner.setDeclaredTouchKeypadName(mapping != null && mapping.touchKeypad != null
                ? mapping.touchKeypad.inputDeviceName : null);
        KeyboardDeviceInfo primary = scanner.getPrimaryKeyboard();
        TouchKeypadInfo touch = scanner.getPrimaryTouchKeypad();

        KeypadLayoutDetector.Detection layout = KeypadLayoutDetector.detectLive(
                mapping != null ? mapping.keypadLayout : null, primary);

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

    /**
     * These capabilities with a different touch keypad: what a live re-scan found after the pad
     * appeared, vanished or changed. Everything else is kept as detected.
     */
    public DeviceCapabilities withTouchKeypad(TouchKeypadInfo touch) {
        return new DeviceCapabilities(deviceType, hasPhysicalKeyboard, keypadLayout,
                keypadLayoutSource, keypadVariant, primaryKeyboard,
                touch != null, touch, isBlackBerryDevice, isEmulator);
    }

    /**
     * Builds capabilities directly instead of probing the hardware — the injection seam for tests
     * that must run against a device shape a JVM cannot detect. {@link #detect()} on a JVM always
     * reports the VKB shape (no InputDevices), which is why the physical-keyboard rendering of the
     * settings screens has never been under test.
     *
     * <p>The fields not taken as parameters are fixed at their "no hardware pad" values:
     * {@code primaryKeyboard} null, {@code touchKeypadDeviceId} -1, both touch-keypad floats 0,
     * {@code isEmulator} false. A test needing the touch-keypad ids should drive them through a
     * {@code DeviceInputMapping} with {@code forceTouchKeypad}, the way a forced-CKB config does.
     *
     * <p>Production never calls this; {@link #detect()} remains the only construction site on a
     * device.
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

    // All getters - no logic
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

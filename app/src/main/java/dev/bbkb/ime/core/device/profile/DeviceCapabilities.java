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
    private final int touchKeypadDeviceId;
    private final float touchKeypadResolution;
    private final float touchKeypadYMax;
    private final boolean isBlackBerryDevice;
    private final boolean isEmulator;

    private DeviceCapabilities(DetectedDeviceType deviceType, boolean hasPhysicalKeyboard,
            String keypadLayout, KeypadLayoutDetector.Source keypadLayoutSource,
            String keypadVariant, KeyboardDeviceInfo primaryKeyboard,
            boolean hasTouchKeypad, int touchKeypadDeviceId, float touchKeypadResolution,
            float touchKeypadYMax, boolean isBlackBerryDevice, boolean isEmulator) {
        this.deviceType = deviceType;
        this.hasPhysicalKeyboard = hasPhysicalKeyboard;
        this.keypadLayout = keypadLayout;
        this.keypadLayoutSource = keypadLayoutSource;
        this.keypadVariant = keypadVariant;
        this.primaryKeyboard = primaryKeyboard;
        this.hasTouchKeypad = hasTouchKeypad;
        this.touchKeypadDeviceId = touchKeypadDeviceId;
        this.touchKeypadResolution = touchKeypadResolution;
        this.touchKeypadYMax = touchKeypadYMax;
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
                touch != null ? touch.getDeviceId() : -1,
                touch != null ? touch.getResolution() : 0f,
                touch != null ? touch.getYRangeMax() : 0f,
                HardwareProbe.isBlackBerryDevice(),
                HardwareProbe.isEmulator());
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
                null, hasTouchKeypad, -1, 0f, 0f, isBlackBerryDevice, false);
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
    public int getTouchKeypadDeviceId() { return touchKeypadDeviceId; }
    public float getTouchKeypadResolution() { return touchKeypadResolution; }
    public float getTouchKeypadYMax() { return touchKeypadYMax; }
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

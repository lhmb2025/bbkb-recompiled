package dev.bbkb.ime.core.device.config.builder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dev.bbkb.ime.core.device.detection.KeypadLayoutDetector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Everything about this handset the app can work out on its own, with no help from the user.
 *
 * <p>Immutable and free of {@code Context}: {@link DeviceProfileBuilder#detect} reads the live
 * device and fills one of these in, and everything downstream — the screen's read-only rows, the
 * {@code <match>} block, the header comment — works from the record rather than re-reading the
 * hardware. That also means the exporter and the screen can both be tested against a synthetic
 * MP01 or KEY2 without either device present.
 */
public final class DeviceFacts {

    /** One physical keyboard {@code InputDevice}. */
    public static final class Keyboard {
        public final int deviceId;
        @NonNull public final String name;
        @Nullable public final String descriptor;
        public final int vendorId;
        public final int productId;
        public final int sources;
        public final int keyboardType;
        /** This device also reports {@code SOURCE_TOUCHPAD}, i.e. it is a capacitive keypad. */
        public final boolean hasTouch;
        /**
         * The loaded {@code KeyCharacterMap} has an Alt layer. False on a ROM that fell back to
         * {@code Generic.kcm} — the Minimal Phone MP01 does, because its {@code .idc} names a
         * {@code .kl} that is not on the filesystem — and that is exactly the case where the app
         * has to supply the Alt characters itself.
         */
        public final boolean hasAltLayer;

        public Keyboard(int deviceId, @NonNull String name, @Nullable String descriptor,
                        int vendorId, int productId, int sources, int keyboardType,
                        boolean hasTouch, boolean hasAltLayer) {
            this.deviceId = deviceId;
            this.name = name;
            this.descriptor = descriptor;
            this.vendorId = vendorId;
            this.productId = productId;
            this.sources = sources;
            this.keyboardType = keyboardType;
            this.hasTouch = hasTouch;
            this.hasAltLayer = hasAltLayer;
        }

        /** Vendor and product as the four-hex-digit form a {@code <match>} block would carry. */
        @NonNull
        public String vendorProduct() {
            return String.format(Locale.ROOT, "0x%04x / 0x%04x", vendorId, productId);
        }

        @NonNull
        @Override
        public String toString() {
            return name + " (id=" + deviceId + ", " + vendorProduct()
                    + ", sources=0x" + Integer.toHexString(sources)
                    + ", type=" + keyboardType + ", touch=" + hasTouch + ")";
        }
    }

    @NonNull public final String buildDevice;
    @NonNull public final String buildModel;
    @NonNull public final String buildManufacturer;
    @NonNull public final String buildBrand;
    /** {@code ro.build.display.id} — the ROM build, e.g. {@code MP01_20260104_1412}. */
    @NonNull public final String romDisplayId;
    @NonNull public final String androidRelease;
    public final int sdkInt;
    @NonNull public final String appVersion;

    @NonNull public final List<Keyboard> keyboards;
    /** The keyboard a config for this device should match on, or null when there is none. */
    @Nullable public final Keyboard primaryKeyboard;

    @NonNull public final String keypadLayout;
    @NonNull public final KeypadLayoutDetector.Source keypadLayoutSource;
    /** A capacitive/touch keypad exists, whether on the keyboard device or beside it. */
    public final boolean hasTouchKeypad;

    /** Display name of the shipped config that already matches this device, or null. */
    @Nullable public final String matchedConfigName;
    /** Id of that config, in the form {@code CustomDeviceConfigManager} activates. */
    @Nullable public final String matchedConfigId;

    DeviceFacts(@NonNull String buildDevice, @NonNull String buildModel,
                @NonNull String buildManufacturer, @NonNull String buildBrand,
                @NonNull String romDisplayId, @NonNull String androidRelease, int sdkInt,
                @NonNull String appVersion, @NonNull List<Keyboard> keyboards,
                @Nullable Keyboard primaryKeyboard, @NonNull String keypadLayout,
                @NonNull KeypadLayoutDetector.Source keypadLayoutSource, boolean hasTouchKeypad,
                @Nullable String matchedConfigName, @Nullable String matchedConfigId) {
        this.buildDevice = buildDevice;
        this.buildModel = buildModel;
        this.buildManufacturer = buildManufacturer;
        this.buildBrand = buildBrand;
        this.romDisplayId = romDisplayId;
        this.androidRelease = androidRelease;
        this.sdkInt = sdkInt;
        this.appVersion = appVersion;
        this.keyboards = Collections.unmodifiableList(new ArrayList<>(keyboards));
        this.primaryKeyboard = primaryKeyboard;
        this.keypadLayout = keypadLayout;
        this.keypadLayoutSource = keypadLayoutSource;
        this.hasTouchKeypad = hasTouchKeypad;
        this.matchedConfigName = matchedConfigName;
        this.matchedConfigId = matchedConfigId;
    }

    /** The {@code InputDevice} name a generated {@code <match>} block should key on, if any. */
    @Nullable
    public String matchDeviceName() {
        return primaryKeyboard != null ? primaryKeyboard.name : null;
    }

    /**
     * {@code CKB} when this handset has a touch-sensitive keypad, {@code PKB} otherwise. Both
     * force the physical-keyboard pipeline on; only CKB additionally forces the touch keypad,
     * which is what gates swipe input.
     */
    @NonNull
    public String deviceType() {
        return hasTouchKeypad ? "CKB" : "PKB";
    }

    /**
     * Whether a generated config should pin {@code <keypad-layout>}.
     *
     * <p>Only when no firmware source answered. The device name, the {@code ro.*.keypadlanguage}
     * property and the KeyCharacterMap's Alt fingerprint are all the firmware saying which keypad
     * is fitted, and each is true of every unit that matches the config; pinning over them would
     * freeze one answer for everyone who imports the file. A scancode observation is proof about
     * <em>this</em> unit and nothing above it answered, and the fallback is not an answer at all —
     * both are cases where the file is the only place the layout can be recorded.
     */
    public boolean shouldDeclareKeypadLayout() {
        return keypadLayoutSource == KeypadLayoutDetector.Source.SCANCODE
                || keypadLayoutSource == KeypadLayoutDetector.Source.FALLBACK;
    }

    /** The default profile name offered in the UI, e.g. {@code "Minimal (MP01)"}. */
    @NonNull
    public String suggestedProfileName() {
        final String model = buildModel.trim();
        final String device = buildDevice.trim();
        if (!model.isEmpty() && !device.isEmpty() && !model.equalsIgnoreCase(device)) {
            return model + " (" + device + ")";
        }
        if (!model.isEmpty()) return model;
        if (!device.isEmpty()) return device;
        return "Custom device";
    }
}

package dev.bbkb.ime.core.device.config.builder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dev.bbkb.ime.core.device.detection.KeypadLayoutDetector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * What the app detects about this handset unaided. Immutable and {@code Context}-free, so
 * everything downstream works from this record and tests can use synthetic devices.
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
        /** Also reports {@code SOURCE_TOUCHPAD}: a capacitive keypad. */
        public final boolean hasTouch;
        /** The loaded {@code KeyCharacterMap} has an Alt layer; false on a {@code Generic.kcm} fallback. */
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

        /** Vendor and product as four-digit hex. */
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
    /** {@code ro.build.display.id}, the ROM build, e.g. {@code MP01_20260104_1412}. */
    @NonNull public final String romDisplayId;
    @NonNull public final String androidRelease;
    public final int sdkInt;
    @NonNull public final String appVersion;

    @NonNull public final List<Keyboard> keyboards;
    /** The keyboard a config should match on, or null. */
    @Nullable public final Keyboard primaryKeyboard;

    @NonNull public final String keypadLayout;
    @NonNull public final KeypadLayoutDetector.Source keypadLayoutSource;
    /** A capacitive/touch keypad exists, whether on the keyboard device or beside it. */
    public final boolean hasTouchKeypad;

    /** Display name of the shipped config that already matches this device, or null. */
    @Nullable public final String matchedConfigName;
    /** Id of that config, as {@code CustomDeviceConfigManager} activates it. */
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

    @Nullable
    public String matchDeviceName() {
        return primaryKeyboard != null ? primaryKeyboard.name : null;
    }

    /** {@code CKB} with a touch keypad (which gates swipe input), else {@code PKB}. */
    @NonNull
    public String deviceType() {
        return hasTouchKeypad ? "CKB" : "PKB";
    }

    /**
     * Pin {@code <keypad-layout>} only when no firmware source answered: firmware answers hold per
     * unit, and pinning one would freeze it for everyone importing the file.
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

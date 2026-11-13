package dev.bbkb.ime.core.device.config.builder;

import android.content.Context;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dev.bbkb.ime.core.device.config.CustomDeviceConfigManager;
import dev.bbkb.ime.core.device.config.model.DeviceInputConfig;
import dev.bbkb.ime.core.device.config.parser.DeviceInputMappingParser;
import dev.bbkb.ime.core.device.detection.KeyboardDeviceScanner;
import dev.bbkb.ime.core.device.detection.KeypadLayoutDetector;
import dev.bbkb.ime.core.shared.Logger;
import dev.bbkb.ime.BuildConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a device config for this handset: {@link #detect} reads what the hardware reports,
 * {@link #draft} adds the keys the user captured, since a bezel key's scancode/keycode pair can
 * only be observed (keycodes change between ROMs).
 *
 * <p>Keylayout files ({@code /vendor/usr/keylayout/*.kl}) are not read: on a ROM that falls back
 * to {@code Generic.kl}, the file present is not the file in use.
 */
public final class DeviceProfileBuilder {

    private static final String TAG = "DeviceProfileBuilder";

    private DeviceProfileBuilder() {}

    /** Reads everything detectable about this handset. Safe to call off the main thread. */
    @NonNull
    public static DeviceFacts detect(@NonNull Context context) {
        final List<DeviceFacts.Keyboard> keyboards = scanKeyboards();
        final DeviceFacts.Keyboard primary = pickPrimary(keyboards);

        boolean hasTouchKeypad = KeyboardDeviceScanner.getInstance().getPrimaryTouchKeypad() != null;
        if (!hasTouchKeypad) {
            for (DeviceFacts.Keyboard keyboard : keyboards) {
                if (keyboard.hasTouch) {
                    hasTouchKeypad = true;
                    break;
                }
            }
        }

        // Run the detector now if the IME has not started this boot.
        KeypadLayoutDetector.Detection detection = KeypadLayoutDetector.last();
        if (detection == null) {
            detection = KeypadLayoutDetector.detectLive(
                    null, KeyboardDeviceScanner.getInstance().getPrimaryKeyboard());
        }

        String matchedName = null;
        String matchedId = null;
        if (primary != null) {
            final CustomDeviceConfigManager.ConfigInfo matched =
                    findMatchingShippedConfig(context, primary.name);
            if (matched != null) {
                matchedName = matched.name;
                matchedId = matched.id;
            }
        }

        return new DeviceFacts(
                nonNull(android.os.Build.DEVICE),
                nonNull(android.os.Build.MODEL),
                nonNull(android.os.Build.MANUFACTURER),
                nonNull(android.os.Build.BRAND),
                romDisplayId(),
                nonNull(android.os.Build.VERSION.RELEASE),
                android.os.Build.VERSION.SDK_INT,
                BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")",
                keyboards,
                primary,
                detection.layout,
                detection.source,
                hasTouchKeypad,
                matchedName,
                matchedId);
    }

    /**
     * Every non-virtual {@code SOURCE_KEYBOARD} device, in id order. Not via
     * {@code KeyboardDeviceScanner}, whose {@code isPhysicalKeyboard()} filter would drop the
     * unexpected keypads profiles are built for, and which hides vendor/product ids and sources.
     */
    @NonNull
    private static List<DeviceFacts.Keyboard> scanKeyboards() {
        final List<DeviceFacts.Keyboard> out = new ArrayList<>();
        final int[] ids = InputDevice.getDeviceIds();
        for (int id : ids) {
            final InputDevice device = InputDevice.getDevice(id);
            if (device == null || device.isVirtual()) continue;
            if ((device.getSources() & InputDevice.SOURCE_KEYBOARD) == 0) continue;

            final String name = device.getName();
            out.add(new DeviceFacts.Keyboard(
                    device.getId(),
                    name != null ? name : "",
                    device.getDescriptor(),
                    device.getVendorId(),
                    device.getProductId(),
                    device.getSources(),
                    device.getKeyboardType(),
                    (device.getSources() & InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD,
                    hasAltLayer(device)));
        }
        return out;
    }

    /** True when the device's loaded keychar map has any Alt characters at all. */
    private static boolean hasAltLayer(@NonNull InputDevice device) {
        try {
            final KeyCharacterMap map = device.getKeyCharacterMap();
            if (map == null) return false;
            return map.get(KeyEvent.KEYCODE_A, KeyEvent.META_ALT_ON) != 0
                    || map.get(KeyEvent.KEYCODE_S, KeyEvent.META_ALT_ON) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The keyboard a config keys on: first named alphabetic one, else first named. Built-in cannot
     * be asked ({@code isExternal()} is {@code @hide}); the screen shows which name was chosen.
     */
    @Nullable
    private static DeviceFacts.Keyboard pickPrimary(@NonNull List<DeviceFacts.Keyboard> keyboards) {
        DeviceFacts.Keyboard firstNamed = null;
        for (DeviceFacts.Keyboard keyboard : keyboards) {
            if (keyboard.name.isEmpty()) continue;
            if (keyboard.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
                return keyboard;
            }
            if (firstNamed == null) {
                firstNamed = keyboard;
            }
        }
        return firstNamed;
    }

    /**
     * The shipped config claiming {@code deviceName}, or null. Same first-match order as
     * {@code findMatchingPreloadedConfig}, so it reports the config that would be auto-selected.
     */
    @Nullable
    private static CustomDeviceConfigManager.ConfigInfo findMatchingShippedConfig(
            @NonNull Context context, @NonNull String deviceName) {
        try {
            final CustomDeviceConfigManager manager = CustomDeviceConfigManager.getInstance(context);
            for (CustomDeviceConfigManager.ConfigInfo info : manager.getAvailableConfigs()) {
                if (info.type != CustomDeviceConfigManager.ConfigType.PRELOADED
                        || info.resourceId == 0) {
                    continue;
                }
                final DeviceInputConfig config =
                        DeviceInputMappingParser.parseConfigFromXmlResource(context, info.resourceId);
                if (config != null && config.findMappingForDevice(deviceName) != null) {
                    return info;
                }
            }
        } catch (Exception e) {
            Logger.error(TAG, "Could not scan shipped configs: " + e.getMessage());
        }
        return null;
    }

    @NonNull
    private static String romDisplayId() {
        // Build.DISPLAY is ro.build.display.id.
        return nonNull(android.os.Build.DISPLAY).trim();
    }

    @NonNull
    private static String nonNull(@Nullable String value) {
        return value != null ? value : "";
    }

    /**
     * @param profileName the display name the config carries; blank falls back to
     *                    {@link DeviceFacts#suggestedProfileName()}
     */
    @NonNull
    public static DeviceProfileDraft draft(@NonNull DeviceFacts facts,
                                           @NonNull GuidedCapture capture,
                                           @Nullable String profileName) {
        final String name = (profileName == null || profileName.trim().isEmpty())
                ? facts.suggestedProfileName()
                : profileName.trim();
        return new DeviceProfileDraft(name, facts, capture.usableResults());
    }
}

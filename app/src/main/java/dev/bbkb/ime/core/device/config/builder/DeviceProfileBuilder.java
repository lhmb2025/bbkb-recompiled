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
import dev.bbkb.ime.core.shared.SystemProps;
import dev.bbkb.ime.BuildConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a device config for the handset the app is running on.
 *
 * <p>Two halves, and the split matters. {@link #detect} works out everything the hardware will
 * tell us without being asked — the {@code Build} identifiers, every physical keyboard
 * {@code InputDevice} with its vendor/product ids and sources, whether a touch keypad exists,
 * what {@code KeypadLayoutDetector} makes of the keypad and from which source, and whether a
 * shipped config already claims this device. {@link #draft} then folds those facts together with
 * what the user captured by pressing keys, because the one thing detection cannot produce is the
 * scancode/keycode pair of a bezel key: on the Minimal Phone MP01 the Sym key's scancode is 249
 * on every ROM but its keycode changed between two 2025/2026 ROMs, so it has to be observed.
 *
 * <p>The kernel keylayout and keychar files ({@code /vendor/usr/keylayout/*.kl}) are deliberately
 * not read. They are the authoritative answer, and on the KEY2 they are even world-readable, but
 * on a ROM that falls back to {@code Generic.kl} — the MP01 does, because its {@code .idc} names
 * a {@code .kl} that is not on the filesystem — the file that is <em>present</em> is not the file
 * in <em>use</em>, so it would confidently describe a mapping the device does not have. What the
 * key actually sent is the only evidence that cannot be wrong.
 */
public final class DeviceProfileBuilder {

    private static final String TAG = "DeviceProfileBuilder";

    private DeviceProfileBuilder() {} // No instantiation

    // ── detection ────────────────────────────────────────────────────────────

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

        // The detector's own live answer if it has one; otherwise run it now, which is the case
        // in Settings on a device whose IME has not started this boot.
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
     * Every non-virtual {@code SOURCE_KEYBOARD} device, in device-id order.
     *
     * <p>{@code KeyboardDeviceScanner} is not used for this: it keeps only the devices that pass
     * {@code isPhysicalKeyboard()} and exposes neither vendor/product ids nor the sources mask,
     * and an unknown handset whose keypad reports a type this app does not expect is exactly the
     * device someone is building a profile for. Everything with a keyboard source is listed, and
     * the screen shows the lot.
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
     * The keyboard a config should key on: the first named alphabetic one, else the first named
     * one at all.
     *
     * <p>There is no public way to ask whether an {@code InputDevice} is built in — {@code
     * isExternal()} is {@code @hide} — so "alphabetic first, then device-id order" is the whole
     * rule. It picks the handset's own keypad on both reference devices ({@code aw9523b-key} on
     * the MP01, {@code stmpe_keypad} on the KEY2), and if a Bluetooth keyboard is paired at
     * capture time the user can see which name was chosen on the screen's detected rows.
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
     * The shipped config whose {@code <match>} already claims {@code deviceName}, or null.
     *
     * <p>Same order as {@code CustomDeviceConfigManager.findMatchingPreloadedConfig}: the
     * available-configs list is sorted by display name and the first match wins, so this reports
     * the config that would actually be auto-selected rather than some other one that also
     * matches.
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
        final String prop = SystemProps.get("ro.build.display.id");
        if (prop != null && !prop.trim().isEmpty()) return prop.trim();
        return nonNull(android.os.Build.DISPLAY);
    }

    @NonNull
    private static String nonNull(@Nullable String value) {
        return value != null ? value : "";
    }

    // ── draft assembly ───────────────────────────────────────────────────────

    /**
     * Folds detected facts and captured keys into the draft the exporter serialises.
     *
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

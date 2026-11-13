package dev.bbkb.ime.core.device.config.resolver;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.view.InputDevice;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import dev.bbkb.ime.core.device.config.CustomDeviceConfigManager;
import dev.bbkb.ime.core.device.config.model.AltMappingsTable;
import dev.bbkb.ime.core.device.config.model.DeviceInputConfig;
import dev.bbkb.ime.core.device.config.model.DeviceInputMapping;
import dev.bbkb.ime.core.device.config.model.ScancodeMapping;
import dev.bbkb.ime.core.device.config.parser.AltMappingsParser;
import dev.bbkb.ime.core.device.config.parser.DeviceInputMappingParser;
import dev.bbkb.ime.BuildConfig;

/**
 * Decides which device profile speaks for which keyboard, cached by input-device id until a device
 * changes or another configuration is chosen. {@link #resolveInputMapping} is the profile that
 * matches a device (or, for {@link #ALL_DEVICES}, the phone); {@link #keyProfile} is the profile
 * that governs a key from that device, read by everything decided per key.
 */
public class DeviceInputResolver {
    
    private static final String TAG = "DeviceInputResolver";
    private static DeviceInputConfig sConfig = null;

    /** Resolved mapping per device id; {@code sResolvedDeviceIds} marks searched ids, since null is a real answer. */
    private static final SparseArray<DeviceInputMapping> sMappingByDeviceId = new SparseArray<>(4);
    private static final SparseBooleanArray sResolvedDeviceIds = new SparseBooleanArray(4);

    /** Device id used for the "search every connected physical keyboard" resolution. */
    public static final int ALL_DEVICES = 0;

    /**
     * {@link #keyProfile} per device id; {@link #NO_PROFILE} records "none". Read on every
     * hardware key event, so it is published copy-on-write and read without a lock.
     */
    private static volatile SparseArray<Object> sKeyProfiles = new SparseArray<>(4);

    private static final Object NO_PROFILE = new Object();

    private static boolean sDeviceListenerRegistered = false;

    /** Application context, kept so a device change can re-resolve without a caller in hand. */
    private static Context sAppContext;

    /**
     * @param deviceId device id, or {@link #ALL_DEVICES} to search every physical keyboard
     * @return the matching mapping, or null for default behavior
     */
    public static synchronized DeviceInputMapping resolveInputMapping(Context context, int deviceId) {
        if (context == null) {
            return null;
        }

        registerDeviceListener(context);

        if (sConfig == null) {
            sConfig = CustomDeviceConfigManager.getInstance(context).getActiveConfig();
        }

        if (sConfig == null) {
            return null;
        }

        if (sResolvedDeviceIds.get(deviceId)) {
            return sMappingByDeviceId.get(deviceId);
        }

        if (deviceId == ALL_DEVICES) {
            return findMappingFromAllDevices();
        }

        InputDevice device = InputDevice.getDevice(deviceId);
        if (device == null) {
            return findMappingFromAllDevices();
        }

        DeviceInputMapping mapping = sConfig.findMappingForInputDevice(device);

        cacheMapping(deviceId, mapping);
        return mapping;
    }

    /**
     * The profile that governs a key from this device, or null. A matched keyboard gets its
     * profile; any other built-in or virtual device gets the phone's (keypad and side keys are
     * often separate devices); an unnamed external keyboard gets none.
     *
     * @param deviceId {@code KeyEvent.getDeviceId()}
     */
    @Nullable
    public static DeviceInputMapping keyProfile(int deviceId) {
        final Object cached = sKeyProfiles.get(deviceId);
        if (cached != null) {
            return cached == NO_PROFILE ? null : (DeviceInputMapping) cached;
        }
        return resolveKeyProfile(deviceId);
    }

    private static synchronized DeviceInputMapping resolveKeyProfile(int deviceId) {
        final Context context = sAppContext;
        if (sConfig == null && context == null) {
            return null;  // nothing has been resolved with a context yet; ask again next time
        }
        final DeviceInputMapping phone = sResolvedDeviceIds.get(ALL_DEVICES) || context == null
                ? sMappingByDeviceId.get(ALL_DEVICES)
                : resolveInputMapping(context, ALL_DEVICES);
        if (sConfig == null) {
            return null;  // no active configuration could be loaded
        }

        final InputDevice device = deviceId == ALL_DEVICES ? null : InputDevice.getDevice(deviceId);
        DeviceInputMapping profile = phone;
        if (device != null && !device.isVirtual()) {
            DeviceInputMapping own = sResolvedDeviceIds.get(deviceId)
                    ? sMappingByDeviceId.get(deviceId)
                    : sConfig.findMappingForInputDevice(device);
            cacheMapping(deviceId, own);
            if (own != null) {
                profile = own;
            } else if (DeviceInputConfig.isExternal(device)) {
                profile = null;
            }
        }

        final SparseArray<Object> next = sKeyProfiles.clone();
        next.put(deviceId, profile != null ? profile : NO_PROFILE);
        sKeyProfiles = next;
        return profile;
    }

    /**
     * The role the governing profile gives a key, or null: no profile governs the keyboard it
     * came from, or the profile gives that key no role.
     */
    @Nullable
    public static ScancodeMapping keyRole(int scanCode, int keyCode, int deviceId) {
        final DeviceInputMapping profile = keyProfile(deviceId);
        return profile == null ? null : profile.roleFor(scanCode, keyCode);
    }

    /**
     * Whether a key from this device is governed by the phone's own profile, so the profile's
     * Alt table and keypad shape apply to it. True for every keyboard on a phone with no
     * profile; false for an external keyboard the phone's profile does not name.
     */
    public static boolean isPhoneKeyboard(int deviceId) {
        return keyProfile(deviceId) == keyProfile(ALL_DEVICES);
    }

    private static DeviceInputMapping findMappingFromAllDevices() {
        if (sResolvedDeviceIds.get(ALL_DEVICES)) {
            return sMappingByDeviceId.get(ALL_DEVICES);
        }

        int[] deviceIds = InputDevice.getDeviceIds();

        for (int id : deviceIds) {
            InputDevice device = InputDevice.getDevice(id);
            if (device == null) continue;

            int sources = device.getSources();
            boolean isKeyboard = (sources & InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD;
            boolean isExternal = !device.isVirtual();

            if (isKeyboard && isExternal) {
                DeviceInputMapping mapping = sConfig.findMappingForInputDevice(device);
                if (mapping != null) {
                    cacheMapping(ALL_DEVICES, mapping);
                    return mapping;
                }
            }
        }

        // No keyboard matched: a hand-picked configuration is the phone's profile anyway.
        final DeviceInputMapping chosen = sConfig.chosenPhoneProfile();
        cacheMapping(ALL_DEVICES, chosen);
        return chosen;
    }

    private static void cacheMapping(int deviceId, DeviceInputMapping mapping) {
        sMappingByDeviceId.put(deviceId, mapping);
        sResolvedDeviceIds.put(deviceId, true);
    }

    /** Invalidates the cache when a keyboard is attached, removed or changed. */
    private static void registerDeviceListener(Context context) {
        if (sDeviceListenerRegistered) return;
        final InputManager inputManager =
                (InputManager) context.getApplicationContext().getSystemService(Context.INPUT_SERVICE);
        if (inputManager == null) return;
        sAppContext = context.getApplicationContext();
        // Frequent: Android reports the built-in keyboard as changed on every subtype switch. The
        // phone's profile is resolved again at once, each keyboard's on its next key.
        inputManager.registerInputDeviceListener(new InputManager.InputDeviceListener() {
            @Override public void onInputDeviceAdded(int deviceId)   { resetAndResolve(); }
            @Override public void onInputDeviceRemoved(int deviceId) { resetAndResolve(); }
            @Override public void onInputDeviceChanged(int deviceId) { resetAndResolve(); }
        }, new Handler(Looper.getMainLooper()));
        sDeviceListenerRegistered = true;
    }

    /** The Alt table the device config names, or null if none is configured. */
    public static AltMappingsTable getAltMappingsTable(Context context, int deviceId) {
        return AltMappingsParser.tableFor(context, resolveInputMapping(context, deviceId));
    }

    /** A device was added, removed or changed: forgets what was resolved and re-resolves the phone's profile. */
    static synchronized void resetAndResolve() {
        // Not resetConfig(): the parsed Alt tables stay, or every language switch would make the
        // next key re-parse its Alt table on the main thread.
        resetResolved();
        if (sAppContext != null) {
            resolveInputMapping(sAppContext, ALL_DEVICES);
        }
    }

    /** Another device configuration was chosen: everything resolved goes, the Alt tables too. */
    public static synchronized void resetConfig() {
        resetResolved();
        AltMappingsParser.clearMappingsCache();
    }

    /** Forgets what was resolved from the configuration and the attached devices. */
    private static void resetResolved() {
        sConfig = null;
        sMappingByDeviceId.clear();
        sResolvedDeviceIds.clear();
        sKeyProfiles = new SparseArray<>(4);
    }

    /** Makes {@code profile} (or null for none) the phone's profile for a JVM test; undo with {@link #resetConfig()}. */
    @VisibleForTesting
    public static synchronized void installPhoneProfileForTest(@Nullable DeviceInputMapping profile) {
        resetResolved();
        sConfig = new DeviceInputConfig();
        if (profile != null) {
            sConfig.mappings.add(profile);
        }
        cacheMapping(ALL_DEVICES, profile);
    }
}

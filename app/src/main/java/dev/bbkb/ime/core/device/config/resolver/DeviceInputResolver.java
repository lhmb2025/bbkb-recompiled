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
 * Decides which device profile speaks for which keyboard, once per keyboard.
 *
 * <p>Two questions, both answered here and cached by input-device id until a device is attached,
 * removed or changed, or another configuration is chosen:
 * <ul>
 *   <li>{@link #resolveInputMapping}: which profile <em>matches</em> this input device (or, for
 *       {@link #ALL_DEVICES}, this phone). The phone-wide answer is the profile
 *       {@code DeviceProfile} carries.</li>
 *   <li>{@link #keyProfile}: which profile <em>governs a key</em> that came from this device.
 *       Everything decided per key reads this: key roles ({@link #keyRole}), the Alt table and
 *       keypad-layout observation ({@link #isPhoneKeyboard}).</li>
 * </ul>
 */
public class DeviceInputResolver {
    
    private static final String TAG = "DeviceInputResolver";
    private static DeviceInputConfig sConfig = null;

    /**
     * Resolved mapping per device id, with {@code sResolvedDeviceIds} recording which ids have
     * been searched (a resolved-to-null result is a real answer and must not be re-searched).
     *
     * This used to be one device-id-agnostic pair of statics guarded by a single
     * {@code sSearchPerformed} flag that was consulted <em>before</em> the device id was even
     * looked at -- so once any device had been resolved, every other device silently inherited
     * that device's alt-mappings, scancode roles, hasTouchKeypad and forcePkbDevice. On a KEY2
     * with a Bluetooth keyboard attached, the second keyboard got the first one's config.
     */
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
     * Get the input mapping configuration for a device.
     *
     * @param context Application context
     * @param deviceId Device ID (if 0, will search all physical keyboard devices)
     * @return DeviceInputMapping if custom config exists, null for default behavior
     */
    public static synchronized DeviceInputMapping resolveInputMapping(Context context, int deviceId) {
        if (context == null) {
            return null;
        }

        registerDeviceListener(context);

        // Load config from active configuration (managed by CustomDeviceConfigManager)
        if (sConfig == null) {
            sConfig = CustomDeviceConfigManager.getInstance(context).getActiveConfig();
        }

        if (sConfig == null) {
            return null;
        }

        // Return the cached result for *this* device, if it has been resolved.
        if (sResolvedDeviceIds.get(deviceId)) {
            return sMappingByDeviceId.get(deviceId);
        }

        // If deviceId is 0 or invalid, search all physical keyboard devices
        if (deviceId == ALL_DEVICES) {
            return findMappingFromAllDevices();
        }

        // Get device info by ID
        InputDevice device = InputDevice.getDevice(deviceId);
        if (device == null) {
            return findMappingFromAllDevices();
        }

        // Find mapping: name, and vendor/product ids for configs that match on them
        DeviceInputMapping mapping = sConfig.findMappingForInputDevice(device);

        cacheMapping(deviceId, mapping);
        return mapping;
    }

    /**
     * The profile that governs a key from this input device, or null when none does.
     *
     * <ul>
     *   <li>A keyboard that a profile matches gets that profile.</li>
     *   <li>Any other built-in device gets the phone's profile: a phone's keypad and its side
     *       keys are often separate input devices, and a profile that names the keypad still
     *       describes the phone. So does a key Android attributes to no real device (injected
     *       keys, the virtual keyboard).</li>
     *   <li>An external keyboard that no profile names gets none, exactly as on a phone with no
     *       profile at all.</li>
     * </ul>
     *
     * <p>There used to be one process-wide table of key roles, filled from whichever profile
     * was resolved first and asked without saying which keyboard the key came from.
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

    /**
     * Search all connected input devices for a matching configuration.
     */
    private static DeviceInputMapping findMappingFromAllDevices() {
        // Return cached result if already searched
        if (sResolvedDeviceIds.get(ALL_DEVICES)) {
            return sMappingByDeviceId.get(ALL_DEVICES);
        }

        int[] deviceIds = InputDevice.getDeviceIds();

        for (int id : deviceIds) {
            InputDevice device = InputDevice.getDevice(id);
            if (device == null) continue;

            // Only consider physical keyboards
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

        // Cache null result to prevent repeated searches
        cacheMapping(ALL_DEVICES, null);
        return null;
    }

    private static void cacheMapping(int deviceId, DeviceInputMapping mapping) {
        sMappingByDeviceId.put(deviceId, mapping);
        sResolvedDeviceIds.put(deviceId, true);
    }

    /**
     * Nothing used to invalidate the mapping cache when a keyboard was attached or detached --
     * only CustomDeviceConfigManager.setActiveConfigId, via {@link #resetConfig()}. A keyboard
     * plugged in after boot therefore reused whatever had already been resolved.
     */
    private static void registerDeviceListener(Context context) {
        if (sDeviceListenerRegistered) return;
        final InputManager inputManager =
                (InputManager) context.getApplicationContext().getSystemService(Context.INPUT_SERVICE);
        if (inputManager == null) return;
        sAppContext = context.getApplicationContext();
        // A device change invalidates what was resolved. Android reports the built-in keyboard as
        // "changed" on every IME subtype switch (the keyboard layout is re-assigned), so this is
        // frequent; the phone's profile is resolved again right away, and each keyboard's own
        // answer on its next key.
        inputManager.registerInputDeviceListener(new InputManager.InputDeviceListener() {
            @Override public void onInputDeviceAdded(int deviceId)   { resetAndResolve(); }
            @Override public void onInputDeviceRemoved(int deviceId) { resetAndResolve(); }
            @Override public void onInputDeviceChanged(int deviceId) { resetAndResolve(); }
        }, new Handler(Looper.getMainLooper()));
        sDeviceListenerRegistered = true;
    }

    /**
     * Get the Alt mappings table for a device.
     * Returns loaded table from XML file specified in device config.
     * 
     * @param context Application context
     * @param deviceId Device ID
     * @return AltMappingsTable or null if no custom mappings configured
     */
    public static AltMappingsTable getAltMappingsTable(Context context, int deviceId) {
        DeviceInputMapping mapping = resolveInputMapping(context, deviceId);
        
        if (mapping == null || mapping.mappingType != DeviceInputMapping.InputMappingType.CUSTOM_ALT_MAPPINGS) {
            return null;
        }
        
        if (mapping.altMappingsFile == null || mapping.altMappingsFile.isEmpty()) {
            if (BuildConfig.DEBUG) android.util.Log.w(TAG, "Device " + deviceId + " has CUSTOM_ALT_MAPPINGS but no file specified");
            return null;
        }
        
        // Load mappings from XML (cached)
        return AltMappingsParser.getCachedMappings(context, mapping.altMappingsFile);
    }
    
    /**
     * A device was added, removed or reported as changed: forgets what was resolved and resolves
     * the phone's profile again at once.
     */
    static synchronized void resetAndResolve() {
        // Not resetConfig(): a device coming, going or "changing" does not change what an XML
        // resource holds, so the parsed Alt tables stay. Android reports the built-in keyboard
        // as changed on every language switch, and dropping the tables there made the next
        // physical key parse its Alt table again on the main thread (Minimal Phone, Titan).
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

    /**
     * Makes {@code profile} the phone's profile without a configuration file or an input device
     * to match it against, neither of which a JVM test has; null installs "no profile". Undo
     * with {@link #resetConfig()}.
     */
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

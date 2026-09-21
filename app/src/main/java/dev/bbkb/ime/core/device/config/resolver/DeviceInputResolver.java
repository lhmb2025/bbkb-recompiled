package dev.bbkb.ime.core.device.config.resolver;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.view.InputDevice;

import dev.bbkb.ime.core.device.config.CustomDeviceConfigManager;
import dev.bbkb.ime.core.device.config.model.AltMappingsTable;
import dev.bbkb.ime.core.device.config.model.DeviceInputConfig;
import dev.bbkb.ime.core.device.config.model.DeviceInputMapping;
import dev.bbkb.ime.core.device.config.model.DeviceSettingOverride;
import dev.bbkb.ime.core.device.config.parser.AltMappingsParser;
import dev.bbkb.ime.core.device.config.parser.DeviceInputMappingParser;
import dev.bbkb.ime.BuildConfig;

/**
 * Resolves device-specific input behavior configurations.
 * Determines which devices should use custom Alt+key mappings vs standard behavior.
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
     * The ScancodeMappingResolver is a process-wide singleton holding one mapping, so it keeps
     * the historical "first non-null resolution wins" behaviour rather than being re-pointed at
     * whichever device was classified most recently.
     */
    private static boolean sScancodeResolverInitialized = false;

    private static boolean sDeviceListenerRegistered = false;

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

        String deviceName = device.getName();

        // Find mapping
        DeviceInputMapping mapping = sConfig.findMappingForDevice(deviceName);

        cacheMapping(deviceId, mapping);
        return mapping;
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
                String deviceName = device.getName();

                DeviceInputMapping mapping = sConfig.findMappingForDevice(deviceName);
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
        if (mapping != null && !sScancodeResolverInitialized) {
            ScancodeMappingResolver.getInstance().initialize(mapping);
            sScancodeResolverInitialized = true;
        }
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
        inputManager.registerInputDeviceListener(new InputManager.InputDeviceListener() {
            @Override public void onInputDeviceAdded(int deviceId)   { resetConfig(); }
            @Override public void onInputDeviceRemoved(int deviceId) { resetConfig(); }
            @Override public void onInputDeviceChanged(int deviceId) { resetConfig(); }
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
     * Reset cached configuration (for testing).
     */
    public static synchronized void resetConfig() {
        sConfig = null;
        sMappingByDeviceId.clear();
        sResolvedDeviceIds.clear();
        sScancodeResolverInitialized = false;
        AltMappingsParser.clearMappingsCache();
        ScancodeMappingResolver.getInstance().reset();
    }
}

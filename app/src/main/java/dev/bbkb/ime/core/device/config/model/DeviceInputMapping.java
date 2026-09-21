package dev.bbkb.ime.core.device.config.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents a device-specific input mapping configuration entry.
 * Enhanced to support flexible device matching, settings overrides, and layout overrides.
 */
public class DeviceInputMapping {
    
    public enum InputMappingType {
        DEFAULT,                 // Use Android's standard behavior
        CUSTOM_ALT_MAPPINGS      // Load Alt+key mappings from XML file
    }
    
    public String deviceName;               // For logging/display purposes
    public InputMappingType mappingType;
    public String altMappingsFile;          // XML file name for Alt+key mappings (without .xml extension)
    public String keypadType;               // Force specific physical keypad type (e.g., "3row", "4row")

    /**
     * Physical keypad layout override from {@code <keypad-layout>} — "qwerty", "qwertz" or
     * "azerty", already validated by the parser; null (the normal case) leaves
     * {@code KeypadLayoutDetector} to work it out from the firmware. This is the ONLY override
     * for the detected layout, and it exists for a unit whose firmware answers nothing; a shipped
     * config must not declare it, or every unit matching that config is pinned to one layout.
     */
    public String keypadLayout;
    public String description;              // Optional description for logging
    
    // NEW: Device matching criteria
    public DeviceMatchCriteria matchCriteria;
    
    // NEW: Layout overrides (structured)
    public Map<String, String> layoutOverrides = new HashMap<>();
    
    // NEW: Settings overrides
    public List<DeviceSettingOverride> settingsOverrides = new ArrayList<>();
    
    // NEW: Force PKB device type (from logic-organization-plan-2.md)
    /** Treat this device as a PKB device, regardless of hardware detection */
    public boolean forcePkbDevice = false;

    /** Force hasTouchKeypad() = true regardless of hardware detection. Set by <device-type>CKB</device-type>. */
    public boolean forceTouchKeypad = false;
    
    // NEW: Layout-specific alt override files (layout name → alt mappings XML file name)
    /** Maps keyboard layout (e.g. "azerty") to alt override file (e.g. "device_alt_mappings_azerty_overrides") */
    public Map<String, String> layoutAltOverrides = new HashMap<>();
    
    // NEW: Scancode-to-role mappings (from unified key mapping pipeline, schema v2.1)
    /** Per-key scancode/keycode → role mappings loaded from <scancode-mappings> XML section */
    public List<ScancodeMapping> scancodeMappings = new ArrayList<>();

    /** Optional per-device CKB letter grid (from a {@code <ckb-key-grid>} block); null if unspecified. */
    public CkbKeyGridConfig ckbKeyGridConfig;

    // NEW: Device KDB variant (schema v2.2)
    /** Engine keyboard-geometry (KDB) variant from {@code <kdb-variant>}: layouts in
     *  assets/kdb/&lt;variant&gt;/ override the root assets/kdb/ copies (per-layout, with root
     *  fallback). Requires the owned KDB module; null = default root layouts. */
    public String kdbVariant;

    /** Piecewise CKB sensor-Y -> engine-Y warp, "s0:a0,s1:a1,..." ascending breakpoints
     * (§8.7.12 retail swipe config). Null = identity. Applies only to the gesture engine feed. */
    public String ckbYWarp;

    public DeviceInputMapping() {
        // Default constructor
    }
    
    public DeviceInputMapping(String deviceName, InputMappingType mappingType, String altMappingsFile, String description) {
        this.deviceName = deviceName;
        this.mappingType = mappingType;
        this.altMappingsFile = altMappingsFile;
        this.keypadType = null;
        this.description = description;
    }
    
    /**
     * Check if this mapping matches the specified device.
     * Uses the new DeviceMatchCriteria if available, otherwise falls back to exact deviceName match.
     */
    public boolean matchesDevice(String deviceName, String vendorId, String productId) {
        if (matchCriteria != null && matchCriteria.hasAnyCriteria()) {
            return matchCriteria.matches(deviceName, vendorId, productId);
        }
        
        // Fallback to legacy exact match for backward compatibility
        return deviceName != null && deviceName.equals(this.deviceName);
    }
    
    /**
     * Get a setting override by key.
     * @param key Preference key to look up
     * @return DeviceSettingOverride if found, null otherwise
     */
    public DeviceSettingOverride getSettingOverride(String key) {
        if (key == null || settingsOverrides == null) {
            return null;
        }
        
        for (DeviceSettingOverride override : settingsOverrides) {
            if (key.equals(override.key)) {
                return override;
            }
        }
        
        return null;
    }
    
    /**
     * Check if a setting has an override.
     */
    public boolean hasSettingOverride(String key) {
        return getSettingOverride(key) != null;
    }
    
    /**
     * Get the device's multifunction key mapping, if the config declares one.
     * Devices are expected to declare at most one MULTIFUNCTION key; the first wins.
     *
     * @return the MULTIFUNCTION ScancodeMapping, or null if this device has none
     *         (the "Multifunction key" setting is hidden in that case)
     */
    public ScancodeMapping getMultifunctionKeyMapping() {
        if (scancodeMappings == null) {
            return null;
        }
        for (ScancodeMapping mapping : scancodeMappings) {
            if (mapping.role == KeyRole.MULTIFUNCTION) {
                return mapping;
            }
        }
        return null;
    }

    /**
     * Get the layout override for a specific layout resource.
     * @param originalLayout Original layout resource name
     * @return Override layout name if configured, null otherwise
     */
    public String getLayoutOverride(String originalLayout) {
        if (layoutOverrides == null || originalLayout == null) {
            return null;
        }
        return layoutOverrides.get(originalLayout);
    }
    
    @Override
    public String toString() {
        return "DeviceInputMapping{" +
                "deviceName='" + deviceName + '\'' +
                ", mappingType=" + mappingType +
                ", altMappingsFile='" + altMappingsFile + '\'' +
                ", keypadType='" + keypadType + '\'' +
                ", description='" + description + '\'' +
                ", matchCriteria=" + (matchCriteria != null ? matchCriteria.toString() : "null") +
                ", forcePkbDevice=" + forcePkbDevice +
                ", forceTouchKeypad=" + forceTouchKeypad +
                ", layoutOverrides=" + layoutOverrides.size() + " entries" +
                ", settingsOverrides=" + settingsOverrides.size() + " entries" +
                '}';
    }
}

package dev.bbkb.ime.core.device.config.model;

/**
 * Represents a device-specific settings override.
 * Allows forcing specific values or making settings read-only based on hardware capabilities.
 */
public class DeviceSettingOverride {
    
    public enum SettingType {
        BOOLEAN,
        INTEGER,
        STRING,
        LIST
    }
    
    public String key;                      // Preference key (e.g., "pref_override_device_meta_state")
    public SettingType type;                // Type of setting
    public Object forcedValue;              // Value to force (null = no force)
    /**
     * The device's default for this setting, from {@code default-value} (null = the app-wide
     * default). Unlike {@link #forcedValue} it neither locks nor overrides anything: it is what
     * an unset preference reads as on this device, and the user can still change it.
     */
    public Object defaultValue;
    public boolean readOnly;                // Whether setting can be changed
    public boolean hidden;                  // Whether setting is hidden from the UI entirely
    
    public DeviceSettingOverride() {
        this.readOnly = false;
        this.hidden = false;
    }
    
    public DeviceSettingOverride(String key, SettingType type, Object forcedValue, boolean readOnly) {
        this.key = key;
        this.type = type;
        this.forcedValue = forcedValue;
        this.readOnly = readOnly;
        this.hidden = false;
    }
    
    /**
     * Check if this override has a forced value.
     */
    public boolean hasForcedValue() {
        return forcedValue != null;
    }
    
    /**
     * Get the forced value as a boolean (if applicable).
     */
    public Boolean getForcedBooleanValue() {
        if (type == SettingType.BOOLEAN && forcedValue instanceof Boolean) {
            return (Boolean) forcedValue;
        }
        return null;
    }
    
    /**
     * Get the forced value as an integer (if applicable).
     */
    public Integer getForcedIntegerValue() {
        if (type == SettingType.INTEGER && forcedValue instanceof Integer) {
            return (Integer) forcedValue;
        }
        return null;
    }
    
    /**
     * Get the forced value as a string (if applicable).
     */
    public String getForcedStringValue() {
        if ((type == SettingType.STRING || type == SettingType.LIST) && forcedValue instanceof String) {
            return (String) forcedValue;
        }
        return null;
    }
    
    /**
     * The device default as a string (STRING and LIST settings), or null when there is none.
     */
    public String getDefaultStringValue() {
        if ((type == SettingType.STRING || type == SettingType.LIST) && defaultValue instanceof String) {
            return (String) defaultValue;
        }
        return null;
    }

    @Override
    public String toString() {
        return "DeviceSettingOverride{" +
                "key='" + key + '\'' +
                ", type=" + type +
                ", forcedValue=" + forcedValue +
                ", defaultValue=" + defaultValue +
                ", readOnly=" + readOnly +
                ", hidden=" + hidden +
                '}';
    }
}

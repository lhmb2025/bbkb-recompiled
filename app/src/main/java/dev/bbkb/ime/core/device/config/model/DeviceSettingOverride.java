package dev.bbkb.ime.core.device.config.model;

/** A device profile's override of one setting: a forced value, a device default, read-only or hidden. */
public class DeviceSettingOverride {
    
    public enum SettingType {
        BOOLEAN,
        INTEGER,
        STRING,
        LIST
    }

    public String key;                      // e.g. "pref_override_device_meta_state"
    public SettingType type;
    public Object forcedValue;              // null = not forced
    /** {@code default-value}: what an unset preference reads as here; the user can still change it. */
    public Object defaultValue;
    public boolean readOnly;
    public boolean hidden;                  // hidden from the UI entirely

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

    public boolean hasForcedValue() {
        return forcedValue != null;
    }

    public Boolean getForcedBooleanValue() {
        if (type == SettingType.BOOLEAN && forcedValue instanceof Boolean) {
            return (Boolean) forcedValue;
        }
        return null;
    }

    public Integer getForcedIntegerValue() {
        if (type == SettingType.INTEGER && forcedValue instanceof Integer) {
            return (Integer) forcedValue;
        }
        return null;
    }

    public String getForcedStringValue() {
        if ((type == SettingType.STRING || type == SettingType.LIST) && forcedValue instanceof String) {
            return (String) forcedValue;
        }
        return null;
    }

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

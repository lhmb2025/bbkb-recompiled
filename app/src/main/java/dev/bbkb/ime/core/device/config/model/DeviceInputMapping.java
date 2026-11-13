package dev.bbkb.ime.core.device.config.model;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One device profile: how a device is matched and what it overrides (Alt tables, layouts, settings, key roles). */
public class DeviceInputMapping {
    
    public enum InputMappingType {
        DEFAULT,                 // Android's standard behavior
        CUSTOM_ALT_MAPPINGS      // Alt+key mappings from an XML file
    }

    public String deviceName;
    public InputMappingType mappingType;
    public String altMappingsFile;          // Alt mappings XML name, without .xml
    /**
     * Alt characters written inline in {@code <alt-mappings>}, layered over {@link #altMappingsFile}'s
     * table (or alone); null when none. Lets imported profiles carry Alt characters, since
     * {@code <source>} can only name a built-in table.
     */
    public AltMappingsTable inlineAltMappings;
    /** {@link #inlineAltMappings} over the named table, built once; see {@code AltMappingsParser.tableFor}. */
    public volatile AltMappingsTable layeredAltMappings;
    public String keypadType;               // Forced keypad type, e.g. "3row", "4row"

    /**
     * {@code <keypad-layout>} ("qwerty", "qwertz", "azerty"), the only override of the detected layout;
     * null leaves it to {@code KeypadLayoutDetector}. Declare it only for a model sold in one layout,
     * never for a config matching several variants (athena); {@code ShippedDeviceConfigsTest} pins the list.
     */
    public String keypadLayout;
    /** {@code <keypad-layouts>}: shapes the model was sold in, offered on the Physical keyboard screen over detection. */
    public java.util.List<String> keypadLayoutOptions = new java.util.ArrayList<>();
    public String description;

    public DeviceMatchCriteria matchCriteria;

    public Map<String, String> layoutOverrides = new HashMap<>();

    public List<DeviceSettingOverride> settingsOverrides = new ArrayList<>();

    /** Treat this device as a PKB device, regardless of hardware detection. */
    public boolean forcePkbDevice = false;

    /** Force hasTouchKeypad() true regardless of hardware; set by {@code <device-type>CKB</device-type>}. */
    public boolean forceTouchKeypad = false;

    /** Keyboard layout (e.g. "azerty") to Alt override file (e.g. "device_alt_mappings_azerty_overrides"). */
    public Map<String, String> layoutAltOverrides = new HashMap<>();

    /** Per-key scancode/keycode to role mappings from {@code <scancode-mappings>}. */
    public List<ScancodeMapping> scancodeMappings = new ArrayList<>();

    /** Optional per-device CKB letter grid (from a {@code <ckb-key-grid>} block); null if unspecified. */
    public CkbKeyGridConfig ckbKeyGridConfig;

    /** {@code <kdb-variant>}: layouts in assets/kdb/&lt;variant&gt;/ override the root copies, per layout.
     *  Requires the owned KDB module; null = root layouts. */
    public String kdbVariant;

    /** Piecewise CKB sensor-Y to engine-Y warp, "s0:a0,s1:a1,..." ascending; null = identity.
     *  Applies only to the gesture engine feed. */
    public String ckbYWarp;

    /** The touch surface over the keys, from {@code <touch-keypad>}; null when undeclared. */
    public TouchKeypadConfig touchKeypad;

    /** Firmware behaviours to tolerate, from {@code <quirk name="..."/>}; empty when none. */
    public final EnumSet<DeviceQuirk> quirks = EnumSet.noneOf(DeviceQuirk.class);

    public boolean hasQuirk(DeviceQuirk quirk) {
        return quirk != null && quirks.contains(quirk);
    }

    /**
     * {@code <accessibility-helper>off}: the firmware delivers every key, so the accessibility key
     * interceptor component is disabled at runtime ({@code KeyInterceptorComponent}), leaving the
     * system Accessibility list, and its settings row is greyed out.
     */
    public boolean accessibilityHelperOff = false;

    public DeviceInputMapping() {
        // Fields are filled in by DeviceInputMappingParser.
    }
    
    public DeviceInputMapping(String deviceName, InputMappingType mappingType, String altMappingsFile, String description) {
        this.deviceName = deviceName;
        this.mappingType = mappingType;
        this.altMappingsFile = altMappingsFile;
        this.keypadType = null;
        this.description = description;
    }

    /** Matches by {@link #matchCriteria} when set, else by exact {@link #deviceName}. */
    public boolean matchesDevice(String deviceName, String vendorId, String productId) {
        if (matchCriteria != null && matchCriteria.hasAnyCriteria()) {
            return matchCriteria.matches(deviceName, vendorId, productId);
        }

        return deviceName != null && deviceName.equals(this.deviceName);
    }
    
    /**
     * True when the keyboard itself (name, vendor or product id) matches this profile, not only the
     * phone; a phone-only profile says nothing about a keyboard plugged into it.
     */
    public boolean namesInputDevice() {
        if (matchCriteria != null && matchCriteria.hasAnyCriteria()) {
            return matchCriteria.hasInputDeviceCriteria();
        }
        return deviceName != null && !deviceName.isEmpty();  // matched by exact name
    }

    /** Cache for {@link #roleFor}: (scanCode << 16 | keyCode) → mapping, {@link #NO_ROLE} for a miss. */
    private volatile android.util.SparseArray<ScancodeMapping> roleCache = new android.util.SparseArray<>();

    private static final ScancodeMapping NO_ROLE = new ScancodeMapping();

    /**
     * The key's role from {@code <scancode-mappings>} (first match wins), or null. Called per key
     * event, so cached copy-on-write and lock-free; the list must not change once in use. Callers
     * with a key event want {@code DeviceInputResolver.keyRole}, which checks the source keyboard.
     */
    public ScancodeMapping roleFor(int scanCode, int keyCode) {
        final List<ScancodeMapping> mappings = scancodeMappings;
        if (mappings == null || mappings.isEmpty()) {
            return null;
        }
        final int cacheKey = (scanCode << 16) | (keyCode & 0xFFFF);
        final ScancodeMapping cached = roleCache.get(cacheKey);
        if (cached != null) {
            return cached == NO_ROLE ? null : cached;
        }
        ScancodeMapping found = null;
        for (ScancodeMapping mapping : mappings) {
            if (mapping.matches(scanCode, keyCode)) {
                found = mapping;
                break;
            }
        }
        synchronized (this) {
            final android.util.SparseArray<ScancodeMapping> next = roleCache.clone();
            next.put(cacheKey, found != null ? found : NO_ROLE);
            roleCache = next;
        }
        return found;
    }

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

    public boolean hasSettingOverride(String key) {
        return getSettingOverride(key) != null;
    }

    /** The first MULTIFUNCTION mapping, or null (the "Multifunction key" setting is then hidden). */
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
                ", touchKeypad=" + touchKeypad +
                ", quirks=" + quirks +
                ", layoutOverrides=" + layoutOverrides.size() + " entries" +
                ", settingsOverrides=" + settingsOverrides.size() + " entries" +
                '}';
    }
}

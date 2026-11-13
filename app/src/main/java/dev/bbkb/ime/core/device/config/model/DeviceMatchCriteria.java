package dev.bbkb.ime.core.device.config.model;

import dev.bbkb.ime.core.shared.Logger;

import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The {@code <match>} block of a device config: constraints ANDed together, unset meaning "don't
 * care". Build-derived fields read {@link android.os.Build} at match time, so tests fake a device
 * by setting those static fields ({@code ReflectionHelpers} or {@code ShadowBuild}).
 */
public class DeviceMatchCriteria {

    /** What a {@code <match>} child element constrains. */
    public enum Field {
        DEVICE_NAME("device-name", false),
        /** The keyboard's vendor id as {@link #inputDeviceId hex} ({@code "0x2533"}); exact rules ignore case. */
        VENDOR_ID("vendor-id", true),
        /** The keyboard InputDevice's product id, in the same form as {@link #VENDOR_ID}. */
        PRODUCT_ID("product-id", true),
        /** Compared against {@link android.os.Build#BRAND}. */
        BRAND("brand", true),
        /** Compared against {@link android.os.Build#DEVICE} (e.g. "athena" for the KEY2). */
        BUILD_DEVICE("build-device", true),
        /** {@link android.os.Build#BOARD}: the only id separating Titan 2 (G71BoardV1) from Titan 2 Elite (G72BoardV1). */
        BOARD("board", true),
        /** Compared against {@link android.os.Build#DISPLAY}, the ROM's display build id. */
        DISPLAY("display", true),
        /** Compared against {@link android.os.Build#MODEL} ("Titan Pocket", "Titan Slim"). */
        MODEL("model", true),
        /** Compared against {@link android.os.Build#MANUFACTURER} ("A-gold" on the Titans). */
        MANUFACTURER("manufacturer", true);

        private final String tag;
        /** Exact rules ignore case for every field except {@code device-name}. */
        private final boolean ignoreCaseWhenExact;

        Field(String tag, boolean ignoreCaseWhenExact) {
            this.tag = tag;
            this.ignoreCaseWhenExact = ignoreCaseWhenExact;
        }

        /** The field a {@code <match>} child element name selects, or null if unrecognised. */
        public static Field of(String tag) {
            for (Field f : values()) {
                if (f.tag.equals(tag)) return f;
            }
            return null;
        }
    }

    /** A single exact-or-regex rule. Build one with {@link #exact} or {@link #regex}. */
    public static final class Rule {
        private final String pattern;
        /** null means this is an exact rule. */
        private final Pattern regex;

        private Rule(String pattern, Pattern regex) {
            this.pattern = pattern;
            this.regex = regex;
        }

        boolean matches(String value, boolean ignoreCaseWhenExact) {
            if (value == null) return false;
            if (regex != null) return regex.matcher(value).matches();
            return ignoreCaseWhenExact ? pattern.equalsIgnoreCase(value) : pattern.equals(value);
        }

        /** Case-sensitive form for InputDevice names outside {@code <match>} (the touch keypad's {@code <input-device>}). */
        public boolean matches(String value) {
            return matches(value, false);
        }

        /** The rule's text as the config wrote it: the exact value, or the regex source. */
        public String pattern() {
            return pattern;
        }

        /** True for a {@code regex=} rule, which must match the whole value. */
        public boolean isRegex() {
            return regex != null;
        }

        @Override
        public String toString() {
            return (regex == null ? "EXACT" : "REGEX") + ":'" + pattern + "'";
        }
    }

    /** Matches nothing: stands in for a {@code regex="..."} that does not compile. */
    private static final Pattern NEVER = Pattern.compile("(?!)");

    public static Rule exact(String pattern) {
        return new Rule(pattern, null);
    }

    public static Rule regex(String pattern) {
        try {
            return new Rule(pattern, Pattern.compile(pattern));
        } catch (PatternSyntaxException e) {
            Logger.error("DeviceMatchCriteria",
                    "Invalid regex pattern: " + pattern + " - " + e.getMessage());
            return new Rule(pattern, NEVER);
        }
    }

    private final EnumMap<Field, Rule> rules = new EnumMap<>(Field.class);

    public void set(Field field, Rule rule) {
        rules.put(field, rule);
    }

    /** The form vendor-id and product-id rules use: lower-case {@code 0x} hex, at least four digits. */
    public static String inputDeviceId(int id) {
        return String.format(java.util.Locale.ROOT, "0x%04x", id);
    }

    /**
     * True if every set criterion matches.
     *
     * @param vendorId {@link #inputDeviceId} form, or null with no InputDevice (a vendor-id rule then fails)
     * @param productId same form as vendorId, or null likewise
     */
    public boolean matches(String deviceName, String vendorId, String productId) {
        for (Map.Entry<Field, Rule> entry : rules.entrySet()) {
            final String value;
            switch (entry.getKey()) {
                case DEVICE_NAME:  value = deviceName; break;
                case VENDOR_ID:    value = vendorId;   break;
                case PRODUCT_ID:   value = productId;  break;
                case BRAND:        value = android.os.Build.BRAND;        break;
                case BOARD:        value = android.os.Build.BOARD;        break;
                case DISPLAY:      value = android.os.Build.DISPLAY;      break;
                case MODEL:        value = android.os.Build.MODEL;        break;
                case MANUFACTURER: value = android.os.Build.MANUFACTURER; break;
                default:           value = android.os.Build.DEVICE;       break;
            }
            if (!entry.getValue().matches(value, entry.getKey().ignoreCaseWhenExact)) return false;
        }
        return true;
    }

    /** True when a rule names the keyboard itself (name, vendor or product id), not only the phone. */
    public boolean hasInputDeviceCriteria() {
        return rules.containsKey(Field.DEVICE_NAME) || rules.containsKey(Field.VENDOR_ID)
                || rules.containsKey(Field.PRODUCT_ID);
    }

    /**
     * True for a vendor or product id rule: the profile describes a plugged-in keyboard, so it applies
     * only while that keyboard is attached, even when chosen by hand.
     */
    public boolean hasVendorOrProductRule() {
        return rules.containsKey(Field.VENDOR_ID) || rules.containsKey(Field.PRODUCT_ID);
    }

    public boolean hasAnyCriteria() {
        return !rules.isEmpty();
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("DeviceMatchCriteria{");
        String sep = "";
        for (Map.Entry<Field, Rule> entry : rules.entrySet()) {
            sb.append(sep).append(entry.getKey().tag).append('=').append(entry.getValue());
            sep = ", ";
        }
        return sb.append('}').toString();
    }
}

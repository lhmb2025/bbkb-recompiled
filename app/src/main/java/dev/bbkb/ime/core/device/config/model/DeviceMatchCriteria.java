package dev.bbkb.ime.core.device.config.model;

import dev.bbkb.ime.core.shared.Logger;

import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The {@code <match>} block of a device config: up to five constraints, ANDed together, with an
 * unset constraint meaning "don't care".
 *
 * <p>Audit W3-D: this was five parallel {@code MatchRule} fields, five near-identical checks in
 * {@link #matches}, two special-cased case-insensitive comparisons and a hand-rolled
 * {@code toString} with {@code first} bookkeeping. The five differ only in where the value to
 * compare comes from and whether an EXACT rule ignores case, so they are now one {@link Field}
 * table over an {@link EnumMap}. {@link Field#of} also gives the parser its element-name lookup,
 * replacing five copies of the same exact/regex attribute block.
 */
public class DeviceMatchCriteria {

    /** What a {@code <match>} child element constrains. */
    public enum Field {
        DEVICE_NAME("device-name", false),
        VENDOR_ID("vendor-id", false),
        PRODUCT_ID("product-id", false),
        /** Compared against {@link android.os.Build#BRAND}. */
        BRAND("brand", true),
        /** Compared against {@link android.os.Build#DEVICE} (e.g. "athena" for the KEY2). */
        BUILD_DEVICE("build-device", true);

        private final String tag;
        /** EXACT rules on the Build-derived fields compare case-insensitively; the others do not. */
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

        @Override
        public String toString() {
            return (regex == null ? "EXACT" : "REGEX") + ":'" + pattern + "'";
        }
    }

    /**
     * A pattern that matches nothing, standing in for a {@code regex="..."} that would not
     * compile — which used to be represented by a null compiled pattern plus a null branch in the
     * matcher. Either way an unparseable regex matches no device.
     */
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

    /**
     * Returns true if every specified criterion matches (AND logic); unspecified criteria are
     * ignored.
     *
     * @param deviceName Device name to check
     * @param vendorId Vendor ID to check (hex format, e.g., "0x1234")
     * @param productId Product ID to check (hex format, e.g., "0x5678")
     */
    public boolean matches(String deviceName, String vendorId, String productId) {
        for (Map.Entry<Field, Rule> entry : rules.entrySet()) {
            final String value;
            switch (entry.getKey()) {
                case DEVICE_NAME: value = deviceName; break;
                case VENDOR_ID:   value = vendorId;   break;
                case PRODUCT_ID:  value = productId;  break;
                case BRAND:       value = android.os.Build.BRAND;  break;
                default:          value = android.os.Build.DEVICE; break;
            }
            if (!entry.getValue().matches(value, entry.getKey().ignoreCaseWhenExact)) return false;
        }
        return true;
    }

    /** Check if at least one matching criterion is defined. */
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

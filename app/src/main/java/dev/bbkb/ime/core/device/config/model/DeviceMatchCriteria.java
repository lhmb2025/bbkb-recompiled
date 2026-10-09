package dev.bbkb.ime.core.device.config.model;

import dev.bbkb.ime.core.shared.Logger;

import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The {@code <match>} block of a device config: a set of constraints, ANDed together, with an
 * unset constraint meaning "don't care".
 *
 * <p>Audit W3-D: this was five parallel {@code MatchRule} fields, five near-identical checks in
 * {@link #matches}, two special-cased case-insensitive comparisons and a hand-rolled
 * {@code toString} with {@code first} bookkeeping. The fields differ only in where the value to
 * compare comes from and whether an EXACT rule ignores case, so they are one {@link Field} table
 * over an {@link EnumMap}. {@link Field#of} also gives the parser its element-name lookup,
 * replacing per-field copies of the same exact/regex attribute block.
 *
 * <p>The Build-derived fields read {@link android.os.Build}'s static fields at match time, so a
 * test fakes a device by setting those fields (Robolectric's {@code ReflectionHelpers} or
 * {@code ShadowBuild}) exactly as it already did for {@code build-device}.
 */
public class DeviceMatchCriteria {

    /** What a {@code <match>} child element constrains. */
    public enum Field {
        DEVICE_NAME("device-name", false),
        /**
         * The keyboard InputDevice's vendor id, written as {@link #inputDeviceId hex}
         * ({@code "0x2533"}). An EXACT rule ignores case so {@code 0x25AB} and {@code 0x25ab} agree.
         */
        VENDOR_ID("vendor-id", true),
        /** The keyboard InputDevice's product id, in the same form as {@link #VENDOR_ID}. */
        PRODUCT_ID("product-id", true),
        /** Compared against {@link android.os.Build#BRAND}. */
        BRAND("brand", true),
        /** Compared against {@link android.os.Build#DEVICE} (e.g. "athena" for the KEY2). */
        BUILD_DEVICE("build-device", true),
        /**
         * Compared against {@link android.os.Build#BOARD}. The Titan 2 and the Titan 2 Elite share
         * every other Build id and differ here (G71BoardV1 vs G72BoardV1).
         */
        BOARD("board", true),
        /** Compared against {@link android.os.Build#DISPLAY}, the ROM's display build id. */
        DISPLAY("display", true),
        /** Compared against {@link android.os.Build#MODEL} ("Titan Pocket", "Titan Slim"). */
        MODEL("model", true),
        /** Compared against {@link android.os.Build#MANUFACTURER} ("A-gold" on the Titans). */
        MANUFACTURER("manufacturer", true);

        private final String tag;
        /**
         * EXACT rules on the Build-derived fields and the hex ids compare case-insensitively;
         * {@code device-name} does not.
         */
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

        /**
         * Case-sensitive form, for rules that name an InputDevice outside a {@code <match>} block
         * (the {@code <touch-keypad>}'s {@code <input-device>}): device names are matched exactly
         * as {@code device-name} matches them.
         */
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
     * The form {@code vendor-id} / {@code product-id} rules are written in and compared against:
     * lower-case hex with a {@code 0x} prefix and at least four digits ({@code 0x2533}).
     */
    public static String inputDeviceId(int id) {
        return String.format(java.util.Locale.ROOT, "0x%04x", id);
    }

    /**
     * Returns true if every specified criterion matches (AND logic); unspecified criteria are
     * ignored.
     *
     * @param deviceName Device name to check
     * @param vendorId Vendor ID to check (hex format, e.g., "0x1234"; see {@link #inputDeviceId}),
     *        or null when the caller has no InputDevice in hand — a set vendor-id rule then fails
     * @param productId Product ID to check (hex format, e.g., "0x5678"), or null likewise
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

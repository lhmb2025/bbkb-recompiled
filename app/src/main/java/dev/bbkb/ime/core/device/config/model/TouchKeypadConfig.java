package dev.bbkb.ime.core.device.config.model;

import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * A device config's {@code <touch-keypad>} block: the touch surface over the physical keys.
 *
 * <pre>
 * &lt;touch-keypad range-x="1440" range-y="720" contacts="single" native-min-sdk="36" source="auto"&gt;
 *     &lt;input-device exact="touchPad"/&gt;
 * &lt;/touch-keypad&gt;
 * </pre>
 *
 * <ul>
 *   <li>{@code input-device}: the pad's InputDevice name; matched by name even without {@code SOURCE_TOUCHPAD}.</li>
 *   <li>{@code range-x} / {@code range-y}: sensor extents, used only when the InputDevice reports no ranges.</li>
 *   <li>{@code contacts}: {@code single} (sensor merges fingers) or {@code multi}.</li>
 *   <li>{@code native-min-sdk}: SDK from which the OS delivers pad events to the IME; absent means always.</li>
 *   <li>{@code source}: {@code auto} (default), {@code native} or {@code shizuku}.</li>
 * </ul>
 * Swipe typing stays off on such a pad until the profile also names a {@code <kdb-variant>}.
 */
public final class TouchKeypadConfig {

    /** {@link #nativeMinSdk} when the attribute is absent. */
    public static final int NATIVE_MIN_SDK_UNSET = -1;

    /** How many simultaneous contacts the sensor reports. */
    public enum Contacts {
        SINGLE, MULTI;

        @Nullable
        public static Contacts fromString(@Nullable String value) {
            if (value == null) return null;
            switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "single": return SINGLE;
                case "multi":  return MULTI;
                default:       return null;
            }
        }
    }

    /** The profile's pin on which touch source runs; see {@code TouchSourceSelector}. */
    public enum SourcePreference {
        /** The native source when the OS delivers the pad, otherwise the privileged reader. */
        AUTO,
        /** Only the native source; never the privileged reader on this device. */
        NATIVE,
        /** Always the privileged reader (for its exclusive grab), never the native source. */
        SHIZUKU;

        @Nullable
        public static SourcePreference fromString(@Nullable String value) {
            if (value == null) return null;
            switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "auto":    return AUTO;
                case "native":  return NATIVE;
                case "shizuku": return SHIZUKU;
                default:        return null;
            }
        }
    }

    /** The pad's InputDevice name rule; null when the block names no device. */
    @Nullable
    public DeviceMatchCriteria.Rule inputDeviceName;

    /** Sensor X extent, or 0 when the profile does not say. */
    public int rangeX;

    /** Sensor Y extent, or 0 when the profile does not say. */
    public int rangeY;

    public Contacts contacts = Contacts.SINGLE;

    /** See the class doc; {@link #NATIVE_MIN_SDK_UNSET} when absent. */
    public int nativeMinSdk = NATIVE_MIN_SDK_UNSET;

    public SourcePreference source = SourcePreference.AUTO;

    public boolean hasNativeMinSdk() {
        return nativeMinSdk != NATIVE_MIN_SDK_UNSET;
    }

    /** True when an InputDevice called {@code name} is this pad. */
    public boolean matchesInputDeviceName(@Nullable String name) {
        return inputDeviceName != null && inputDeviceName.matches(name);
    }

    @Override
    public String toString() {
        return "TouchKeypadConfig{inputDevice=" + inputDeviceName
                + ", range=" + rangeX + "x" + rangeY
                + ", contacts=" + contacts
                + ", nativeMinSdk=" + (hasNativeMinSdk() ? String.valueOf(nativeMinSdk) : "unset")
                + ", source=" + source + '}';
    }
}

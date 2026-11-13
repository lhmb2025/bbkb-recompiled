package dev.bbkb.ime.core.device.detection;

import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import dev.bbkb.ime.core.shared.Logger;

import java.util.Locale;

/**
 * Works out the physical keypad shape (QWERTY, QWERTZ, AZERTY). Sources abstain with null rather
 * than guess; the first definitive answer wins:
 * <ol>
 *   <li>{@link Source#USER}: the Keypad layout setting ({@code DeviceProfile.PREF_KEYPAD_LAYOUT}),
 *       offered only where the config lists {@code <keypad-layouts>};</li>
 *   <li>{@link Source#DEVICE_CONFIG}: {@code <keypad-layout>} on the device config. No shipped config
 *       declares one ({@code ShippedDeviceConfigsTest}), since it would disable every live source;</li>
 *   <li>{@link Source#DEVICE_NAME}: the keyboard's InputDevice name. Android picks the {@code .idc} by
 *       name, so a KEY2 AZERTY/QWERTZ keypad is {@code stmpe_azerty_keypad} / {@code stmpe_qwertz_keypad};
 *       QWERTY is plain {@code stmpe_keypad} and abstains;</li>
 *   <li>{@link Source#SYSPROP}: {@code ro.hwf.keypadlanguage}, then {@code ro.product.keypadlanguage}
 *       (some LineageOS builds set neither);</li>
 *   <li>{@link Source#KCM}: Alt-character fingerprint, see {@link #fromKeyCharacterMap};</li>
 *   <li>{@link Source#SCANCODE}: proof from a typed key, only after the user types, see
 *       {@link #observeKeyEvent};</li>
 *   <li>{@link Source#FALLBACK}: {@code "qwerty"}.</li>
 * </ol>
 * {@link #detect} is pure (testable with fake tables); {@link #detectLive} reads the real device.
 */
public final class KeypadLayoutDetector {

    private static final String TAG = "KeypadLayoutDetector";

    public static final String QWERTY = "qwerty";
    public static final String QWERTZ = "qwertz";
    public static final String AZERTY = "azerty";

    /** In descending confidence. */
    public enum Source { USER, DEVICE_CONFIG, DEVICE_NAME, SYSPROP, KCM, SCANCODE, FALLBACK }

    public static final class Detection {
        /** One of {@link #QWERTY}, {@link #QWERTZ}, {@link #AZERTY}. */
        @NonNull public final String layout;
        @NonNull public final Source source;

        private Detection(@NonNull String layout, @NonNull Source source) {
            this.layout = layout;
            this.source = source;
        }

        static Detection of(@NonNull String layout, @NonNull Source source) {
            return new Detection(layout, source);
        }

        @Override
        public String toString() {
            return layout + " (" + source + ")";
        }
    }

    /** {@link KeyCharacterMap#get(int, int)} shape (0 = no character), so tests can pass a table. */
    public interface AltCharLookup {
        int get(int keyCode, int metaState);
    }

    public interface StringSource {
        @Nullable String get();
    }

    /**
     * Layout proven by a scancode observation. Process-lifetime only on purpose: re-deriving costs
     * one keystroke, and persisting would make a wrong observation (a remapping a11y service) permanent.
     */
    @Nullable private static volatile String sObservedLayout;

    @Nullable private static volatile Detection sLast;

    private KeypadLayoutDetector() {}

    /**
     * Null or unrecognised inputs abstain, so a source with nothing to say never outranks one that does.
     *
     * @param configLayout   {@code <keypad-layout>} from the active device config
     * @param deviceName     built-in keyboard's {@code InputDevice.getName()}
     * @param keypadLanguageProp raw {@code ro.*.keypadlanguage} value, e.g. {@code "1 qwerty"}
     * @param altChars       Alt-character lookup against the loaded KeyCharacterMap
     * @param observedLayout layout proven by a scancode observation
     */
    @NonNull
    @VisibleForTesting
    public static Detection detect(@Nullable String configLayout,
                                   @Nullable String deviceName,
                                   @Nullable String keypadLanguageProp,
                                   @Nullable AltCharLookup altChars,
                                   @Nullable String observedLayout) {
        return detect(null, configLayout, deviceName, keypadLanguageProp, altChars, observedLayout);
    }

    @NonNull
    @VisibleForTesting
    public static Detection detect(@Nullable String userLayout,
                                   @Nullable String configLayout,
                                   @Nullable String deviceName,
                                   @Nullable String keypadLanguageProp,
                                   @Nullable AltCharLookup altChars,
                                   @Nullable String observedLayout) {
        String layout = normalize(userLayout);
        if (layout != null) return Detection.of(layout, Source.USER);

        layout = normalize(configLayout);
        if (layout != null) return Detection.of(layout, Source.DEVICE_CONFIG);

        layout = fromDeviceName(deviceName);
        if (layout != null) return Detection.of(layout, Source.DEVICE_NAME);

        layout = fromKeypadLanguageProp(keypadLanguageProp);
        if (layout != null) return Detection.of(layout, Source.SYSPROP);

        layout = fromKeyCharacterMap(altChars);
        if (layout != null) return Detection.of(layout, Source.KCM);

        layout = normalize(observedLayout);
        if (layout != null) return Detection.of(layout, Source.SCANCODE);

        return Detection.of(QWERTY, Source.FALLBACK);
    }

    /** Canonical layout name or null. Also the device-config parser's validator, so XML typos fail at parse. */
    @Nullable
    public static String normalize(@Nullable String value) {
        if (value == null) return null;
        final String v = value.trim().toLowerCase(Locale.ROOT);
        if (QWERTY.equals(v) || QWERTZ.equals(v) || AZERTY.equals(v)) return v;
        return null;
    }

    @Nullable
    @VisibleForTesting
    static String fromDeviceName(@Nullable String deviceName) {
        if (deviceName == null) return null;
        final String n = deviceName.toLowerCase(Locale.ROOT);
        if (n.contains(AZERTY)) return AZERTY;
        if (n.contains(QWERTZ)) return QWERTZ;
        if (n.contains(QWERTY)) return QWERTY;
        return null;
    }

    /**
     * Stock value is {@code "<index> <name>"} ({@code "1 qwerty"} on athena); a bare name works too.
     * Anything else, including the empty string of an unset property, abstains.
     */
    @Nullable
    @VisibleForTesting
    static String fromKeypadLanguageProp(@Nullable String raw) {
        if (raw == null) return null;
        final String trimmed = raw.trim();
        final int space = trimmed.lastIndexOf(' ');
        if (space >= 0) {
            final String afterSpace = normalize(trimmed.substring(space + 1));
            if (afterSpace != null) return afterSpace;
        }
        return normalize(trimmed);
    }

    /**
     * From the KEY2's {@code /vendor/usr/keychars/stmpe{,_azerty,_qwertz}.kcm}:
     *
     * <pre>
     *            Alt+A   Alt+Y
     *   QWERTY    '*'     ')'
     *   AZERTY    '#'     ')'
     *   QWERTZ    '*'     '7'
     * </pre>
     *
     * Alt chars keep their positions while letters move. Both 0 means not one of these keymaps
     * (Generic.kcm has no Alt layer): abstain, not QWERTY.
     */
    @Nullable
    @VisibleForTesting
    static String fromKeyCharacterMap(@Nullable AltCharLookup altChars) {
        if (altChars == null) return null;
        final int altA = altChars.get(KeyEvent.KEYCODE_A, KeyEvent.META_ALT_ON);
        final int altY = altChars.get(KeyEvent.KEYCODE_Y, KeyEvent.META_ALT_ON);
        if (altA == 0 && altY == 0) return null;
        if (altA == '#') return AZERTY;
        if (altY == '7') return QWERTZ;
        return QWERTY;
    }

    /**
     * The layout one {@code (scanCode, keyCode)} pair proves, or null:
     *
     * <pre>
     *   scancode 16 -> A on AZERTY, Q on QWERTY and QWERTZ
     *   scancode 21 -> Z on QWERTZ, Y on QWERTY and AZERTY
     * </pre>
     *
     * Scancode 50 is M on all three (the KEY2 AZERTY does not move M), so it proves nothing.
     */
    @Nullable
    @VisibleForTesting
    static String layoutFromScancode(int scanCode, int keyCode) {
        if (scanCode == 16) {
            if (keyCode == KeyEvent.KEYCODE_A) return AZERTY;
            if (keyCode == KeyEvent.KEYCODE_Q) return QWERTY;
        } else if (scanCode == 21) {
            if (keyCode == KeyEvent.KEYCODE_Z) return QWERTZ;
            if (keyCode == KeyEvent.KEYCODE_Y) return QWERTY;
        }
        return null;
    }

    /**
     * Called on every physical key down, so it must stay cheap.
     *
     * @return true when the layout changed and the caller must rebuild the device profile; false
     *         when a higher source already answered (recorded anyway), or nothing new was proven
     */
    public static synchronized boolean observeKeyEvent(int scanCode, int keyCode) {
        final String observed = layoutFromScancode(scanCode, keyCode);
        if (observed == null) return false;
        if (observed.equals(sObservedLayout)) return false;
        sObservedLayout = observed;

        final Detection last = sLast;
        if (last != null && last.source != Source.FALLBACK && last.source != Source.SCANCODE) {
            return false;
        }
        final boolean layoutChanged = last == null || !observed.equals(last.layout);
        sLast = Detection.of(observed, Source.SCANCODE);
        if (layoutChanged) {
            Logger.info(TAG, "scancode observation (" + scanCode + "->" + keyCode + ") upgrades"
                    + " keypad layout to " + observed
                    + " (was " + (last == null ? "unknown" : last.toString()) + ")");
        }
        return layoutChanged;
    }

    /** Runs {@link #detect} on the real device and records the result for {@link #observeKeyEvent}. */
    @NonNull
    public static Detection detectLive(@Nullable String configLayout,
                                       @Nullable KeyboardDeviceInfo primaryKeyboard) {
        return detectLive(null, configLayout, primaryKeyboard);
    }

    @NonNull
    public static Detection detectLive(@Nullable String userLayout,
                                       @Nullable String configLayout,
                                       @Nullable KeyboardDeviceInfo primaryKeyboard) {
        final Detection detection = detect(
                userLayout,
                configLayout,
                primaryKeyboard != null ? primaryKeyboard.getName() : null,
                HardwareProbe.getKeypadLanguageProp(),
                altCharLookupFor(primaryKeyboard),
                sObservedLayout);
        sLast = detection;
        Logger.info(TAG, "keypad layout = " + detection.layout + ", source = " + detection.source
                + " (user=" + (userLayout == null ? "none" : userLayout)
                + ", config=" + (configLayout == null ? "none" : configLayout)
                + ", name=" + (primaryKeyboard != null ? primaryKeyboard.getName() : "none")
                + ", prop=" + HardwareProbe.getKeypadLanguageProp()
                + ", observed=" + (sObservedLayout == null ? "none" : sObservedLayout) + ")");
        return detection;
    }

    @Nullable
    public static Detection last() {
        return sLast;
    }

    /**
     * Masks the combining-accent flag (as {@code AuxCharacterResolver} does) so a dead key compares as
     * its base char. Null with no keyboard or no map (JVM, device gone mid-scan).
     */
    @Nullable
    private static AltCharLookup altCharLookupFor(@Nullable KeyboardDeviceInfo primaryKeyboard) {
        if (primaryKeyboard == null) return null;
        final KeyCharacterMap map;
        try {
            final InputDevice device = InputDevice.getDevice(primaryKeyboard.getDeviceId());
            map = device != null ? device.getKeyCharacterMap() : null;
        } catch (Throwable t) {
            return null;
        }
        if (map == null) return null;
        return (keyCode, metaState) -> {
            int c = map.get(keyCode, metaState);
            if ((c & KeyCharacterMap.COMBINING_ACCENT) != 0) {
                c &= KeyCharacterMap.COMBINING_ACCENT_MASK;
            }
            return c;
        };
    }

    @VisibleForTesting
    public static synchronized void resetForTest() {
        sObservedLayout = null;
        sLast = null;
    }

    @VisibleForTesting
    public static synchronized void recordForTest(@NonNull String layout, @NonNull Source source) {
        sLast = Detection.of(layout, source);
    }
}

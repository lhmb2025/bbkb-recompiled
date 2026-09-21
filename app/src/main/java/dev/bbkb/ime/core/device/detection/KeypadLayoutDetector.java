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
 * Works out which physical keypad a BlackBerry-style handset has — QWERTY, QWERTZ or AZERTY —
 * from several independent sources, in confidence order.
 *
 * <p>This replaces a single sysprop read that defaulted to {@code "qwerty"} whenever it found
 * nothing. That default is wrong on exactly the units that need the answer most: the stock KEY2
 * firmware sets {@code ro.hwf.keypadlanguage} (the owner's athena reports {@code "1 qwerty"}),
 * but several LineageOS builds do not set it at all, so an AZERTY or QWERTZ KEY2 running one of
 * them silently rendered the QWERTY symbol rows. The fix is not to guess harder from one source
 * but to ask more of them, and to let "I don't know" propagate instead of being laundered into
 * an answer.
 *
 * <p><b>Source order</b> (first definitive answer wins):
 * <ol>
 *   <li>{@link Source#DEVICE_CONFIG} — a {@code <keypad-layout>} element on the active device
 *       config XML. The single override, for a unit whose firmware tells us nothing; no shipped
 *       config declares one (pinned by {@code ShippedDeviceConfigsTest}) because hard-coding it
 *       would disable every live source below on every unit sharing that config.</li>
 *   <li>{@link Source#DEVICE_NAME} — the built-in keyboard's {@code InputDevice} name. Android
 *       picks {@code /vendor/usr/idc/<name>.idc} <em>by device name</em>, which is how the
 *       KEY2's three vendor keymaps ({@code stmpe}, {@code stmpe_azerty}, {@code stmpe_qwertz})
 *       are selected at all: an AZERTY unit's keypad must be named {@code stmpe_azerty_keypad}
 *       and a QWERTZ one {@code stmpe_qwertz_keypad} for its own {@code .kl}/{@code .kcm} to
 *       load. The owner's QWERTY unit is plain {@code stmpe_keypad}, so this source abstains
 *       there and the sysprop answers.</li>
 *   <li>{@link Source#SYSPROP} — {@code ro.hwf.keypadlanguage}, then
 *       {@code ro.product.keypadlanguage}.</li>
 *   <li>{@link Source#KCM} — the Alt-character fingerprint of the loaded KeyCharacterMap. Alt
 *       characters are position-invariant across the three KEY2 keymaps while the letters move,
 *       so two lookups separate them; see {@link #fromKeyCharacterMap}.</li>
 *   <li>{@link Source#SCANCODE} — what the keypad actually sent. A scancode/keycode pair
 *       observed on the hardware key path is proof, but it only arrives after the user types,
 *       so it ranks below the sources available at init; see {@link #observeKeyEvent}.</li>
 *   <li>{@link Source#FALLBACK} — {@code "qwerty"}, the majority shape.</li>
 * </ol>
 *
 * <p>Every source is a pure function of values handed to it, so {@link #detect} can be driven
 * from fake tables in a unit test without a real {@link KeyCharacterMap} or a real
 * {@link InputDevice}. {@link #detectLive} is the thin adapter that reads the real ones.
 *
 * <p>There is deliberately no user-facing setting for this.
 */
public final class KeypadLayoutDetector {

    private static final String TAG = "KeypadLayoutDetector";

    public static final String QWERTY = "qwerty";
    public static final String QWERTZ = "qwertz";
    public static final String AZERTY = "azerty";

    /** Where a {@link Detection} came from, in descending confidence. */
    public enum Source { DEVICE_CONFIG, DEVICE_NAME, SYSPROP, KCM, SCANCODE, FALLBACK }

    /** An answer, and the source that produced it. */
    public static final class Detection {
        /** One of {@link #QWERTY}, {@link #QWERTZ}, {@link #AZERTY}; never null. */
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

    /**
     * {@code (keyCode, metaState) -> unicode char}, 0 when the map has no character. The shape of
     * {@link KeyCharacterMap#get(int, int)}, as a parameter so tests can hand over a table.
     */
    public interface AltCharLookup {
        int get(int keyCode, int metaState);
    }

    /** A string that may be absent — a system property, a device name. */
    public interface StringSource {
        @Nullable String get();
    }

    // ── mutable process state ────────────────────────────────────────────────

    /**
     * The layout proven by a scancode observation, or null before one arrives. Process-lifetime
     * only, deliberately: the codebase has no store for a derived hardware fact (the prefs are
     * user settings, and the owner rejected a setting for this), and re-deriving it costs one
     * keystroke on the next launch. Writing it to prefs would also make a wrong observation —
     * say from a remapping accessibility service — permanent.
     */
    @Nullable private static volatile String sObservedLayout;

    /** The detection installed by the last {@link #detectLive}; null before the first one. */
    @Nullable private static volatile Detection sLast;

    private KeypadLayoutDetector() {} // No instantiation

    // ── the pure detector ────────────────────────────────────────────────────

    /**
     * Runs the source chain. Every input is optional; null (or an unrecognised value) means that
     * source abstains, which is the whole point — a source with nothing to say must not be able
     * to install a guess ahead of a source that does.
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
        String layout = normalize(configLayout);
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

    /**
     * Canonicalises a layout name, or returns null if it is not one of the three this app can
     * render. Also the validator the device-config parser uses, so an XML typo is rejected at
     * parse time rather than silently becoming a layout nothing matches.
     */
    @Nullable
    public static String normalize(@Nullable String value) {
        if (value == null) return null;
        final String v = value.trim().toLowerCase(Locale.ROOT);
        if (QWERTY.equals(v) || QWERTZ.equals(v) || AZERTY.equals(v)) return v;
        return null;
    }

    /**
     * Source 2. The vendor {@code .idc} files are matched by device name, so a non-QWERTY KEY2
     * keypad must carry the variant in its name for its own keymap to load at all; other vendors
     * name variant keypads the same way. {@code qwerty} is accepted too, though no known unit
     * spells it out — a name that says which one it is, is an answer either way.
     */
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
     * Source 3. The stock value is {@code "<index> <name>"} ({@code "1 qwerty"} on athena), so the
     * word after the space is the layout; a bare {@code "qwerty"} is accepted as well. Anything
     * that does not name one of the three layouts — including the empty string, which is what an
     * unset-but-present property reads as — is no answer. The old code returned {@code "qwerty"}
     * here, which is the bug this class exists to fix.
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
     * Source 4. Read from the three vendor keymaps the KEY2 ships
     * ({@code /vendor/usr/keychars/stmpe{,_azerty,_qwertz}.kcm}):
     *
     * <pre>
     *            Alt+A   Alt+Y
     *   QWERTY    '*'     ')'
     *   AZERTY    '#'     ')'
     *   QWERTZ    '*'     '7'
     * </pre>
     *
     * Alt characters keep their key positions across the three while the letters move, so
     * {@code A -> '#'} means AZERTY and {@code Y -> '7'} means QWERTZ. Both lookups coming back
     * 0 means this is not one of those keymaps at all (Generic.kcm has no Alt layer), which is
     * an abstention, not QWERTY.
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

    // ── source 5: scancode observation ───────────────────────────────────────

    /**
     * The layout proven by one {@code (scanCode, keyCode)} pair, or null if the pair says
     * nothing. Two keys move between the three keymaps and nothing else has to be known:
     *
     * <pre>
     *   scancode 16 -> A on AZERTY, Q on QWERTY and QWERTZ
     *   scancode 21 -> Z on QWERTZ, Y on QWERTY and AZERTY
     * </pre>
     *
     * (Scancode 50 is M on all three — the KEY2's AZERTY keypad does not move M the way a PC
     * AZERTY layout does — so it is useless here and is not consulted.)
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
     * Feeds one hardware key event to source 5. Called from the hardware key path on every
     * physical key down; almost every call is two integer compares and a return.
     *
     * @return true when this observation changed the layout the app should be using, i.e. the
     *         caller must rebuild the device profile. False whenever a higher-ranked source has
     *         already answered (the observation is recorded but cannot displace it), when the
     *         pair proves nothing, and when it proves what is already in force.
     */
    public static synchronized boolean observeKeyEvent(int scanCode, int keyCode) {
        final String observed = layoutFromScancode(scanCode, keyCode);
        if (observed == null) return false;
        if (observed.equals(sObservedLayout)) return false;  // already seen this one
        sObservedLayout = observed;

        final Detection last = sLast;
        if (last != null && last.source != Source.FALLBACK && last.source != Source.SCANCODE) {
            // Config, device name, sysprop or KCM already answered; source 5 ranks below them.
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

    // ── live adapter ─────────────────────────────────────────────────────────

    /**
     * Runs {@link #detect} against the real device: the config layout the caller resolved, the
     * primary keyboard's name and KeyCharacterMap, the keypad-language system properties, and
     * whatever a scancode observation has proven so far. Logs the outcome, and records it so
     * {@link #observeKeyEvent} knows whether a later observation can change anything.
     */
    @NonNull
    public static Detection detectLive(@Nullable String configLayout,
                                       @Nullable KeyboardDeviceInfo primaryKeyboard) {
        final Detection detection = detect(
                configLayout,
                primaryKeyboard != null ? primaryKeyboard.getName() : null,
                HardwareProbe.getKeypadLanguageProp(),
                altCharLookupFor(primaryKeyboard),
                sObservedLayout);
        sLast = detection;
        Logger.info(TAG, "keypad layout = " + detection.layout + ", source = " + detection.source
                + " (config=" + (configLayout == null ? "none" : configLayout)
                + ", name=" + (primaryKeyboard != null ? primaryKeyboard.getName() : "none")
                + ", prop=" + HardwareProbe.getKeypadLanguageProp()
                + ", observed=" + (sObservedLayout == null ? "none" : sObservedLayout) + ")");
        return detection;
    }

    /** The last live detection, or null before {@link #detectLive} has run. */
    @Nullable
    public static Detection last() {
        return sLast;
    }

    /**
     * Wraps the primary keyboard's {@link KeyCharacterMap} as an {@link AltCharLookup}, masking
     * the combining-accent flag the way {@code AuxCharacterResolver} does so a dead-key entry
     * compares as its base character. Null when there is no physical keyboard, or when the
     * framework will not hand over its map (a JVM, a device that vanished mid-scan).
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

    /**
     * Drops the scancode observation and the recorded detection. For tests only — the process
     * state above is otherwise write-once-per-boot.
     */
    @VisibleForTesting
    public static synchronized void resetForTest() {
        sObservedLayout = null;
        sLast = null;
    }

    /**
     * Installs a detection as if {@link #detectLive} had produced it, so a test can put
     * {@link #observeKeyEvent} behind a specific higher-ranked answer. Tests only.
     */
    @VisibleForTesting
    public static synchronized void recordForTest(@NonNull String layout, @NonNull Source source) {
        sLast = Detection.of(layout, source);
    }
}

package dev.bbkb.ime.core.device.touch;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dev.bbkb.ime.core.device.config.model.TouchKeypadConfig;
import dev.bbkb.ime.core.device.config.model.TouchKeypadConfig.SourcePreference;

/**
 * Which keypad touch source should run on this device. A pure function of the profile's
 * {@code <touch-keypad>}, the Android version and whether the OS has enumerated the pad, so it is
 * the same answer everywhere it is asked (the IME, and later a settings screen).
 *
 * <p>Selection is automatic, and a profile can pin it with {@code source}:
 * <ul>
 *   <li>{@code auto} (default) — the native source when the OS delivers the pad to a focused IME,
 *       otherwise the privileged (Shizuku) reader;</li>
 *   <li>{@code native} — the native source only; never the privileged reader on this device;</li>
 *   <li>{@code shizuku} — always the privileged reader (for its exclusive grab); the native
 *       source stays off.</li>
 * </ul>
 * "The OS delivers the pad" is {@code native-min-sdk <= SDK_INT} when the profile gives one, and
 * otherwise "the pad is enumerated".
 *
 * <p><b>The Shizuku seam.</b> The privileged reader is built separately. Until it lands, a
 * {@link Choice#SHIZUKU} selection reports {@link TouchSourceStatus.Reason#SHIZUKU_NOT_YET_AVAILABLE}
 * through {@link Selection#shizukuStatus()}, and nothing runs; the native source still honours the
 * selection by staying off.
 */
public final class TouchSourceSelector {

    private TouchSourceSelector() {}

    /** The source that should run. */
    public enum Choice { NONE, NATIVE, SHIZUKU }

    /** A selection and why it was made. */
    public static final class Selection {
        @NonNull public final Choice choice;
        @NonNull public final TouchSourceStatus.Reason reason;
        /** The profile's pin, or null when the profile declares no touch keypad. */
        @Nullable public final SourcePreference preference;

        Selection(@NonNull Choice choice, @NonNull TouchSourceStatus.Reason reason,
                  @Nullable SourcePreference preference) {
            this.choice = choice;
            this.reason = reason;
            this.preference = preference;
        }

        /**
         * The native source's status as far as the selection decides it: NOT_APPLICABLE (with the
         * reason) unless it was chosen, in which case IDLE until the source attaches.
         */
        @NonNull
        public TouchSourceStatus nativeStatus() {
            if (choice == Choice.NATIVE) {
                return TouchSourceStatus.of(TouchSourceStatus.State.IDLE, TouchSourceStatus.Reason.OK);
            }
            return TouchSourceStatus.of(TouchSourceStatus.State.NOT_APPLICABLE, reason);
        }

        /**
         * The privileged reader's status. Chosen: UNAVAILABLE, SHIZUKU_NOT_YET_AVAILABLE (the seam).
         * Not chosen: NOT_APPLICABLE, with the reason it is not needed or not allowed.
         */
        @NonNull
        public TouchSourceStatus shizukuStatus() {
            if (choice == Choice.SHIZUKU) {
                return TouchSourceStatus.of(TouchSourceStatus.State.UNAVAILABLE,
                        TouchSourceStatus.Reason.SHIZUKU_NOT_YET_AVAILABLE);
            }
            if (choice == Choice.NATIVE) {
                return TouchSourceStatus.of(TouchSourceStatus.State.NOT_APPLICABLE,
                        preference == SourcePreference.NATIVE
                                ? TouchSourceStatus.Reason.PROFILE_PINS_NATIVE
                                : TouchSourceStatus.Reason.OK);
            }
            return TouchSourceStatus.of(TouchSourceStatus.State.NOT_APPLICABLE, reason);
        }

        @Override
        public String toString() {
            return "Selection{" + choice + ", " + reason + ", pin=" + preference + '}';
        }
    }

    /**
     * Whether the OS hands the pad's events to a focused IME window: from {@code native-min-sdk}
     * when the profile gives one, otherwise whenever the pad is enumerated.
     */
    public static boolean osDeliversPad(@NonNull TouchKeypadConfig declared, int sdkInt,
                                        boolean padEnumerated) {
        return declared.hasNativeMinSdk() ? sdkInt >= declared.nativeMinSdk : padEnumerated;
    }

    /**
     * @param declared the profile's {@code <touch-keypad>}, or null
     * @param sdkInt {@code Build.VERSION.SDK_INT}
     * @param padEnumerated whether the scanner found the pad's InputDevice
     */
    @NonNull
    public static Selection select(@Nullable TouchKeypadConfig declared, int sdkInt,
                                   boolean padEnumerated) {
        if (declared == null) {
            return new Selection(Choice.NONE, TouchSourceStatus.Reason.NO_TOUCH_KEYPAD_DECLARED, null);
        }
        final SourcePreference pin = declared.source;
        if (pin == SourcePreference.SHIZUKU) {
            return new Selection(Choice.SHIZUKU, TouchSourceStatus.Reason.PROFILE_PINS_SHIZUKU, pin);
        }
        if (osDeliversPad(declared, sdkInt, padEnumerated)) {
            return new Selection(Choice.NATIVE, TouchSourceStatus.Reason.OK, pin);
        }
        final TouchSourceStatus.Reason why = declared.hasNativeMinSdk()
                ? TouchSourceStatus.Reason.OS_DOES_NOT_DELIVER_PAD
                : TouchSourceStatus.Reason.PAD_NOT_ENUMERATED;
        if (pin == SourcePreference.NATIVE) {
            return new Selection(Choice.NONE, why, pin);
        }
        return new Selection(Choice.SHIZUKU, why, pin);
    }
}

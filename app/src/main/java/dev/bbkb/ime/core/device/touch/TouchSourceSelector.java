package dev.bbkb.ime.core.device.touch;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dev.bbkb.ime.core.device.config.model.TouchKeypadConfig;
import dev.bbkb.ime.core.device.config.model.TouchKeypadConfig.SourcePreference;

/**
 * Which keypad touch source should run on this device. A pure function of the profile's
 * {@code <touch-keypad>}, the Android version and whether the OS has enumerated the pad, so it is
 * the same answer everywhere it is asked (the IME, and the Touch surface helper screen).
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
 * <p>A selection says which source runs, not how that source is doing: the reader's live state
 * (Shizuku installed, running, granted, streaming) belongs to {@link ShizukuTouchSource}, and
 * {@link Selection#shizukuStatus(TouchSourceStatus)} passes it through when the reader is the
 * choice.
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
        /** Whether the OS had enumerated the pad's InputDevice when this was decided. */
        public final boolean padEnumerated;
        /**
         * Whether the native route can apply on this Android version at all: a declared pad, not
         * pinned to the reader, and {@code native-min-sdk} (when given) reached. It may still wait
         * for the pad to be enumerated (the Titan 2's Scroll assistant).
         */
        public final boolean nativeRouteApplies;

        Selection(@NonNull Choice choice, @NonNull TouchSourceStatus.Reason reason,
                  @Nullable SourcePreference preference, boolean padEnumerated,
                  boolean nativeRouteApplies) {
            this.choice = choice;
            this.reason = reason;
            this.preference = preference;
            this.padEnumerated = padEnumerated;
            this.nativeRouteApplies = nativeRouteApplies;
        }

        /**
         * The native source's status as far as the selection decides it. Chosen: IDLE until the
         * source attaches, or UNAVAILABLE / PAD_NOT_ENUMERATED while the OS has not enumerated
         * the pad (on the Titan 2 that is the OEM Scroll assistant being off). Not chosen:
         * NOT_APPLICABLE, with the reason.
         */
        @NonNull
        public TouchSourceStatus nativeStatus() {
            if (choice == Choice.NATIVE) {
                return padEnumerated
                        ? TouchSourceStatus.of(TouchSourceStatus.State.IDLE, TouchSourceStatus.Reason.OK)
                        : TouchSourceStatus.of(TouchSourceStatus.State.UNAVAILABLE,
                                TouchSourceStatus.Reason.PAD_NOT_ENUMERATED);
            }
            return TouchSourceStatus.of(TouchSourceStatus.State.NOT_APPLICABLE, reason);
        }

        /**
         * The privileged reader's status. Chosen: {@code reader}, the reader's own live status
         * ({@link ShizukuTouchSource#statusOf}). Not chosen: NOT_APPLICABLE, with the reason it is
         * not needed or not allowed.
         */
        @NonNull
        public TouchSourceStatus shizukuStatus(@NonNull TouchSourceStatus reader) {
            if (choice == Choice.SHIZUKU) {
                return reader;
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
            return "Selection{" + choice + ", " + reason + ", pin=" + preference
                    + ", padEnumerated=" + padEnumerated
                    + ", nativeRouteApplies=" + nativeRouteApplies + '}';
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

    /** See {@link Selection#nativeRouteApplies}. */
    static boolean nativeRouteApplies(@NonNull TouchKeypadConfig declared, int sdkInt) {
        return declared.source != SourcePreference.SHIZUKU
                && (!declared.hasNativeMinSdk() || sdkInt >= declared.nativeMinSdk);
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
            return new Selection(Choice.NONE, TouchSourceStatus.Reason.NO_TOUCH_KEYPAD_DECLARED,
                    null, padEnumerated, false);
        }
        final SourcePreference pin = declared.source;
        final boolean nativeApplies = nativeRouteApplies(declared, sdkInt);
        if (pin == SourcePreference.SHIZUKU) {
            return new Selection(Choice.SHIZUKU, TouchSourceStatus.Reason.PROFILE_PINS_SHIZUKU,
                    pin, padEnumerated, nativeApplies);
        }
        if (osDeliversPad(declared, sdkInt, padEnumerated)) {
            return new Selection(Choice.NATIVE, TouchSourceStatus.Reason.OK, pin, padEnumerated,
                    nativeApplies);
        }
        final TouchSourceStatus.Reason why = declared.hasNativeMinSdk()
                ? TouchSourceStatus.Reason.OS_DOES_NOT_DELIVER_PAD
                : TouchSourceStatus.Reason.PAD_NOT_ENUMERATED;
        if (pin == SourcePreference.NATIVE) {
            return new Selection(Choice.NONE, why, pin, padEnumerated, nativeApplies);
        }
        return new Selection(Choice.SHIZUKU, why, pin, padEnumerated, nativeApplies);
    }
}

package dev.bbkb.ime.core.device.touch;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dev.bbkb.ime.core.device.config.model.TouchKeypadConfig;
import dev.bbkb.ime.core.device.config.model.TouchKeypadConfig.SourcePreference;

/**
 * Which keypad touch source runs: a pure function of the profile's {@code <touch-keypad>}, the SDK
 * level and whether the pad is enumerated. The profile's {@code source} pin:
 * <ul>
 *   <li>{@code auto} (default): native when the OS delivers the pad to a focused IME, else Shizuku;</li>
 *   <li>{@code native}: native only;</li>
 *   <li>{@code shizuku}: always the Shizuku reader (for its exclusive grab).</li>
 * </ul>
 * "The OS delivers the pad" is {@code native-min-sdk <= SDK_INT} when given, else "pad enumerated".
 */
public final class TouchSourceSelector {

    private TouchSourceSelector() {}

    public enum Choice { NONE, NATIVE, SHIZUKU }

    public static final class Selection {
        @NonNull public final Choice choice;
        @NonNull public final TouchSourceStatus.Reason reason;
        /** Null when the profile declares no touch keypad. */
        @Nullable public final SourcePreference preference;
        public final boolean padEnumerated;
        /**
         * A declared pad not pinned to Shizuku, with {@code native-min-sdk} (if given) reached. It may
         * still wait for the pad to be enumerated (the Titan 2's Scroll assistant).
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

        /** Chosen: IDLE, or PAD_NOT_ENUMERATED (Titan 2: Scroll assistant off). Else NOT_APPLICABLE. */
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

        /** Chosen: {@code reader}, the reader's live status. Else NOT_APPLICABLE with the reason. */
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

    /** Whether the OS hands the pad's events to a focused IME window. */
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

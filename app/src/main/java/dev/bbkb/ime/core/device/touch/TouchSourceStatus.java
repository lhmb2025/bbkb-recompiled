package dev.bbkb.ime.core.device.touch;

import androidx.annotation.NonNull;

/**
 * Where one keypad touch source stands, with the reason, in a form a settings screen can show
 * as-is. Immutable; sources publish a new one through {@link KeypadTouchSource.StatusListener}.
 */
public final class TouchSourceStatus {

    /** The coarse state. */
    public enum State {
        /** The source is running: pad events can reach the gesture pipeline through it. */
        ACTIVE,
        /** The source applies to this device but is not running right now (window hidden). */
        IDLE,
        /** The source does not apply to this device; {@link Reason} says why. */
        NOT_APPLICABLE,
        /** The source would apply but cannot run yet; {@link Reason} says why. */
        UNAVAILABLE
    }

    /** Why the source is in its state. */
    public enum Reason {
        /** Running, or ready to run on the next window show. */
        OK,
        /** The device profile declares no touch keypad. */
        NO_TOUCH_KEYPAD_DECLARED,
        /** The OS does not deliver the pad to an IME on this Android version. */
        OS_DOES_NOT_DELIVER_PAD,
        /** The OS delivers the pad only once it enumerates it, and it has not (OEM switch off). */
        PAD_NOT_ENUMERATED,
        /** The profile pins the privileged reader ({@code source="shizuku"}). */
        PROFILE_PINS_SHIZUKU,
        /** The profile forbids the privileged reader ({@code source="native"}). */
        PROFILE_PINS_NATIVE,
        /** The privileged (Shizuku) reader is not part of this build yet. */
        SHIZUKU_NOT_YET_AVAILABLE,
        /** The source was stopped (the IME is going away). */
        STOPPED
    }

    @NonNull public final State state;
    @NonNull public final Reason reason;

    private TouchSourceStatus(@NonNull State state, @NonNull Reason reason) {
        this.state = state;
        this.reason = reason;
    }

    @NonNull
    public static TouchSourceStatus of(@NonNull State state, @NonNull Reason reason) {
        return new TouchSourceStatus(state, reason);
    }

    public boolean isActive() {
        return state == State.ACTIVE;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TouchSourceStatus)) return false;
        TouchSourceStatus other = (TouchSourceStatus) o;
        return state == other.state && reason == other.reason;
    }

    @Override
    public int hashCode() {
        return state.hashCode() * 31 + reason.hashCode();
    }

    @Override
    public String toString() {
        return state + "(" + reason + ")";
    }
}

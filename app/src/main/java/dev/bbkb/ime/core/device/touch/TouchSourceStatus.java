package dev.bbkb.ime.core.device.touch;

import androidx.annotation.NonNull;

/** Immutable state + reason of one keypad touch source, shown as-is by settings screens. */
public final class TouchSourceStatus {
    public enum State {
        ACTIVE,
        /** Applies but not running right now (window hidden). */
        IDLE,
        NOT_APPLICABLE,
        /** Would apply but cannot run yet. */
        UNAVAILABLE
    }

    public enum Reason {
        /** Running, or ready on the next window show. */
        OK,
        NO_TOUCH_KEYPAD_DECLARED,
        OS_DOES_NOT_DELIVER_PAD,
        /** Delivered only once enumerated, and it is not (OEM switch off). */
        PAD_NOT_ENUMERATED,
        PROFILE_PINS_SHIZUKU,
        PROFILE_PINS_NATIVE,
        /** Running Shizuku predates v11. */
        SHIZUKU_OUTDATED,
        SHIZUKU_NOT_INSTALLED,
        /** Shizuku stops at every reboot. */
        SHIZUKU_NOT_RUNNING,
        SHIZUKU_NOT_GRANTED,
        /** Refused for good; only the Shizuku app can allow it now. */
        SHIZUKU_DENIED,
        /** No {@code <input-device>} named for the pad. */
        PAD_NOT_NAMED,
        /** No input device has the pad's name yet; the reader keeps looking. */
        PAD_NOT_FOUND,
        /** The reader could not start or read the pad; it retries. */
        READER_FAILED,
        /** The IME is going away. */
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

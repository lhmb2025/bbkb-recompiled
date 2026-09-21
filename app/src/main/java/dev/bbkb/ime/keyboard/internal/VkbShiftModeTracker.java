package dev.bbkb.ime.keyboard.internal;



public final class VkbShiftModeTracker {

    private static final String TAG = "VkbShiftModeTracker";

    private int mState = 0;

    private static String shiftStateToString(int i) {
        switch (i) {
            case 0:
                return "UNSHIFTED";
            case 1:
                return "MANUAL_SHIFTED";
            case 2:
                return "MANUAL_SHIFTED_FROM_AUTO";
            case 3:
                return "AUTOMATIC_SHIFTED";
            case 4:
                return "SHIFT_LOCKED";
            case 5:
                return "SHIFT_LOCK_SHIFTED";
            default:
                return "UNKNOWN";
        }
    }

    public void setShifted(boolean z) {
        int i = this.mState;
        // FIX (BACKSPACE_GLITCH_REPORT §3a): the UNSHIFTED -> MANUAL_SHIFTED transition
        // (i == 0 -> 1) must only happen when shifting ON (z == true). Previously it ran
        // unconditionally, so setShifted(false) on an UNSHIFTED keyboard wrongly produced
        // MANUAL_SHIFTED. Because the post-keystroke shift recompute calls setShiftMode(0) ->
        // setShifted(false) after every key, the mode oscillated UNSHIFTED <-> MANUAL_SHIFTED, and on
        // the MANUAL_SHIFTED presses isManualShiftAndShiftPressing() made Backspace forward-delete.
        // Corrected to match AOSP AlphabetShiftState.setShifted semantics.
        if (z) {
            if (i == 0) {
                this.mState = 1;          // UNSHIFTED -> MANUAL_SHIFTED
            } else {
                switch (i) {
                    case 3:
                        this.mState = 2;  // AUTOMATIC_SHIFTED -> MANUAL_SHIFTED_FROM_AUTO
                        break;
                    case 4:
                        this.mState = 5;  // SHIFT_LOCKED -> SHIFT_LOCK_SHIFTED
                        break;
                }
            }
        } else {
            this.mState = (i == 4 || i == 5) ? 4 : 0;  // -> SHIFT_LOCKED (if locked) else UNSHIFTED
        }
    }

    public void setShiftLocked(boolean z) {
        if (z) {
            this.mState = 4;
        } else {
            this.mState = 0;
        }
    }

    public void setAutomaticShifted() {
        this.mState = 3;
    }

    public boolean isShiftedOrShiftLocked() {
        return this.mState != 0;
    }

    public boolean isShiftLocked() {
        int i = this.mState;
        return i == 4 || i == 5;
    }

    public boolean isShiftLockShifted() {
        return this.mState == 5;
    }

    public boolean isAutomaticShifted() {
        return this.mState == 3;
    }

    public boolean isManualShifted() {
        int i = this.mState;
        return i == 1 || i == 2 || i == 5;
    }

    public boolean isManualShiftedFromAutomaticShifted() {
        return this.mState == 2;
    }

    public String toString() {
        return shiftStateToString(this.mState);
    }
}

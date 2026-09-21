package dev.bbkb.ime.keyboard.internal;



final class ShiftKeyState extends ModifierKeyState {
    public ShiftKeyState(String str) {
        super(str);
    }

    @Override // dev.bbkb.ime.keyboard.internal.ModifierKeyState
    public void onOtherKeyPressed() {
        int i = this.mState;
        if (i == 1) {
            this.mState = 2;
        } else if (i == 3) {
            this.mState = 4;
        }
    }

    public void onPressOnShifted() {
        this.mState = 3;
    }

    public boolean isPressingOnShifted() {
        return this.mState == 3;
    }

    public boolean isIgnoring() {
        return this.mState == 4;
    }

    @Override // dev.bbkb.ime.keyboard.internal.ModifierKeyState
    public String toString() {
        return stateToString(this.mState);
    }

    @Override // dev.bbkb.ime.keyboard.internal.ModifierKeyState
    protected String stateToString(int i) {
        switch (i) {
            case 3:
                return "PRESSING_ON_SHIFTED";
            case 4:
                return "IGNORING";
            default:
                return super.stateToString(i);
        }
    }
}

package dev.bbkb.ime.keyboard.internal;



class ModifierKeyState {

    protected static final String TAG = "ModifierKeyState";

    protected final String name;

    protected int mState = 0;

    protected String stateToString(int i) {
        switch (i) {
            case 0:
                return "RELEASING";
            case 1:
                return "PRESSING";
            case 2:
                return "CHORDING";
            default:
                return "UNKNOWN";
        }
    }

    public ModifierKeyState(String str) {
        this.name = str;
    }

    public void onPress() {
        this.mState = 1;
    }

    public void onRelease() {
        this.mState = 0;
    }

    public void onOtherKeyPressed() {
        if (this.mState == 1) {
            this.mState = 2;
        }
    }

    public boolean isPressing() {
        return this.mState == 1;
    }

    public boolean isReleasing() {
        return this.mState == 0;
    }

    public boolean isChording() {
        return this.mState == 2;
    }

    public String toString() {
        return stateToString(this.mState);
    }
}

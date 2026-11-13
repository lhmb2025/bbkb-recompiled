package dev.bbkb.ime.keyboard.internal;


import dev.bbkb.ime.keyboard.Key;
import dev.bbkb.ime.keyboard.Keyboard;

public class KeyDetector {

    private final int mKeyHysteresisDistanceSquared;

    private final int mKeyHysteresisDistanceForSlidingModifierSquared;

    private Keyboard mKeyboard;

    private int mCorrectionX;

    private int mCorrectionY;

    public KeyDetector() {
        this(0.0f, 0.0f);
    }

    public KeyDetector(float keyHysteresisDistance, float keyHysteresisDistanceForSlidingModifier) {
        this.mKeyHysteresisDistanceSquared = (int) (keyHysteresisDistance * keyHysteresisDistance);
        this.mKeyHysteresisDistanceForSlidingModifierSquared = (int) (keyHysteresisDistanceForSlidingModifier * keyHysteresisDistanceForSlidingModifier);
    }

    public void setKeyboard(Keyboard keyboard, float correctionX, float correctionY) {
        if (keyboard == null) {
            throw new NullPointerException();
        }
        this.mCorrectionX = (int) correctionX;
        this.mCorrectionY = (int) correctionY;
        this.mKeyboard = keyboard;
    }

    public int getKeyHysteresisDistanceSquared(boolean isSlidingFromModifier) {
        return isSlidingFromModifier ? this.mKeyHysteresisDistanceForSlidingModifierSquared : this.mKeyHysteresisDistanceSquared;
    }

    public int getTouchX(int x) {
        return x + this.mCorrectionX;
    }

    public int getTouchY(int y) {
        return y + this.mCorrectionY;
    }

    public Keyboard getKeyboard() {
        return this.mKeyboard;
    }

    public Key detectHitKey(int x, int y) {
        int iM6225b;
        Key key = null;
        if (this.mKeyboard == null) {
            return null;
        }
        int iM6588a = getTouchX(x);
        int iM6593b = getTouchY(y);
        int i3 = Integer.MAX_VALUE;
        for (Key key2 : this.mKeyboard.getNearestKeys(iM6588a, iM6593b)) {
            if (key2.isOnKey(iM6588a, iM6593b) && (iM6225b = key2.squaredDistanceToEdge(iM6588a, iM6593b)) <= i3 && (key == null || iM6225b < i3 || key2.getCode() > key.getCode())) {
                key = key2;
                i3 = iM6225b;
            }
        }
        return key;
    }
}

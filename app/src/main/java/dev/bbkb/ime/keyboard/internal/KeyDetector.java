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

    public KeyDetector(float f, float f2) {
        this.mKeyHysteresisDistanceSquared = (int) (f * f);
        this.mKeyHysteresisDistanceForSlidingModifierSquared = (int) (f2 * f2);
    }

    public void setKeyboard(Keyboard c0965e, float f, float f2) {
        if (c0965e == null) {
            throw new NullPointerException();
        }
        this.mCorrectionX = (int) f;
        this.mCorrectionY = (int) f2;
        this.mKeyboard = c0965e;
    }

    public int getKeyHysteresisDistanceSquared(boolean z) {
        return z ? this.mKeyHysteresisDistanceForSlidingModifierSquared : this.mKeyHysteresisDistanceSquared;
    }

    public int getTouchX(int i) {
        return i + this.mCorrectionX;
    }

    public int getTouchY(int i) {
        return i + this.mCorrectionY;
    }

    public Keyboard getKeyboard() {
        return this.mKeyboard;
    }

    public Key detectHitKey(int i, int i2) {
        int iM6225b;
        Key key = null;
        if (this.mKeyboard == null) {
            return null;
        }
        int iM6588a = getTouchX(i);
        int iM6593b = getTouchY(i2);
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

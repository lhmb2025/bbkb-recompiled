package dev.bbkb.ime.keyboard.internal;

import dev.bbkb.ime.keyboard.Key;
import dev.bbkb.ime.keyboard.Keyboard;


public final class MoreKeysDetector extends KeyDetector {

    private final int slideAllowanceSquare;

    private final int slideAllowanceSquareTop;

    public MoreKeysDetector(float slideAllowance) {
        this.slideAllowanceSquare = (int) (slideAllowance * slideAllowance);
        this.slideAllowanceSquareTop = this.slideAllowanceSquare * 2;
    }

    @Override // dev.bbkb.ime.keyboard.KeyDetector
    public Key detectHitKey(int x, int y) {
        Keyboard c0965eA = getKeyboard();
        Key key = null;
        if (c0965eA == null) {
            return null;
        }
        int iA = getTouchX(x);
        int iB = getTouchY(y);
        int i3 = y < 0 ? this.slideAllowanceSquareTop : this.slideAllowanceSquare;
        for (Key key2 : c0965eA.getKeys()) {
            int iM6225b = key2.squaredDistanceToEdge(iA, iB);
            if (iM6225b < i3) {
                key = key2;
                i3 = iM6225b;
            }
        }
        return key;
    }
}

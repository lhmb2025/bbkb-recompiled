package dev.bbkb.ime.core.keyevent;

import android.view.KeyEvent;



public interface KeyCharacterInterpreter extends KeyCharacterResult {
    KeyCharacterResult.Interpretation interpretKeyCharacter(KeyEvent keyEvent, int i);

    
    public static class MetaMask {

        public static final MetaMask IDENTITY = new MetaMask(0, 0);

        private final int mOnBits;

        private final int mOffBits;

        public MetaMask(int i, int i2) {
            this.mOnBits = i;
            this.mOffBits = i2;
        }

        public int apply(int i) {
            return (i & (~this.mOffBits)) | this.mOnBits;
        }
    }
}

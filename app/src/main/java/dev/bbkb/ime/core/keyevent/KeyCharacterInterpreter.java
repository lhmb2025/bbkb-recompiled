package dev.bbkb.ime.core.keyevent;

import android.view.KeyEvent;



public interface KeyCharacterInterpreter extends KeyCharacterResult {
    KeyCharacterResult.Interpretation interpretKeyCharacter(KeyEvent keyEvent, int metaState);

    
    public static class MetaMask {

        public static final MetaMask IDENTITY = new MetaMask(0, 0);

        private final int mOnBits;

        private final int mOffBits;

        public MetaMask(int onBits, int offBits) {
            this.mOnBits = onBits;
            this.mOffBits = offBits;
        }

        public int apply(int metaState) {
            return (metaState & (~this.mOffBits)) | this.mOnBits;
        }
    }
}

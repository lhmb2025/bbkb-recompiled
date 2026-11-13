package dev.bbkb.ime.core.keyevent;



public interface KeyCharacterResult {

    
    public static class Interpretation {

        public final int codePoint;

        public final CharSequence text;

        private final int flags;

        private static final int FLAG_MODIFIER = 1;
        private static final int FLAG_SHIFT_LOCKED = 2;
        /** Text output sets both flags above. */
        private static final int FLAGS_TEXT_OUTPUT = FLAG_MODIFIER | FLAG_SHIFT_LOCKED;

        /** @param i the code point */
        public Interpretation(int i) {
            this.flags = 0;
            this.codePoint = i;
            this.text = null;
        }

        /**
         * @param i the code point
         * @param z true if Shift is locked
         * @param z2 true if a modifier (multitap) interpretation
         */
        public Interpretation(int i, boolean z, boolean z2) {
            this.flags = (z ? FLAG_SHIFT_LOCKED : 0) | (z2 ? FLAG_MODIFIER : 0);
            this.codePoint = i;
            this.text = null;
        }

        public Interpretation(CharSequence charSequence) {
            this.flags = FLAGS_TEXT_OUTPUT;
            this.codePoint = -4;
            this.text = charSequence;
        }

        public boolean isModifier() {
            return (this.flags & FLAG_MODIFIER) != 0;
        }

        public boolean isShiftLocked() {
            return (this.flags & FLAG_SHIFT_LOCKED) != 0;
        }

        /** Both bits, not either: modifier-only or shift-locked interpretations are not text output. */
        public boolean isTextOutput() {
            return (this.flags & FLAGS_TEXT_OUTPUT) == FLAGS_TEXT_OUTPUT;
        }
    }
}

package dev.bbkb.ime.core.keyevent;



public interface KeyCharacterResult {

    
    public static class Interpretation {

        public final int codePoint;

        public final CharSequence text;

        private final int flags;

        private static final int FLAG_MODIFIER = 1;
        private static final int FLAG_SHIFT_LOCKED = 2;
        /** Set by the text-output constructor only; it is the pair of the two flags above. */
        private static final int FLAGS_TEXT_OUTPUT = FLAG_MODIFIER | FLAG_SHIFT_LOCKED;

        public Interpretation(int i) {
            this.flags = 0;
            this.codePoint = i;
            this.text = null;
        }

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

        /**
         * Audit DK-32: this tested {@code != 0}, so a modifier-only (flags 1) or shift-locked
         * (flags 2) interpretation reported itself as text output. Only the text-output
         * constructor sets both bits. The bug was masked because {@code KeyEventConverter}'s
         * consumer also guards on {@code text != null} and the two-arg constructor leaves
         * {@code text} null — a trap for anyone adding a constructor that sets both.
         */
        public boolean isTextOutput() {
            return (this.flags & FLAGS_TEXT_OUTPUT) == FLAGS_TEXT_OUTPUT;
        }
    }
}

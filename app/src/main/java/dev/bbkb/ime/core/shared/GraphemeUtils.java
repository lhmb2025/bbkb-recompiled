package dev.bbkb.ime.core.shared;



public final class GraphemeUtils {

    public static int getGraphemeLengthBeforeCursor(CharSequence charSequence, int i) {
        int i2 = 1;
        if (charSequence == null) {
            return 1;
        }
        int iM5738a = EmojiTextAnalyzer.getTrailingEmojiLength(charSequence, i);
        if (iM5738a != 0 || charSequence.length() <= 0) {
            return iM5738a;
        }
        int length = charSequence.length();
        if (length >= 2 && Character.isSurrogatePair(charSequence.charAt(length - 2), charSequence.charAt(length - 1))) {
            return 2;
        }
        while (i2 < length && Character.getType(charSequence.charAt(length - i2)) == Character.NON_SPACING_MARK) {
            i2++;
        }
        return i2;
    }

    public static int getGraphemeLengthAfterCursor(CharSequence charSequence, int i) {
        int i2 = 1;
        if (charSequence == null) {
            return 1;
        }
        int iM5742b = EmojiTextAnalyzer.getLeadingEmojiLength(charSequence, i);
        if (iM5742b != 0 || charSequence.length() <= 0) {
            return iM5742b;
        }
        int length = charSequence.length();
        if (length >= 2 && Character.isSurrogatePair(charSequence.charAt(0), charSequence.charAt(1))) {
            return 2;
        }
        while (i2 < length && Character.getType(charSequence.charAt(i2)) == Character.NON_SPACING_MARK) {
            i2++;
        }
        return i2;
    }

    public static int surrogatePairToCodePoint(String str) {
        if (str.length() != 2 || !Character.isSurrogatePair(str.charAt(0), str.charAt(1))) {
            return 0;
        }
        return Character.toCodePoint(str.charAt(0), str.charAt(1));
    }
}

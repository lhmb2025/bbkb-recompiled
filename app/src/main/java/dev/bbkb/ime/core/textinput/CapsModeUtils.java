package dev.bbkb.ime.core.textinput;

import android.text.TextUtils;
import android.util.Patterns;

import dev.bbkb.ime.core.settings.util.SpacingAndPunctuation;



public final class CapsModeUtils {
    public static boolean shouldLowercaseWord(int shiftState) {
        return 5 == shiftState || 7 == shiftState;
    }

    private static boolean isStartPunctuation(int codePoint) {
        return codePoint == 34 || codePoint == 39 || codePoint == 191 || codePoint == 161 || Character.getType(codePoint) == 21;
    }

    public static int getCapsMode(CharSequence charSequence, int reqModes, SpacingAndPunctuation spacingAndPunctuation, boolean hasSpaceBefore) {
        int length;
        if ((reqModes & 24576) == 0) {
            return reqModes & 4096;
        }
        if (hasSpaceBefore) {
            length = charSequence.length() + 1;
        } else {
            length = charSequence.length();
            while (length > 0 && isStartPunctuation(charSequence.charAt(length - 1))) {
                length--;
            }
        }
        char cCharAt = ' ';
        int i2 = hasSpaceBefore ? length - 1 : length;
        while (i2 > 0) {
            cCharAt = charSequence.charAt(i2 - 1);
            if (!Character.isSpaceChar(cCharAt) && cCharAt != '\t') {
                break;
            }
            i2--;
        }
        boolean z2 = false;
        if (i2 <= 0 || Character.isWhitespace(cCharAt)) {
            if (spacingAndPunctuation.usesGermanRules) {
                while (true) {
                    i2--;
                    if (i2 < 0 || !Character.isWhitespace(cCharAt)) {
                        break;
                    }
                    if ('\n' == cCharAt) {
                        z2 = true;
                    }
                    cCharAt = charSequence.charAt(i2);
                }
                if (',' == cCharAt && z2) {
                    return reqModes & 12288;
                }
            }
            return reqModes & 28672;
        }
        if (length == i2) {
            return reqModes & 4096;
        }
        if ((reqModes & 16384) == 0) {
            return reqModes & 12288;
        }
        if (spacingAndPunctuation.usesAmericanTypography) {
            while (i2 > 0) {
                char cCharAt2 = charSequence.charAt(i2 - 1);
                if (cCharAt2 != '\"' && cCharAt2 != '\'' && Character.getType(cCharAt2) != 22) {
                    break;
                }
                i2--;
            }
        }
        if (i2 <= 0) {
            return reqModes & 4096;
        }
        int i3 = i2 - 1;
        char cCharAt3 = charSequence.charAt(i3);
        if (cCharAt3 == '?' || cCharAt3 == '!') {
            return reqModes & 20480;
        }
        if (!spacingAndPunctuation.isSentenceSeparator(cCharAt3) || i3 <= 0) {
            return reqModes & 12288;
        }
        if (i3 >= 2 && spacingAndPunctuation.usesAmericanTypography && TextUtils.equals("...", charSequence.subSequence(i3 - 2, i3 + 1)) && (i3 == 2 || charSequence.charAt(i3 - 3) != '.')) {
            return reqModes & 12288;
        }
        int i4 = reqModes & 28672;
        int i5 = reqModes & 12288;
        int i6 = i3;
        char c = 0;
        while (i6 > 0) {
            i6--;
            char cCharAt4 = charSequence.charAt(i6);
            switch (c) {
                case 0:
                    if (!Character.isLetter(cCharAt4)) {
                        if (!Character.isDigit(cCharAt4) || !spacingAndPunctuation.usesGermanRules) {
                            return i4;
                        }
                        c = 4;
                        break;
                    } else {
                        c = 1;
                        break;
                    }
                case 1:
                    if (Character.isLetter(cCharAt4)) {
                        c = 1;
                        break;
                    } else {
                        if (!spacingAndPunctuation.isSentenceSeparator(cCharAt4)) {
                            return i4;
                        }
                        c = 2;
                        break;
                    }
                case 2:
                    if (!Character.isLetter(cCharAt4)) {
                        return i4;
                    }
                    c = 3;
                    break;
                case 3:
                    if (Character.isLetter(cCharAt4)) {
                        c = 3;
                        break;
                    } else {
                        if (!spacingAndPunctuation.isSentenceSeparator(cCharAt4)) {
                            return Patterns.WEB_URL.matcher(charSequence.subSequence(i6 + 1, i3)).matches() ^ true ? i5 : i4;
                        }
                        c = 2;
                        break;
                    }
                case 4:
                    if (Character.isLetter(cCharAt4)) {
                        c = 1;
                        break;
                    } else {
                        if (!Character.isDigit(cCharAt4)) {
                            return i5;
                        }
                        c = 4;
                        break;
                    }
            }
        }
        return (c == 0 || 3 == c) ? i5 : i4;
    }
}

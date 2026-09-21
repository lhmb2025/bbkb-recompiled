package dev.bbkb.ime.core.textinput;

import android.text.TextUtils;
import android.util.Patterns;

import dev.bbkb.ime.core.settings.util.SpacingAndPunctuation;



public final class CapsModeUtils {
    public static boolean shouldLowercaseWord(int i) {
        return 5 == i || 7 == i;
    }

    private static boolean isStartPunctuation(int i) {
        return i == 34 || i == 39 || i == 191 || i == 161 || Character.getType(i) == 21;
    }

    public static int getCapsMode(CharSequence charSequence, int i, SpacingAndPunctuation c0806f, boolean z) {
        int length;
        if ((i & 24576) == 0) {
            return i & 4096;
        }
        if (z) {
            length = charSequence.length() + 1;
        } else {
            length = charSequence.length();
            while (length > 0 && isStartPunctuation(charSequence.charAt(length - 1))) {
                length--;
            }
        }
        char cCharAt = ' ';
        int i2 = z ? length - 1 : length;
        while (i2 > 0) {
            cCharAt = charSequence.charAt(i2 - 1);
            if (!Character.isSpaceChar(cCharAt) && cCharAt != '\t') {
                break;
            }
            i2--;
        }
        boolean z2 = false;
        if (i2 <= 0 || Character.isWhitespace(cCharAt)) {
            if (c0806f.usesGermanRules) {
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
                    return i & 12288;
                }
            }
            return i & 28672;
        }
        if (length == i2) {
            return i & 4096;
        }
        if ((i & 16384) == 0) {
            return i & 12288;
        }
        if (c0806f.usesAmericanTypography) {
            while (i2 > 0) {
                char cCharAt2 = charSequence.charAt(i2 - 1);
                if (cCharAt2 != '\"' && cCharAt2 != '\'' && Character.getType(cCharAt2) != 22) {
                    break;
                }
                i2--;
            }
        }
        if (i2 <= 0) {
            return i & 4096;
        }
        int i3 = i2 - 1;
        char cCharAt3 = charSequence.charAt(i3);
        if (cCharAt3 == '?' || cCharAt3 == '!') {
            return i & 20480;
        }
        if (!c0806f.isSentenceSeparator(cCharAt3) || i3 <= 0) {
            return i & 12288;
        }
        if (i3 >= 2 && c0806f.usesAmericanTypography && TextUtils.equals("...", charSequence.subSequence(i3 - 2, i3 + 1)) && (i3 == 2 || charSequence.charAt(i3 - 3) != '.')) {
            return i & 12288;
        }
        int i4 = i & 28672;
        int i5 = i & 12288;
        int i6 = i3;
        char c = 0;
        while (i6 > 0) {
            i6--;
            char cCharAt4 = charSequence.charAt(i6);
            switch (c) {
                case 0:
                    if (!Character.isLetter(cCharAt4)) {
                        if (!Character.isDigit(cCharAt4) || !c0806f.usesGermanRules) {
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
                        if (!c0806f.isSentenceSeparator(cCharAt4)) {
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
                        if (!c0806f.isSentenceSeparator(cCharAt4)) {
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

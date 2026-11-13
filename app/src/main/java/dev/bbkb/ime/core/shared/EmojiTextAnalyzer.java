package dev.bbkb.ime.core.shared;

import android.text.TextUtils;

import java.util.regex.Pattern;



public final class EmojiTextAnalyzer {

    private static final Pattern EMOTICON_SHORTCODE_PATTERN = Pattern.compile("/::\\)|/::~|/::B|/::\\||/:8-\\)|/::<|/::\\$|/::X|/::Z|/::'\\(|/::-\\||/::@|/::P|/::D|/::O|/::\\(|/::\\+|/:--b|/::Q|/::T|/:,@P|/:,@-D|/::d|/:,@o|/::g|/:\\|-\\)|/::!|/::L|/::>|/::,@|/:,@f|/::-S|/:\\?|/:,@x|/:,@@|/:,@|/::8|/:,@!|/:!!!|/:xx|\\[Bye\\]|/:wipe|/:dig|/:handclap|/:&-\\(|/:B-\\)|/:<@|/:@>|/::-O|/:>-\\||/:P-\\(|/::'\\||/:X-\\)|/::\\*|/:@x|/:8\\*|/:pd|/:<W>|/:beer|/:basketb|/:oo|/:coffee|/:eat|/:pig|/:rose|/:fade|/:showlove|/:heart|/:break|/:cake|/:li|/:bome|/:kn|/:footb|/:ladybug|/:shit|/:moon|/:sun|/:gift|/:hug|/:strong|/:weak|/:share|/:v|/:@\\)|/:jj|/:@@|/:bad|/:lvu|/:no|/:ok|/:love|/:<L>|/:jump|/:shake|/:<O>|/:circle|/:kotow|/:turn|/:skip|/:oY|/:#-0|/:hiphot|/:kiss|/:<&|/:&>|\\[Salute\\]|\ue415|\ue40c|\ue412|\ue409|\ue40d|\ue107|\ue403|\ue40e|\\[Yeah!\\]|\\[Facepalm\\]|\\[Hey\\]|\\[Smirk\\]|\\[Smart\\]|\\[Concerned\\]|\ue11b|\ue41d|\ue14c|\ue00d|\ue427|\ue51f|\ue30c|\ue312|\ue112|\\[Packet\\]|\\[Candle\\]");

    // U+25E9 is not an emoji, but isEmoji accepts it alone as a legacy modifier.
    private static final String LEGACY_MODIFIER = "\u25e9";

    private static final int VARIATION_SELECTOR_16 = 0xFE0F;
    private static final int COMBINING_ENCLOSING_KEYCAP = 0x20E3;

    /**
     * Whether {@code codePoint} belongs to an emoji, by block so new emoji need no update:
     * <ul>
     *   <li>U+1F000..U+1FFFF: all pictograph blocks, regional indicators, skin tones.</li>
     *   <li>BMP U+2300..U+23FF, U+2600..U+27BF, U+2B00..U+2BFF. Lone BMP emoji elsewhere
     *       (© ™ ‼) are one char unless U+FE0F follows, which counts itself.</li>
     *   <li>U+FE0F, U+20E3 keycap, tags U+E0020..U+E007F.</li>
     * </ul>
     * ZWJ is excluded: Indic half forms use it and must keep deleting piece by piece.
     */
    public static boolean isEmojiRelated(int codePoint) {
        return (codePoint >= 0x1F000 && codePoint <= 0x1FFFF)
                || (codePoint >= 0x2300 && codePoint <= 0x23FF)
                || (codePoint >= 0x2600 && codePoint <= 0x27BF)
                || (codePoint >= 0x2B00 && codePoint <= 0x2BFF)
                || codePoint == VARIATION_SELECTOR_16
                || codePoint == COMBINING_ENCLOSING_KEYCAP
                || (codePoint >= 0xE0020 && codePoint <= 0xE007F);
    }

    /** Whether the text starts with an emoji, or is the legacy U+25E9 modifier. */
    public static boolean isEmoji(String text) {
        if (TextUtils.isEmpty(text)) {
            return false;
        }
        return isEmojiRelated(text.codePointAt(0)) || LEGACY_MODIFIER.equals(text);
    }

    /** @param codePoint a code point; negative is never an emoji */
    public static boolean isEmoji(int codePoint) {
        if (codePoint < 0) {
            return false;
        }
        return isEmoji(new String(Character.toChars(codePoint)));
    }

    /**
     * Length of an emoticon shortcode ("/::)", "[Bye]") at the cursor, or 0.
     *
     * @param isBeforeCursor true to look before the cursor (end of {@code charSequence}), false for after it
     * @param adjacentCodePoint the code point after the cursor; a forward match must start with '/'
     */
    public static int findEmoticonShortcodeLength(boolean isBeforeCursor, CharSequence charSequence, int adjacentCodePoint) {
        if (charSequence == null || charSequence.length() == 0) {
            return 0;
        }
        int i2 = 3;
        if (!isBeforeCursor) {
            if (adjacentCodePoint == 47 && charSequence.length() >= 2 && Character.codePointAt(charSequence, 1) == 58) {
                int i3 = 0;
                while (i2 < 9 && i2 < charSequence.length()) {
                    i2++;
                    if (matchesEmoticonPattern(charSequence.subSequence(0, i2).toString())) {
                        i3 = i2;
                    }
                }
                return i3;
            }
        } else {
            int length = charSequence.length() - 1;
            while (length >= 0 && Character.codePointAt(charSequence, length) != 58 && Character.codePointAt(charSequence, length) != 91) {
                length--;
            }
            if (length < 0 || charSequence.length() < 3) {
                return 0;
            }
            if (length > 0) {
                int i4 = length - 1;
                if (Character.codePointAt(charSequence, i4) == 47) {
                    return getEmoticonLengthFrom(charSequence, i4);
                }
            }
            if (length > 1 && Character.codePointAt(charSequence, length - 1) == 58) {
                int i5 = length - 2;
                if (Character.codePointAt(charSequence, i5) == 47) {
                    return getEmoticonLengthFrom(charSequence, i5);
                }
            }
            if (Character.codePointAt(charSequence, length) == 91) {
                return getEmoticonLengthFrom(charSequence, length);
            }
        }
        return 0;
    }

    /** @param start the index where the candidate shortcode starts */
    private static int getEmoticonLengthFrom(CharSequence charSequence, int start) {
        CharSequence charSequenceSubSequence = charSequence.subSequence(start, charSequence.length());
        if (matchesEmoticonPattern(charSequenceSubSequence.toString())) {
            return charSequenceSubSequence.length();
        }
        return 0;
    }

    /** @param candidate the candidate shortcode */
    private static boolean matchesEmoticonPattern(String candidate) {
        return EMOTICON_SHORTCODE_PATTERN.matcher(candidate).matches();
    }
}

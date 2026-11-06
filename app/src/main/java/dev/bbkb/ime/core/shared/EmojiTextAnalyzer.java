package dev.bbkb.ime.core.shared;

import android.text.TextUtils;

import java.util.regex.Pattern;



public final class EmojiTextAnalyzer {

    private static final Pattern EMOTICON_SHORTCODE_PATTERN = Pattern.compile("/::\\)|/::~|/::B|/::\\||/:8-\\)|/::<|/::\\$|/::X|/::Z|/::'\\(|/::-\\||/::@|/::P|/::D|/::O|/::\\(|/::\\+|/:--b|/::Q|/::T|/:,@P|/:,@-D|/::d|/:,@o|/::g|/:\\|-\\)|/::!|/::L|/::>|/::,@|/:,@f|/::-S|/:\\?|/:,@x|/:,@@|/:,@|/::8|/:,@!|/:!!!|/:xx|\\[Bye\\]|/:wipe|/:dig|/:handclap|/:&-\\(|/:B-\\)|/:<@|/:@>|/::-O|/:>-\\||/:P-\\(|/::'\\||/:X-\\)|/::\\*|/:@x|/:8\\*|/:pd|/:<W>|/:beer|/:basketb|/:oo|/:coffee|/:eat|/:pig|/:rose|/:fade|/:showlove|/:heart|/:break|/:cake|/:li|/:bome|/:kn|/:footb|/:ladybug|/:shit|/:moon|/:sun|/:gift|/:hug|/:strong|/:weak|/:share|/:v|/:@\\)|/:jj|/:@@|/:bad|/:lvu|/:no|/:ok|/:love|/:<L>|/:jump|/:shake|/:<O>|/:circle|/:kotow|/:turn|/:skip|/:oY|/:#-0|/:hiphot|/:kiss|/:<&|/:&>|\\[Salute\\]|\ue415|\ue40c|\ue412|\ue409|\ue40d|\ue107|\ue403|\ue40e|\\[Yeah!\\]|\\[Facepalm\\]|\\[Hey\\]|\\[Smirk\\]|\\[Smart\\]|\\[Concerned\\]|\ue11b|\ue41d|\ue14c|\ue00d|\ue427|\ue51f|\ue30c|\ue312|\ue112|\\[Packet\\]|\\[Candle\\]");
    
    // The original app's emoji-modifier table also held U+25E9, which is not an emoji or a
    // modifier. isEmoji keeps accepting it on its own so it accepts everything it did.
    private static final String LEGACY_MODIFIER = "\u25e9";

    private static final int VARIATION_SELECTOR_16 = 0xFE0F;
    private static final int COMBINING_ENCLOSING_KEYCAP = 0x20E3;

    /**
     * Whether {@code codePoint} belongs to an emoji: a pictograph, or one of the code points
     * that build emoji sequences. Structural, so it needs no update when Unicode adds emoji:
     * <ul>
     *   <li>U+1F000..U+1FFFF: the supplementary symbol and pictograph area. Every pictographic
     *       emoji block, current and future, is allocated here, and it also holds the regional
     *       indicators (U+1F1E6..U+1F1FF, flags) and the skin-tone modifiers
     *       (U+1F3FB..U+1F3FF).</li>
     *   <li>The BMP blocks that hold emoji: Miscellaneous Technical U+2300..U+23FF (⌚ ⌛ ⏩ ⏰),
     *       Miscellaneous Symbols and Dingbats U+2600..U+27BF (☀ ☝ ❤ ✅) and Miscellaneous
     *       Symbols and Arrows U+2B00..U+2BFF (⭐ ⭕ ⬛ ⬅). New emoji are allocated outside the
     *       BMP. The few lone BMP emoji outside these blocks (© ™ ‼ ↔ ▶ ...) are left out: they
     *       are one char long unless a U+FE0F follows, which counts by itself.</li>
     *   <li>U+FE0F variation selector-16, U+20E3 combining enclosing keycap (1️⃣) and the tag
     *       characters U+E0020..U+E007F (subdivision flags).</li>
     * </ul>
     * The zero-width joiner is deliberately NOT included. It never stands at either end of an
     * emoji, and every emoji ZWJ sequence holds a pictograph that already counts, while
     * Indic scripts use it inside a syllable (a half form is consonant + virama + ZWJ), which
     * must keep deleting piece by piece.
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

    /** Whether the text starts with an emoji (or is one of the original app's modifiers). */
    public static boolean isEmoji(String str) {
        if (TextUtils.isEmpty(str)) {
            return false;
        }
        return isEmojiRelated(str.codePointAt(0)) || LEGACY_MODIFIER.equals(str);
    }

    public static boolean isEmoji(int i) {
        if (i < 0) {
            return false;
        }
        return isEmoji(new String(Character.toChars(i)));
    }

    public static int findEmoticonShortcodeLength(boolean z, CharSequence charSequence, int i) {
        if (charSequence == null || charSequence.length() == 0) {
            return 0;
        }
        int i2 = 3;
        if (!z) {
            if (i == 47 && charSequence.length() >= 2 && Character.codePointAt(charSequence, 1) == 58) {
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

    private static int getEmoticonLengthFrom(CharSequence charSequence, int i) {
        CharSequence charSequenceSubSequence = charSequence.subSequence(i, charSequence.length());
        if (matchesEmoticonPattern(charSequenceSubSequence.toString())) {
            return charSequenceSubSequence.length();
        }
        return 0;
    }

    private static boolean matchesEmoticonPattern(String str) {
        return EMOTICON_SHORTCODE_PATTERN.matcher(str).matches();
    }
}

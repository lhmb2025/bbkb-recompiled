package dev.bbkb.ime.core.shared;

import android.text.TextUtils;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;



public final class EmojiTextAnalyzer {

    private static final Pattern EMOTICON_SHORTCODE_PATTERN = Pattern.compile("/::\\)|/::~|/::B|/::\\||/:8-\\)|/::<|/::\\$|/::X|/::Z|/::'\\(|/::-\\||/::@|/::P|/::D|/::O|/::\\(|/::\\+|/:--b|/::Q|/::T|/:,@P|/:,@-D|/::d|/:,@o|/::g|/:\\|-\\)|/::!|/::L|/::>|/::,@|/:,@f|/::-S|/:\\?|/:,@x|/:,@@|/:,@|/::8|/:,@!|/:!!!|/:xx|\\[Bye\\]|/:wipe|/:dig|/:handclap|/:&-\\(|/:B-\\)|/:<@|/:@>|/::-O|/:>-\\||/:P-\\(|/::'\\||/:X-\\)|/::\\*|/:@x|/:8\\*|/:pd|/:<W>|/:beer|/:basketb|/:oo|/:coffee|/:eat|/:pig|/:rose|/:fade|/:showlove|/:heart|/:break|/:cake|/:li|/:bome|/:kn|/:footb|/:ladybug|/:shit|/:moon|/:sun|/:gift|/:hug|/:strong|/:weak|/:share|/:v|/:@\\)|/:jj|/:@@|/:bad|/:lvu|/:no|/:ok|/:love|/:<L>|/:jump|/:shake|/:<O>|/:circle|/:kotow|/:turn|/:skip|/:oY|/:#-0|/:hiphot|/:kiss|/:<&|/:&>|\\[Salute\\]|\ue415|\ue40c|\ue412|\ue409|\ue40d|\ue107|\ue403|\ue40e|\\[Yeah!\\]|\\[Facepalm\\]|\\[Hey\\]|\\[Smirk\\]|\\[Smart\\]|\\[Concerned\\]|\ue11b|\ue41d|\ue14c|\ue00d|\ue427|\ue51f|\ue30c|\ue312|\ue112|\\[Packet\\]|\\[Candle\\]");
    
    private static final Set<String> EMOJI_MODIFIERS = new HashSet<>(java.util.Arrays.asList("◩", "️", "🏻", "🏼", "🏽", "🏾", "🏿"));
    // Modern emoji can be up to ~20 characters (base + modifiers + ZWJ sequences)
    private static final int MAX_EMOJI_LENGTH = 20;

    private static final char ZERO_WIDTH_JOINER = '\u200d';

    private static boolean isEmojiOrModifier(String str) {
        return str != null && !str.isEmpty() && isEmojiOrModifier(str, 0, str.length());
    }

    /**
     * Equivalent to {@code isEmojiOrModifier(s.substring(start, end))}, without materialising the
     * substring. The backspace and cursor paths call this once per candidate length.
     */
    private static boolean isEmojiOrModifier(String s, int start, int end) {
        if (start >= end) {
            return false;
        }
        // Check if the region is exactly an emoji modifier
        for (String modifier : EMOJI_MODIFIERS) {
            if (modifier.length() == end - start && s.regionMatches(start, modifier, 0, modifier.length())) {
                return true;
            }
        }
        // Check if first codepoint of the region is in emoji ranges
        final char first = s.charAt(start);
        final int codePoint;
        if (Character.isHighSurrogate(first) && start + 1 < end && Character.isLowSurrogate(s.charAt(start + 1))) {
            codePoint = Character.toCodePoint(first, s.charAt(start + 1));
        } else {
            codePoint = first;
        }
        return isEmojiCodePoint(codePoint);
    }
    
    private static boolean isEmojiCodePoint(int codePoint) {
        // Basic emoji ranges (simplified check)
        return (codePoint >= 0x1F300 && codePoint <= 0x1F9FF) ||  // Misc Symbols and Pictographs, Emoticons, etc.
               (codePoint >= 0x2600 && codePoint <= 0x27BF) ||     // Misc symbols
               (codePoint >= 0x1F600 && codePoint <= 0x1F64F) ||   // Emoticons
               (codePoint >= 0x1F680 && codePoint <= 0x1F6FF) ||   // Transport and Map
               (codePoint >= 0x2700 && codePoint <= 0x27BF);       // Dingbats
    }
    
    public static boolean isEmoji(String str) {
        if (TextUtils.isEmpty(str)) {
            return false;
        }
        // Fix #2: Use codepoint count instead of character length
        // Basic emojis are 1 codepoint but 2 chars (surrogate pair)
        // Emojis with skin tones, ZWJ sequences can be multiple codepoints
        int codePointCount = str.codePointCount(0, str.length());
        if (codePointCount <= 2) {
            return isEmojiOrModifier(str);
        }
        // For longer strings, check if first codepoint is emoji
        // This handles ZWJ sequences (family, profession emojis) and flag emojis
        int firstCodePoint = str.codePointAt(0);
        return isEmojiCodePoint(firstCodePoint);
    }

    public static boolean isEmoji(int i) {
        if (i < 0) {
            return false;
        }
        return isEmojiOrModifier(new String(Character.toChars(i)));
    }

    public static int getTrailingEmojiLength(CharSequence charSequence, int i) {
        if (charSequence == null) {
            throw new NullPointerException();
        }
        int iM5739a = findEmoticonShortcodeLength(true, charSequence, i);
        if (iM5739a != 0) {
            return iM5739a;
        }
        final String string = charSequence.toString();
        final int end = string.length();
        int candidateLength = 0;
        int bestLength = 0;
        int start = end - 1;
        while (candidateLength < MAX_EMOJI_LENGTH && start >= 0) {
            final int regionStart = start;
            candidateLength = end - regionStart;
            start--;
            if (isEmojiOrModifier(string, regionStart, end)) {
                if (start >= 0 && string.indexOf(ZERO_WIDTH_JOINER, start) < 0) {
                    break;
                }
                bestLength = candidateLength;
            }
        }
        return bestLength;
    }

    public static int getLeadingEmojiLength(CharSequence charSequence, int i) {
        if (charSequence == null) {
            throw new NullPointerException();
        }
        int iM5739a = findEmoticonShortcodeLength(false, charSequence, i);
        if (iM5739a != 0) {
            return iM5739a;
        }
        final String string = charSequence.toString();
        int candidateLength = 0;
        int bestLength = 0;
        while (candidateLength < MAX_EMOJI_LENGTH && candidateLength < string.length()) {
            candidateLength++;
            if (isEmojiOrModifier(string, 0, candidateLength)) {
                bestLength = candidateLength;
            }
        }
        return bestLength;
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

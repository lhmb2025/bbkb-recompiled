package dev.bbkb.ime.core.shared;

import java.text.BreakIterator;
import java.util.Locale;

/**
 * How many chars one Backspace, forward delete or cursor step crosses, from a short window next
 * to the cursor. Cursor steps cross one grapheme cluster; deletes take the whole cluster only
 * when it holds an emoji, otherwise one code point plus its non-spacing marks (as the platform
 * does for Devanagari, Hangul jamo, Thai). Emoticon shortcodes are checked first. The
 * BreakIterator is per thread.
 */
public final class GraphemeUtils {

    private static final char ZERO_WIDTH_JOINER = '\u200D';

    private static final ThreadLocal<BreakIterator> CHARACTER_ITERATOR = new ThreadLocal<BreakIterator>() {
        @Override
        protected BreakIterator initialValue() {
            return BreakIterator.getCharacterInstance(Locale.ROOT);
        }
    };

    /**
     * Chars one Backspace deletes from the end of {@code charSequence} (the text before the cursor).
     *
     * @param i the code point before the cursor
     */
    public static int getGraphemeLengthBeforeCursor(CharSequence charSequence, int i) {
        if (charSequence == null) {
            return 1;
        }
        int shortcode = EmojiTextAnalyzer.findEmoticonShortcodeLength(true, charSequence, i);
        if (shortcode != 0 || charSequence.length() == 0) {
            return shortcode;
        }
        String text = charSequence.toString();
        int length = text.length();
        int start = clusterStartBefore(text, length);
        if (containsEmoji(text, start, length)) {
            return length - start;
        }
        return codePointWithMarksBefore(text);
    }

    /**
     * Chars one forward delete removes from the start of {@code charSequence} (the text after the cursor).
     *
     * @param i the code point after the cursor
     */
    public static int getGraphemeLengthAfterCursor(CharSequence charSequence, int i) {
        if (charSequence == null) {
            return 1;
        }
        int shortcode = EmojiTextAnalyzer.findEmoticonShortcodeLength(false, charSequence, i);
        if (shortcode != 0 || charSequence.length() == 0) {
            return shortcode;
        }
        String text = charSequence.toString();
        int end = clusterEndAfter(text, 0);
        if (containsEmoji(text, 0, end)) {
            return end;
        }
        return codePointWithMarksAfter(text);
    }

    /**
     * Chars one cursor step left crosses: the last cluster of {@code charSequence}.
     *
     * @param i the code point before the cursor
     */
    public static int getClusterLengthBeforeCursor(CharSequence charSequence, int i) {
        if (charSequence == null) {
            return 1;
        }
        int shortcode = EmojiTextAnalyzer.findEmoticonShortcodeLength(true, charSequence, i);
        if (shortcode != 0 || charSequence.length() == 0) {
            return shortcode;
        }
        String text = charSequence.toString();
        return text.length() - clusterStartBefore(text, text.length());
    }

    /**
     * Chars one cursor step right crosses: the first cluster of {@code charSequence}.
     *
     * @param i the code point after the cursor
     */
    public static int getClusterLengthAfterCursor(CharSequence charSequence, int i) {
        if (charSequence == null) {
            return 1;
        }
        int shortcode = EmojiTextAnalyzer.findEmoticonShortcodeLength(false, charSequence, i);
        if (shortcode != 0 || charSequence.length() == 0) {
            return shortcode;
        }
        return clusterEndAfter(charSequence.toString(), 0);
    }

    /** @param str a two-char string; returns 0 unless it is a surrogate pair */
    public static int surrogatePairToCodePoint(String str) {
        if (str.length() != 2 || !Character.isSurrogatePair(str.charAt(0), str.charAt(1))) {
            return 0;
        }
        return Character.toCodePoint(str.charAt(0), str.charAt(1));
    }

    private static BreakIterator iteratorOver(String text) {
        BreakIterator iterator = CHARACTER_ITERATOR.get();
        iterator.setText(text);
        return iterator;
    }

    /** The start of the cluster that ends at {@code end}. */
    private static int clusterStartBefore(String text, int end) {
        BreakIterator iterator = iteratorOver(text);
        int start = iterator.preceding(end);
        while (start > 0 && isInsideEmojiSequence(text, start)) {
            start = iterator.preceding(start);
        }
        return Math.max(0, start);
    }

    /** The end of the cluster that starts at {@code start}. */
    private static int clusterEndAfter(String text, int start) {
        BreakIterator iterator = iteratorOver(text);
        int end = iterator.following(start);
        while (end != BreakIterator.DONE && end < text.length() && isInsideEmojiSequence(text, end)) {
            end = iterator.following(end);
        }
        return end == BreakIterator.DONE ? text.length() : end;
    }

    /**
     * Whether a reported boundary at {@code index} splits an emoji sequence (before an extender or
     * ZWJ after an emoji, after a joining ZWJ, inside a flag pair). Current ICU never does this;
     * older iterators, such as the JDK 17 one unit tests run on, do.
     */
    private static boolean isInsideEmojiSequence(String text, int index) {
        if (index <= 0 || index >= text.length()) {
            return false;
        }
        int before = text.codePointBefore(index);
        int next = text.codePointAt(index);
        if (before == ZERO_WIDTH_JOINER) {
            int beforeJoiner = index - 1;
            return beforeJoiner > 0
                    && EmojiTextAnalyzer.isEmojiRelated(text.codePointBefore(beforeJoiner))
                    && EmojiTextAnalyzer.isEmojiRelated(next);
        }
        if (isRegionalIndicator(next)) {
            // A flag is a pair of regional indicators: glue only to an unpaired one.
            int run = 0;
            for (int i = index; i > 0 && isRegionalIndicator(text.codePointBefore(i)); i -= 2) {
                run++;
            }
            return run % 2 == 1;
        }
        return (next == ZERO_WIDTH_JOINER || isEmojiExtender(next))
                && EmojiTextAnalyzer.isEmojiRelated(before);
    }

    private static boolean isRegionalIndicator(int codePoint) {
        return codePoint >= 0x1F1E6 && codePoint <= 0x1F1FF;
    }

    /** Skin-tone modifier, VS16, combining enclosing keycap or tag character. */
    private static boolean isEmojiExtender(int codePoint) {
        return (codePoint >= 0x1F3FB && codePoint <= 0x1F3FF)
                || codePoint == 0xFE0F
                || codePoint == 0x20E3
                || (codePoint >= 0xE0020 && codePoint <= 0xE007F);
    }

    private static boolean containsEmoji(String text, int start, int end) {
        for (int index = start; index < end; ) {
            int codePoint = text.codePointAt(index);
            if (EmojiTextAnalyzer.isEmojiRelated(codePoint)) {
                return true;
            }
            index += Character.charCount(codePoint);
        }
        return false;
    }

    /** Non-emoji delete: the last code point plus the non-spacing marks after it. */
    private static int codePointWithMarksBefore(String text) {
        int length = text.length();
        if (length >= 2 && Character.isSurrogatePair(text.charAt(length - 2), text.charAt(length - 1))) {
            return 2;
        }
        int count = 1;
        while (count < length && Character.getType(text.charAt(length - count)) == Character.NON_SPACING_MARK) {
            count++;
        }
        // Marks on a supplementary base (𝒜 + U+0301): take its whole surrogate pair.
        if (count < length && Character.isLowSurrogate(text.charAt(length - count))
                && Character.isHighSurrogate(text.charAt(length - count - 1))) {
            count++;
        }
        return count;
    }

    /** Non-emoji forward delete: the first code point plus its marks (a supplementary one alone). */
    private static int codePointWithMarksAfter(String text) {
        int length = text.length();
        if (length >= 2 && Character.isSurrogatePair(text.charAt(0), text.charAt(1))) {
            return 2;
        }
        int count = 1;
        while (count < length && Character.getType(text.charAt(count)) == Character.NON_SPACING_MARK) {
            count++;
        }
        return count;
    }
}

package dev.bbkb.ime.core.shared;

import java.text.BreakIterator;
import java.util.Locale;

/**
 * How many chars one Backspace, one forward delete or one cursor step crosses.
 *
 * <p>Cluster boundaries come from the platform's character {@link BreakIterator} (ICU on
 * Android: extended grapheme clusters, so it knows every emoji sequence the phone's Unicode
 * version knows). The two kinds of caller use it differently:
 * <ul>
 *   <li>Cursor steps ({@link #getClusterLengthBeforeCursor}, {@link #getClusterLengthAfterCursor})
 *       cross one whole cluster, as the platform's own text field does.</li>
 *   <li>Deletes ({@link #getGraphemeLengthBeforeCursor}, {@link #getGraphemeLengthAfterCursor})
 *       take the whole cluster only when it holds an emoji
 *       ({@link EmojiTextAnalyzer#isEmojiRelated}). Any other text keeps the older rule of one
 *       code point plus its non-spacing marks, so a Devanagari vowel sign, a Hangul jamo or a
 *       Thai mark is deleted on its own, as the platform's text field does, rather than the
 *       whole syllable.</li>
 * </ul>
 * Both first check for an emoticon shortcode ({@link EmojiTextAnalyzer#findEmoticonShortcodeLength}),
 * which the iterator knows nothing about.
 *
 * <p>The callers pass a short window of text (tens of chars) next to the cursor. They are the
 * backspace and cursor-move handlers, which run on the IME's main thread; the iterator is kept
 * per thread, so a call from another thread is still safe and no keystroke creates one.
 */
public final class GraphemeUtils {

    private static final char ZERO_WIDTH_JOINER = '\u200D';

    private static final ThreadLocal<BreakIterator> CHARACTER_ITERATOR = new ThreadLocal<BreakIterator>() {
        @Override
        protected BreakIterator initialValue() {
            return BreakIterator.getCharacterInstance(Locale.ROOT);
        }
    };

    /** Chars one Backspace deletes from the end of {@code charSequence} (the text before the cursor). */
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

    /** Chars one forward delete removes from the start of {@code charSequence} (the text after the cursor). */
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

    /** Chars one cursor step left crosses: the last cluster of {@code charSequence}. */
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

    /** Chars one cursor step right crosses: the first cluster of {@code charSequence}. */
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
     * Whether a boundary the iterator reported at {@code index} splits an emoji sequence: before a
     * ZWJ, skin tone, VS16, keycap or tag that follows an emoji, after a ZWJ that joins two
     * emoji, or between the two regional indicators of a flag.
     *
     * <p>A current ICU never puts a boundary there, so on a current phone this changes nothing.
     * It is for iterators that predate the emoji rules of Unicode 9 or do not know a newer emoji
     * as a modifier base: ICU on Android 6 and 7, and the JDK 17 iterator the unit tests run on,
     * which splits ZWJ sequences, flags and skin tones into their code points.
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
            // A flag is a PAIR of regional indicators: glue only to an unpaired one.
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

    /**
     * The delete rule for text that is not an emoji: the last code point plus the non-spacing
     * marks after it.
     */
    private static int codePointWithMarksBefore(String text) {
        int length = text.length();
        if (length >= 2 && Character.isSurrogatePair(text.charAt(length - 2), text.charAt(length - 1))) {
            return 2;
        }
        int count = 1;
        while (count < length && Character.getType(text.charAt(length - count)) == Character.NON_SPACING_MARK) {
            count++;
        }
        // Marks on a supplementary base (𝒜 + U+0301): take the base's whole surrogate pair, not
        // its low half.
        if (count < length && Character.isLowSurrogate(text.charAt(length - count))
                && Character.isHighSurrogate(text.charAt(length - count - 1))) {
            count++;
        }
        return count;
    }

    /**
     * The forward-delete rule for text that is not an emoji: the first code point plus the
     * non-spacing marks after it (a supplementary code point is taken alone).
     */
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

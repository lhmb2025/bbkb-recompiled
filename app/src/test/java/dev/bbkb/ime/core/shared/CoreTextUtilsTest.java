package dev.bbkb.ime.core.shared;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Locale;

/** Code-point handling in the shared text utilities that sit on the backspace/suggestion paths. */
public class CoreTextUtilsTest {

    private static final String GRINNING_FACE = "😀"; // U+1F600, one surrogate pair

    @Test
    public void isAllWhitespaceHandlesPlainStrings() {
        assertTrue(StringHelper.isAllWhitespace(""));
        assertTrue(StringHelper.isAllWhitespace("   \t\n"));
        assertFalse(StringHelper.isAllWhitespace("  a  "));
    }

    /**
     * Regression: the scan used a char index bounded by the *code point* count, so it stopped
     * short on any string containing a surrogate pair and reported an emoji-bearing string as
     * all-whitespace once its non-whitespace characters fell past the truncated bound.
     */
    @Test
    public void isAllWhitespaceIsNotFooledBySurrogatePairs() {
        // 3 code points, 4 chars: the old loop only inspected indices 0..2.
        assertFalse(StringHelper.isAllWhitespace(" " + GRINNING_FACE + "x"));
        assertFalse(StringHelper.isAllWhitespace(GRINNING_FACE));
        assertFalse(StringHelper.isAllWhitespace("  " + GRINNING_FACE));
    }

    @Test
    public void surrogatePairToCodePointMatchesTheFrameworkHelpers() {
        assertEquals(0x1F600, GraphemeUtils.surrogatePairToCodePoint(GRINNING_FACE));
        assertEquals(0, GraphemeUtils.surrogatePairToCodePoint("ab"));
        assertEquals(0, GraphemeUtils.surrogatePairToCodePoint("a"));
    }

    @Test
    public void getScriptFromLocaleDegradesInsteadOfThrowing() {
        assertEquals(ScriptUtils.SCRIPT_UNKNOWN, ScriptUtils.getScriptFromLocale(new Locale("zz")));
        // An unknown script must be accepted by the letter filter rather than crashing the IME.
        assertTrue(ScriptUtils.isLetterPartOfScript('a', ScriptUtils.SCRIPT_UNKNOWN));
        assertEquals(3, ScriptUtils.getScriptFromLocale(new Locale("ru")));
    }

    @Test
    public void distanceIsTheEuclideanDistance() {
        assertEquals(5.0f, GeometryUtils.getDistance(0f, 0f, 3f, 4f), 0.0001f);
        assertEquals(0.0f, GeometryUtils.getDistance(2f, 2f, 2f, 2f), 0.0001f);
    }

    // ── the grapheme after the cursor: the emoji scan must stop at the emoji ────

    private static int graphemeAfter(String text) {
        return GraphemeUtils.getGraphemeLengthAfterCursor(text, text.codePointAt(0));
    }

    /**
     * Regression: the leading-emoji scan only checked that the FIRST code point of a growing
     * prefix was an emoji, so once the text started with one every longer prefix matched too
     * and the whole string (capped at 20 chars) came back. One cursor step or forward delete
     * over an emoji then took the text after it as well.
     */
    @Test
    public void getGraphemeLengthAfterCursorStopsAtTheEndOfTheEmoji() {
        assertEquals(2, graphemeAfter(GRINNING_FACE + "bcdef"));
        assertEquals(2, EmojiTextAnalyzer.getLeadingEmojiLength(GRINNING_FACE + "bcdef", 0x1F600));
    }

    @Test
    public void getGraphemeLengthAfterCursorKeepsAnEmojiSequenceTogether() {
        assertEquals("skin tone", 4, graphemeAfter("👍🏽x"));
        assertEquals("ZWJ family", 8, graphemeAfter("👨\u200d👩\u200d👧x"));
        assertEquals("keycap", 3, graphemeAfter("1\ufe0f\u20e3x"));
        assertEquals("flag", 4, graphemeAfter("🇺🇸x"));
    }

    @Test
    public void getGraphemeLengthAfterCursorOverPlainTextIsOneChar() {
        assertEquals(1, graphemeAfter("abc"));
        // A digit is only a keycap when the enclosing keycap follows it.
        assertEquals(1, graphemeAfter("1x"));
        assertEquals(0, EmojiTextAnalyzer.getLeadingEmojiLength("abc", 'a'));
    }

    @Test
    public void getGraphemeLengthAfterCursorTreatsNeighbouringEmojiAsSeparate() {
        // Two emoji side by side are two steps, as they are stepping backwards.
        assertEquals(2, graphemeAfter(GRINNING_FACE + GRINNING_FACE + "x"));
        assertEquals("two flags", 4, graphemeAfter("🇺🇸🇬🇧"));
    }

    @Test
    public void getGraphemeLengthBeforeCursorStillStopsAtTheEmoji() {
        assertEquals(2, GraphemeUtils.getGraphemeLengthBeforeCursor("abc" + GRINNING_FACE, 0x1F600));
    }
}

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
}

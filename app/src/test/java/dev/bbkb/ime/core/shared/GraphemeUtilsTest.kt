package dev.bbkb.ime.core.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What one Backspace, forward delete and cursor step cross: deletes take a whole cluster only if it
 * holds an emoji (else one code point plus non-spacing marks); cursor steps take the whole cluster.
 * JDK 17's iterator splits emoji sequences like old Android ICU, so these exercise GraphemeUtils'
 * guard for that. Robolectric only for `TextUtils.isEmpty`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class GraphemeUtilsTest {

    private fun backspace(textBefore: String): Int =
        GraphemeUtils.getGraphemeLengthBeforeCursor(textBefore, cpBefore(textBefore))

    private fun forwardDelete(textAfter: String): Int =
        GraphemeUtils.getGraphemeLengthAfterCursor(textAfter, cpAfter(textAfter))

    private fun stepLeft(textBefore: String): Int =
        GraphemeUtils.getClusterLengthBeforeCursor(textBefore, cpBefore(textBefore))

    private fun stepRight(textAfter: String): Int =
        GraphemeUtils.getClusterLengthAfterCursor(textAfter, cpAfter(textAfter))

    private fun cpBefore(s: String) = if (s.isEmpty()) -1 else s.codePointBefore(s.length)
    private fun cpAfter(s: String) = if (s.isEmpty()) -1 else s.codePointAt(0)

    /** Asserts that all four measures give [expected] for [unit] next to the cursor. */
    private fun assertWholeUnit(name: String, unit: String, expected: Int = unit.length) {
        assertEquals("$name: backspace", expected, backspace("ab $unit"))
        assertEquals("$name: forward delete", expected, forwardDelete("$unit ab"))
        assertEquals("$name: step left", expected, stepLeft("ab $unit"))
        assertEquals("$name: step right", expected, stepRight("$unit ab"))
    }

    @Test
    fun newerEmojiWithASkinTone_isOneUnit() {
        // U+1FAF6 (Unicode 14) + U+1F3FD: past U+1F9FF.
        assertWholeUnit("heart hands, medium skin tone", "🫶🏽", 4)
        assertWholeUnit("melting face", "🫠", 2)
    }

    @Test
    fun olderEmojiWithASkinTone_isOneUnit() {
        assertWholeUnit("thumbs up, medium skin tone", "👍🏽", 4)
        // A BMP base: U+261D + tone.
        assertWholeUnit("index pointing up, tone", "☝🏽", 3)
    }

    @Test
    fun star_isOneUnit() {
        assertWholeUnit("star", "⭐", 1)
        assertWholeUnit("star + VS16", "⭐️", 2)
        assertWholeUnit("red heart + VS16", "❤️", 2)
    }

    @Test
    fun zwjFamily_isOneUnit() {
        assertWholeUnit("family", "👨‍👩‍👧", 8)
        assertWholeUnit("woman running", "🏃‍♀️", 5)
    }

    @Test
    fun flag_isOneUnit() {
        assertWholeUnit("flag", "🇺🇸", 4)
        // Subdivision flag: black flag + tag characters + cancel tag.
        val england = "🏴󠁧󠁢󠁥󠁮󠁧󠁿"
        assertWholeUnit("England", england, 14)
    }

    @Test
    fun keycap_isOneUnit() {
        assertWholeUnit("keycap one", "1️⃣", 3)
        assertWholeUnit("keycap without VS16", "#⃣", 2)
    }

    @Test
    fun twoFlagsInARow_goOneFlagAtATime() {
        val two = "🇺🇸🇬🇧"
        assertEquals(4, backspace(two))
        assertEquals(4, backspace(two.substring(0, 4)))
        assertEquals(4, forwardDelete(two))
        assertEquals(4, stepLeft(two))
        assertEquals(4, stepRight(two))
    }

    @Test
    fun neighbouringEmoji_areSeparate() {
        assertEquals(2, backspace("😀😀"))
        assertEquals(2, forwardDelete("😀😀"))
        assertEquals(2, stepLeft("😀😀"))
        assertEquals(2, stepRight("😀bc"))
    }

    @Test
    fun aMarkOnAnEmoji_goesWithIt() {
        // The non-emoji rule would have taken the mark and the low surrogate, splitting the pair.
        assertEquals(3, backspace("a😀́"))
    }

    @Test
    fun plainAscii_isOneChar() {
        assertWholeUnit("ascii", "x", 1)
        assertEquals(1, forwardDelete("1x")) // a digit without the keycap is plain text
    }

    @Test
    fun letterPlusCombiningAcute_isOneUnit() {
        assertWholeUnit("e + acute", "é", 2)
        assertWholeUnit("e + two marks", "ẹ́", 3)
    }

    @Test
    fun devanagariSyllable_deletesTheVowelSignAlone_butStepsAsOne() {
        val ki = "कि" // क + ि (a spacing mark, not a non-spacing one)
        assertEquals("backspace takes the vowel sign", 1, backspace("ab $ki"))
        assertEquals("forward delete takes the consonant", 1, forwardDelete("$ki ab"))
        assertEquals("one step left crosses the syllable", 2, stepLeft("ab $ki"))
        assertEquals("one step right crosses the syllable", 2, stepRight("$ki ab"))
    }

    @Test
    fun devanagariHalfForm_backspaceTakesTheJoinerAlone() {
        // क + ् + ZWJ: the ZWJ is not emoji-related, so the old rule applies.
        assertEquals(1, backspace("क्‍"))
    }

    @Test
    fun hangul_jamoSequence_deletesOneJamo_butStepsAsOne() {
        val han = "한" // ᄒ ᅡ ᆫ, one syllable in conjoining jamo
        assertEquals(1, backspace("a$han"))
        assertEquals(1, forwardDelete("${han}a"))
        assertEquals(3, stepLeft("a$han"))
        assertEquals(3, stepRight("${han}a"))
        assertWholeUnit("precomposed syllable", "한", 1)
    }

    @Test
    fun supplementaryLetter_isOneCodePoint() {
        val script = "𝒜" // 𝒜 U+1D49C, not an emoji
        assertWholeUnit("script A", script, 2)
        // With a mark: the whole pair goes with the mark, never half of it.
        assertEquals(3, backspace("a$script́"))
    }

    @Test
    fun emptyText_isZero_nullIsOne() {
        assertEquals(0, backspace(""))
        assertEquals(0, forwardDelete(""))
        assertEquals(0, stepLeft(""))
        assertEquals(0, stepRight(""))
        assertEquals(1, GraphemeUtils.getGraphemeLengthBeforeCursor(null, -1))
        assertEquals(1, GraphemeUtils.getGraphemeLengthAfterCursor(null, -1))
        assertEquals(1, GraphemeUtils.getClusterLengthBeforeCursor(null, -1))
        assertEquals(1, GraphemeUtils.getClusterLengthAfterCursor(null, -1))
    }

    @Test
    fun theOnlyUnitInTheText_isTheWholeText() {
        for (unit in listOf("a", "😀", "🇺🇸", "é", "👨‍👩‍👧")) {
            assertEquals(unit, unit.length, backspace(unit))
            assertEquals(unit, unit.length, forwardDelete(unit))
            assertEquals(unit, unit.length, stepLeft(unit))
            assertEquals(unit, unit.length, stepRight(unit))
        }
    }

    @Test
    fun emoticonShortcode_isStillOneUnit() {
        assertEquals(4, backspace("hi /::)"))
        assertEquals(4, stepLeft("hi /::)"))
        assertEquals(4, forwardDelete("/::) hi"))
        assertEquals(4, stepRight("/::) hi"))
    }

    @Test
    fun isEmoji_acceptsWhatItDidAndTheNewerBlocks() {
        for (s in listOf("😀", "😀😀😀", "☀️", "👍🏽", "👨‍👩‍👧", "️", "🏽", "◩", "✅")) {
            assertTrue(s, EmojiTextAnalyzer.isEmoji(s))
        }
        for (s in listOf("🫶", "🫠", "⭐", "🇺🇸", "⌚")) {
            assertTrue("newly recognised: $s", EmojiTextAnalyzer.isEmoji(s))
        }
        for (s in listOf("", "a", "abc", "1", "𝒜", "‍", "é")) {
            assertFalse(s, EmojiTextAnalyzer.isEmoji(s))
        }
        assertFalse(EmojiTextAnalyzer.isEmoji(null as String?))
    }
}

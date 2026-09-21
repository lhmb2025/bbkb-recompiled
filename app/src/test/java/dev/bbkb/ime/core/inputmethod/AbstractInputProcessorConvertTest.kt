package dev.bbkb.ime.core.inputmethod

import dev.bbkb.ime.core.textinput.composing.TouchPointerCoordTracker
import com.blackberry.nuanceshim.NuanceSDK
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Audit DK-39: `AbstractInputProcessor.convert()` was raw jadx output (ten pre-declared `i`..`i8`
 * temporaries) and was renamed/rescoped without any intended behaviour change. This pins the
 * coordinate-realignment contract so the rename is verifiable: while source and converted text
 * agree code point by code point, each output code point inherits the source's coordinates; at the
 * first divergence the reported counts truncate to the common prefix and everything downstream is
 * emitted with no coordinates.
 */
class AbstractInputProcessorConvertTest {

    /** A processor whose conversion is supplied by the test; `nuanceSDK` is never touched. */
    private class FixedConversion(private val output: String) : AbstractInputProcessor() {
        override fun convertText(charSequence: CharSequence, nuanceSDK: NuanceSDK?): CharSequence = output
        override fun processSymbols(charSequence: CharSequence, nuanceSDK: NuanceSDK?) = Unit
    }

    /** A tracker with one sample per code point of [text], coordinates 10*i / 20*i, flag 1. */
    private fun trackerFor(text: String): TouchPointerCoordTracker {
        val count = text.codePointCount(0, text.length)
        val tracker = TouchPointerCoordTracker(count)
        for (i in 0 until count) {
            tracker.addPointer(text.codePointAt(text.offsetByCodePoints(0, i)), 10 * i, 20 * i, 0, 0, 1)
        }
        return tracker
    }

    private fun convert(source: String, converted: String): InputMethodCallback.ConversionResult =
        FixedConversion(converted).convert(source, trackerFor(source), null)

    @Test fun identicalText_copiesTheTrackerWholesale() {
        val result = convert("abc", "abc")
        assertEquals(3, result.codePointCount)
        assertEquals(3, result.charCount)
        assertArrayEquals(intArrayOf(0, 10, 20), result.coordTracker.xCoordinates)
        assertArrayEquals(intArrayOf(0, 20, 40), result.coordTracker.yCoordinates)
        assertArrayEquals(intArrayOf(1, 1, 1), result.coordTracker.intentionalFlags)
    }

    @Test fun divergenceAtTheEnd_keepsThePrefixCoordinates() {
        // "abc" -> "abX": the first two code points align, the third diverges.
        val result = convert("abc", "abX")
        assertEquals("counts truncate to the common prefix", 2, result.codePointCount)
        assertEquals(2, result.charCount)
        assertArrayEquals(intArrayOf(0, 10, -1), result.coordTracker.xCoordinates)
        assertArrayEquals(intArrayOf(0, 20, -1), result.coordTracker.yCoordinates)
        assertArrayEquals("the diverged sample carries no intentional flag",
            intArrayOf(1, 1, 0), result.coordTracker.intentionalFlags)
    }

    @Test fun divergenceAtTheStart_dropsEveryCoordinate() {
        val result = convert("abc", "xyz")
        assertEquals(0, result.codePointCount)
        assertEquals(0, result.charCount)
        assertArrayEquals(intArrayOf(-1, -1, -1), result.coordTracker.xCoordinates)
        assertArrayEquals(intArrayOf(0, 0, 0), result.coordTracker.intentionalFlags)
    }

    @Test fun convertedLongerThanSource_padsBeyondTheSource() {
        // The loop runs over the CONVERTED code points; past the source's end there is no input
        // code point to compare, so those positions diverge and carry no coordinates.
        val result = convert("ab", "abcd")
        assertEquals(2, result.codePointCount)
        assertEquals(2, result.charCount)
        assertEquals(4, result.coordTracker.pointerSize)
        assertArrayEquals(intArrayOf(0, 10, -1, -1), result.coordTracker.xCoordinates)
    }

    @Test fun convertedShorterThanSource_onlyEmitsConvertedLength() {
        val result = convert("abcd", "ab")
        assertEquals(2, result.codePointCount)
        assertEquals(2, result.charCount)
        assertEquals(2, result.coordTracker.pointerSize)
        assertArrayEquals(intArrayOf(0, 10), result.coordTracker.xCoordinates)
    }

    @Test fun vietnameseStyleAccentSubstitution_realignsTheTail() {
        // The Telex case the realignment exists for: "as" -> "á" (one code point replaces two).
        val result = convert("as", "á")
        assertEquals(0, result.codePointCount)
        assertEquals(0, result.charCount)
        assertEquals(1, result.coordTracker.pointerSize)
        assertArrayEquals(intArrayOf(-1), result.coordTracker.xCoordinates)
    }
}

package dev.bbkb.ime.core.settings.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Combining marks are word content, whether they take space of their own or not. Until
 * 2026-09-28 only spacing marks counted, so a Tibetan vowel sign or subjoined letter, an Arabic
 * vowel point or a Thai vowel written above the line ended the word being typed: on the KEY2
 * the Tibetan keyboard committed "བྱ" as soon as ྱ was typed.
 */
class WordCodePointTest {

    @Test
    fun nonSpacingMarksAreWordContent() {
        assertTrue(SettingsValues.isCombiningMark(0x0FB1))   // Tibetan subjoined ya
        assertTrue(SettingsValues.isCombiningMark(0x0F72))   // Tibetan vowel sign i
        assertTrue(SettingsValues.isCombiningMark(0x0AC1))   // Gujarati vowel sign u
        assertTrue(SettingsValues.isCombiningMark(0x064E))   // Arabic fatha
        assertTrue(SettingsValues.isCombiningMark(0x0E34))   // Thai vowel sign i
        assertTrue(SettingsValues.isCombiningMark(0x05B8))   // Hebrew qamats
    }

    @Test
    fun spacingMarksStillAre() {
        assertTrue(SettingsValues.isCombiningMark(0x093E))   // Devanagari vowel sign aa
        assertTrue(SettingsValues.isCombiningMark(0x0ABF))   // Gujarati vowel sign i
    }

    @Test
    fun lettersPunctuationAndSpacesAreNot() {
        assertFalse(SettingsValues.isCombiningMark('a'.code))
        assertFalse(SettingsValues.isCombiningMark(' '.code))
        assertFalse(SettingsValues.isCombiningMark(','.code))
        assertFalse(SettingsValues.isCombiningMark(0x0F0B))  // Tibetan tsheg
        assertFalse(SettingsValues.isCombiningMark(0x0F40))  // Tibetan letter ka
    }
}

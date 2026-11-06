package dev.bbkb.ime.core.keyevent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [InputEvent.editsText]: what counts as the user typing, which is what brings the suggestion strip
 * back over a menu they raised (owner decision 2026-11-05: keys that edit text and swipes count;
 * modifiers, mode switches and board keys do not).
 */
class InputEventEditsTextTest {

    private fun key(codePoint: Int, keyCode: Int = 0) =
        InputEvent.createHardwareKeyPress(codePoint, keyCode, null, false, 0L)

    @Test
    fun aLetterADigitAndASymbolAreTyping() {
        assertTrue(key('a'.code).editsText())
        assertTrue(key('7'.code).editsText())
        assertTrue(key('?'.code).editsText())
    }

    @Test
    fun spaceAndEnterAreTyping() {
        assertTrue(key(' '.code).editsText())
        assertTrue(key('\n'.code).editsText())
    }

    @Test
    fun deleteIsTyping() {
        assertTrue(InputEvent.createFunctionalKeyEvent(-1, -5, null).editsText())
    }

    @Test
    fun textIsTyping() {
        assertTrue(InputEvent.createTextInputEvent(".com", -4).editsText())
    }

    @Test
    fun aModifierIsNot() {
        assertFalse(InputEvent.createModifierKeyEvent(-1, -1, null, false, false, 0L).editsText())
    }

    @Test
    fun aModeSwitchOrBoardKeyIsNot() {
        assertFalse("?123", InputEvent.createFunctionalKeyEvent(-1, -2, null).editsText())
        assertFalse("ABC", InputEvent.createFunctionalKeyEvent(-1, -3, null).editsText())
        assertFalse("emoji", InputEvent.createFunctionalKeyEvent(-1, -11, null).editsText())
    }

    @Test
    fun anEmptyEventIsNot() {
        assertFalse(InputEvent.createEmptyEvent().editsText())
    }
}

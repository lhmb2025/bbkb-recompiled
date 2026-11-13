package dev.bbkb.ime.core.keyevent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `isTextOutput()` is true only when both flag bits are set (the text-output constructor); a
 * modifier-only or shift-locked interpretation is not text output.
 */
class KeyCharacterResultTest {

    @Test fun textConstructor_isTextOutput() {
        val interpretation = KeyCharacterResult.Interpretation("abc")
        assertTrue(interpretation.isTextOutput)
    }

    @Test fun modifierOnly_isNotTextOutput() {
        // (codePoint, isShiftLocked = false, isModifier = true) -> flags == 1
        val interpretation = KeyCharacterResult.Interpretation('a'.code, false, true)
        assertTrue(interpretation.isModifier)
        assertFalse(interpretation.isShiftLocked)
        assertFalse("a modifier-only interpretation must not claim to be text output",
            interpretation.isTextOutput)
    }

    @Test fun shiftLockedOnly_isNotTextOutput() {
        // (codePoint, isShiftLocked = true, isModifier = false) -> flags == 2
        val interpretation = KeyCharacterResult.Interpretation('a'.code, true, false)
        assertTrue(interpretation.isShiftLocked)
        assertFalse(interpretation.isModifier)
        assertFalse("a shift-locked interpretation must not claim to be text output",
            interpretation.isTextOutput)
    }

    @Test fun plainCodePoint_isNotTextOutput() {
        assertFalse(KeyCharacterResult.Interpretation('a'.code).isTextOutput)
    }
}

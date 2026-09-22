package dev.bbkb.ime.core.keyevent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit DK-32: `isTextOutput()` tested `(flags & 3) != 0`, so a modifier-only or shift-locked
 * interpretation reported itself as text output. Only the text-output constructor sets both bits.
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

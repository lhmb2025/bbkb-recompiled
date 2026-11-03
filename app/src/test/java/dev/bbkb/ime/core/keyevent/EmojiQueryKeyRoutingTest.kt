package dev.bbkb.ime.core.keyevent

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Which hardware keys the open emoji board takes for its search query (owner decisions,
 * 2026-11-02): letters, digits, punctuation, Space and Backspace feed the query; Enter, the
 * modifiers, Sym, Back, function and board keys pass through. The processor supplies the resolved
 * key and the converted event; these are the pure rules.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class EmojiQueryKeyRoutingTest {

    private fun physical(keyCode: Int) = ResolvedKey.forTest(keyCode, 0, true, null)

    @Test
    fun lettersSpaceAndBackspaceMayFeedTheQuery() {
        assertTrue(EmojiQueryKeyRouting.mayFeedQuery(physical(KeyEvent.KEYCODE_A)))
        assertTrue(EmojiQueryKeyRouting.mayFeedQuery(physical(KeyEvent.KEYCODE_SPACE)))
        assertTrue(EmojiQueryKeyRouting.mayFeedQuery(physical(KeyEvent.KEYCODE_DEL)))
        assertTrue(EmojiQueryKeyRouting.mayFeedQuery(physical(KeyEvent.KEYCODE_1)))
        assertTrue(EmojiQueryKeyRouting.mayFeedQuery(physical(KeyEvent.KEYCODE_COMMA)))
    }

    @Test
    fun enterModifiersSymAndBackPassThrough() {
        assertFalse(EmojiQueryKeyRouting.mayFeedQuery(physical(KeyEvent.KEYCODE_ENTER)))
        assertFalse(EmojiQueryKeyRouting.mayFeedQuery(physical(KeyEvent.KEYCODE_SHIFT_LEFT)))
        assertFalse(EmojiQueryKeyRouting.mayFeedQuery(physical(KeyEvent.KEYCODE_ALT_LEFT)))
        assertFalse(EmojiQueryKeyRouting.mayFeedQuery(physical(KeyEvent.KEYCODE_CTRL_LEFT)))
        assertFalse(EmojiQueryKeyRouting.mayFeedQuery(physical(KeyEvent.KEYCODE_SYM)))
        assertFalse(EmojiQueryKeyRouting.mayFeedQuery(physical(KeyEvent.KEYCODE_BACK)))
    }

    @Test
    fun onScreenEventsNeverFeedTheQuery() {
        assertFalse(EmojiQueryKeyRouting.mayFeedQuery(ResolvedKey.forTest(KeyEvent.KEYCODE_A, 0, false, null)))
    }

    @Test
    fun aPrintableCodePointIsTheQueryText() {
        assertEquals("a", EmojiQueryKeyRouting.queryText(hardwarePress('a'.code, KeyEvent.KEYCODE_A)))
        assertEquals("A", EmojiQueryKeyRouting.queryText(hardwarePress('A'.code, KeyEvent.KEYCODE_A)))
        assertEquals(" ", EmojiQueryKeyRouting.queryText(hardwarePress(' '.code, KeyEvent.KEYCODE_SPACE)))
        assertEquals("@", EmojiQueryKeyRouting.queryText(hardwarePress('@'.code, KeyEvent.KEYCODE_P)))
        assertEquals("é", EmojiQueryKeyRouting.queryText(hardwarePress(0xE9, KeyEvent.KEYCODE_E)))
        assertEquals("😀", EmojiQueryKeyRouting.queryText(hardwarePress(0x1F600, KeyEvent.KEYCODE_UNKNOWN)))
    }

    @Test
    fun textOutputIsTheQueryText() {
        val event = InputEvent.createTextEvent(0, "ij", KeyEvent.KEYCODE_I, null, false, 0L, false)
        assertEquals("ij", EmojiQueryKeyRouting.queryText(event))
        assertNull(EmojiQueryKeyRouting.queryText(InputEvent.createTextEvent(0, "", KeyEvent.KEYCODE_I, null, false, 0L, false)))
    }

    @Test
    fun controlCharactersModifiersAndEmptyEventsAddNothing() {
        assertNull("tab", EmojiQueryKeyRouting.queryText(hardwarePress('\t'.code, KeyEvent.KEYCODE_TAB)))
        assertNull("newline", EmojiQueryKeyRouting.queryText(hardwarePress('\n'.code, KeyEvent.KEYCODE_ENTER)))
        assertNull("delete", EmojiQueryKeyRouting.queryText(hardwarePress(0x7f, KeyEvent.KEYCODE_FORWARD_DEL)))
        assertNull("negative key code", EmojiQueryKeyRouting.queryText(hardwarePress(-5, KeyEvent.KEYCODE_DEL)))
        assertNull("modifier", EmojiQueryKeyRouting.queryText(
            InputEvent.createModifierKeyEvent(0, KeyEvent.KEYCODE_SHIFT_LEFT, null, false, false, 0L)))
        assertNull("empty", EmojiQueryKeyRouting.queryText(InputEvent.createEmptyEvent()))
        assertNull("null", EmojiQueryKeyRouting.queryText(null))
        assertNull("gesture end", EmojiQueryKeyRouting.queryText(
            InputEvent.createGestureEndCopy(hardwarePress('a'.code, KeyEvent.KEYCODE_A))))
    }

    private fun hardwarePress(codePoint: Int, keyCode: Int): InputEvent =
        InputEvent.createHardwareKeyPressEx(codePoint, keyCode, null, false, 0L, false)
}

package dev.bbkb.ime.core.keyevent

import android.view.KeyEvent
import dev.bbkb.ime.core.device.config.model.KeyRole
import dev.bbkb.ime.core.device.config.model.ScancodeMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ResolvedKey] on its own: the value type Phase 1e introduced, with no IME and no singletons in
 * the picture. Everything here is a question about key identity and nothing else — the suite has
 * no way to observe modifier state or the emoji/mic/multifunction pairing flags, which is the
 * boundary this slice was drawn on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ResolvedKeyTest {

    // ===================================================== the raw identity it carries

    @Test
    fun `it carries the key code, the scancode and the classification it was built with`() {
        val key = ResolvedKey.forTest(KeyEvent.KEYCODE_R, /* scanCode */ 19, true, null)

        assertEquals(KeyEvent.KEYCODE_R, key.keyCode())
        assertEquals(19, key.scanCode())
        assertTrue(key.isPhysical())
        assertNull(key.mapping())
        assertNull(key.role())
    }

    @Test
    fun `a virtual key reports itself as such`() {
        assertFalse(ResolvedKey.forTest(KeyEvent.KEYCODE_R, 19, false, null).isPhysical())
    }

    // ===================================================== the two pseudo-keycodes

    @Test
    fun `the pseudo-keycodes are 666 and 667`() {
        assertEquals(666, ResolvedKey.PSEUDO_KEYCODE_EMOJI)
        assertEquals(667, ResolvedKey.PSEUDO_KEYCODE_VOICE)
    }

    @Test
    fun `666 is the emoji key and 667 is not`() {
        val emoji = resolved(ResolvedKey.PSEUDO_KEYCODE_EMOJI)
        assertTrue(emoji.isPseudoEmojiKeyCode())
        assertFalse(emoji.isPseudoVoiceKeyCode())
        assertTrue(emoji.isEmojiKey())

        val voice = resolved(ResolvedKey.PSEUDO_KEYCODE_VOICE)
        assertTrue(voice.isPseudoVoiceKeyCode())
        assertFalse(voice.isPseudoEmojiKeyCode())
        assertFalse("667 has never been the emoji key's legacy fallback", voice.isEmojiKey())
    }

    @Test
    fun `both pseudo-keycodes are board keys, an ordinary key code is not`() {
        assertTrue(resolved(ResolvedKey.PSEUDO_KEYCODE_EMOJI).isBoardKey())
        assertTrue(resolved(ResolvedKey.PSEUDO_KEYCODE_VOICE).isBoardKey())
        assertFalse(resolved(KeyEvent.KEYCODE_R).isBoardKey())
        assertFalse(
            "the pseudo-keycodes are not a device-config role",
            resolved(ResolvedKey.PSEUDO_KEYCODE_EMOJI).isRoleBoardKey()
        )
    }

    @Test
    fun `666 stays the emoji key even when the device config calls it something else`() {
        val key = resolved(ResolvedKey.PSEUDO_KEYCODE_EMOJI, mapping(KeyRole.CHARACTER))

        assertTrue(key.isEmojiKey())
        assertTrue(key.isBoardKey())
    }

    // ===================================================== device-config roles

    @Test
    fun `the board roles are board keys`() {
        for (role in listOf(KeyRole.BOARD_EMOJI, KeyRole.BOARD_VOICE, KeyRole.BOARD_SYM)) {
            assertTrue(role.name, resolved(KeyEvent.KEYCODE_R, mapping(role)).isBoardKey())
        }
    }

    @Test
    fun `MULTIFUNCTION is a board key too, though the accessibility layer never eats it`() {
        val key = resolved(KeyEvent.KEYCODE_7, mapping(KeyRole.MULTIFUNCTION))

        assertTrue(key.isBoardKey())
        assertTrue(key.isMultifunctionKey())
        assertFalse(KeyRole.MULTIFUNCTION.isConsumedAtAccessibilityLevel())
    }

    @Test
    fun `CHARACTER, MODIFIER and FUNCTION roles are not board keys`() {
        for (role in listOf(KeyRole.CHARACTER, KeyRole.MODIFIER, KeyRole.FUNCTION)) {
            assertFalse(role.name, resolved(KeyEvent.KEYCODE_R, mapping(role)).isBoardKey())
        }
    }

    @Test
    fun `BOARD_EMOJI is the emoji key and BOARD_VOICE is the voice key`() {
        assertTrue(resolved(KeyEvent.KEYCODE_R, mapping(KeyRole.BOARD_EMOJI)).isEmojiKey())
        assertFalse(resolved(KeyEvent.KEYCODE_R, mapping(KeyRole.BOARD_EMOJI)).isVoiceKey())
        assertTrue(resolved(KeyEvent.KEYCODE_R, mapping(KeyRole.BOARD_VOICE)).isVoiceKey())
        assertFalse(resolved(KeyEvent.KEYCODE_R, mapping(KeyRole.BOARD_VOICE)).isEmojiKey())
    }

    // ===================================================== the Sym key's two names

    @Test
    fun `the Sym key is KEYCODE_SYM when the config is silent`() {
        assertTrue(resolved(KeyEvent.KEYCODE_SYM).isSymKey())
        assertFalse(resolved(KeyEvent.KEYCODE_ALT_RIGHT).isSymKey())
    }

    @Test
    fun `a BOARD_SYM role outranks the key code the ROM attached`() {
        assertTrue(
            resolved(KeyEvent.KEYCODE_ALT_RIGHT, mapping(KeyRole.BOARD_SYM)).isSymKey()
        )
        assertFalse(
            "a config that gives the key another role wins over KEYCODE_SYM",
            resolved(KeyEvent.KEYCODE_SYM, mapping(KeyRole.CHARACTER)).isSymKey()
        )
    }

    // ===================================================== board ids

    @Test
    fun `the board id falls back when the config names none`() {
        assertEquals(
            ResolvedKey.DEFAULT_EMOJI_BOARD_ID,
            resolved(KeyEvent.KEYCODE_R, mapping(KeyRole.BOARD_EMOJI))
                .boardId(ResolvedKey.DEFAULT_EMOJI_BOARD_ID)
        )
        assertEquals(
            ResolvedKey.DEFAULT_VOICE_BOARD_ID,
            resolved(KeyEvent.KEYCODE_R, null).boardId(ResolvedKey.DEFAULT_VOICE_BOARD_ID)
        )
    }

    @Test
    fun `a config board id wins over the fallback`() {
        val key = resolved(KeyEvent.KEYCODE_R, mapping(KeyRole.BOARD_EMOJI, boardId = 42))

        assertEquals(42, key.boardId(ResolvedKey.DEFAULT_EMOJI_BOARD_ID))
    }

    @Test
    fun `the default board ids are the emoji and voice board key codes`() {
        assertEquals(-11, ResolvedKey.DEFAULT_EMOJI_BOARD_ID)
        assertEquals(-27, ResolvedKey.DEFAULT_VOICE_BOARD_ID)
    }

    // ===================================================== the key-code classes

    @Test
    fun `it names the key-code classes the processor branches on`() {
        assertTrue(resolved(KeyEvent.KEYCODE_ENTER).isEnterKey())
        assertTrue(resolved(KeyEvent.KEYCODE_DEL).isBackspaceKey())
        assertTrue(resolved(KeyEvent.KEYCODE_BACK).isBackKey())
        assertTrue(resolved(KeyEvent.KEYCODE_FUNCTION).isFunctionKey())
        assertTrue(resolved(KeyEvent.KEYCODE_SHIFT_LEFT).isShiftKey())
        assertTrue(resolved(KeyEvent.KEYCODE_SHIFT_RIGHT).isShiftKey())
        assertTrue(resolved(KeyEvent.KEYCODE_ALT_LEFT).isAltKey())
        assertTrue(resolved(KeyEvent.KEYCODE_ALT_RIGHT).isAltKey())
    }

    @Test
    fun `an ordinary letter key is none of those classes`() {
        val key = resolved(KeyEvent.KEYCODE_R)

        assertFalse(key.isEnterKey())
        assertFalse(key.isBackspaceKey())
        assertFalse(key.isBackKey())
        assertFalse(key.isFunctionKey())
        assertFalse(key.isShiftKey())
        assertFalse(key.isAltKey())
        assertFalse(key.isBoardKey())
        assertFalse(key.isSymKey())
    }

    /**
     * Arrows, Home/End, digits, currency and word separators are resolved downstream (by
     * `KeyEventConverter` and `InputLogic`), not here: identity does not claim to answer them.
     */
    @Test
    fun `keys this layer does not discriminate report no role at all`() {
        for (keyCode in listOf(
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MOVE_HOME,
            KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_PERIOD
        )) {
            val key = resolved(keyCode)
            assertNull("keycode $keyCode", key.role())
            assertFalse("keycode $keyCode", key.isBoardKey())
            assertNull("keycode $keyCode", key.getMultifunctionAction())
        }
    }

    // ===================================================== the multifunction action

    @Test
    fun `a non-multifunction key has no configured action`() {
        assertNull(resolved(KeyEvent.KEYCODE_R).getMultifunctionAction())
        assertNull(resolved(KeyEvent.KEYCODE_R, mapping(KeyRole.BOARD_EMOJI)).getMultifunctionAction())
    }

    // ===================================================== helpers

    private fun resolved(keyCode: Int, mapping: ScancodeMapping? = null): ResolvedKey =
        ResolvedKey.forTest(keyCode, /* scanCode */ 0, /* physical */ true, mapping)

    private fun mapping(role: KeyRole, boardId: Int = 0): ScancodeMapping =
        ScancodeMapping().also {
            it.role = role
            it.boardId = boardId
        }
}

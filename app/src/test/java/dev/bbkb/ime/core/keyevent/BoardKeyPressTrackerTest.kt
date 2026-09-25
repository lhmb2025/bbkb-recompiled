package dev.bbkb.ime.core.keyevent

import android.view.KeyEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [BoardKeyPressTracker]'s contract on its own — no IME, no converter, no processor.
 *
 * <p>Every clause of the contract stated in that class's javadoc has a test here, including the two
 * that are easy to get wrong in a rewrite: one slot per *action* (not per key), and which
 * [ModifierResetReason]s discard a pending action.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class BoardKeyPressTrackerTest {

    private val tracker get() = BoardKeyPressTracker.getInstance()

    @Before
    fun setUp() = tracker.clearPendingActions()

    @After
    fun tearDown() = tracker.clearPendingActions()

    // ===================================================== arm / consume

    @Test
    fun `nothing is armed to begin with`() {
        for (action in PendingKeyAction.values()) {
            assertFalse(action.name, tracker.isArmed(action))
            assertNull(action.name, tracker.armedBy(action))
            assertFalse(action.name, tracker.consume(action))
        }
    }

    @Test
    fun `an armed action is pending until it is consumed`() {
        tracker.arm(event(KeyEvent.KEYCODE_7, 8), PendingKeyAction.VOICE_INPUT)

        assertTrue(tracker.isArmed(PendingKeyAction.VOICE_INPUT))
        assertTrue(tracker.consume(PendingKeyAction.VOICE_INPUT))
        assertFalse("consume must spend it", tracker.isArmed(PendingKeyAction.VOICE_INPUT))
        assertFalse("and it cannot be spent twice", tracker.consume(PendingKeyAction.VOICE_INPUT))
    }

    @Test
    fun `isArmed does not spend the pending action`() {
        tracker.arm(event(KeyEvent.KEYCODE_7, 8), PendingKeyAction.MULTIFUNCTION)

        assertTrue(tracker.isArmed(PendingKeyAction.MULTIFUNCTION))
        assertTrue(tracker.isArmed(PendingKeyAction.MULTIFUNCTION))
        assertTrue(tracker.consume(PendingKeyAction.MULTIFUNCTION))
    }

    @Test
    fun `the three actions are independent slots`() {
        tracker.arm(event(KeyEvent.KEYCODE_7, 8), PendingKeyAction.VOICE_INPUT)
        tracker.arm(event(KeyEvent.KEYCODE_ALT_RIGHT, 250), PendingKeyAction.EMOJI_BOARD)

        assertTrue(tracker.consume(PendingKeyAction.VOICE_INPUT))
        assertTrue("spending one must not spend another", tracker.isArmed(PendingKeyAction.EMOJI_BOARD))
        assertFalse(tracker.isArmed(PendingKeyAction.MULTIFUNCTION))
    }

    /**
     * One slot per action, not one per key — the shape of the three booleans this replaces. Two
     * emoji-role keys held together used to set one flag and they arm one slot.
     */
    @Test
    fun `arming the same action for a second key replaces the first`() {
        tracker.arm(event(KeyEvent.KEYCODE_ALT_RIGHT, 250), PendingKeyAction.EMOJI_BOARD)
        tracker.arm(event(KeyEvent.KEYCODE_ALT_LEFT, 251), PendingKeyAction.EMOJI_BOARD)

        assertEquals(
            KeyEvent.KEYCODE_ALT_LEFT,
            tracker.armedBy(PendingKeyAction.EMOJI_BOARD)!!.keyCode()
        )
        assertTrue(tracker.consume(PendingKeyAction.EMOJI_BOARD))
        assertFalse("one arm, one action", tracker.consume(PendingKeyAction.EMOJI_BOARD))
    }

    // ===================================================== the recorded identity

    @Test
    fun `the arming key's identity is recorded`() {
        tracker.arm(event(KeyEvent.KEYCODE_ALT_RIGHT, 250), PendingKeyAction.EMOJI_BOARD)

        val armed = tracker.armedBy(PendingKeyAction.EMOJI_BOARD)!!
        assertEquals(KeyEvent.KEYCODE_ALT_RIGHT, armed.keyCode())
        assertEquals(250, armed.scanCode())
    }

    @Test
    fun `an already-resolved key can arm too, with the same identity`() {
        val key = ResolvedKey.forTest(KeyEvent.KEYCODE_7, 8, /* physical */ true, null)
        tracker.arm(key, PendingKeyAction.VOICE_INPUT)

        assertEquals(
            ResolvedKey.Identity.of(event(KeyEvent.KEYCODE_7, 8)),
            tracker.armedBy(PendingKeyAction.VOICE_INPUT)
        )
    }

    @Test
    fun `consuming clears the recorded identity`() {
        tracker.arm(event(KeyEvent.KEYCODE_7, 8), PendingKeyAction.VOICE_INPUT)
        tracker.consume(PendingKeyAction.VOICE_INPUT)

        assertNull(tracker.armedBy(PendingKeyAction.VOICE_INPUT))
    }

    // ===================================================== the reset contract

    @Test
    fun `every full reset reason discards pending actions`() {
        for (reason in ModifierResetReason.values()) {
            if (reason.scope() != ModifierResetReason.Scope.ALL) continue
            tracker.arm(event(KeyEvent.KEYCODE_7, 8), PendingKeyAction.VOICE_INPUT)

            tracker.onModifiersReset(reason)

            assertFalse(reason.name, tracker.isArmed(PendingKeyAction.VOICE_INPUT))
        }
    }

    @Test
    fun `a partial reset leaves pending actions armed`() {
        for (reason in ModifierResetReason.values()) {
            if (reason.scope() == ModifierResetReason.Scope.ALL) continue
            tracker.clearPendingActions()
            tracker.arm(event(KeyEvent.KEYCODE_7, 8), PendingKeyAction.VOICE_INPUT)

            tracker.onModifiersReset(reason)

            assertTrue(reason.name, tracker.isArmed(PendingKeyAction.VOICE_INPUT))
        }
    }

    @Test
    fun `a full reset discards all three at once`() {
        tracker.arm(event(KeyEvent.KEYCODE_7, 8), PendingKeyAction.VOICE_INPUT)
        tracker.arm(event(KeyEvent.KEYCODE_ALT_RIGHT, 250), PendingKeyAction.EMOJI_BOARD)
        tracker.arm(event(KeyEvent.KEYCODE_FUNCTION, 110), PendingKeyAction.MULTIFUNCTION)

        tracker.onModifiersReset(ModifierResetReason.WINDOW_HIDDEN)

        for (action in PendingKeyAction.values()) {
            assertFalse(action.name, tracker.isArmed(action))
        }
    }

    // ===================================================== Identity

    @Test
    fun `identity is the key code and the scancode together`() {
        val a = ResolvedKey.Identity.of(event(KeyEvent.KEYCODE_ALT_RIGHT, 249))
        val sameKey = ResolvedKey.Identity.of(event(KeyEvent.KEYCODE_ALT_RIGHT, 249))
        val sameKeyCodeOtherKey = ResolvedKey.Identity.of(event(KeyEvent.KEYCODE_ALT_RIGHT, 250))
        val sameScanCodeOtherKeyCode = ResolvedKey.Identity.of(event(KeyEvent.KEYCODE_SYM, 249))

        assertEquals(a, sameKey)
        assertEquals(a.hashCode(), sameKey.hashCode())
        assertNotEquals(
            "the MP01 sends KEYCODE_ALT_RIGHT for both its Sym key and its emoji key",
            a, sameKeyCodeOtherKey
        )
        assertNotEquals(
            "a MULTIFUNCTION key remapped to Ctrl keeps its scancode and changes its key code",
            a, sameScanCodeOtherKeyCode
        )
    }

    /** Neither the device nor the meta state is part of identity: a held key stays the same key. */
    @Test
    fun `identity ignores the device, the action and the meta state`() {
        val down = KeyEvent(
            0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_7, 0, KeyEvent.META_ALT_ON, 3, 8
        )
        val up = KeyEvent(0L, 0L, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_7, 0, 0, 9, 8)

        assertEquals(ResolvedKey.Identity.of(down), ResolvedKey.Identity.of(up))
    }

    @Test
    fun `a ResolvedKey reports its own identity`() {
        val key = ResolvedKey.forTest(KeyEvent.KEYCODE_7, 8, /* physical */ true, null)

        assertEquals(KeyEvent.KEYCODE_7, key.identity().keyCode())
        assertEquals(8, key.identity().scanCode())
        assertEquals(key.identity(), key.identity())
    }

    private fun event(keyCode: Int, scanCode: Int): KeyEvent =
        KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, keyCode, 0, 0, 0, scanCode)
}

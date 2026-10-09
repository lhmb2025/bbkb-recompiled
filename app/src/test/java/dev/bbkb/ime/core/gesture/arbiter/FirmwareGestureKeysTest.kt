package dev.bbkb.ime.core.gesture.arbiter

import dev.bbkb.ime.core.device.config.model.KeyRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A firmware swipe key (the Titan 2's keycode 322 / 404, role GESTURE_SWIPE_LEFT) runs whatever
 * the user assigned to the swipe-left slot — the same slot a pad swipe left uses — through the
 * ordinary policy. Pure JVM.
 */
class FirmwareGestureKeysTest {

    private val mode = ModeState(gesturesEnabled = true, swipeTypingEnabled = false,
        fccActive = false, composing = false)

    @Test
    fun onlyTheGestureRoleIsAFirmwareGestureKey() {
        val swipe = FirmwareGestureKeys.swipeFor(KeyRole.GESTURE_SWIPE_LEFT)!!
        assertEquals(GestureDirection.LEFT, swipe.direction)
        assertEquals("the swipe slot, not the flick slot", false, swipe.fast)
        for (role in KeyRole.values().filter { it != KeyRole.GESTURE_SWIPE_LEFT }) {
            assertNull("$role", FirmwareGestureKeys.swipeFor(role))
            assertNull(FirmwareGestureKeys.outcome(role, mode, GestureAssignments()))
        }
        assertNull(FirmwareGestureKeys.swipeFor(null))
    }

    @Test
    fun defaultAssignment_deletesAWord() {
        assertEquals(PolicyOutcome.Act(GestureAction.DELETE_WORD),
            FirmwareGestureKeys.outcome(KeyRole.GESTURE_SWIPE_LEFT, mode, GestureAssignments()))
    }

    @Test
    fun runsWhateverTheUserAssignedToSwipeLeft() {
        for (action in listOf(GestureAction.NEXT_LANGUAGE, GestureAction.ENTER_CURSOR_MODE,
                GestureAction.CYCLE_SYMBOLS, GestureAction.DISMISS_KEYBOARD)) {
            val assigned = GestureAssignments(swipeLeft = action, flickLeft = GestureAction.UNDO)
            assertEquals(PolicyOutcome.Act(action),
                FirmwareGestureKeys.outcome(KeyRole.GESTURE_SWIPE_LEFT, mode, assigned))
        }
    }

    @Test
    fun anUnassignedSlot_orGesturesOff_orCursorMode_doesNothing() {
        assertEquals(PolicyOutcome.Yield, FirmwareGestureKeys.outcome(KeyRole.GESTURE_SWIPE_LEFT, mode,
            GestureAssignments(swipeLeft = GestureAction.NONE)))
        assertEquals(PolicyOutcome.Yield, FirmwareGestureKeys.outcome(KeyRole.GESTURE_SWIPE_LEFT,
            mode.copy(gesturesEnabled = false), GestureAssignments()))
        assertEquals(PolicyOutcome.Yield, FirmwareGestureKeys.outcome(KeyRole.GESTURE_SWIPE_LEFT,
            mode.copy(fccActive = true), GestureAssignments()))
    }

    @Test
    fun theTypingGuardDoesNotApply_theKeyIsNotTyping() {
        val typing = mode.copy(keyTiming = KeyTiming(startedInNoiseWindow = true,
            keyDuringContact = true, insideSuppressionWindow = true))
        assertEquals(PolicyOutcome.Act(GestureAction.DELETE_WORD),
            FirmwareGestureKeys.outcome(KeyRole.GESTURE_SWIPE_LEFT, typing, GestureAssignments()))
    }

    // ── device defaults for the slots ────────────────────────────────────────

    @Test
    fun deviceDefaults_replaceOnlyTheSlotsTheyName() {
        val d = GestureAssignments.defaults { key ->
            if (key == GestureAssignments.KEY_DOUBLE_TAP) "none" else null
        }
        assertEquals(GestureAction.NONE, d.doubleTap)
        assertEquals(GestureAssignments().copy(doubleTap = GestureAction.NONE), d)
        assertEquals("no device defaults: the app's", GestureAssignments(), GestureAssignments.defaults { null })
    }
}

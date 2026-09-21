package dev.bbkb.ime.core.gesture.arbiter

import org.junit.Assert.assertEquals
import org.junit.Test

class GesturePolicyTest {

    private val policy = GesturePolicy()
    private val assignments = GestureAssignments() // defaults
    private val normal = ModeState(gesturesEnabled = true, swipeTypingEnabled = true, fccActive = false, composing = true)

    private fun swipe(dir: GestureDirection, fast: Boolean) =
        GestureClassification.Swipe(dir, fast, confidence = 1f)

    private fun act(c: GestureClassification, mode: ModeState = normal) = policy.resolve(c, mode, assignments)

    // --- typing windows: a finger that lands/rests while keys go down is typing ---

    private val typing = normal.copy(
        keyTiming = KeyTiming(startedInNoiseWindow = false, keyDuringContact = true, insideSuppressionWindow = false)
    )

    @Test fun anyTypingWindow_yieldsEveryAssignableGesture() {
        for (dir in GestureDirection.values()) for (fast in listOf(true, false)) {
            assertEquals("$dir fast=$fast", PolicyOutcome.Yield, act(swipe(dir, fast), typing))
        }
        assertEquals(PolicyOutcome.Yield, act(GestureClassification.Hold, typing))
        assertEquals(PolicyOutcome.Yield, act(GestureClassification.DoubleTap, typing))
    }

    @Test fun typingWindow_alsoHoldsBackTheReservedCommit() {
        // The original never dispatched the up-swipe commit inside its windows either; a flick-up
        // that is really a finger lifting off a key must not commit a suggestion.
        assertEquals(PolicyOutcome.Yield, act(swipe(GestureDirection.UP, fast = true), typing))
    }

    @Test fun eachWindowAloneSuppresses() {
        val noise = normal.copy(keyTiming = KeyTiming(true, false, false))
        val suppression = normal.copy(keyTiming = KeyTiming(false, false, true))
        assertEquals(PolicyOutcome.Yield, act(swipe(GestureDirection.LEFT, fast = true), noise))
        assertEquals(PolicyOutcome.Yield, act(swipe(GestureDirection.LEFT, fast = true), suppression))
    }

    @Test fun clearTiming_isTheDefault_andActs() {
        assertEquals(KeyTiming.CLEAR, normal.keyTiming)
        assertEquals(PolicyOutcome.Act(GestureAction.DELETE_WORD), act(swipe(GestureDirection.LEFT, fast = true)))
    }

    // --- reserved + folded directions ---

    @Test fun flickUp_isReservedCommit() {
        assertEquals(PolicyOutcome.Act(GestureAction.COMMIT_SUGGESTION), act(swipe(GestureDirection.UP, fast = true)))
    }

    @Test fun slowSwipeUp_alsoCommits() {
        // Up is fully reserved now: flick OR swipe up both commit, so a slow up never dead-ends.
        assertEquals(PolicyOutcome.Act(GestureAction.COMMIT_SUGGESTION), act(swipe(GestureDirection.UP, fast = false)))
    }

    @Test fun flickDown_foldsIntoSwipeDownSlot() {
        assertEquals(PolicyOutcome.Act(GestureAction.CYCLE_SYMBOLS), act(swipe(GestureDirection.DOWN, fast = true)))
    }

    @Test fun swipeDown_isCycleSymbolsByDefault() {
        assertEquals(PolicyOutcome.Act(GestureAction.CYCLE_SYMBOLS), act(swipe(GestureDirection.DOWN, fast = false)))
    }

    // --- horizontal: flick vs swipe distinct slots ---

    @Test fun flickLeft_usesFlickLeftSlot() {
        assertEquals(PolicyOutcome.Act(GestureAction.DELETE_WORD), act(swipe(GestureDirection.LEFT, fast = true)))
    }

    @Test fun swipeLeft_usesSwipeLeftSlot() {
        assertEquals(PolicyOutcome.Act(GestureAction.DELETE_WORD), act(swipe(GestureDirection.LEFT, fast = false)))
    }

    @Test fun flickAndSwipeRight_canDiffer() {
        val custom = assignments.copy(flickRight = GestureAction.NEXT_LANGUAGE, swipeRight = GestureAction.EMOJI)
        assertEquals(PolicyOutcome.Act(GestureAction.NEXT_LANGUAGE), policy.resolve(swipe(GestureDirection.RIGHT, true), normal, custom))
        assertEquals(PolicyOutcome.Act(GestureAction.EMOJI), policy.resolve(swipe(GestureDirection.RIGHT, false), normal, custom))
    }

    // --- hold / double-tap ---

    @Test fun doubleTap_entersCursorModeByDefault() {
        assertEquals(PolicyOutcome.Act(GestureAction.ENTER_CURSOR_MODE), act(GestureClassification.DoubleTap))
    }

    @Test fun hold_isNoneByDefault_yieldsNotConsumes() {
        // NONE must yield (not consume) so a no-action gesture never aborts a flow word.
        // Holding a key must also fall through to its normal behavior rather than be eaten.
        assertEquals(PolicyOutcome.Yield, act(GestureClassification.Hold))
    }

    @Test fun swipeResolvingToNone_yields() {
        // flick-right defaults to NONE → must yield rather than eat a possible gesture-typed word.
        assertEquals(PolicyOutcome.Yield, act(swipe(GestureDirection.RIGHT, fast = true)))
    }

    // --- yields ---

    @Test fun tap_yields() {
        assertEquals(PolicyOutcome.Yield, act(GestureClassification.Tap))
    }

    @Test fun flowTrace_yields() {
        assertEquals(PolicyOutcome.Yield, act(GestureClassification.FlowTrace))
    }

    @Test fun gesturesDisabled_yieldsEverySwipe() {
        val off = normal.copy(gesturesEnabled = false)
        assertEquals(PolicyOutcome.Yield, act(swipe(GestureDirection.LEFT, true), off))
        assertEquals(PolicyOutcome.Yield, act(swipe(GestureDirection.UP, true), off)) // even reserved commit
    }

    @Test fun fccActive_yieldsGestures() {
        val fcc = normal.copy(fccActive = true)
        assertEquals(PolicyOutcome.Yield, act(swipe(GestureDirection.DOWN, false), fcc))
        assertEquals(PolicyOutcome.Yield, act(GestureClassification.Hold, fcc))
    }
}

package dev.bbkb.ime.core.gesture.arbiter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three typing windows the original applied to CKB gestures, now judged by [KeyTypingGuard]:
 * 150 ms touch-noise after a key, cancel-on-key during a contact, 350 ms suppression before the
 * gesture's end (or start).
 */
class KeyTypingGuardTest {

    private var measureFromEnd = true

    private val guard = KeyTypingGuard(
        noiseWindowMs = { 150L },
        suppressionWindowMs = { 350L },
        measureFromEnd = { measureFromEnd },
    )

    @Test fun noKeyEverSeen_isClear() {
        guard.onContactStart(1_000)
        assertEquals(KeyTiming.CLEAR, guard.keyTiming(1_400))
        assertFalse(guard.keyTiming(1_400).suppressesGestures)
    }

    @Test fun keyLongBeforeContact_isClear() {
        guard.onKeyEvent(1_000)
        guard.onContactStart(1_400) // 400 ms later: outside both windows
        assertEquals(KeyTiming.CLEAR, guard.keyTiming(1_600))
    }

    @Test fun contactStartingInsideNoiseWindow_isSuppressed() {
        guard.onKeyEvent(1_000)
        guard.onContactStart(1_100) // 100 ms: a landing finger
        val t = guard.keyTiming(2_000) // gesture ends well clear of the suppression window
        assertTrue(t.startedInNoiseWindow)
        assertFalse(t.insideSuppressionWindow)
        assertTrue(t.suppressesGestures)
    }

    @Test fun contactStartingJustOutsideNoiseWindow_butEndingInsideSuppression_isSuppressed() {
        guard.onKeyEvent(1_000)
        guard.onContactStart(1_150) // exactly the window: not noise
        val t = guard.keyTiming(1_300) // 300 ms after the key: inside 350
        assertFalse(t.startedInNoiseWindow)
        assertTrue(t.insideSuppressionWindow)
        assertTrue(t.suppressesGestures)
    }

    @Test fun keyDuringContact_isSuppressed_evenIfBothWindowsAreClear() {
        guard.onKeyEvent(0)
        guard.onContactStart(5_000)
        guard.onKeyEvent(5_100) // typing continues under the finger
        val t = guard.keyTiming(5_600) // 500 ms after that key: past both windows
        assertTrue(t.keyDuringContact)
        assertFalse(t.startedInNoiseWindow)
        assertFalse(t.insideSuppressionWindow)
        assertTrue(t.suppressesGestures)
    }

    @Test fun keyDuringContact_resetsOnNextContact() {
        guard.onContactStart(5_000)
        guard.onKeyEvent(5_100)
        guard.onContactEnd()
        guard.onContactStart(9_000)
        assertFalse(guard.keyTiming(9_400).keyDuringContact)
    }

    @Test fun keyAfterContactEnded_doesNotCountAsDuringContact() {
        guard.onContactStart(5_000)
        guard.onContactEnd()
        guard.onKeyEvent(5_100)
        guard.onContactStart(9_000)
        assertFalse(guard.keyTiming(9_400).keyDuringContact)
    }

    @Test fun suppressionAnchor_followsTheUsersChoice() {
        guard.onKeyEvent(1_000)
        guard.onContactStart(1_200) // 200 ms: clear of noise, inside suppression if anchored at start
        // Measured from the end (default): a gesture that ends 400 ms after the key is clear.
        measureFromEnd = true
        assertFalse(guard.keyTiming(1_400).insideSuppressionWindow)
        // Measured from the start: the same gesture is inside the window.
        measureFromEnd = false
        assertTrue(guard.keyTiming(1_400).insideSuppressionWindow)
    }

    @Test fun thresholdsAreReadLive() {
        var suppression = 350L
        val live = KeyTypingGuard({ 150L }, { suppression }, { true })
        live.onKeyEvent(1_000)
        live.onContactStart(1_200)
        assertTrue(live.keyTiming(1_300).insideSuppressionWindow)
        suppression = 100L
        assertFalse(live.keyTiming(1_300).insideSuppressionWindow)
    }

    @Test fun lastKeyEventTime_isExposedForDiagnostics() {
        assertEquals(KeyTypingGuard.NO_KEY, guard.lastKeyEventTime)
        guard.onKeyEvent(42)
        assertEquals(42L, guard.lastKeyEventTime)
    }
}

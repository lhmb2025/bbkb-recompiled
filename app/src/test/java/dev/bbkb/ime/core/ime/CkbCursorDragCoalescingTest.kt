package dev.bbkb.ime.core.ime

import android.os.Looper
import android.view.MotionEvent
import dev.bbkb.ime.core.BlackBerryIME
import dev.bbkb.ime.core.settings.util.SettingsManager
import dev.bbkb.ime.core.settings.util.SettingsValues
import dev.bbkb.ime.core.textinput.InputLogic
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Pins that the keypad cursor drag queues steps and moves once per frame, flushing the queue first
 * on a new direction or axis, a key, finger lift or new contact, so the cursor lands where
 * step-by-step moves would have put it. Also pins the drag with a second finger resting on the
 * keypad (holding Shift), and the Shift selection swipe outside cursor mode.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class CkbCursorDragCoalescingTest {

    private lateinit var ime: BlackBerryIME
    private lateinit var bridge: CkbGestureBridge
    private val events = mutableListOf<MotionEvent>()
    private var t = 1000L

    @Before
    fun setUp() {
        // The code under test calls the engine from the main thread; run engine ops inline so
        // the debug thread assertions pass.
        dev.bbkb.ime.core.engine.EngineThreads.setInlineForTests(true)
        val sv = allocate(SettingsValues::class.java)
        // One step per 10 px, no speed multiplier: each 10 px sample below is exactly one step.
        set(sv, "horizontalScrollDistanceForCursorMove", 10)
        set(sv, "verticalScrollDistanceForCursorMove", 10)
        set(sv, "singleLineVerticalScrollForCursorMove", 10)
        set(sv, "maxCursorMoveSpeedMultiplier", 1)
        set(sv, "velocityForMaxCursorMoveSpeed", 1000)
        val settings = Mockito.mock(SettingsManager::class.java)
        Mockito.`when`(settings.getSettingsValues()).thenReturn(sv)
        ime = Mockito.mock(BlackBerryIME::class.java)
        Mockito.`when`(ime.getSettingsManager()).thenReturn(settings)
        // onContactStart snapshots the strip's suggestions; a bare InputLogic has none.
        Mockito.`when`(ime.getInputLogic()).thenReturn(Mockito.mock(InputLogic::class.java))
        bridge = CkbGestureBridge(ime)
    }

    @After
    fun tearDown() {
        events.forEach { it.recycle() }
        dev.bbkb.ime.core.engine.EngineThreads.setInlineForTests(false)
    }

    private fun <T> allocate(cls: Class<T>): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        @Suppress("UNCHECKED_CAST")
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, cls) as T
    }

    private fun set(target: Any, name: String, value: Int) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.setInt(target, value)
    }

    private fun event(action: Int, x: Float, y: Float = 100f): MotionEvent {
        t += 8
        return MotionEvent.obtain(1000L, t, action, x, y, 0).also { events += it }
    }

    private fun down(x: Float, y: Float = 100f) {
        val e = event(MotionEvent.ACTION_DOWN, x, y)
        bridge.onContactStart(e.eventTime)
        bridge.handleCursorModeDrag(e)
    }

    private fun move(x: Float, y: Float = 100f) = bridge.handleCursorModeDrag(event(MotionEvent.ACTION_MOVE, x, y))

    private fun up(x: Float) = bridge.handleCursorModeDrag(event(MotionEvent.ACTION_UP, x))

    private fun nextFrame() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))

    @Test
    fun theStepsOfOneFrame_areOneMove() {
        down(200f)
        move(190f)
        move(180f)
        move(170f)
        verify(ime, never()).moveLeft(anyInt())
        nextFrame()
        verify(ime, times(1)).moveLeft(3)
        verify(ime, never()).moveRight(anyInt())
    }

    @Test
    fun aReversal_movesWhatWasQueuedFirst() {
        // Separate moves clamp at the ends of the text; summing across a reversal would not.
        down(200f)
        move(190f)
        move(180f)
        move(190f)
        nextFrame()
        val order = inOrder(ime)
        order.verify(ime).moveLeft(2)
        order.verify(ime).moveRight(1)
    }

    @Test
    fun anAxisChange_movesWhatWasQueuedFirst() {
        down(200f, 100f)
        move(190f, 100f)
        move(190f, 90f)
        nextFrame()
        val order = inOrder(ime)
        order.verify(ime).moveLeft(1)
        order.verify(ime).moveUp(1)
    }

    @Test
    fun liftingTheFinger_movesAtOnce() {
        down(200f)
        move(190f)
        move(180f)
        up(180f)
        verify(ime).moveLeft(2)
        verify(ime, never()).toggleCursorMode()
        nextFrame()
        verify(ime, times(1)).moveLeft(anyInt())
    }

    @Test
    fun aKey_landsAfterTheQueuedSteps() {
        down(200f)
        move(190f)
        bridge.onHardwareKey(t)
        verify(ime).moveLeft(1)
    }

    /** One sample of several fingers, each an (x, y) pair in pointer order; ids are 0, 1, ... */
    private fun fingers(action: Int, vararg points: Pair<Float, Float>): MotionEvent {
        t += 8
        val props = Array(points.size) { i ->
            MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER }
        }
        val coords = Array(points.size) { i ->
            MotionEvent.PointerCoords().apply { x = points[i].first; y = points[i].second; pressure = 1f; size = 1f }
        }
        return MotionEvent.obtain(1000L, t, action, points.size, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
            .also { events += it }
    }

    private fun pointerAction(action: Int, index: Int) = action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    /** A finger resting on Shift at (900, 480), then a second finger landing at ([x], 100). */
    private fun holdShiftThenTouch(x: Float) {
        down(900f, 480f)
        val second = fingers(pointerAction(MotionEvent.ACTION_POINTER_DOWN, 1), 900f to 480f, x to 100f)
        bridge.onContactStart(second.eventTime)
        bridge.handleCursorModeDrag(second)
    }

    private fun moveSecond(x: Float) =
        bridge.handleCursorModeDrag(fingers(MotionEvent.ACTION_MOVE, 900f to 480f, x to 100f))

    @Test
    fun withAFingerHoldingShift_theOtherFingerMovesTheCursor() {
        holdShiftThenTouch(200f)
        moveSecond(190f)
        moveSecond(180f)
        moveSecond(170f)
        nextFrame()
        verify(ime, times(1)).moveLeft(3)
        verify(ime, never()).moveRight(anyInt())
    }

    @Test
    fun aFingerLandingOrLifting_doesNotMoveTheCursor() {
        holdShiftThenTouch(200f)
        moveSecond(190f)
        // The swiping finger lifts and lands again far away: only its travel counts.
        bridge.handleCursorModeDrag(fingers(pointerAction(MotionEvent.ACTION_POINTER_UP, 1), 900f to 480f, 190f to 100f))
        move(900f, 480f)
        holdShiftThenTouch(600f)
        moveSecond(590f)
        nextFrame()
        verify(ime, times(2)).moveLeft(1)
        verify(ime, never()).moveRight(anyInt())
        verify(ime, never()).moveUp(anyInt())
        verify(ime, never()).moveDown(anyInt())
    }

    @Test
    fun theFirstFingerLifting_leavesTheOtherDragging() {
        holdShiftThenTouch(200f)
        moveSecond(190f)
        // Shift's finger (pointer 0) lifts first; the swiping finger carries on alone.
        bridge.handleCursorModeDrag(fingers(pointerAction(MotionEvent.ACTION_POINTER_UP, 0), 900f to 480f, 190f to 100f))
        move(180f)
        nextFrame()
        verify(ime, times(1)).moveLeft(2)
        verify(ime, never()).moveRight(anyInt())
        verify(ime, never()).moveUp(anyInt())
        verify(ime, never()).moveDown(anyInt())
    }

    @Test
    fun aSwipeUnderAHeldFinger_doesNotLeaveCursorMode() {
        holdShiftThenTouch(200f)
        moveSecond(190f)
        moveSecond(180f)
        bridge.handleCursorModeDrag(fingers(pointerAction(MotionEvent.ACTION_POINTER_UP, 1), 900f to 480f, 180f to 100f))
        bridge.handleCursorModeDrag(event(MotionEvent.ACTION_UP, 900f, 480f))
        verify(ime).moveLeft(2)
        verify(ime, never()).toggleCursorMode()
    }

    // Shift held outside cursor mode: the same drag, behind the tap slop (30 px of finger travel).

    private fun shiftDown(x: Float, y: Float) {
        val e = event(MotionEvent.ACTION_DOWN, x, y)
        bridge.onContactStart(e.eventTime)
        bridge.handleShiftSelectionDrag(e)
    }

    /** A finger on Shift at (900, 480), then a second finger landing at ([x], 100). */
    private fun shiftHeldThenTouch(x: Float, alreadyDown: Boolean = false) {
        if (!alreadyDown) shiftDown(900f, 480f)
        val second = fingers(pointerAction(MotionEvent.ACTION_POINTER_DOWN, 1), 900f to 480f, x to 100f)
        bridge.onContactStart(second.eventTime)
        bridge.handleShiftSelectionDrag(second)
    }

    private fun shiftMoveSecond(x: Float): MotionEvent =
        fingers(MotionEvent.ACTION_MOVE, 900f to 480f, x to 100f).also { bridge.handleShiftSelectionDrag(it) }

    private fun shiftLiftSecond(x: Float) = bridge.handleShiftSelectionDrag(
        fingers(pointerAction(MotionEvent.ACTION_POINTER_UP, 1), 900f to 480f, x to 100f))

    @Test
    fun aShiftSwipe_movesOncePastTheSlop_andCarriesTheTravelSoFar() {
        shiftHeldThenTouch(200f)
        val within = shiftMoveSecond(180f)
        nextFrame()
        verify(ime, never()).moveLeft(anyInt())
        assertFalse(bridge.feed(within))
        verify(ime, never()).onShiftSelectionSwipeStarted()
        val beyond = shiftMoveSecond(160f)
        // Shift starts selecting before the first step is sent.
        verify(ime).onShiftSelectionSwipeStarted()
        nextFrame()
        verify(ime, times(1)).moveLeft(4)
        // The contact is taken: no swipe typing, and the arbiter must not read it as a flick.
        assertTrue(bridge.feed(beyond))
    }

    @Test
    fun aSwipeTypingTraceUnderWayWhenShiftGoesDown_isLeftAlone() {
        Mockito.`when`(ime.isKeypadSwipeTypingUnderWay()).thenReturn(true)
        // Picked up mid-contact: the first sample the Shift swipe sees is a move.
        bridge.handleShiftSelectionDrag(event(MotionEvent.ACTION_MOVE, 300f))
        val later = event(MotionEvent.ACTION_MOVE, 200f)
        bridge.handleShiftSelectionDrag(later)
        nextFrame()
        verify(ime, never()).onShiftSelectionSwipeStarted()
        verify(ime, never()).moveLeft(anyInt())
        assertFalse(bridge.feed(later))
    }

    @Test
    fun aShiftTap_neitherMovesNorTogglesCursorMode() {
        shiftDown(200f, 100f)
        bridge.handleShiftSelectionDrag(event(MotionEvent.ACTION_MOVE, 205f))
        bridge.handleShiftSelectionDrag(event(MotionEvent.ACTION_UP, 205f))
        nextFrame()
        verify(ime, never()).toggleCursorMode()
        verify(ime, never()).moveLeft(anyInt())
        verify(ime, never()).moveRight(anyInt())
    }

    @Test
    fun underShift_eachLandingFingerMustPassTheSlopAgain() {
        shiftHeldThenTouch(200f)
        shiftMoveSecond(160f)
        shiftLiftSecond(160f)
        // A finger landing to press a key wobbles 20 px: the selection stays put.
        shiftHeldThenTouch(500f, alreadyDown = true)
        shiftMoveSecond(520f)
        shiftLiftSecond(520f)
        nextFrame()
        verify(ime, times(1)).moveLeft(4)
        verify(ime, never()).moveRight(anyInt())
    }

    @Test
    fun aShiftSwipePickedUpAgain_startsAfresh() {
        shiftHeldThenTouch(200f)
        shiftMoveSecond(160f)
        // Shift is let go mid-contact, then pressed again with the finger elsewhere.
        bridge.endDrag()
        verify(ime).moveLeft(4)
        shiftMoveSecond(700f)
        shiftMoveSecond(705f)
        nextFrame()
        verify(ime, times(1)).moveLeft(anyInt())
        verify(ime, never()).moveRight(anyInt())
    }

    @Test
    fun theEngineIsCancelledOncePerContact() {
        val first = event(MotionEvent.ACTION_DOWN, 200f)
        bridge.onContactStart(first.eventTime)
        assertTrue(bridge.cancelEngineTrackingOncePerContact(first))
        assertFalse(bridge.cancelEngineTrackingOncePerContact(event(MotionEvent.ACTION_MOVE, 190f)))
        assertFalse(bridge.cancelEngineTrackingOncePerContact(event(MotionEvent.ACTION_MOVE, 180f)))
        val second = event(MotionEvent.ACTION_DOWN, 200f)
        bridge.onContactStart(second.eventTime)
        assertTrue(bridge.cancelEngineTrackingOncePerContact(second))
    }
}

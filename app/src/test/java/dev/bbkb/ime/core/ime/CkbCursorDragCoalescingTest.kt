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
 * P-17: the keypad cursor drag queues its steps and moves once per frame, and a sample that
 * starts a new direction or axis, a key, the finger lifting or a new contact first moves what is
 * queued, so the cursor lands where the step-by-step moves would have put it.
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

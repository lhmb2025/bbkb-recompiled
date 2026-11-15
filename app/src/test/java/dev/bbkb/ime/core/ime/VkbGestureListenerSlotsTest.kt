package dev.bbkb.ime.core.ime

import android.view.MotionEvent
import dev.bbkb.ime.core.BlackBerryIME
import dev.bbkb.ime.core.gesture.arbiter.GestureAction
import dev.bbkb.ime.core.gesture.arbiter.VkbGestureAssignments
import dev.bbkb.ime.core.settings.util.SettingsValues
import dev.bbkb.ime.keyboard.KeyboardSwitcher
import dev.bbkb.ime.keyboard.MainKeyboardView
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [VkbGestureListener] runs the slot for the swipe's direction under the main switch; a None slot
 * or the switch off lets the stroke through; swipe up stays the prediction flick; an open slideboard
 * closes first. Thresholds are set loose so only direction matters here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.blackberry.nuanceshim"])
class VkbGestureListenerSlotsTest {

    private lateinit var ime: BlackBerryIME
    private lateinit var keyboardView: MainKeyboardView

    @Before
    fun setUp() {
        ime = mock(BlackBerryIME::class.java)
        keyboardView = mock(MainKeyboardView::class.java)
        val switcher = mock(KeyboardSwitcher::class.java)
        `when`(ime.getKeyboardSwitcher()).thenReturn(switcher)
        `when`(switcher.getMainKeyboardView()).thenReturn(keyboardView)
        `when`(keyboardView.translationX).thenReturn(0f)
        `when`(ime.isInputViewShown).thenReturn(true)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyArg(): T = org.mockito.ArgumentMatchers.any<T>() as T
    private fun <T> eqArg(value: T): T = org.mockito.ArgumentMatchers.eq(value) ?: value

    private fun settings(enabled: Boolean, slots: VkbGestureAssignments): SettingsValues {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val sv = unsafeClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, SettingsValues::class.java) as SettingsValues
        fun set(name: String, value: Any) =
            SettingsValues::class.java.getDeclaredField(name).apply { isAccessible = true }.set(sv, value)
        set("isVkbSwipeGesturesEnabled", enabled)
        set("vkbGestureAssignments", slots)
        set("horizontalSwipeTanTheta", 0.5)
        set("verticalSwipeTanTheta", 0.5)
        for (f in listOf("fastHorizontalSwipeMinX", "slowHorizontalSwipeMinX", "fastVerticalSwipeMinY", "slowVerticalSwipeMinY")) set(f, 100)
        for (f in listOf("fastHorizontalSwipeMinVelocity", "slowHorizontalSwipeMinVelocity", "fastVerticalSwipeMinVelocity")) set(f, 500)
        set("swipeGestureTimeout", 1000)
        return sv
    }

    /** A 300 px fling from the middle of the keyboard in 100 ms. */
    private fun fling(listener: VkbGestureListener, dx: Float, dy: Float): Boolean {
        val down = MotionEvent.obtain(1000L, 1000L, MotionEvent.ACTION_DOWN, 500f, 500f, 0)
        val up = MotionEvent.obtain(1000L, 1100L, MotionEvent.ACTION_UP, 500f + dx, 500f + dy, 0)
        return listener.onFling(down, up, dx * 10f, dy * 10f)
    }

    private fun listener(enabled: Boolean = true, slots: VkbGestureAssignments = VkbGestureAssignments()) =
        VkbGestureListener(ime, settings(enabled, slots))

    @Test
    fun eachDirection_runsItsSlot() {
        val slots = VkbGestureAssignments(GestureAction.UNDO, GestureAction.EMOJI, GestureAction.CYCLE_SYMBOLS)
        val l = listener(slots = slots)
        assertTrue(fling(l, -300f, 0f))
        verify(ime).handleVkbSwipeAction(eqArg(GestureAction.UNDO), anyArg(), anyArg())
        assertTrue(fling(l, 300f, 0f))
        verify(ime).handleVkbSwipeAction(eqArg(GestureAction.EMOJI), anyArg(), anyArg())
        assertTrue(fling(l, 0f, 300f))
        verify(ime).handleVkbSwipeAction(eqArg(GestureAction.CYCLE_SYMBOLS), anyArg(), anyArg())
    }

    @Test
    fun noneSlot_letsTheStrokeThrough() {
        // Default right slot is None.
        assertFalse(fling(listener(), 300f, 0f))
        verify(ime, never()).handleVkbSwipeAction(anyArg(), anyArg(), anyArg())
    }

    @Test
    fun mainSwitchOff_runsNoSlot_butTheUpFlickStillPicks() {
        val l = listener(enabled = false)
        assertFalse(fling(l, -300f, 0f))
        assertFalse(fling(l, 0f, 300f))
        verify(ime, never()).handleVkbSwipeAction(anyArg(), anyArg(), anyArg())
        assertTrue(fling(l, 0f, -300f))
        verify(ime).handlePredictionFlick(anyArg())
    }

    @Test
    fun rtl_mirrorsLeftAndRight() {
        `when`(ime.isCurrentLanguageRtl()).thenReturn(true)
        val l = listener(slots = VkbGestureAssignments(GestureAction.DELETE_WORD, GestureAction.EMOJI))
        assertTrue(fling(l, 300f, 0f))
        verify(ime).handleVkbSwipeAction(eqArg(GestureAction.DELETE_WORD), anyArg(), anyArg())
    }

    @Test
    fun openSlideboard_closesBeforeAnySlot() {
        `when`(keyboardView.translationX).thenReturn(50f)
        assertTrue(fling(listener(), -300f, 0f))
        verify(keyboardView).setSlideTranslation(anyFloat(), org.mockito.ArgumentMatchers.eq(true))
        verify(ime, never()).handleVkbSwipeAction(anyArg(), anyArg(), anyArg())
    }
}

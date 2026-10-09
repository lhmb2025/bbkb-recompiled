package dev.bbkb.ime.core.device.touch

import android.app.Activity
import android.app.Dialog
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.device.config.model.TouchKeypadConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * [NativeTouchSource]: attaches to the IME window's decor view only when the selector gives it the
 * pad, undoes everything on detach, dedupes the two delivery routes, and does nothing at all on a
 * device without such a profile — the KEY2, the MP01, the emulators.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class NativeTouchSourceTest {

    private val titan2Pad = TouchKeypadConfig().apply { nativeMinSdk = 36; rangeX = 1440; rangeY = 720 }

    private class FakeHost(var decor: View?, var selection: TouchSourceSelector.Selection) :
        NativeTouchSource.Host {
        val delivered = mutableListOf<MotionEvent>()
        var decorAsked = 0
        var answer = true
        override fun decorView(): View? { decorAsked++; return decor }
        override fun onGenericMotionEvent(event: MotionEvent): Boolean { delivered += event; return answer }
        override fun selection() = selection
    }

    @After
    fun tearDown() {
        KeypadTouchSources.publishNativeStatus(null)
    }

    private fun native() = TouchSourceSelector.select(titan2Pad, 36, false)
    private fun none() = TouchSourceSelector.select(null, 36, true)

    private fun padEvent(action: Int, time: Long, deviceId: Int = 7): MotionEvent =
        MotionEvent.obtain(time, time, action, 100f, 50f, 1f, 0.1f, 0, 1f, 1f, deviceId, 0).also {
            it.source = InputDevice.SOURCE_TOUCHPAD
        }

    private fun decorView() = FrameLayout(ApplicationProvider.getApplicationContext())

    // ── no profile, no change ────────────────────────────────────────────────

    @Test
    fun withoutADeclaredPad_theWindowIsNeverTouched() {
        val decor = decorView()
        val focusable = decor.isFocusable
        val focusableInTouchMode = decor.isFocusableInTouchMode
        val host = FakeHost(decor, none())
        val source = NativeTouchSource(host)

        source.attach()
        source.attach()
        source.detach()

        assertEquals("the decor view is not even asked for", 0, host.decorAsked)
        assertFalse(source.isAttached)
        assertEquals(focusable, decor.isFocusable)
        assertEquals(focusableInTouchMode, decor.isFocusableInTouchMode)
        assertFalse(decor.isFocused)
        assertEquals(TouchSourceStatus.State.NOT_APPLICABLE, source.status().state)
        assertEquals(TouchSourceStatus.Reason.NO_TOUCH_KEYPAD_DECLARED, source.status().reason)
    }

    @Test
    fun withoutADeclaredPad_theDedupeIsANoOp() {
        val source = NativeTouchSource(FakeHost(decorView(), none()))
        source.attach()
        val down = padEvent(MotionEvent.ACTION_DOWN, 1000L)
        source.recordResult(down, true)
        assertNull("same event twice is passed through untouched", source.duplicateResult(down))
        assertNull(source.duplicateResult(down))
    }

    @Test
    fun aShizukuPinnedProfile_keepsTheNativeSourceOff() {
        val pinned = TouchKeypadConfig().apply {
            nativeMinSdk = 36
            source = TouchKeypadConfig.SourcePreference.SHIZUKU
        }
        val host = FakeHost(decorView(), TouchSourceSelector.select(pinned, 36, true))
        val source = NativeTouchSource(host)
        source.attach()
        assertFalse(source.isAttached)
        assertEquals(0, host.decorAsked)
        assertEquals(TouchSourceStatus.Reason.PROFILE_PINS_SHIZUKU, source.status().reason)
    }

    // ── attach / detach ──────────────────────────────────────────────────────

    @Test
    fun attach_focusesTheDecorAndForwardsGenericMotion_detachUndoesIt() {
        val decor = decorView()
        val host = FakeHost(decor, native())
        val source = NativeTouchSource(host)
        val statuses = mutableListOf<TouchSourceStatus>()
        source.addStatusListener { _, s -> statuses += s }

        source.attach()
        assertTrue(source.isAttached)
        assertTrue(decor.isFocusable)
        assertTrue(decor.isFocusableInTouchMode)
        assertTrue(decor.isFocused)
        assertEquals(TouchSourceStatus.State.ACTIVE, source.status().state)
        assertEquals(TouchSourceStatus.State.ACTIVE, KeypadTouchSources.nativeStatus().state)

        val ev = padEvent(MotionEvent.ACTION_DOWN, 1000L)
        assertTrue("the listener forwards into the IME entry", decor.dispatchGenericMotionEvent(ev))
        assertSame(ev, host.delivered.single())

        source.detach()
        assertFalse(source.isAttached)
        assertFalse(decor.isFocused)
        assertFalse(decor.isFocusable)
        assertFalse(decor.isFocusableInTouchMode)
        host.answer = false
        decor.dispatchGenericMotionEvent(padEvent(MotionEvent.ACTION_DOWN, 2000L))
        assertEquals("no listener after detach", 1, host.delivered.size)
        assertEquals(TouchSourceStatus.State.IDLE, source.status().state)
        assertEquals(listOf(TouchSourceStatus.State.ACTIVE, TouchSourceStatus.State.IDLE),
            statuses.map { it.state })
    }

    @Test
    fun attachWithNoWindowYet_isIdle_thenAttachesLater() {
        val host = FakeHost(null, native())
        val source = NativeTouchSource(host)
        source.attach()
        assertFalse(source.isAttached)
        assertEquals(TouchSourceStatus.State.IDLE, source.status().state)
        host.decor = decorView()
        source.attach()
        assertTrue(source.isAttached)
    }

    @Test
    fun stop_detachesAndStaysOff() {
        val decor = decorView()
        val source = NativeTouchSource(FakeHost(decor, native()))
        source.attach()
        source.stop()
        assertFalse(source.isAttached)
        source.attach()
        assertFalse("a stopped source does not re-attach", source.isAttached)
        assertEquals(TouchSourceStatus.Reason.STOPPED, source.status().reason)
        source.start()
        source.attach()
        assertTrue(source.isAttached)
    }

    // ── dedupe ───────────────────────────────────────────────────────────────

    @Test
    fun attached_theSecondArrivalOfAnEventGetsTheFirstAnswer() {
        val source = NativeTouchSource(FakeHost(decorView(), native()))
        source.attach()
        val t = SystemClock.uptimeMillis()
        val down = padEvent(MotionEvent.ACTION_DOWN, t)
        val move = padEvent(MotionEvent.ACTION_MOVE, t + 11)

        assertNull("first sighting", source.duplicateResult(down))
        source.recordResult(down, true)
        assertNull(source.duplicateResult(move))
        source.recordResult(move, false)

        // The other route delivers the same two events (copies: same device, action, time).
        assertEquals(true, source.duplicateResult(MotionEvent.obtain(down)))
        assertEquals(false, source.duplicateResult(MotionEvent.obtain(move)))
        // A different device, action or time is a different event.
        assertNull(source.duplicateResult(padEvent(MotionEvent.ACTION_DOWN, t, deviceId = 8)))
        assertNull(source.duplicateResult(padEvent(MotionEvent.ACTION_UP, t)))
        assertNull(source.duplicateResult(padEvent(MotionEvent.ACTION_DOWN, t + 1)))

        source.detach()
        assertNull("forgotten on detach", source.duplicateResult(down))
    }

    // ── focus stays with the editor ──────────────────────────────────────────

    /**
     * The decor view is focused inside the IME's own window; the editor's window keeps its
     * focused view, which is what the IME's InputConnection is bound to. A Dialog stands in for
     * the IME window here. This is the JVM half of the check; typing into the editor with the
     * source attached on a Titan 2 is the other half.
     */
    @Test
    fun focusingTheImeDecor_leavesTheEditorFocused() {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        val activity = controller.get()
        val editor = EditText(activity)
        activity.setContentView(editor)
        controller.start().resume().visible()
        ShadowLooper.idleMainLooper()
        assertTrue(editor.requestFocus())
        ShadowLooper.idleMainLooper()
        // The editor window's focused view, read from its view tree (Robolectric's Activity
        // shadow keeps its own getCurrentFocus() field, which no view focus updates).
        val editorWindow = activity.window.decorView
        assertSame(editor, editorWindow.findFocus())

        val imeWindow = Dialog(activity)
        imeWindow.setContentView(FrameLayout(activity))
        imeWindow.show()
        ShadowLooper.idleMainLooper()
        val decor = imeWindow.window!!.decorView
        assertTrue("a separate window", decor.rootView !== editorWindow.rootView)

        val source = NativeTouchSource(FakeHost(decor, native()))
        source.attach()
        ShadowLooper.idleMainLooper()

        assertTrue(source.isAttached)
        assertTrue("the decor holds focus inside its own window", decor.hasFocus())
        assertSame("the editor's window still serves the editor", editor, editorWindow.findFocus())
        assertTrue(editor.isFocused)

        source.detach()
        assertSame(editor, editorWindow.findFocus())
        assertTrue(editor.isFocused)
        imeWindow.dismiss()
    }
}

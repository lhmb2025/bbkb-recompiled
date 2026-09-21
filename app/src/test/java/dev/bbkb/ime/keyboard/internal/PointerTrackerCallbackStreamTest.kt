package dev.bbkb.ime.keyboard.internal

import android.content.Context
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.engine.NuanceSDKManager
import dev.bbkb.ime.core.keyevent.InputSource
import dev.bbkb.ime.core.settings.util.SettingsManager
import dev.bbkb.ime.core.settings.util.SettingsValues
import dev.bbkb.ime.core.shared.Logger
import dev.bbkb.ime.keyboard.Key
import dev.bbkb.ime.keyboard.Keyboard
import dev.bbkb.ime.keyboard.KeyboardActionListenerInterface
import dev.bbkb.ime.R
import com.blackberry.nuanceshim.NuanceSDK
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.MockedStatic
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Characterises the VKB typing path through [PointerTracker]: synthetic down/move/up/cancel
 * sequences are driven exactly the way `MainKeyboardView.processMotionEvent` drives them, and the
 * complete, ordered stream of everything the tracker tells its collaborators is pinned — the
 * keyboard action listener (onPressKey / onCodeInput / onReleaseKey / onFinishSlidingInput /
 * onCancelInput / batch callbacks), the key timers, the batch-update timer, the drawing proxy and
 * the Nuance engine touch calls.
 *
 * The streams were recorded against the code as it stood before any Wave 3b change; they are the
 * contract, not a description of what the code "should" do.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.blackberry.nuanceshim"])
class PointerTrackerCallbackStreamTest {

    @get:Rule
    val testName = TestName()

    private val log = mutableListOf<String>()

    private lateinit var settingsStatic: MockedStatic<SettingsManager>
    private lateinit var sdkStatic: MockedStatic<NuanceSDKManager>
    private lateinit var detector: KeyDetector
    private var gestureEnabled = false
    private val t0 = 1_000L

    // ---- layout: one row, keys 100px wide, 100px tall ------------------------------------------

    private class Spec(val key: Key, val x0: Int, val x1: Int)

    private val specs = mutableListOf<Spec>()

    private fun key(
        code: Int, x0: Int, modifier: Boolean = false, shift: Boolean = false,
        repeatable: Boolean = false, moreKeys: Boolean = false,
    ): Key {
        val k = mock(Key::class.java)
        `when`(k.code).thenReturn(code)
        `when`(k.isModifierKey).thenReturn(modifier)
        `when`(k.isShiftKey).thenReturn(shift)
        `when`(k.isActive).thenReturn(true)
        `when`(k.isRepeatable).thenReturn(repeatable)
        `when`(k.hasMoreKeys()).thenReturn(moreKeys)
        `when`(k.toString()).thenReturn("key($code)")
        specs += Spec(k, x0, x0 + 100)
        return k
    }

    private val keyA by lazy { key('a'.code, 0) }
    private val keyB by lazy { key('b'.code, 100) }
    private val keyC by lazy { key('c'.code, 200) }
    private val keyShift by lazy { key(-1, 300, modifier = true, shift = true) }
    private val keyDelete by lazy { key(-5, 400, repeatable = true) }
    private val keyD by lazy { key('d'.code, 500, moreKeys = true) }

    private fun code(k: Key?) = k?.code?.toString() ?: "null"

    // ---- collaborators ------------------------------------------------------------------------

    private val timers = object : TimerProxy {
        override fun startTypingStateTimer(key: Key) { log += "timer.startTypingState(${code(key)})" }
        override fun cancelKeyRepeatTimer(t: PointerTracker) { log += "timer.cancelKeyRepeat(${t.mPointerId})" }
        override fun startLongPressTimer(t: PointerTracker, i: Int) { log += "timer.startLongPress(${t.mPointerId},$i)" }
        override fun startKeyRepeatTimer(t: PointerTracker, i: Int, i2: Int) { log += "timer.startKeyRepeat(${t.mPointerId},$i,$i2)" }
        override fun cancelLongPressTimers(t: PointerTracker) { log += "timer.cancelLongPress(${t.mPointerId})" }
        override fun startLockFocusTimer(t: PointerTracker, i: Int) { log += "timer.startLockFocus(${t.mPointerId},$i)" }
        override fun cancelLongPressShiftKeyTimer() { log += "timer.cancelLongPressShift" }
        override fun cancelKeyTimers(t: PointerTracker) { log += "timer.cancelKeyTimers(${t.mPointerId})" }
        override fun isTypingState() = false
    }

    private val batchTimers = object : TimerCallback {
        override fun cancelAllUpdateBatchInputTimers() { log += "batch.cancelAll" }
        override fun startUpdateBatchInputTimer(h: AbstractDrawingHandler) { log += "batch.start(${h.mPointerId})" }
        override fun cancelUpdateBatchInputTimer(h: AbstractDrawingHandler) { log += "batch.cancel(${h.mPointerId})" }
    }

    private val drawing = object : PointerTracker.KeyDrawingProxy {
        override fun showGestureTrail(t: PointerTracker) { log += "draw.showTrail(${t.mPointerId})" }
        override fun invalidateKeyGraphics(key: Key) { log += "draw.invalidate(${code(key)})" }
        override fun showSlidingKeyInputPreview(t: PointerTracker) { log += "draw.slidingPreview(${t.mPointerId})" }
        override fun showKeyPreview(key: Key) { log += "draw.showPreview(${code(key)})" }
        override fun dismissKeyPreview(key: Key?) { log += "draw.dismissPreview(${code(key)})" }
        override fun dismissGestureTrail() { log += "draw.dismissTrail" }
    }

    private val sliding = object : PointerTracker.SlidingPanel {
        override fun setSlideTranslation(f: Float, z: Boolean) { log += "slide.set($f,$z)" }
        override fun getTranslationX() = 0f
        override fun onSlidingFinished() { log += "slide.finished" }
    }

    private val listener = object : KeyboardActionListenerInterface {
        override fun onMoreKeysKeyTyped() { log += "L.onMoreKeysKeyTyped" }
        override fun onCodeInput(i: Int, i2: Int, i3: Int, j: Long, z: Boolean) { log += "L.onCodeInput($i,$i2,$i3,$j,$z)" }
        override fun onReleaseKey(i: Int, z: Boolean) { log += "L.onReleaseKey($i,$z)" }
        override fun onTextInput(str: String?, j: Long) { log += "L.onTextInput($str,$j)" }
        override fun onPressKey(i: Int, i2: Int, z: Boolean) { log += "L.onPressKey($i,$i2,$z)" }
        override fun onEndBatchInput(e: InputSource?) { log += "L.onEndBatchInput($e)" }
        override fun onCustomRequest(i: Int): Boolean { log += "L.onCustomRequest($i)"; return false }
        override fun onStartBatchInput() { log += "L.onStartBatchInput" }
        override fun onUpdateBatchInput() { log += "L.onUpdateBatchInput" }
        override fun onCancelBatchInput() { log += "L.onCancelBatchInput" }
        override fun onFinishSlidingInput() { log += "L.onFinishSlidingInput" }
        override fun onCancelInput() { log += "L.onCancelInput" }
    }

    // ---- fixtures -----------------------------------------------------------------------------

    private fun unsafeAllocate(c: Class<*>): Any {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(theUnsafe, c)
    }

    private fun setField(target: Any, owner: Class<*>, name: String, value: Any?) {
        owner.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    private fun settings(): SettingsValues {
        val sv = unsafeAllocate(SettingsValues::class.java) as SettingsValues
        setField(sv, SettingsValues::class.java, "isSlideboardEnabled", false)
        setField(sv, SettingsValues::class.java, "keyLongpressTimeoutMs", 400)
        setField(sv, SettingsValues::class.java, "slideboardKeyLongpressTimeout", 300)
        setField(sv, SettingsValues::class.java, "isVkbGestureInputEnabled", false)
        val capsType = SettingsValues::class.java.getDeclaredField("editorCapabilities").type
        val caps = unsafeAllocate(capsType)
        setField(caps, capsType, "shouldSupportGestureInput", true)
        setField(sv, SettingsValues::class.java, "editorCapabilities", caps)
        return sv
    }

    private fun keyboard(): Keyboard {
        val kb = mock(Keyboard::class.java)
        setField(kb, Keyboard::class.java, "mShiftKeys", listOf(keyShift))
        setField(kb, Keyboard::class.java, "mAltCodeKeysWhileTyping", emptyList<Key>())
        setField(kb, Keyboard::class.java, "mMostCommonKeyWidth", 100)
        setField(kb, Keyboard::class.java, "mOccupiedHeight", 100)
        val id = mock(KeyboardId::class.java)
        `when`(id.isAlphabetKeyboard).thenReturn(true)
        setField(kb, Keyboard::class.java, "mId", id)
        `when`(kb.allowsGestureForCode(anyInt())).thenAnswer { Character.isLetter(it.arguments[0] as Int) }
        return kb
    }

    private fun styledAttributes(context: Context) = context.obtainStyledAttributes(
        null, R.styleable.MainKeyboardView, R.attr.mainKeyboardViewStyle, R.style.MainKeyboardView
    )

    @Before
    fun setUp() {
        resetStatics()
        val sm = mock(SettingsManager::class.java)
        val sv = settings()
        `when`(sm.settingsValues).thenReturn(sv)
        settingsStatic = mockStatic(SettingsManager::class.java)
        settingsStatic.`when`<SettingsManager> { SettingsManager.getInstance() }.thenReturn(sm)

        val nuance = mock(NuanceSDK::class.java)
        doAnswer { log += "sdk.touchStart(${it.arguments[0]},${it.arguments[1]},${it.arguments[2]},${it.arguments[3]})"; false }
            .`when`(nuance).touchStart(anyLong(), anyFloat(), anyFloat(), anyLong())
        doAnswer { log += "sdk.touchMove(${it.arguments[0]},${it.arguments[1]},${it.arguments[2]},${it.arguments[3]})"; false }
            .`when`(nuance).touchMove(anyLong(), anyFloat(), anyFloat(), anyLong())
        doAnswer { log += "sdk.touchEnd(${it.arguments[0]},${it.arguments[1]},${it.arguments[2]},${it.arguments[3]})"; false }
            .`when`(nuance).touchEnd(anyLong(), anyFloat(), anyFloat(), anyLong())
        doAnswer { log += "sdk.touchCancel(${it.arguments[0]})"; false }
            .`when`(nuance).touchCancel(anyLong())
        val lock = Any()
        sdkStatic = mockStatic(NuanceSDKManager::class.java)
        sdkStatic.`when`<NuanceSDK> { NuanceSDKManager.getInstance() }.thenReturn(nuance)
        // The touch call sites go through the null-checked choke point (audit L8), not through
        // getInstance() directly. mockStatic stubs EVERY static, so an unstubbed sdkOrWarn would
        // hand them null and every sdk.* line would silently vanish from the golden stream —
        // which is exactly what a real engine-load failure looks like, and why the streams below
        // are the regression test for the null handling as well.
        sdkStatic.`when`<NuanceSDK> { NuanceSDKManager.sdkOrWarn(anyString()) }.thenReturn(nuance)
        sdkStatic.`when`<Any> { NuanceSDKManager.getGestureLock() }.thenReturn(lock)

        val app = ApplicationProvider.getApplicationContext<Context>()
        val themed = ContextThemeWrapper(app, R.style.KeyboardTheme_LXX)
        PointerTracker.init(
            styledAttributes(themed), timers, batchTimers, drawing,
            { gestureEnabled }, sliding
        )
        PointerTracker.setKeyboardActionListener(listener)
        // Materialise the layout in a fixed order.
        listOf(keyA, keyB, keyC, keyShift, keyDelete, keyD)
        detector = object : KeyDetector() {
            override fun detectHitKey(x: Int, y: Int): Key? =
                if (y !in 0 until 100) null else specs.firstOrNull { x >= it.x0 && x < it.x1 }?.key
        }
        detector.setKeyboard(keyboard(), 0f, 0f)
        PointerTracker.setKeyDetector(detector)
        log.clear()
    }

    @After
    fun tearDown() {
        try {
            val q = PointerTracker::class.java.getDeclaredField("sPointerTrackerQueue")
                .apply { isAccessible = true }.get(null) as PointerTrackerQueue
            q.releaseAllPointers(0)
        } finally {
            PointerTracker.release()
            resetStatics()
            sdkStatic.close()
            settingsStatic.close()
        }
    }

    private fun resetStatics() {
        for (name in listOf("sInGesture", "sInMoreKeysPanel", "sSuppressSlideboard")) {
            setField(PointerTracker::class.java, PointerTracker::class.java, name, false)
        }
    }

    // ---- event plumbing (mirrors MainKeyboardView.processMotionEvent) --------------------------

    private data class P(val id: Int, val x: Int, val y: Int)

    private fun event(action: Int, at: Long, pointers: List<P>, actionIndex: Int = 0): MotionEvent {
        val props = Array(pointers.size) { i ->
            MotionEvent.PointerProperties().apply { id = pointers[i].id; toolType = MotionEvent.TOOL_TYPE_FINGER }
        }
        val coords = Array(pointers.size) { i ->
            MotionEvent.PointerCoords().apply { x = pointers[i].x.toFloat(); y = pointers[i].y.toFloat(); pressure = 1f; size = 1f }
        }
        return MotionEvent.obtain(
            t0, at, action or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            pointers.size, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
    }

    private fun dispatch(e: MotionEvent) {
        val id = e.getPointerId(e.actionIndex)
        log += "--- ${MotionEvent.actionToString(e.action)} id=$id t=${e.eventTime}"
        PointerTracker.getPointerTracker(id).processMotionEvent(e, detector)
        e.recycle()
    }

    private fun down(at: Long, x: Int, y: Int = 50, id: Int = 0) =
        dispatch(event(MotionEvent.ACTION_DOWN, at, listOf(P(id, x, y))))
    private fun move(at: Long, vararg ps: P) = dispatch(event(MotionEvent.ACTION_MOVE, at, ps.toList()))
    private fun up(at: Long, x: Int, y: Int = 50, id: Int = 0) =
        dispatch(event(MotionEvent.ACTION_UP, at, listOf(P(id, x, y))))
    private fun pointerDown(at: Long, index: Int, vararg ps: P) =
        dispatch(event(MotionEvent.ACTION_POINTER_DOWN, at, ps.toList(), index))
    private fun pointerUp(at: Long, index: Int, vararg ps: P) =
        dispatch(event(MotionEvent.ACTION_POINTER_UP, at, ps.toList(), index))
    private fun cancel(at: Long, vararg ps: P) = dispatch(event(MotionEvent.ACTION_CANCEL, at, ps.toList()))

    private fun tracker(id: Int = 0) = PointerTracker.getPointerTracker(id)

    private fun assertStream(expected: String) {
        val actual = log.joinToString("\n")
        if (actual != expected.trimIndent()) println("GOLDEN ${testName.methodName}\n$actual\nEND GOLDEN")
        assertEquals(expected.trimIndent(), actual)
    }

    // ---- build-time constants the move path depends on -----------------------------------------

    /**
     * `PointerTracker` only lets a finger re-select keys by dragging when
     * `keySelectionByDraggingFinger` resolves true; with it true, the "sliding finger while multi
     * touching" / cancel-on-slide arms of the move path cannot run. Pin the resolved value through
     * both style chains `MainKeyboardView` can be inflated with.
     */
    @Test
    fun keySelectionByDraggingFingerResolvesTrue() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        for (ctx in listOf(ContextThemeWrapper(app, R.style.KeyboardTheme_LXX), app)) {
            val ta = styledAttributes(ctx)
            assertTrue(ta.getBoolean(R.styleable.MainKeyboardView_keySelectionByDraggingFinger, false))
            ta.recycle()
        }
    }

    // ---- single pointer -----------------------------------------------------------------------

    @Test
    fun tapLetter() {
        down(1000, 50)
        up(1080, 52)
        assertStream(
            """
            --- ACTION_DOWN id=0 t=1000
            draw.dismissTrail
            L.onPressKey(97,0,true)
            timer.startTypingState(97)
            timer.cancelLongPressShift
            timer.startLockFocus(0,250)
            draw.showPreview(97)
            draw.invalidate(97)
            --- ACTION_UP id=0 t=1080
            batch.cancel(0)
            timer.cancelKeyTimers(0)
            draw.dismissTrail
            draw.dismissPreview(97)
            draw.invalidate(97)
            sdk.touchCancel(0)
            L.onCodeInput(97,50,50,1080,false)
            L.onReleaseKey(97,false)
            sdk.touchEnd(0,52.0,50.0,1080)
            """
        )
    }

    @Test
    fun tapKeyWithMoreKeysStartsAndCancelsLongPress() {
        down(1000, 550)
        up(1080, 550)
        assertStream(
            """
            --- ACTION_DOWN id=0 t=1000
            draw.dismissTrail
            L.onPressKey(100,0,true)
            timer.startTypingState(100)
            timer.cancelLongPressShift
            timer.startLongPress(0,400)
            timer.startLockFocus(0,250)
            draw.showPreview(100)
            draw.invalidate(100)
            --- ACTION_UP id=0 t=1080
            batch.cancel(0)
            timer.cancelKeyTimers(0)
            draw.dismissTrail
            draw.dismissPreview(100)
            draw.invalidate(100)
            sdk.touchCancel(0)
            L.onCodeInput(100,550,50,1080,false)
            L.onReleaseKey(100,false)
            sdk.touchEnd(0,550.0,50.0,1080)
            """
        )
    }

    @Test
    fun slideBeforeLockFocusExpiryStaysOnFirstKey() {
        down(1000, 50)
        move(1020, P(0, 150, 50))
        up(1040, 150)
        assertStream(
            """
            --- ACTION_DOWN id=0 t=1000
            draw.dismissTrail
            L.onPressKey(97,0,true)
            timer.startTypingState(97)
            timer.cancelLongPressShift
            timer.startLockFocus(0,250)
            draw.showPreview(97)
            draw.invalidate(97)
            --- ACTION_MOVE id=0 t=1020
            sdk.touchMove(0,150.0,50.0,1020)
            --- ACTION_UP id=0 t=1040
            batch.cancel(0)
            timer.cancelKeyTimers(0)
            draw.dismissTrail
            draw.dismissPreview(97)
            draw.invalidate(97)
            sdk.touchCancel(0)
            L.onCodeInput(97,50,50,1040,false)
            L.onReleaseKey(97,false)
            sdk.touchEnd(0,150.0,50.0,1040)
            """
        )
    }

    @Test
    fun slideAcrossKeysAfterLockFocusExpiry() {
        down(1000, 50)
        tracker().onLockFocusTimerExpired()
        move(1020, P(0, 150, 50))
        move(1040, P(0, 250, 50))
        up(1060, 250)
        assertStream(
            """
            --- ACTION_DOWN id=0 t=1000
            draw.dismissTrail
            L.onPressKey(97,0,true)
            timer.startTypingState(97)
            timer.cancelLongPressShift
            timer.startLockFocus(0,250)
            draw.showPreview(97)
            draw.invalidate(97)
            --- ACTION_MOVE id=0 t=1020
            draw.dismissPreview(97)
            draw.invalidate(97)
            L.onReleaseKey(97,true)
            timer.cancelKeyTimers(0)
            L.onPressKey(98,0,true)
            timer.startTypingState(98)
            timer.cancelLongPressShift
            draw.showPreview(98)
            draw.invalidate(98)
            sdk.touchMove(0,150.0,50.0,1020)
            --- ACTION_MOVE id=0 t=1040
            draw.dismissPreview(98)
            draw.invalidate(98)
            L.onReleaseKey(98,true)
            timer.cancelKeyTimers(0)
            L.onPressKey(99,0,true)
            timer.startTypingState(99)
            timer.cancelLongPressShift
            draw.showPreview(99)
            draw.invalidate(99)
            sdk.touchMove(0,250.0,50.0,1040)
            --- ACTION_UP id=0 t=1060
            batch.cancel(0)
            timer.cancelKeyTimers(0)
            draw.dismissTrail
            draw.dismissPreview(99)
            draw.invalidate(99)
            sdk.touchCancel(0)
            L.onCodeInput(99,250,50,1060,false)
            L.onReleaseKey(99,false)
            sdk.touchEnd(0,250.0,50.0,1060)
            """
        )
    }

    @Test
    fun slideOffTheKeyboardCancelsInput() {
        down(1000, 50)
        tracker().onLockFocusTimerExpired()
        move(1020, P(0, 50, 250))
        up(1040, 50, 250)
        assertStream(
            """
            --- ACTION_DOWN id=0 t=1000
            draw.dismissTrail
            L.onPressKey(97,0,true)
            timer.startTypingState(97)
            timer.cancelLongPressShift
            timer.startLockFocus(0,250)
            draw.showPreview(97)
            draw.invalidate(97)
            --- ACTION_MOVE id=0 t=1020
            draw.dismissPreview(97)
            draw.invalidate(97)
            L.onReleaseKey(97,true)
            timer.cancelKeyTimers(0)
            sdk.touchMove(0,50.0,250.0,1020)
            --- ACTION_UP id=0 t=1040
            batch.cancel(0)
            timer.cancelKeyTimers(0)
            draw.dismissTrail
            draw.dismissPreview(null)
            sdk.touchCancel(0)
            L.onCancelInput
            sdk.touchEnd(0,50.0,250.0,1040)
            """
        )
    }

    @Test
    fun slideOffAndBackOntoAKey() {
        down(1000, 50)
        tracker().onLockFocusTimerExpired()
        move(1020, P(0, 50, 250))
        move(1040, P(0, 150, 50))
        up(1060, 150)
        assertStream(
            """
            --- ACTION_DOWN id=0 t=1000
            draw.dismissTrail
            L.onPressKey(97,0,true)
            timer.startTypingState(97)
            timer.cancelLongPressShift
            timer.startLockFocus(0,250)
            draw.showPreview(97)
            draw.invalidate(97)
            --- ACTION_MOVE id=0 t=1020
            draw.dismissPreview(97)
            draw.invalidate(97)
            L.onReleaseKey(97,true)
            timer.cancelKeyTimers(0)
            sdk.touchMove(0,50.0,250.0,1020)
            --- ACTION_MOVE id=0 t=1040
            L.onPressKey(98,0,true)
            timer.startTypingState(98)
            timer.cancelLongPressShift
            draw.showPreview(98)
            draw.invalidate(98)
            sdk.touchMove(0,150.0,50.0,1040)
            --- ACTION_UP id=0 t=1060
            batch.cancel(0)
            timer.cancelKeyTimers(0)
            draw.dismissTrail
            draw.dismissPreview(98)
            draw.invalidate(98)
            sdk.touchCancel(0)
            L.onCodeInput(98,150,50,1060,false)
            L.onReleaseKey(98,false)
            sdk.touchEnd(0,150.0,50.0,1060)
            """
        )
    }

    @Test
    fun slideFromShiftOntoLetter() {
        down(1000, 350)
        tracker().onLockFocusTimerExpired()
        move(1020, P(0, 50, 50))
        up(1040, 50)
        assertStream(
            """
            --- ACTION_DOWN id=0 t=1000
            draw.dismissTrail
            L.onPressKey(-1,0,true)
            timer.startTypingState(-1)
            timer.cancelLongPressShift
            timer.startLockFocus(0,250)
            draw.showPreview(-1)
            draw.invalidate(-1)
            --- ACTION_MOVE id=0 t=1020
            draw.dismissPreview(-1)
            draw.invalidate(-1)
            L.onReleaseKey(-1,true)
            timer.cancelKeyTimers(0)
            L.onPressKey(97,0,true)
            timer.startTypingState(97)
            timer.cancelLongPressShift
            draw.showPreview(97)
            draw.invalidate(97)
            sdk.touchMove(0,50.0,50.0,1020)
            --- ACTION_UP id=0 t=1040
            batch.cancel(0)
            timer.cancelKeyTimers(0)
            draw.dismissTrail
            draw.dismissPreview(97)
            draw.invalidate(97)
            sdk.touchCancel(0)
            L.onCodeInput(97,50,50,1040,false)
            L.onReleaseKey(97,false)
            sdk.touchEnd(0,50.0,50.0,1040)
            """
        )
    }

    @Test
    fun slideFromLetterOntoModifier() {
        down(1000, 50)
        tracker().onLockFocusTimerExpired()
        move(1020, P(0, 350, 50))
        up(1040, 350)
        assertStream(
            """
            --- ACTION_DOWN id=0 t=1000
            draw.dismissTrail
            L.onPressKey(97,0,true)
            timer.startTypingState(97)
            timer.cancelLongPressShift
            timer.startLockFocus(0,250)
            draw.showPreview(97)
            draw.invalidate(97)
            --- ACTION_MOVE id=0 t=1020
            draw.dismissPreview(97)
            draw.invalidate(97)
            L.onReleaseKey(97,true)
            timer.cancelKeyTimers(0)
            timer.cancelLongPressShift
            draw.showPreview(-1)
            draw.invalidate(-1)
            sdk.touchMove(0,350.0,50.0,1020)
            --- ACTION_UP id=0 t=1040
            batch.cancel(0)
            timer.cancelKeyTimers(0)
            draw.dismissTrail
            draw.dismissPreview(-1)
            draw.invalidate(-1)
            sdk.touchCancel(0)
            L.onCodeInput(-1,-1,-1,1040,false)
            L.onReleaseKey(-1,false)
            sdk.touchEnd(0,350.0,50.0,1040)
            """
        )
    }

    @Test
    fun smallMoveWithinTheSameKey() {
        down(1000, 50)
        tracker().onLockFocusTimerExpired()
        move(1020, P(0, 60, 55))
        up(1040, 60, 55)
        assertStream(
            """
            --- ACTION_DOWN id=0 t=1000
            draw.dismissTrail
            L.onPressKey(97,0,true)
            timer.startTypingState(97)
            timer.cancelLongPressShift
            timer.startLockFocus(0,250)
            draw.showPreview(97)
            draw.invalidate(97)
            --- ACTION_MOVE id=0 t=1020
            sdk.touchMove(0,60.0,55.0,1020)
            --- ACTION_UP id=0 t=1040
            batch.cancel(0)
            timer.cancelKeyTimers(0)
            draw.dismissTrail
            draw.dismissPreview(97)
            draw.invalidate(97)
            sdk.touchCancel(0)
            L.onCodeInput(97,50,50,1040,false)
            L.onReleaseKey(97,false)
            sdk.touchEnd(0,60.0,55.0,1040)
            """
        )
    }

    @Test
    fun cancelEvent() {
        down(1000, 50)
        cancel(1020, P(0, 50, 50))
        assertStream(
            """
            --- ACTION_DOWN id=0 t=1000
            draw.dismissTrail
            L.onPressKey(97,0,true)
            timer.startTypingState(97)
            timer.cancelLongPressShift
            timer.startLockFocus(0,250)
            draw.showPreview(97)
            draw.invalidate(97)
            --- ACTION_CANCEL id=0 t=1020
            sdk.touchCancel(0)
            sdk.touchCancel(0)
            batch.cancel(0)
            timer.cancelKeyTimers(0)
            draw.dismissTrail
            draw.dismissPreview(97)
            draw.invalidate(97)
            sdk.touchCancel(0)
            timer.cancelKeyTimers(0)
            draw.dismissPreview(null)
            draw.dismissTrail
            """
        )
    }

    @Test
    fun keyRepeatOnDelete() {
        down(1000, 450)
        tracker().onKeyRepeat(-5, 1)
        tracker().onKeyRepeat(-5, 2)
        up(1400, 450)
        assertStream(
            """
            --- ACTION_DOWN id=0 t=1000
            draw.dismissTrail
            L.onPressKey(-5,0,true)
            timer.startTypingState(-5)
            timer.startKeyRepeat(0,1,400)
            timer.cancelLongPressShift
            timer.startLockFocus(0,250)
            draw.showPreview(-5)
            draw.invalidate(-5)
            timer.startKeyRepeat(0,2,50)
            L.onPressKey(-5,1,true)
            timer.startTypingState(-5)
            L.onCodeInput(-5,-1,-1,100,true)
            timer.startKeyRepeat(0,3,50)
            L.onPressKey(-5,2,true)
            timer.startTypingState(-5)
            L.onCodeInput(-5,-1,-1,100,true)
            --- ACTION_UP id=0 t=1400
            batch.cancel(0)
            timer.cancelKeyTimers(0)
            draw.dismissTrail
            draw.dismissPreview(-5)
            draw.invalidate(-5)
            sdk.touchEnd(0,450.0,50.0,1400)
            """
        )
    }

    @Test
    fun longPressHandlerCancelsTrackingThenUp() {
        down(1000, 550)
        tracker().cancelTrackingAndReleaseKey()
        up(1500, 550)
        assertStream(
            """
            --- ACTION_DOWN id=0 t=1000
            draw.dismissTrail
            L.onPressKey(100,0,true)
            timer.startTypingState(100)
            timer.cancelLongPressShift
            timer.startLongPress(0,400)
            timer.startLockFocus(0,250)
            draw.showPreview(100)
            draw.invalidate(100)
            draw.dismissTrail
            sdk.touchCancel(0)
            draw.dismissPreview(100)
            draw.invalidate(100)
            --- ACTION_UP id=0 t=1500
            batch.cancel(0)
            timer.cancelKeyTimers(0)
            draw.dismissTrail
            draw.dismissPreview(100)
            draw.invalidate(100)
            sdk.touchEnd(0,550.0,50.0,1500)
            """
        )
    }

    @Test
    fun rapidRetapAtTheSameSpot() {
        down(1000, 50)
        up(1010, 50)
        down(1015, 51)
        up(1030, 51)
        assertStream(
            """
            --- ACTION_DOWN id=0 t=1000
            draw.dismissTrail
            L.onPressKey(97,0,true)
            timer.startTypingState(97)
            timer.cancelLongPressShift
            timer.startLockFocus(0,250)
            draw.showPreview(97)
            draw.invalidate(97)
            --- ACTION_UP id=0 t=1010
            batch.cancel(0)
            timer.cancelKeyTimers(0)
            draw.dismissTrail
            draw.dismissPreview(97)
            draw.invalidate(97)
            sdk.touchCancel(0)
            L.onCodeInput(97,50,50,1010,false)
            L.onReleaseKey(97,false)
            sdk.touchEnd(0,50.0,50.0,1010)
            --- ACTION_DOWN id=0 t=1015
            sdk.touchCancel(0)
            --- ACTION_UP id=0 t=1030
            batch.cancel(0)
            timer.cancelKeyTimers(0)
            draw.dismissTrail
            draw.dismissPreview(null)
            sdk.touchEnd(0,51.0,50.0,1030)
            """
        )
    }

    // ---- two pointers -------------------------------------------------------------------------

    @Test
    fun rolloverFirstFingerLiftsFirst() {
        down(1000, 50)
        pointerDown(1040, 1, P(0, 50, 50), P(1, 150, 50))
        pointerUp(1060, 0, P(0, 50, 50), P(1, 150, 50))
        up(1100, 150, id = 1)
        assertStream(
            """
            --- ACTION_DOWN id=0 t=1000
            draw.dismissTrail
            L.onPressKey(97,0,true)
            timer.startTypingState(97)
            timer.cancelLongPressShift
            timer.startLockFocus(0,250)
            draw.showPreview(97)
            draw.invalidate(97)
            --- ACTION_POINTER_DOWN(1) id=1 t=1040
            draw.dismissTrail
            L.onPressKey(98,0,false)
            timer.startTypingState(98)
            timer.cancelLongPressShift
            timer.startLockFocus(1,250)
            draw.showPreview(98)
            draw.invalidate(98)
            --- ACTION_POINTER_UP(0) id=0 t=1060
            batch.cancel(0)
            timer.cancelKeyTimers(0)
            draw.dismissTrail
            draw.dismissPreview(97)
            draw.invalidate(97)
            sdk.touchCancel(0)
            L.onCodeInput(97,50,50,1060,false)
            L.onReleaseKey(97,false)
            sdk.touchEnd(0,50.0,50.0,1060)
            --- ACTION_UP id=1 t=1100
            batch.cancel(1)
            timer.cancelKeyTimers(1)
            draw.dismissTrail
            draw.dismissPreview(98)
            draw.invalidate(98)
            sdk.touchCancel(1)
            L.onCodeInput(98,150,50,1100,false)
            L.onReleaseKey(98,false)
            sdk.touchEnd(1,150.0,50.0,1100)
            """
        )
    }

    @Test
    fun secondFingerLiftsFirstPhantomsTheOlderOne() {
        down(1000, 50)
        pointerDown(1040, 1, P(0, 50, 50), P(1, 150, 50))
        pointerUp(1060, 1, P(0, 50, 50), P(1, 150, 50))
        up(1100, 50)
        assertStream(
            """
            --- ACTION_DOWN id=0 t=1000
            draw.dismissTrail
            L.onPressKey(97,0,true)
            timer.startTypingState(97)
            timer.cancelLongPressShift
            timer.startLockFocus(0,250)
            draw.showPreview(97)
            draw.invalidate(97)
            --- ACTION_POINTER_DOWN(1) id=1 t=1040
            draw.dismissTrail
            L.onPressKey(98,0,false)
            timer.startTypingState(98)
            timer.cancelLongPressShift
            timer.startLockFocus(1,250)
            draw.showPreview(98)
            draw.invalidate(98)
            --- ACTION_POINTER_UP(1) id=1 t=1060
            batch.cancel(1)
            batch.cancel(0)
            timer.cancelKeyTimers(0)
            draw.dismissTrail
            draw.dismissPreview(97)
            draw.invalidate(97)
            sdk.touchCancel(0)
            L.onCodeInput(97,50,50,1060,false)
            L.onReleaseKey(97,false)
            sdk.touchCancel(0)
            timer.cancelKeyTimers(1)
            draw.dismissTrail
            draw.dismissPreview(98)
            draw.invalidate(98)
            sdk.touchCancel(1)
            L.onCodeInput(98,150,50,1060,false)
            L.onReleaseKey(98,false)
            sdk.touchEnd(1,150.0,50.0,1060)
            --- ACTION_UP id=0 t=1100
            batch.cancel(0)
            timer.cancelKeyTimers(0)
            draw.dismissTrail
            draw.dismissPreview(null)
            sdk.touchEnd(0,50.0,50.0,1100)
            """
        )
    }

    @Test
    fun modifierDownReleasesHeldLetter() {
        down(1000, 50)
        pointerDown(1040, 1, P(0, 50, 50), P(1, 350, 50))
        pointerUp(1060, 0, P(0, 50, 50), P(1, 350, 50))
        up(1100, 350, id = 1)
        assertStream(
            """
            --- ACTION_DOWN id=0 t=1000
            draw.dismissTrail
            L.onPressKey(97,0,true)
            timer.startTypingState(97)
            timer.cancelLongPressShift
            timer.startLockFocus(0,250)
            draw.showPreview(97)
            draw.invalidate(97)
            --- ACTION_POINTER_DOWN(1) id=1 t=1040
            batch.cancel(0)
            timer.cancelKeyTimers(0)
            draw.dismissTrail
            draw.dismissPreview(97)
            draw.invalidate(97)
            sdk.touchCancel(0)
            L.onCodeInput(97,50,50,1040,false)
            L.onReleaseKey(97,false)
            sdk.touchCancel(0)
            draw.dismissTrail
            L.onPressKey(-1,0,true)
            timer.startTypingState(-1)
            timer.cancelLongPressShift
            timer.startLockFocus(1,250)
            draw.showPreview(-1)
            draw.invalidate(-1)
            --- ACTION_POINTER_UP(0) id=0 t=1060
            batch.cancel(0)
            timer.cancelKeyTimers(0)
            draw.dismissTrail
            draw.dismissPreview(null)
            sdk.touchEnd(0,50.0,50.0,1060)
            --- ACTION_UP id=1 t=1100
            batch.cancel(1)
            timer.cancelKeyTimers(1)
            draw.dismissTrail
            draw.dismissPreview(-1)
            draw.invalidate(-1)
            sdk.touchCancel(1)
            L.onCodeInput(-1,-1,-1,1100,false)
            L.onReleaseKey(-1,false)
            sdk.touchEnd(1,350.0,50.0,1100)
            """
        )
    }

    @Test
    fun heldShiftSurvivesALetterTap() {
        down(1000, 350)
        pointerDown(1040, 1, P(0, 350, 50), P(1, 50, 50))
        pointerUp(1060, 1, P(0, 350, 50), P(1, 50, 50))
        up(1100, 350)
        assertStream(
            """
            --- ACTION_DOWN id=0 t=1000
            draw.dismissTrail
            L.onPressKey(-1,0,true)
            timer.startTypingState(-1)
            timer.cancelLongPressShift
            timer.startLockFocus(0,250)
            draw.showPreview(-1)
            draw.invalidate(-1)
            --- ACTION_POINTER_DOWN(1) id=1 t=1040
            draw.dismissTrail
            L.onPressKey(97,0,false)
            timer.startTypingState(97)
            timer.cancelLongPressShift
            timer.startLockFocus(1,250)
            draw.showPreview(97)
            draw.invalidate(97)
            --- ACTION_POINTER_UP(1) id=1 t=1060
            batch.cancel(1)
            timer.cancelKeyTimers(1)
            draw.dismissTrail
            draw.dismissPreview(97)
            draw.invalidate(97)
            sdk.touchCancel(1)
            L.onCodeInput(97,50,50,1060,false)
            L.onReleaseKey(97,false)
            sdk.touchEnd(1,50.0,50.0,1060)
            --- ACTION_UP id=0 t=1100
            batch.cancel(0)
            timer.cancelKeyTimers(0)
            draw.dismissTrail
            draw.dismissPreview(-1)
            draw.invalidate(-1)
            sdk.touchCancel(0)
            L.onCodeInput(-1,-1,-1,1100,false)
            L.onReleaseKey(-1,false)
            sdk.touchEnd(0,350.0,50.0,1100)
            """
        )
    }

    @Test
    fun twoFingersMovingTogether() {
        down(1000, 50)
        tracker().onLockFocusTimerExpired()
        pointerDown(1040, 1, P(0, 50, 50), P(1, 350, 50))
        tracker(1).onLockFocusTimerExpired()
        move(1060, P(0, 150, 50), P(1, 250, 50))
        pointerUp(1080, 1, P(0, 150, 50), P(1, 250, 50))
        up(1100, 150)
        assertStream(
            """
            --- ACTION_DOWN id=0 t=1000
            draw.dismissTrail
            L.onPressKey(97,0,true)
            timer.startTypingState(97)
            timer.cancelLongPressShift
            timer.startLockFocus(0,250)
            draw.showPreview(97)
            draw.invalidate(97)
            --- ACTION_POINTER_DOWN(1) id=1 t=1040
            batch.cancel(0)
            timer.cancelKeyTimers(0)
            draw.dismissTrail
            draw.dismissPreview(97)
            draw.invalidate(97)
            sdk.touchCancel(0)
            L.onCodeInput(97,50,50,1040,false)
            L.onReleaseKey(97,false)
            sdk.touchCancel(0)
            draw.dismissTrail
            L.onPressKey(-1,0,true)
            timer.startTypingState(-1)
            timer.cancelLongPressShift
            timer.startLockFocus(1,250)
            draw.showPreview(-1)
            draw.invalidate(-1)
            --- ACTION_MOVE id=0 t=1060
            draw.dismissPreview(-1)
            draw.invalidate(-1)
            L.onReleaseKey(-1,true)
            timer.cancelKeyTimers(1)
            L.onPressKey(99,0,true)
            timer.startTypingState(99)
            timer.cancelLongPressShift
            draw.showPreview(99)
            draw.invalidate(99)
            sdk.touchMove(1,250.0,50.0,1060)
            --- ACTION_POINTER_UP(1) id=1 t=1080
            batch.cancel(1)
            timer.cancelKeyTimers(1)
            draw.dismissTrail
            draw.dismissPreview(99)
            draw.invalidate(99)
            sdk.touchCancel(1)
            L.onCodeInput(99,250,50,1080,false)
            L.onReleaseKey(99,false)
            sdk.touchEnd(1,250.0,50.0,1080)
            --- ACTION_UP id=0 t=1100
            batch.cancel(0)
            timer.cancelKeyTimers(0)
            draw.dismissTrail
            draw.dismissPreview(null)
            sdk.touchEnd(0,150.0,50.0,1100)
            """
        )
    }

    // ---- gesture (VKB swipe) ------------------------------------------------------------------

    private fun swipeAcross(from: Long, id: Int = 0) {
        var t = from
        var x = 30
        while (x <= 580) {
            t += 10
            x += 25
            move(t, P(id, minOf(x, 590), 50))
        }
    }

    @Test
    fun swipeAcrossLetters() {
        gestureEnabled = true
        down(1000, 30)
        swipeAcross(1000)
        up(1300, 590)
        assertStream(
            """
            --- ACTION_DOWN id=0 t=1000
            draw.dismissTrail
            L.onPressKey(97,0,true)
            timer.startTypingState(97)
            timer.cancelLongPressShift
            timer.startLockFocus(0,250)
            draw.showPreview(97)
            draw.invalidate(97)
            sdk.touchStart(0,30.0,50.0,1000)
            --- ACTION_MOVE id=0 t=1010
            sdk.touchMove(0,55.0,50.0,1010)
            --- ACTION_MOVE id=0 t=1020
            sdk.touchMove(0,80.0,50.0,1020)
            --- ACTION_MOVE id=0 t=1030
            L.onStartBatchInput
            timer.cancelLongPress(0)
            L.onUpdateBatchInput
            batch.start(0)
            draw.slidingPreview(0)
            draw.dismissPreview(97)
            draw.invalidate(97)
            sdk.touchMove(0,105.0,50.0,1030)
            --- ACTION_MOVE id=0 t=1040
            batch.start(0)
            L.onUpdateBatchInput
            batch.start(0)
            draw.slidingPreview(0)
            draw.dismissPreview(null)
            sdk.touchMove(0,130.0,50.0,1040)
            --- ACTION_MOVE id=0 t=1050
            batch.start(0)
            L.onUpdateBatchInput
            batch.start(0)
            draw.slidingPreview(0)
            draw.dismissPreview(null)
            sdk.touchMove(0,155.0,50.0,1050)
            --- ACTION_MOVE id=0 t=1060
            batch.start(0)
            L.onUpdateBatchInput
            batch.start(0)
            draw.slidingPreview(0)
            draw.dismissPreview(null)
            sdk.touchMove(0,180.0,50.0,1060)
            --- ACTION_MOVE id=0 t=1070
            batch.start(0)
            L.onUpdateBatchInput
            batch.start(0)
            draw.slidingPreview(0)
            draw.dismissPreview(null)
            sdk.touchMove(0,205.0,50.0,1070)
            --- ACTION_MOVE id=0 t=1080
            batch.start(0)
            L.onUpdateBatchInput
            batch.start(0)
            draw.slidingPreview(0)
            draw.dismissPreview(null)
            sdk.touchMove(0,230.0,50.0,1080)
            --- ACTION_MOVE id=0 t=1090
            batch.start(0)
            L.onUpdateBatchInput
            batch.start(0)
            draw.slidingPreview(0)
            draw.dismissPreview(null)
            sdk.touchMove(0,255.0,50.0,1090)
            --- ACTION_MOVE id=0 t=1100
            batch.start(0)
            L.onUpdateBatchInput
            batch.start(0)
            draw.slidingPreview(0)
            draw.dismissPreview(null)
            sdk.touchMove(0,280.0,50.0,1100)
            --- ACTION_MOVE id=0 t=1110
            batch.start(0)
            L.onUpdateBatchInput
            batch.start(0)
            draw.slidingPreview(0)
            draw.dismissPreview(null)
            sdk.touchMove(0,305.0,50.0,1110)
            --- ACTION_MOVE id=0 t=1120
            batch.start(0)
            L.onUpdateBatchInput
            batch.start(0)
            draw.slidingPreview(0)
            draw.dismissPreview(null)
            sdk.touchMove(0,330.0,50.0,1120)
            --- ACTION_MOVE id=0 t=1130
            batch.start(0)
            L.onUpdateBatchInput
            batch.start(0)
            draw.slidingPreview(0)
            draw.dismissPreview(null)
            sdk.touchMove(0,355.0,50.0,1130)
            --- ACTION_MOVE id=0 t=1140
            batch.start(0)
            L.onUpdateBatchInput
            batch.start(0)
            draw.slidingPreview(0)
            draw.dismissPreview(null)
            sdk.touchMove(0,380.0,50.0,1140)
            --- ACTION_MOVE id=0 t=1150
            batch.start(0)
            L.onUpdateBatchInput
            batch.start(0)
            draw.slidingPreview(0)
            draw.dismissPreview(null)
            sdk.touchMove(0,405.0,50.0,1150)
            --- ACTION_MOVE id=0 t=1160
            batch.start(0)
            L.onUpdateBatchInput
            batch.start(0)
            draw.slidingPreview(0)
            draw.dismissPreview(null)
            sdk.touchMove(0,430.0,50.0,1160)
            --- ACTION_MOVE id=0 t=1170
            batch.start(0)
            L.onUpdateBatchInput
            batch.start(0)
            draw.slidingPreview(0)
            draw.dismissPreview(null)
            sdk.touchMove(0,455.0,50.0,1170)
            --- ACTION_MOVE id=0 t=1180
            batch.start(0)
            L.onUpdateBatchInput
            batch.start(0)
            draw.slidingPreview(0)
            draw.dismissPreview(null)
            sdk.touchMove(0,480.0,50.0,1180)
            --- ACTION_MOVE id=0 t=1190
            batch.start(0)
            L.onUpdateBatchInput
            batch.start(0)
            draw.slidingPreview(0)
            draw.dismissPreview(null)
            sdk.touchMove(0,505.0,50.0,1190)
            --- ACTION_MOVE id=0 t=1200
            batch.start(0)
            L.onUpdateBatchInput
            batch.start(0)
            draw.slidingPreview(0)
            draw.dismissPreview(null)
            sdk.touchMove(0,530.0,50.0,1200)
            --- ACTION_MOVE id=0 t=1210
            batch.start(0)
            L.onUpdateBatchInput
            batch.start(0)
            draw.slidingPreview(0)
            draw.dismissPreview(null)
            sdk.touchMove(0,555.0,50.0,1210)
            --- ACTION_MOVE id=0 t=1220
            batch.start(0)
            L.onUpdateBatchInput
            batch.start(0)
            draw.slidingPreview(0)
            draw.dismissPreview(null)
            sdk.touchMove(0,580.0,50.0,1220)
            --- ACTION_MOVE id=0 t=1230
            L.onUpdateBatchInput
            batch.start(0)
            draw.slidingPreview(0)
            draw.dismissPreview(null)
            sdk.touchMove(0,590.0,50.0,1230)
            --- ACTION_UP id=0 t=1300
            batch.cancel(0)
            timer.cancelKeyTimers(0)
            draw.dismissTrail
            draw.dismissPreview(null)
            batch.cancelAll
            L.onEndBatchInput(SOFTWARE)
            draw.slidingPreview(0)
            sdk.touchEnd(0,590.0,50.0,1300)
            """
        )
    }

    @Test
    fun swipeEndingOnAModifier() {
        gestureEnabled = true
        down(1000, 30)
        var t = 1000L
        for (x in listOf(55, 80, 105, 130, 155, 180, 205, 230, 255, 280, 305, 330, 350)) {
            t += 10
            move(t, P(0, x, 50))
        }
        up(t + 20, 350)
        assertStream(
            """
            --- ACTION_DOWN id=0 t=1000
            draw.dismissTrail
            L.onPressKey(97,0,true)
            timer.startTypingState(97)
            timer.cancelLongPressShift
            timer.startLockFocus(0,250)
            draw.showPreview(97)
            draw.invalidate(97)
            sdk.touchStart(0,30.0,50.0,1000)
            --- ACTION_MOVE id=0 t=1010
            sdk.touchMove(0,55.0,50.0,1010)
            --- ACTION_MOVE id=0 t=1020
            sdk.touchMove(0,80.0,50.0,1020)
            --- ACTION_MOVE id=0 t=1030
            L.onStartBatchInput
            timer.cancelLongPress(0)
            L.onUpdateBatchInput
            batch.start(0)
            draw.slidingPreview(0)
            draw.dismissPreview(97)
            draw.invalidate(97)
            sdk.touchMove(0,105.0,50.0,1030)
            --- ACTION_MOVE id=0 t=1040
            batch.start(0)
            L.onUpdateBatchInput
            batch.start(0)
            draw.slidingPreview(0)
            draw.dismissPreview(null)
            sdk.touchMove(0,130.0,50.0,1040)
            --- ACTION_MOVE id=0 t=1050
            batch.start(0)
            L.onUpdateBatchInput
            batch.start(0)
            draw.slidingPreview(0)
            draw.dismissPreview(null)
            sdk.touchMove(0,155.0,50.0,1050)
            --- ACTION_MOVE id=0 t=1060
            batch.start(0)
            L.onUpdateBatchInput
            batch.start(0)
            draw.slidingPreview(0)
            draw.dismissPreview(null)
            sdk.touchMove(0,180.0,50.0,1060)
            --- ACTION_MOVE id=0 t=1070
            batch.start(0)
            L.onUpdateBatchInput
            batch.start(0)
            draw.slidingPreview(0)
            draw.dismissPreview(null)
            sdk.touchMove(0,205.0,50.0,1070)
            --- ACTION_MOVE id=0 t=1080
            batch.start(0)
            L.onUpdateBatchInput
            batch.start(0)
            draw.slidingPreview(0)
            draw.dismissPreview(null)
            sdk.touchMove(0,230.0,50.0,1080)
            --- ACTION_MOVE id=0 t=1090
            batch.start(0)
            L.onUpdateBatchInput
            batch.start(0)
            draw.slidingPreview(0)
            draw.dismissPreview(null)
            sdk.touchMove(0,255.0,50.0,1090)
            --- ACTION_MOVE id=0 t=1100
            batch.start(0)
            L.onUpdateBatchInput
            batch.start(0)
            draw.slidingPreview(0)
            draw.dismissPreview(null)
            sdk.touchMove(0,280.0,50.0,1100)
            --- ACTION_MOVE id=0 t=1110
            batch.start(0)
            L.onUpdateBatchInput
            batch.start(0)
            draw.slidingPreview(0)
            draw.dismissPreview(null)
            sdk.touchMove(0,305.0,50.0,1110)
            --- ACTION_MOVE id=0 t=1120
            batch.start(0)
            L.onUpdateBatchInput
            batch.start(0)
            draw.slidingPreview(0)
            draw.dismissPreview(null)
            sdk.touchMove(0,330.0,50.0,1120)
            --- ACTION_MOVE id=0 t=1130
            batch.start(0)
            L.onUpdateBatchInput
            batch.start(0)
            draw.slidingPreview(0)
            draw.dismissPreview(null)
            sdk.touchMove(0,350.0,50.0,1130)
            --- ACTION_UP id=0 t=1150
            batch.cancel(0)
            timer.cancelKeyTimers(0)
            draw.dismissTrail
            draw.dismissPreview(null)
            batch.cancelAll
            L.onEndBatchInput(SOFTWARE)
            draw.slidingPreview(0)
            sdk.touchEnd(0,350.0,50.0,1150)
            """
        )
    }

    @Test
    fun swipeInterruptedBySecondPointer() {
        gestureEnabled = true
        down(1000, 30)
        swipeAcross(1000)
        pointerDown(1250, 1, P(0, 590, 50), P(1, 150, 50))
        pointerUp(1270, 1, P(0, 590, 50), P(1, 150, 50))
        up(1300, 590)
        assertStream(
            """
            --- ACTION_DOWN id=0 t=1000
            draw.dismissTrail
            L.onPressKey(97,0,true)
            timer.startTypingState(97)
            timer.cancelLongPressShift
            timer.startLockFocus(0,250)
            draw.showPreview(97)
            draw.invalidate(97)
            sdk.touchStart(0,30.0,50.0,1000)
            --- ACTION_MOVE id=0 t=1010
            sdk.touchMove(0,55.0,50.0,1010)
            --- ACTION_MOVE id=0 t=1020
            sdk.touchMove(0,80.0,50.0,1020)
            --- ACTION_MOVE id=0 t=1030
            L.onStartBatchInput
            timer.cancelLongPress(0)
            L.onUpdateBatchInput
            batch.start(0)
            draw.slidingPreview(0)
            draw.dismissPreview(97)
            draw.invalidate(97)
            sdk.touchMove(0,105.0,50.0,1030)
            --- ACTION_MOVE id=0 t=1040
            batch.start(0)
            L.onUpdateBatchInput
            batch.start(0)
            draw.slidingPreview(0)
            draw.dismissPreview(null)
            sdk.touchMove(0,130.0,50.0,1040)
            --- ACTION_MOVE id=0 t=1050
            batch.start(0)
            L.onUpdateBatchInput
            batch.start(0)
            draw.slidingPreview(0)
            draw.dismissPreview(null)
            sdk.touchMove(0,155.0,50.0,1050)
            --- ACTION_MOVE id=0 t=1060
            batch.start(0)
            L.onUpdateBatchInput
            batch.start(0)
            draw.slidingPreview(0)
            draw.dismissPreview(null)
            sdk.touchMove(0,180.0,50.0,1060)
            --- ACTION_MOVE id=0 t=1070
            batch.start(0)
            L.onUpdateBatchInput
            batch.start(0)
            draw.slidingPreview(0)
            draw.dismissPreview(null)
            sdk.touchMove(0,205.0,50.0,1070)
            --- ACTION_MOVE id=0 t=1080
            batch.start(0)
            L.onUpdateBatchInput
            batch.start(0)
            draw.slidingPreview(0)
            draw.dismissPreview(null)
            sdk.touchMove(0,230.0,50.0,1080)
            --- ACTION_MOVE id=0 t=1090
            batch.start(0)
            L.onUpdateBatchInput
            batch.start(0)
            draw.slidingPreview(0)
            draw.dismissPreview(null)
            sdk.touchMove(0,255.0,50.0,1090)
            --- ACTION_MOVE id=0 t=1100
            batch.start(0)
            L.onUpdateBatchInput
            batch.start(0)
            draw.slidingPreview(0)
            draw.dismissPreview(null)
            sdk.touchMove(0,280.0,50.0,1100)
            --- ACTION_MOVE id=0 t=1110
            batch.start(0)
            L.onUpdateBatchInput
            batch.start(0)
            draw.slidingPreview(0)
            draw.dismissPreview(null)
            sdk.touchMove(0,305.0,50.0,1110)
            --- ACTION_MOVE id=0 t=1120
            batch.start(0)
            L.onUpdateBatchInput
            batch.start(0)
            draw.slidingPreview(0)
            draw.dismissPreview(null)
            sdk.touchMove(0,330.0,50.0,1120)
            --- ACTION_MOVE id=0 t=1130
            batch.start(0)
            L.onUpdateBatchInput
            batch.start(0)
            draw.slidingPreview(0)
            draw.dismissPreview(null)
            sdk.touchMove(0,355.0,50.0,1130)
            --- ACTION_MOVE id=0 t=1140
            batch.start(0)
            L.onUpdateBatchInput
            batch.start(0)
            draw.slidingPreview(0)
            draw.dismissPreview(null)
            sdk.touchMove(0,380.0,50.0,1140)
            --- ACTION_MOVE id=0 t=1150
            batch.start(0)
            L.onUpdateBatchInput
            batch.start(0)
            draw.slidingPreview(0)
            draw.dismissPreview(null)
            sdk.touchMove(0,405.0,50.0,1150)
            --- ACTION_MOVE id=0 t=1160
            batch.start(0)
            L.onUpdateBatchInput
            batch.start(0)
            draw.slidingPreview(0)
            draw.dismissPreview(null)
            sdk.touchMove(0,430.0,50.0,1160)
            --- ACTION_MOVE id=0 t=1170
            batch.start(0)
            L.onUpdateBatchInput
            batch.start(0)
            draw.slidingPreview(0)
            draw.dismissPreview(null)
            sdk.touchMove(0,455.0,50.0,1170)
            --- ACTION_MOVE id=0 t=1180
            batch.start(0)
            L.onUpdateBatchInput
            batch.start(0)
            draw.slidingPreview(0)
            draw.dismissPreview(null)
            sdk.touchMove(0,480.0,50.0,1180)
            --- ACTION_MOVE id=0 t=1190
            batch.start(0)
            L.onUpdateBatchInput
            batch.start(0)
            draw.slidingPreview(0)
            draw.dismissPreview(null)
            sdk.touchMove(0,505.0,50.0,1190)
            --- ACTION_MOVE id=0 t=1200
            batch.start(0)
            L.onUpdateBatchInput
            batch.start(0)
            draw.slidingPreview(0)
            draw.dismissPreview(null)
            sdk.touchMove(0,530.0,50.0,1200)
            --- ACTION_MOVE id=0 t=1210
            batch.start(0)
            L.onUpdateBatchInput
            batch.start(0)
            draw.slidingPreview(0)
            draw.dismissPreview(null)
            sdk.touchMove(0,555.0,50.0,1210)
            --- ACTION_MOVE id=0 t=1220
            batch.start(0)
            L.onUpdateBatchInput
            batch.start(0)
            draw.slidingPreview(0)
            draw.dismissPreview(null)
            sdk.touchMove(0,580.0,50.0,1220)
            --- ACTION_MOVE id=0 t=1230
            L.onUpdateBatchInput
            batch.start(0)
            draw.slidingPreview(0)
            draw.dismissPreview(null)
            sdk.touchMove(0,590.0,50.0,1230)
            --- ACTION_POINTER_DOWN(1) id=1 t=1250
            sdk.touchCancel(1)
            --- ACTION_POINTER_UP(1) id=1 t=1270
            sdk.touchCancel(1)
            --- ACTION_UP id=0 t=1300
            batch.cancel(0)
            timer.cancelKeyTimers(0)
            draw.dismissTrail
            draw.dismissPreview(null)
            batch.cancelAll
            L.onEndBatchInput(SOFTWARE)
            draw.slidingPreview(0)
            sdk.touchEnd(0,590.0,50.0,1300)
            """
        )
    }

    @Test
    fun gestureEnabledTapIsNotABatch() {
        gestureEnabled = true
        down(1000, 50)
        up(1080, 50)
        assertStream(
            """
            --- ACTION_DOWN id=0 t=1000
            draw.dismissTrail
            L.onPressKey(97,0,true)
            timer.startTypingState(97)
            timer.cancelLongPressShift
            timer.startLockFocus(0,250)
            draw.showPreview(97)
            draw.invalidate(97)
            sdk.touchStart(0,50.0,50.0,1000)
            --- ACTION_UP id=0 t=1080
            batch.cancel(0)
            timer.cancelKeyTimers(0)
            draw.dismissTrail
            draw.dismissPreview(97)
            draw.invalidate(97)
            sdk.touchCancel(0)
            L.onCodeInput(97,50,50,1080,false)
            L.onReleaseKey(97,false)
            sdk.touchEnd(0,50.0,50.0,1080)
            """
        )
    }

    @Test
    fun gestureStartingOnAModifierIsNotDetected() {
        gestureEnabled = true
        down(1000, 350)
        move(1020, P(0, 250, 50))
        move(1040, P(0, 150, 50))
        up(1060, 150)
        assertStream(
            """
            --- ACTION_DOWN id=0 t=1000
            draw.dismissTrail
            L.onPressKey(-1,0,true)
            timer.startTypingState(-1)
            timer.cancelLongPressShift
            timer.startLockFocus(0,250)
            draw.showPreview(-1)
            draw.invalidate(-1)
            sdk.touchStart(0,350.0,50.0,1000)
            --- ACTION_MOVE id=0 t=1020
            sdk.touchMove(0,250.0,50.0,1020)
            --- ACTION_MOVE id=0 t=1040
            sdk.touchMove(0,150.0,50.0,1040)
            --- ACTION_UP id=0 t=1060
            batch.cancel(0)
            timer.cancelKeyTimers(0)
            draw.dismissTrail
            draw.dismissPreview(-1)
            draw.invalidate(-1)
            sdk.touchCancel(0)
            L.onCodeInput(-1,-1,-1,1060,false)
            L.onReleaseKey(-1,false)
            sdk.touchEnd(0,150.0,50.0,1060)
            """
        )
    }

    // ---- debug log text (grep targets for adb recipes) -----------------------------------------

    @Test
    fun debugLogLinesAreStable() {
        val loggerStatic = mockStatic(Logger::class.java)
        try {
            loggerStatic.`when`<Boolean> { Logger.isLoggable(anyString(), anyInt()) }.thenReturn(true)
            ShadowLog.reset()
            down(1000, 50)
            tracker().onLockFocusTimerExpired()
            move(1020, P(0, 150, 50))
            up(1040, 150)
            down(1045, 150)
            up(1050, 150)
            gestureEnabled = true
            down(2000, 30, id = 0)
            swipeAcross(2000)
            up(2300, 590)
            down(3000, 350)
            move(3020, P(0, 350, 250))
            cancel(3030, P(0, 350, 250))
            log.clear()
            for (item in ShadowLog.getLogs()) {
                if (item.tag == "PointerTracker" || item.tag == "PointerTrackerQueue" || item.tag == "AbstractDrawingHandler") {
                    log += "${item.type}/${item.tag}: ${item.msg}"
                }
            }
        } finally {
            loggerStatic.close()
        }
        assertStream(
            """
            3/PointerTracker: [0] onDownEvent:   50   50  1000 a
            3/PointerTracker: [0] onPress    : a
            3/PointerTracker: [0] onMoveEvent:  150   50  1020 b
            3/PointerTracker: [0] isMajorEnoughMoveToBeOnNewKey: 0.00 key width from key edge
            3/PointerTracker: [0] onRelease  : a sliding
            3/PointerTracker: [0] onPress    : b
            3/PointerTracker: [0] onUpEvent  :  150   50  1040 b
            3/PointerTracker: [0] onRelease  : b
            3/PointerTracker: [0] onDownEvent:  150   50  1045 b
            3/PointerTracker: [0] onDownEvent: ignore potential noise: time=5 distance=0
            3/PointerTracker: [0]-onUpEvent  :  150   50  1050 b
            3/PointerTracker: [0]-onDownEvent:   30   50  2000 a
            3/PointerTracker: [0] onPress    : a
            3/PointerTracker: [0] onMoveEvent:   55   50  2010 a
            3/PointerTracker: [0] onMoveEvent:   80   50  2020 a
            3/PointerTracker: [0] onMoveEvent:  105   50  2030 b
            3/PointerTracker: [0] onStartBatchInput
            3/PointerTracker: [0] onUpdateBatchInput
            3/PointerTracker: [0] onMoveEvent:  130   50  2040 b
            3/PointerTracker: [0] onUpdateBatchInput
            3/PointerTracker: [0] onMoveEvent:  155   50  2050 b
            3/PointerTracker: [0] onUpdateBatchInput
            3/PointerTracker: [0] onMoveEvent:  180   50  2060 b
            3/PointerTracker: [0] onUpdateBatchInput
            3/PointerTracker: [0] onMoveEvent:  205   50  2070 c
            3/PointerTracker: [0] onUpdateBatchInput
            3/PointerTracker: [0] onMoveEvent:  230   50  2080 c
            3/PointerTracker: [0] onUpdateBatchInput
            3/PointerTracker: [0] onMoveEvent:  255   50  2090 c
            3/PointerTracker: [0] onUpdateBatchInput
            3/PointerTracker: [0] onMoveEvent:  280   50  2100 c
            3/PointerTracker: [0] onUpdateBatchInput
            3/PointerTracker: [0] onMoveEvent:  305   50  2110 shift
            3/PointerTracker: [0] onUpdateBatchInput
            3/PointerTracker: [0] onMoveEvent:  330   50  2120 shift
            3/PointerTracker: [0] onUpdateBatchInput
            3/PointerTracker: [0] onMoveEvent:  355   50  2130 shift
            3/PointerTracker: [0] onUpdateBatchInput
            3/PointerTracker: [0] onMoveEvent:  380   50  2140 shift
            3/PointerTracker: [0] onUpdateBatchInput
            3/PointerTracker: [0] onMoveEvent:  405   50  2150 delete
            3/PointerTracker: [0] onUpdateBatchInput
            3/PointerTracker: [0] onMoveEvent:  430   50  2160 delete
            3/PointerTracker: [0] onUpdateBatchInput
            3/PointerTracker: [0] onMoveEvent:  455   50  2170 delete
            3/PointerTracker: [0] onUpdateBatchInput
            3/PointerTracker: [0] onMoveEvent:  480   50  2180 delete
            3/PointerTracker: [0] onUpdateBatchInput
            3/PointerTracker: [0] onMoveEvent:  505   50  2190 d
            3/PointerTracker: [0] onUpdateBatchInput
            3/PointerTracker: [0] onMoveEvent:  530   50  2200 d
            3/PointerTracker: [0] onUpdateBatchInput
            3/PointerTracker: [0] onMoveEvent:  555   50  2210 d
            3/PointerTracker: [0] onUpdateBatchInput
            3/PointerTracker: [0] onMoveEvent:  580   50  2220 d
            3/PointerTracker: [0] onUpdateBatchInput
            3/PointerTracker: [0] onMoveEvent:  590   50  2230 d
            3/PointerTracker: [0] onUpdateBatchInput
            3/PointerTracker: [0] onUpEvent  :  590   50  2300 d
            3/PointerTracker: [0] onEndBatchInput
            3/PointerTracker: [0] onDownEvent:  350   50  3000 shift
            3/PointerTracker: [0] onPress    : shift
            3/PointerTracker: [0] onMoveEvent:  350  250  3020 none
            3/PointerTracker: [0] isMajorEnoughMoveToBeOnNewKey: 0.00 key width from key edge
            3/PointerTracker: [0] onRelease  : shift sliding
            3/PointerTracker: [0] onCancelEvt:  350  250  3030 none
            3/PointerTracker: [0]-onPhntEvent:  350  250  3030 none
            """
        )
    }
}

package dev.bbkb.ime.keyboard.internal

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import com.blackberry.nuanceshim.NuanceSDK
import dev.bbkb.ime.R
import dev.bbkb.ime.core.engine.EngineThreads
import dev.bbkb.ime.core.engine.NuanceSDKManager
import dev.bbkb.ime.core.settings.util.SettingsManager
import dev.bbkb.ime.core.settings.util.SettingsValues
import dev.bbkb.ime.keyboard.KeyboardActionListenerInterface
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.MockedStatic
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The touch keypad: what [GestureEventProcessor] gives the engine for a contact. A field that takes
 * no swipes gives it nothing; a tap is a start, its moves and one cancel; a swipe is decoded once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.blackberry.nuanceshim"])
class KeypadEngineTouchTest {

    private val calls = mutableListOf<String>()
    private lateinit var settingsStatic: MockedStatic<SettingsManager>
    private lateinit var sdkStatic: MockedStatic<NuanceSDKManager>
    private var fieldTakesSwipes = true
    private val t0 = 10_000L

    private val batchTimers = object : TimerCallback {
        override fun cancelAllUpdateBatchInputTimers() {}
        override fun startUpdateBatchInputTimer(h: AbstractDrawingHandler) {}
        override fun cancelUpdateBatchInputTimer(h: AbstractDrawingHandler) {}
    }

    private fun unsafeAllocate(c: Class<*>): Any {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(theUnsafe, c)
    }

    private fun setField(target: Any?, owner: Class<*>, name: String, value: Any?) {
        owner.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    private fun settings(): SettingsValues {
        val sv = unsafeAllocate(SettingsValues::class.java) as SettingsValues
        val capsType = SettingsValues::class.java.getDeclaredField("editorCapabilities").type
        val caps = unsafeAllocate(capsType)
        setField(caps, capsType, "shouldSupportGestureInput", fieldTakesSwipes)
        setField(sv, SettingsValues::class.java, "editorCapabilities", caps)
        return sv
    }

    private fun resetStatics() {
        val c = GestureEventProcessor::class.java
        setField(null, c, "sInGesture", false)
        setField(null, c, "sGestureHandlingActive", false)
        setField(null, c, "sLastKeyEventTime", 0L)
        (c.getDeclaredField("sTrackers").apply { isAccessible = true }.get(null) as ArrayList<*>).clear()
        EngineTouch.resetForTests()
    }

    @Before
    fun setUp() {
        resetStatics()
        EngineThreads.setInlineForTests(true)
        val sm = mock(SettingsManager::class.java)
        `when`(sm.settingsValues).thenAnswer { settings() }
        settingsStatic = mockStatic(SettingsManager::class.java)
        settingsStatic.`when`<SettingsManager> { SettingsManager.getInstance() }.thenReturn(sm)

        val sdk = mock(NuanceSDK::class.java)
        doAnswer { calls += "start"; true }.`when`(sdk).touchStart(anyLong(), anyFloat(), anyFloat(), anyLong())
        doAnswer { calls += "move"; true }.`when`(sdk).touchMove(anyLong(), anyFloat(), anyFloat(), anyLong())
        doAnswer { calls += "end"; false }.`when`(sdk).touchEnd(anyLong(), anyFloat(), anyFloat(), anyLong())
        doAnswer { calls += "cancel"; true }.`when`(sdk).touchCancel(anyLong())
        sdkStatic = mockStatic(NuanceSDKManager::class.java)
        sdkStatic.`when`<NuanceSDK> { NuanceSDKManager.sdkOrWarn(anyString()) }.thenReturn(sdk)

        val app = ApplicationProvider.getApplicationContext<Context>()
        val themed = ContextThemeWrapper(app, R.style.KeyboardTheme_LXX)
        val attrs = themed.obtainStyledAttributes(
            null, R.styleable.MainKeyboardView, R.attr.mainKeyboardViewStyle, R.style.MainKeyboardView
        )
        GestureEventProcessor.init(attrs, batchTimers) { true }
        GestureEventProcessor.setKeyboardActionListener(mock(KeyboardActionListenerInterface::class.java))
        calls.clear()
    }

    @After
    fun tearDown() {
        try {
            GestureEventProcessor.setKeyboardActionListener(KeyboardActionListenerInterface.EMPTY)
            resetStatics()
        } finally {
            sdkStatic.close()
            settingsStatic.close()
            EngineThreads.setInlineForTests(false)
        }
    }

    private fun event(action: Int, at: Long, x: Int, y: Int): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x.toFloat(); this.y = y.toFloat(); pressure = 1f; size = 1f })
        return MotionEvent.obtain(t0, at, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHPAD, 0)
    }

    private fun send(action: Int, at: Long, x: Int, y: Int = 200) {
        val e = event(action, at, x, y)
        GestureEventProcessor.getGestureEventProcessor(0).processMotionEvent(e)
        e.recycle()
    }

    private fun swipe() {
        send(MotionEvent.ACTION_DOWN, t0, 100)
        var x = 100
        var t = t0
        while (x < 900) {
            x += 20
            t += 10
            send(MotionEvent.ACTION_MOVE, t, x)
        }
        send(MotionEvent.ACTION_UP, t + 10, x)
    }

    @Test
    fun aTapIsAStartItsMovesAndOneCancel() {
        send(MotionEvent.ACTION_DOWN, t0, 100)
        send(MotionEvent.ACTION_MOVE, t0 + 10, 101)
        send(MotionEvent.ACTION_MOVE, t0 + 20, 102)
        send(MotionEvent.ACTION_UP, t0 + 30, 102)
        assertEquals(listOf("start", "move", "move", "cancel"), calls)
    }

    @Test
    fun aSwipeIsDecodedOnce() {
        swipe()
        assertEquals("start", calls.first())
        assertEquals("the lift-off is the decode", "end", calls.last())
        assertEquals(1, calls.count { it == "end" })
        assertEquals("nothing cancels a swipe", 0, calls.count { it == "cancel" })
        assertTrue("every sample is delivered, one op each", calls.count { it == "move" } == 40)
    }

    @Test
    fun aFieldWithoutSwipingGivesTheEngineNothing() {
        fieldTakesSwipes = false
        swipe()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun aPressedKeyStopsTheMovesAndTheLiftCancelsOnce() {
        send(MotionEvent.ACTION_DOWN, t0, 100)
        send(MotionEvent.ACTION_MOVE, t0 + 10, 101)
        // A hardware key goes down under the finger: the trackers stop following the contact.
        GestureEventProcessor.cancelAllTracking()
        send(MotionEvent.ACTION_MOVE, t0 + 20, 102)
        send(MotionEvent.ACTION_MOVE, t0 + 30, 103)
        send(MotionEvent.ACTION_UP, t0 + 40, 103)
        assertEquals(listOf("start", "move", "cancel"), calls)
    }
}

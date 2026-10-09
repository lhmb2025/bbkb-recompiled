package dev.bbkb.ime.core.device.touch

import android.content.Context
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.R
import dev.bbkb.ime.core.device.config.model.DeviceInputMapping
import dev.bbkb.ime.core.device.config.model.DeviceMatchCriteria
import dev.bbkb.ime.core.device.config.model.TouchKeypadConfig
import dev.bbkb.ime.core.device.config.model.TouchKeypadConfig.SourcePreference
import dev.bbkb.ime.core.device.config.parser.DeviceInputMappingParser
import dev.bbkb.ime.core.device.detection.KeyboardDeviceScanner
import dev.bbkb.ime.core.device.detection.TouchKeypadInfo
import dev.bbkb.ime.core.device.profile.DeviceCapabilities
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.device.profile.DeviceProfileTestSupport
import dev.bbkb.ime.core.device.touch.TouchSourceSelector.Choice
import dev.bbkb.ime.core.device.touch.TouchSourceStatus.Reason
import dev.bbkb.ime.core.device.touch.TouchSourceStatus.State
import dev.bbkb.ime.core.device.touch.shizuku.AxisRange
import dev.bbkb.ime.core.device.touch.shizuku.DecodedTouch
import dev.bbkb.ime.core.device.touch.shizuku.ShizukuTouchEngine
import dev.bbkb.ime.core.device.touch.shizuku.ShizukuTouchErrors
import dev.bbkb.ime.core.device.touch.shizuku.ShizukuTouchState
import dev.bbkb.ime.core.device.touch.shizuku.ShizukuTouchStatus
import dev.bbkb.ime.core.device.touch.shizuku.TouchAction
import dev.bbkb.ime.core.device.touch.shizuku.TouchDeviceInfo
import dev.bbkb.ime.core.device.touch.shizuku.TouchDeviceMatcher
import dev.bbkb.ime.keyboard.internal.GestureEventProcessor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ShizukuTouchSource], against a fake engine: when it runs (each profile pin and SDK case), what
 * it does to the engine around the IME window showing and hiding, how a decoded touch becomes the
 * MotionEvent the IME's generic-motion entry gets, and the two facts a stream changes elsewhere —
 * `DeviceProfile.hasTouchKeypad()` and the [TouchKeypadGeometry] frame.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ShizukuTouchSourceTest {

    private lateinit var context: Context

    /** The Titan 2's pad as the engine measures it on its evdev node. */
    private val titan2Pad = TouchDeviceInfo(
        path = "/dev/input/event6", name = "touchPad",
        mtX = AxisRange(0, 1440), mtY = AxisRange(0, 720), hasTrackingId = true,
    )

    private class FakeEngine : ShizukuTouchEngineFacade {
        var status = ShizukuTouchStatus(ShizukuTouchState.NOT_RUNNING)
        val starts = mutableListOf<Pair<TouchDeviceMatcher, Boolean>>()
        val grabs = mutableListOf<Boolean>()
        var stops = 0
        private var touches: ShizukuTouchEngine.TouchListener? = null
        private var statuses: ShizukuTouchEngine.StatusListener? = null

        override fun start(context: Context, matcher: TouchDeviceMatcher, grab: Boolean,
                           touches: ShizukuTouchEngine.TouchListener,
                           statuses: ShizukuTouchEngine.StatusListener) {
            starts += matcher to grab
            this.touches = touches
            this.statuses = statuses
            statuses.onStatus(status) // as the engine does, from inside start()
        }

        override fun setGrab(grab: Boolean) { grabs += grab }
        override fun stop() { stops++; touches = null; statuses = null }
        override fun status() = status
        override fun refreshStatus(context: Context) {}
        override fun requestPermission(context: Context) = false
        override fun addStatusListener(context: Context, listener: ShizukuTouchEngine.StatusListener) {}
        override fun removeStatusListener(listener: ShizukuTouchEngine.StatusListener) {}

        fun emit(next: ShizukuTouchStatus) { status = next; statuses?.onStatus(next) }
        fun touch(t: DecodedTouch) { touches?.onTouch(t) }
    }

    /** Delivered events are copied: the source recycles each one after dispatch. */
    private class FakeHost(
        var selection: TouchSourceSelector.Selection,
        var pad: TouchKeypadConfig?,
    ) : ShizukuTouchSource.Host {
        val delivered = mutableListOf<MotionEvent>()
        /** hasTouchKeypad() and isFromTouchKeypad() at the moment each event arrived. */
        val sawPad = mutableListOf<Boolean>()
        override fun context(): Context = ApplicationProvider.getApplicationContext()
        override fun onGenericMotionEvent(event: MotionEvent): Boolean {
            delivered += MotionEvent.obtain(event)
            val profile = DeviceProfile.current()
            sawPad += profile.hasTouchKeypad() && profile.isFromTouchKeypad(event)
            return true
        }
        override fun selection() = selection
        override fun touchKeypad() = pad
    }

    private val engine = FakeEngine()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        KeyboardDeviceScanner.resetForTest(null)
        SyntheticTouchSources.clear()
    }

    @After
    fun tearDown() {
        SyntheticTouchSources.clear()
        KeypadTouchSources.useShizukuEngineForTest(null)
        KeyboardDeviceScanner.resetForTest(null)
        DeviceProfile.initialize(null)
        GestureEventProcessor.setKeypadGeometry(TouchKeypadGeometry.KEY2_DEFAULT)
    }

    private fun pad(minSdk: Int? = 36, source: SourcePreference = SourcePreference.AUTO,
                    rule: DeviceMatchCriteria.Rule? = DeviceMatchCriteria.exact("touchPad")) =
        TouchKeypadConfig().apply {
            nativeMinSdk = minSdk ?: TouchKeypadConfig.NATIVE_MIN_SDK_UNSET
            this.source = source
            inputDeviceName = rule
            rangeX = 1440
            rangeY = 720
        }

    private fun source(config: TouchKeypadConfig?, sdk: Int = 35, enumerated: Boolean = false) =
        FakeHost(TouchSourceSelector.select(config, sdk, enumerated), config)
            .let { it to ShizukuTouchSource(it, engine) }

    private fun streaming(device: TouchDeviceInfo = titan2Pad) =
        ShizukuTouchStatus(ShizukuTouchState.STREAMING, device = device)

    private fun touch(action: TouchAction, x: Int, y: Int, at: Long, down: Long, device: TouchDeviceInfo = titan2Pad) =
        DecodedTouch(action, x, y, at, down, device)

    private fun pkbShape(pad: TouchKeypadInfo?): DeviceCapabilities =
        DeviceCapabilities.forShape(DeviceCapabilities.DetectedDeviceType.PKB,
            true, false, false, "qwerty", "4row").withTouchKeypad(pad)

    private fun parsed(resId: Int): DeviceInputMapping =
        DeviceInputMappingParser.parseConfigFromXmlResource(context, resId).mappings[0]

    // ── when it runs ─────────────────────────────────────────────────────────

    @Test
    fun runsOnlyWhereTheSelectorPicksTheReader() {
        data class Case(val name: String, val config: TouchKeypadConfig?, val sdk: Int,
                        val enumerated: Boolean, val runs: Boolean)
        val cases = listOf(
            Case("no pad declared (the KEY2)", null, 35, true, false),
            Case("auto, below native-min-sdk (Titan 2 on Android 15)", pad(), 35, false, true),
            Case("auto, below native-min-sdk, pad enumerated", pad(), 35, true, true),
            Case("auto, at native-min-sdk (Titan 2 on Android 16)", pad(), 36, false, false),
            Case("pinned shizuku, at native-min-sdk", pad(source = SourcePreference.SHIZUKU), 36, true, true),
            Case("pinned native, below native-min-sdk", pad(source = SourcePreference.NATIVE), 35, false, false),
            Case("auto, no native-min-sdk, pad enumerated", pad(minSdk = null), 30, true, false),
            Case("auto, no native-min-sdk, pad not enumerated", pad(minSdk = null), 30, false, true),
        )
        for (c in cases) {
            val before = engine.starts.size
            val (_, source) = source(c.config, c.sdk, c.enumerated)
            source.onWindowShown()
            assertEquals(c.name, if (c.runs) before + 1 else before, engine.starts.size)
            assertEquals(c.name, c.runs, source.isRunning)
            if (c.runs) source.stop()
        }
    }

    @Test
    fun notChosen_reportsWhyNot_andNeverTouchesTheEngine() {
        val (_, source) = source(pad(), sdk = 36)
        source.onWindowShown()
        source.onWindowHidden()
        assertTrue(engine.starts.isEmpty())
        assertTrue(engine.grabs.isEmpty())
        assertEquals(State.NOT_APPLICABLE, source.status().state)
    }

    @Test
    fun whenTheOsLaterEnumeratesThePad_theNextShowHandsItToTheNativeSource() {
        // A Titan Pocket (no native-min-sdk): the reader runs while mtk-pad is missing...
        val config = pad(minSdk = null)
        val (host, source) = source(config, sdk = 30, enumerated = false)
        source.onWindowShown()
        engine.emit(streaming())
        assertTrue(SyntheticTouchSources.contains(ShizukuTouchSource.DEVICE_ID))

        // ...and stops once the OS enumerates it, so the two routes never run together.
        host.selection = TouchSourceSelector.select(config, 30, true)
        source.onWindowShown()
        assertEquals(1, engine.stops)
        assertFalse(source.isRunning)
        assertFalse(SyntheticTouchSources.contains(ShizukuTouchSource.DEVICE_ID))
        assertEquals(State.NOT_APPLICABLE, source.status().state)
    }

    // ── the engine around show / hide ────────────────────────────────────────

    @Test
    fun startsOnFirstShow_grabsWhileShown_releasesWhenHidden_stopsOnStop() {
        engine.status = ShizukuTouchStatus(ShizukuTouchState.READY)
        val (_, source) = source(pad())

        source.onWindowShown()
        assertEquals("started once, on the profile's pad, with the grab", 1, engine.starts.size)
        assertEquals(TouchDeviceMatcher.exact("touchPad"), engine.starts[0].first)
        assertTrue(engine.starts[0].second)

        source.onWindowHidden()
        source.onWindowShown()
        source.onWindowHidden()
        assertEquals("kept bound: no second start", 1, engine.starts.size)
        assertEquals(listOf(false, true, false), engine.grabs)

        source.stop()
        assertEquals(1, engine.stops)
        assertEquals(TouchSourceStatus.of(State.IDLE, Reason.STOPPED), source.status())
        source.onWindowShown()
        assertEquals("a stopped source stays stopped", 1, engine.starts.size)
    }

    @Test
    fun aRegexRuleIsAnchored_andNoRuleMeansNothingToOpen() {
        val (_, regexSource) = source(pad(rule = DeviceMatchCriteria.regex("mtk-pad|touchPad")))
        regexSource.onWindowShown()
        val matcher = engine.starts.single().first
        assertTrue(matcher.isRegex)
        assertTrue(matcher.matches("mtk-pad"))
        assertTrue(matcher.matches("touchPad"))
        assertFalse("a profile regex matches the whole name", matcher.matches("touchPad2"))
        regexSource.stop()

        val (_, unnamed) = source(pad(rule = null))
        unnamed.onWindowShown()
        assertEquals(1, engine.starts.size)
        assertEquals(TouchSourceStatus.of(State.UNAVAILABLE, Reason.PAD_NOT_NAMED), unnamed.status())
    }

    @Test
    fun theEnginesRealStateIsReported() {
        val (_, source) = source(pad())
        source.onWindowShown()
        val expected = listOf(
            ShizukuTouchStatus(ShizukuTouchState.UNSUPPORTED, ShizukuTouchErrors.API_TOO_OLD) to
                TouchSourceStatus.of(State.UNAVAILABLE, Reason.SHIZUKU_UNSUPPORTED),
            ShizukuTouchStatus(ShizukuTouchState.UNSUPPORTED, ShizukuTouchErrors.SERVER_TOO_OLD) to
                TouchSourceStatus.of(State.UNAVAILABLE, Reason.SHIZUKU_OUTDATED),
            ShizukuTouchStatus(ShizukuTouchState.NOT_INSTALLED) to
                TouchSourceStatus.of(State.UNAVAILABLE, Reason.SHIZUKU_NOT_INSTALLED),
            ShizukuTouchStatus(ShizukuTouchState.NOT_RUNNING) to
                TouchSourceStatus.of(State.UNAVAILABLE, Reason.SHIZUKU_NOT_RUNNING),
            ShizukuTouchStatus(ShizukuTouchState.NOT_GRANTED) to
                TouchSourceStatus.of(State.UNAVAILABLE, Reason.SHIZUKU_NOT_GRANTED),
            ShizukuTouchStatus(ShizukuTouchState.NOT_GRANTED, permissionDeniedForever = true) to
                TouchSourceStatus.of(State.UNAVAILABLE, Reason.SHIZUKU_DENIED),
            ShizukuTouchStatus(ShizukuTouchState.READY) to TouchSourceStatus.of(State.IDLE, Reason.OK),
            ShizukuTouchStatus(ShizukuTouchState.BOUND) to TouchSourceStatus.of(State.IDLE, Reason.OK),
            ShizukuTouchStatus(ShizukuTouchState.BOUND, ShizukuTouchErrors.NOT_FOUND) to
                TouchSourceStatus.of(State.UNAVAILABLE, Reason.PAD_NOT_FOUND),
            ShizukuTouchStatus(ShizukuTouchState.BOUND, ShizukuTouchErrors.DEVICE_GONE) to
                TouchSourceStatus.of(State.UNAVAILABLE, Reason.PAD_NOT_FOUND),
            ShizukuTouchStatus(ShizukuTouchState.BOUND, ShizukuTouchErrors.OPEN_FAILED + ":13") to
                TouchSourceStatus.of(State.UNAVAILABLE, Reason.READER_FAILED),
            streaming() to TouchSourceStatus.of(State.ACTIVE, Reason.OK),
            ShizukuTouchStatus(ShizukuTouchState.STREAMING, ShizukuTouchErrors.GRAB_FAILED + ":16", titan2Pad) to
                TouchSourceStatus.of(State.ACTIVE, Reason.OK),
        )
        for ((engineStatus, sourceStatus) in expected) {
            engine.emit(engineStatus)
            assertEquals(engineStatus.describe(), sourceStatus, source.status())
            assertEquals(engineStatus.describe(), sourceStatus, ShizukuTouchSource.statusOf(engineStatus))
        }
    }

    @Test
    fun keypadTouchSources_readsTheSameState_throughTheSelector() {
        KeypadTouchSources.useShizukuEngineForTest(engine)
        DeviceProfile.installForTest(pkbShape(null))
        DeviceProfileTestSupport.installMapping(parsed(R.xml.device_config_titan2)) // 36 > this JVM's 34
        engine.status = ShizukuTouchStatus(ShizukuTouchState.NOT_RUNNING)
        assertEquals(TouchSourceStatus.of(State.UNAVAILABLE, Reason.SHIZUKU_NOT_RUNNING),
            KeypadTouchSources.shizukuStatus())
        assertEquals(KeypadTouchSources.shizukuStatus(), KeypadTouchSources.activeStatus())
        engine.status = streaming()
        assertEquals(State.ACTIVE, KeypadTouchSources.shizukuStatus().state)

        // A profile that does not choose the reader reports why, whatever the engine says.
        DeviceProfileTestSupport.installMapping(parsed(R.xml.device_config_titan2).apply {
            touchKeypad!!.nativeMinSdk = 34
        })
        assertEquals(TouchSourceStatus.of(State.NOT_APPLICABLE, Reason.OK), KeypadTouchSources.shizukuStatus())
    }

    // ── DecodedTouch → MotionEvent ───────────────────────────────────────────

    @Test
    fun aDecodedTouch_becomesATouchpadMotionEvent_inSensorUnits() {
        val (host, source) = source(pad())
        source.onWindowShown()
        engine.emit(streaming())

        engine.touch(touch(TouchAction.DOWN, 100, 50, at = 5_000, down = 5_000))
        engine.touch(touch(TouchAction.MOVE, 420, 66, at = 5_016, down = 5_000))
        engine.touch(touch(TouchAction.UP, 900, 700, at = 5_040, down = 5_000))

        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP),
            host.delivered.map { it.action })
        val move = host.delivered[1]
        assertEquals(420f, move.x, 0f)
        assertEquals(66f, move.y, 0f)
        assertEquals(5_016L, move.eventTime)
        assertEquals(5_000L, move.downTime)
        assertEquals(ShizukuTouchSource.DEVICE_ID, move.deviceId)
        assertEquals(InputDevice.SOURCE_TOUCHPAD, move.source)
        assertEquals(900f, host.delivered[2].x, 0f)
    }

    @Test
    fun theFactoryStampsTheSyntheticDevice() {
        val e = ShizukuTouchSource.motionEvent(MotionEvent.ACTION_CANCEL, 10L, 20L, 3f, 4f)
        assertEquals(MotionEvent.ACTION_CANCEL, e.action)
        assertEquals(10L, e.downTime)
        assertEquals(20L, e.eventTime)
        assertEquals(ShizukuTouchSource.DEVICE_ID, e.deviceId)
        assertEquals(InputDevice.SOURCE_TOUCHPAD, e.source)
        e.recycle()
    }

    @Test
    fun touchesOutsideAStream_orForAContactNeverOpened_areDropped() {
        val (host, source) = source(pad())
        source.onWindowShown()
        engine.emit(ShizukuTouchStatus(ShizukuTouchState.READY))
        engine.touch(touch(TouchAction.DOWN, 1, 1, 100, 100))
        assertTrue("not streaming yet", host.delivered.isEmpty())

        engine.emit(streaming())
        engine.touch(touch(TouchAction.MOVE, 2, 2, 110, 100))
        engine.touch(touch(TouchAction.UP, 2, 2, 120, 100))
        assertTrue("a MOVE / UP with no DOWN behind it", host.delivered.isEmpty())
    }

    @Test
    fun aStreamThatEndsMidContact_isCancelledWhileStillThePad_andTheEnginesLateCancelIsDropped() {
        DeviceProfile.installForTest(pkbShape(null))
        val (host, source) = source(pad())
        source.onWindowShown()
        engine.emit(streaming())
        engine.touch(touch(TouchAction.DOWN, 10, 10, 1_000, 1_000))
        engine.touch(touch(TouchAction.MOVE, 30, 12, 1_010, 1_000))

        engine.emit(ShizukuTouchStatus(ShizukuTouchState.NOT_RUNNING)) // Shizuku died
        assertEquals(MotionEvent.ACTION_CANCEL, host.delivered.last().action)
        assertEquals(1_000L, host.delivered.last().downTime)
        assertTrue("every event, the CANCEL included, arrived while it counted as the pad", host.sawPad.all { it })

        engine.touch(touch(TouchAction.CANCEL, 30, 12, 1_020, 1_000))
        assertEquals("the engine's own CANCEL finds no stream", 3, host.delivered.size)
    }

    @Test
    fun stoppingMidContact_cancelsTheContact() {
        val (host, source) = source(pad())
        source.onWindowShown()
        engine.emit(streaming())
        engine.touch(touch(TouchAction.DOWN, 10, 10, 1_000, 1_000))
        source.stop()
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), host.delivered.map { it.action })
        assertFalse(SyntheticTouchSources.contains(ShizukuTouchSource.DEVICE_ID))
    }

    // ── hasTouchKeypad and the frame ─────────────────────────────────────────

    @Test
    fun hasTouchKeypad_isTrueExactlyWhileStreaming() {
        DeviceProfile.installForTest(pkbShape(null))
        DeviceProfileTestSupport.installMapping(parsed(R.xml.device_config_titan2)) // Android 15 shape
        val profile = DeviceProfile.current()
        assertEquals(Choice.SHIZUKU, profile.touchSourceSelection.choice)
        val (_, source) = source(profile.touchKeypadConfig)
        val ours = ShizukuTouchSource.motionEvent(MotionEvent.ACTION_DOWN, 0L, 0L, 1f, 1f)

        source.onWindowShown()
        engine.emit(ShizukuTouchStatus(ShizukuTouchState.READY))
        assertFalse(profile.hasTouchKeypad())
        assertFalse(profile.isFromTouchKeypad(ours))

        engine.emit(streaming())
        assertTrue(profile.hasTouchKeypad())
        assertTrue(profile.isFromTouchKeypad(ours))

        engine.emit(ShizukuTouchStatus(ShizukuTouchState.BOUND, ShizukuTouchErrors.DEVICE_GONE))
        assertFalse(profile.hasTouchKeypad())

        engine.emit(streaming())
        assertTrue(profile.hasTouchKeypad())
        source.stop()
        assertFalse("stopping ends it too", profile.hasTouchKeypad())
        ours.recycle()
    }

    @Test
    fun theMeasuredRangesAreTheFrame_whileStreaming() {
        // The Elite's profile guesses 1080 x 600; suppose the engine measures 1200 x 640 and the
        // OS also enumerates a pad (1440 x 720): the measured frame is what the stream's
        // coordinates are in, so it wins over both.
        DeviceProfile.installForTest(pkbShape(TouchKeypadInfo.forTest(9, 0f, 1440f, 720f)))
        DeviceProfileTestSupport.installMapping(parsed(R.xml.device_config_titan2_elite))
        val profile = DeviceProfile.current()
        assertEquals(TouchKeypadGeometry.Source.INPUT_DEVICE, profile.touchKeypadGeometry.xSource())

        val (_, source) = source(pad(source = SourcePreference.SHIZUKU))
        source.onWindowShown()
        engine.emit(streaming(titan2Pad.copy(mtX = AxisRange(0, 1200), mtY = AxisRange(0, 640))))

        val g = profile.touchKeypadGeometry
        assertEquals(TouchKeypadGeometry.Source.MEASURED, g.xSource())
        assertEquals(TouchKeypadGeometry.Source.MEASURED, g.ySource())
        assertEquals(1200, g.frameWidth())
        assertEquals(640, g.frameHeight())
        assertEquals(1200, TouchKeypadGeometry.current().frameWidth())

        source.stop()
        assertEquals("back to the enumerated pad", TouchKeypadGeometry.Source.INPUT_DEVICE,
            profile.touchKeypadGeometry.xSource())
        assertEquals(1440, profile.touchKeypadGeometry.frameWidth())
    }

    @Test
    fun aStreamWithNoRanges_fallsBackToTheProfile() {
        DeviceProfile.installForTest(pkbShape(null))
        DeviceProfileTestSupport.installMapping(parsed(R.xml.device_config_titan2))
        val (_, source) = source(pad())
        source.onWindowShown()
        engine.emit(streaming(TouchDeviceInfo(path = "/dev/input/event6", name = "touchPad")))
        assertTrue(DeviceProfile.current().hasTouchKeypad())
        assertNull(SyntheticTouchSources.measuredPad())
        assertEquals(TouchKeypadGeometry.Source.PROFILE, DeviceProfile.current().touchKeypadGeometry.xSource())
        source.stop()
    }
}

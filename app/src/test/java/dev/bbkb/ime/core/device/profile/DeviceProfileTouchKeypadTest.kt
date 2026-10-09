package dev.bbkb.ime.core.device.profile

import android.content.Context
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.R
import dev.bbkb.ime.core.device.config.model.DeviceInputMapping
import dev.bbkb.ime.core.device.config.model.DeviceMatchCriteria
import dev.bbkb.ime.core.device.config.model.DeviceQuirk
import dev.bbkb.ime.core.device.config.model.TouchKeypadConfig
import dev.bbkb.ime.core.device.config.parser.DeviceInputMappingParser
import dev.bbkb.ime.core.device.detection.KeyboardDeviceScanner
import dev.bbkb.ime.core.device.detection.TouchKeypadInfo
import dev.bbkb.ime.core.device.touch.SyntheticTouchSources
import dev.bbkb.ime.core.device.touch.TouchKeypadGeometry
import dev.bbkb.ime.core.device.touch.TouchSourceSelector
import dev.bbkb.ime.core.gesture.arbiter.GestureAction
import dev.bbkb.ime.core.gesture.arbiter.GestureAssignments
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The touch-keypad questions [DeviceProfile] answers: [DeviceProfile.isFromTouchKeypad] by the
 * scanned id, by the profile's pad name and by a registered synthetic id;
 * [DeviceProfile.declaresTouchKeypad] against [DeviceProfile.hasTouchKeypad]; the geometry,
 * the live re-scan, the swipe-typing gate term and the profile's setting defaults. And that a KEY2
 * shape answers every one of them as it always did.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class DeviceProfileTouchKeypadTest {

    private lateinit var context: Context

    /** The KEY2's touch_keypad: a standalone pad, 1080 x 525. */
    private val key2Pad = TouchKeypadInfo.forTest(5, 0.4f, 1080f, 525f)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        SyntheticTouchSources.clear()
        KeyboardDeviceScanner.resetForTest(null)
        DeviceProfile.initialize(null)
        // A re-scan pushes the stroke frame into GestureEventProcessor's statics; put the KEY2's
        // back so no later test inherits a Titan frame.
        dev.bbkb.ime.keyboard.internal.GestureEventProcessor.setKeypadGeometry(TouchKeypadGeometry.KEY2_DEFAULT)
    }

    private fun pkbShape(pad: TouchKeypadInfo?): DeviceCapabilities =
        DeviceCapabilities.forShape(DeviceCapabilities.DetectedDeviceType.PKB,
            true, false, false, "qwerty", "4row").withTouchKeypad(pad)

    private fun parsed(resId: Int): DeviceInputMapping =
        DeviceInputMappingParser.parseConfigFromXmlResource(context, resId).mappings[0]

    /** A Titan 2-like profile whose native SDK this JVM (API 34) satisfies. */
    private fun nativeTitanMapping(): DeviceInputMapping = parsed(R.xml.device_config_titan2).apply {
        touchKeypad!!.nativeMinSdk = 34
    }

    private fun event(deviceId: Int, action: Int = MotionEvent.ACTION_DOWN): MotionEvent =
        MotionEvent.obtain(0L, 0L, action, 10f, 10f, 1f, 0.1f, 0, 1f, 1f, deviceId, 0).also {
            it.source = InputDevice.SOURCE_TOUCHPAD
        }

    private fun namedDevice(id: Int, name: String): InputDevice {
        val d = mock(InputDevice::class.java)
        `when`(d.id).thenReturn(id)
        `when`(d.name).thenReturn(name)
        return d
    }

    private fun fakeDevices(vararg devices: InputDevice) {
        val byId = devices.associateBy { it.id }
        KeyboardDeviceScanner.resetForTest(object : KeyboardDeviceScanner.InputDevices {
            override fun ids() = byId.keys.toIntArray()
            override fun get(id: Int) = byId[id]
        })
    }

    // ── the KEY2 shape: unchanged ────────────────────────────────────────────

    @Test
    fun key2Shape_answersAsBefore() {
        DeviceProfile.installForTest(pkbShape(key2Pad))
        val p = DeviceProfile.current()

        assertTrue(p.hasTouchKeypad())
        assertFalse(p.declaresTouchKeypad())
        assertEquals(5, p.touchKeypadDeviceId)
        assertTrue(p.isFromTouchKeypad(event(5)))
        assertFalse(p.isFromTouchKeypad(event(6)))
        assertEquals(TouchSourceSelector.Choice.NONE, p.touchSourceSelection.choice)
        assertTrue(p.isTouchKeypadSwipeTypingSupported)
        assertEquals(525f, p.touchKeypadYMax, 0f)
        assertEquals(0.4f, p.touchKeypadResolution, 0f)

        val g = p.touchKeypadGeometry
        assertEquals(1080, g.frameWidth())
        assertEquals(525, g.frameHeight())
        assertEquals(144, g.strokeKeyWidth())
        assertEquals(610, g.strokeBoardHeight())
    }

    @Test
    fun key2Config_onTheForcedCkbRig_keepsItsWarpYMax() {
        // The emulator posing as a KEY2: athena's config (CKB forced, <ckb-y-warp>0:0,450:324),
        // no hardware pad. getTouchKeypadYMax answered 450 before; it still does.
        DeviceProfile.installForTest(pkbShape(null))
        DeviceProfileTestSupport.installMapping(parsed(R.xml.device_config_athena))
        val p = DeviceProfile.current()
        assertTrue(p.hasTouchKeypad())
        assertEquals(450f, p.touchKeypadYMax, 0f)
        assertEquals(144, p.touchKeypadGeometry.strokeKeyWidth())
        assertEquals(610, p.touchKeypadGeometry.strokeBoardHeight())
        assertEquals(1080f, p.touchKeypadGeometry.normalizationReference(), 0f)
        assertTrue(p.isTouchKeypadSwipeTypingSupported)
        assertFalse(p.hasQuirk(DeviceQuirk.FN_NO_KEY_UP))
    }

    @Test
    fun noPadNoProfile_hasNoTouchKeypad_andTheKey2Frame() {
        DeviceProfile.installForTest(pkbShape(null))
        val p = DeviceProfile.current()
        assertFalse(p.hasTouchKeypad())
        assertFalse(p.isFromTouchKeypad(event(5)))
        assertEquals(0f, p.touchKeypadYMax, 0f)
        assertEquals(TouchKeypadGeometry.Source.KEY2_DEFAULT, p.touchKeypadGeometry.ySource())
    }

    // ── declares vs has ──────────────────────────────────────────────────────

    @Test
    fun titan2OnAndroid15_declaresAPad_butNoEventsCanArrive() {
        DeviceProfile.installForTest(pkbShape(null))
        DeviceProfileTestSupport.installMapping(parsed(R.xml.device_config_titan2)) // native-min-sdk 36
        val p = DeviceProfile.current()
        assertTrue(p.declaresTouchKeypad())
        assertFalse("below the native SDK and no reader yet: nothing can deliver", p.hasTouchKeypad())
        assertEquals(TouchSourceSelector.Choice.SHIZUKU, p.touchSourceSelection.choice)
        assertEquals(TouchKeypadConfig.SourcePreference.AUTO, p.touchSourceSelection.preference)
        assertEquals("the profile's range stands in for the pad", 720f, p.touchKeypadYMax, 0f)
        assertEquals(1440, p.touchKeypadGeometry.frameWidth())
    }

    @Test
    fun titanOnItsNativeSdk_hasATouchKeypad() {
        DeviceProfile.installForTest(pkbShape(null))
        DeviceProfileTestSupport.installMapping(nativeTitanMapping())
        val p = DeviceProfile.current()
        assertTrue(p.declaresTouchKeypad())
        assertTrue(p.hasTouchKeypad())
        assertEquals(TouchSourceSelector.Choice.NATIVE, p.touchSourceSelection.choice)
    }

    // ── isFromTouchKeypad: id, name, synthetic ───────────────────────────────

    @Test
    fun isFromTouchKeypad_acceptsTheDeviceTheProfileNames() {
        fakeDevices(namedDevice(9, "touchPad"), namedDevice(10, "sub_touch"), namedDevice(3, "TitanKey"))
        DeviceProfile.installForTest(pkbShape(null))
        DeviceProfileTestSupport.installMapping(nativeTitanMapping())
        val p = DeviceProfile.current()
        assertTrue(p.isFromTouchKeypad(event(9)))
        assertTrue(p.isFromTouchKeypad(event(9, MotionEvent.ACTION_MOVE)))
        assertFalse("the rear screen is not the pad", p.isFromTouchKeypad(event(10)))
        assertFalse(p.isFromTouchKeypad(event(3)))
        assertFalse("unknown device", p.isFromTouchKeypad(event(42)))
    }

    @Test
    fun isFromTouchKeypad_acceptsARegisteredSyntheticSource() {
        DeviceProfile.installForTest(pkbShape(null))
        val p = DeviceProfile.current()
        assertFalse(p.isFromTouchKeypad(event(0x7f00)))
        SyntheticTouchSources.register(0x7f00)
        assertTrue("a running synthetic source counts as a pad", p.hasTouchKeypad())
        assertTrue(p.isFromTouchKeypad(event(0x7f00)))
        assertFalse(p.isFromTouchKeypad(event(0x7f01)))
        SyntheticTouchSources.unregister(0x7f00)
        assertFalse(p.hasTouchKeypad())
        assertFalse(p.isFromTouchKeypad(event(0x7f00)))
    }

    @Test
    fun aNameRuleOnAnotherProfile_neverLooksUpDevices() {
        // KEY2 profile names no pad: an unrelated device id is rejected without any lookup.
        fakeDevices(namedDevice(9, "touchPad"))
        DeviceProfile.installForTest(pkbShape(key2Pad))
        DeviceProfileTestSupport.installMapping(parsed(R.xml.device_config_athena))
        assertFalse(DeviceProfile.current().isFromTouchKeypad(event(9)))
    }

    // ── live re-scan ─────────────────────────────────────────────────────────

    @Test
    fun aPadThatAppearsLater_isFoldedIntoTheProfile_andGoesAgain() {
        DeviceProfile.installForTest(pkbShape(null))
        val titan = parsed(R.xml.device_config_titan2)
        DeviceProfileTestSupport.installMapping(titan)
        val p = DeviceProfile.current()
        assertFalse(p.hasTouchKeypad())

        DeviceProfile.onScannedTouchKeypadChanged(TouchKeypadInfo.forTest(9, 0f, 1440f, 720f))
        assertTrue(p.hasTouchKeypad())
        assertEquals(9, p.touchKeypadDeviceId)
        assertTrue(p.isFromTouchKeypad(event(9)))
        assertEquals(TouchKeypadGeometry.Source.INPUT_DEVICE, p.touchKeypadGeometry.xSource())
        assertEquals("the mapping is untouched by a re-scan", titan, p.deviceMapping)

        DeviceProfile.onScannedTouchKeypadChanged(null)
        assertFalse(p.hasTouchKeypad())
        assertEquals(-1, p.touchKeypadDeviceId)
    }

    // ── swipe typing gate term, defaults, quirks ─────────────────────────────

    @Test
    fun swipeTypingNeedsAKdbVariantOnADeclaredPad() {
        DeviceProfile.installForTest(pkbShape(null))
        val titan = parsed(R.xml.device_config_titan2)
        DeviceProfileTestSupport.installMapping(titan)
        assertFalse(DeviceProfile.current().isTouchKeypadSwipeTypingSupported)
        titan.kdbVariant = "titan2"
        assertTrue(DeviceProfile.current().isTouchKeypadSwipeTypingSupported)
    }

    @Test
    fun profileDefaults_reachTheGestureSlots_onlyWhereDeclared() {
        DeviceProfile.installForTest(pkbShape(key2Pad))
        assertEquals(GestureAction.ENTER_CURSOR_MODE, GestureAssignments.deviceDefaults().doubleTap)

        DeviceProfileTestSupport.installMapping(parsed(R.xml.device_config_titan2))
        val p = DeviceProfile.current()
        assertEquals("none", p.getDefaultStringValue(GestureAssignments.KEY_DOUBLE_TAP, "enter_cursor_mode"))
        assertEquals("app", p.getDefaultStringValue("some_other_key", "app"))
        val defaults = GestureAssignments.deviceDefaults()
        assertEquals(GestureAction.NONE, defaults.doubleTap)
        assertEquals("the other slots keep the app defaults", GestureAction.DELETE_WORD, defaults.swipeLeft)
        assertFalse("a default is not a lock", p.isSettingReadOnly(GestureAssignments.KEY_DOUBLE_TAP))
        assertFalse(p.isSettingHidden(GestureAssignments.KEY_DOUBLE_TAP))
    }

    @Test
    fun theUsersOwnChoice_beatsTheDeviceDefault() {
        DeviceProfile.installForTest(pkbShape(null))
        DeviceProfileTestSupport.installMapping(parsed(R.xml.device_config_titan2))
        val prefs = context.getSharedPreferences("gesture_defaults_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        assertEquals(GestureAction.NONE,
            GestureAssignments.fromPrefs(prefs, GestureAssignments.deviceDefaults()).doubleTap)
        prefs.edit().putString(GestureAssignments.KEY_DOUBLE_TAP, "enter_cursor_mode").commit()
        assertEquals(GestureAction.ENTER_CURSOR_MODE,
            GestureAssignments.fromPrefs(prefs, GestureAssignments.deviceDefaults()).doubleTap)
        prefs.edit().clear().commit()
    }

    @Test
    fun eliteQuirk_isVisibleThroughTheProfile() {
        DeviceProfile.installForTest(pkbShape(null))
        DeviceProfileTestSupport.installMapping(parsed(R.xml.device_config_titan2_elite))
        assertTrue(DeviceProfile.current().hasQuirk(DeviceQuirk.FN_NO_KEY_UP))
    }

    @Test
    fun aRuleNamingThePad_isCaseSensitive() {
        val m = DeviceInputMapping().apply {
            touchKeypad = TouchKeypadConfig().apply {
                inputDeviceName = DeviceMatchCriteria.exact("touchPad")
                nativeMinSdk = 34
            }
        }
        fakeDevices(namedDevice(9, "TOUCHPAD"))
        DeviceProfile.installForTest(pkbShape(null))
        DeviceProfileTestSupport.installMapping(m)
        assertFalse(DeviceProfile.current().isFromTouchKeypad(event(9)))
    }
}

package dev.bbkb.ime.core.device.detection

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.R
import dev.bbkb.ime.core.device.config.model.DeviceMatchCriteria
import dev.bbkb.ime.core.device.config.parser.DeviceInputMappingParser
import dev.bbkb.ime.core.device.config.resolver.ScancodeMappingResolver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * [KeyboardDeviceScanner]'s touch keypad half: the source-based passes as they always were (the
 * KEY2's standalone touch_keypad), the profile-named pad, and the live re-scan an input-device
 * listener drives — which must never touch the key configuration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class KeyboardDeviceScannerTest {

    private val devices = linkedMapOf<Int, InputDevice>()

    @Before
    fun setUp() {
        devices.clear()
        KeyboardDeviceScanner.resetForTest(object : KeyboardDeviceScanner.InputDevices {
            override fun ids() = devices.keys.toIntArray()
            override fun get(id: Int) = devices[id]
        })
    }

    @After
    fun tearDown() {
        KeyboardDeviceScanner.resetForTest(null)
        ScancodeMappingResolver.getInstance().reset()
    }

    /** MotionRange is final and not mockable here; build a real one through its constructor. */
    private fun range(axis: Int, max: Float): InputDevice.MotionRange =
        ReflectionHelpers.callConstructor(InputDevice.MotionRange::class.java,
            ClassParameter.from(Int::class.javaPrimitiveType, axis),
            ClassParameter.from(Int::class.javaPrimitiveType, InputDevice.SOURCE_TOUCHPAD),
            ClassParameter.from(Float::class.javaPrimitiveType, 0f),
            ClassParameter.from(Float::class.javaPrimitiveType, max),
            ClassParameter.from(Float::class.javaPrimitiveType, 0f),
            ClassParameter.from(Float::class.javaPrimitiveType, 0f),
            ClassParameter.from(Float::class.javaPrimitiveType, 0f))

    private fun device(
        id: Int, name: String, sources: Int,
        keyboardType: Int = InputDevice.KEYBOARD_TYPE_NONE,
        xMax: Float? = null, yMax: Float? = null,
    ): InputDevice {
        val d = mock(InputDevice::class.java)
        `when`(d.id).thenReturn(id)
        `when`(d.name).thenReturn(name)
        `when`(d.sources).thenReturn(sources)
        `when`(d.keyboardType).thenReturn(keyboardType)
        `when`(d.isVirtual).thenReturn(false)
        if (xMax != null) {
            val rx = range(MotionEvent.AXIS_X, xMax)
            `when`(d.getMotionRange(MotionEvent.AXIS_X)).thenReturn(rx)
        }
        if (yMax != null) {
            val ry = range(MotionEvent.AXIS_Y, yMax)
            `when`(d.getMotionRange(MotionEvent.AXIS_Y)).thenReturn(ry)
        }
        devices[id] = d
        return d
    }

    private fun keyboard(id: Int, name: String) =
        device(id, name, InputDevice.SOURCE_KEYBOARD, InputDevice.KEYBOARD_TYPE_ALPHABETIC)

    // ── the source-based scan, unchanged ─────────────────────────────────────

    @Test
    fun key2_standaloneTouchpadIsTheTouchKeypad() {
        keyboard(3, "stmpe_keypad")
        device(5, "touch_keypad", InputDevice.SOURCE_TOUCHPAD, xMax = 1080f, yMax = 525f)
        val scanner = KeyboardDeviceScanner.getInstance()
        assertEquals(3, scanner.primaryKeyboard!!.deviceId)
        val pad = scanner.primaryTouchKeypad!!
        assertEquals(5, pad.deviceId)
        assertEquals(1080f, pad.xRangeMax, 0f)
        assertEquals(525f, pad.yRangeMax, 0f)
    }

    @Test
    fun aTouchpadWithoutRanges_isNotFoundBySource() {
        keyboard(3, "TitanKey")
        device(9, "touchPad", InputDevice.SOURCE_TOUCHPAD)
        assertNull(KeyboardDeviceScanner.getInstance().primaryTouchKeypad)
    }

    // ── the profile-named pad ────────────────────────────────────────────────

    @Test
    fun theNamedPadIsFound_whateverItsSources_andTheRearScreenIsNot() {
        keyboard(3, "TitanKey")
        device(10, "sub_touch", InputDevice.SOURCE_TOUCHSCREEN, xMax = 1080f, yMax = 1240f)
        device(9, "touchPad", InputDevice.SOURCE_KEYBOARD)
        val scanner = KeyboardDeviceScanner.getInstance()
        assertNull(scanner.primaryTouchKeypad)

        val seen = mutableListOf<TouchKeypadInfo?>()
        scanner.addTouchKeypadListener { seen += it }
        scanner.setDeclaredTouchKeypadName(DeviceMatchCriteria.exact("touchPad"))

        val pad = scanner.primaryTouchKeypad!!
        assertEquals(9, pad.deviceId)
        assertEquals("no ranges reported: the profile's stand in later", 0f, pad.xRangeMax, 0f)
        assertEquals(listOf(9), seen.map { it?.deviceId })

        scanner.setDeclaredTouchKeypadName(DeviceMatchCriteria.exact("touchPad"))
        assertEquals("the same name again re-scans nothing", 1, seen.size)
    }

    @Test
    fun theNamedPadWinsOverAnotherTouchpad() {
        keyboard(3, "TitanKey")
        device(11, "some_touchpad", InputDevice.SOURCE_TOUCHPAD, xMax = 100f, yMax = 100f)
        device(9, "touchPad", InputDevice.SOURCE_TOUCHPAD, xMax = 1440f, yMax = 720f)
        val scanner = KeyboardDeviceScanner.getInstance()
        assertEquals("by source, the first touchpad", 11, scanner.primaryTouchKeypad!!.deviceId)
        scanner.setDeclaredTouchKeypadName(DeviceMatchCriteria.exact("touchPad"))
        assertEquals(9, scanner.primaryTouchKeypad!!.deviceId)
        assertEquals(1440f, scanner.primaryTouchKeypad!!.xRangeMax, 0f)
    }

    // ── live re-scan from the input-device listener ──────────────────────────

    @Test
    fun aPadThatAppearsAndVanishes_isPickedUpWithoutARestart() {
        keyboard(3, "TitanKey")
        val scanner = KeyboardDeviceScanner.getInstance()
        scanner.setDeclaredTouchKeypadName(DeviceMatchCriteria.exact("touchPad"))
        assertNull(scanner.primaryTouchKeypad)
        val seen = mutableListOf<TouchKeypadInfo?>()
        scanner.addTouchKeypadListener { seen += it }
        val listener = scanner.inputDeviceListenerForTest()

        // The OEM Scroll assistant switched on: the pad enumerates.
        device(9, "touchPad", InputDevice.SOURCE_TOUCHPAD, xMax = 1440f, yMax = 720f)
        listener.onInputDeviceAdded(9)
        assertEquals(9, scanner.primaryTouchKeypad!!.deviceId)
        assertEquals(1, seen.size)

        // An unrelated change re-scans to the same answer: nobody is told.
        listener.onInputDeviceChanged(3)
        assertEquals(1, seen.size)

        // Switched off again.
        devices.remove(9)
        listener.onInputDeviceRemoved(9)
        assertNull(scanner.primaryTouchKeypad)
        assertEquals(2, seen.size)
        assertNull(seen.last())
    }

    /**
     * Android reports the built-in keyboard as "changed" on every IME subtype switch. A reaction
     * to that once emptied the scancode-role resolver and left every config-mapped key dead
     * (2026-09-26). The scanner's listener re-scans the touch keypad only.
     */
    @Test
    fun aDeviceChangedEvent_leavesTheKeyConfigurationAlone() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val mp01 = DeviceInputMappingParser.parseConfigFromXmlResource(context, R.xml.device_config_minimal)
            .mappings[0]
        val resolver = ScancodeMappingResolver.getInstance()
        resolver.initialize(mp01)
        assertNotNull(resolver.resolve(249, KeyEvent.KEYCODE_ALT_RIGHT))

        val kb = keyboard(3, "aw9523b-key")
        val scanner = KeyboardDeviceScanner.getInstance()
        val primary = scanner.primaryKeyboard
        scanner.inputDeviceListenerForTest().onInputDeviceChanged(kb.id)
        scanner.inputDeviceListenerForTest().onInputDeviceAdded(42)
        scanner.inputDeviceListenerForTest().onInputDeviceRemoved(42)

        assertTrue("the resolver must stay populated", resolver.isInitialized)
        assertEquals(dev.bbkb.ime.core.device.config.model.KeyRole.BOARD_SYM,
            resolver.resolve(249, KeyEvent.KEYCODE_ALT_RIGHT)!!.role)
        assertSame("the keyboard cache is not re-scanned either", primary, scanner.primaryKeyboard)
    }

    @Test
    fun registeringTheDeviceListener_isIdempotent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scanner = KeyboardDeviceScanner.getInstance()
        scanner.registerDeviceListener(context)
        scanner.registerDeviceListener(context)
        assertFalse(scanner.rescanTouchKeypad())
    }
}

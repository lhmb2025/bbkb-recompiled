package dev.bbkb.ime.core.device.config.resolver

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.R
import dev.bbkb.ime.core.device.config.model.DeviceInputMapping
import dev.bbkb.ime.core.device.config.model.KeyRole
import dev.bbkb.ime.core.device.config.parser.DeviceInputMappingParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A profile's key roles belong to the phone's own keys. The role lookup used to take no device,
 * so on a KEY2 (key code 7 = the mic key) the 0 key of a Bluetooth or USB keyboard started voice
 * input. These pin the rule in [ScancodeMappingResolver.appliesTo] and the device-aware lookup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class KeyRolesPerKeyboardTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ScancodeMappingResolver.getInstance().reset()
    }

    @After
    fun tearDown() {
        ScancodeMappingResolver.getInstance().reset()
    }

    private fun profile(resId: Int): DeviceInputMapping =
        DeviceInputMappingParser.parseConfigFromXmlResource(context, resId).mappings[0]

    private fun keyboard(name: String, external: Boolean, virtual: Boolean = false): InputDevice {
        val device = mock(InputDevice::class.java)
        `when`(device.name).thenReturn(name)
        `when`(device.isExternal).thenReturn(external)
        `when`(device.isVirtual).thenReturn(virtual)
        return device
    }

    @Test
    fun aProfileMatchedOnThePhone_appliesToBuiltInKeysOnly() {
        val key2 = profile(R.xml.device_config_athena)
        assertTrue(ScancodeMappingResolver.appliesTo(key2, keyboard("stmpe-keypad", external = false)))
        assertTrue("side keys are a separate built-in device",
            ScancodeMappingResolver.appliesTo(key2, keyboard("gpio-keys", external = false)))
        assertFalse(ScancodeMappingResolver.appliesTo(key2, keyboard("Logitech K380", external = true)))
    }

    @Test
    fun keysWithNoRealDevice_keepTheProfile() {
        val key2 = profile(R.xml.device_config_athena)
        assertTrue(ScancodeMappingResolver.appliesTo(key2, null))
        assertTrue(ScancodeMappingResolver.appliesTo(key2,
            keyboard("Virtual", external = false, virtual = true)))
    }

    @Test
    fun aProfileMatchedOnTheKeyboardName_followsThatKeyboard() {
        val mp01 = profile(R.xml.device_config_minimal)
        assertTrue(ScancodeMappingResolver.appliesTo(mp01, keyboard("aw9523b-key", external = false)))
        assertTrue("named by the profile, so it applies even when external",
            ScancodeMappingResolver.appliesTo(mp01, keyboard("aw9523b-key", external = true)))
        assertFalse(ScancodeMappingResolver.appliesTo(mp01, keyboard("Logitech K380", external = true)))
    }

    @Test
    fun theZeroKeyOfAnExternalKeyboard_isNotTheKey2MicKey() {
        val resolver = ScancodeMappingResolver.getInstance()
        resolver.initialize(profile(R.xml.device_config_athena))
        val builtIn = keyboard("stmpe-keypad", external = false)
        val bluetooth = keyboard("Logitech K380", external = true)
        mockStatic(InputDevice::class.java).use { devices ->
            devices.`when`<InputDevice> { InputDevice.getDevice(3) }.thenReturn(builtIn)
            devices.`when`<InputDevice> { InputDevice.getDevice(9) }.thenReturn(bluetooth)

            assertEquals(KeyRole.BOARD_VOICE, resolver.resolve(11, KeyEvent.KEYCODE_0, 3)!!.role)
            assertNull(resolver.resolve(11, KeyEvent.KEYCODE_0, 9))
            assertTrue(resolver.appliesToDevice(3))
            assertFalse(resolver.appliesToDevice(9))
        }
    }

    @Test
    fun withNoProfile_everyKeyboardCounts() {
        assertTrue(ScancodeMappingResolver.getInstance().appliesToDevice(9))
        assertNull(ScancodeMappingResolver.getInstance().resolve(11, KeyEvent.KEYCODE_0, 9))
    }

    @Test
    fun theQ25MicKey_typesZeroWithAlt() {
        val resolver = ScancodeMappingResolver.getInstance()
        resolver.initialize(profile(R.xml.device_config_q25))
        val mic = resolver.resolve(0, KeyEvent.KEYCODE_0)!!
        assertEquals(KeyRole.BOARD_VOICE, mic.role)
        assertEquals('0', mic.altChar)
    }
}

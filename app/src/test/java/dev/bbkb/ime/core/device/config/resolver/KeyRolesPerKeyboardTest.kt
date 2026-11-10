package dev.bbkb.ime.core.device.config.resolver

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.R
import dev.bbkb.ime.core.device.config.model.DeviceInputConfig
import dev.bbkb.ime.core.device.config.model.DeviceInputMapping
import dev.bbkb.ime.core.device.config.model.KeyRole
import dev.bbkb.ime.core.device.config.parser.DeviceInputMappingParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
 * Which profile speaks for which keyboard. A profile matched on the phone's build used to match
 * every keyboard attached to that phone, and key roles were asked without saying which keyboard
 * the key came from: on a KEY2 (key code 7 = the mic key) the 0 key of a Bluetooth or USB
 * keyboard started voice input.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class KeyRolesPerKeyboardTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DeviceInputResolver.resetConfig()
    }

    @After
    fun tearDown() {
        DeviceInputResolver.resetConfig()
    }

    private fun profile(resId: Int): DeviceInputMapping =
        DeviceInputMappingParser.parseConfigFromXmlResource(context, resId).mappings[0]

    private fun configOf(profile: DeviceInputMapping) =
        DeviceInputConfig().apply { mappings.add(profile) }

    private fun keyboard(name: String, external: Boolean, virtual: Boolean = false): InputDevice {
        val device = mock(InputDevice::class.java)
        `when`(device.name).thenReturn(name)
        `when`(device.isExternal).thenReturn(external)
        `when`(device.isVirtual).thenReturn(virtual)
        return device
    }

    /** Runs [block] with these devices behind `InputDevice.getDevice(id)`. */
    private fun withDevices(vararg devices: Pair<Int, InputDevice>, block: () -> Unit) {
        mockStatic(InputDevice::class.java).use { static ->
            for ((id, device) in devices) {
                static.`when`<InputDevice> { InputDevice.getDevice(id) }.thenReturn(device)
            }
            block()
        }
    }

    // ── the match step ───────────────────────────────────────────────────────

    @Test
    fun aProfileMatchedOnThePhone_doesNotMatchAnExternalKeyboard() {
        val key2 = profile(R.xml.device_config_athena)
        org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build::class.java, "DEVICE", "athena")
        val config = configOf(key2)
        assertSame(key2, config.findMappingForInputDevice(keyboard("stmpe-keypad", external = false)))
        assertSame("side keys are a separate built-in device",
            key2, config.findMappingForInputDevice(keyboard("gpio-keys", external = false)))
        assertNull(config.findMappingForInputDevice(keyboard("Logitech K380", external = true)))
    }

    @Test
    fun aProfileThatNamesTheKeyboard_matchesItWhereverItIs() {
        val mp01 = profile(R.xml.device_config_minimal)
        val config = configOf(mp01)
        assertSame(mp01, config.findMappingForInputDevice(keyboard("aw9523b-key", external = false)))
        assertSame(mp01, config.findMappingForInputDevice(keyboard("aw9523b-key", external = true)))
        assertNull(config.findMappingForInputDevice(keyboard("Logitech K380", external = true)))
    }

    // ── which profile governs a key ──────────────────────────────────────────

    @Test
    fun theZeroKeyOfAnExternalKeyboard_isNotTheKey2MicKey() {
        org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build::class.java, "DEVICE", "athena")
        val key2 = profile(R.xml.device_config_athena)
        DeviceInputResolver.installPhoneProfileForTest(key2)
        withDevices(
            3 to keyboard("stmpe-keypad", external = false),
            9 to keyboard("Logitech K380", external = true),
        ) {
            assertEquals(KeyRole.BOARD_VOICE, DeviceInputResolver.keyRole(11, KeyEvent.KEYCODE_0, 3)!!.role)
            assertNull(DeviceInputResolver.keyRole(11, KeyEvent.KEYCODE_0, 9))
            assertNull(DeviceInputResolver.keyProfile(9))
            assertTrue(DeviceInputResolver.isPhoneKeyboard(3))
            assertFalse("so the phone's Alt table and keypad shape do not apply to it",
                DeviceInputResolver.isPhoneKeyboard(9))
        }
    }

    @Test
    fun aKeyWithNoRealDevice_getsThePhonesProfile() {
        val key2 = profile(R.xml.device_config_athena)
        DeviceInputResolver.installPhoneProfileForTest(key2)
        withDevices(-1 to keyboard("Virtual", external = false, virtual = true)) {
            assertSame(key2, DeviceInputResolver.keyProfile(-1))
            assertSame("an id Android knows no device for", key2, DeviceInputResolver.keyProfile(77))
        }
    }

    @Test
    fun aBuiltInDeviceTheProfileDoesNotName_stillGetsThePhonesProfile() {
        val mp01 = profile(R.xml.device_config_minimal)
        DeviceInputResolver.installPhoneProfileForTest(mp01)
        withDevices(
            2 to keyboard("aw9523b-key", external = false),
            4 to keyboard("gpio-keys", external = false),
            9 to keyboard("Logitech K380", external = true),
        ) {
            assertSame(mp01, DeviceInputResolver.keyProfile(2))
            assertSame(mp01, DeviceInputResolver.keyProfile(4))
            assertNull(DeviceInputResolver.keyProfile(9))
        }
    }

    @Test
    fun withNoProfile_everyKeyboardIsThePhones() {
        DeviceInputResolver.installPhoneProfileForTest(null)
        withDevices(9 to keyboard("Logitech K380", external = true)) {
            assertNull(DeviceInputResolver.keyRole(11, KeyEvent.KEYCODE_0, 9))
            assertTrue(DeviceInputResolver.isPhoneKeyboard(9))
        }
    }

    @Test
    fun aDeviceChange_isForgottenAndWorkedOutAgain() {
        org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build::class.java, "DEVICE", "athena")
        DeviceInputResolver.installPhoneProfileForTest(profile(R.xml.device_config_athena))
        withDevices(9 to keyboard("Logitech K380", external = true)) {
            assertNull(DeviceInputResolver.keyProfile(9))
        }
        // The same id now belongs to a built-in device, as after an unplug and a re-enumeration.
        DeviceInputResolver.installPhoneProfileForTest(profile(R.xml.device_config_athena))
        withDevices(9 to keyboard("stmpe-keypad", external = false)) {
            assertEquals(KeyRole.BOARD_VOICE, DeviceInputResolver.keyRole(11, KeyEvent.KEYCODE_0, 9)!!.role)
        }
    }

    // ── shipped profiles ─────────────────────────────────────────────────────

    @Test
    fun theQ25MicKey_typesZeroWithAlt() {
        val mic = profile(R.xml.device_config_q25).roleFor(0, KeyEvent.KEYCODE_0)!!
        assertEquals(KeyRole.BOARD_VOICE, mic.role)
        assertEquals('0', mic.altChar)
    }
}

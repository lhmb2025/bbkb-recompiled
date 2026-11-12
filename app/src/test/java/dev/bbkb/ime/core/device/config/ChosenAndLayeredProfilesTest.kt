package dev.bbkb.ime.core.device.config

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.R
import dev.bbkb.ime.core.device.config.builder.DeviceConfigSummary
import dev.bbkb.ime.core.device.config.model.DeviceInputConfig
import dev.bbkb.ime.core.device.config.model.DeviceInputMapping
import dev.bbkb.ime.core.device.config.model.KeyRole
import dev.bbkb.ime.core.device.config.parser.AltMappingsParser
import dev.bbkb.ime.core.device.config.parser.DeviceInputMappingParser
import dev.bbkb.ime.core.device.config.resolver.DeviceInputResolver
import dev.bbkb.ime.core.device.profile.DeviceCapabilities
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.device.profile.DeviceProfileTestSupport
import dev.bbkb.ime.core.settings.PrefsManager
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
import java.io.File

/**
 * Three things a device profile could not do:
 *
 *  - be applied by hand. Picking a profile whose `<match>` rules fail on this phone gave the
 *    phone no profile at all, and a stored pick that no longer loaded switched detection off;
 *  - make the phone a keypad phone. `<device-type>PKB</device-type>` reached the per-key
 *    classifier only, so the app stayed in on-screen mode unless Android reported an alphabetic
 *    keyboard;
 *  - build on a shipped profile, or carry Alt characters of its own. A custom profile replaced
 *    the shipped one whole, and an Alt table could only be one built into the app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ChosenAndLayeredProfilesTest {

    private lateinit var context: Context
    private val prefs get() = PrefsManager.defaultSharedPreferences(context)
    private val configDir get() = File(context.filesDir, "device_configs")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs.edit().clear().commit()
        configDir.deleteRecursively()
        CustomDeviceConfigManager.resetForTest()
        DeviceInputResolver.resetConfig()
        AltMappingsParser.clearMappingsCache()
        ReflectionHelpers.setStaticField(android.os.Build::class.java, "DEVICE", "some_other_phone")
    }

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
        configDir.deleteRecursively()
        CustomDeviceConfigManager.resetForTest()
        DeviceInputResolver.resetConfig()
        DeviceProfile.initialize(null)
    }

    private fun shipped(resId: Int): DeviceInputMapping =
        DeviceInputMappingParser.parseConfigFromXmlResource(context, resId).mappings[0]

    private fun keyboard(name: String, external: Boolean): InputDevice {
        val device = mock(InputDevice::class.java)
        `when`(device.name).thenReturn(name)
        `when`(device.isExternal).thenReturn(external)
        `when`(device.isVirtual).thenReturn(false)
        return device
    }

    private fun custom(fileName: String, xml: String): String {
        configDir.mkdirs()
        File(configDir, fileName).writeText(xml)
        return "custom:$fileName"
    }

    // ── picked by hand ───────────────────────────────────────────────────────

    @Test
    fun aProfilePickedByHand_speaksForThePhoneEvenWhenItsRulesFail() {
        val mp01 = shipped(R.xml.device_config_minimal)  // matches a keypad named aw9523b-key
        val config = DeviceInputConfig().apply { mappings.add(mp01) }
        val ownKeypad = keyboard("some-other-keypad", external = false)

        assertNull("found by detection, it applies only where it matches",
            config.findMappingForInputDevice(ownKeypad))

        config.chosenByUser = true
        assertSame("picked by hand, it is the phone's profile",
            mp01, config.findMappingForInputDevice(ownKeypad))
        assertNull("but never a plugged-in keyboard's",
            config.findMappingForInputDevice(keyboard("Logitech K380", external = true)))
    }

    @Test
    fun aProfileForAPluggedInKeyboard_isNotPutOnThePhoneByPickingIt() {
        val config = DeviceInputMappingParser.parseConfigFromStream(
            """<device-input-config name="A USB keyboard"><device>
                 <match><vendor-id exact="0x046d"/><product-id exact="0xb342"/></match>
                 <device-type>PKB</device-type>
               </device></device-input-config>""".byteInputStream())
        config.chosenByUser = true

        assertNull(config.chosenPhoneProfile())
        assertNull(config.findMappingForInputDevice(keyboard("gpio-keys", external = false)))
    }

    @Test
    fun aPickStoredByAnEarlierBuild_keepsItsOldMeaning_andANewPickApplies() {
        prefs.edit().putString("active_device_config_id", "preloaded:device_config_minimal").commit()
        CustomDeviceConfigManager.resetForTest()
        assertFalse("an id with no flag was stored before a pick meant 'apply this'",
            CustomDeviceConfigManager.getInstance(context).activeConfig.chosenByUser)

        val manager = CustomDeviceConfigManager.getInstance(context)
        manager.activeConfigId = "preloaded:device_config_minimal"
        assertTrue(manager.activeConfig.chosenByUser)
        assertNotNull(manager.activeConfig.chosenPhoneProfile())

        manager.activeConfigId = CustomDeviceConfigManager.ID_DEFAULT
        assertFalse("Detect automatically is nobody's pick", manager.activeConfig.chosenByUser)
    }

    @Test
    fun aPickWhoseFileIsGone_goesBackToDetection() {
        prefs.edit()
            .putString("active_device_config_id", "custom:gone.xml")
            .putBoolean("active_device_config_chosen", true)
            .commit()
        CustomDeviceConfigManager.resetForTest()
        val manager = CustomDeviceConfigManager.getInstance(context)

        val config = manager.activeConfig

        assertNotNull(config)
        assertFalse(config.chosenByUser)
        assertEquals("the stored pick is dropped, so detection runs as on a fresh install",
            "default", prefs.getString("active_device_config_id", null))
        assertFalse(prefs.contains("active_device_config_chosen"))
        assertEquals("default", manager.activeConfigId)
    }

    @Test
    fun aPickThatHoldsNoDevice_goesBackToDetectionToo() {
        val id = custom("empty.xml", """<device-input-config name="Empty"></device-input-config>""")
        prefs.edit().putString("active_device_config_id", id)
            .putBoolean("active_device_config_chosen", true).commit()
        CustomDeviceConfigManager.resetForTest()

        CustomDeviceConfigManager.getInstance(context).activeConfig

        assertEquals("default", prefs.getString("active_device_config_id", null))
    }

    // ── the keypad declaration ───────────────────────────────────────────────

    @Test
    fun aProfileThatDeclaresAKeypad_makesThePhoneAKeypadPhone() {
        // What the scan reports when Android does not call the keypad an alphabetic keyboard.
        DeviceProfile.installForTest(DeviceCapabilities.forShape(
            DeviceCapabilities.DetectedDeviceType.VKB, false, false, false, "qwerty", "none"))
        assertFalse(DeviceProfile.current().isPkbDevice())
        assertFalse(DeviceProfile.current().hasPhysicalKeyboard())

        DeviceProfileTestSupport.installMapping(shipped(R.xml.device_config_minimal))  // PKB

        assertTrue(DeviceProfile.current().isPkbDevice())
        assertTrue(DeviceProfile.current().hasPhysicalKeyboard())
    }

    @Test
    fun aProfileThatDeclaresNoDeviceType_leavesTheScansAnswer() {
        DeviceProfile.installForTest(DeviceCapabilities.forShape(
            DeviceCapabilities.DetectedDeviceType.VKB, false, false, false, "qwerty", "none"))
        DeviceProfileTestSupport.installMapping(shipped(R.xml.device_config_blackberry))

        assertFalse(DeviceProfile.current().isPkbDevice())
        assertFalse(DeviceProfile.current().hasPhysicalKeyboard())
    }

    // ── building on a shipped profile ────────────────────────────────────────

    private val layeredOnTheMinimalPhone = """
        <device-input-config name="My Minimal Phone">
          <device extends="device_config_minimal">
            <input-mappings>
              <alt-mappings>
                <mapping keycode="667" char="!"/>
              </alt-mappings>
              <scancode-mappings>
                <key rawScanCode="251" role="FUNCTION" notes="the mic key is just a key here"/>
              </scancode-mappings>
            </input-mappings>
          </device>
        </device-input-config>"""

    @Test
    fun aCustomProfileBuildsOnAShippedOne_changingOnlyWhatItNames() {
        val id = custom("mine.xml", layeredOnTheMinimalPhone)
        val manager = CustomDeviceConfigManager.getInstance(context)
        manager.activeConfigId = id
        val base = shipped(R.xml.device_config_minimal)
        val mine = manager.activeConfig.mappings.single()

        // Inherited, unsaid: the match rules, the device type, the Alt table's name, the quirks.
        assertTrue(mine.matchesDevice("aw9523b-key", null, null))
        assertTrue(mine.forcePkbDevice)
        assertEquals(base.altMappingsFile, mine.altMappingsFile)
        assertEquals(base.quirks, mine.quirks)
        assertEquals(base.keypadLayout, mine.keypadLayout)

        // Said again: its own role for the mic key wins, and every other role is still there.
        assertEquals(KeyRole.BOARD_VOICE, base.roleFor(251, 0).role)
        assertEquals(KeyRole.FUNCTION, mine.roleFor(251, 0).role)
        assertEquals(KeyRole.BOARD_SYM, mine.roleFor(249, 0).role)
        assertEquals(base.scancodeMappings.size + 1, mine.scancodeMappings.size)
    }

    @Test
    fun aProfilesOwnAltCharacters_sitOnTheTableItInherits() {
        val id = custom("mine.xml", layeredOnTheMinimalPhone)
        val manager = CustomDeviceConfigManager.getInstance(context)
        manager.activeConfigId = id
        val mine = manager.activeConfig.mappings.single()
        val shippedTable = AltMappingsParser.tableFor(context, shipped(R.xml.device_config_minimal))!!

        val table = AltMappingsParser.tableFor(context, mine)!!

        assertEquals("the built-in table's answer for the mic key", '.', shippedTable.getMapping(667))
        assertEquals("the profile's own answer wins", '!', table.getMapping(667))
        assertEquals("every other key keeps the built-in answer", '0', table.getMapping(666))
        assertEquals(shippedTable.getSpecialFunctionCode(666), table.getSpecialFunctionCode(666))
        assertSame("built once, not per key", table, AltMappingsParser.tableFor(context, mine))
    }

    @Test
    fun aProfileCanCarryAnAltTableOfItsOwn_withNoBuiltInTableNamed() {
        val config = DeviceInputMappingParser.parseConfigFromStream(
            """<device-input-config name="A new phone"><device>
                 <match><device-name exact="new-keypad"/></match>
                 <input-mappings><alt-mappings>
                   <mapping keycode="Q" char="#"/>
                   <mapping keycode="W" char="1"/>
                   <special-function keycode="667" function="voice"/>
                 </alt-mappings></input-mappings>
               </device></device-input-config>""".byteInputStream())
        val profile = config.mappings.single()

        val table = AltMappingsParser.tableFor(context, profile)!!

        assertNull(profile.altMappingsFile)
        assertEquals('#', table.getMapping(KeyEvent.KEYCODE_Q))
        assertEquals('1', table.getMapping(KeyEvent.KEYCODE_W))
        assertEquals(2, table.size())
        assertTrue(table.getSpecialFunctionCode(667) != 0)
    }

    @Test
    fun onlyADeviceProfileCanBeBuiltOn() {
        // An Alt table is an XML resource too, but it is not a profile: the device is then read
        // on its own, and with no match rules of its own it is not a device at all.
        val id = custom("odd.xml", """
            <device-input-config name="Odd"><device extends="device_alt_mappings_aw9523b">
              <device-type>PKB</device-type>
            </device></device-input-config>""")
        val manager = CustomDeviceConfigManager.getInstance(context)
        manager.activeConfigId = id

        assertTrue(manager.activeConfig.mappings.isEmpty() || manager.activeConfigId == "default")
    }

    @Test
    fun theImportSummaryNamesWhatAProfileBuildsOn() {
        val summary = DeviceConfigSummary.of(layeredOnTheMinimalPhone.byteInputStream())!!

        assertEquals("device_config_minimal", summary.basedOn)
        assertFalse("its rules are the built-in profile's, so it is not a file that matches nothing",
            summary.matchesNothing())
        assertEquals("Minimal Phone (MP01)",
            CustomDeviceConfigManager.getInstance(context).builtInProfileName(summary.basedOn))
    }
}

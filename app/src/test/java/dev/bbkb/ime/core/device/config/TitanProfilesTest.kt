package dev.bbkb.ime.core.device.config

import android.content.Context
import android.os.Build
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.R
import dev.bbkb.ime.core.device.config.model.DeviceInputMapping
import dev.bbkb.ime.core.device.config.model.DeviceQuirk
import dev.bbkb.ime.core.device.config.model.KeyRole
import dev.bbkb.ime.core.device.config.model.TouchKeypadConfig
import dev.bbkb.ime.core.device.config.parser.DeviceInputMappingParser
import dev.bbkb.ime.core.device.config.resolver.ScancodeMappingResolver
import dev.bbkb.ime.core.device.detection.KeypadLayoutDetector
import dev.bbkb.ime.core.keyevent.ResolvedKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * The five Unihertz Titan profiles: which shipped config each Titan's Build ids select (in the
 * order the app auto-selects, first match by display name), that every other device still selects
 * what it did, and the roles, pad facts, defaults and keypad layouts the profiles declare.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class TitanProfilesTest {

    private lateinit var context: Context

    private val buildFields = listOf("BRAND", "DEVICE", "BOARD", "DISPLAY", "MODEL", "MANUFACTURER")
    private val savedBuild = buildFields.associateWith {
        ReflectionHelpers.getStaticField<String>(Build::class.java, it)
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearBuild()
    }

    @After
    fun tearDown() {
        savedBuild.forEach { (field, value) -> ReflectionHelpers.setStaticField(Build::class.java, field, value) }
        ScancodeMappingResolver.getInstance().reset()
    }

    private fun clearBuild() = buildFields.forEach {
        ReflectionHelpers.setStaticField(Build::class.java, it, "unknown")
    }

    private fun build(vararg fields: Pair<String, String>) {
        clearBuild()
        fields.forEach { (field, value) -> ReflectionHelpers.setStaticField(Build::class.java, field, value) }
    }

    /**
     * The config the app would auto-select for a keyboard called [keyboardName]: the preloaded
     * configs in the manager's own order (display name), first match wins.
     */
    private fun selectedConfig(keyboardName: String): String? {
        val manager = CustomDeviceConfigManager.getInstance(context)
        for (info in manager.availableConfigs) {
            if (info.type != CustomDeviceConfigManager.ConfigType.PRELOADED) continue
            val config = DeviceInputMappingParser.parseConfigFromXmlResource(context, info.resourceId)
            if (config.findMappingForDevice(keyboardName) != null) return info.path
        }
        return null
    }

    /** Every preloaded config that would match, to prove the Titans' rules are disjoint. */
    private fun allMatching(keyboardName: String): List<String> {
        val manager = CustomDeviceConfigManager.getInstance(context)
        return manager.availableConfigs
            .filter { it.type == CustomDeviceConfigManager.ConfigType.PRELOADED }
            .filter {
                DeviceInputMappingParser.parseConfigFromXmlResource(context, it.resourceId)
                    .findMappingForDevice(keyboardName) != null
            }
            .map { it.path }
    }

    private fun mapping(resId: Int): DeviceInputMapping {
        val config = DeviceInputMappingParser.parseConfigFromXmlResource(context, resId)
        assertEquals(1, config.mappings.size)
        return config.mappings[0]
    }

    // ── selection ────────────────────────────────────────────────────────────

    @Test
    fun titan2_selectsTheTitan2Profile() {
        build("BRAND" to "Unihertz", "DEVICE" to "Titan_2", "BOARD" to "G71BoardV1",
            "MODEL" to "Titan 2", "DISPLAY" to "Titan 2_EEA_V01.00.14")
        assertEquals("device_config_titan2", selectedConfig("TitanKey"))
        assertEquals(listOf("device_config_titan2"), allMatching("TitanKey"))
    }

    @Test
    fun titan2Elite_selectsTheEliteProfile_notTheTitan2s() {
        build("BRAND" to "Unihertz", "DEVICE" to "Titan_2", "BOARD" to "G72BoardV1",
            "MODEL" to "Titan 2 Elite", "DISPLAY" to "Titan 2 Elite_EEA_20260301")
        assertEquals("device_config_titan2_elite", selectedConfig("TitanKey"))
        assertEquals(listOf("device_config_titan2_elite"), allMatching("TitanKey"))
    }

    @Test
    fun titan2_onAnUnknownBoard_selectsNeither() {
        build("BRAND" to "Unihertz", "DEVICE" to "Titan_2", "BOARD" to "G73BoardV1")
        assertNull(selectedConfig("TitanKey"))
    }

    @Test
    fun titan2019_selectsTheTitanProfile() {
        build("MANUFACTURER" to "A-gold", "MODEL" to "Titan", "DEVICE" to "Titan",
            "BOARD" to "g61v71c2k_dfl_tee")
        assertEquals("device_config_titan", selectedConfig("aw9523-key"))
        assertEquals(listOf("device_config_titan"), allMatching("aw9523-key"))
    }

    @Test
    fun titanPocket_selectsThePocketProfile() {
        build("MANUFACTURER" to "A-gold", "MODEL" to "Titan Pocket")
        assertEquals("device_config_titan_pocket", selectedConfig("aw9523-key"))
        assertEquals(listOf("device_config_titan_pocket"), allMatching("aw9523-key"))
    }

    @Test
    fun titanSlim_selectsTheSlimProfile() {
        build("MANUFACTURER" to "A-gold", "MODEL" to "Titan Slim")
        assertEquals("device_config_titan_slim", selectedConfig("aw9523-key"))
        assertEquals(listOf("device_config_titan_slim"), allMatching("aw9523-key"))
    }

    @Test
    fun theOtherDevices_selectWhatTheyAlwaysDid() {
        build("BRAND" to "blackberry", "DEVICE" to "athena")
        assertEquals("device_config_athena", selectedConfig("stmpe_keypad"))
        build("BRAND" to "Minimal", "DEVICE" to "MP01")
        assertEquals("device_config_minimal", selectedConfig("aw9523b-key"))
        build("BRAND" to "zinwa", "DEVICE" to "Q25")
        assertEquals("device_config_q25", selectedConfig("q25"))
        // The Minimal Phone's keypad name is one letter off the Titans'; no Titan rule takes it.
        assertEquals(listOf("device_config_minimal"), allMatching("aw9523b-key"))
    }

    // ── the Titan 2 profile's contents ───────────────────────────────────────

    @Test
    fun titan2_rolesPadAndDefaults() {
        val m = mapping(R.xml.device_config_titan2)
        assertTrue(m.forcePkbDevice)
        assertFalse("not forced CKB: its pad events must actually arrive", m.forceTouchKeypad)
        assertEquals("device_alt_mappings_titan2", m.altMappingsFile)
        assertNull("no KDB variant yet: swipe typing stays off", m.kdbVariant)
        assertFalse(m.hasQuirk(DeviceQuirk.FN_NO_KEY_UP))

        val pad = m.touchKeypad!!
        assertTrue(pad.matchesInputDeviceName("touchPad"))
        assertFalse(pad.matchesInputDeviceName("sub_touch"))
        assertEquals(1440, pad.rangeX)
        assertEquals(720, pad.rangeY)
        assertEquals(TouchKeypadConfig.Contacts.SINGLE, pad.contacts)
        assertEquals(36, pad.nativeMinSdk)
        assertEquals(TouchKeypadConfig.SourcePreference.AUTO, pad.source)

        val resolver = ScancodeMappingResolver.getInstance()
        resolver.initialize(m)
        assertEquals(KeyRole.BOARD_SYM, resolver.resolve(253, android.view.KeyEvent.KEYCODE_SYM)!!.role)
        val fn = resolver.resolve(251, android.view.KeyEvent.KEYCODE_CTRL_LEFT)!!
        assertEquals(KeyRole.MODIFIER, fn.role)
        assertEquals("KEYCODE_CTRL_LEFT", fn.treatAs)
        for (func in intArrayOf(249, 250)) {
            val mf = resolver.resolve(func, 0)!!
            assertEquals(KeyRole.FUNCTION, mf.role)
            assertEquals("no board on the Func keys", 0, mf.boardId)
        }
        assertEquals(KeyRole.MODIFIER, resolver.resolve(100, android.view.KeyEvent.KEYCODE_ALT_RIGHT)!!.role)
        assertEquals(KeyRole.GESTURE_SWIPE_LEFT, resolver.resolve(0, 322)!!.role)
        assertEquals(KeyRole.GESTURE_SWIPE_LEFT, resolver.resolve(0, 404)!!.role)

        val doubleTap = m.getSettingOverride("ckb_gesture_double_tap")!!
        assertEquals("none", doubleTap.getDefaultStringValue())
        assertFalse("a default the user can change, not a forced value", doubleTap.hasForcedValue())
        assertFalse(doubleTap.readOnly)
    }

    /**
     * Alt arrives as ALT_RIGHT on scancode 100 (ALT_LEFT on 56 on the Elite): the key pipeline must
     * read it as Alt — not as a board key, not as the Sym key — with the profile installed.
     */
    @Test
    fun titanAltKeys_areAltToTheKeyPipeline() {
        for ((resId, alt) in listOf(
            R.xml.device_config_titan2 to (KeyEvent.KEYCODE_ALT_RIGHT to 100),
            R.xml.device_config_titan2_elite to (KeyEvent.KEYCODE_ALT_LEFT to 56),
            R.xml.device_config_titan to (KeyEvent.KEYCODE_ALT_RIGHT to 100),
            R.xml.device_config_titan_pocket to (KeyEvent.KEYCODE_ALT_RIGHT to 100),
        )) {
            val resolver = ScancodeMappingResolver.getInstance()
            resolver.initialize(mapping(resId))
            val key = ResolvedKey.forTest(alt.first, alt.second, true,
                resolver.resolve(alt.second, alt.first))
            assertTrue(key.isAltKey())
            assertFalse(key.isBoardKey())
            assertFalse(key.isSymKey())
            resolver.reset()
        }
    }

    @Test
    fun titan2Elite_altLeftQuirkAndPad() {
        val m = mapping(R.xml.device_config_titan2_elite)
        assertTrue(m.hasQuirk(DeviceQuirk.FN_NO_KEY_UP))
        assertEquals("device_alt_mappings_titan2_elite", m.altMappingsFile)
        assertEquals(1080, m.touchKeypad!!.rangeX)
        assertEquals(600, m.touchKeypad!!.rangeY)
        assertEquals(36, m.touchKeypad!!.nativeMinSdk)
        val resolver = ScancodeMappingResolver.getInstance()
        resolver.initialize(m)
        assertEquals(KeyRole.MODIFIER, resolver.resolve(56, android.view.KeyEvent.KEYCODE_ALT_LEFT)!!.role)
        assertNull("the Elite's Alt is not on the Titan 2's scancode", resolver.resolve(100, 0))
        assertEquals("none", m.getSettingOverride("ckb_gesture_double_tap")!!.getDefaultStringValue())
    }

    @Test
    fun titan2019_noSymKey_redKeyIsFunction_padHasNoNativeMinSdk() {
        val m = mapping(R.xml.device_config_titan)
        val resolver = ScancodeMappingResolver.getInstance()
        resolver.initialize(m)
        assertTrue("no Sym key is invented",
            m.scancodeMappings.none { it.role == KeyRole.BOARD_SYM })
        assertEquals(KeyRole.FUNCTION, resolver.resolve(249, 0)!!.role)
        assertEquals(KeyRole.FUNCTION, resolver.resolve(250, 0)!!.role)
        assertEquals(KeyRole.MODIFIER, resolver.resolve(100, 0)!!.role)
        assertTrue(m.touchKeypad!!.matchesInputDeviceName("mtk-pad"))
        assertFalse(m.touchKeypad!!.hasNativeMinSdk())
        assertEquals("device_alt_mappings_titan", m.altMappingsFile)
        assertNull("the older ROMs have no Cursor assistant: double-tap keeps its default",
            m.getSettingOverride("ckb_gesture_double_tap"))
    }

    @Test
    fun pocketAndSlim_shareKeysAndAltTable() {
        for (resId in intArrayOf(R.xml.device_config_titan_pocket, R.xml.device_config_titan_slim)) {
            val m = mapping(resId)
            val resolver = ScancodeMappingResolver.getInstance()
            resolver.initialize(m)
            assertEquals(KeyRole.BOARD_SYM, resolver.resolve(127, 0)!!.role)
            assertEquals(KeyRole.FUNCTION, resolver.resolve(183, 0)!!.role)
            assertEquals(KeyRole.FUNCTION, resolver.resolve(249, 0)!!.role)
            assertEquals(KeyRole.FUNCTION, resolver.resolve(250, 0)!!.role)
            assertEquals(KeyRole.MODIFIER, resolver.resolve(100, 0)!!.role)
            assertEquals("device_alt_mappings_titan_pocket", m.altMappingsFile)
            assertEquals(720, m.touchKeypad!!.rangeX)
            assertEquals(360, m.touchKeypad!!.rangeY)
            assertTrue(m.touchKeypad!!.matchesInputDeviceName("mtk-pad"))
            resolver.reset()
        }
    }

    // ── keypad layout ────────────────────────────────────────────────────────

    /**
     * The declared layout is what live detection already answered on these single-layout models
     * (qwerty), so the effective layout is unchanged — it is now simply not left to detection.
     */
    @Test
    fun singleLayoutProfiles_effectiveKeypadLayoutIsQwerty() {
        for (resId in intArrayOf(
            R.xml.device_config_minimal, R.xml.device_config_q25, R.xml.device_config_titan,
            R.xml.device_config_titan2, R.xml.device_config_titan2_elite,
            R.xml.device_config_titan_pocket, R.xml.device_config_titan_slim,
        )) {
            val m = mapping(resId)
            val withDeclaration = KeypadLayoutDetector.detect(m.keypadLayout, null, null, null, null)
            assertEquals(KeypadLayoutDetector.QWERTY, withDeclaration.layout)
            assertEquals(KeypadLayoutDetector.Source.DEVICE_CONFIG, withDeclaration.source)
            val withoutDeclaration = KeypadLayoutDetector.detect(null, null, null, null, null)
            assertEquals("unchanged: what detection answers with no other evidence",
                withoutDeclaration.layout, withDeclaration.layout)
        }
        assertNull("athena still leaves the layout to detection",
            mapping(R.xml.device_config_athena).keypadLayout)
    }
}

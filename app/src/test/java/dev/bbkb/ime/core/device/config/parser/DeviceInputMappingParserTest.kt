package dev.bbkb.ime.core.device.config.parser

import dev.bbkb.ime.core.device.config.model.DeviceInputMapping
import dev.bbkb.ime.core.device.config.model.DeviceSettingOverride
import dev.bbkb.ime.core.device.config.model.KeyRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [DeviceInputMappingParser], the parser behind device_config_*.xml /
 * device_input_config.xml. Uses [DeviceInputMappingParser.parseConfigFromStream] with inline
 * XML so the tests cover exactly what a config author can write, including the newer
 * `<kdb-variant>` and `<ckb-key-grid>` elements.
 *
 * Runs under Robolectric for android.util.Xml (a real XmlPullParser on the JVM).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class DeviceInputMappingParserTest {

    private fun parse(xml: String) =
        DeviceInputMappingParser.parseConfigFromStream(xml.byteInputStream())

    private fun parseSingle(deviceBody: String): DeviceInputMapping {
        val config = parse("""<?xml version="1.0" encoding="utf-8"?>
            <device-input-config><device>$deviceBody</device></device-input-config>""")
        assertEquals("expected exactly one parsed device", 1, config.mappings.size)
        return config.mappings[0]
    }

    // ── device matching ──────────────────────────────────────────────────────

    @Test
    fun exactDeviceNameMatch_parsesAndMatches() {
        val m = parseSingle("""<match><device-name exact="stmpe_keypad"/></match>""")
        assertNotNull(m.matchCriteria)
        assertTrue(m.matchCriteria.matches("stmpe_keypad", null, null))
        assertFalse(m.matchCriteria.matches("other_keypad", null, null))
    }

    @Test
    fun regexDeviceNameMatch_parsesAndMatches() {
        val m = parseSingle("""<match><device-name regex=".*keypad.*"/></match>""")
        assertTrue(m.matchCriteria.matches("stmpe_keypad", null, null))
        assertFalse(m.matchCriteria.matches("touchscreen", null, null))
    }

    @Test
    fun deviceWithoutNameOrCriteria_isDropped() {
        val config = parse("""<device-input-config><device>
            <device-type>PKB</device-type></device></device-input-config>""")
        assertTrue(config.mappings.isEmpty())
    }

    @Test
    fun deviceTypeCkb_forcesPkbAndTouchKeypad() {
        val m = parseSingle("""<match><device-name exact="d"/></match>
            <device-type>CKB</device-type>""")
        assertTrue(m.forcePkbDevice)
        assertTrue(m.forceTouchKeypad)
    }

    @Test
    fun deviceTypePkb_forcesPkbOnly() {
        val m = parseSingle("""<match><device-name exact="d"/></match>
            <device-type>PKB</device-type>""")
        assertTrue(m.forcePkbDevice)
        assertFalse(m.forceTouchKeypad)
    }

    // ── kdb-variant (device KDB variant feature) ─────────────────────────────

    @Test
    fun kdbVariant_parsedAndTrimmed() {
        val m = parseSingle("""<match><device-name exact="d"/></match>
            <kdb-variant>  athena  </kdb-variant>""")
        assertEquals("athena", m.kdbVariant)
    }

    @Test
    fun kdbVariant_absent_isNull() {
        val m = parseSingle("""<match><device-name exact="d"/></match>""")
        assertNull(m.kdbVariant)
    }

    @Test
    fun kdbVariant_blank_isIgnored() {
        val m = parseSingle("""<match><device-name exact="d"/></match>
            <kdb-variant>   </kdb-variant>""")
        assertNull(m.kdbVariant)
    }

    // ── input mappings ───────────────────────────────────────────────────────

    @Test
    fun layoutOverrides_parsedIntoMap() {
        val m = parseSingle("""<match><device-name exact="d"/></match>
            <input-mappings><layout-overrides>
                <override from="qwerty" to="qwerty_pkb"/>
                <override from="azerty" to="azerty_pkb"/>
            </layout-overrides></input-mappings>""")
        assertEquals("qwerty_pkb", m.layoutOverrides["qwerty"])
        assertEquals("azerty_pkb", m.layoutOverrides["azerty"])
    }

    @Test
    fun keypadType_parsed() {
        val m = parseSingle("""<match><device-name exact="d"/></match>
            <input-mappings><keypad-type>qwerty</keypad-type></input-mappings>""")
        assertEquals("qwerty", m.keypadType)
    }

    @Test
    fun scancodeMapping_withRoleAndScanCode_parsed() {
        val m = parseSingle("""<match><device-name exact="d"/></match>
            <input-mappings><scancode-mappings>
                <key rawScanCode="42" role="MODIFIER" altChar="#"/>
            </scancode-mappings></input-mappings>""")
        assertEquals(1, m.scancodeMappings.size)
        assertEquals(42, m.scancodeMappings[0].rawScanCode)
        assertEquals('#', m.scancodeMappings[0].altChar)
    }

    @Test
    fun scancodeMapping_missingRole_isDropped() {
        val m = parseSingle("""<match><device-name exact="d"/></match>
            <input-mappings><scancode-mappings>
                <key rawScanCode="42"/>
            </scancode-mappings></input-mappings>""")
        assertTrue(m.scancodeMappings.isEmpty())
    }

    // ── multifunction key ────────────────────────────────────────────────────

    @Test
    fun multifunctionKey_parsesRoleDefaultActionAndAltChar() {
        val m = parseSingle("""<match><device-name exact="d"/></match>
            <input-mappings><scancode-mappings>
                <key rawKeyCode="7" role="MULTIFUNCTION" altChar="0" default-action="voice_input"/>
            </scancode-mappings></input-mappings>""")
        assertEquals(1, m.scancodeMappings.size)
        val k = m.scancodeMappings[0]
        assertEquals(KeyRole.MULTIFUNCTION, k.role)
        assertEquals("voice_input", k.defaultAction)
        assertEquals('0', k.altChar)
        assertEquals(7, k.rawKeyCode)
        // MULTIFUNCTION must reach the IME pipeline, never the accessibility interceptor
        assertFalse(k.role.isConsumedAtAccessibilityLevel())
        // The settings screen finds the key through this helper
        assertSame(k, m.getMultifunctionKeyMapping())
    }

    @Test
    fun multifunctionKey_defaultActionOptional() {
        val m = parseSingle("""<match><device-name exact="d"/></match>
            <input-mappings><scancode-mappings>
                <key rawKeyCode="7" role="MULTIFUNCTION"/>
            </scancode-mappings></input-mappings>""")
        assertEquals(KeyRole.MULTIFUNCTION, m.scancodeMappings[0].role)
        assertNull(m.scancodeMappings[0].defaultAction)
    }

    @Test
    fun multifunctionKeyMapping_absent_returnsNull() {
        val m = parseSingle("""<match><device-name exact="d"/></match>
            <input-mappings><scancode-mappings>
                <key rawKeyCode="7" role="BOARD_VOICE"/>
            </scancode-mappings></input-mappings>""")
        assertNull(m.getMultifunctionKeyMapping())
    }

    // ── settings overrides ───────────────────────────────────────────────────

    @Test
    fun booleanSettingOverride_parsed() {
        val m = parseSingle("""<match><device-name exact="d"/></match>
            <settings-overrides>
                <setting key="type_by_swiping_ckb" type="boolean" forced-value="true" read-only="true" hidden="true"/>
            </settings-overrides>""")
        assertEquals(1, m.settingsOverrides.size)
        val o = m.settingsOverrides[0]
        assertEquals("type_by_swiping_ckb", o.key)
        assertEquals(DeviceSettingOverride.SettingType.BOOLEAN, o.type)
        assertEquals(true, o.forcedValue)
        assertTrue(o.readOnly)
        assertTrue(o.hidden)
    }

    @Test
    fun settingOverride_unknownType_isDropped() {
        val m = parseSingle("""<match><device-name exact="d"/></match>
            <settings-overrides>
                <setting key="k" type="floatish" forced-value="1"/>
            </settings-overrides>""")
        assertTrue(m.settingsOverrides.isEmpty())
    }

    // ── ckb-key-grid ─────────────────────────────────────────────────────────

    @Test
    fun ckbKeyGrid_parsedWithDimensionsAndCells() {
        val m = parseSingle("""<match><device-name exact="d"/></match>
            <ckb-key-grid width="1080" height="525">
                <key label="q" cx="0.05" cy="0.17" w="0.1" h="0.33"/>
                <key label="w" cx="0.15" cy="0.17" w="0.1" h="0.33"/>
            </ckb-key-grid>""")
        assertNotNull(m.ckbKeyGridConfig)
        assertEquals(1080, m.ckbKeyGridConfig.width)
        assertEquals(525, m.ckbKeyGridConfig.height)
        assertEquals(2, m.ckbKeyGridConfig.cells.size)
        assertEquals("q", m.ckbKeyGridConfig.cells[0].label)
        assertEquals(0.05f, m.ckbKeyGridConfig.cells[0].cx, 1e-6f)
    }

    @Test
    fun ckbKeyGrid_empty_isNull() {
        val m = parseSingle("""<match><device-name exact="d"/></match>
            <ckb-key-grid width="1080" height="525"/>""")
        assertNull(m.ckbKeyGridConfig)
    }

    // ── multiple devices ─────────────────────────────────────────────────────

    @Test
    fun multipleDevices_allParsed() {
        val config = parse("""<device-input-config>
            <device><match><device-name exact="a"/></match><kdb-variant>athena</kdb-variant></device>
            <device><match><device-name exact="b"/></match></device>
        </device-input-config>""")
        assertEquals(2, config.mappings.size)
        assertEquals("athena", config.mappings[0].kdbVariant)
        assertNull(config.mappings[1].kdbVariant)
    }
}

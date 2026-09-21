package dev.bbkb.ime.core.device.config.parser

import android.os.Build
import dev.bbkb.ime.core.device.config.model.DeviceInputMapping
import dev.bbkb.ime.core.device.config.model.DeviceSettingOverride
import dev.bbkb.ime.core.device.config.model.KeyRole
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * Characterisation tests for [DeviceInputMappingParser] and the model types it fills.
 *
 * Written before the W3-D parser/model rewrite to pin the schema paths that
 * [DeviceInputMappingParserTest] does not reach: `<layout-alt-overrides>`, `<alt-mappings>`
 * (including `<enabled>false`), `<ckb-y-warp>`, unrecognised `<device-type>` values, the
 * `vendor-id` / `product-id` / `brand` / `build-device` match rules, exact-vs-regex precedence,
 * invalid regex, the non-boolean setting-override types, the scancode attribute error paths and
 * [DeviceInputMappingParser.parseDisplayName].
 *
 * These assertions describe the parser as it behaved *before* the rewrite. They must keep passing
 * verbatim; a failure here means the rewrite changed behaviour.
 *
 * Runs under Robolectric for android.util.Xml (a real XmlPullParser on the JVM).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class DeviceInputMappingParserCharacterisationTest {

    private val originalBrand: String = Build.BRAND
    private val originalDevice: String = Build.DEVICE

    @After
    fun restoreBuildFields() {
        setBrand(originalBrand)
        setBuildDevice(originalDevice)
    }

    private fun setBrand(value: String?) =
        ReflectionHelpers.setStaticField(Build::class.java, "BRAND", value)

    private fun setBuildDevice(value: String?) =
        ReflectionHelpers.setStaticField(Build::class.java, "DEVICE", value)

    private fun parse(xml: String) =
        DeviceInputMappingParser.parseConfigFromStream(xml.byteInputStream())

    private fun parseSingle(deviceBody: String): DeviceInputMapping {
        val config = parse(
            """<?xml version="1.0" encoding="utf-8"?>
            <device-input-config><device>$deviceBody</device></device-input-config>"""
        )
        assertEquals("expected exactly one parsed device", 1, config.mappings.size)
        return config.mappings[0]
    }

    private fun named(body: String) = parseSingle("""<match><device-name exact="d"/></match>$body""")

    // ── <device-type> ────────────────────────────────────────────────────────

    @Test
    fun deviceType_unrecognisedValue_forcesNothing() {
        val m = named("<device-type>VKB</device-type>")
        assertFalse(m.forcePkbDevice)
        assertFalse(m.forceTouchKeypad)
    }

    @Test
    fun deviceType_isCaseInsensitive() {
        val m = named("<device-type>ckb</device-type>")
        assertTrue(m.forcePkbDevice)
        assertTrue(m.forceTouchKeypad)
    }

    @Test
    fun deviceType_absent_forcesNothing() {
        val m = named("")
        assertFalse(m.forcePkbDevice)
        assertFalse(m.forceTouchKeypad)
    }

    // ── <ckb-y-warp> ─────────────────────────────────────────────────────────

    @Test
    fun ckbYWarp_parsedAndTrimmed() {
        val m = named("<ckb-y-warp>  0:0,450:450  </ckb-y-warp>")
        assertEquals("0:0,450:450", m.ckbYWarp)
    }

    @Test
    fun ckbYWarp_blank_isIgnored() {
        val m = named("<ckb-y-warp>   </ckb-y-warp>")
        assertNull(m.ckbYWarp)
    }

    @Test
    fun ckbYWarp_absent_isNull() {
        assertNull(named("").ckbYWarp)
    }

    // ── <keypad-layout> ──────────────────────────────────────────────────────

    @Test
    fun keypadLayout_acceptsTheThreeLayouts() {
        assertEquals("qwerty", named("<keypad-layout>qwerty</keypad-layout>").keypadLayout)
        assertEquals("qwertz", named("<keypad-layout>qwertz</keypad-layout>").keypadLayout)
        assertEquals("azerty", named("<keypad-layout>azerty</keypad-layout>").keypadLayout)
    }

    @Test
    fun keypadLayout_isTrimmedAndLowercased() {
        assertEquals("qwertz", named("<keypad-layout>  QWERTZ  </keypad-layout>").keypadLayout)
    }

    @Test
    fun keypadLayout_rejectsAnythingElse() {
        // A rejected value must leave the field null so KeypadLayoutDetector falls through to the
        // live firmware sources, rather than pinning the device to a layout nothing matches.
        assertNull(named("<keypad-layout>dvorak</keypad-layout>").keypadLayout)
        assertNull(named("<keypad-layout>qwerty-uk</keypad-layout>").keypadLayout)
        assertNull(named("<keypad-layout></keypad-layout>").keypadLayout)
        assertNull(named("<keypad-layout>   </keypad-layout>").keypadLayout)
    }

    @Test
    fun keypadLayout_absent_isNull() {
        assertNull(named("").keypadLayout)
    }

    @Test
    fun keypadLayout_doesNotDisturbTheRestOfTheDevice() {
        val m = named("<keypad-layout>azerty</keypad-layout><ckb-y-warp>0:0,450:324</ckb-y-warp>")
        assertEquals("azerty", m.keypadLayout)
        assertEquals("0:0,450:324", m.ckbYWarp)
    }

    // ── <alt-mappings> ───────────────────────────────────────────────────────

    @Test
    fun altMappings_sourceSetsCustomTypeAndFile() {
        val m = named(
            """<input-mappings><alt-mappings>
                <source>device_alt_mappings_athena</source>
            </alt-mappings></input-mappings>"""
        )
        assertEquals(DeviceInputMapping.InputMappingType.CUSTOM_ALT_MAPPINGS, m.mappingType)
        assertEquals("device_alt_mappings_athena", m.altMappingsFile)
    }

    @Test
    fun altMappings_enabledFalse_revertsTypeToDefaultButKeepsFile() {
        val m = named(
            """<input-mappings><alt-mappings>
                <source>device_alt_mappings_athena</source>
                <enabled>false</enabled>
            </alt-mappings></input-mappings>"""
        )
        assertEquals(DeviceInputMapping.InputMappingType.DEFAULT, m.mappingType)
        assertEquals("device_alt_mappings_athena", m.altMappingsFile)
    }

    @Test
    fun altMappings_enabledTrue_keepsCustomType() {
        val m = named(
            """<input-mappings><alt-mappings>
                <enabled>true</enabled>
                <source>device_alt_mappings_athena</source>
            </alt-mappings></input-mappings>"""
        )
        assertEquals(DeviceInputMapping.InputMappingType.CUSTOM_ALT_MAPPINGS, m.mappingType)
    }

    @Test
    fun altMappings_emptyElement_stillFlipsTypeToCustom() {
        val m = named("<input-mappings><alt-mappings/></input-mappings>")
        assertEquals(DeviceInputMapping.InputMappingType.CUSTOM_ALT_MAPPINGS, m.mappingType)
        assertNull(m.altMappingsFile)
    }

    @Test
    fun altMappings_absent_leavesDefaultType() {
        assertEquals(DeviceInputMapping.InputMappingType.DEFAULT, named("").mappingType)
    }

    // ── <layout-alt-overrides> ───────────────────────────────────────────────

    @Test
    fun layoutAltOverrides_parsedIntoMap() {
        val m = named(
            """<input-mappings><layout-alt-overrides>
                <override layout="azerty" alt-mappings="device_alt_mappings_azerty_overrides"/>
                <override layout="qwertz" alt-mappings="device_alt_mappings_qwertz_overrides"/>
            </layout-alt-overrides></input-mappings>"""
        )
        assertEquals(2, m.layoutAltOverrides.size)
        assertEquals("device_alt_mappings_azerty_overrides", m.layoutAltOverrides["azerty"])
        assertEquals("device_alt_mappings_qwertz_overrides", m.layoutAltOverrides["qwertz"])
    }

    @Test
    fun layoutAltOverrides_missingAttribute_isDropped() {
        val m = named(
            """<input-mappings><layout-alt-overrides>
                <override layout="azerty"/>
                <override alt-mappings="orphan"/>
            </layout-alt-overrides></input-mappings>"""
        )
        assertTrue(m.layoutAltOverrides.isEmpty())
    }

    @Test
    fun layoutOverrides_missingAttribute_isDropped() {
        val m = named(
            """<input-mappings><layout-overrides>
                <override from="qwerty"/>
                <override to="orphan"/>
            </layout-overrides></input-mappings>"""
        )
        assertTrue(m.layoutOverrides.isEmpty())
    }

    @Test
    fun layoutOverride_lookupHelper_returnsMappedName() {
        val m = named(
            """<input-mappings><layout-overrides>
                <override from="rows_symbols" to="rows_symbols_pkb"/>
            </layout-overrides></input-mappings>"""
        )
        assertEquals("rows_symbols_pkb", m.getLayoutOverride("rows_symbols"))
        assertNull(m.getLayoutOverride("rows_symbols_shifted"))
        assertNull(m.getLayoutOverride(null))
    }

    // ── <match> rules beyond device-name ─────────────────────────────────────

    @Test
    fun vendorAndProductId_exactRules_areAnded() {
        val m = named("")
        assertNotNull(m.matchCriteria)
        val full = parseSingle(
            """<match>
                <device-name exact="kbd"/>
                <vendor-id exact="0x0fca"/>
                <product-id exact="0x8010"/>
            </match>"""
        )
        assertTrue(full.matchCriteria.matches("kbd", "0x0fca", "0x8010"))
        assertFalse(full.matchCriteria.matches("kbd", "0x0fca", "0x8011"))
        assertFalse(full.matchCriteria.matches("kbd", "0xdead", "0x8010"))
        assertFalse(full.matchCriteria.matches("other", "0x0fca", "0x8010"))
    }

    @Test
    fun vendorId_regexRule_matches() {
        val m = parseSingle("""<match><vendor-id regex="0x0f.*"/></match>""")
        assertTrue(m.matchCriteria.matches(null, "0x0fca", null))
        assertFalse(m.matchCriteria.matches(null, "0x1234", null))
    }

    @Test
    fun unsetCriterion_isDontCare() {
        val m = parseSingle("""<match><vendor-id exact="0x0fca"/></match>""")
        assertTrue(m.matchCriteria.matches(null, "0x0fca", null))
    }

    @Test
    fun nullValue_againstSetRule_neverMatches() {
        val m = parseSingle("""<match><product-id exact="0x8010"/></match>""")
        assertFalse(m.matchCriteria.matches(null, null, null))
    }

    @Test
    fun exactAttribute_winsOverRegexOnTheSameElement() {
        val m = parseSingle("""<match><device-name exact="literal" regex=".*"/></match>""")
        assertTrue(m.matchCriteria.matches("literal", null, null))
        assertFalse(m.matchCriteria.matches("anything else", null, null))
        assertEquals("literal", m.deviceName)
    }

    @Test
    fun regexDeviceName_isRecordedForLoggingWithPrefix() {
        val m = parseSingle("""<match><device-name regex=".*keypad"/></match>""")
        assertEquals("regex:.*keypad", m.deviceName)
    }

    @Test
    fun matchElementWithNeitherAttribute_isIgnored() {
        val config = parse(
            """<device-input-config><device>
                <match><device-name/></match>
            </device></device-input-config>"""
        )
        assertTrue("no criteria and no name => device dropped", config.mappings.isEmpty())
    }

    @Test
    fun unknownMatchChild_isIgnored() {
        val m = parseSingle(
            """<match>
                <device-name exact="d"/>
                <colour exact="red"/>
            </match>"""
        )
        assertTrue(m.matchCriteria.matches("d", null, null))
    }

    @Test
    fun invalidRegex_matchesNothing() {
        val m = parseSingle("""<match><device-name regex="([unclosed"/></match>""")
        assertNotNull(m.matchCriteria)
        assertFalse(m.matchCriteria.matches("([unclosed", null, null))
        assertFalse(m.matchCriteria.matches("anything", null, null))
    }

    @Test
    fun brandExactRule_isCaseInsensitiveAgainstBuildBrand() {
        val m = parseSingle("""<match><brand exact="BlackBerry"/></match>""")
        setBrand("blackberry")
        assertTrue(m.matchCriteria.matches(null, null, null))
        setBrand("Unihertz")
        assertFalse(m.matchCriteria.matches(null, null, null))
        setBrand(null)
        assertFalse(m.matchCriteria.matches(null, null, null))
    }

    @Test
    fun brandRegexRule_isCaseSensitive() {
        val m = parseSingle("""<match><brand regex="Black.*"/></match>""")
        setBrand("BlackBerry")
        assertTrue(m.matchCriteria.matches(null, null, null))
        setBrand("blackberry")
        assertFalse(m.matchCriteria.matches(null, null, null))
    }

    @Test
    fun buildDeviceExactRule_isCaseInsensitiveAgainstBuildDevice() {
        val m = parseSingle("""<match><build-device exact="athena"/></match>""")
        setBuildDevice("ATHENA")
        assertTrue(m.matchCriteria.matches(null, null, null))
        setBuildDevice("bbf100")
        assertFalse(m.matchCriteria.matches(null, null, null))
        setBuildDevice(null)
        assertFalse(m.matchCriteria.matches(null, null, null))
    }

    @Test
    fun buildDeviceRegexRule_isCaseSensitive() {
        val m = parseSingle("""<match><build-device regex="athe.*"/></match>""")
        setBuildDevice("athena")
        assertTrue(m.matchCriteria.matches(null, null, null))
        setBuildDevice("ATHENA")
        assertFalse(m.matchCriteria.matches(null, null, null))
    }

    @Test
    fun matchCriteria_isDroppedWhenNoRuleParsed_butDeviceKeptWhenNamed() {
        // <match> present but empty: hasAnyCriteria() false, so matchCriteria stays null and the
        // device is dropped for want of a name too.
        val config = parse("""<device-input-config><device><match/></device></device-input-config>""")
        assertTrue(config.mappings.isEmpty())
    }

    // ── settings overrides ───────────────────────────────────────────────────

    @Test
    fun integerSettingOverride_parsed() {
        val m = named(
            """<settings-overrides>
                <setting key="k" type="integer" forced-value="42"/>
            </settings-overrides>"""
        )
        val o = m.settingsOverrides[0]
        assertEquals(DeviceSettingOverride.SettingType.INTEGER, o.type)
        assertEquals(42, o.forcedValue)
        assertEquals(Integer.valueOf(42), o.getForcedIntegerValue())
        assertNull(o.getForcedBooleanValue())
        assertNull(o.getForcedStringValue())
        assertFalse(o.readOnly)
        assertFalse(o.hidden)
    }

    @Test
    fun integerSettingOverride_unparseableValue_keepsTypeAndLeavesValueNull() {
        val m = named(
            """<settings-overrides>
                <setting key="k" type="integer" forced-value="forty-two"/>
            </settings-overrides>"""
        )
        val o = m.settingsOverrides[0]
        assertEquals(DeviceSettingOverride.SettingType.INTEGER, o.type)
        assertNull(o.forcedValue)
        assertFalse(o.hasForcedValue())
    }

    @Test
    fun stringAndListSettingOverrides_parsed() {
        val m = named(
            """<settings-overrides>
                <setting key="s" type="string" forced-value="hello"/>
                <setting key="l" type="LIST" forced-value="two"/>
            </settings-overrides>"""
        )
        assertEquals(2, m.settingsOverrides.size)
        assertEquals(DeviceSettingOverride.SettingType.STRING, m.settingsOverrides[0].type)
        assertEquals("hello", m.settingsOverrides[0].getForcedStringValue())
        assertEquals(DeviceSettingOverride.SettingType.LIST, m.settingsOverrides[1].type)
        assertEquals("two", m.settingsOverrides[1].getForcedStringValue())
    }

    @Test
    fun booleanSettingOverride_nonTrueValueIsFalse() {
        val m = named(
            """<settings-overrides>
                <setting key="k" type="boolean" forced-value="yes"/>
            </settings-overrides>"""
        )
        assertEquals(false, m.settingsOverrides[0].forcedValue)
    }

    @Test
    fun settingOverride_withoutForcedValue_isReadOnlyOnly() {
        val m = named(
            """<settings-overrides>
                <setting key="k" type="boolean" read-only="true"/>
            </settings-overrides>"""
        )
        val o = m.settingsOverrides[0]
        assertNull(o.forcedValue)
        assertFalse(o.hasForcedValue())
        assertTrue(o.readOnly)
    }

    @Test
    fun settingOverride_missingKeyOrType_isDropped() {
        val m = named(
            """<settings-overrides>
                <setting type="boolean" forced-value="true"/>
                <setting key="k"/>
            </settings-overrides>"""
        )
        assertTrue(m.settingsOverrides.isEmpty())
    }

    @Test
    fun settingOverride_lookupHelpers() {
        val m = named(
            """<settings-overrides>
                <setting key="present" type="boolean" forced-value="true"/>
            </settings-overrides>"""
        )
        assertNotNull(m.getSettingOverride("present"))
        assertTrue(m.hasSettingOverride("present"))
        assertNull(m.getSettingOverride("absent"))
        assertFalse(m.hasSettingOverride("absent"))
        assertNull(m.getSettingOverride(null))
    }

    // ── scancode mappings ────────────────────────────────────────────────────

    @Test
    fun scancodeMapping_allAttributesParsed() {
        val m = named(
            """<input-mappings><scancode-mappings>
                <key rawScanCode="110" rawKeyCode="119" treatAs="KEYCODE_SYM" role="BOARD_SYM"
                     altChar="0" board="-11" default-action="voice_input" notes="mic key"/>
            </scancode-mappings></input-mappings>"""
        )
        val k = m.scancodeMappings[0]
        assertEquals(110, k.rawScanCode)
        assertEquals(119, k.rawKeyCode)
        assertEquals("KEYCODE_SYM", k.treatAs)
        assertEquals(KeyRole.BOARD_SYM, k.role)
        assertEquals('0', k.altChar)
        assertEquals(-11, k.boardId)
        assertEquals("voice_input", k.defaultAction)
        assertEquals("mic key", k.notes)
        assertTrue(k.hasAltChar())
        assertTrue(k.role.isConsumedAtAccessibilityLevel())
        // both codes set => AND matching
        assertTrue(k.matches(110, 119))
        assertFalse(k.matches(110, 7))
    }

    @Test
    fun scancodeMapping_roleIsCaseInsensitive() {
        val m = named(
            """<input-mappings><scancode-mappings>
                <key rawKeyCode="7" role="board_voice"/>
            </scancode-mappings></input-mappings>"""
        )
        assertEquals(KeyRole.BOARD_VOICE, m.scancodeMappings[0].role)
    }

    @Test
    fun scancodeMapping_unknownRole_isDropped() {
        val m = named(
            """<input-mappings><scancode-mappings>
                <key rawKeyCode="7" role="TELEPORT"/>
            </scancode-mappings></input-mappings>"""
        )
        assertTrue(m.scancodeMappings.isEmpty())
    }

    @Test
    fun scancodeMapping_withNeitherCode_isDropped() {
        val m = named(
            """<input-mappings><scancode-mappings>
                <key role="CHARACTER"/>
            </scancode-mappings></input-mappings>"""
        )
        assertTrue(m.scancodeMappings.isEmpty())
    }

    @Test
    fun scancodeMapping_unparseableScanCode_dropsTheWholeKey() {
        val m = named(
            """<input-mappings><scancode-mappings>
                <key rawScanCode="oops" role="CHARACTER"/>
            </scancode-mappings></input-mappings>"""
        )
        assertTrue(m.scancodeMappings.isEmpty())
    }

    @Test
    fun scancodeMapping_unparseableKeyCode_dropsTheWholeKey() {
        val m = named(
            """<input-mappings><scancode-mappings>
                <key rawScanCode="42" rawKeyCode="oops" role="CHARACTER"/>
            </scancode-mappings></input-mappings>"""
        )
        assertTrue(m.scancodeMappings.isEmpty())
    }

    @Test
    fun scancodeMapping_unparseableBoardId_keepsTheKeyWithBoardZero() {
        val m = named(
            """<input-mappings><scancode-mappings>
                <key rawKeyCode="7" role="BOARD_VOICE" board="oops"/>
            </scancode-mappings></input-mappings>"""
        )
        assertEquals(1, m.scancodeMappings.size)
        assertEquals(0, m.scancodeMappings[0].boardId)
    }

    @Test
    fun scancodeMapping_emptyAltChar_leavesNoAltChar() {
        val m = named(
            """<input-mappings><scancode-mappings>
                <key rawKeyCode="7" role="BOARD_VOICE" altChar=""/>
            </scancode-mappings></input-mappings>"""
        )
        assertFalse(m.scancodeMappings[0].hasAltChar())
    }

    @Test
    fun scancodeMapping_multiCharAltChar_usesFirstCharacter() {
        val m = named(
            """<input-mappings><scancode-mappings>
                <key rawKeyCode="7" role="BOARD_VOICE" altChar="xyz"/>
            </scancode-mappings></input-mappings>"""
        )
        assertEquals('x', m.scancodeMappings[0].altChar)
    }

    @Test
    fun scancodeMapping_scanCodeOnly_ignoresKeyCode() {
        val m = named(
            """<input-mappings><scancode-mappings>
                <key rawScanCode="42" role="MODIFIER"/>
            </scancode-mappings></input-mappings>"""
        )
        val k = m.scancodeMappings[0]
        assertEquals(-1, k.rawKeyCode)
        assertTrue(k.matches(42, 999))
        assertFalse(k.matches(43, 999))
    }

    // ── ckb-key-grid edge cases ──────────────────────────────────────────────

    @Test
    fun ckbKeyGrid_missingDimensionsAndBadFloats_defaultToZero() {
        val m = named(
            """<ckb-key-grid>
                <key label="q" cx="nope" cy="0.17"/>
            </ckb-key-grid>"""
        )
        assertNotNull(m.ckbKeyGridConfig)
        assertEquals(0, m.ckbKeyGridConfig.width)
        assertEquals(0, m.ckbKeyGridConfig.height)
        assertEquals(1, m.ckbKeyGridConfig.cells.size)
        assertEquals(0f, m.ckbKeyGridConfig.cells[0].cx, 1e-6f)
        assertEquals(0.17f, m.ckbKeyGridConfig.cells[0].cy, 1e-6f)
        assertEquals(0f, m.ckbKeyGridConfig.cells[0].w, 1e-6f)
    }

    @Test
    fun ckbKeyGrid_cellWithoutLabel_isDropped() {
        val m = named(
            """<ckb-key-grid width="10" height="10">
                <key cx="0.1" cy="0.1"/>
                <key label="" cx="0.2" cy="0.2"/>
            </ckb-key-grid>"""
        )
        assertNull(m.ckbKeyGridConfig)
    }

    // ── document level ───────────────────────────────────────────────────────

    @Test
    fun unknownTopLevelElements_areIgnored() {
        val config = parse(
            """<device-input-config version="2.2">
                <metadata><author>someone</author></metadata>
                <device><match><device-name exact="a"/></match></device>
            </device-input-config>"""
        )
        assertEquals(1, config.mappings.size)
    }

    @Test
    fun unknownDeviceChildElements_areIgnored() {
        val m = named("<future-element><nested>x</nested></future-element>")
        assertEquals("d", m.deviceName)
    }

    // NOTE: there is deliberately no test for a truncated document inside a <device> element.
    // A document that ends with an element still open (e.g. "<device-input-config><device><match>")
    // makes the parser spin forever: Android's KXmlParser returns END_DOCUMENT from next() at EOF
    // instead of throwing, and every element loop terminates only on its own END_TAG. Recorded as
    // a defect by W3-D and deliberately left unfixed in this behaviour-neutral wave; a test for it
    // would hang the suite.

    @Test
    fun emptyDocument_yieldsEmptyConfig() {
        assertTrue(parse("").mappings.isEmpty())
    }

    // ── parseDisplayName ─────────────────────────────────────────────────────

    @Test
    fun displayName_fromRootAttributeOnDeviceInputConfig() {
        val name = DeviceInputMappingParser.parseDisplayName(
            """<device-input-config name="BlackBerry KEY2"><device/></device-input-config>"""
                .byteInputStream()
        )
        assertEquals("BlackBerry KEY2", name)
    }

    @Test
    fun displayName_fromRootAttributeOnDeviceMappings() {
        val name = DeviceInputMappingParser.parseDisplayName(
            """<device-mappings name="Legacy root"/>""".byteInputStream()
        )
        assertEquals("Legacy root", name)
    }

    @Test
    fun displayName_fromNameElement() {
        val name = DeviceInputMappingParser.parseDisplayName(
            """<device-input-config><name>Titan Pocket</name></device-input-config>"""
                .byteInputStream()
        )
        assertEquals("Titan Pocket", name)
    }

    @Test
    fun displayName_emptyRootAttribute_fallsThroughToNameElement() {
        val name = DeviceInputMappingParser.parseDisplayName(
            """<device-input-config name=""><name>Fallback</name></device-input-config>"""
                .byteInputStream()
        )
        assertEquals("Fallback", name)
    }

    @Test
    fun displayName_absent_isNull() {
        assertNull(
            DeviceInputMappingParser.parseDisplayName(
                """<device-input-config><device/></device-input-config>""".byteInputStream()
            )
        )
    }

    @Test
    fun displayName_malformedXml_isNull() {
        assertNull(DeviceInputMappingParser.parseDisplayName("""<device-input-config""".byteInputStream()))
    }
}

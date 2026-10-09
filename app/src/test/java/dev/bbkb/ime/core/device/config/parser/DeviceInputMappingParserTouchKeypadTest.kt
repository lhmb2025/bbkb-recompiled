package dev.bbkb.ime.core.device.config.parser

import android.os.Build
import android.view.InputDevice
import dev.bbkb.ime.core.device.config.model.DeviceInputMapping
import dev.bbkb.ime.core.device.config.model.DeviceMatchCriteria
import dev.bbkb.ime.core.device.config.model.DeviceQuirk
import dev.bbkb.ime.core.device.config.model.TouchKeypadConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * The schema additions for the Titans: `<touch-keypad>`, `<quirk>`, the board / display / model /
 * manufacturer match rules, vendor-id / product-id rules that can now actually match, and the
 * non-forcing `default-value` on a setting override.
 *
 * Runs under Robolectric for android.util.Xml (a real XmlPullParser on the JVM).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class DeviceInputMappingParserTouchKeypadTest {

    private val savedBuild = listOf("BOARD", "DISPLAY", "MODEL", "MANUFACTURER", "DEVICE", "BRAND")
        .associateWith { ReflectionHelpers.getStaticField<String>(Build::class.java, it) }

    @After
    fun restoreBuild() {
        savedBuild.forEach { (field, value) -> setBuild(field, value) }
    }

    private fun setBuild(field: String, value: String?) =
        ReflectionHelpers.setStaticField(Build::class.java, field, value)

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

    // ── <touch-keypad> ───────────────────────────────────────────────────────

    @Test
    fun touchKeypad_parsesEveryAttributeAndTheDeviceName() {
        val pad = named(
            """<touch-keypad range-x="1440" range-y="720" contacts="single" native-min-sdk="36" source="native">
                   <input-device exact="touchPad"/>
               </touch-keypad>"""
        ).touchKeypad
        assertNotNull(pad)
        pad!!
        assertEquals(1440, pad.rangeX)
        assertEquals(720, pad.rangeY)
        assertEquals(TouchKeypadConfig.Contacts.SINGLE, pad.contacts)
        assertTrue(pad.hasNativeMinSdk())
        assertEquals(36, pad.nativeMinSdk)
        assertEquals(TouchKeypadConfig.SourcePreference.NATIVE, pad.source)
        assertTrue(pad.matchesInputDeviceName("touchPad"))
        assertFalse("device names match case-sensitively, like device-name",
            pad.matchesInputDeviceName("touchpad"))
        assertFalse("the rear screen is not the pad", pad.matchesInputDeviceName("sub_touch"))
    }

    @Test
    fun touchKeypad_regexName_andDefaults() {
        val pad = named("""<touch-keypad><input-device regex="mtk-pad.*"/></touch-keypad>""").touchKeypad!!
        assertTrue(pad.matchesInputDeviceName("mtk-pad"))
        assertTrue(pad.matchesInputDeviceName("mtk-pad-2"))
        assertEquals("no range declared", 0, pad.rangeX)
        assertEquals(0, pad.rangeY)
        assertEquals(TouchKeypadConfig.Contacts.SINGLE, pad.contacts)
        assertFalse("absent native-min-sdk = delivered whenever enumerated", pad.hasNativeMinSdk())
        assertEquals(TouchKeypadConfig.SourcePreference.AUTO, pad.source)
    }

    @Test
    fun touchKeypad_sourcePins() {
        for ((value, expected) in listOf(
            "auto" to TouchKeypadConfig.SourcePreference.AUTO,
            "native" to TouchKeypadConfig.SourcePreference.NATIVE,
            "shizuku" to TouchKeypadConfig.SourcePreference.SHIZUKU,
            "SHIZUKU" to TouchKeypadConfig.SourcePreference.SHIZUKU,
        )) {
            assertEquals(value, expected, named("""<touch-keypad source="$value"/>""").touchKeypad!!.source)
        }
    }

    @Test
    fun touchKeypad_unknownSourceOrContacts_keepTheDefaultsAndTheBlock() {
        val pad = named(
            """<touch-keypad range-x="720" contacts="many" source="adb">
                   <input-device exact="mtk-pad"/></touch-keypad>"""
        ).touchKeypad!!
        assertEquals(TouchKeypadConfig.SourcePreference.AUTO, pad.source)
        assertEquals(TouchKeypadConfig.Contacts.SINGLE, pad.contacts)
        assertEquals(720, pad.rangeX)
        assertTrue(pad.matchesInputDeviceName("mtk-pad"))
    }

    @Test
    fun touchKeypad_multiContacts() {
        assertEquals(TouchKeypadConfig.Contacts.MULTI,
            named("""<touch-keypad contacts="multi"/>""").touchKeypad!!.contacts)
    }

    @Test
    fun noTouchKeypad_isNull_andSiblingsStillParse() {
        val m = named("""<ckb-y-warp>0:0,450:324</ckb-y-warp><kdb-variant>athena</kdb-variant>""")
        assertNull(m.touchKeypad)
        assertEquals("0:0,450:324", m.ckbYWarp)
        assertEquals("athena", m.kdbVariant)
    }

    @Test
    fun touchKeypad_doesNotSwallowTheElementsAfterIt() {
        val m = named(
            """<touch-keypad range-x="1"><input-device exact="p"/></touch-keypad>
               <keypad-layout>qwerty</keypad-layout>
               <quirk name="fn-no-key-up"/>"""
        )
        assertNotNull(m.touchKeypad)
        assertEquals("qwerty", m.keypadLayout)
        assertTrue(m.hasQuirk(DeviceQuirk.FN_NO_KEY_UP))
    }

    // ── <quirk> ──────────────────────────────────────────────────────────────

    @Test
    fun quirk_known_isRecorded_unknown_isIgnored() {
        val m = named("""<quirk name="fn-no-key-up"/><quirk name="no-such-quirk"/>""")
        assertTrue(m.hasQuirk(DeviceQuirk.FN_NO_KEY_UP))
        assertEquals(1, m.quirks.size)
        assertFalse(named("").hasQuirk(DeviceQuirk.FN_NO_KEY_UP))
    }

    // ── board / display / model / manufacturer ───────────────────────────────

    @Test
    fun boardRule_readsBuildBoard() {
        val m = parseSingle("""<match><build-device exact="Titan_2"/><board exact="G72BoardV1"/></match>""")
        setBuild("DEVICE", "Titan_2")
        setBuild("BOARD", "G72BoardV1")
        assertTrue(m.matchCriteria.matches("TitanKey", null, null))
        setBuild("BOARD", "g72boardv1")
        assertTrue("exact Build rules ignore case", m.matchCriteria.matches("TitanKey", null, null))
        setBuild("BOARD", "G71BoardV1")
        assertFalse("ANDed with build-device: the Titan 2's board is not the Elite's",
            m.matchCriteria.matches("TitanKey", null, null))
    }

    @Test
    fun displayRule_regexPrefix() {
        val m = parseSingle("""<match><display regex="Titan 2 Elite.*"/></match>""")
        setBuild("DISPLAY", "Titan 2 Elite_EEA_20260301")
        assertTrue(m.matchCriteria.matches("x", null, null))
        setBuild("DISPLAY", "Titan 2_EEA_V01.00.14")
        assertFalse(m.matchCriteria.matches("x", null, null))
        setBuild("DISPLAY", null)
        assertFalse("a set rule never matches a missing value", m.matchCriteria.matches("x", null, null))
    }

    @Test
    fun modelAndManufacturerRules() {
        val m = parseSingle("""<match><manufacturer exact="A-gold"/><model exact="Titan"/></match>""")
        setBuild("MANUFACTURER", "A-gold")
        setBuild("MODEL", "Titan")
        assertTrue(m.matchCriteria.matches("aw9523-key", null, null))
        setBuild("MODEL", "Titan Pocket")
        assertFalse(m.matchCriteria.matches("aw9523-key", null, null))
    }

    // ── vendor-id / product-id, matched against the keyboard InputDevice ─────

    private fun keyboard(name: String, vendor: Int, product: Int): InputDevice {
        val device = mock(InputDevice::class.java)
        `when`(device.name).thenReturn(name)
        `when`(device.vendorId).thenReturn(vendor)
        `when`(device.productId).thenReturn(product)
        return device
    }

    @Test
    fun vendorAndProductRules_matchTheInputDevice() {
        val config = parse(
            """<device-input-config><device>
                <match><vendor-id exact="0x2533"/><product-id exact="0x0001"/></match>
                <device-type>PKB</device-type>
            </device></device-input-config>"""
        )
        assertNotNull(config.findMappingForInputDevice(keyboard("TitanKey", 0x2533, 0x0001)))
        assertNull(config.findMappingForInputDevice(keyboard("TitanKey", 0x2533, 0x0002)))
        assertNull("the name-only form has no ids, so an id rule cannot match through it",
            config.findMappingForDevice("TitanKey"))
    }

    @Test
    fun vendorRule_exactIgnoresHexCase_andIdsAreZeroPadded() {
        assertEquals("0x0fca", DeviceMatchCriteria.inputDeviceId(0x0fca))
        assertEquals("0x2533", DeviceMatchCriteria.inputDeviceId(0x2533))
        val m = parseSingle("""<match><vendor-id exact="0x0FCA"/></match>""")
        assertTrue(m.matchCriteria.matches(null, "0x0fca", null))
    }

    @Test
    fun aNameOnlyConfig_stillMatchesThroughTheInputDevice() {
        val config = parse(
            """<device-input-config><device><match><device-name exact="aw9523b-key"/></match>
               </device></device-input-config>"""
        )
        val mapping = config.findMappingForInputDevice(keyboard("aw9523b-key", 0, 0))
        assertSame(config.mappings[0], mapping)
    }

    // ── default-value ────────────────────────────────────────────────────────

    @Test
    fun defaultValue_onAStringSetting_isADefaultNotALock() {
        val o = named(
            """<settings-overrides>
                <setting key="ckb_gesture_double_tap" type="string" default-value="none"/>
            </settings-overrides>"""
        ).getSettingOverride("ckb_gesture_double_tap")!!
        assertEquals("none", o.getDefaultStringValue())
        assertFalse("a default does not force the value", o.hasForcedValue())
        assertFalse(o.readOnly)
        assertFalse(o.hidden)
    }

    @Test
    fun defaultValue_onABooleanSetting_isIgnored() {
        val o = named(
            """<settings-overrides><setting key="k" type="boolean" default-value="true"/></settings-overrides>"""
        ).getSettingOverride("k")!!
        assertNull(o.defaultValue)
        assertNull(o.getDefaultStringValue())
    }
}

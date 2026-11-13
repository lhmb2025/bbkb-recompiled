package dev.bbkb.ime.core.device.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.device.config.parser.DeviceInputMappingParser
import dev.bbkb.ime.R
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
 * Invariants over the shipped `device_config_*.xml` files. Chiefly: no config may declare
 * `<layout-alt-overrides>`, because every shipped device's firmware KCM is already per-layout (the
 * KEY2 carries `stmpe{,_azerty,_qwertz}.kcm`), so an override can only displace a right answer.
 * Exempt a device by name only after confirming its firmware ships one KCM for every layout
 * (`adb shell "su -c 'ls /vendor/usr/keychars/'"`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ShippedDeviceConfigsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    /** Every `R.xml.device_config_*` resource, by resource name. */
    private fun shippedConfigResources(): List<Pair<String, Int>> =
        R.xml::class.java.fields
            .filter { it.name.startsWith("device_config_") }
            .map { it.name to it.getInt(null) }

    @Test
    fun theScanFindsTheShippedConfigs() {
        // A reflection scan that silently returned nothing would make every assertion vacuous.
        val names = shippedConfigResources().map { it.first }
        assertTrue("R.xml scan found no device_config_* resources", names.isNotEmpty())
        assertTrue("expected the athena config among $names", names.contains("device_config_athena"))
    }

    /**
     * Only a single-layout model's config may declare `<keypad-layout>`: it is the top-ranked
     * source in [dev.bbkb.ime.core.device.detection.KeypadLayoutDetector] and would override live
     * detection on every unit the config matches. [SINGLE_LAYOUT_CONFIGS] are models sold in one
     * layout only; each declares `qwerty`.
     */
    @Test
    fun onlySingleLayoutModelsHardCodeAKeypadLayout() {
        for ((name, resId) in shippedConfigResources()) {
            val config = DeviceInputMappingParser.parseConfigFromXmlResource(context, resId)
                ?: continue
            for (mapping in config.mappings) {
                if (name in SINGLE_LAYOUT_CONFIGS) {
                    assertEquals(
                        "$name is a single-layout model and must declare <keypad-layout>qwerty",
                        "qwerty", mapping.keypadLayout
                    )
                } else {
                    assertNull(
                        "$name declares <keypad-layout>${mapping.keypadLayout}</keypad-layout>" +
                            " — firmware detection must stay live for every unit matching a" +
                            " shipped config; see this test's javadoc before adding one",
                        mapping.keypadLayout
                    )
                }
            }
        }
    }

    @Test
    fun theSingleLayoutListNamesShippedConfigs() {
        val names = shippedConfigResources().map { it.first }.toSet()
        for (name in SINGLE_LAYOUT_CONFIGS) {
            assertTrue("$name is exempted but not shipped", name in names)
        }
        assertFalse("athena matches every KEY2 layout and must stay live",
            "device_config_athena" in SINGLE_LAYOUT_CONFIGS)
    }

    private companion object {
        val SINGLE_LAYOUT_CONFIGS = setOf(
            "device_config_minimal",
            "device_config_q25",
            "device_config_titan",
            "device_config_titan2",
            "device_config_titan2_elite",
            "device_config_titan_pocket",
            "device_config_titan_slim",
        )
    }

    /**
     * Only the BlackBerry configs switch the BBKB helper off: their firmware delivers every key the
     * keyboard handles. Every other phone keeps it offered (the Minimal Phone needs it).
     */
    @Test
    fun onlyTheBlackBerryConfigsSwitchTheHelperOff() {
        val off = shippedConfigResources().filter { (_, resId) ->
            DeviceInputMappingParser.parseConfigFromXmlResource(context, resId).mappings
                .any { it.accessibilityHelperOff }
        }.map { it.first }.toSet()
        assertEquals(setOf("device_config_athena", "device_config_blackberry"), off)
    }

    @Test
    fun noShippedConfigDeclaresLayoutAltOverrides() {
        for ((name, resId) in shippedConfigResources()) {
            val config = DeviceInputMappingParser.parseConfigFromXmlResource(context, resId)
                ?: continue
            for (mapping in config.mappings) {
                assertTrue(
                    "$name declares <layout-alt-overrides> ${mapping.layoutAltOverrides.keys}" +
                        " — the firmware KCM already supplies per-layout alt characters;" +
                        " see this test's javadoc before adding one back",
                    mapping.layoutAltOverrides.isEmpty()
                )
            }
        }
    }
}

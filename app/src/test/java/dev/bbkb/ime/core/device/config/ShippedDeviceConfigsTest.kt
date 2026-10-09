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
 * Invariants over the `device_config_*.xml` files this app ships.
 *
 * The one that matters: **no shipped config may declare `<layout-alt-overrides>`.** Every device
 * the app ships a config for gets its Alt+key characters from the firmware KeyCharacterMap, and
 * on BlackBerry hardware that KCM is already per-layout — the KEY2 (athena) carries
 * `/vendor/usr/keychars/stmpe{,_azerty,_qwertz}.kcm` selected by a matching `.idc`, so the
 * AZERTY and QWERTZ alt characters are correct before this app sees them. A layout-alt-override
 * table can therefore only *displace* a right answer with a hand-maintained one, which is what
 * the retired `device_alt_mappings_{azerty,qwertz}_overrides.xml` pair did.
 *
 * Adding `<layout-alt-overrides>` back is legitimate only for a device whose firmware ships ONE
 * KCM for every keypad layout. If that device arrives, verify the claim against the device
 * (`adb shell "su -c 'ls /vendor/usr/keychars/'"`) and then relax this test to exempt it by name.
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
        // Guards the test itself: a reflection scan that silently returns nothing would make
        // every assertion below vacuous.
        val names = shippedConfigResources().map { it.first }
        assertTrue("R.xml scan found no device_config_* resources", names.isNotEmpty())
        assertTrue("expected the athena config among $names", names.contains("device_config_athena"))
    }

    /**
     * **Only a single-layout model's config may declare `<keypad-layout>`.**
     *
     * That element is an override, and one config covers every unit that matches it — athena's
     * covers every BlackBerry KEY2 in the world, QWERTY, AZERTY and QWERTZ alike. Hard-coding a
     * layout there is not "documenting the KEY2 is QWERTY"; it is the top-ranked source in
     * [dev.bbkb.ime.core.device.detection.KeypadLayoutDetector], so it would
     * switch off the keypad's InputDevice name, the `ro.*.keypadlanguage` properties, the
     * KeyCharacterMap fingerprint and the scancode observation on every one of those units at
     * once — reinstating the very bug the detector was written to fix, and this time with no
     * source able to correct it.
     *
     * The exemptions are [SINGLE_LAYOUT_CONFIGS]: models sold in one layout only (the Minimal
     * Phone, the Zinwa Q25, the Unihertz Titans), where every unit's keypad is the same. User
     * letter maps bind to the effective keypad layout, and on those devices that is declared
     * rather than left to live detection. Each declares exactly `qwerty`. Adding a config here
     * means checking that the model really has no other layout.
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

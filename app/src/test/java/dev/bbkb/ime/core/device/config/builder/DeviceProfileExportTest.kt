package dev.bbkb.ime.core.device.config.builder

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.device.config.CustomDeviceConfigManager
import dev.bbkb.ime.core.device.config.model.KeyRole
import dev.bbkb.ime.core.device.config.parser.DeviceInputMappingParser
import dev.bbkb.ime.core.device.detection.KeypadLayoutDetector
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

/**
 * The export half of the profile builder: what a capture on real hardware turns into, and that the
 * app's own parser reads it back as the same thing.
 *
 * The two fixtures are the two reference handsets. The MP01 one is the harder case and the reason
 * the feature exists: its ROM falls back to `Generic.kl`/`Generic.kcm` (its `.idc` names a `.kl`
 * that is not on the filesystem), so its Emoji and Mic keys carry a scancode and no keycode at
 * all, nothing in the firmware will say which keypad is fitted, and its Sym keycode changed
 * between two ROMs. The KEY2 one is the opposite: the firmware answers the layout question, so the
 * generated config must stay out of the way and not pin it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class DeviceProfileExportTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        forgetTheConfigManagerSingleton()
        DeviceProfileExporter.configDir(context).deleteRecursively()
    }

    /**
     * `CustomDeviceConfigManager` is a process singleton holding the application context it was
     * first built with. Robolectric shares one sandbox — and therefore one set of statics —
     * between test classes with the same config while giving each test a fresh Application with a
     * fresh files directory, so a manager built by an earlier class lists a directory this class
     * no longer writes to. On a phone there is one process and one files directory, so this is a
     * fixture problem only.
     */
    private fun forgetTheConfigManagerSingleton() {
        CustomDeviceConfigManager::class.java.getDeclaredField("sInstance").apply {
            isAccessible = true
            set(null, null)
        }
    }

    /**
     * Robolectric hands every test in a fork the same app data directory, so a config left in
     * `files/device_configs` is a config the *next* test class finds: `SettingsScreenRenderTest`
     * renders the configuration screen and grew a whole "CUSTOM" section from one profile written
     * here. The active-config preference is reset for the same reason — it is process state the
     * config manager caches.
     */
    @After
    fun tearDown() {
        forgetTheConfigManagerSingleton()
        DeviceProfileExporter.configDir(context).deleteRecursively()
        CustomDeviceConfigManager.getInstance(context)
            .setActiveConfigId(CustomDeviceConfigManager.ID_DEFAULT)
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private fun keyboard(
        name: String,
        deviceId: Int = 4,
        vendorId: Int = 0,
        productId: Int = 0,
        hasTouch: Boolean = false,
        hasAltLayer: Boolean = false,
    ) = DeviceFacts.Keyboard(
        deviceId, name, null, vendorId, productId,
        InputDevice.SOURCE_KEYBOARD, InputDevice.KEYBOARD_TYPE_ALPHABETIC, hasTouch, hasAltLayer,
    )

    private fun facts(
        buildDevice: String,
        model: String,
        manufacturer: String,
        brand: String,
        rom: String,
        release: String,
        sdk: Int,
        keyboards: List<DeviceFacts.Keyboard>,
        layout: String,
        source: KeypadLayoutDetector.Source,
        hasTouchKeypad: Boolean,
        matchedConfigName: String? = null,
    ) = DeviceFacts(
        buildDevice, model, manufacturer, brand, rom, release, sdk, "5.0.0-beta.17 (1429)",
        keyboards, keyboards.firstOrNull(), layout, source, hasTouchKeypad,
        matchedConfigName, matchedConfigName?.let { "preloaded:device_config_test" },
    )

    /**
     * A Minimal Phone MP01 on `MP01_20260104_1412`: Sym is scancode 249 reporting `KEYCODE_SYM`
     * (it reported `KEYCODE_ALT_RIGHT` on the November 2025 ROM — the exact reason the pair is
     * captured rather than assumed), and the Emoji and Mic keys carry no keycode at all because
     * `Generic.kl` has nothing to map them to.
     */
    private fun mp01Facts() = facts(
        buildDevice = "MP01",
        model = "Minimal Phone",
        manufacturer = "Minimal",
        brand = "Minimal",
        rom = "MP01_20260104_1412",
        release = "14",
        sdk = 34,
        keyboards = listOf(keyboard("aw9523b-key")),
        layout = KeypadLayoutDetector.QWERTY,
        source = KeypadLayoutDetector.Source.FALLBACK,
        hasTouchKeypad = false,
        matchedConfigName = null,
    )

    private fun mp01Capture(): GuidedCapture {
        val capture = GuidedCapture()
        capture.press(249, KeyEvent.KEYCODE_SYM)
        capture.hold(56, KeyEvent.KEYCODE_ALT_LEFT)
        capture.press(42, KeyEvent.KEYCODE_SHIFT_LEFT)
        capture.press(28, KeyEvent.KEYCODE_ENTER)
        capture.press(14, KeyEvent.KEYCODE_DEL)
        capture.press(57, KeyEvent.KEYCODE_SPACE)
        capture.skip()                                   // no Speed key on the MP01
        capture.press(250, KeyEvent.KEYCODE_UNKNOWN)     // Emoji: scancode only
        capture.press(251, KeyEvent.KEYCODE_UNKNOWN)     // Mic: scancode only
        return capture
    }

    /** A BlackBerry KEY2: capacitive keypad, firmware that answers the layout, Speed key. */
    private fun key2Facts() = facts(
        buildDevice = "athena",
        model = "BBF100-6",
        manufacturer = "BlackBerry",
        brand = "blackberry",
        rom = "AAO472",
        release = "8.1.0",
        sdk = 27,
        keyboards = listOf(keyboard("stmpe_keypad", deviceId = 3, hasAltLayer = true)),
        layout = KeypadLayoutDetector.QWERTY,
        source = KeypadLayoutDetector.Source.SYSPROP,
        hasTouchKeypad = true,
        matchedConfigName = "BlackBerry Key2 (Athena)",
    )

    private fun key2Capture(): GuidedCapture {
        val capture = GuidedCapture()
        capture.press(63, KeyEvent.KEYCODE_SYM)
        capture.press(56, KeyEvent.KEYCODE_ALT_LEFT)
        capture.press(42, KeyEvent.KEYCODE_SHIFT_LEFT)
        capture.press(28, KeyEvent.KEYCODE_ENTER)
        capture.press(14, KeyEvent.KEYCODE_DEL)
        capture.press(57, KeyEvent.KEYCODE_SPACE)
        capture.press(110, KeyEvent.KEYCODE_FUNCTION)    // Speed key
        capture.skip()                                   // no Emoji key
        capture.press(0, KeyEvent.KEYCODE_0)             // Mic key: keycode only
        return capture
    }

    private fun GuidedCapture.press(scanCode: Int, keyCode: Int) {
        onKeyDown(scanCode, keyCode, 4, 0, 1_000L)
        onKeyUp(scanCode, keyCode, 4)
    }

    private fun GuidedCapture.hold(scanCode: Int, keyCode: Int) {
        onKeyDown(scanCode, keyCode, 4, 0, 1_000L)
        onKeyDown(scanCode, keyCode, 4, 1, 1_400L)
        onKeyDown(scanCode, keyCode, 4, 2, 1_460L)
        onKeyDown(scanCode, keyCode, 4, 3, 1_520L)
        onKeyUp(scanCode, keyCode, 4)
    }

    private fun draftFor(facts: DeviceFacts, capture: GuidedCapture, name: String) =
        DeviceProfileBuilder.draft(facts, capture, name).withExportDate("2026-09-18")

    // ── the generated document ───────────────────────────────────────────────

    @Test
    fun anMp01CaptureProducesExactlyThisConfig() {
        val xml = DeviceProfileXmlWriter.toXml(
            draftFor(mp01Facts(), mp01Capture(), "Minimal Phone (MP01)")
        )
        assertEquals(MP01_XML, xml)
    }

    @Test
    fun theGeneratedMp01ConfigParsesBackToWhatWasCaptured() {
        val draft = draftFor(mp01Facts(), mp01Capture(), "Minimal Phone (MP01)")
        val xml = DeviceProfileXmlWriter.toXml(draft)

        assertNull(DeviceProfileExporter.validate(xml, draft))

        // <match> ANDs the keypad name with Build.DEVICE, so the matcher is only meaningful on a
        // Build that is this fixture's handset.
        ShadowBuild.setDevice("MP01")
        val parsed = DeviceInputMappingParser.parseConfigFromStream(
            ByteArrayInputStream(xml.toByteArray())
        )
        val mapping = parsed.findMappingForDevice("aw9523b-key")
        assertNotNull("the generated <match> must claim this handset's keypad", mapping)
        assertTrue("PKB forces the physical-keyboard pipeline", mapping!!.forcePkbDevice)
        assertFalse("the MP01 keypad is not capacitive", mapping.forceTouchKeypad)
        assertEquals(KeypadLayoutDetector.QWERTY, mapping.keypadLayout)

        assertEquals(8, mapping.scancodeMappings.size)
        val sym = mapping.scancodeMappings[0]
        assertEquals(KeyRole.BOARD_SYM, sym.role)
        assertEquals(249, sym.rawScanCode)
        assertEquals(KeyEvent.KEYCODE_SYM, sym.rawKeyCode)
        assertEquals("KEYCODE_SYM", sym.treatAs)
        assertTrue(sym.matches(249, KeyEvent.KEYCODE_SYM))

        val emoji = mapping.scancodeMappings[6]
        assertEquals(KeyRole.BOARD_EMOJI, emoji.role)
        assertEquals(250, emoji.rawScanCode)
        assertEquals(
            "a key with no keycode must match on its scancode alone",
            -1, emoji.rawKeyCode,
        )
        assertEquals(CaptureStep.BoardIds.EMOJI, emoji.boardId)
        assertTrue(emoji.matches(250, KeyEvent.KEYCODE_UNKNOWN))

        val mic = mapping.scancodeMappings[7]
        assertEquals(KeyRole.BOARD_VOICE, mic.role)
        assertEquals(CaptureStep.BoardIds.VOICE, mic.boardId)
    }

    @Test
    fun aKey2CaptureIsCkbAndLeavesTheKeypadLayoutToTheFirmware() {
        val draft = draftFor(key2Facts(), key2Capture(), "Key2 (athena)")
        val xml = DeviceProfileXmlWriter.toXml(draft)

        assertNull(DeviceProfileExporter.validate(xml, draft))
        assertFalse(
            "a firmware-sourced layout must not be pinned into a shareable config",
            xml.contains("</keypad-layout>"),
        )
        assertTrue(xml.contains("No <keypad-layout> here, deliberately"))

        ShadowBuild.setDevice("athena")
        val parsed = DeviceInputMappingParser.parseConfigFromStream(
            ByteArrayInputStream(xml.toByteArray())
        )
        val mapping = parsed.findMappingForDevice("stmpe_keypad")!!
        assertTrue("CKB forces the touch keypad, which gates swipe input", mapping.forceTouchKeypad)
        assertNull(mapping.keypadLayout)

        val speed = mapping.getMultifunctionKeyMapping()!!
        assertEquals(110, speed.rawScanCode)
        assertEquals(KeyEvent.KEYCODE_FUNCTION, speed.rawKeyCode)
        assertEquals(CaptureStep.MULTIFUNCTION_DEFAULT_ACTION, speed.defaultAction)
        assertNull("the Speed key has no printed meaning to normalise to", speed.treatAs)

        val mic = mapping.scancodeMappings.last()
        assertEquals(KeyRole.BOARD_VOICE, mic.role)
        assertEquals("a key with no scancode matches on its keycode alone", -1, mic.rawScanCode)
        assertEquals(KeyEvent.KEYCODE_0, mic.rawKeyCode)
    }

    @Test
    fun aScancodeProvenLayoutIsRecordedBecauseNothingRankedAboveItAnswered() {
        val facts = facts(
            buildDevice = "MP01", model = "Minimal Phone", manufacturer = "Minimal",
            brand = "Minimal", rom = "MP01_20260104_1412", release = "14", sdk = 34,
            keyboards = listOf(keyboard("aw9523b-key")),
            layout = KeypadLayoutDetector.AZERTY,
            source = KeypadLayoutDetector.Source.SCANCODE,
            hasTouchKeypad = false,
        )
        val draft = draftFor(facts, mp01Capture(), "Minimal AZERTY")
        val xml = DeviceProfileXmlWriter.toXml(draft)

        assertTrue(xml.contains("<keypad-layout>azerty</keypad-layout>"))
        ShadowBuild.setDevice("MP01")
        val parsed = DeviceInputMappingParser.parseConfigFromStream(
            ByteArrayInputStream(xml.toByteArray())
        )
        assertEquals(
            KeypadLayoutDetector.AZERTY,
            parsed.findMappingForDevice("aw9523b-key")!!.keypadLayout,
        )
    }

    // ── escaping ─────────────────────────────────────────────────────────────

    @Test
    fun namesThatWouldBreakTheDocumentAreEscaped() {
        val facts = facts(
            buildDevice = "q<25>", model = "Zinwa \"Q25\"", manufacturer = "Zinwa & Co",
            brand = "Zinwa", rom = "Q25--2026", release = "13", sdk = 33,
            keyboards = listOf(keyboard("mtk-kpd & \"main\"")),
            layout = KeypadLayoutDetector.QWERTY,
            source = KeypadLayoutDetector.Source.SYSPROP,
            hasTouchKeypad = false,
        )
        val capture = GuidedCapture().apply { press(249, KeyEvent.KEYCODE_SYM) }
        val draft = draftFor(facts, capture, "Zinwa & \"Q25\" <beta>")
        val xml = DeviceProfileXmlWriter.toXml(draft)

        assertTrue(xml.contains("""<device-name exact="mtk-kpd &amp; &quot;main&quot;"/>"""))
        assertTrue(xml.contains("""<build-device exact="q&lt;25&gt;"/>"""))
        assertFalse("a literal -- would terminate the comment early", xml.contains("Q25--2026"))
        assertTrue(xml.contains("Q25- -2026"))

        // The whole point: the parser reads back the un-escaped values.
        assertNull(DeviceProfileExporter.validate(xml, draft))
        val parsed = DeviceInputMappingParser.parseConfigFromStream(
            ByteArrayInputStream(xml.toByteArray())
        )
        ShadowBuild.setDevice("q<25>")
        assertNotNull(parsed.findMappingForDevice("mtk-kpd & \"main\""))
        assertEquals(
            "Zinwa & \"Q25\" <beta>",
            DeviceInputMappingParser.parseDisplayName(ByteArrayInputStream(xml.toByteArray())),
        )
    }

    // ── the refusal ──────────────────────────────────────────────────────────

    @Test
    fun aConfigThatDoesNotSurviveTheRoundTripIsNotWritten() {
        val draft = draftFor(mp01Facts(), mp01Capture(), "Minimal Phone (MP01)")
        // A config whose <match> names some other handset parses perfectly and does nothing.
        val wrong = DeviceProfileXmlWriter.toXml(draft).replace("aw9523b-key", "some-other-keypad")
        val reason = DeviceProfileExporter.validate(wrong, draft)
        assertNotNull("a config that cannot match this device must be refused", reason)
        assertTrue(reason!!.contains("does not match this device"))
    }

    @Test
    fun aTruncatedConfigIsRefusedRatherThanWritten() {
        val draft = draftFor(mp01Facts(), mp01Capture(), "Minimal Phone (MP01)")
        val xml = DeviceProfileXmlWriter.toXml(draft)
        val truncated = xml.substring(0, xml.indexOf("</scancode-mappings>"))
        assertNotNull(DeviceProfileExporter.validate(truncated, draft))
    }

    // ── writing, activating, sharing ─────────────────────────────────────────

    @Test
    fun exportWritesIntoTheDirectoryImportedConfigsLiveIn() {
        val draft = draftFor(mp01Facts(), mp01Capture(), "Minimal Phone (MP01)")
        val result = DeviceProfileExporter.export(context, draft)

        assertTrue(result.failure ?: "", result.ok())
        val file = result.file!!
        assertEquals(DeviceProfileExporter.configDir(context), file.parentFile)
        assertEquals("Minimal_Phone__MP01_.xml", file.name)
        assertEquals("custom:Minimal_Phone__MP01_.xml", result.configId)
        assertEquals(result.xml, file.readText())
        assertFalse(
            "the temp file must not be left behind",
            File(file.parentFile, file.name + ".tmp").exists(),
        )
    }

    @Test
    fun savingTwiceReplacesTheProfileRatherThanPilingUpCopies() {
        val draft = draftFor(mp01Facts(), mp01Capture(), "Minimal Phone (MP01)")
        DeviceProfileExporter.export(context, draft)
        val second = DeviceProfileExporter.export(context, draft)

        assertTrue(second.ok())
        assertEquals(1, DeviceProfileExporter.configDir(context).listFiles()!!.size)
    }

    @Test
    fun anExportedProfileIsListedAndActivatedByTheConfigManager() {
        val draft = draftFor(mp01Facts(), mp01Capture(), "Minimal Phone (MP01)")
        val result = DeviceProfileExporter.exportAndActivate(context, draft)
        assertTrue(result.failure ?: "", result.ok())

        val manager = CustomDeviceConfigManager.getInstance(context)
        assertEquals(result.configId, manager.activeConfigId)
        assertTrue(
            "the built profile must appear in the configuration list",
            manager.availableConfigs.any {
                it.id == result.configId &&
                    it.type == CustomDeviceConfigManager.ConfigType.CUSTOM &&
                    it.name == "Minimal Phone (MP01)"
            },
        )
        ShadowBuild.setDevice("MP01")
        assertNotNull(
            "the active config must resolve for this device",
            manager.activeConfig.findMappingForDevice("aw9523b-key"),
        )
    }

    /**
     * The exporter writes straight into the config manager's private directory under its private
     * id prefix, because those are what make a built profile activatable without a second copy of
     * the file. Nothing in the compiler ties the two together, so this does.
     */
    @Test
    fun theMirroredConfigManagerConstantsStillAgree() {
        fun constant(name: String): String {
            val field = CustomDeviceConfigManager::class.java.getDeclaredField(name)
            field.isAccessible = true
            return field.get(null) as String
        }
        assertEquals(constant("CUSTOM_CONFIG_DIR"), DeviceProfileExporter.CUSTOM_CONFIG_DIR)
        assertEquals(constant("PREFIX_CUSTOM"), DeviceProfileExporter.PREFIX_CUSTOM)
    }

    // ── what the import dialog reads ─────────────────────────────────────────

    @Test
    fun anExportedProfileSummarisesAsWhatItMatches() {
        val draft = draftFor(mp01Facts(), mp01Capture(), "Minimal Phone (MP01)")
        val file = DeviceProfileExporter.export(context, draft).file!!

        val summary = DeviceConfigSummary.of(file)!!
        assertEquals("Minimal Phone (MP01)", summary.name)
        assertEquals("aw9523b-key", summary.deviceName)
        assertEquals("MP01", summary.buildDevice)
        assertEquals("PKB", summary.deviceType)
        assertEquals("qwerty", summary.keypadLayout)
        assertEquals(8, summary.keyCount())
        assertFalse(summary.matchesNothing())

        assertTrue("the phone it was built on must be recognised", summary.couldMatch(mp01Facts()))
        assertFalse("another handset must be warned about", summary.couldMatch(key2Facts()))
    }

    @Test
    fun aShippedConfigSummarisesTheSameWay() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <device-input-config version="2.1" name="BlackBerry Key2 (Athena)">
                <device>
                    <match><build-device exact="athena"/></match>
                    <device-type>CKB</device-type>
                    <input-mappings><scancode-mappings>
                        <key rawKeyCode="7" role="BOARD_VOICE" treatAs="KEYCODE_MIC"/>
                    </scancode-mappings></input-mappings>
                </device>
            </device-input-config>
        """.trimIndent()

        val summary = DeviceConfigSummary.of(ByteArrayInputStream(xml.toByteArray()))!!
        assertEquals("BlackBerry Key2 (Athena)", summary.name)
        assertNull(summary.deviceName)
        assertEquals("athena", summary.buildDevice)
        assertEquals("CKB", summary.deviceType)
        assertEquals(1, summary.keyCount())
        assertTrue(summary.couldMatch(key2Facts()))
        assertFalse(summary.couldMatch(mp01Facts()))
    }

    @Test
    fun aConfigThatNamesNothingToMatchIsCalledOut() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <device-input-config version="2.1" name="Nothing">
                <device><match/><device-type>PKB</device-type></device>
            </device-input-config>
        """.trimIndent()
        val summary = DeviceConfigSummary.of(ByteArrayInputStream(xml.toByteArray()))!!
        assertTrue(summary.matchesNothing())
        assertEquals(0, summary.keyCount())
    }

    private companion object {
        /**
         * The document a Minimal Phone MP01 capture produces, pinned in full. It is the artefact
         * the user shares, so a change to any part of it — the header a reader relies on, the
         * order of the keys, an attribute the parser needs — is a change to the feature.
         */
        val MP01_XML = """
<?xml version="1.0" encoding="utf-8"?>
<!--
    Device Configuration: Minimal Phone (MP01)
    Schema v2.3

    Built on the device by the profile builder.
    App: 5.0.0-beta.17 (1429)
    Date: 2026-09-18
    ROM: MP01_20260104_1412 (Android 14, SDK 34)
    Build: DEVICE=MP01 MODEL=Minimal Phone MANUFACTURER=Minimal BRAND=Minimal
    Keyboard: aw9523b-key [id=4, vendor=0x0000, product=0x0000, sources=0x101, keyboardType=2, touch=false, altLayer=false]
    Keypad layout: qwerty (source FALLBACK)
    Touch keypad: false
    Matched shipped config: none

    Captured keys (raw, as this hardware reported them):
      SYM: scanCode=249 keyCode=63 (KEYCODE_SYM) deviceId=4 repeat=not held
      ALT: scanCode=56 keyCode=57 (KEYCODE_ALT_LEFT) deviceId=4 repeat=60ms x3
      SHIFT: scanCode=42 keyCode=59 (KEYCODE_SHIFT_LEFT) deviceId=4 repeat=not held
      ENTER: scanCode=28 keyCode=66 (KEYCODE_ENTER) deviceId=4 repeat=not held
      BACKSPACE: scanCode=14 keyCode=67 (KEYCODE_DEL) deviceId=4 repeat=not held
      SPACE: scanCode=57 keyCode=62 (KEYCODE_SPACE) deviceId=4 repeat=not held
      EMOJI: scanCode=250 keyCode=0 (no keycode) deviceId=4 repeat=not held
      MIC: scanCode=251 keyCode=0 (no keycode) deviceId=4 repeat=not held
-->

<device-input-config version="2.3" name="Minimal Phone (MP01)">

    <device>
        <match>
            <device-name exact="aw9523b-key"/>
            <build-device exact="MP01"/>
        </match>

        <device-type>PKB</device-type>

        <!-- Declared because no firmware source answered: the detected layout came from FALLBACK, which ranks below the keypad device name, the ro.*.keypadlanguage property and the KeyCharacterMap fingerprint. -->
        <keypad-layout>qwerty</keypad-layout>

        <input-mappings>
            <scancode-mappings>
                <key rawScanCode="249" rawKeyCode="63" role="BOARD_SYM"
                     treatAs="KEYCODE_SYM"
                     notes="captured on device: sym key"/>
                <key rawScanCode="56" rawKeyCode="57" role="MODIFIER"
                     treatAs="KEYCODE_ALT_LEFT"
                     notes="captured on device: alt key"/>
                <key rawScanCode="42" rawKeyCode="59" role="MODIFIER"
                     treatAs="KEYCODE_SHIFT_LEFT"
                     notes="captured on device: shift key"/>
                <key rawScanCode="28" rawKeyCode="66" role="FUNCTION"
                     treatAs="KEYCODE_ENTER"
                     notes="captured on device: enter key"/>
                <key rawScanCode="14" rawKeyCode="67" role="FUNCTION"
                     treatAs="KEYCODE_DEL"
                     notes="captured on device: backspace key"/>
                <key rawScanCode="57" rawKeyCode="62" role="CHARACTER"
                     treatAs="KEYCODE_SPACE"
                     notes="captured on device: space key"/>
                <key rawScanCode="250" role="BOARD_EMOJI"
                     treatAs="KEYCODE_EMOJI" board="-11"
                     notes="captured on device: emoji key"/>
                <key rawScanCode="251" role="BOARD_VOICE"
                     treatAs="KEYCODE_MIC" board="-27"
                     notes="captured on device: mic key"/>
            </scancode-mappings>
        </input-mappings>
    </device>

</device-input-config>
""".removePrefix("\n")
    }
}

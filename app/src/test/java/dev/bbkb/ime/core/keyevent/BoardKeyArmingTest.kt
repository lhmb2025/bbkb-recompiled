package dev.bbkb.ime.core.keyevent

import android.content.Context
import android.text.InputType
import android.view.InputDevice
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.R
import dev.bbkb.ime.core.device.config.model.DeviceInputMapping
import dev.bbkb.ime.core.device.config.parser.DeviceInputMappingParser
import dev.bbkb.ime.core.device.config.resolver.ScancodeMappingResolver
import dev.bbkb.ime.core.device.detection.KeyEventDeviceClassifier
import dev.bbkb.ime.core.device.profile.DeviceCapabilities
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.device.profile.DeviceProfileTestSupport
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.settings.util.SettingsManager
import dev.bbkb.ime.core.textinput.connection.EditorCapabilities
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * The **arming** half of the Phase 1g pairing, driven through a real [KeyEventConverter] and the
 * shipped device configs.
 *
 * <p>There were eight sites in that class that set one of the three old `InputMethodHelper` flags —
 * the role-based emoji / voice / multifunction block, the BlackBerry mic key code 7, the
 * `KEYCODE_VOICE_ASSIST` + mic-pseudo-scancode pair, the emoji pseudo-scancode, and the two
 * `AltMappingsTable` virtual key codes. Eight replacements is eight chances to arm the wrong action,
 * or to arm where the old code did not. These tests ask the converter, not a mock, and they cover
 * the two cases the brief singles out as must-not-move: **Alt+mic types the mapping's alt character
 * and arms nothing**, and **a multifunction key set to Ctrl arms nothing at all**.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class BoardKeyArmingTest {

    /** The MP01 scancodes, from device_config_minimal.xml. */
    private val MP01_EMOJI_SCANCODE = 250
    private val MP01_MIC_SCANCODE = 251

    /**
     * The BlackBerry mic key's key code: raw 7, which is `KEYCODE_0` — the KEY2's mic key doubles
     * as the zero digit ("Mic/zero key: sends keyCode=7", device_config_athena.xml). Not
     * `KEYCODE_7`, which is 14.
     */
    private val MIC_KEYCODE_7 = KeyEvent.KEYCODE_0

    /** The KEY2 multifunction key, from device_config_athena.xml: keyCode 119, scanCode 110. */
    private val KEY2_MULTIFUNCTION_KEYCODE = KeyEvent.KEYCODE_FUNCTION
    private val KEY2_MULTIFUNCTION_SCANCODE = 110

    private lateinit var context: Context
    private lateinit var converter: KeyEventConverter

    private val pairing get() = BoardKeyPressTracker.getInstance()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PrefsManager.getPrefs(context).edit().clear().commit()
        loadSettings()
        KeyEventDeviceClassifier.getInstance().clearCache()
        pairing.clearPendingActions()
    }

    @After
    fun tearDown() {
        pairing.clearPendingActions()
        ScancodeMappingResolver.getInstance().reset()
        KeyEventDeviceClassifier.getInstance().clearCache()
        DeviceProfile.initialize(null)
        PrefsManager.getPrefs(context).edit().clear().commit()
    }

    // ===================================================== MP01, by device-config role

    @Test
    fun `the MP01 emoji key arms the emoji board`() {
        installMp01()

        val result = convert(down(KeyEvent.KEYCODE_ALT_RIGHT, MP01_EMOJI_SCANCODE))

        assertArmedOnly(PendingKeyAction.EMOJI_BOARD)
        assertConsumedWithoutTyping(result)
        assertArmedBy(PendingKeyAction.EMOJI_BOARD, KeyEvent.KEYCODE_ALT_RIGHT, MP01_EMOJI_SCANCODE)
    }

    @Test
    fun `the MP01 mic key arms voice input`() {
        installMp01()

        val result = convert(down(KeyEvent.KEYCODE_ALT_RIGHT, MP01_MIC_SCANCODE))

        assertArmedOnly(PendingKeyAction.VOICE_INPUT)
        assertConsumedWithoutTyping(result)
    }

    /** Alt+mic types the mapping's alt character — '.' on the MP01 — and arms nothing. */
    @Test
    fun `Alt plus the MP01 mic key types a period and arms nothing`() {
        installMp01()

        val result = convert(down(KeyEvent.KEYCODE_ALT_RIGHT, MP01_MIC_SCANCODE), KeyEvent.META_ALT_ON)

        assertNothingArmed()
        assertEquals('.'.code, result.mCodePoint)
    }

    @Test
    fun `Alt plus the MP01 emoji key types its alt character and arms nothing`() {
        installMp01()

        val result =
            convert(down(KeyEvent.KEYCODE_ALT_RIGHT, MP01_EMOJI_SCANCODE), KeyEvent.META_ALT_ON)

        assertNothingArmed()
        assertEquals('0'.code, result.mCodePoint)
    }

    /** The Sym key is not one of the three: it converts to the symbol toggle and arms nothing. */
    @Test
    fun `the MP01 Sym key arms nothing`() {
        installMp01()

        convert(down(KeyEvent.KEYCODE_ALT_RIGHT, 249))

        assertNothingArmed()
    }

    /** A held key's repeats must not re-arm: the action fires once, on the release. */
    @Test
    fun `a held emoji key arms once`() {
        installMp01()
        convert(down(KeyEvent.KEYCODE_ALT_RIGHT, MP01_EMOJI_SCANCODE))
        assertTrue(pairing.consume(PendingKeyAction.EMOJI_BOARD))

        convert(down(KeyEvent.KEYCODE_ALT_RIGHT, MP01_EMOJI_SCANCODE, repeat = 1))
        convert(down(KeyEvent.KEYCODE_ALT_RIGHT, MP01_EMOJI_SCANCODE, repeat = 2))

        assertNothingArmed()
    }

    // ===================================================== MP01, the legacy pseudo-keycode path

    /**
     * The `.kl`-named emoji key, whose ROM sends the pseudo-scancode 666 with `KEYCODE_UNKNOWN`
     * because "EM" is not a valid Android keycode name. Reached here with no device config loaded
     * at all, which is the configuration the fallback exists for.
     */
    @Test
    fun `the emoji pseudo-scancode arms the emoji board with no device config`() {
        installBareP()

        convert(down(KeyEvent.KEYCODE_UNKNOWN, ResolvedKey.PSEUDO_KEYCODE_EMOJI))

        assertArmedOnly(PendingKeyAction.EMOJI_BOARD)
        assertArmedBy(
            PendingKeyAction.EMOJI_BOARD,
            KeyEvent.KEYCODE_UNKNOWN, ResolvedKey.PSEUDO_KEYCODE_EMOJI
        )
    }

    /** The BlackBerry OEM mic key: key code 7, no mapping needed. */
    @Test
    fun `the legacy mic key code 7 arms voice input`() {
        installBareP()

        convert(down(MIC_KEYCODE_7, /* scanCode */ 8))

        assertArmedOnly(PendingKeyAction.VOICE_INPUT)
    }

    /** `KEYCODE_VOICE_ASSIST` on the mic pseudo-scancode — what the system sends after a .kl update. */
    @Test
    fun `KEYCODE_VOICE_ASSIST on the mic pseudo-scancode arms voice input`() {
        installBareP()

        convert(down(/* KEYCODE_VOICE_ASSIST */ 231, ResolvedKey.PSEUDO_KEYCODE_VOICE))

        assertArmedOnly(PendingKeyAction.VOICE_INPUT)
    }

    @Test
    fun `Alt plus KEYCODE_VOICE_ASSIST types a period and arms nothing`() {
        installBareP()

        val result = convert(
            down(231, ResolvedKey.PSEUDO_KEYCODE_VOICE), KeyEvent.META_ALT_ON
        )

        assertNothingArmed()
        assertEquals('.'.code, result.mCodePoint)
    }

    // ===================================================== KEY2 multifunction key

    @Test
    fun `a KEY2 multifunction key set to switch language arms the multifunction action`() {
        installKey2(multifunctionAction = MultifunctionKeyHandler.ACTION_LANGUAGE_SWITCH)

        val result = convert(down(KEY2_MULTIFUNCTION_KEYCODE, KEY2_MULTIFUNCTION_SCANCODE))

        assertArmedOnly(PendingKeyAction.MULTIFUNCTION)
        assertConsumedWithoutTyping(result)
        assertArmedBy(
            PendingKeyAction.MULTIFUNCTION,
            KEY2_MULTIFUNCTION_KEYCODE, KEY2_MULTIFUNCTION_SCANCODE
        )
    }

    /**
     * "voice_input" is a retired action, so the key falls back to the device default (the emoji
     * board) instead of arming voice input.
     */
    @Test
    fun `a KEY2 multifunction key left on the retired voice action arms the emoji board`() {
        // The migration runs on a background thread, so the stored value may or may not have been
        // cleared yet; the key must fall back either way.
        installKey2(multifunctionAction = MultifunctionKeyHandler.LEGACY_ACTION_VOICE_INPUT, expectedStored = null)

        convert(down(KEY2_MULTIFUNCTION_KEYCODE, KEY2_MULTIFUNCTION_SCANCODE))

        assertArmedOnly(PendingKeyAction.EMOJI_BOARD)
    }

    @Test
    fun `a KEY2 multifunction key set to the emoji board arms the emoji board`() {
        installKey2(multifunctionAction = MultifunctionKeyHandler.ACTION_EMOJI_BOARD)

        convert(down(KEY2_MULTIFUNCTION_KEYCODE, KEY2_MULTIFUNCTION_SCANCODE))

        assertArmedOnly(PendingKeyAction.EMOJI_BOARD)
    }

    /**
     * Ctrl arms nothing: `BlackBerryIME.remapKeyEvent` has already rewritten the event to
     * `KEYCODE_CTRL_LEFT`, and the physical-Ctrl machinery owns it from there. If this key armed a
     * pending action, Ctrl+C would also toggle a board on release.
     */
    @Test
    fun `a KEY2 multifunction key set to Ctrl arms nothing`() {
        installKey2(multifunctionAction = MultifunctionKeyHandler.ACTION_CTRL)

        convert(down(KEY2_MULTIFUNCTION_KEYCODE, KEY2_MULTIFUNCTION_SCANCODE))

        assertNothingArmed()
    }

    @Test
    fun `Alt plus a KEY2 multifunction key arms nothing`() {
        installKey2(multifunctionAction = MultifunctionKeyHandler.ACTION_CLIPBOARD_BOARD)

        convert(
            down(KEY2_MULTIFUNCTION_KEYCODE, KEY2_MULTIFUNCTION_SCANCODE), KeyEvent.META_ALT_ON
        )

        assertNothingArmed()
    }

    /** The KEY2's dedicated mic key (keyCode 7, role BOARD_VOICE, altChar '0'). */
    @Test
    fun `the KEY2 mic key arms voice input and Alt plus it types a zero`() {
        installKey2(multifunctionAction = MultifunctionKeyHandler.ACTION_LANGUAGE_SWITCH)

        convert(down(MIC_KEYCODE_7, /* scanCode */ 8))
        assertArmedOnly(PendingKeyAction.VOICE_INPUT)

        pairing.clearPendingActions()
        val alted = convert(down(MIC_KEYCODE_7, 8), KeyEvent.META_ALT_ON)

        assertNothingArmed()
        assertEquals('0'.code, alted.mCodePoint)
    }

    /**
     * "Dictation key" off swaps the two: the bare mic key types its zero, and Alt plus it starts
     * voice input.
     */
    @Test
    fun `with the dictation key off, the KEY2 mic key types a zero and Alt plus it arms voice input`() {
        installKey2(multifunctionAction = MultifunctionKeyHandler.ACTION_EMOJI_BOARD)
        PrefsManager.getPrefs(context).edit().putBoolean("pref_voice_input_key", false).commit()

        val bare = convert(down(MIC_KEYCODE_7, 8))
        assertNothingArmed()
        assertEquals('0'.code, bare.mCodePoint)

        pairing.clearPendingActions()
        convert(down(MIC_KEYCODE_7, 8), KeyEvent.META_ALT_ON)
        assertArmedOnly(PendingKeyAction.VOICE_INPUT)

        pairing.clearPendingActions()
        convert(down(MIC_KEYCODE_7, 8, metaState = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON),
            KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON)
        assertArmedOnly(PendingKeyAction.VOICE_INPUT)
    }

    /** An ordinary letter arms nothing at all. */
    @Test
    fun `an ordinary letter arms nothing`() {
        installKey2(multifunctionAction = MultifunctionKeyHandler.ACTION_LANGUAGE_SWITCH)

        convert(down(KeyEvent.KEYCODE_A, /* scanCode */ 30))

        assertNothingArmed()
    }

    // ===================================================== helpers

    private fun assertArmedOnly(expected: PendingKeyAction) {
        for (action in PendingKeyAction.values()) {
            assertEquals("$action", action == expected, pairing.isArmed(action))
        }
    }

    private fun assertNothingArmed() {
        for (action in PendingKeyAction.values()) {
            assertFalse("$action must not be armed", pairing.isArmed(action))
        }
    }

    private fun assertArmedBy(action: PendingKeyAction, keyCode: Int, scanCode: Int) {
        val armed = pairing.armedBy(action)
        assertNotNull("$action is not armed", armed)
        assertEquals(keyCode, armed!!.keyCode())
        assertEquals(scanCode, armed.scanCode())
    }

    /**
     * What the converter answers for a key whose action fires on release: a gesture-end event with
     * no data, which is how [KeyEventProcessor] knows to consume the key rather than let its base
     * character through to the app.
     */
    private fun assertConsumedWithoutTyping(result: InputEvent) {
        assertTrue("the key must be consumed, not typed", result.isGestureEnd())
        assertFalse("and it must carry no character", result.hasData())
    }

    private fun convert(event: KeyEvent, computedMeta: Int = 0): InputEvent =
        converter.convertKeyEvent(event, computedMeta, InputType.TYPE_CLASS_TEXT)

    private fun down(keyCode: Int, scanCode: Int, repeat: Int = 0, metaState: Int = 0): KeyEvent =
        KeyEvent(
            0L, 0L, KeyEvent.ACTION_DOWN, keyCode, repeat, metaState,
            /* deviceId */ 0, scanCode, /* flags */ 0, InputDevice.SOURCE_KEYBOARD
        )

    private fun loadSettings() {
        SettingsManager.initialize(context)
        SettingsManager.getInstance().loadSettings(
            context, Locale.US,
            EditorCapabilities(null, false, context.packageName, Locale.US, false)
        )
    }

    private fun buildConverter() {
        converter = KeyEventConverter(
            /* deviceId */ 0,
            // No special interpretation: fall through to the key handling under test.
            { _, _ -> null },
            context,
            AuxCharacterResolver.Builder().build()
        )
    }

    private fun installMp01() {
        DeviceProfile.installForTest(
            DeviceCapabilities.forShape(
                DeviceCapabilities.DetectedDeviceType.PKB,
                /* hasPhysicalKeyboard */ true, /* hasTouchKeypad */ false,
                /* isBlackBerryDevice */ false, "qwerty", "4row"
            )
        )
        installConfig(R.xml.device_config_minimal, "aw9523b-key")
    }

    private fun installKey2(multifunctionAction: String, expectedStored: String? = multifunctionAction) {
        PrefsManager.getPrefs(context).edit()
            .putString(MultifunctionKeyHandler.PREF_KEY, multifunctionAction).commit()
        loadSettings()
        if (expectedStored != null) {
            assertEquals(
                "premise: the multifunction action pref did not take",
                expectedStored,
                SettingsManager.getInstance().settingsValues.multifunctionKeyAction
            )
        }
        DeviceProfile.installForTest(
            DeviceCapabilities.forShape(
                DeviceCapabilities.DetectedDeviceType.PKB,
                /* hasPhysicalKeyboard */ true, /* hasTouchKeypad */ true,
                /* isBlackBerryDevice */ true, "qwerty", "4row"
            )
        )
        val config = DeviceInputMappingParser
            .parseConfigFromXmlResource(context, R.xml.device_config_athena)
        assertNotNull("device_config_athena.xml failed to parse", config)
        install(config.mappings[0])
    }

    /** A PKB device with no scancode mappings at all: the legacy fallback paths. */
    private fun installBareP() {
        DeviceProfile.installForTest(
            DeviceCapabilities.forShape(
                DeviceCapabilities.DetectedDeviceType.PKB,
                /* hasPhysicalKeyboard */ true, /* hasTouchKeypad */ false,
                /* isBlackBerryDevice */ true, "qwerty", "4row"
            )
        )
        ScancodeMappingResolver.getInstance().reset()
        KeyEventDeviceClassifier.getInstance().clearCache()
        buildConverter()
    }

    private fun installConfig(xmlRes: Int, deviceName: String) {
        val config = DeviceInputMappingParser.parseConfigFromXmlResource(context, xmlRes)
        assertNotNull("config $xmlRes failed to parse", config)
        val mapping = config.findMappingForDevice(deviceName)
        assertNotNull("config $xmlRes no longer matches $deviceName", mapping)
        install(mapping)
    }

    private fun install(mapping: DeviceInputMapping) {
        DeviceProfileTestSupport.installMapping(mapping)
        ScancodeMappingResolver.getInstance().initialize(mapping)
        KeyEventDeviceClassifier.getInstance().clearCache()
        buildConverter()
    }
}

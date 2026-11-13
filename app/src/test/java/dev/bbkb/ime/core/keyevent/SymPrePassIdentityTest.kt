package dev.bbkb.ime.core.keyevent

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.R
import dev.bbkb.ime.core.BlackBerryIME
import dev.bbkb.ime.core.device.config.model.KeyRole
import dev.bbkb.ime.core.device.config.parser.DeviceInputMappingParser
import dev.bbkb.ime.core.device.config.resolver.DeviceInputResolver
import dev.bbkb.ime.core.device.detection.KeyEventDeviceClassifier
import dev.bbkb.ime.core.device.profile.DeviceCapabilities
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.device.profile.DeviceProfileTestSupport
import dev.bbkb.ime.core.device.state.PhysicalKeyboardStateTracker
import dev.bbkb.ime.core.ime.HardwareKeyBridge
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.settings.util.SettingsManager
import dev.bbkb.ime.core.textinput.connection.EditorCapabilities
import dev.bbkb.ime.keyboard.KeyboardSwitcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * The accessibility pre-pass (`processKeyEventForState`) identifies the Sym key through
 * [ResolvedKey], like `onKeyDownInternal`. On the MP01 Sym is `KEYCODE_ALT_RIGHT` on scancode 249
 * (role BOARD_SYM), which `HardwareKeyBridge` routes into the pre-pass and consumes; the KEY2
 * (no Sym mapping in `device_config_athena.xml`) must behave exactly as a raw keycode test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SymPrePassIdentityTest {

    /** The MP01 Sym key's raw hardware scancode. */
    private val MP01_SYM_SCANCODE = 249

    private lateinit var context: Context
    private lateinit var ime: BlackBerryIME
    private lateinit var keyboardSwitcher: KeyboardSwitcher
    private lateinit var tracker: PhysicalKeyboardStateTracker
    private lateinit var handler: AltSymShortcutHandler
    private lateinit var processor: KeyEventProcessor
    private lateinit var actions: RecordingActions

    /** Records which configured action the Alt+Sym chord dispatched, if any. */
    private class RecordingActions : AltSymShortcutHandler.ActionCallback {
        var symbolKeyboard = 0
        var clipboard = 0
        var fcc = 0
        var numberPad = 0
        var languageSwitch = 0
        var emojiPicker = 0
        var inputMenu = 0
        var ctrlTaps = 0
        override fun openSymbolKeyboard() { symbolKeyboard++ }
        override fun switchLanguage() { languageSwitch++ }
        override fun quickSwitchLanguage() { languageSwitch++ }
        override fun toggleEmojiPicker() { emojiPicker++ }
        override fun toggleClipboard() { clipboard++ }
        override fun toggleFcc() { fcc++ }
        override fun toggleNumberPad() { numberPad++ }
        override fun toggleInputMenu() { inputMenu++ }
        override fun tapCtrl() { ctrlTaps++ }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PrefsManager.getPrefs(context).edit().clear()
            .putString(AltSymShortcutHandler.PREF_KEY, AltSymShortcutHandler.ACTION_EMOJI_BOARD)
            .commit()
        SettingsManager.initialize(context)
        SettingsManager.getInstance().loadSettings(
            context, Locale.US,
            EditorCapabilities(null, false, context.packageName, Locale.US, false)
        )
        assertEquals(
            AltSymShortcutHandler.ACTION_EMOJI_BOARD,
            SettingsManager.getInstance().settingsValues.altSymShortcutAction
        )

        keyboardSwitcher = mock(KeyboardSwitcher::class.java)
        tracker = PhysicalKeyboardStateTracker(null)
        ime = mock(BlackBerryIME::class.java)

        val bridge = HardwareKeyBridge(ime)
        handler = bridge.altSymShortcutHandler
        actions = RecordingActions()
        handler.setCallback(actions)

        `when`(ime.getHardwareKeys()).thenReturn(bridge)
        `when`(ime.getPhysicalKeyboardStateTracker()).thenReturn(tracker)
        `when`(ime.getKeyboardSwitcher()).thenReturn(keyboardSwitcher)
        `when`(ime.getApplicationContext()).thenReturn(context)
        `when`(ime.getCurrentInputType()).thenReturn(0)
        `when`(ime.getCurrentImeOptions()).thenReturn(0)
        `when`(ime.isInputViewShown()).thenReturn(false)

        processor = KeyEventProcessor(ime)
    }

    @After
    fun tearDown() {
        DeviceInputResolver.resetConfig()
        KeyEventDeviceClassifier.getInstance().clearCache()
        DeviceProfile.initialize(null)
        PrefsManager.getPrefs(context).edit().clear().commit()
    }

    /**
     * A real {@code KEYCODE_SYM} on a KEY2 reaches the pre-pass and toggles the symbol board. The
     * premise is asserted first: the athena config maps no Sym key, so identity falls back to the
     * key code.
     */
    @Test
    fun `a KEY2 Sym still toggles the symbol board on the pre-pass`() {
        installKey2()

        processor.processKeyEventForState(
            physical(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SYM, scanCode = 0)
        )

        assertEquals("no Alt is not a chord", 0, actions.emojiPicker)
        verify(keyboardSwitcher).onSymbolShiftToggle(0, 0, false, true)
    }

    /** The same KEY2 Sym with Alt held: the chord, on the pre-pass. */
    @Test
    fun `a KEY2 Sym still fires the chord on the pre-pass`() {
        installKey2()

        processor.processKeyEventForState(
            physical(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SYM, 0, KeyEvent.META_ALT_ON)
        )

        assertEquals(1, actions.emojiPicker)
        verify(keyboardSwitcher, never())
            .onSymbolShiftToggle(anyInt(), anyInt(), anyBoolean(), anyBoolean())
    }

    /** And on the ordinary hardware key path, which already asked [ResolvedKey]. */
    @Test
    fun `a KEY2 Sym still fires the chord on the ordinary key-down path`() {
        installKey2()
        val down = physical(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SYM, 0, KeyEvent.META_ALT_ON)
        `when`(ime.remapKeyEvent(KeyEvent.KEYCODE_SYM, down)).thenReturn(down)

        assertTrue(processor.onKeyDownInternal(KeyEvent.KEYCODE_SYM, down))
        assertEquals(1, actions.emojiPicker)
    }

    /** An ordinary letter is not the Sym key on the pre-pass, mapping or no mapping. */
    @Test
    fun `an ordinary key is not the Sym key on the pre-pass`() {
        installKey2()

        processor.processKeyEventForState(
            physical(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, scanCode = 30)
        )

        assertEquals(0, actions.emojiPicker)
        verify(keyboardSwitcher, never())
            .onSymbolShiftToggle(anyInt(), anyInt(), anyBoolean(), anyBoolean())
    }

    /** The same MP01 Sym event through both entry points: both recognise it. */
    @Test
    fun `an MP01 Sym is recognised by both paths`() {
        installMp01()
        val down = mp01Sym(KeyEvent.META_ALT_ON)
        `when`(ime.remapKeyEvent(KeyEvent.KEYCODE_ALT_RIGHT, down)).thenReturn(down)

        assertTrue(
            "premise: the ordinary key path already calls this key the Sym key",
            processor.onKeyDownInternal(KeyEvent.KEYCODE_ALT_RIGHT, down)
        )
        assertEquals(1, actions.emojiPicker)

        // A fresh handler state for the second path: the chord above claimed the key-up.
        handler.consumeSymKeyUp()
        processor.processKeyEventForState(mp01Sym(KeyEvent.META_ALT_ON))

        assertEquals("the pre-pass must reach the same conclusion", 2, actions.emojiPicker)
    }

    /**
     * A bare MP01 Sym on the pre-pass toggles the symbol board rather than being consumed by the
     * accessibility service and dropped.
     */
    @Test
    fun `a bare MP01 Sym toggles the symbol board on the pre-pass`() {
        installMp01()

        processor.processKeyEventForState(mp01Sym(metaState = 0))

        verify(keyboardSwitcher).onSymbolShiftToggle(0, 0, false, true)
    }

    /**
     * The MP01's Sym key is also its right-Alt key code, so the pre-pass must name it to the
     * tracker as the Sym key, or `isSymKeyHeld()` ("hold Sym to keep the symbol board open")
     * cannot answer on that device.
     */
    @Test
    fun `the pre-pass names the MP01 Sym key code to the tracker`() {
        installMp01()

        processor.processKeyEventForState(mp01Sym(metaState = 0))

        assertTrue(
            "the tracker must learn which key code Sym answers to on this device,"
                    + " or 'hold Sym to keep the symbol board open' cannot work there",
            tracker.isSymKeyHeld()
        )
    }

    /** A config that gives the key another role is authoritative: not the Sym key. */
    @Test
    fun `an MP01 emoji key is not the Sym key on the pre-pass`() {
        installMp01()

        // scancode 250 is <key rawScanCode="250" role="BOARD_EMOJI"/> in device_config_minimal.xml
        processor.processKeyEventForState(
            physical(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_RIGHT, scanCode = 250)
        )

        assertEquals(0, actions.emojiPicker)
        verify(keyboardSwitcher, never())
            .onSymbolShiftToggle(anyInt(), anyInt(), anyBoolean(), anyBoolean())
    }

    /**
     * A KEY2-shaped profile with the shipped `device_config_athena.xml`, which must keep naming no
     * Sym key so `KEYCODE_SYM` identity comes from the key code.
     */
    private fun installKey2() {
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
        val mapping = config.mappings[0]
        DeviceProfileTestSupport.installMapping(mapping)
        DeviceInputResolver.installPhoneProfileForTest(mapping)
        KeyEventDeviceClassifier.getInstance().clearCache()

        assertNull(
            "device_config_athena.xml now maps the KEY2's Sym key — this suite's KEY2 claim"
                    + " (identity falls back to the key code) no longer holds",
            DeviceInputResolver.keyRole(0, KeyEvent.KEYCODE_SYM, DeviceInputResolver.ALL_DEVICES)
        )
    }

    /** An MP01-shaped profile with the shipped `device_config_minimal.xml` loaded. */
    private fun installMp01() {
        DeviceProfile.installForTest(
            DeviceCapabilities.forShape(
                DeviceCapabilities.DetectedDeviceType.PKB,
                /* hasPhysicalKeyboard */ true, /* hasTouchKeypad */ false,
                /* isBlackBerryDevice */ false, "qwerty", "4row"
            )
        )
        val config = DeviceInputMappingParser
            .parseConfigFromXmlResource(context, R.xml.device_config_minimal)
        assertNotNull("device_config_minimal.xml failed to parse", config)
        val mapping = config.findMappingForDevice("aw9523b-key")
        assertNotNull("device_config_minimal.xml no longer matches aw9523b-key", mapping)
        DeviceProfileTestSupport.installMapping(mapping)
        DeviceInputResolver.installPhoneProfileForTest(mapping)
        KeyEventDeviceClassifier.getInstance().clearCache()

        val sym = DeviceInputResolver.keyRole(MP01_SYM_SCANCODE, KeyEvent.KEYCODE_ALT_RIGHT, DeviceInputResolver.ALL_DEVICES)
        assertNotNull("premise: scancode 249 must resolve on an MP01", sym)
        assertEquals(
            "premise: and it must be the BOARD_SYM role", KeyRole.BOARD_SYM, sym!!.role
        )
    }

    /** What the MP01 ROM sends for its Sym key: KEYCODE_ALT_RIGHT on scancode 249. */
    private fun mp01Sym(metaState: Int): KeyEvent =
        physical(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_RIGHT, MP01_SYM_SCANCODE, metaState)

    private fun physical(action: Int, keyCode: Int, scanCode: Int, metaState: Int = 0): KeyEvent =
        KeyEvent(
            0L, 0L, action, keyCode, /* repeat */ 0, metaState,
            /* deviceId */ 0, scanCode, /* flags */ 0, InputDevice.SOURCE_KEYBOARD
        )
}

package dev.bbkb.ime.core.keyevent

import android.view.KeyEvent
import dev.bbkb.ime.core.BlackBerryIME
import dev.bbkb.ime.core.device.config.resolver.DeviceInputResolver
import dev.bbkb.ime.core.device.config.resolver.KeyRoleTestSupport
import dev.bbkb.ime.core.device.detection.KeyEventDeviceClassifier
import dev.bbkb.ime.core.device.profile.DeviceCapabilities
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.locale.SubtypeManager
import dev.bbkb.ime.core.textinput.InputMethodHelper
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.MockedStatic
import org.mockito.Mockito.RETURNS_DEEP_STUBS
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * A hinted physical key released on the PKB symbol board ends a one-shot symbol entry through
 * [KeyEventProcessor.onKeyUpInternal] -> `KeyboardSwitcher.onSymbolKeyLongPress`, unless Sym is
 * held (which keeps the board open). Held Sym comes from the tracker, not `META_SYM_ON`, which the
 * KEY2's Sym key does not set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SymbolOneShotReleaseTest {
    private lateinit var classifierStatic: MockedStatic<KeyEventDeviceClassifier>
    private lateinit var subtypeStatic: MockedStatic<SubtypeManager>
    private lateinit var helperStatic: MockedStatic<InputMethodHelper>

    private lateinit var ime: BlackBerryIME
    private lateinit var processor: KeyEventProcessor

    @Before
    fun setUp() {
        // Every physical key event in this test comes from a hardware keyboard.
        classifierStatic = mockStatic(KeyEventDeviceClassifier::class.java)
        val classifier = mock(KeyEventDeviceClassifier::class.java)
        `when`(classifier.isPhysicalKeyboardEvent(anyArg())).thenReturn(true)
        classifierStatic.`when`<KeyEventDeviceClassifier> { KeyEventDeviceClassifier.getInstance() }
            .thenReturn(classifier)

        // No scancode role for these keys: not a board key, not emoji/voice/multifunction.
        KeyRoleTestSupport.everyKeyIs(null)

        subtypeStatic = mockStatic(SubtypeManager::class.java)
        val subtypeManager = mock(SubtypeManager::class.java)
        `when`(subtypeManager.currentSubtypeLocale).thenReturn(Locale.US)
        subtypeStatic.`when`<SubtypeManager> { SubtypeManager.getInstance() }
            .thenReturn(subtypeManager)

        helperStatic = mockStatic(InputMethodHelper::class.java)
        helperStatic.`when`<InputMethodHelper> { InputMethodHelper.getInstance() }
            .thenReturn(mock(InputMethodHelper::class.java))

        ime = mock(BlackBerryIME::class.java, RETURNS_DEEP_STUBS)
        `when`(ime.isInputActive).thenReturn(true)
        `when`(ime.isInputViewShown).thenReturn(true)
        `when`(ime.isAltEnterPressed).thenReturn(false)
        `when`(ime.auxBarManager).thenReturn(null)
        `when`(ime.vkbGestureListener).thenReturn(null)
        `when`(ime.remapKeyEvent(anyInt(), anyArg())).thenAnswer { it.arguments[1] as KeyEvent }
        `when`(ime.getControlMode().handleHardKeyUp(anyInt(), anyArg())).thenReturn(false)
        // The key-DOWN path tracked this key, which is what routes it into the branch under test.
        `when`(ime.getInputLogic().isKeyTracked(anyArg())).thenReturn(true)
        `when`(ime.getKeyboardSwitcher().getKeyByPhysicalScanCode(anyInt(), anyBoolean())).thenReturn(null)
        `when`(ime.getPhysicalKeyboardStateTracker().getSymbolPageOrder()).thenReturn(0)
        // ModifierState.isSymHeld() is the tracker's isSymKeyHeld() OR the event's META_SYM_ON.
        `when`(ime.getPhysicalKeyboardStateTracker().getModifierState(anyArg<KeyEvent>()))
            .thenReturn(ModifierState.builder().symKeyHeld(false).build())
        `when`(ime.superOnKeyUp(anyInt(), anyArg())).thenReturn(false)

        DeviceProfile.installForTest(pkbShape())
        processor = KeyEventProcessor(ime)
    }

    @After
    fun tearDown() {
        classifierStatic.close()
        DeviceInputResolver.resetConfig()
        subtypeStatic.close()
        helperStatic.close()
    }

    private fun hintedKeyPresent() {
        `when`(ime.getKeyboardSwitcher().getKeyByPhysicalScanCode(anyInt(), anyBoolean()))
            .thenReturn(mock(dev.bbkb.ime.keyboard.Key::class.java))
    }

    @Test
    fun `with Sym not held, releasing a hinted key ends the one-shot entry`() {
        hintedKeyPresent()
        processor.onKeyUpInternal(KeyEvent.KEYCODE_R, keyUp(KeyEvent.KEYCODE_R))
        verify(ime.getKeyboardSwitcher()).onSymbolKeyLongPress(anyInt(), anyInt())
    }

    @Test
    fun `while Sym is held, releasing a hinted key leaves the board open`() {
        hintedKeyPresent()
        `when`(ime.getPhysicalKeyboardStateTracker().getModifierState(anyArg<KeyEvent>()))
            .thenReturn(ModifierState.builder().symKeyHeld(true).build())
        processor.onKeyUpInternal(KeyEvent.KEYCODE_R, keyUp(KeyEvent.KEYCODE_R))
        verify(ime.getKeyboardSwitcher(), never()).onSymbolKeyLongPress(anyInt(), anyInt())
    }

    @Test
    fun `a key with no hint on the board never ends the entry`() {
        processor.onKeyUpInternal(KeyEvent.KEYCODE_R, keyUp(KeyEvent.KEYCODE_R))
        verify(ime.getKeyboardSwitcher(), never()).onSymbolKeyLongPress(anyInt(), anyInt())
    }

    private fun <T> anyArg(): T = ArgumentMatchers.any<T>() as T

    private fun keyUp(keyCode: Int): KeyEvent =
        KeyEvent(0L, 0L, KeyEvent.ACTION_UP, keyCode, 0, 0, 0, 30)

    private fun pkbShape(): DeviceCapabilities = DeviceCapabilities.forShape(
        DeviceCapabilities.DetectedDeviceType.PKB,
        /* hasPhysicalKeyboard= */ true,
        /* hasTouchKeypad= */ false,
        /* isBlackBerryDevice= */ true,
        "qwerty",
        "4row"
    )

    private fun vkbShape(): DeviceCapabilities = DeviceCapabilities.forShape(
        DeviceCapabilities.DetectedDeviceType.VKB,
        /* hasPhysicalKeyboard= */ false,
        /* hasTouchKeypad= */ false,
        /* isBlackBerryDevice= */ false,
        "qwerty",
        "none"
    )
}

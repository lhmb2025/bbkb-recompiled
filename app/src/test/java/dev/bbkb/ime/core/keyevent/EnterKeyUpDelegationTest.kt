package dev.bbkb.ime.core.keyevent

import android.view.KeyEvent
import dev.bbkb.ime.core.BlackBerryIME
import dev.bbkb.ime.core.device.config.resolver.ScancodeMappingResolver
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
 * The hardware ENTER key-up must reach the app, not be swallowed by the IME.
 *
 * The 2026-08-27 WhatsApp parity change made the key-DOWN side return
 * [BlackBerryIME.superOnKeyDown] so the APP receives the raw ENTER and applies its own
 * convention, and its comment asserts that "the key-UP side already passes Enter through".
 * It did not: [KeyEventProcessor.onKeyUpInternal]'s tracked-key branch consumed every key-up,
 * so the app saw an ACTION_DOWN with no matching ACTION_UP — half a confirm-key press, which
 * is what a lock screen's password field (and anything else that pairs the two halves) never
 * gets from the stock keyboard. The original delegates it:
 *
 * ```
 * return !(isShiftKey(keyCode) || (keyCode == 66 && N() != 1)) || super.onKeyUp(keyCode, event);
 * ```
 *
 * with `N()` == `getSymbolPageOrder()`. These cases pin that rule.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class EnterKeyUpDelegationTest {

    private lateinit var classifierStatic: MockedStatic<KeyEventDeviceClassifier>
    private lateinit var resolverStatic: MockedStatic<ScancodeMappingResolver>
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
        resolverStatic = mockStatic(ScancodeMappingResolver::class.java)
        val resolver = mock(ScancodeMappingResolver::class.java)
        `when`(resolver.resolve(anyInt(), anyInt())).thenReturn(null)
        resolverStatic.`when`<ScancodeMappingResolver> { ScancodeMappingResolver.getInstance() }
            .thenReturn(resolver)

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
        `when`(ime.superOnKeyUp(anyInt(), anyArg())).thenReturn(false)

        DeviceProfile.installForTest(pkbShape())
        processor = KeyEventProcessor(ime)
    }

    @After
    fun tearDown() {
        classifierStatic.close()
        resolverStatic.close()
        subtypeStatic.close()
        helperStatic.close()
    }

    @Test
    fun `hardware Enter key-up is handed to the app`() {
        val consumed = processor.onKeyUpInternal(KeyEvent.KEYCODE_ENTER, keyUp(KeyEvent.KEYCODE_ENTER))

        assertFalse("ENTER key-up must not be consumed by the IME", consumed)
        verify(ime).superOnKeyUp(eq(KeyEvent.KEYCODE_ENTER), anyArg())
    }

    @Test
    fun `an ordinary character key-up is still consumed`() {
        val consumed = processor.onKeyUpInternal(KeyEvent.KEYCODE_A, keyUp(KeyEvent.KEYCODE_A))

        assertTrue("only ENTER changes; other tracked key-ups stay consumed", consumed)
        verify(ime, never()).superOnKeyUp(eq(KeyEvent.KEYCODE_A), anyArg())
    }

    @Test
    fun `Enter key-up is consumed while the shift page is active`() {
        // The original's N() != 1 guard: Shift+Enter is answered by the key-DOWN side's own
        // branch, so the release stays with the IME.
        `when`(ime.getPhysicalKeyboardStateTracker().getSymbolPageOrder()).thenReturn(1)

        val consumed = processor.onKeyUpInternal(KeyEvent.KEYCODE_ENTER, keyUp(KeyEvent.KEYCODE_ENTER))

        assertTrue(consumed)
        verify(ime, never()).superOnKeyUp(eq(KeyEvent.KEYCODE_ENTER), anyArg())
    }

    @Test
    fun `Enter key-up is consumed on a VKB shape`() {
        // There the key-DOWN side answers Enter with performEditorAction and consumes the press,
        // so releasing the UP alone would be a confirm-key release the app never saw pressed.
        DeviceProfile.installForTest(vkbShape())

        val consumed = processor.onKeyUpInternal(KeyEvent.KEYCODE_ENTER, keyUp(KeyEvent.KEYCODE_ENTER))

        assertTrue(consumed)
        verify(ime, never()).superOnKeyUp(eq(KeyEvent.KEYCODE_ENTER), anyArg())
    }

    /** Kotlin needs a non-null stand-in for Mockito's any(); the value is never read. */
    @Suppress("UNCHECKED_CAST")
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

package dev.bbkb.ime.core.keyevent

import android.content.Context
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.BlackBerryIME
import dev.bbkb.ime.core.device.config.resolver.ScancodeMappingResolver
import dev.bbkb.ime.core.device.detection.KeyEventDeviceClassifier
import dev.bbkb.ime.core.device.profile.DeviceCapabilities
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.locale.SubtypeManager
import dev.bbkb.ime.core.settings.PrefsManager
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
 * Phase 1e, the one deliberate behaviour decision (owner, 2026-09-22).
 *
 * <p>`KeyEventProcessor` used to ask "is Sym held?" two different ways, and on a BlackBerry KEY2
 * — whose Sym key does not set `META_SYM_ON` — the two disagreed:
 *
 * <ul>
 *   <li>the key-DOWN Alt fallthrough asked `ModifierState.ofEvent(event)`, i.e. the event's own
 *       meta state and nothing else, so on a KEY2 a held Sym was invisible there;</li>
 *   <li>the key-UP one-shot symbol release asked
 *       `PhysicalKeyboardStateTracker.getModifierState(event)`, which is that meta state OR the
 *       tracker's held-key set, so the held Sym was seen.</li>
 * </ul>
 *
 * <p>Both now read the tracker. This suite is the pin: build an event whose meta state says Sym
 * is NOT pressed while the tracker says the Sym key IS held, and the held-Sym behaviour has to
 * win on both paths. It fails against the pre-Phase-1e key-DOWN path, which is the point —
 * `KeyEvent.isSymPressed()` / `META_SYM_ON` is never the question being asked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SymHeldReadsTrackerTest {

    private lateinit var context: Context
    private lateinit var classifierStatic: MockedStatic<KeyEventDeviceClassifier>
    private lateinit var resolverStatic: MockedStatic<ScancodeMappingResolver>
    private lateinit var subtypeStatic: MockedStatic<SubtypeManager>
    private lateinit var helperStatic: MockedStatic<InputMethodHelper>

    private lateinit var ime: BlackBerryIME
    private lateinit var processor: KeyEventProcessor

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PrefsManager.getPrefs(context).edit().clear().commit()
        DeviceProfile.initialize(context)
        DeviceProfile.installForTest(key2Shape())

        classifierStatic = mockStatic(KeyEventDeviceClassifier::class.java)
        val classifier = mock(KeyEventDeviceClassifier::class.java)
        `when`(classifier.isPhysicalKeyboardEvent(anyArg())).thenReturn(true)
        classifierStatic.`when`<KeyEventDeviceClassifier> { KeyEventDeviceClassifier.getInstance() }
            .thenReturn(classifier)

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
        `when`(ime.applicationContext).thenReturn(context)
        `when`(ime.packageName).thenReturn(context.packageName)
        `when`(ime.isInputActive).thenReturn(true)
        `when`(ime.isInputViewShown).thenReturn(true)
        `when`(ime.isAltEnterPressed).thenReturn(false)
        `when`(ime.auxBarManager).thenReturn(null)
        `when`(ime.vkbGestureListener).thenReturn(null)
        `when`(ime.remapKeyEvent(anyInt(), anyArg())).thenAnswer { it.arguments[1] as KeyEvent }
        `when`(ime.getControlMode().handleHardKeyDown(anyInt(), anyArg())).thenReturn(false)
        `when`(ime.getControlMode().handleHardKeyUp(anyInt(), anyArg())).thenReturn(false)
        `when`(ime.getPhysicalKeyboardStateTracker().getSymbolPageOrder()).thenReturn(0)
        `when`(ime.superOnKeyDown(anyInt(), anyArg())).thenReturn(false)
        `when`(ime.superOnKeyUp(anyInt(), anyArg())).thenReturn(false)

        processor = KeyEventProcessor(ime)
    }

    @After
    fun tearDown() {
        classifierStatic.close()
        resolverStatic.close()
        subtypeStatic.close()
        helperStatic.close()
        DeviceProfile.initialize(null)
        PrefsManager.getPrefs(context).edit().clear().commit()
    }

    /**
     * The premise the whole suite rests on: a KEY2 Sym key sets no `META_SYM_ON`, so an event
     * read on its own reports "Sym not held" while the tracker reports "held".
     */
    @Test
    fun `the event and the tracker disagree, which is what makes this a decision`() {
        val event = keyDown(KeyEvent.KEYCODE_ALT_LEFT)

        assertFalse(
            "premise: this event's own meta state says Sym is not pressed",
            ModifierState.ofEvent(event).isSymHeld()
        )
        assertTrue(
            "premise: the tracker's held-key set says it is",
            symHeld().isSymHeld()
        )
    }

    // ===================================================== key DOWN (the site that changed)

    @Test
    fun `a held Sym suppresses the Alt symbol long press on the key-down path`() {
        trackerSays(symHeld())

        processor.onKeyDownInternal(KeyEvent.KEYCODE_ALT_LEFT, keyDown(KeyEvent.KEYCODE_ALT_LEFT))

        verify(ime.getKeyboardSwitcher(), never()).onSymbolKeyLongPress(anyInt(), anyInt())
    }

    @Test
    fun `with no held Sym the Alt symbol long press still fires on the key-down path`() {
        trackerSays(ModifierState.none())

        processor.onKeyDownInternal(KeyEvent.KEYCODE_ALT_LEFT, keyDown(KeyEvent.KEYCODE_ALT_LEFT))

        verify(ime.getKeyboardSwitcher()).onSymbolKeyLongPress(anyInt(), anyInt())
    }

    // ===================================================== key UP (already read the tracker)

    @Test
    fun `a held Sym keeps the symbol board open on the key-up path`() {
        trackerSays(symHeld())
        trackedWithHint()

        processor.onKeyUpInternal(KeyEvent.KEYCODE_R, keyUp(KeyEvent.KEYCODE_R))

        verify(ime.getKeyboardSwitcher(), never()).onSymbolKeyLongPress(anyInt(), anyInt())
    }

    @Test
    fun `with no held Sym the key-up path ends the one-shot entry`() {
        trackerSays(ModifierState.none())
        trackedWithHint()

        processor.onKeyUpInternal(KeyEvent.KEYCODE_R, keyUp(KeyEvent.KEYCODE_R))

        verify(ime.getKeyboardSwitcher()).onSymbolKeyLongPress(anyInt(), anyInt())
    }

    // ===================================================== helpers

    /** The tracker's answer: Sym held, and no `META_SYM_ON` anywhere in the event. */
    private fun symHeld(): ModifierState = ModifierState.builder().symKeyHeld(true).build()

    private fun trackerSays(state: ModifierState) {
        `when`(ime.getPhysicalKeyboardStateTracker().getModifierState(anyArg<KeyEvent>()))
            .thenReturn(state)
    }

    private fun trackedWithHint() {
        `when`(ime.getInputLogic().isKeyTracked(anyArg())).thenReturn(true)
        `when`(ime.getKeyboardSwitcher().getKeyByPhysicalScanCode(anyInt(), anyBoolean()))
            .thenReturn(mock(dev.bbkb.ime.keyboard.Key::class.java))
    }

    private fun <T> anyArg(): T = ArgumentMatchers.any<T>() as T

    private fun keyDown(keyCode: Int): KeyEvent =
        KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, keyCode, 0, /* metaState */ 0, 0, 30)

    private fun keyUp(keyCode: Int): KeyEvent =
        KeyEvent(0L, 0L, KeyEvent.ACTION_UP, keyCode, 0, /* metaState */ 0, 0, 30)

    private fun key2Shape(): DeviceCapabilities = DeviceCapabilities.forShape(
        DeviceCapabilities.DetectedDeviceType.PKB,
        /* hasPhysicalKeyboard= */ true,
        /* hasTouchKeypad= */ true,
        /* isBlackBerryDevice= */ true,
        "qwerty",
        "4row"
    )
}

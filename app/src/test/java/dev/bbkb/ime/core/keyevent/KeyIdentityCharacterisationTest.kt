package dev.bbkb.ime.core.keyevent

import android.view.KeyEvent
import dev.bbkb.ime.core.BlackBerryIME
import dev.bbkb.ime.core.device.config.model.KeyRole
import dev.bbkb.ime.core.device.config.model.ScancodeMapping
import dev.bbkb.ime.core.device.config.resolver.DeviceInputResolver
import dev.bbkb.ime.core.device.config.resolver.KeyRoleTestSupport
import dev.bbkb.ime.core.device.detection.KeyEventDeviceClassifier
import dev.bbkb.ime.core.device.profile.DeviceCapabilities
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.locale.SubtypeManager
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.settings.util.SettingsManager
import dev.bbkb.ime.core.textinput.connection.EditorCapabilities
import androidx.test.core.app.ApplicationProvider
import android.content.Context
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
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Pins key identity only, through [KeyEventProcessor]'s two entry points: is the event physical,
 * what the remap turned it into, what the device config calls it, and what pseudo-keycodes
 * 666 / 667 mean. Asserts on observable effects (board requested, framework hand-back), not on
 * branch shape.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class KeyIdentityCharacterisationTest {

    private lateinit var context: Context
    private lateinit var classifierStatic: MockedStatic<KeyEventDeviceClassifier>
    private lateinit var subtypeStatic: MockedStatic<SubtypeManager>

    private lateinit var classifier: KeyEventDeviceClassifier

    private lateinit var ime: BlackBerryIME
    private lateinit var processor: KeyEventProcessor

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PrefsManager.getPrefs(context).edit().clear().commit()
        // DeviceProfile.appContext() must be non-null or the key-down path re-detects the
        // profile from the (keyboard-less) JVM.
        DeviceProfile.initialize(context)
        DeviceProfile.installForTest(pkbShape())
        SettingsManager.initialize(context)
        SettingsManager.getInstance().loadSettings(
            context, Locale.US,
            EditorCapabilities(null, false, context.packageName, Locale.US, false)
        )

        classifierStatic = mockStatic(KeyEventDeviceClassifier::class.java)
        classifier = mock(KeyEventDeviceClassifier::class.java)
        `when`(classifier.isPhysicalKeyboardEvent(anyArg())).thenReturn(true)
        classifierStatic.`when`<KeyEventDeviceClassifier> { KeyEventDeviceClassifier.getInstance() }
            .thenReturn(classifier)

        KeyRoleTestSupport.everyKeyIs(null)

        subtypeStatic = mockStatic(SubtypeManager::class.java)
        val subtypeManager = mock(SubtypeManager::class.java)
        `when`(subtypeManager.currentSubtypeLocale).thenReturn(Locale.US)
        subtypeStatic.`when`<SubtypeManager> { SubtypeManager.getInstance() }
            .thenReturn(subtypeManager)

        ime = mock(BlackBerryIME::class.java, RETURNS_DEEP_STUBS)
        `when`(ime.applicationContext).thenReturn(context)
        `when`(ime.packageName).thenReturn(context.packageName)
        `when`(ime.isInputActive).thenReturn(true)
        `when`(ime.isInputViewShown).thenReturn(true)
        `when`(ime.isUimEnabled()).thenReturn(true)
        `when`(ime.isAltEnterPressed).thenReturn(false)
        `when`(ime.auxBarManager).thenReturn(null)
        `when`(ime.vkbGestureListener).thenReturn(null)
        `when`(ime.remapKeyEvent(anyInt(), anyArg())).thenAnswer { it.arguments[1] as KeyEvent }
        `when`(ime.getControlMode().handleHardKeyDown(anyInt(), anyArg())).thenReturn(false)
        `when`(ime.getControlMode().handleHardKeyUp(anyInt(), anyArg())).thenReturn(false)
        `when`(ime.getInputLogic().isKeyTracked(anyArg())).thenReturn(false)
        `when`(ime.getPhysicalKeyboardStateTracker().getSymbolPageOrder()).thenReturn(0)
        `when`(ime.getPhysicalKeyboardStateTracker().getModifierState(anyArg<KeyEvent>()))
            .thenReturn(ModifierState.none())
        `when`(ime.superOnKeyUp(anyInt(), anyArg())).thenReturn(false)
        `when`(ime.superOnKeyDown(anyInt(), anyArg())).thenReturn(false)

        processor = KeyEventProcessor(ime)
        BoardKeyPressTracker.getInstance().clearPendingActions()
    }

    @After
    fun tearDown() {
        classifierStatic.close()
        DeviceInputResolver.resetConfig()
        subtypeStatic.close()
        DeviceProfile.initialize(null)
        BoardKeyPressTracker.getInstance().clearPendingActions()
        PrefsManager.getPrefs(context).edit().clear().commit()
    }

    @Test
    fun `a non-physical key-down goes straight back to the framework`() {
        `when`(classifier.isPhysicalKeyboardEvent(anyArg())).thenReturn(false)
        val down = keyDown(KeyEvent.KEYCODE_A)

        processor.onKeyDownInternal(KeyEvent.KEYCODE_A, down)

        verify(ime).superOnKeyDown(KeyEvent.KEYCODE_A, down)
        verify(ime).applyCursorModeState(false, false, false)
    }

    @Test
    fun `a non-physical key-up goes back to the framework with the un-remapped key code`() {
        `when`(classifier.isPhysicalKeyboardEvent(anyArg())).thenReturn(false)
        val up = keyUp(KeyEvent.KEYCODE_A)

        processor.onKeyUpInternal(KeyEvent.KEYCODE_A, up)

        verify(ime).superOnKeyUp(KeyEvent.KEYCODE_A, up)
        verify(ime, never()).remapKeyEvent(anyInt(), anyArg())
    }

    /**
     * The branches below the remap classify the remapped event, not the delivered one. Pinned
     * through the emoji board key, whose whole branch is gated on it.
     */
    @Test
    fun `the physical classification used after the remap is the remapped event's`() {
        val raw = keyUp(KeyEvent.KEYCODE_A)
        val remapped = keyUp(ResolvedKeyConstants.PSEUDO_EMOJI)
        doReturn(remapped).`when`(ime).remapKeyEvent(anyInt(), anyArg())
        `when`(classifier.isPhysicalKeyboardEvent(raw)).thenReturn(true)
        `when`(classifier.isPhysicalKeyboardEvent(remapped)).thenReturn(false)
        arm(PendingKeyAction.EMOJI_BOARD)

        processor.onKeyUpInternal(KeyEvent.KEYCODE_A, raw)

        verify(ime.getKeyboardSwitcher().unifiedInputBoardManager, never()).requestBoard(anyInt())
    }

    @Test
    fun `the remapped key code is what the rest of the key-up path sees`() {
        val raw = keyUp(KeyEvent.KEYCODE_SHIFT_LEFT)
        val remapped = keyUp(KeyEvent.KEYCODE_CTRL_LEFT)
        doReturn(remapped).`when`(ime).remapKeyEvent(anyInt(), anyArg())

        processor.onKeyUpInternal(KeyEvent.KEYCODE_SHIFT_LEFT, raw)

        verify(ime.getControlMode()).handleHardKeyUp(eq(KeyEvent.KEYCODE_CTRL_LEFT), anyArg())
    }

    @Test
    fun `pseudo-keycode 666 is the emoji board key even with no device mapping`() {
        arm(PendingKeyAction.EMOJI_BOARD)

        processor.onKeyUpInternal(
            ResolvedKeyConstants.PSEUDO_EMOJI, keyUp(ResolvedKeyConstants.PSEUDO_EMOJI)
        )

        verify(ime.getKeyboardSwitcher().unifiedInputBoardManager).requestBoard(-11)
    }

    @Test
    fun `pseudo-keycode 666 outranks a device mapping that calls the key a character`() {
        mapKey(KeyRole.CHARACTER)
        arm(PendingKeyAction.EMOJI_BOARD)

        processor.onKeyUpInternal(
            ResolvedKeyConstants.PSEUDO_EMOJI, keyUp(ResolvedKeyConstants.PSEUDO_EMOJI)
        )

        verify(ime.getKeyboardSwitcher().unifiedInputBoardManager).requestBoard(-11)
    }

    @Test
    fun `pseudo-keycode 667 is not the emoji key`() {
        arm(PendingKeyAction.EMOJI_BOARD)

        processor.onKeyUpInternal(
            ResolvedKeyConstants.PSEUDO_VOICE, keyUp(ResolvedKeyConstants.PSEUDO_VOICE)
        )

        verify(ime.getKeyboardSwitcher().unifiedInputBoardManager, never()).requestBoard(anyInt())
    }

    /**
     * 666 is the emoji key, so the emoji branch consumes it (opening nothing with no pending
     * action) before the bottom of the key-up path.
     */
    @Test
    fun `an untracked 666 key-up is consumed by the emoji branch`() {
        processor.onKeyUpInternal(
            ResolvedKeyConstants.PSEUDO_EMOJI, keyUp(ResolvedKeyConstants.PSEUDO_EMOJI)
        )

        verify(ime, never()).superOnKeyUp(eq(ResolvedKeyConstants.PSEUDO_EMOJI), anyArg())
        verify(ime.getKeyboardSwitcher().unifiedInputBoardManager, never()).requestBoard(anyInt())
    }

    /**
     * 667 is exempt from the "extra key event … consuming" guard at the bottom of the key-up
     * path: an untracked 667 is handed back to the framework where an ordinary key is swallowed.
     */
    @Test
    fun `an untracked 667 key-up is handed back to the framework`() {
        processor.onKeyUpInternal(
            ResolvedKeyConstants.PSEUDO_VOICE, keyUp(ResolvedKeyConstants.PSEUDO_VOICE)
        )

        verify(ime).superOnKeyUp(eq(ResolvedKeyConstants.PSEUDO_VOICE), anyArg())
    }

    @Test
    fun `an untracked ordinary key-up is consumed`() {
        processor.onKeyUpInternal(KeyEvent.KEYCODE_A, keyUp(KeyEvent.KEYCODE_A))

        verify(ime, never()).superOnKeyUp(eq(KeyEvent.KEYCODE_A), anyArg())
    }

    @Test
    fun `an untracked board-role key-up is handed back to the framework`() {
        mapKey(KeyRole.BOARD_SYM)

        processor.onKeyUpInternal(KeyEvent.KEYCODE_SYM, keyUp(KeyEvent.KEYCODE_SYM))

        verify(ime).superOnKeyUp(eq(KeyEvent.KEYCODE_SYM), anyArg())
    }

    @Test
    fun `KEYCODE_FUNCTION is handed back to the framework`() {
        processor.onKeyUpInternal(KeyEvent.KEYCODE_FUNCTION, keyUp(KeyEvent.KEYCODE_FUNCTION))

        verify(ime).superOnKeyUp(eq(KeyEvent.KEYCODE_FUNCTION), anyArg())
    }

    @Test
    fun `a BOARD_EMOJI role opens the emoji board`() {
        mapKey(KeyRole.BOARD_EMOJI)
        arm(PendingKeyAction.EMOJI_BOARD)

        processor.onKeyUpInternal(KeyEvent.KEYCODE_ALT_RIGHT, keyUp(KeyEvent.KEYCODE_ALT_RIGHT))

        verify(ime.getKeyboardSwitcher().unifiedInputBoardManager).requestBoard(-11)
    }

    @Test
    fun `a BOARD_EMOJI role with its own board id opens that board`() {
        mapKey(KeyRole.BOARD_EMOJI, boardId = 42)
        arm(PendingKeyAction.EMOJI_BOARD)

        processor.onKeyUpInternal(KeyEvent.KEYCODE_ALT_RIGHT, keyUp(KeyEvent.KEYCODE_ALT_RIGHT))

        verify(ime.getKeyboardSwitcher().unifiedInputBoardManager).requestBoard(42)
    }

    @Test
    fun `a BOARD_VOICE role opens the voice board`() {
        mapKey(KeyRole.BOARD_VOICE)
        arm(PendingKeyAction.VOICE_INPUT)

        processor.onKeyUpInternal(KeyEvent.KEYCODE_7, keyUp(KeyEvent.KEYCODE_7))

        verify(ime.getKeyboardSwitcher().unifiedInputBoardManager).requestBoard(-27)
    }

    @Test
    fun `a BOARD_VOICE role with its own board id opens that board`() {
        mapKey(KeyRole.BOARD_VOICE, boardId = 43)
        arm(PendingKeyAction.VOICE_INPUT)

        processor.onKeyUpInternal(KeyEvent.KEYCODE_7, keyUp(KeyEvent.KEYCODE_7))

        verify(ime.getKeyboardSwitcher().unifiedInputBoardManager).requestBoard(43)
    }

    @Test
    fun `a MULTIFUNCTION key set to the emoji action takes the emoji board path`() {
        mapKey(KeyRole.MULTIFUNCTION, defaultAction = MultifunctionKeyHandler.ACTION_EMOJI_BOARD)
        arm(PendingKeyAction.EMOJI_BOARD)

        processor.onKeyUpInternal(KeyEvent.KEYCODE_7, keyUp(KeyEvent.KEYCODE_7))

        verify(ime.getKeyboardSwitcher().unifiedInputBoardManager).requestBoard(-11)
    }

    @Test
    fun `a MULTIFUNCTION key set to switch language switches language on release`() {
        mapKey(KeyRole.MULTIFUNCTION, defaultAction = MultifunctionKeyHandler.ACTION_LANGUAGE_QUICK_SWITCH)
        arm(PendingKeyAction.MULTIFUNCTION)

        processor.onKeyUpInternal(KeyEvent.KEYCODE_7, keyUp(KeyEvent.KEYCODE_7))

        verify(ime).updateSuggestionsFromSubtype(InputSource.HARDWARE)
    }

    @Test
    fun `a MULTIFUNCTION key set to the clipboard board toggles that board`() {
        mapKey(KeyRole.MULTIFUNCTION, defaultAction = MultifunctionKeyHandler.ACTION_CLIPBOARD_BOARD)
        arm(PendingKeyAction.MULTIFUNCTION)

        processor.onKeyUpInternal(KeyEvent.KEYCODE_7, keyUp(KeyEvent.KEYCODE_7))

        verify(ime.getKeyboardSwitcher().unifiedInputBoardManager).requestBoard(-25)
    }

    @Test
    fun `the Sym key is recognised by key code when the device config is silent`() {
        assertTrue(KeyEventProcessor.isSymBoardKey(KeyEvent.KEYCODE_SYM, null))
        assertFalse(KeyEventProcessor.isSymBoardKey(KeyEvent.KEYCODE_ALT_RIGHT, null))
    }

    @Test
    fun `a BOARD_SYM role outranks whatever key code the ROM attached`() {
        val sym = ScancodeMapping().apply { role = KeyRole.BOARD_SYM }
        assertTrue(KeyEventProcessor.isSymBoardKey(KeyEvent.KEYCODE_ALT_RIGHT, sym))

        val character = ScancodeMapping().apply { role = KeyRole.CHARACTER }
        assertFalse(
            "a config that gives the key another role wins over KEYCODE_SYM",
            KeyEventProcessor.isSymBoardKey(KeyEvent.KEYCODE_SYM, character)
        )
    }

    @Test
    fun `an ordinary text key-down dismisses the open boards`() {
        processor.onKeyDownInternal(KeyEvent.KEYCODE_A, keyDown(KeyEvent.KEYCODE_A))

        verify(ime).dismissBoardsForTextKey()
    }

    @Test
    fun `a board-role key-down leaves the open boards alone`() {
        mapKey(KeyRole.BOARD_EMOJI)

        processor.onKeyDownInternal(KeyEvent.KEYCODE_ALT_RIGHT, keyDown(KeyEvent.KEYCODE_ALT_RIGHT))

        verify(ime, never()).dismissBoardsForTextKey()
    }

    @Test
    fun `a MULTIFUNCTION key-down leaves the open boards alone`() {
        mapKey(KeyRole.MULTIFUNCTION, defaultAction = MultifunctionKeyHandler.ACTION_LANGUAGE_QUICK_SWITCH)

        processor.onKeyDownInternal(KeyEvent.KEYCODE_7, keyDown(KeyEvent.KEYCODE_7))

        verify(ime, never()).dismissBoardsForTextKey()
    }

    @Test
    fun `a pseudo-keycode key-down leaves the open boards alone`() {
        processor.onKeyDownInternal(
            ResolvedKeyConstants.PSEUDO_EMOJI, keyDown(ResolvedKeyConstants.PSEUDO_EMOJI)
        )

        verify(ime, never()).dismissBoardsForTextKey()
    }

    /**
     * The MP01's Sym key arrives as KEYCODE_ALT_RIGHT, so the Alt symbol-long-press fallthrough
     * has to be suppressed for it or the board toggle fires twice.
     */
    @Test
    fun `a board-role Alt key-down does not also fire the symbol long press`() {
        mapKey(KeyRole.BOARD_EMOJI)

        processor.onKeyDownInternal(KeyEvent.KEYCODE_ALT_RIGHT, keyDown(KeyEvent.KEYCODE_ALT_RIGHT))

        verify(ime.getKeyboardSwitcher(), never()).onSymbolKeyLongPress(anyInt(), anyInt())
    }

    @Test
    fun `an ordinary Alt key-down does fire the symbol long press`() {
        processor.onKeyDownInternal(KeyEvent.KEYCODE_ALT_LEFT, keyDown(KeyEvent.KEYCODE_ALT_LEFT))

        verify(ime.getKeyboardSwitcher()).onSymbolKeyLongPress(anyInt(), anyInt())
    }

    @Test
    fun `the pseudo-keycodes never end a one-shot symbol entry`() {
        trackedWithHint()

        processor.onKeyUpInternal(
            ResolvedKeyConstants.PSEUDO_EMOJI, keyUp(ResolvedKeyConstants.PSEUDO_EMOJI)
        )
        processor.onKeyUpInternal(
            ResolvedKeyConstants.PSEUDO_VOICE, keyUp(ResolvedKeyConstants.PSEUDO_VOICE)
        )

        verify(ime.getKeyboardSwitcher(), never()).onSymbolKeyLongPress(anyInt(), anyInt())
    }

    @Test
    fun `a board-role key never ends a one-shot symbol entry`() {
        trackedWithHint()
        mapKey(KeyRole.BOARD_SYM)

        processor.onKeyUpInternal(KeyEvent.KEYCODE_SYM, keyUp(KeyEvent.KEYCODE_SYM))

        verify(ime.getKeyboardSwitcher(), never()).onSymbolKeyLongPress(anyInt(), anyInt())
    }

    @Test
    fun `a virtual key never ends a one-shot symbol entry`() {
        trackedWithHint()
        val raw = keyUp(KeyEvent.KEYCODE_R)
        `when`(classifier.isPhysicalKeyboardEvent(anyArg())).thenReturn(true)
        `when`(classifier.isPhysicalKeyboardEvent(raw)).thenReturn(true)
        // The remap hands back a different instance, and it is that one the branch classifies.
        val remapped = keyUp(KeyEvent.KEYCODE_R)
        doReturn(remapped).`when`(ime).remapKeyEvent(anyInt(), anyArg())
        `when`(classifier.isPhysicalKeyboardEvent(remapped)).thenReturn(false)

        processor.onKeyUpInternal(KeyEvent.KEYCODE_R, raw)

        verify(ime.getKeyboardSwitcher(), never()).onSymbolKeyLongPress(anyInt(), anyInt())
    }

    @Test
    fun `an ordinary hinted key does end a one-shot symbol entry`() {
        trackedWithHint()

        processor.onKeyUpInternal(KeyEvent.KEYCODE_R, keyUp(KeyEvent.KEYCODE_R))

        verify(ime.getKeyboardSwitcher()).onSymbolKeyLongPress(anyInt(), anyInt())
    }

    /** Puts the key-up in the tracked branch with a symbol hint on the board. */
    private fun trackedWithHint() {
        `when`(ime.getInputLogic().isKeyTracked(anyArg())).thenReturn(true)
        `when`(ime.getKeyboardSwitcher().getKeyByPhysicalScanCode(anyInt(), anyBoolean()))
            .thenReturn(mock(dev.bbkb.ime.keyboard.Key::class.java))
    }

    private fun mapKey(
        role: KeyRole,
        boardId: Int = 0,
        defaultAction: String? = null,
    ) {
        val mapping = ScancodeMapping().also {
            it.role = role
            it.boardId = boardId
            it.defaultAction = defaultAction
        }
        KeyRoleTestSupport.everyKeyIs(mapping)
    }

    /** Put a pending board action in place, the way a key-DOWN would. Setup only. */
    private fun arm(action: PendingKeyAction) {
        // The identity does not matter here: consumption is not gated on it (see
        // BoardKeyPairingCharacterisationTest), and this suite is about key identity, not pairing.
        BoardKeyPressTracker.getInstance().arm(keyDown(KeyEvent.KEYCODE_UNKNOWN), action)
    }

    private fun <T> anyArg(): T = ArgumentMatchers.any<T>() as T

    private fun keyUp(keyCode: Int): KeyEvent =
        KeyEvent(0L, 0L, KeyEvent.ACTION_UP, keyCode, 0, 0, 0, 30)

    private fun keyDown(keyCode: Int): KeyEvent =
        KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, keyCode, 0, 0, 0, 30)

    private fun pkbShape(): DeviceCapabilities = DeviceCapabilities.forShape(
        DeviceCapabilities.DetectedDeviceType.PKB,
        /* hasPhysicalKeyboard= */ true,
        /* hasTouchKeypad= */ false,
        /* isBlackBerryDevice= */ true,
        "qwerty",
        "4row"
    )

    /**
     * The pseudo-keycodes as literal numbers, so this suite fails if [ResolvedKey] ever changes
     * their values.
     */
    private object ResolvedKeyConstants {
        const val PSEUDO_EMOJI = 666
        const val PSEUDO_VOICE = 667
    }
}

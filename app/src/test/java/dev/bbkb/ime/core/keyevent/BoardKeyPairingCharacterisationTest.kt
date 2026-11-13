package dev.bbkb.ime.core.keyevent

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import dev.bbkb.ime.core.BlackBerryIME
import dev.bbkb.ime.core.device.config.model.KeyRole
import dev.bbkb.ime.core.device.config.model.ScancodeMapping
import dev.bbkb.ime.core.device.config.resolver.DeviceInputResolver
import dev.bbkb.ime.core.device.config.resolver.KeyRoleTestSupport
import dev.bbkb.ime.core.device.detection.KeyEventDeviceClassifier
import dev.bbkb.ime.core.device.profile.DeviceCapabilities
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.device.state.PhysicalKeyboardStateTracker
import dev.bbkb.ime.core.ime.InputViewCoordinator
import dev.bbkb.ime.core.locale.SubtypeManager
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.settings.util.SettingsManager
import dev.bbkb.ime.core.settings.util.SettingsValues
import dev.bbkb.ime.core.textinput.InputSessionCoordinator
import dev.bbkb.ime.core.textinput.connection.EditorCapabilities
import dev.bbkb.ime.keyboard.inputboard.clipboard.ClipboardController
import dev.bbkb.ime.keyboard.inputboard.fcc.FccController
import dev.bbkb.ime.keyboard.inputboard.numberpad.NumberPadController
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.MockedStatic
import org.mockito.Mockito.RETURNS_DEEP_STUBS
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Pins the down/up pairing for the emoji, mic and multifunction keys: the DOWN arms a pending
 * action in [BoardKeyPressTracker] and the UP performs it. Assertions are about observable
 * effects (which board is requested, cursor mode, framework hand-back), not where state lives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class BoardKeyPairingCharacterisationTest {

    private lateinit var context: Context
    private lateinit var classifierStatic: MockedStatic<KeyEventDeviceClassifier>
    private lateinit var subtypeStatic: MockedStatic<SubtypeManager>

    private lateinit var classifier: KeyEventDeviceClassifier

    private lateinit var tracker: PhysicalKeyboardStateTracker
    private lateinit var ime: BlackBerryIME
    private lateinit var processor: KeyEventProcessor

    /** The Athena mic key: keyCode 7, its own scancode. Distinct identity from [EMOJI_KEY]. */
    private val MIC_KEY = KeyEvent.KEYCODE_7
    private val MIC_SCANCODE = 8
    private val EMOJI_KEY = KeyEvent.KEYCODE_ALT_RIGHT
    private val EMOJI_SCANCODE = 250
    private val ORDINARY_KEY = KeyEvent.KEYCODE_A
    private val ORDINARY_SCANCODE = 30

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PrefsManager.getPrefs(context).edit().clear().commit()
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

        // A REAL tracker: the reset contract is one of the things under test, and a mock would
        // answer resetAllMetaState() by doing nothing at all.
        tracker = PhysicalKeyboardStateTracker(null)

        ime = mock(BlackBerryIME::class.java, RETURNS_DEEP_STUBS)
        `when`(ime.applicationContext).thenReturn(context)
        `when`(ime.packageName).thenReturn(context.packageName)
        `when`(ime.isInputActive).thenReturn(true)
        `when`(ime.isInputViewShown).thenReturn(true)
        `when`(ime.isUimEnabled()).thenReturn(true)
        `when`(ime.isAltEnterPressed).thenReturn(false)
        `when`(ime.auxBarManager).thenReturn(null)
        `when`(ime.vkbGestureListener).thenReturn(null)
        `when`(ime.getPhysicalKeyboardStateTracker()).thenReturn(tracker)
        `when`(ime.remapKeyEvent(anyInt(), anyArg())).thenAnswer { it.arguments[1] as KeyEvent }
        `when`(ime.getControlMode().handleHardKeyDown(anyInt(), anyArg())).thenReturn(false)
        `when`(ime.getControlMode().handleHardKeyUp(anyInt(), anyArg())).thenReturn(false)
        `when`(ime.getInputLogic().isKeyTracked(anyArg())).thenReturn(false)
        `when`(ime.superOnKeyUp(anyInt(), anyArg())).thenReturn(false)
        `when`(ime.superOnKeyDown(anyInt(), anyArg())).thenReturn(false)

        processor = KeyEventProcessor(ime)
        clearPairing()
    }

    @After
    fun tearDown() {
        clearPairing()
        // Session state outside the settings snapshot: it must not leak into the next test.
        SettingsValues.setInputMenuRequestedByKey(false)
        DeviceProfile.setOnScreenKeyboardShowing(false)
        classifierStatic.close()
        DeviceInputResolver.resetConfig()
        subtypeStatic.close()
        DeviceProfile.initialize(null)
        PrefsManager.getPrefs(context).edit().clear().commit()
    }

    @Test
    fun `an armed emoji key opens the emoji board on its release`() {
        mapKey(KeyRole.BOARD_EMOJI)
        arm(PendingKeyAction.EMOJI_BOARD, EMOJI_KEY, EMOJI_SCANCODE)

        processor.onKeyUpInternal(EMOJI_KEY, keyUp(EMOJI_KEY, EMOJI_SCANCODE))

        verify(boardManager()).requestBoard(-11)
    }

    @Test
    fun `an armed mic key opens the voice board on its release`() {
        mapKey(KeyRole.BOARD_VOICE)
        arm(PendingKeyAction.VOICE_INPUT, MIC_KEY, MIC_SCANCODE)

        processor.onKeyUpInternal(MIC_KEY, keyUp(MIC_KEY, MIC_SCANCODE))

        verify(boardManager()).requestBoard(-27)
    }

    @Test
    fun `an armed multifunction key dispatches its action on release`() {
        mapKey(KeyRole.MULTIFUNCTION, defaultAction = MultifunctionKeyHandler.ACTION_LANGUAGE_QUICK_SWITCH)
        arm(PendingKeyAction.MULTIFUNCTION, MIC_KEY, MIC_SCANCODE)

        processor.onKeyUpInternal(MIC_KEY, keyUp(MIC_KEY, MIC_SCANCODE))

        verify(ime).updateSuggestionsFromSubtype(InputSource.HARDWARE)
    }

    /** The action fires on the UP and only on the UP: the DOWN must not open anything. */
    @Test
    fun `the key-down half opens nothing by itself`() {
        mapKey(KeyRole.BOARD_EMOJI)

        processor.onKeyDownInternal(EMOJI_KEY, keyDown(EMOJI_KEY, EMOJI_SCANCODE))

        verify(boardManager(), never()).requestBoard(anyInt())
        verify(ime, never()).updateSuggestionsFromSubtype(InputSource.HARDWARE)
    }

    /**
     * With the input menu off (or hidden with "Show the suggestion bar") the board actions open
     * their board on its own, without the menu bar; the board's toggle closes it on the next press.
     */
    @Test
    fun `with the input menu off the multifunction board actions toggle their board directly`() {
        `when`(ime.isUimEnabled()).thenReturn(false)
        val clipboard = mock(ClipboardController::class.java)
        val fcc = mock(FccController::class.java)
        val numberPad = mock(NumberPadController::class.java)
        `when`(ime.clipboardController).thenReturn(clipboard)
        `when`(ime.fccController).thenReturn(fcc)
        `when`(ime.numberPadController).thenReturn(numberPad)

        pressMultifunction(MultifunctionKeyHandler.ACTION_CLIPBOARD_BOARD)
        verify(clipboard).show()

        pressMultifunction(MultifunctionKeyHandler.ACTION_FCC)
        pressMultifunction(MultifunctionKeyHandler.ACTION_FCC)
        verify(fcc, times(2)).toggle()

        pressMultifunction(MultifunctionKeyHandler.ACTION_NUMBER_PAD)
        verify(numberPad).toggle()

        verify(boardManager(), never()).requestBoard(anyInt())
    }

    @Test
    fun `with the input menu on the multifunction board actions go through the coordinator`() {
        val fcc = mock(FccController::class.java)
        val numberPad = mock(NumberPadController::class.java)
        `when`(ime.fccController).thenReturn(fcc)
        `when`(ime.numberPadController).thenReturn(numberPad)

        pressMultifunction(MultifunctionKeyHandler.ACTION_FCC)
        pressMultifunction(MultifunctionKeyHandler.ACTION_NUMBER_PAD)

        verify(boardManager()).requestBoard(FccController.KEY_CODE)
        verify(boardManager()).requestBoard(NumberPadController.KEY_CODE)
        verify(fcc, never()).toggle()
        verify(numberPad, never()).toggle()
    }

    @Test
    fun `with the input menu off a board action with no board built yet does nothing`() {
        `when`(ime.isUimEnabled()).thenReturn(false)
        `when`(ime.fccController).thenReturn(null)
        `when`(ime.numberPadController).thenReturn(null)

        pressMultifunction(MultifunctionKeyHandler.ACTION_FCC)
        pressMultifunction(MultifunctionKeyHandler.ACTION_NUMBER_PAD)

        verify(boardManager(), never()).requestBoard(anyInt())
    }

    /**
     * With the menu hidden there is no hamburger button: the action raises it as a session
     * override (preferences untouched), and while it is up board actions take the menu's route.
     */
    @Test
    fun `with the bar hidden the input-menu action raises the menu and the board actions take its route`() {
        val menu = inputMenuWithTheBarHidden()
        val fcc = mock(FccController::class.java)
        `when`(ime.fccController).thenReturn(fcc)
        assertFalse("the fixture: the setting keeps the menu off", ime.isUimEnabled())

        pressMultifunction(MultifunctionKeyHandler.ACTION_TOGGLE_UIM)

        verify(boardManager()).showMenuForKey()
        assertTrue(menu.up)
        assertTrue(SettingsValues.isInputMenuRequestedByKey())
        assertTrue(ime.isUimEnabled())

        pressMultifunction(MultifunctionKeyHandler.ACTION_FCC)
        verify(boardManager()).requestBoard(FccController.KEY_CODE)
        verify(fcc, never()).toggle()

        // A session override, not a settings change.
        assertFalse(PrefsManager.getPrefs(context).getBoolean("show_predictions", true))
        assertFalse(PrefsManager.getPrefs(context).getBoolean("pref_uim_enabled", true))
    }

    @Test
    fun `a second press hides the menu and the board actions go back to their own toggles`() {
        val menu = inputMenuWithTheBarHidden()
        val fcc = mock(FccController::class.java)
        `when`(ime.fccController).thenReturn(fcc)

        pressMultifunction(MultifunctionKeyHandler.ACTION_TOGGLE_UIM)
        pressMultifunction(MultifunctionKeyHandler.ACTION_TOGGLE_UIM)

        verify(boardManager(), times(1)).showMenuForKey()
        verify(boardManager()).hide()
        assertFalse(menu.up)
        assertFalse(SettingsValues.isInputMenuRequestedByKey())
        assertFalse(ime.isUimEnabled())

        pressMultifunction(MultifunctionKeyHandler.ACTION_FCC)
        verify(fcc).toggle()
        verify(boardManager(), never()).requestBoard(anyInt())
    }

    /** A menu the settings already show is taken away by the press, and not marked as the key's. */
    @Test
    fun `the input-menu action hides a menu that is already up`() {
        val menu = inputMenuWithTheBarHidden()
        menu.up = true

        pressMultifunction(MultifunctionKeyHandler.ACTION_TOGGLE_UIM)

        verify(boardManager()).hide()
        verify(boardManager(), never()).showMenuForKey()
        assertFalse(SettingsValues.isInputMenuRequestedByKey())
    }

    /** The next field gets the user's settings back: the menu the key raised goes at input start. */
    @Test
    fun `the menu the key raised ends at the next input start`() {
        val menu = inputMenuWithTheBarHidden()
        val fcc = mock(FccController::class.java)
        `when`(ime.fccController).thenReturn(fcc)
        pressMultifunction(MultifunctionKeyHandler.ACTION_TOGGLE_UIM)
        assertTrue(SettingsValues.isInputMenuRequestedByKey())

        InputSessionCoordinator(ime).onStartInputInternal(EditorInfo(), false)

        assertFalse(SettingsValues.isInputMenuRequestedByKey())
        verify(boardManager()).hide()
        assertFalse(menu.up)
        pressMultifunction(MultifunctionKeyHandler.ACTION_FCC)
        verify(fcc).toggle()
        verify(boardManager(), never()).requestBoard(anyInt())
    }

    /**
     * Only a key-UP consumes a pending action; it survives any number of further key-DOWNs.
     * This is what makes "hold the mic key" work.
     */
    @Test
    fun `a pending action survives further key-downs`() {
        mapKey(KeyRole.BOARD_EMOJI)
        arm(PendingKeyAction.EMOJI_BOARD, EMOJI_KEY, EMOJI_SCANCODE)

        processor.onKeyDownInternal(EMOJI_KEY, keyDown(EMOJI_KEY, EMOJI_SCANCODE))
        processor.onKeyDownInternal(EMOJI_KEY, keyDown(EMOJI_KEY, EMOJI_SCANCODE))
        processor.onKeyUpInternal(EMOJI_KEY, keyUp(EMOJI_KEY, EMOJI_SCANCODE))

        verify(boardManager(), times(1)).requestBoard(-11)
    }

    /** One arm, one action: the release spends it, and a second release does nothing. */
    @Test
    fun `a spent emoji arm is not spent twice`() {
        mapKey(KeyRole.BOARD_EMOJI)
        arm(PendingKeyAction.EMOJI_BOARD, EMOJI_KEY, EMOJI_SCANCODE)

        processor.onKeyUpInternal(EMOJI_KEY, keyUp(EMOJI_KEY, EMOJI_SCANCODE))
        processor.onKeyUpInternal(EMOJI_KEY, keyUp(EMOJI_KEY, EMOJI_SCANCODE))

        verify(boardManager(), times(1)).requestBoard(-11)
    }

    @Test
    fun `a spent voice arm is not spent twice`() {
        mapKey(KeyRole.BOARD_VOICE)
        arm(PendingKeyAction.VOICE_INPUT, MIC_KEY, MIC_SCANCODE)

        processor.onKeyUpInternal(MIC_KEY, keyUp(MIC_KEY, MIC_SCANCODE))
        processor.onKeyUpInternal(MIC_KEY, keyUp(MIC_KEY, MIC_SCANCODE))

        verify(boardManager(), times(1)).requestBoard(-27)
    }

    @Test
    fun `a spent multifunction arm is not spent twice`() {
        mapKey(KeyRole.MULTIFUNCTION, defaultAction = MultifunctionKeyHandler.ACTION_LANGUAGE_QUICK_SWITCH)
        arm(PendingKeyAction.MULTIFUNCTION, MIC_KEY, MIC_SCANCODE)

        processor.onKeyUpInternal(MIC_KEY, keyUp(MIC_KEY, MIC_SCANCODE))
        processor.onKeyUpInternal(MIC_KEY, keyUp(MIC_KEY, MIC_SCANCODE))

        verify(ime, times(1)).updateSuggestionsFromSubtype(InputSource.HARDWARE)
    }

    /**
     * An unarmed emoji key-up opens nothing but is still consumed: the emoji branch is gated on
     * the key's identity, not the pending action, which keeps the base character from the app.
     */
    @Test
    fun `an unarmed emoji key-up opens nothing and is still consumed`() {
        mapKey(KeyRole.BOARD_EMOJI)

        processor.onKeyUpInternal(EMOJI_KEY, keyUp(EMOJI_KEY, EMOJI_SCANCODE))

        verify(boardManager(), never()).requestBoard(anyInt())
        verify(ime, never()).superOnKeyUp(eq(EMOJI_KEY), anyArg())
    }

    /**
     * An unarmed mic key-up skips the voice branch (gated on the pending action, not the key) and
     * lands in generic tracked-key handling, which hands a board-role key back to the framework.
     */
    @Test
    fun `an unarmed mic key-up opens nothing`() {
        mapKey(KeyRole.BOARD_VOICE)

        processor.onKeyUpInternal(MIC_KEY, keyUp(MIC_KEY, MIC_SCANCODE))

        verify(boardManager(), never()).requestBoard(anyInt())
        verify(ime).superOnKeyUp(eq(MIC_KEY), anyArg())
    }

    @Test
    fun `an unarmed multifunction key-up dispatches nothing`() {
        mapKey(KeyRole.MULTIFUNCTION, defaultAction = MultifunctionKeyHandler.ACTION_LANGUAGE_QUICK_SWITCH)

        processor.onKeyUpInternal(MIC_KEY, keyUp(MIC_KEY, MIC_SCANCODE))

        verify(ime, never()).updateSuggestionsFromSubtype(InputSource.HARDWARE)
    }

    /**
     * Pins a quirk, not an endorsement: the voice branch never checks that the released key is the
     * one that armed it, so a letter released while the mic key is held opens the voice board.
     * Gating consumption on the arming key would be a behaviour change.
     */
    @Test
    fun `a pending voice action is consumed by any physical key-up`() {
        arm(PendingKeyAction.VOICE_INPUT, MIC_KEY, MIC_SCANCODE)

        processor.onKeyUpInternal(ORDINARY_KEY, keyUp(ORDINARY_KEY, ORDINARY_SCANCODE))

        verify(boardManager()).requestBoard(-27)
    }

    /**
     * The same quirk on the multifunction branch, worse: the action is read from the key being
     * released, so another key's release with an action pending resolves null and throws.
     * Unreachable in practice (only its own release follows), pinned so a rewrite cannot change it.
     */
    @Test
    fun `a pending multifunction action read off the wrong key throws`() {
        arm(PendingKeyAction.MULTIFUNCTION, MIC_KEY, MIC_SCANCODE)

        assertThrows(NullPointerException::class.java) {
            processor.onKeyUpInternal(ORDINARY_KEY, keyUp(ORDINARY_KEY, ORDINARY_SCANCODE))
        }
    }

    /** A non-physical key-up leaves a pending action alone: it returns to the framework first. */
    @Test
    fun `a non-physical key-up does not spend a pending action`() {
        mapKey(KeyRole.BOARD_VOICE)
        arm(PendingKeyAction.VOICE_INPUT, MIC_KEY, MIC_SCANCODE)
        `when`(classifier.isPhysicalKeyboardEvent(anyArg())).thenReturn(false)

        processor.onKeyUpInternal(MIC_KEY, keyUp(MIC_KEY, MIC_SCANCODE))
        verify(boardManager(), never()).requestBoard(anyInt())

        `when`(classifier.isPhysicalKeyboardEvent(anyArg())).thenReturn(true)
        processor.onKeyUpInternal(MIC_KEY, keyUp(MIC_KEY, MIC_SCANCODE))

        verify(boardManager()).requestBoard(-27)
    }

    /**
     * This `isPhysical()` guard reads the remapped event's classification, unlike the bail-out at
     * the top of the method. Defensive in production (`withKeyAndMeta` keeps the device id), but a
     * mutation dropping it otherwise survives the whole suite.
     */
    @Test
    fun `a remap to a non-physical event does not spend a pending voice action`() {
        mapKey(KeyRole.BOARD_VOICE)
        arm(PendingKeyAction.VOICE_INPUT, MIC_KEY, MIC_SCANCODE)
        val raw = keyUp(MIC_KEY, MIC_SCANCODE)
        val remapped = keyUp(MIC_KEY, MIC_SCANCODE)
        doReturn(remapped).`when`(ime).remapKeyEvent(anyInt(), anyArg())
        `when`(classifier.isPhysicalKeyboardEvent(raw)).thenReturn(true)
        `when`(classifier.isPhysicalKeyboardEvent(remapped)).thenReturn(false)

        processor.onKeyUpInternal(MIC_KEY, raw)

        verify(boardManager(), never()).requestBoard(anyInt())
        assertTrue(
            "the pending action must survive an event the branch declined to act on",
            BoardKeyPressTracker.getInstance().isArmed(PendingKeyAction.VOICE_INPUT)
        )
    }

    @Test
    fun `a remap to a non-physical event does not spend a pending multifunction action`() {
        mapKey(KeyRole.MULTIFUNCTION, defaultAction = MultifunctionKeyHandler.ACTION_LANGUAGE_QUICK_SWITCH)
        arm(PendingKeyAction.MULTIFUNCTION, MIC_KEY, MIC_SCANCODE)
        val raw = keyUp(MIC_KEY, MIC_SCANCODE)
        val remapped = keyUp(MIC_KEY, MIC_SCANCODE)
        doReturn(remapped).`when`(ime).remapKeyEvent(anyInt(), anyArg())
        `when`(classifier.isPhysicalKeyboardEvent(raw)).thenReturn(true)
        `when`(classifier.isPhysicalKeyboardEvent(remapped)).thenReturn(false)

        processor.onKeyUpInternal(MIC_KEY, raw)

        verify(ime, never()).updateSuggestionsFromSubtype(InputSource.HARDWARE)
        assertTrue(
            BoardKeyPressTracker.getInstance().isArmed(PendingKeyAction.MULTIFUNCTION)
        )
    }

    /**
     * Emoji and mic held at once are independent slots, so each release performs its own. The
     * emoji branch runs first and returns, so an emoji release cannot spend the voice action.
     */
    @Test
    fun `two board keys armed at once each perform their own action`() {
        mapKey(KeyRole.BOARD_EMOJI)
        arm(PendingKeyAction.EMOJI_BOARD, EMOJI_KEY, EMOJI_SCANCODE)
        arm(PendingKeyAction.VOICE_INPUT, MIC_KEY, MIC_SCANCODE)

        processor.onKeyUpInternal(EMOJI_KEY, keyUp(EMOJI_KEY, EMOJI_SCANCODE))
        verify(boardManager()).requestBoard(-11)
        verify(boardManager(), never()).requestBoard(-27)

        mapKey(KeyRole.BOARD_VOICE)
        processor.onKeyUpInternal(MIC_KEY, keyUp(MIC_KEY, MIC_SCANCODE))

        verify(boardManager()).requestBoard(-27)
    }

    /**
     * Arming the same slot twice (two emoji-role keys, or a re-arming repeat) is one pending
     * action: the second release opens nothing.
     */
    @Test
    fun `arming the same action twice still performs it once`() {
        mapKey(KeyRole.BOARD_EMOJI)
        arm(PendingKeyAction.EMOJI_BOARD, EMOJI_KEY, EMOJI_SCANCODE)
        arm(PendingKeyAction.EMOJI_BOARD, KeyEvent.KEYCODE_ALT_LEFT, 251)

        processor.onKeyUpInternal(EMOJI_KEY, keyUp(EMOJI_KEY, EMOJI_SCANCODE))
        processor.onKeyUpInternal(KeyEvent.KEYCODE_ALT_LEFT, keyUp(KeyEvent.KEYCODE_ALT_LEFT, 251))

        verify(boardManager(), times(1)).requestBoard(-11)
    }

    /**
     * Pending actions clear with the rest of the per-key state on a modifier reset (IME hidden,
     * input finished, editor switched, screen off, reload), so a stale arm cannot open a board.
     */
    @Test
    fun `a full modifier reset between the down and the up discards the pending action`() {
        mapKey(KeyRole.BOARD_VOICE)
        arm(PendingKeyAction.VOICE_INPUT, MIC_KEY, MIC_SCANCODE)

        tracker.resetAllMetaState()
        processor.onKeyUpInternal(MIC_KEY, keyUp(MIC_KEY, MIC_SCANCODE))

        verify(boardManager(), never()).requestBoard(anyInt())
    }

    /** The same on the multifunction slot, which could fire a destructive action off a stale arm. */
    @Test
    fun `a full modifier reset discards a pending multifunction action`() {
        mapKey(KeyRole.MULTIFUNCTION, defaultAction = MultifunctionKeyHandler.ACTION_LANGUAGE_QUICK_SWITCH)
        arm(PendingKeyAction.MULTIFUNCTION, MIC_KEY, MIC_SCANCODE)

        tracker.resetAllMetaState()
        processor.onKeyUpInternal(MIC_KEY, keyUp(MIC_KEY, MIC_SCANCODE))

        verify(ime, never()).updateSuggestionsFromSubtype(InputSource.HARDWARE)
    }

    /**
     * A partial reset is not a full one: leaving the Alt page spends only the Alt span, so a
     * pending action must survive it (the MP01 arms its emoji key with Alt in play).
     */
    @Test
    fun `leaving the Alt page leaves a pending action armed`() {
        mapKey(KeyRole.BOARD_EMOJI)
        arm(PendingKeyAction.EMOJI_BOARD, EMOJI_KEY, EMOJI_SCANCODE)

        tracker.resetAltStateAndNotify()
        processor.onKeyUpInternal(EMOJI_KEY, keyUp(EMOJI_KEY, EMOJI_SCANCODE))

        verify(boardManager()).requestBoard(-11)
    }

    /**
     * A board key's DOWN must not dismiss the boards or disable cursor mode: its UP decides, and
     * disabling on the way down clears the state that decision reads.
     */
    @Test
    fun `a board key-down does not dismiss boards while an ordinary key-down does`() {
        mapKey(KeyRole.BOARD_EMOJI)
        processor.onKeyDownInternal(EMOJI_KEY, keyDown(EMOJI_KEY, EMOJI_SCANCODE))
        verify(ime, never()).dismissBoardsForTextKey()

        KeyRoleTestSupport.everyKeyIs(null)
        processor.onKeyDownInternal(ORDINARY_KEY, keyDown(ORDINARY_KEY, ORDINARY_SCANCODE))

        verify(ime).dismissBoardsForTextKey()
    }

    @Test
    fun `a multifunction key-down does not dismiss boards`() {
        mapKey(KeyRole.MULTIFUNCTION, defaultAction = MultifunctionKeyHandler.ACTION_LANGUAGE_QUICK_SWITCH)

        processor.onKeyDownInternal(MIC_KEY, keyDown(MIC_KEY, MIC_SCANCODE))

        verify(ime, never()).dismissBoardsForTextKey()
        verify(ime, never()).applyCursorModeState(
            ArgumentMatchers.anyBoolean(), ArgumentMatchers.anyBoolean(),
            ArgumentMatchers.anyBoolean()
        )
    }

    private fun boardManager() = ime.getKeyboardSwitcher().unifiedInputBoardManager

    /** Put a pending action in place, the way a key-DOWN would. */
    private fun arm(action: PendingKeyAction, keyCode: Int, scanCode: Int) {
        BoardKeyPressTracker.getInstance().arm(keyDown(keyCode, scanCode), action)
    }

    /** A full press of the multifunction key with [action] as its configured action. */
    private fun pressMultifunction(action: String) {
        mapKey(KeyRole.MULTIFUNCTION, defaultAction = action)
        arm(PendingKeyAction.MULTIFUNCTION, MIC_KEY, MIC_SCANCODE)
        processor.onKeyUpInternal(MIC_KEY, keyUp(MIC_KEY, MIC_SCANCODE))
    }

    private fun clearPairing() {
        BoardKeyPressTracker.getInstance().clearPendingActions()
    }

    /** The menu bar as far as the toggle can see it: up or not. */
    private class FakeMenu(var up: Boolean = false)

    /**
     * "Show the suggestion bar" off on this physical keyboard, with the real [SettingsValues]
     * (the only input to [BlackBerryIME.isUimEnabled]), the real [InputViewCoordinator], and a
     * menu bar the board manager mock raises and lowers.
     */
    private fun inputMenuWithTheBarHidden(): FakeMenu {
        // The bar hides when suggestions and the menu are both off.
        PrefsManager.getPrefs(context).edit()
            .putBoolean("show_predictions", false).putBoolean("pref_uim_enabled", false).commit()
        SettingsManager.getInstance().loadSettings(
            context, Locale.US,
            EditorCapabilities(null, false, context.packageName, Locale.US, false)
        )
        DeviceProfile.setOnScreenKeyboardShowing(false)
        assertTrue(SettingsManager.getInstance().settingsValues.isPkbSuggestionBarHidden)
        `when`(ime.isUimEnabled()).thenAnswer { SettingsManager.getInstance().settingsValues.isUimEnabled }

        val coordinator = InputViewCoordinator(ime)
        coordinator.initialize()
        `when`(ime.getUiCoordinator()).thenReturn(coordinator)

        val menu = FakeMenu()
        val manager = boardManager()
        `when`(manager.isShowing()).thenAnswer { menu.up }
        doAnswer { menu.up = true; true }.`when`(manager).showMenuForKey()
        doAnswer { menu.up = false; null }.`when`(manager).hide()
        return menu
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

    private fun <T> anyArg(): T = ArgumentMatchers.any<T>() as T

    private fun keyUp(keyCode: Int, scanCode: Int): KeyEvent =
        KeyEvent(0L, 0L, KeyEvent.ACTION_UP, keyCode, 0, 0, 0, scanCode)

    private fun keyDown(keyCode: Int, scanCode: Int): KeyEvent =
        KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, keyCode, 0, 0, 0, scanCode)

    private fun pkbShape(): DeviceCapabilities = DeviceCapabilities.forShape(
        DeviceCapabilities.DetectedDeviceType.PKB,
        /* hasPhysicalKeyboard= */ true,
        /* hasTouchKeypad= */ false,
        /* isBlackBerryDevice= */ true,
        "qwerty",
        "4row"
    )
}

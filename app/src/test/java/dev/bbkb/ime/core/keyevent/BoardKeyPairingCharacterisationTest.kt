package dev.bbkb.ime.core.keyevent

import android.view.KeyEvent
import dev.bbkb.ime.core.BlackBerryIME
import dev.bbkb.ime.core.device.config.model.KeyRole
import dev.bbkb.ime.core.device.config.model.ScancodeMapping
import dev.bbkb.ime.core.device.config.resolver.ScancodeMappingResolver
import dev.bbkb.ime.core.device.detection.KeyEventDeviceClassifier
import dev.bbkb.ime.core.device.profile.DeviceCapabilities
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.device.state.PhysicalKeyboardStateTracker
import dev.bbkb.ime.core.locale.SubtypeManager
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.settings.util.SettingsManager
import dev.bbkb.ime.core.textinput.connection.EditorCapabilities
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.After
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
 * Phase 1g characterisation: the **down/up pairing** for the emoji, mic and multifunction keys.
 *
 * <p>These three keys do nothing on the way down. The key-DOWN arms a pending action and the
 * key-UP performs it, and before Phase 1g the arming travelled through three mutable public
 * fields on the `InputMethodHelper` singleton — armed by [KeyEventConverter], consumed by
 * [KeyEventProcessor], owned by neither.
 *
 * <p>Every assertion here was written and run green against the code as it stood at `de4d2092`,
 * *before* the pairing moved into [BoardKeyPressTracker]. The assertions are about observable
 * effects — which board the coordinator is asked for, whether cursor mode toggles, whether the
 * key is handed back to the framework — not about where the state lives, so the move cannot make
 * them pass by construction.
 *
 * <p>The one thing that had to change with the move is [arm], the helper that puts a pending
 * action in place: it used to assign `InputMethodHelper.emojiKeyPressed` and friends and now
 * calls the owner. Two of the tests below record a deliberate behaviour change and say so on the
 * test itself; everything else is behaviour that must not move.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class BoardKeyPairingCharacterisationTest {

    private lateinit var context: Context
    private lateinit var classifierStatic: MockedStatic<KeyEventDeviceClassifier>
    private lateinit var resolverStatic: MockedStatic<ScancodeMappingResolver>
    private lateinit var subtypeStatic: MockedStatic<SubtypeManager>

    private lateinit var classifier: KeyEventDeviceClassifier
    private lateinit var resolver: ScancodeMappingResolver

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

        resolverStatic = mockStatic(ScancodeMappingResolver::class.java)
        resolver = mock(ScancodeMappingResolver::class.java)
        `when`(resolver.resolve(anyInt(), anyInt())).thenReturn(null)
        resolverStatic.`when`<ScancodeMappingResolver> { ScancodeMappingResolver.getInstance() }
            .thenReturn(resolver)

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
        classifierStatic.close()
        resolverStatic.close()
        subtypeStatic.close()
        DeviceProfile.initialize(null)
        PrefsManager.getPrefs(context).edit().clear().commit()
    }

    // ===================================================== a matched down/up pair

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
        mapKey(KeyRole.MULTIFUNCTION, defaultAction = MultifunctionKeyHandler.ACTION_LANGUAGE_SWITCH)
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

    // ===================================================== a down with no up

    /**
     * Nothing consumes a pending action until a key-UP arrives, and the pending action survives
     * any number of further key-DOWNs. This is what makes "hold the mic key" work.
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
        mapKey(KeyRole.MULTIFUNCTION, defaultAction = MultifunctionKeyHandler.ACTION_LANGUAGE_SWITCH)
        arm(PendingKeyAction.MULTIFUNCTION, MIC_KEY, MIC_SCANCODE)

        processor.onKeyUpInternal(MIC_KEY, keyUp(MIC_KEY, MIC_SCANCODE))
        processor.onKeyUpInternal(MIC_KEY, keyUp(MIC_KEY, MIC_SCANCODE))

        verify(ime, times(1)).updateSuggestionsFromSubtype(InputSource.HARDWARE)
    }

    // ===================================================== an up with no down

    /**
     * An unarmed emoji key-up opens nothing — but it is still **consumed**, because the emoji
     * branch is gated on the key's identity and not on the pending action. That is what keeps the
     * key's base character from reaching the app.
     */
    @Test
    fun `an unarmed emoji key-up opens nothing and is still consumed`() {
        mapKey(KeyRole.BOARD_EMOJI)

        processor.onKeyUpInternal(EMOJI_KEY, keyUp(EMOJI_KEY, EMOJI_SCANCODE))

        verify(boardManager(), never()).requestBoard(anyInt())
        verify(ime, never()).superOnKeyUp(eq(EMOJI_KEY), anyArg())
    }

    /**
     * An unarmed mic key-up falls through the voice branch entirely — that branch is gated on the
     * pending action, not on the key — and lands in the generic tracked-key handling, which for a
     * board-role key hands it back to the framework.
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
        mapKey(KeyRole.MULTIFUNCTION, defaultAction = MultifunctionKeyHandler.ACTION_LANGUAGE_SWITCH)

        processor.onKeyUpInternal(MIC_KEY, keyUp(MIC_KEY, MIC_SCANCODE))

        verify(ime, never()).updateSuggestionsFromSubtype(InputSource.HARDWARE)
    }

    // ===================================================== the up need not be the armed key

    /**
     * **Characterisation of a quirk, not an endorsement.** The voice branch asks only "is a voice
     * action pending, and is this event physical" — it never asks whether *this* key is the key
     * that armed it. So an ordinary letter released while the mic key is still held opens the
     * voice board.
     *
     * <p>Phase 1g records the arming key's identity but deliberately does NOT gate consumption on
     * it: doing so is a behaviour change on top of the one the owner authorised. See
     * PHASE1G-SUMMARY.md.
     */
    @Test
    fun `a pending voice action is consumed by any physical key-up`() {
        arm(PendingKeyAction.VOICE_INPUT, MIC_KEY, MIC_SCANCODE)

        processor.onKeyUpInternal(ORDINARY_KEY, keyUp(ORDINARY_KEY, ORDINARY_SCANCODE))

        verify(boardManager()).requestBoard(-27)
    }

    /**
     * The same quirk on the multifunction branch, and it is worse there: the action dispatched is
     * read from the key being **released**, so an ordinary key's release with a multifunction
     * action pending resolves a null action and throws. Unreachable in practice (only a
     * MULTIFUNCTION key arms it, and its own release normally follows), pinned so a later rewrite
     * cannot quietly change it.
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
     * The `isPhysical()` guard inside the voice and multifunction branches is not the same test as
     * the "not a physical keyboard" bail-out at the top of the method: this one reads the
     * **remapped** event's classification, so the only way to reach it with a false answer is a
     * remap that hands back an event the classifier calls non-physical. Defensive in production —
     * `ControlModeController.withKeyAndMeta` copies the device id and the classifier keys on it —
     * but it is the guard, and a mutation that drops it otherwise survives the whole suite.
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
        mapKey(KeyRole.MULTIFUNCTION, defaultAction = MultifunctionKeyHandler.ACTION_LANGUAGE_SWITCH)
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

    // ===================================================== two board keys interleaved

    /**
     * Emoji and mic held at once: the two pending actions are independent slots, so each release
     * performs its own. The emoji branch runs first and returns, so an emoji release cannot spend
     * the voice action.
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
     * Arming the same slot twice — two emoji-role keys held together, or a repeat that re-armed —
     * is one pending action, not two: the second release opens nothing.
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

    // ===================================================== the reset contract

    /**
     * **A deliberate behaviour change, Phase 1g.** Before the move the three flags were cleared
     * only by the key-up that consumed them, so a modifier reset — the IME window hidden, input
     * finished, the editor switched, the screen off, the keyboard reloaded — left a pending
     * action armed indefinitely, and the next physical key-up anywhere opened the board. The
     * pending actions now clear with the rest of the per-key state.
     *
     * <p>Against `de4d2092` this test asserted the opposite (`verify(boardManager())
     * .requestBoard(-27)`) and passed.
     */
    @Test
    fun `a full modifier reset between the down and the up discards the pending action`() {
        mapKey(KeyRole.BOARD_VOICE)
        arm(PendingKeyAction.VOICE_INPUT, MIC_KEY, MIC_SCANCODE)

        tracker.resetAllMetaState()
        processor.onKeyUpInternal(MIC_KEY, keyUp(MIC_KEY, MIC_SCANCODE))

        verify(boardManager(), never()).requestBoard(anyInt())
    }

    /**
     * **The same deliberate change** on the multifunction slot, which is the one that could fire
     * a destructive action (switch language, toggle a board) off a stale arm.
     *
     * <p>Against `de4d2092` this test asserted the action fired (`verify(ime).toggleCursorMode()`,
     * when cursor mode was still a multifunction action) and passed.
     */
    @Test
    fun `a full modifier reset discards a pending multifunction action`() {
        mapKey(KeyRole.MULTIFUNCTION, defaultAction = MultifunctionKeyHandler.ACTION_LANGUAGE_SWITCH)
        arm(PendingKeyAction.MULTIFUNCTION, MIC_KEY, MIC_SCANCODE)

        tracker.resetAllMetaState()
        processor.onKeyUpInternal(MIC_KEY, keyUp(MIC_KEY, MIC_SCANCODE))

        verify(ime, never()).updateSuggestionsFromSubtype(InputSource.HARDWARE)
    }

    /**
     * A partial reset is not a full one. Leaving the Alt page spends the Alt span and nothing
     * else, so a pending action must survive it — the MP01 arms its emoji key with Alt in play.
     */
    @Test
    fun `leaving the Alt page leaves a pending action armed`() {
        mapKey(KeyRole.BOARD_EMOJI)
        arm(PendingKeyAction.EMOJI_BOARD, EMOJI_KEY, EMOJI_SCANCODE)

        tracker.resetAltStateAndNotify()
        processor.onKeyUpInternal(EMOJI_KEY, keyUp(EMOJI_KEY, EMOJI_SCANCODE))

        verify(boardManager()).requestBoard(-11)
    }

    // ===================================================== board keys on the key-DOWN path

    /**
     * The other half of the pairing contract: a board key's DOWN must not dismiss the boards or
     * disable cursor mode, because its UP is what decides, and disabling on the way down clears
     * the state that decision reads (the historical toggle-reopen fight).
     */
    @Test
    fun `a board key-down does not dismiss boards while an ordinary key-down does`() {
        mapKey(KeyRole.BOARD_EMOJI)
        processor.onKeyDownInternal(EMOJI_KEY, keyDown(EMOJI_KEY, EMOJI_SCANCODE))
        verify(ime, never()).dismissBoardsForTextKey()

        `when`(resolver.resolve(anyInt(), anyInt())).thenReturn(null)
        processor.onKeyDownInternal(ORDINARY_KEY, keyDown(ORDINARY_KEY, ORDINARY_SCANCODE))

        verify(ime).dismissBoardsForTextKey()
    }

    @Test
    fun `a multifunction key-down does not dismiss boards`() {
        mapKey(KeyRole.MULTIFUNCTION, defaultAction = MultifunctionKeyHandler.ACTION_LANGUAGE_SWITCH)

        processor.onKeyDownInternal(MIC_KEY, keyDown(MIC_KEY, MIC_SCANCODE))

        verify(ime, never()).dismissBoardsForTextKey()
        verify(ime, never()).applyCursorModeState(
            ArgumentMatchers.anyBoolean(), ArgumentMatchers.anyBoolean(),
            ArgumentMatchers.anyBoolean()
        )
    }

    // ===================================================== helpers

    private fun boardManager() = ime.getKeyboardSwitcher().unifiedInputBoardManager

    /**
     * Put a pending action in place, the way a key-DOWN would.
     *
     * <p>This is the ONE part of the suite the Phase 1g move rewrote. Against `de4d2092` it
     * assigned the `InputMethodHelper` flags:
     * <pre>
     *   EMOJI_BOARD   -> InputMethodHelper.getInstance().emojiKeyPressed = true
     *   VOICE_INPUT   -> InputMethodHelper.getInstance().micKeyPressed = true
     *   MULTIFUNCTION -> InputMethodHelper.getInstance().multifunctionKeyPressed = true
     * </pre>
     */
    private fun arm(action: PendingKeyAction, keyCode: Int, scanCode: Int) {
        BoardKeyPressTracker.getInstance().arm(keyDown(keyCode, scanCode), action)
    }

    private fun clearPairing() {
        BoardKeyPressTracker.getInstance().clearPendingActions()
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
        `when`(resolver.resolve(anyInt(), anyInt())).thenReturn(mapping)
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

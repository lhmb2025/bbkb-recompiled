package dev.bbkb.ime.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.settings.util.SettingsManager
import dev.bbkb.ime.core.textinput.connection.EditorCapabilities
import dev.bbkb.ime.keyboard.KeyboardSwitcher
import dev.bbkb.ime.keyboard.inputboard.UnifiedBoardCoordinator
import dev.bbkb.ime.keyboard.inputboard.UnifiedInputBoardManager
import dev.bbkb.ime.keyboard.inputboard.fcc.FccController
import dev.bbkb.ime.keyboard.state.CrossAxisRules
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.Locale

/**
 * Characterisation of the **PHYSICAL TEXT KEY** board policy — `dismissBoardsForTextKey`, raised
 * from `KeyEventProcessor` on every non-Shift, non-Enter, non-board physical key-down.
 *
 * This is one of the two "a key was pressed" policies, and until Phase 1d it was the only one with
 * no test at all. It is a different policy from the commit-driven rebuild (`SWITCH_LAYOUT`, whose
 * exempt set `CrossAxisRulesTest` pins): that one spares FCC, the number pad and voice; this one
 * used to spare FCC alone.
 *
 * **Owner ruling R3(b), 2026-09-22:** typing a letter must no longer close the number pad. The
 * number pad is a board the user types *from*, exactly like FCC, so it joins FCC in this policy's
 * exempt set. Voice deliberately does NOT: typing means the user has stopped dictating.
 *
 * The tests below are written against the *post-ruling* set and are marked where they would have
 * failed before it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class TextKeyBoardDismissalTest {

    private lateinit var switcherStatic: MockedStatic<KeyboardSwitcher>
    private lateinit var ime: BlackBerryIME
    private lateinit var switcher: KeyboardSwitcher
    private lateinit var uim: UnifiedInputBoardManager
    private lateinit var coordinator: UnifiedBoardCoordinator
    private lateinit var fcc: FccController

    private val cursorBoard = CrossAxisRules.CURSOR_BOARD_KEY_CODE
    private val numberPad = CrossAxisRules.NUMBER_PAD_KEY_CODE
    private val voice = CrossAxisRules.VOICE_KEY_CODE

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsManager.getInstance()
        ReflectionHelpers.setField(settings, "sharedPreferences", PrefsManager.getPrefs(context))
        ReflectionHelpers.setField(settings, "resources", context.resources)
        settings.loadSettings(
            context,
            Locale.US,
            EditorCapabilities(null, false, context.packageName, Locale.US, false),
        )
        setUim(true)
        setDynamicSearch(false)

        switcher = Mockito.mock(KeyboardSwitcher::class.java)
        uim = Mockito.mock(UnifiedInputBoardManager::class.java)
        coordinator = Mockito.mock(UnifiedBoardCoordinator::class.java)
        fcc = Mockito.mock(FccController::class.java)

        Mockito.`when`(switcher.getUnifiedInputBoardManager()).thenReturn(uim)
        Mockito.`when`(uim.getBoardCoordinator()).thenReturn(coordinator)
        Mockito.`when`(switcher.isEmojiKeyboardShowing()).thenReturn(false)

        switcherStatic = Mockito.mockStatic(KeyboardSwitcher::class.java)
        switcherStatic.`when`<KeyboardSwitcher> { KeyboardSwitcher.getInstance() }
            .thenReturn(switcher)

        ime = Mockito.mock(BlackBerryIME::class.java, Mockito.CALLS_REAL_METHODS)
        ReflectionHelpers.setField(ime, "keyboardSwitcher", switcher)
        ime.fccController = fcc
    }

    @After
    fun tearDown() {
        switcherStatic.close()
        ReflectionHelpers.setField(SettingsManager.getInstance(), "settingsValues", null)
    }

    private fun setUim(enabled: Boolean) = ReflectionHelpers.setField(
        SettingsManager.getInstance().getSettingsValues(), "isUimEnabled", enabled,
    )

    private fun setDynamicSearch(enabled: Boolean) = ReflectionHelpers.setField(
        SettingsManager.getInstance().getSettingsValues(), "isEmojiDynamicSearchEnabled", enabled,
    )

    /** Arrange "this board is the open one". */
    private fun open(keyCode: Int) = Mockito.`when`(coordinator.activeBoard()).thenReturn(keyCode)

    // ═══════════════════════════════════════════ the boards that survive

    /**
     * FCC keeps its own legacy dismissal with the mid-toggle exemption, and the coordinator is
     * reconciled only when FCC really did hide. The generic close is never asked for it.
     */
    @Test
    fun aTextKeyLeavesTheCursorBoardToFccsOwnExemptDismissal() {
        open(cursorBoard)
        Mockito.`when`(fcc.isViewActive()).thenReturn(true)

        ime.dismissBoardsForTextKey()

        verify(fcc).hideUnlessToggling()
        verify(coordinator, never()).onTextKeyPressed()
        verify(coordinator, never()).notifyBoardClosed()
        // the sweep still runs — it is defence-in-depth for components outside the coordinator
        verify(uim).hideComponentsExcept(intArrayOf(cursorBoard, numberPad))
    }

    /** FCC did hide (its view is gone): the coordinator is told, so the next press is not dead. */
    @Test
    fun aTextKeyThatReallyHidFccReconcilesTheCoordinator() {
        open(cursorBoard)
        Mockito.`when`(fcc.isViewActive()).thenReturn(false)

        ime.dismissBoardsForTextKey()

        verify(coordinator).notifyBoardClosed()
    }

    /**
     * **R3(b), the behaviour change.** Typing a letter no longer closes the number pad — it is a
     * board the user types FROM. Before the ruling the generic close fired here and the pad went
     * away under the user's hands.
     */
    @Test
    fun aTextKeyLeavesTheNumberPadOpen() {
        open(numberPad)

        ime.dismissBoardsForTextKey()

        verify(coordinator, never()).onTextKeyPressed()
    }

    /**
     * ...and the defence-in-depth sweep spares it too, not just the coordinator close: it runs
     * with the table's whole text-key exempt set, not just FCC.
     */
    @Test
    fun theDefenceInDepthSweepSparesTheWholeTextKeyExemptSet() {
        open(numberPad)

        ime.dismissBoardsForTextKey()

        verify(uim).hideComponentsExcept(intArrayOf(cursorBoard, numberPad))
    }

    // ══════════════════════════════════════════════ the boards that close

    /** Voice closes on a text key: typing means the user has stopped dictating. */
    @Test
    fun aTextKeyClosesTheVoiceBoard() {
        open(voice)

        ime.dismissBoardsForTextKey()

        verify(coordinator).onTextKeyPressed()
    }

    /** Emoji, clipboard and anything else close on a text key. */
    @Test
    fun aTextKeyClosesTheEmojiBoard() {
        open(CrossAxisRules.EMOJI_KEY_CODE)

        ime.dismissBoardsForTextKey()

        verify(coordinator).onTextKeyPressed()
    }

    /**
     * With nothing open the generic close still runs (it is a no-op inside the coordinator) and
     * the sweep goes with it. Pinned so the exemption above is visibly an exemption, not the
     * default.
     */
    @Test
    fun aTextKeyWithNoBoardOpenStillRunsTheCloseAndTheSweep() {
        open(UnifiedBoardCoordinator.NO_BOARD)

        ime.dismissBoardsForTextKey()

        verify(coordinator).onTextKeyPressed()
        verify(uim).hideComponentsExcept(intArrayOf(cursorBoard, numberPad))
        verify(uim).refresh()
    }

    // ════════════════════════════════════════ emoji dynamic search wins

    /**
     * **R3(c).** While emoji dynamic search is active a text key is search input, not typing: it
     * closes NO board, not even the ones that would otherwise go.
     */
    @Test
    fun aTextKeyDuringEmojiDynamicSearchClosesNothing() {
        setDynamicSearch(true)
        Mockito.`when`(switcher.isEmojiKeyboardShowing()).thenReturn(true)
        open(voice)

        ime.dismissBoardsForTextKey()

        verify(coordinator, never()).onTextKeyPressed()
        verify(uim, never()).hideComponentsExcept(Mockito.any())
        verify(uim).refresh()
    }

    /** Dynamic search enabled but the emoji board is down: the ordinary policy applies. */
    @Test
    fun dynamicSearchWithTheEmojiBoardDownDoesNotSpareAnything() {
        setDynamicSearch(true)
        Mockito.`when`(switcher.isEmojiKeyboardShowing()).thenReturn(false)
        open(voice)

        ime.dismissBoardsForTextKey()

        verify(coordinator).onTextKeyPressed()
    }

    // ═══════════════════════════════════════════════ the UIM-disabled path

    /**
     * With the UIM disabled the coordinator path is skipped entirely and the emoji board, if it is
     * up, is closed through `closeActiveComponent()`.
     */
    @Test
    fun withTheUimDisabledAnOpenEmojiBoardIsClosedThroughTheLegacyPath() {
        setUim(false)
        Mockito.`when`(switcher.isEmojiKeyboardShowing()).thenReturn(true)

        ime.dismissBoardsForTextKey()

        verify(coordinator, never()).onTextKeyPressed()
        verify(uim).closeActiveComponent()
    }

    @Test
    fun withTheUimDisabledAndNoEmojiBoardNothingIsClosed() {
        setUim(false)
        Mockito.`when`(switcher.isEmojiKeyboardShowing()).thenReturn(false)

        ime.dismissBoardsForTextKey()

        verify(uim, never()).closeActiveComponent()
        verify(uim, never()).refresh()
    }
}

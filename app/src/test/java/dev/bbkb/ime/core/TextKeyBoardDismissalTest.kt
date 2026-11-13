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
 * Pins `dismissBoardsForTextKey`, raised from `KeyEventProcessor` on every non-Shift, non-Enter,
 * non-board physical key-down. Exempt: FCC and the number pad (boards the user types from). Voice
 * closes, since typing means dictation is over. Differs from the commit-driven `SWITCH_LAYOUT`
 * rebuild pinned in `CrossAxisRulesTest`.
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
        SettingsManager.getInstance().getSettingsValues(), "uimEnabledSetting", enabled,
    )

    private fun setDynamicSearch(enabled: Boolean) = ReflectionHelpers.setField(
        SettingsManager.getInstance().getSettingsValues(), "isEmojiDynamicSearchEnabled", enabled,
    )

    /** Arrange "this board is the open one". */
    private fun open(keyCode: Int) = Mockito.`when`(coordinator.activeBoard()).thenReturn(keyCode)

    /**
     * FCC keeps its own dismissal with the mid-toggle exemption; the coordinator is reconciled only
     * when FCC really hid. The generic close is never asked for it.
     */
    @Test
    fun aTextKeyLeavesTheCursorBoardToFccsOwnExemptDismissal() {
        open(cursorBoard)
        Mockito.`when`(fcc.isViewActive()).thenReturn(true)

        ime.dismissBoardsForTextKey()

        verify(fcc).hideUnlessToggling()
        verify(coordinator, never()).onTextKeyPressed()
        verify(coordinator, never()).notifyBoardClosed()
        // the sweep still runs as defence in depth for components outside the coordinator
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

    /** Typing a letter does not close the number pad: it is a board the user types from. */
    @Test
    fun aTextKeyLeavesTheNumberPadOpen() {
        open(numberPad)

        ime.dismissBoardsForTextKey()

        verify(coordinator, never()).onTextKeyPressed()
    }

    /** The defence-in-depth sweep spares it too: it uses the table's whole text-key exempt set. */
    @Test
    fun theDefenceInDepthSweepSparesTheWholeTextKeyExemptSet() {
        open(numberPad)

        ime.dismissBoardsForTextKey()

        verify(uim).hideComponentsExcept(intArrayOf(cursorBoard, numberPad))
    }

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

    /** With nothing open the generic close and sweep still run (no-op), so the exemptions are visibly exemptions. */
    @Test
    fun aTextKeyWithNoBoardOpenStillRunsTheCloseAndTheSweep() {
        open(UnifiedBoardCoordinator.NO_BOARD)

        ime.dismissBoardsForTextKey()

        verify(coordinator).onTextKeyPressed()
        verify(uim).hideComponentsExcept(intArrayOf(cursorBoard, numberPad))
        verify(uim).refresh()
    }

    /**
     * The emoji board takes its own text keys upstream in KeyEventProcessor, so a text key reaching
     * here is one it did not want: the ordinary policy applies whether dynamic search is on or off.
     */
    @Test
    fun aTextKeyThatReachesDismissalDuringEmojiDynamicSearchFollowsTheOrdinaryPolicy() {
        setDynamicSearch(true)
        Mockito.`when`(switcher.isEmojiKeyboardShowing()).thenReturn(true)
        open(voice)

        ime.dismissBoardsForTextKey()

        verify(coordinator).onTextKeyPressed()
        verify(uim).hideComponentsExcept(intArrayOf(cursorBoard, numberPad))
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

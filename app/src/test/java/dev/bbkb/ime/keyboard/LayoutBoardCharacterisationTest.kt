package dev.bbkb.ime.keyboard

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.BlackBerryIME
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.settings.util.SettingsManager
import dev.bbkb.ime.core.locale.SubtypeManager
import dev.bbkb.ime.core.textinput.connection.EditorCapabilities
import dev.bbkb.ime.keyboard.inputboard.UnifiedBoardCoordinator
import dev.bbkb.ime.keyboard.inputboard.UnifiedInputBoardManager
import dev.bbkb.ime.keyboard.internal.KeyboardId
import dev.bbkb.ime.keyboard.slideboard.SlideboardManager
import dev.bbkb.ime.keyboard.internal.KeyboardState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.Locale

/**
 * Pins which layout-set element each [KeyboardState.SwitcherCallbacks] loader asks
 * [KeyboardBuilder] for, through which entry point (VKB `getKeyboardForShift` or PKB
 * `getKeyboardInternal`), and what each load does to the aux bar and slideboard. Uses
 * [KeyboardSwitcherLayoutTransitionTest]'s harness: a switcher whose constructor never ran.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class LayoutBoardCharacterisationTest {

    private lateinit var switcher: KeyboardSwitcher
    private lateinit var uim: UnifiedInputBoardManager
    private lateinit var ime: BlackBerryIME
    private lateinit var builder: KeyboardBuilder
    private lateinit var mainKeyboardView: MainKeyboardView
    private lateinit var slideboard: SlideboardManager
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        // The code under test calls the engine from the main thread; run engine ops inline so the
        // debug thread assertions pass.
        dev.bbkb.ime.core.engine.EngineThreads.setInlineForTests(true)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = SettingsManager.getInstance()
        ReflectionHelpers.setField(settings, "sharedPreferences", PrefsManager.getPrefs(context))
        ReflectionHelpers.setField(settings, "resources", context.resources)
        settings.loadSettings(
            context,
            Locale.US,
            EditorCapabilities(null, false, context.packageName, Locale.US, false),
        )

        switcher = Mockito.mock(KeyboardSwitcher::class.java, Mockito.CALLS_REAL_METHODS)
        uim = Mockito.mock(UnifiedInputBoardManager::class.java)
        ime = Mockito.mock(BlackBerryIME::class.java)
        builder = Mockito.mock(KeyboardBuilder::class.java)
        mainKeyboardView = Mockito.mock(MainKeyboardView::class.java)
        slideboard = Mockito.mock(SlideboardManager::class.java)
        prefs = Mockito.mock(SharedPreferences::class.java)

        inject("unifiedInputBoardManager", uim)
        inject("blackberryIme", ime)
        inject("keyboardBuilder", builder)
        inject("sharedPreferences", prefs)
        inject("keyboardState", Mockito.mock(KeyboardState::class.java))
        inject("subtypeManager", SubtypeManager.getInstance())
        inject("mainKeyboardView", mainKeyboardView)
        inject("slideboardManager", slideboard)
        Mockito.`when`(mainKeyboardView.keyboard).thenReturn(null)

        Mockito.`when`(builder.getKeyboardForShift(Mockito.anyInt(), Mockito.anyBoolean()))
            .thenReturn(emptyKeyboard())
        Mockito.`when`(builder.getKeyboardInternal(Mockito.anyInt(), Mockito.anyBoolean()))
            .thenReturn(emptyKeyboard())
    }

    @After
    fun tearDown() {
        ReflectionHelpers.setField(SettingsManager.getInstance(), "settingsValues", null)
        // requestManualShiftOff() sets this process-wide flag; put it back for the next class.
        DeviceProfile.setForceVkbMode(false)
        dev.bbkb.ime.core.engine.EngineThreads.setInlineForTests(false)
    }

    private fun inject(name: String, value: Any?) =
        ReflectionHelpers.setField(KeyboardSwitcher::class.java, switcher, name, value)

    private fun emptyKeyboard(): Keyboard {
        val keyboard = Mockito.mock(Keyboard::class.java)
        ReflectionHelpers.setField(keyboard, "mId", Mockito.mock(KeyboardId::class.java))
        return keyboard
    }

    /** The element id the VKB entry point was asked for. */
    private fun vkbElement(): Int {
        val element = ArgumentCaptor.forClass(Int::class.java)
        verify(builder).getKeyboardForShift(element.capture(), Mockito.anyBoolean())
        return element.value
    }

    /** The "build it for a hardware keyboard" flag the VKB entry point was given. */
    private fun vkbHardwareFlag(): Boolean {
        val flag = ArgumentCaptor.forClass(Boolean::class.java)
        verify(builder).getKeyboardForShift(Mockito.anyInt(), flag.capture())
        return flag.value
    }

    private fun pkbElement(): Int {
        val element = ArgumentCaptor.forClass(Int::class.java)
        verify(builder).getKeyboardInternal(element.capture(), Mockito.anyBoolean())
        return element.value
    }

    /** The alphabet board's five faces: the shift mode is passed straight through as the element id. */
    @Test
    fun theAlphabetLayoutSelectsTheElementForItsShiftMode() {
        val cases = listOf(
            0 to Runnable { switcher.requestShiftOff() },
            1 to Runnable { switcher.requestShiftOnce() },
            2 to Runnable { switcher.requestAutomaticShift() },
            3 to Runnable { switcher.requestShiftLocked() },
            4 to Runnable { switcher.requestShiftMomentary() },
        )
        for ((expected, act) in cases) {
            Mockito.clearInvocations(builder)
            act.run()
            assertEquals("alphabet element for shift mode $expected", expected, vkbElement())
        }
    }

    /**
     * `requestManualShiftOff` bypasses `setAlphabetKeyboard(int)`: it builds element 0 with the
     * hardware flag false, then tells the IME it came back from the symbol keyboard.
     */
    @Test
    fun theManualShiftOffPathBuildsElementZeroAsAnOnScreenKeyboard() {
        switcher.requestManualShiftOff()

        assertEquals(0, vkbElement())
        assertEquals(false, vkbHardwareFlag())
        verify(ime).restoreSuggestionStrip(false, true)
    }

    /**
     * The alphabet load's hardware flag is "no on-screen keyboard showing", and in that case the
     * slideboard comes up with it.
     */
    @Test
    fun theAlphabetLoadWithNoOnScreenKeyboardBuildsForHardwareAndRaisesTheSlideboard() {
        Mockito.`when`(ime.refreshOnScreenKeyboardShowing()).thenReturn(false)

        switcher.requestShiftOff()

        assertEquals(true, vkbHardwareFlag())
        verify(slideboard).show()
    }

    @Test
    fun theAlphabetLoadWithAnOnScreenKeyboardBuildsForTouchAndLeavesTheSlideboardAlone() {
        Mockito.`when`(ime.refreshOnScreenKeyboardShowing()).thenReturn(true)

        switcher.requestShiftOff()

        assertEquals(false, vkbHardwareFlag())
        verify(slideboard, never()).show()
    }

    /** The alphabet load never raises the aux bar. */
    @Test
    fun theAlphabetLoadLeavesTheBarAlone() {
        Mockito.`when`(ime.isUimEnabled()).thenReturn(true)
        Mockito.`when`(uim.isShowing()).thenReturn(false)

        switcher.requestShiftOff()

        verify(uim, never()).show(Mockito.anyBoolean())
    }

    /** The on-screen symbol pages live at elements page + 5. */
    @Test
    fun theOnScreenSymbolLayoutSelectsPagePlusFive() {
        for (page in 0..2) {
            Mockito.clearInvocations(builder)
            switcher.setVkbSymbolsKeyboard(page, false, false, 0)
            assertEquals("vkb symbol page $page", page + 5, vkbElement())
        }
    }

    /** With the custom page first it takes slot 0, so page 1 is element 5, not 6. */
    @Test
    fun theOnScreenSymbolLayoutShiftsDownOneWhenTheCustomPageComesFirst() {
        Mockito.`when`(prefs.getBoolean("enable_symbol_customization_vkb", false)).thenReturn(true)
        Mockito.`when`(prefs.getBoolean("vkb_custom_page_first", false)).thenReturn(true)

        switcher.setVkbSymbolsKeyboard(1, false, false, 0)

        assertEquals(5, vkbElement())
    }

    /** With the custom page last the ordinary pages stay where they were. */
    @Test
    fun theOnScreenSymbolLayoutKeepsPagePlusFiveWhenTheCustomPageComesLast() {
        Mockito.`when`(prefs.getBoolean("enable_symbol_customization_vkb", false)).thenReturn(true)
        Mockito.`when`(prefs.getBoolean("vkb_custom_page_first", false)).thenReturn(false)

        switcher.setVkbSymbolsKeyboard(1, false, false, 0)

        assertEquals(6, vkbElement())
    }

    /** The custom symbol page is element 8 whatever its position, and it carries its own page. */
    @Test
    fun theOnScreenCustomSymbolPageIsElementEight() {
        switcher.setVkbSymbolsKeyboard(0, false, true, 3)

        assertEquals(8, vkbElement())
        verify(builder).setCustomSymbolPage(3)
    }

    /** The on-screen symbol load leaves the bar where it was. */
    @Test
    fun theOnScreenSymbolLoadLeavesTheBarAlone() {
        Mockito.`when`(ime.isUimEnabled()).thenReturn(true)
        Mockito.`when`(uim.isShowing()).thenReturn(false)

        switcher.setVkbSymbolsKeyboard(0, false, false, 0)

        verify(uim, never()).show(Mockito.anyBoolean())
        verify(slideboard, never()).showNumericPanel()
    }

    /** The PKB symbol pages use the same page+5 arithmetic, through the hardware entry point. */
    @Test
    fun thePkbSymbolLayoutSelectsPagePlusFiveThroughTheHardwareEntryPoint() {
        for (page in 0..2) {
            Mockito.clearInvocations(builder)
            switcher.setPkbSymbolsKeyboard(page, true, false, 0)
            assertEquals("pkb symbol page $page", page + 5, pkbElement())
            verify(builder, never()).getKeyboardForShift(Mockito.anyInt(), Mockito.anyBoolean())
        }
    }

    /** The PKB custom page has its own pair of preferences, and is element 8 like the VKB one. */
    @Test
    fun thePkbCustomSymbolPageIsElementEightAndUsesThePkbPreferences() {
        Mockito.`when`(prefs.getBoolean("enable_symbol_customization_pkb", false)).thenReturn(true)
        Mockito.`when`(prefs.getBoolean("pkb_custom_page_first", false)).thenReturn(true)

        switcher.setPkbSymbolsKeyboard(0, true, true, 2)

        assertEquals(8, pkbElement())
        verify(builder).setCustomSymbolPage(2)
    }

    @Test
    fun thePkbSymbolLayoutShiftsDownOneWhenThePkbCustomPageComesFirst() {
        Mockito.`when`(prefs.getBoolean("enable_symbol_customization_pkb", false)).thenReturn(true)
        Mockito.`when`(prefs.getBoolean("pkb_custom_page_first", false)).thenReturn(true)

        switcher.setPkbSymbolsKeyboard(1, true, false, 0)

        assertEquals(5, pkbElement())
    }

    /** The PKB symbol load raises the slideboard's numeric panel and leaves the bar as it was. */
    @Test
    fun thePkbSymbolLoadRaisesTheNumericPanelAndLeavesTheBarAlone() {
        Mockito.`when`(ime.isUimEnabled()).thenReturn(true)
        Mockito.`when`(uim.isShowing()).thenReturn(false)

        switcher.setPkbSymbolsKeyboard(0, true, false, 0)

        verify(uim, never()).show(Mockito.anyBoolean())
        verify(uim, never()).showEmojiBoard()
        verify(slideboard).showNumericPanel()
    }

    /** The numeric panel is not conditional on the UIM: it comes up even with the bar disabled. */
    @Test
    fun thePkbSymbolLoadRaisesTheNumericPanelEvenWithTheBarDisabled() {
        Mockito.`when`(ime.isUimEnabled()).thenReturn(false)

        switcher.setPkbSymbolsKeyboard(0, true, false, 0)

        verify(uim, never()).show(Mockito.anyBoolean())
        verify(slideboard).showNumericPanel()
    }

    /** Opening emoji raises the bar (`show(false)` then `showEmojiBoard()`) only when it is not already up. */
    @Test
    fun theEmojiOpenRaisesTheBarWhenItIsDown() {
        Mockito.`when`(ime.isUimEnabled()).thenReturn(true)
        Mockito.`when`(uim.isShowing()).thenReturn(false)

        switcher.showEmojiKeyboard()

        val inOrder = Mockito.inOrder(uim)
        inOrder.verify(uim).show(false)
        inOrder.verify(uim).showEmojiBoard()
    }

    @Test
    fun theEmojiOpenLeavesAnAlreadyRaisedBarAlone() {
        Mockito.`when`(ime.isUimEnabled()).thenReturn(true)
        Mockito.`when`(uim.isShowing()).thenReturn(true)

        switcher.showEmojiKeyboard()

        verify(uim, never()).show(Mockito.anyBoolean())
        verify(uim, never()).showEmojiBoard()
    }

    /** With the UIM disabled the emoji board opens with no bar at all. */
    @Test
    fun theEmojiOpenWithTheUimDisabledRaisesNoBar() {
        Mockito.`when`(ime.isUimEnabled()).thenReturn(false)

        switcher.showEmojiKeyboard()

        verify(uim, never()).show(Mockito.anyBoolean())
    }

    /** The open is reported to the board axis, by keycode, as board -11. */
    @Test
    fun theEmojiOpenReportsBoardMinusElevenOpen() {
        Mockito.`when`(ime.isUimEnabled()).thenReturn(false)

        switcher.showEmojiKeyboard()

        verify(uim).setActiveComponentByKeyCode(-11)
        verify(uim).refresh()
    }

    /**
     * The open report is the `BOARD_RAISED` epilogue, right after the bar raise. Safe because
     * `EmojiBoardController.isShowing()` reads the mode, already set before this callback.
     */
    @Test
    fun theEmojiOpenRaisesTheBarThenReportsTheOpenThenRefreshes() {
        Mockito.`when`(ime.isUimEnabled()).thenReturn(true)
        Mockito.`when`(uim.isShowing()).thenReturn(false)

        switcher.showEmojiKeyboard()

        val inOrder = Mockito.inOrder(uim)
        inOrder.verify(uim).show(false)
        inOrder.verify(uim).showEmojiBoard()
        inOrder.verify(uim).setActiveComponentByKeyCode(-11)
        inOrder.verify(uim).refresh()
    }

    /**
     * Opening emoji clears a PKB symbol mode first, like every other board; otherwise the next Sym
     * press sees `wasSymbolEnteredFromAlphabet()` false and hinted keys type letters. It must run
     * before the mode moves to EMOJI, hence `BOARD_OPENING` + `BOARD_RAISED`.
     */
    @Test
    fun openingEmojiFromThePkbSymbolBoardClearsTheSymbolModeFirst() {
        val state = pkbInSymbolMode()

        switcher.onEmojiKeyPressed()

        val inOrder = Mockito.inOrder(state)
        inOrder.verify(state).resetSymbolMode()
        inOrder.verify(state).onEmojiInput()
    }

    /** Closing it with the same toggle must not run the open prologue. */
    @Test
    fun theEmojiKeyThatCLOSESTheBoardRunsNoOpenPrologue() {
        val state = pkbInSymbolMode()
        Mockito.`when`(state.isInEmojiMode()).thenReturn(true)

        switcher.onEmojiKeyPressed()

        verify(state, never()).resetSymbolMode()
        verify(state).onEmojiInput()
    }

    /** The -11 code path reaches the same toggle, so it gets the same prologue. */
    @Test
    fun theEmojiCODEPathAlsoRunsTheOpenPrologue() {
        val state = pkbInSymbolMode()

        switcher.onInputCodeChanged(-11, 0, -1)

        verify(state).resetSymbolMode()
    }

    /** ...and no other code does. */
    @Test
    fun anyOtherCodeRunsNoEmojiOpenPrologue() {
        val state = pkbInSymbolMode()

        switcher.onInputCodeChanged(-14, 0, -1)

        verify(state, never()).resetSymbolMode()
    }

    /** Off a PKB the prologue is a no-op. */
    @Test
    fun openingEmojiOnAVkbClearsNoSymbolMode() {
        val state = pkbInSymbolMode()
        Mockito.`when`(mainKeyboardView.keyboard).thenReturn(null) // isPkbDevice() -> false

        switcher.onEmojiKeyPressed()

        verify(state, never()).resetSymbolMode()
    }

    /** A PKB that is NOT in symbol mode has nothing to clear either. */
    @Test
    fun openingEmojiFromThePkbAlphabetClearsNothing() {
        val state = pkbInSymbolMode()
        Mockito.`when`(state.isInSymbolMode()).thenReturn(false)

        switcher.onEmojiKeyPressed()

        verify(state, never()).resetSymbolMode()
    }

    /** A PKB (`isPkbDevice()` reads the current keyboard's `isTouchKeyboard()`) in symbol mode. */
    private fun pkbInSymbolMode(): KeyboardState {
        val keyboard = emptyKeyboard()
        Mockito.`when`(keyboard.isTouchKeyboard).thenReturn(true)
        Mockito.`when`(mainKeyboardView.keyboard).thenReturn(keyboard)
        val state = Mockito.mock(KeyboardState::class.java)
        Mockito.`when`(state.isInSymbolMode()).thenReturn(true)
        Mockito.`when`(state.isInEmojiMode()).thenReturn(false)
        inject("keyboardState", state)
        return state
    }

    /** A panel board wins whenever one is open, because it is drawn over the main keyboard view. */
    @Test
    fun anOpenPanelBoardIsTheActiveBoard() {
        val coordinator =
            UnifiedBoardCoordinator(Mockito.mock(UnifiedBoardCoordinator.BoardHost::class.java))
        coordinator.notifyBoardOpened(-46)
        Mockito.`when`(uim.getBoardCoordinator()).thenReturn(coordinator)

        assertEquals(-46, switcher.activeBoard())
    }

    /** With no panel board open, the typing board is the layout mode read as a board. */
    @Test
    fun withNoPanelBoardOpenTheLayoutModeIsTheActiveBoard() {
        val coordinator =
            UnifiedBoardCoordinator(Mockito.mock(UnifiedBoardCoordinator.BoardHost::class.java))
        Mockito.`when`(uim.getBoardCoordinator()).thenReturn(coordinator)
        val state = Mockito.mock(KeyboardState::class.java)
        inject("keyboardState", state)

        for ((mode, expected) in listOf(
            KeyboardState.KeyboardModeState.ALPHABET to -3,
            KeyboardState.KeyboardModeState.SYMBOL to -22,
            KeyboardState.KeyboardModeState.EMOJI to -11,
            KeyboardState.KeyboardModeState.MENU to -23,
        )) {
            Mockito.`when`(state.currentMode()).thenReturn(mode)
            assertEquals("board for layout mode $mode", expected, switcher.activeBoard())
        }
    }

    /** Some board is always up; with nothing else it is the alphabet. */
    @Test
    fun theAlphabetIsTheBoardThatIsUpWhenNothingElseIs() {
        inject("unifiedInputBoardManager", null)
        inject("keyboardState", null)

        assertEquals(-3, switcher.activeBoard())
    }
}

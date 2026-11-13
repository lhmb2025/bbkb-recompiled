package dev.bbkb.ime.keyboard

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.BlackBerryIME
import dev.bbkb.ime.core.settings.PrefsManager
import org.junit.After
import dev.bbkb.ime.core.textinput.connection.EditorCapabilities
import dev.bbkb.ime.core.settings.util.SettingsManager
import dev.bbkb.ime.core.locale.SubtypeManager
import dev.bbkb.ime.keyboard.inputboard.UnifiedInputBoardManager
import dev.bbkb.ime.keyboard.internal.KeyboardId
import dev.bbkb.ime.keyboard.internal.KeyboardState
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.Locale

/**
 * Pins that every path rebuilding the main keyboard (alphabet load, on-screen symbol load, PKB
 * symbol load, Sym page turn) tells the board axis first. The switcher is built without its
 * constructor (`CALLS_REAL_METHODS`) against a mock builder whose keyboards carry no keys.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class KeyboardSwitcherLayoutTransitionTest {

    private lateinit var switcher: KeyboardSwitcher
    private lateinit var uim: UnifiedInputBoardManager
    private lateinit var ime: BlackBerryIME
    private lateinit var builder: KeyboardBuilder
    private lateinit var mainKeyboardView: MainKeyboardView

    @Before
    fun setUp() {
        // Wired by hand, not SettingsManager.initialize(): that registers a process-wide prefs
        // listener that outlives this class and makes later tests hit a SettingsValues NPE.
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

        inject("unifiedInputBoardManager", uim)
        inject("blackberryIme", ime)
        inject("keyboardBuilder", builder)
        inject("sharedPreferences", Mockito.mock(SharedPreferences::class.java))
        inject("keyboardState", Mockito.mock(KeyboardState::class.java))
        inject("subtypeManager", SubtypeManager.getInstance())
        inject("mainKeyboardView", mainKeyboardView)
        // getCurrentKeyboard() == null: updateSecondaryKeySpec and syncKeyboardLayout short-circuit.
        Mockito.`when`(mainKeyboardView.keyboard).thenReturn(null)

        // A board is open behind the bar and no exempt board is up, so the sweep is not spared.
        Mockito.`when`(uim.isShowing()).thenReturn(true)
        Mockito.`when`(uim.isAnyBoardShowing()).thenReturn(true)
    }

    @After
    fun tearDown() {
        // Leave the process-wide singleton as this class found it.
        ReflectionHelpers.setField(SettingsManager.getInstance(), "settingsValues", null)
    }

    private fun inject(name: String, value: Any?) =
        ReflectionHelpers.setField(KeyboardSwitcher::class.java, switcher, name, value)

    /** A keyboard with just enough shape for the symbol loaders' last few lines. */
    private fun emptyKeyboard(): Keyboard {
        val keyboard = Mockito.mock(Keyboard::class.java)
        ReflectionHelpers.setField(keyboard, "mId", Mockito.mock(KeyboardId::class.java))
        return keyboard
    }

    /** Every alphabet load (one runs after each committed character) tells the board axis. */
    @Test
    fun theAlphabetLoadRaisesALayoutChange() {
        switcher.requestShiftOnce()

        verify(uim).hideKeyboardOnKeyboardStateChange()
    }

    /**
     * Cold start: a physical key can drive the shift chain before startInput() has built a
     * keyboard. With no builder the load is skipped and the board axis is not told.
     */
    @Test
    fun aShiftRequestBeforeTheFirstStartInputIsIgnored() {
        inject("keyboardBuilder", null)

        switcher.requestAutomaticShift()
        switcher.requestShiftOnce()

        verify(uim, never()).hideKeyboardOnKeyboardStateChange()
        verify(ime, never()).refreshOnScreenKeyboardShowing()
    }

    @Test
    fun theOnScreenSymbolLoadRaisesALayoutChange() {
        Mockito.`when`(builder.getKeyboardForShift(Mockito.anyInt(), Mockito.anyBoolean()))
            .thenReturn(emptyKeyboard())

        switcher.setVkbSymbolsKeyboard(0, false, true, 0)

        verify(uim).hideKeyboardOnKeyboardStateChange()
    }

    /**
     * The PKB symbol load (Sym and each page turn) leaves the bar as it finds it. Raising it made
     * the bar flash on each page turn, as the next suggestion update took the slot back.
     */
    @Test
    fun thePkbSymbolLoadDoesNotRaiseTheBar() {
        Mockito.`when`(ime.isUimEnabled()).thenReturn(true)
        Mockito.`when`(uim.isShowing()).thenReturn(false)
        Mockito.`when`(builder.getKeyboardInternal(Mockito.anyInt(), Mockito.anyBoolean()))
            .thenReturn(emptyKeyboard())

        switcher.setPkbSymbolsKeyboard(0, true, true, 0)

        verify(uim, Mockito.never()).show(Mockito.anyBoolean())
        verify(uim, Mockito.never()).showEmojiBoard()
    }

    /** With the bar up and a board open behind it, the PKB symbol load still sweeps the board. */
    @Test
    fun thePkbSymbolLoadSweepsTheBoardsBehindARaisedBar() {
        Mockito.`when`(ime.isUimEnabled()).thenReturn(true)
        Mockito.`when`(builder.getKeyboardInternal(Mockito.anyInt(), Mockito.anyBoolean()))
            .thenReturn(emptyKeyboard())

        switcher.setPkbSymbolsKeyboard(0, true, true, 0)

        verify(uim, Mockito.never()).show(Mockito.anyBoolean())
        verify(uim).hideKeyboardOnKeyboardStateChange()
    }

    /** With the number pad up, the layout change spares every board, even a non-exempt one. */
    @Test
    fun anExemptBoardBeingUpSparesTheLayoutChangeSweep() {
        Mockito.`when`(uim.isBoardViewShowing(-46)).thenReturn(true)

        switcher.requestShiftOnce()

        verify(uim, Mockito.never()).hideKeyboardOnKeyboardStateChange()
    }

    /** With nothing open there is nothing to sweep, and the board axis is left alone entirely. */
    @Test
    fun aLayoutChangeWithNoBoardOpenTouchesTheBoardAxisNotAtAll() {
        Mockito.`when`(uim.isAnyBoardShowing()).thenReturn(false)

        switcher.requestShiftOnce()

        verify(uim, Mockito.never()).hideKeyboardOnKeyboardStateChange()
    }

    /** With the UIM disabled the board axis is still told. */
    @Test
    fun thePkbSymbolLoadWithTheUimDisabledStillRaisesTheLayoutChange() {
        Mockito.`when`(ime.isUimEnabled()).thenReturn(false)
        Mockito.`when`(builder.getKeyboardInternal(Mockito.anyInt(), Mockito.anyBoolean()))
            .thenReturn(emptyKeyboard())

        switcher.setPkbSymbolsKeyboard(0, true, true, 0)

        verify(uim, Mockito.never()).show(Mockito.anyBoolean())
        verify(uim).hideKeyboardOnKeyboardStateChange()
    }

    /** The Sym page turn raises the change before the state machine runs, even with no current keyboard. */
    @Test
    fun theSymbolPageTurnRaisesALayoutChangeEvenWhenItCannotProceed() {
        switcher.onSymbolShiftToggle(0, -1, false, true)

        verify(uim).hideKeyboardOnKeyboardStateChange()
    }
}

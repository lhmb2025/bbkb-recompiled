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
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.Locale

/**
 * Characterisation of the **layout axis' cross-axis edge**: every path that rebuilds the main
 * keyboard tells the board axis about it first.
 *
 * Four sites raise that change — the alphabet load, the on-screen symbol load, the PKB symbol load
 * and the Sym page turn — and before Phase 1a each of them carried its own hand-written
 * `unifiedInputBoardManager.hideKeyboardOnKeyboardStateChange()` call. These tests pin the effect
 * at the boundary (the sweep really is asked for, and the PKB symbol load raises the bar before
 * asking it) so the move to `KeyboardStateCoordinator.apply(switchLayout(...))` can be verified to
 * change nothing.
 *
 * The switcher is instantiated without running its constructor (`CALLS_REAL_METHODS` under the
 * inline mock maker) and given only the collaborators these paths touch. The keyboard build itself
 * is not stubbed out — it runs against a mock builder whose product carries no keys, which is
 * enough for every downstream step to short-circuit.
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
        // Settings are wired by hand rather than through SettingsManager.initialize(): initialize()
        // registers a process-wide SharedPreferences listener that survives this test class, and
        // any later test that commits a preference then re-enters loadSettings with no editor
        // capabilities and trips the known SettingsValues constructor NPE. Only getSettingsValues()
        // is needed here, so only that is set up — and @After puts it back.
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

        // A board IS open behind the bar, which is the only state in which the layout change has
        // anything to do on the board axis. No exempt board is up (the mocked isBoardViewShowing
        // answers false), so the sweep is not spared.
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

    /**
     * Every alphabet load — and the shift chain runs one after every committed character — tells
     * the board axis. This is the path the exemption list exists for.
     */
    @Test
    fun theAlphabetLoadRaisesALayoutChange() {
        switcher.requestShiftOnce()

        verify(uim).hideKeyboardOnKeyboardStateChange()
    }

    @Test
    fun theOnScreenSymbolLoadRaisesALayoutChange() {
        Mockito.`when`(builder.getKeyboardForShift(Mockito.anyInt(), Mockito.anyBoolean()))
            .thenReturn(emptyKeyboard())

        switcher.setVkbSymbolsKeyboard(0, false, true, 0)

        verify(uim).hideKeyboardOnKeyboardStateChange()
    }

    /**
     * The PKB symbol load raises the bar FIRST and only then tells the board axis — the order the
     * on-device behaviour depends on, since the sweep's own gate asks whether the bar is up.
     */
    @Test
    fun thePkbSymbolLoadRaisesTheBarBeforeTheLayoutChange() {
        Mockito.`when`(ime.isUimEnabled()).thenReturn(true)
        // The bar starts down and is up once show(false) has run — the sweep's own gate reads it
        // AFTER the raise, exactly as it does on the device.
        Mockito.`when`(uim.isShowing()).thenReturn(false, true)
        Mockito.`when`(builder.getKeyboardInternal(Mockito.anyInt(), Mockito.anyBoolean()))
            .thenReturn(emptyKeyboard())

        switcher.setPkbSymbolsKeyboard(0, true, true, 0)

        val inOrder = Mockito.inOrder(uim)
        inOrder.verify(uim).show(false)
        inOrder.verify(uim).showEmojiBoard()
        inOrder.verify(uim).hideKeyboardOnKeyboardStateChange()
    }

    /** With the bar already up, the PKB symbol load does not raise it again. */
    @Test
    fun thePkbSymbolLoadLeavesAnAlreadyRaisedBarAlone() {
        Mockito.`when`(ime.isUimEnabled()).thenReturn(true)
        Mockito.`when`(builder.getKeyboardInternal(Mockito.anyInt(), Mockito.anyBoolean()))
            .thenReturn(emptyKeyboard())

        switcher.setPkbSymbolsKeyboard(0, true, true, 0)

        verify(uim, Mockito.never()).show(Mockito.anyBoolean())
        verify(uim).hideKeyboardOnKeyboardStateChange()
    }

    /**
     * The exemption, reached through the real switcher: with the number pad up, the layout change
     * spares every board — including the one that is not exempt.
     */
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

    /** With the UIM disabled the bar is never raised, but the board axis is still told. */
    @Test
    fun thePkbSymbolLoadWithTheUimDisabledStillRaisesTheLayoutChange() {
        Mockito.`when`(ime.isUimEnabled()).thenReturn(false)
        Mockito.`when`(builder.getKeyboardInternal(Mockito.anyInt(), Mockito.anyBoolean()))
            .thenReturn(emptyKeyboard())

        switcher.setPkbSymbolsKeyboard(0, true, true, 0)

        verify(uim, Mockito.never()).show(Mockito.anyBoolean())
        verify(uim).hideKeyboardOnKeyboardStateChange()
    }

    /**
     * The Sym page turn raises the change before the state machine runs, and it does so even when
     * there is no current keyboard to turn — the early return is after the transition.
     */
    @Test
    fun theSymbolPageTurnRaisesALayoutChangeEvenWhenItCannotProceed() {
        switcher.onSymbolShiftToggle(0, -1, false, true)

        verify(uim).hideKeyboardOnKeyboardStateChange()
    }
}

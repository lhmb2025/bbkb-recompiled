package dev.bbkb.ime.core

import android.content.Intent
import dev.bbkb.ime.core.device.state.PhysicalKeyboardStateTracker
import dev.bbkb.ime.core.keyevent.ModifierResetReason
import dev.bbkb.ime.core.keyevent.ModifierState
import dev.bbkb.ime.core.locale.SubtypeManager
import dev.bbkb.ime.core.receivers.ConnectivityAndScreenReceiver
import dev.bbkb.ime.keyboard.KeyboardSwitcher
import dev.bbkb.ime.keyboard.internal.MoreKeysProvider
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * Call sites name a [ModifierResetReason] and each keeps its reset scope: full resets stay ALL and
 * the two partial resets stay partial. Also the symbol board's Sym question and the accent bar's
 * Alt-style lookup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ModifierResetSiteTest {

    private lateinit var tracker: PhysicalKeyboardStateTracker
    private lateinit var ime: BlackBerryIME

    @Before
    fun setUp() {
        tracker = Mockito.mock(PhysicalKeyboardStateTracker::class.java)
        ime = Mockito.mock(BlackBerryIME::class.java)
        Mockito.`when`(ime.getPhysicalKeyboardStateTracker()).thenReturn(tracker)
    }

    /** The BlackBerryIME, receiver and switcher sites that clear everything. */
    @Test
    fun everyReasonThatReplacedTheFullResetIsStillAFullReset() {
        for (reason in listOf(
            ModifierResetReason.KEYBOARD_RELOADED,   // orientation change, loadKeyboard, PKB symbol page turn
            ModifierResetReason.EDITOR_SWITCHED,     // start input
            ModifierResetReason.FINISH_INPUT,        // onFinishInput
            ModifierResetReason.WINDOW_HIDDEN,       // resetUiState
            ModifierResetReason.SCREEN_OFF,          // the screen-off receiver
        )) {
            assertEquals("$reason clears everything, as resetAllMetaState did",
                ModifierResetReason.Scope.ALL, reason.scope())
        }
    }

    @Test
    fun thePartialResetsKeepTheirScopes() {
        assertEquals("the swipe-up / CYCLE_SYMBOLS Alt reset",
            ModifierResetReason.Scope.ALT_ONLY, ModifierResetReason.ALT_PAGE_LEFT.scope())
        assertEquals("the gesture commit's manual-shift reset",
            ModifierResetReason.Scope.MANUAL_SHIFT_ONLY, ModifierResetReason.MANUAL_SHIFT_SPENT.scope())
    }

    @Test
    fun screenOffResetsForTheScreenOffReason() {
        val receiver = ConnectivityAndScreenReceiver(
            ime, Mockito.mock(SubtypeManager::class.java), tracker)

        receiver.onReceive(null, Intent(Intent.ACTION_SCREEN_OFF))

        verify(tracker).resetModifiers(ModifierResetReason.SCREEN_OFF)
        verify(tracker, never()).resetAllMetaState()
        verify(ime).enableCursorMode(false)
    }

    @Test
    fun thePkbSymbolPageTurnResetsForAKeyboardReload() {
        val switcher = switcher()

        switcher.togglePkbSymbolShift()

        verify(tracker).resetModifiers(ModifierResetReason.KEYBOARD_RELOADED)
        verify(tracker, never()).resetAllMetaState()
    }

    /**
     * The symbol board's "is Sym held" question is on the per-keystroke path, so it is answered by
     * the tracker's cheap boolean and never by building a [ModifierState] snapshot.
     */
    @Test
    fun theSymHeldQuestionUsesTheCheapBooleanNotASnapshot() {
        val switcher = switcher()
        Mockito.`when`(tracker.isSymKeyHeld()).thenReturn(true, false)

        assertTrue(switcher.isSymHeld())
        assertFalse(switcher.isSymHeld())
        verify(tracker, never()).getModifierState()
    }

    private fun switcher(): KeyboardSwitcher {
        val switcher = Mockito.mock(KeyboardSwitcher::class.java, Mockito.CALLS_REAL_METHODS)
        ReflectionHelpers.setField(KeyboardSwitcher::class.java, switcher, "blackberryIme", ime)
        return switcher
    }

    @Test
    fun theAccentBarOffersAltStylesOnlyWhenTheEventCarriesAlt() {
        val switcher = Mockito.mock(KeyboardSwitcher::class.java)
        Mockito.`when`(switcher.getMoreKeysForKeyByStyle("e")).thenReturn(arrayOf("€"))
        val provider = MoreKeysProvider(switcher)

        val withAlt = provider.getMoreKeys(MoreKeysProvider.MoreKeysContext(
            "e", ModifierState.ofEventMeta(KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON),
            true, false))
        val withoutAlt = provider.getMoreKeys(MoreKeysProvider.MoreKeysContext(
            "e", ModifierState.ofEventMeta(KeyEvent.META_SHIFT_ON), true, false))
        val nullState = provider.getMoreKeys(MoreKeysProvider.MoreKeysContext("e", null, true, false))

        assertEquals(listOf("€"), withAlt)
        assertTrue("no Alt, no Alt styles", withoutAlt.isEmpty())
        assertTrue("a null state means nothing is active", nullState.isEmpty())
    }
}

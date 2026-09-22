package dev.bbkb.ime.keyboard.inputboard

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewPropertyAnimator
import dev.bbkb.ime.core.BlackBerryIME
import dev.bbkb.ime.core.ime.InputViewCoordinator
import dev.bbkb.ime.keyboard.KeyboardSwitcher
import dev.bbkb.ime.keyboard.SimplifiedKeyboardView
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.ConcurrentHashMap

/**
 * Characterisation tests for **the FCC board's suggestion-strip restore on close** — the one
 * behaviour Wave 2.5 moved between methods and left with no unit test, flagged there as the most
 * likely regression in that fix.
 *
 * When the cursor-mode board (-42) closes, the suggestion strip has to come back, but only if the
 * on-screen keyboard is up: on a physical-keyboard-only device there is no strip to restore and
 * calling for one puts a strip on screen that should not be there.
 *
 * Before Wave 2.5 that restore lived in `dispatchBoardAction`'s close arm. `dispatchBoardAction` is
 * now the OPEN path only and the restore moved to `closeBoard(int)`, so the thing to pin is that it
 * still fires on exactly one edge — the close — and only for -42. These cases are deliberately in
 * their own file: `UnifiedInputBoardManagerBoardStateTest` is a Wave 2/2.5 contract that has to keep
 * passing unchanged.
 *
 * The subject is built the same way that file builds it: without running the constructor
 * (`CALLS_REAL_METHODS`, which under the inline mock maker yields the real class), with the handful
 * of collaborators the close path touches injected. Every method under test runs real production
 * code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class UnifiedInputBoardManagerStripRestoreTest {

    /** A board with a settable "is my view up" answer, over the real controller skeleton. */
    private class FakeBoard(keyCode: Int) : AbstractBoardController<Any>(keyCode, null, Any()) {
        var viewIsUp = false

        override fun isShowing() = viewIsUp
        override fun peekBoardView(): View? = null
        override fun onShow() { viewIsUp = true }
        override fun onHide() { viewIsUp = false }
    }

    private lateinit var uim: UnifiedInputBoardManager
    private lateinit var ime: BlackBerryIME
    private lateinit var components: ConcurrentHashMap<Int, UnifiedInputBoardComponent>
    private lateinit var coordinator: UnifiedBoardCoordinator

    private lateinit var fcc: FakeBoard
    private lateinit var clipboard: FakeBoard

    @Before
    fun setUp() {
        uim = Mockito.mock(UnifiedInputBoardManager::class.java, Mockito.CALLS_REAL_METHODS)

        ime = Mockito.mock(BlackBerryIME::class.java)
        Mockito.`when`(ime.getUiCoordinator())
            .thenReturn(Mockito.mock(InputViewCoordinator::class.java))
        // getAuxBarManager() is left unstubbed (null), which selects the legacy view fallback.

        val keyboardView = Mockito.mock(SimplifiedKeyboardView::class.java)
        Mockito.`when`(keyboardView.visibility).thenReturn(View.VISIBLE)
        Mockito.`when`(keyboardView.animate())
            .thenReturn(Mockito.mock(ViewPropertyAnimator::class.java))

        components = ConcurrentHashMap()
        coordinator = UnifiedBoardCoordinator(uim)

        ReflectionHelpers.setField(UnifiedInputBoardManager::class.java, uim, "componentMap", components)
        ReflectionHelpers.setField(UnifiedInputBoardManager::class.java, uim, "boardCoordinator", coordinator)
        ReflectionHelpers.setField(UnifiedInputBoardManager::class.java, uim, "componentsRegistered", true)
        ReflectionHelpers.setField(UnifiedInputBoardManager::class.java, uim, "imeService", ime)
        ReflectionHelpers.setField(UnifiedInputBoardManager::class.java, uim, "keyboardView", keyboardView)
        ReflectionHelpers.setField(
            UnifiedInputBoardManager::class.java, uim, "invalidationHandler", Handler(Looper.getMainLooper()),
        )
        ReflectionHelpers.setField(
            UnifiedInputBoardManager::class.java, uim, "uimHandler",
            Mockito.mock(UnifiedInputBoardHandler::class.java),
        )
        ReflectionHelpers.setField(
            UnifiedInputBoardManager::class.java, uim, "keyboardSwwitcher", KeyboardSwitcher.getInstance(),
        )
        ReflectionHelpers.setField(UnifiedInputBoardManager::class.java, uim, "animationSafetyReset", Runnable { })

        fcc = FakeBoard(FCC).also { components[FCC] = it }
        clipboard = FakeBoard(CLIPBOARD).also { components[CLIPBOARD] = it }
    }

    private fun onScreenKeyboardIsUp(up: Boolean) =
        Mockito.`when`(ime.isOnScreenKeyboardVisible()).thenReturn(up)

    private fun verifyStripRestored(times: Int) =
        verify(ime, Mockito.times(times)).restoreSuggestionStrip(true, true)

    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun closingTheCursorBoardRestoresTheSuggestionStrip() {
        onScreenKeyboardIsUp(true)
        uim.requestBoard(FCC)

        uim.requestBoard(FCC) // the second press closes

        verifyStripRestored(1)
    }

    @Test
    fun theRestoreIsSkippedWhenThereIsNoOnScreenKeyboardToRestoreItTo() {
        // A physical-keyboard-only device: asking for the strip would put one on screen.
        onScreenKeyboardIsUp(false)
        uim.requestBoard(FCC)

        uim.requestBoard(FCC)

        verify(ime, never()).restoreSuggestionStrip(
            Mockito.anyBoolean(), Mockito.anyBoolean(),
        )
    }

    @Test
    fun openingTheCursorBoardDoesNotRestoreTheStrip() {
        // The restore is a close-edge effect. Wave 2.5 made dispatchBoardAction the OPEN path only;
        // if the restore ever came back to it, the strip would flash on every open.
        onScreenKeyboardIsUp(true)

        uim.requestBoard(FCC)

        verify(ime, never()).restoreSuggestionStrip(
            Mockito.anyBoolean(), Mockito.anyBoolean(),
        )
    }

    @Test
    fun theRestoreFiresOnceForOneCloseNotOncePerPress() {
        onScreenKeyboardIsUp(true)

        uim.requestBoard(FCC)  // open
        uim.requestBoard(FCC)  // close  -> restore
        uim.requestBoard(FCC)  // open
        uim.requestBoard(FCC)  // close  -> restore

        verifyStripRestored(2)
    }

    @Test
    fun closingAnotherBoardDoesNotRestoreTheStrip() {
        // The restore belongs to -42 alone: no other board takes the suggestion strip away.
        onScreenKeyboardIsUp(true)
        uim.requestBoard(CLIPBOARD)

        uim.requestBoard(CLIPBOARD)

        verify(ime, never()).restoreSuggestionStrip(
            Mockito.anyBoolean(), Mockito.anyBoolean(),
        )
    }

    @Test
    fun switchingAwayFromTheCursorBoardDoesNotRestoreTheStrip() {
        // CHARACTERISED: the exclusive-open guard closes the previous board through its own
        // hide(), not through closeBoard(), so switching from FCC to another board never restores
        // the strip — the incoming board owns the screen instead.
        onScreenKeyboardIsUp(true)
        uim.requestBoard(FCC)

        uim.requestBoard(CLIPBOARD)

        verify(ime, never()).restoreSuggestionStrip(
            Mockito.anyBoolean(), Mockito.anyBoolean(),
        )
    }

    @Test
    fun theRestoreStillFiresWhenTheCursorBoardsViewWasAlreadyClobberedDown() {
        // closeBoard is reached from the coordinator's answer, not from the view's, so a board the
        // coordinator still claims restores the strip even though its guarded hide() is a no-op.
        onScreenKeyboardIsUp(true)
        uim.requestBoard(FCC)
        fcc.viewIsUp = false

        uim.requestBoard(FCC)

        verifyStripRestored(1)
    }

    @Test
    fun aDirectCloseOfACursorBoardThatWasNeverOpenDoesNotRestoreTheStrip() {
        // The restore is gated on the board having been open (coordinator or its own view).
        onScreenKeyboardIsUp(true)

        uim.closeBoard(FCC)

        verify(ime, never()).restoreSuggestionStrip(
            Mockito.anyBoolean(), Mockito.anyBoolean(),
        )
    }

    @Test
    fun aDirectCloseOfAnOpenCursorBoardRestoresTheStripAsBefore() {
        onScreenKeyboardIsUp(true)
        uim.requestBoard(FCC)

        uim.closeBoard(FCC)

        verifyStripRestored(1)
    }

    @Test
    fun aDirectCloseOfACursorBoardShownBehindTheCoordinatorsBackRestoresTheStrip() {
        // The view is up but nothing reported the open: the board's own view counts as open.
        onScreenKeyboardIsUp(true)
        fcc.viewIsUp = true

        uim.closeBoard(FCC)

        verifyStripRestored(1)
    }

    private companion object {
        const val CLIPBOARD = -25
        const val FCC = -42
    }
}

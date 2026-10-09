package dev.bbkb.ime.keyboard.inputboard

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewPropertyAnimator
import dev.bbkb.ime.core.BlackBerryIME
import dev.bbkb.ime.core.ime.InputViewCoordinator
import dev.bbkb.ime.core.settings.util.SettingsValues
import dev.bbkb.ime.keyboard.KeyboardSwitcher
import dev.bbkb.ime.keyboard.SimplifiedKeyboardView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.ConcurrentHashMap

/**
 * The menu manager's half of the "Show or hide the input menu" key action:
 * [UnifiedInputBoardManager.showMenuForKey] and the end of the session override in
 * [UnifiedInputBoardManager.hide]. The toggle decision itself is `InputViewCoordinator`'s and is
 * covered in `PkbSuggestionBarTest` and the two key-path suites.
 *
 * Built the way `UnifiedInputBoardManagerBoardStateTest` builds the manager (without its
 * constructor, the board-state collaborators injected), in a file of its own so that contract
 * stays unchanged. `show()` is stubbed to raise the bar and nothing else: raising it is AuxBar
 * mechanics, and what is under test is what happens around it. The bar reads as up or down
 * through the legacy `keyboardView` visibility, as it does there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class UnifiedInputBoardManagerInputMenuKeyTest {

    /** A board with a settable "is my view up" answer, over the real controller skeleton. */
    private class FakeBoard(keyCode: Int) : AbstractBoardController<Any>(keyCode, null, Any()) {
        var viewIsUp = false

        override fun isShowing() = viewIsUp
        override fun peekBoardView(): View? = null
        override fun onShow() { viewIsUp = true }
        override fun onHide() { viewIsUp = false }
    }

    private lateinit var uim: UnifiedInputBoardManager
    private lateinit var keyboardView: SimplifiedKeyboardView

    /** The menu bar's visibility, as the legacy view fallback reports it. */
    private var barUp = false
    private lateinit var coordinator: UnifiedBoardCoordinator
    private lateinit var components: ConcurrentHashMap<Int, UnifiedInputBoardComponent>

    private lateinit var clipboard: FakeBoard
    private lateinit var autofill: FakeBoard

    @Before
    fun setUp() {
        uim = Mockito.mock(UnifiedInputBoardManager::class.java, Mockito.CALLS_REAL_METHODS)

        val ime = Mockito.mock(BlackBerryIME::class.java)
        Mockito.`when`(ime.getUiCoordinator()).thenReturn(Mockito.mock(InputViewCoordinator::class.java))
        // getAuxBarManager() is left unstubbed (null), which selects the legacy view fallback.

        keyboardView = Mockito.mock(SimplifiedKeyboardView::class.java)
        Mockito.`when`(keyboardView.visibility).thenAnswer { if (barUp) View.VISIBLE else View.GONE }
        Mockito.doAnswer { barUp = it.arguments[0] == View.VISIBLE; null }
            .`when`(keyboardView).setVisibility(Mockito.anyInt())
        Mockito.`when`(keyboardView.animate()).thenReturn(Mockito.mock(ViewPropertyAnimator::class.java))

        components = ConcurrentHashMap()
        coordinator = UnifiedBoardCoordinator(uim)

        inject("componentMap", components)
        inject("boardCoordinator", coordinator)
        inject("componentsRegistered", true)
        inject("imeService", ime)
        inject("keyboardView", keyboardView)
        inject("invalidationHandler", Handler(Looper.getMainLooper()))
        inject("uimHandler", Mockito.mock(UnifiedInputBoardHandler::class.java))
        inject("keyboardSwwitcher", KeyboardSwitcher.getInstance())
        inject("animationSafetyReset", Runnable { })

        // Raising the bar: in production, show() through the AuxBar.
        Mockito.doAnswer { barUp = true; null }.`when`(uim).show(Mockito.anyBoolean())

        clipboard = FakeBoard(CLIPBOARD).also { components[CLIPBOARD] = it }
        autofill = FakeBoard(AUTOFILL).also { components[AUTOFILL] = it }
    }

    @After
    fun tearDown() {
        SettingsValues.setInputMenuRequestedByKey(false)
    }

    private fun inject(name: String, value: Any?) =
        ReflectionHelpers.setField(UnifiedInputBoardManager::class.java, uim, name, value)

    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun theMenuComesUpWithNothingToAdopt() {
        assertTrue(uim.showMenuForKey())

        assertTrue(uim.isShowing)
        assertEquals(UnifiedBoardCoordinator.NO_BOARD, coordinator.activeBoard())
    }

    /**
     * The clipboard was opened by its key's menu-off route (its own toggle), which the
     * coordinator never hears about. Raising the menu adopts it, so the first press of its toggle
     * closes it instead of "opening" what is already open.
     */
    @Test
    fun aBoardTheMenuOffRouteOpenedIsAdopted() {
        clipboard.viewIsUp = true

        uim.showMenuForKey()

        assertEquals(CLIPBOARD, coordinator.activeBoard())
        uim.requestBoard(CLIPBOARD)
        assertFalse("one press closes it", clipboard.viewIsUp)
        assertEquals(UnifiedBoardCoordinator.NO_BOARD, coordinator.activeBoard())
    }

    @Test
    fun theAutofillStripIsNotABoardToAdopt() {
        autofill.viewIsUp = true

        uim.showMenuForKey()

        assertEquals(UnifiedBoardCoordinator.NO_BOARD, coordinator.activeBoard())
    }

    @Test
    fun aBoardTheCoordinatorAlreadyTracksIsLeftAsItIs() {
        uim.requestBoard(CLIPBOARD)
        assertEquals(CLIPBOARD, coordinator.activeBoard())

        uim.showMenuForKey()

        assertEquals(CLIPBOARD, coordinator.activeBoard())
        assertTrue(clipboard.viewIsUp)
    }

    /** No bar to show it in: reported, so the caller drops the override, and nothing adopted. */
    @Test
    fun aBarThatDidNotComeUpIsReported() {
        Mockito.doNothing().`when`(uim).show(Mockito.anyBoolean())
        clipboard.viewIsUp = true

        assertFalse(uim.showMenuForKey())

        assertEquals(UnifiedBoardCoordinator.NO_BOARD, coordinator.activeBoard())
    }

    /**
     * Whatever takes the menu bar down ends the key's override: the window going away, the strip
     * replacing it, cursor mode, a settings change, as well as the key's own second press.
     */
    @Test
    fun takingTheMenuBarDownEndsTheKeysOverride() {
        SettingsValues.setInputMenuRequestedByKey(true)
        uim.showMenuForKey()
        uim.requestBoard(CLIPBOARD)

        uim.hide()

        assertFalse(SettingsValues.isInputMenuRequestedByKey())
        assertFalse(uim.isShowing)
        assertFalse("its boards go with it", clipboard.viewIsUp)
        assertEquals(UnifiedBoardCoordinator.NO_BOARD, coordinator.activeBoard())
    }

    private companion object {
        const val CLIPBOARD = -25
        const val AUTOFILL = -37
    }
}

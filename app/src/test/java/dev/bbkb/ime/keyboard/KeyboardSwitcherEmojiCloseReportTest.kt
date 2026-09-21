package dev.bbkb.ime.keyboard

import dev.bbkb.ime.keyboard.inputboard.UnifiedInputBoardComponent
import dev.bbkb.ime.keyboard.inputboard.UnifiedInputBoardManager
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiBoardController
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiPalettesView
import dev.bbkb.ime.keyboard.internal.KeyboardState
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * `hideEmojiKeyboard()` runs from [KeyboardSwitcher.setKeyboard], i.e. on EVERY keyboard rebuild —
 * including the rebuilds the shift chain performs while a completely different board is open. It
 * used to end in `unifiedInputBoardManager.setActiveComponent(null)`, which says "NO board is
 * open" and so wiped the coordinator's only copy of that state for whichever board WAS open.
 *
 * On the Key2 that cost the mic key its toggle: a dictated word commits text, the commit runs
 * `onInputCodeChanged` → the shift chain → `setAlphabetKeyboard` → `setKeyboard` → here, and the
 * voice panel — which this method does not touch — stayed up while the coordinator was told
 * nothing was open, so the next mic press took the OPEN path.
 *
 * This pins the call site: the report must name the emoji board, so the scoping in
 * [UnifiedInputBoardManager.reportBoardClosed] can keep the other boards' state intact.
 *
 * The switcher is instantiated without running its constructor (`CALLS_REAL_METHODS`) and given
 * only the collaborators this one method touches; `getCurrentKeyboard()` is left null so the
 * trailing `clearPkbSymbolMode()` short-circuits.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class KeyboardSwitcherEmojiCloseReportTest {

    @Test
    fun hideEmojiKeyboard_reportsTheCloseForTheEmojiBoardOnly() {
        val uim = Mockito.mock(UnifiedInputBoardManager::class.java)
        val switcher = newSwitcher(uim)

        switcher.hideEmojiKeyboard()

        Mockito.verify(uim).reportBoardClosed(EmojiBoardController.KEY_CODE)
        Mockito.verify(uim, Mockito.never())
            .setActiveComponent(Mockito.nullable(UnifiedInputBoardComponent::class.java))
    }

    /** The emoji palettes still come down and the state machine is still reset. */
    @Test
    fun hideEmojiKeyboard_stillTakesThePalettesDown() {
        val uim = Mockito.mock(UnifiedInputBoardManager::class.java)
        val palettes = Mockito.mock(EmojiPalettesView::class.java)
        val state = Mockito.mock(KeyboardState::class.java)
        val switcher = newSwitcher(uim, palettes, state)

        switcher.hideEmojiKeyboard()

        Mockito.verify(palettes).detachPagerAdapter()
        Mockito.verify(state).resetEmojiMode()
    }

    private fun newSwitcher(
        uim: UnifiedInputBoardManager,
        palettes: EmojiPalettesView? = null,
        state: KeyboardState = Mockito.mock(KeyboardState::class.java),
    ): KeyboardSwitcher {
        val switcher = Mockito.mock(KeyboardSwitcher::class.java, Mockito.CALLS_REAL_METHODS)
        inject(switcher, "mainKeyboardView", Mockito.mock(MainKeyboardView::class.java))
        inject(switcher, "emojiPalettesView", palettes)
        inject(switcher, "keyboardState", state)
        inject(switcher, "unifiedInputBoardManager", uim)
        return switcher
    }

    private fun inject(switcher: KeyboardSwitcher, name: String, value: Any?) =
        ReflectionHelpers.setField(KeyboardSwitcher::class.java, switcher, name, value)
}

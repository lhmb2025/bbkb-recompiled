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
 * `hideEmojiKeyboard()` runs on every keyboard rebuild ([KeyboardSwitcher.setKeyboard]), including
 * shift-chain rebuilds while another board is open. Pins that its close report names the emoji
 * board, so [UnifiedInputBoardManager.reportBoardClosed] keeps other boards' state (e.g. the open
 * voice panel the mic key toggles). The switcher is built without its constructor;
 * `getCurrentKeyboard()` stays null so the trailing `clearPkbSymbolMode()` short-circuits.
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

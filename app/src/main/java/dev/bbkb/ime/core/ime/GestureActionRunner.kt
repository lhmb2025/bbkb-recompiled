package dev.bbkb.ime.core.ime

import android.view.KeyEvent
import dev.bbkb.ime.core.BlackBerryIME
import dev.bbkb.ime.core.gesture.arbiter.GestureAction
import dev.bbkb.ime.core.keyevent.InputSource
import dev.bbkb.ime.core.keyevent.ModifierResetReason

/**
 * Runs an assigned gesture action for either keyboard: the capacitive keypad ([CkbGestureBridge])
 * and the on-screen keyboard ([VkbGestureListener] via [BlackBerryIME.handleVkbSwipeAction]).
 * Commit-suggestion is the keypad bridge's own (it needs the contact's suggestion snapshot).
 */
class GestureActionRunner(private val ime: BlackBerryIME) {

    /**
     * @param fromKeypad true for the capacitive keypad (its symbol pages and modifier state)
     * @param deleteWord the surface's delete-word sweep: where the animation runs differs
     * @return true when the action did something
     */
    fun run(action: GestureAction, fromKeypad: Boolean, deleteWord: () -> Boolean): Boolean = when (action) {
        GestureAction.NONE, GestureAction.COMMIT_SUGGESTION -> false
        GestureAction.DELETE_WORD -> deleteWord()
        GestureAction.ENTER_CURSOR_MODE -> {
            ime.toggleCursorMode()
            true
        }
        GestureAction.NEXT_LANGUAGE -> ime.updateSuggestionsFromSubtype(InputSource.SOFTWARE)
        GestureAction.CYCLE_SYMBOLS -> cycleSymbols(fromKeypad)
        GestureAction.DISMISS_KEYBOARD -> {
            ime.dismissKeyboard()
            true
        }
        GestureAction.UNDO -> undo()
        GestureAction.EMOJI -> {
            // The emoji key's own path: opens the board, or closes it when it is up.
            ime.getKeyboardSwitcher().onEmojiKeyPressed()
            true
        }
    }

    private fun cycleSymbols(fromKeypad: Boolean): Boolean {
        if (fromKeypad) {
            // Leaving the Alt page clears Alt state only; Shift and held keys stay.
            ime.getPhysicalKeyboardStateTracker().resetModifiers(ModifierResetReason.ALT_PAGE_LEFT)
        } else {
            ime.cjkSuggestionGridView?.setVisible(false)
        }
        return ime.getKeyboardSwitcher().onSymbolShiftToggle(
            ime.getCurrentInputType(), ime.getCurrentImeOptions(), true, fromKeypad)
    }

    /** Ctrl+Z to the app, after committing the word being typed so the app undoes whole edits. */
    private fun undo(): Boolean {
        val inputLogic = ime.getInputLogic()
        inputLogic.commitComposingOrReset(ime.getSettingsManager().getSettingsValues())
        val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        inputLogic.sendKeyDownWithMeta(KeyEvent.KEYCODE_Z, meta)
        inputLogic.sendKeyUpWithMeta(KeyEvent.KEYCODE_Z, meta)
        return true
    }
}

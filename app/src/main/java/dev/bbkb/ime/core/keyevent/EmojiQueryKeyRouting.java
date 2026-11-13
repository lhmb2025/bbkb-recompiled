package dev.bbkb.ime.core.keyevent;

import android.view.KeyEvent;

/**
 * Which hardware keys the open emoji board takes for its search query, and the text they add. Typing
 * filters the board instead of closing it; keys type as usual (Space, Alt characters). Enter, modifiers,
 * Sym, Back and board keys pass through; Backspace edits a non-empty query. Pure, for tests.
 */
public final class EmojiQueryKeyRouting {

    private EmojiQueryKeyRouting() {
    }

    /** False for non-physical, Enter, Shift, Alt, Sym, Back, function, board and modifier keys. */
    public static boolean mayFeedQuery(ResolvedKey key) {
        if (!key.isPhysical()) {
            return false;
        }
        if (key.isEnterKey() || key.isShiftKey() || key.isAltKey() || key.isSymKey()
                || key.isBackKey() || key.isFunctionKey() || key.isBoardKey()) {
            return false;
        }
        return !KeyEvent.isModifierKey(key.keyCode());
    }

    /** The text a converted key adds, or null if it types nothing visible. Space counts. */
    public static CharSequence queryText(InputEvent event) {
        if (event == null || !event.hasData() || event.isModifierKey() || event.isGestureEnd()) {
            return null;
        }
        if (event.mText != null) {
            return event.mText.length() == 0 ? null : event.mText;
        }
        int cp = event.mCodePoint;
        if (cp < 0x20 || cp == 0x7f || (cp >= 0xD800 && cp <= 0xDFFF) || cp > 0x10FFFF) {
            return null;
        }
        return new String(Character.toChars(cp));
    }
}

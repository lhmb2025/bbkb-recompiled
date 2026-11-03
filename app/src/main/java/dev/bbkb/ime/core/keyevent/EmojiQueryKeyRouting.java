package dev.bbkb.ime.core.keyevent;

import android.view.KeyEvent;

/**
 * Which hardware keys the open emoji board takes for its search query, and what text they add.
 *
 * <p>Owner decisions, 2026-11-02: typing while the emoji board is open filters the board instead
 * of closing it (type-to-dismiss is gone for this board); the keys otherwise work normally, so
 * Space is a space in the query and Alt plus a letter is its Alt character; Enter, the modifiers,
 * Sym, Back and every board key pass through untouched; Backspace edits the query while there is
 * one and passes through otherwise. The two halves below are pure so they can be pinned by tests;
 * {@code KeyEventProcessor} supplies the resolved key and the converted event and does the
 * routing.
 */
public final class EmojiQueryKeyRouting {

    private EmojiQueryKeyRouting() {
    }

    /**
     * Whether the board may take this key at all. False for Enter, Shift, Alt, Sym, Back, function
     * keys, every board or multifunction key, any framework modifier, and anything not from a
     * physical keyboard. Backspace returns true: whether it is consumed depends on the query.
     */
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

    /**
     * The text a converted key adds to the query, or null when the key types nothing visible
     * (arrows, Tab, function keys, a modifier, a gesture end). Space counts: a two-word query is
     * allowed.
     */
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

package dev.bbkb.ime.core.textinput.controller;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.KeyCharacterMap;

import dev.bbkb.ime.core.settings.util.SettingsValues;
import dev.bbkb.ime.core.textinput.connection.RichInputConnection;
import dev.bbkb.ime.core.shared.GraphemeUtils;

/** Directional cursor moves, optionally extending the selection, stepping by grapheme cluster. */
public class CursorController {
    public interface CancelComposingCallback {
        void cancelComposingAndTouchEvent();
    }

    public interface LayoutDirectionCallback {
        boolean isRightToLeft();
    }

    /** How much text one grapheme step scans, before or after the cursor. */
    private static final int GRAPHEME_SCAN_LENGTH = 48;

    private final RichInputConnection mRichInputConnection;
    private final CancelComposingCallback mCancelComposing;
    private final LayoutDirectionCallback mLayoutDirection;

    public CursorController(RichInputConnection richInputConnection, CancelComposingCallback cancelComposing, LayoutDirectionCallback layoutDirection) {
        this.mRichInputConnection = richInputConnection;
        this.mCancelComposing = cancelComposing;
        this.mLayoutDirection = layoutDirection;
    }

    /** Sends a key-down + key-up pair. The single implementation: InputLogic delegates here. */
    public static void sendKeyEventWithMeta(RichInputConnection richInputConnection, int keyCode, int metaState) {
        long uptimeMillis = SystemClock.uptimeMillis();
        richInputConnection.sendKeyEvent(new KeyEvent(uptimeMillis, uptimeMillis, KeyEvent.ACTION_DOWN,
                keyCode, 0, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, InputDevice.SOURCE_KEYBOARD));
        richInputConnection.sendKeyEvent(new KeyEvent(SystemClock.uptimeMillis(), uptimeMillis, KeyEvent.ACTION_UP,
                keyCode, 0, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, InputDevice.SOURCE_KEYBOARD));
    }

    private void sendKeyEventWithMeta(int keyCode, int metaState) {
        sendKeyEventWithMeta(this.mRichInputConnection, keyCode, metaState);
    }

    /**
     * Chars crossed stepping over {@code steps} graphemes after or before the cursor. Each step
     * scans the same {@link #GRAPHEME_SCAN_LENGTH} window a single move would, so N steps land
     * where N single moves would.
     */
    private int graphemeRunLength(boolean forward, int steps) {
        CharSequence text = forward
                ? this.mRichInputConnection.getTextAfterCursor(steps * GRAPHEME_SCAN_LENGTH, 0)
                : this.mRichInputConnection.getTextBeforeCursor(steps * GRAPHEME_SCAN_LENGTH, 0);
        if (text == null) {
            return 0;
        }
        int length = text.length();
        int offset = 0;
        for (int step = 0; step < steps && offset < length; step++) {
            if (forward) {
                CharSequence window = text.subSequence(offset, Math.min(length, offset + GRAPHEME_SCAN_LENGTH));
                offset += GraphemeUtils.getClusterLengthAfterCursor(window, Character.codePointAt(window, 0));
            } else {
                int end = length - offset;
                CharSequence window = text.subSequence(Math.max(0, end - GRAPHEME_SCAN_LENGTH), end);
                offset += GraphemeUtils.getClusterLengthBeforeCursor(window, Character.codePointBefore(window, window.length()));
            }
        }
        return Math.min(offset, length);
    }

    /**
     * @param z true to move toward the end of the text
     * @param i the number of steps
     * @param z2 true to collapse the steps into one setSelection
     */
    private void moveCursor(boolean z, int i, boolean z2) {
        int iMax;
        int iM5853q = this.mRichInputConnection.getCursorStart();
        int iM5854r = this.mRichInputConnection.getCursorEnd();
        if (z) {
            int iMax2 = Math.max(0, Math.min(i, iM5853q - iM5854r));
            int iMax3 = Math.max(0, i - iMax2);
            if (iMax3 > 1) {
                // A multi-step move (keypad cursor drag) crosses iMax3 graphemes, not chars.
                iMax3 = graphemeRunLength(true, iMax3);
            } else {
                if (iMax3 > 0) {
                    CharSequence charSequenceM5830b = this.mRichInputConnection.getTextAfterCursor(iMax3, 0);
                    iMax3 = charSequenceM5830b == null ? 0 : charSequenceM5830b.length();
                }
                int iM5663b = GraphemeUtils.getClusterLengthAfterCursor(this.mRichInputConnection.getTextAfterCursor(GRAPHEME_SCAN_LENGTH, 0), this.mRichInputConnection.getCodePointAfterCursor());
                if (iM5663b != 1) {
                    iMax3 = iM5663b;
                }
            }
            iMax = iMax2 + iM5854r + iMax3;
        } else {
            if (i > 1) {
                i = graphemeRunLength(false, i);
            } else {
                int iM5660a = GraphemeUtils.getClusterLengthBeforeCursor(this.mRichInputConnection.getTextBeforeCursor(GRAPHEME_SCAN_LENGTH, 0), this.mRichInputConnection.getCodePointBeforeCursor());
                if (iM5660a != 1) {
                    i = iM5660a;
                }
            }
            iMax = Math.max(0, iM5854r - i);
        }
        if (z2) {
            this.mRichInputConnection.setSelection(iMax, iMax);
            return;
        }
        // Stepping one position at a time is what allowBatchedCursorMove == false means; do not
        // collapse it into one setSelection (the z2 branch above is the batched form).
        if (z) {
            for (int i2 = iM5854r + 1; i2 <= iMax; i2++) {
                this.mRichInputConnection.setSelection(i2, i2);
            }
            return;
        }
        for (int i3 = iM5854r - 1; i3 >= iMax; i3--) {
            this.mRichInputConnection.setSelection(i3, i3);
        }
    }

    /** Cancels composing first; uses DPAD keys when selecting or moving beyond the field. */
    public void moveCursorRight(SettingsValues settings, int count, boolean select) {
        mCancelComposing.cancelComposingAndTouchEvent();
        if (!settings.allowHorizontalCursorBeyondField && !select) {
            moveCursor(!mLayoutDirection.isRightToLeft(), count, settings.allowBatchedCursorMove);
            return;
        }
        for (int i = 0; i < count; i++) {
            sendKeyEventWithMeta(KeyEvent.KEYCODE_DPAD_RIGHT, select ? KeyEvent.META_SHIFT_ON : 0);
        }
    }

    public void moveCursorLeft(SettingsValues settings, int count, boolean select) {
        mCancelComposing.cancelComposingAndTouchEvent();
        if (!settings.allowHorizontalCursorBeyondField && !select) {
            moveCursor(mLayoutDirection.isRightToLeft(), count, settings.allowBatchedCursorMove);
            return;
        }
        for (int i = 0; i < count; i++) {
            sendKeyEventWithMeta(KeyEvent.KEYCODE_DPAD_LEFT, select ? KeyEvent.META_SHIFT_ON : 0);
        }
    }

    public void moveCursorUp(int count, boolean select) {
        mCancelComposing.cancelComposingAndTouchEvent();
        for (int i = 0; i < count; i++) {
            sendKeyEventWithMeta(KeyEvent.KEYCODE_DPAD_UP, select ? KeyEvent.META_SHIFT_ON : 0);
        }
    }

    public void moveCursorDown(int count, boolean select) {
        mCancelComposing.cancelComposingAndTouchEvent();
        for (int i = 0; i < count; i++) {
            sendKeyEventWithMeta(KeyEvent.KEYCODE_DPAD_DOWN, select ? KeyEvent.META_SHIFT_ON : 0);
        }
    }
}

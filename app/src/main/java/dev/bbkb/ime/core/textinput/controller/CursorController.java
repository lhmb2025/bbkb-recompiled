package dev.bbkb.ime.core.textinput.controller;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.KeyCharacterMap;

import dev.bbkb.ime.core.settings.util.SettingsValues;
import dev.bbkb.ime.core.textinput.connection.RichInputConnection;
import dev.bbkb.ime.core.shared.GraphemeUtils;

/**
 * Handles cursor movement operations: directional cursor moves (left, right, up, down)
 * with optional selection extension and grapheme-cluster-aware single-character stepping.
 *
 * <p>Extracted from {@code InputLogic} in Phase 4.2 of the text editing reorganization.</p>
 */
public class CursorController {

    /**
     * Callback to cancel any active composing text or touch event before cursor movement.
     */
    public interface CancelComposingCallback {
        void cancelComposingAndTouchEvent();
    }

    /**
     * Callback to determine the current text field's layout direction.
     */
    public interface LayoutDirectionCallback {
        boolean isRightToLeft();
    }

    private final RichInputConnection mRichInputConnection;
    private final CancelComposingCallback mCancelComposing;
    private final LayoutDirectionCallback mLayoutDirection;

    /**
     * Creates a new {@code CursorController}.
     *
     * @param richInputConnection the editor communication wrapper used to send key events
     *                            and update the selection
     * @param cancelComposing     callback invoked before any cursor movement to cancel active
     *                            composing text or touch events
     * @param layoutDirection     callback used to determine RTL vs LTR when mapping
     *                            logical left/right to physical cursor direction
     */
    public CursorController(RichInputConnection richInputConnection, CancelComposingCallback cancelComposing, LayoutDirectionCallback layoutDirection) {
        this.mRichInputConnection = richInputConnection;
        this.mCancelComposing = cancelComposing;
        this.mLayoutDirection = layoutDirection;
    }

    /**
     * Synthesises a key-down + key-up pair for {@code keyCode} with the given meta state.
     *
     * <p>TI-36: {@code InputLogic.sendKeyEventWithMeta} was a byte-for-byte copy of this body,
     * magic argument tail {@code -1, 0, 6} included, and both were live - so a correction to one
     * (for instance to that {@code 6}, which is {@link InputDevice#SOURCE_KEYBOARD}) would have
     * silently missed the other. This is now the single implementation; InputLogic delegates here.
     *
     * @param richInputConnection the editor connection to send through
     * @param keyCode             the Android key code
     * @param metaState           the meta state bitmask (e.g. {@link KeyEvent#META_SHIFT_ON})
     */
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

    private void moveCursor(boolean z, int i, boolean z2) {
        int iMax;
        int iM5853q = this.mRichInputConnection.getCursorStart();
        int iM5854r = this.mRichInputConnection.getCursorEnd();
        if (z) {
            int iMax2 = Math.max(0, Math.min(i, iM5853q - iM5854r));
            int iMax3 = Math.max(0, i - iMax2);
            if (iMax3 > 0) {
                CharSequence charSequenceM5830b = this.mRichInputConnection.getTextAfterCursor(iMax3, 0);
                iMax3 = charSequenceM5830b == null ? 0 : charSequenceM5830b.length();
            }
            int iM5663b = GraphemeUtils.getGraphemeLengthAfterCursor(this.mRichInputConnection.getTextAfterCursor(48, 0), this.mRichInputConnection.getCodePointAfterCursor());
            if (iM5663b != 1) {
                iMax3 = iM5663b;
            }
            iMax = iMax2 + iM5854r + iMax3;
        } else {
            int iM5660a = GraphemeUtils.getGraphemeLengthBeforeCursor(this.mRichInputConnection.getTextBeforeCursor(48, 0), this.mRichInputConnection.getCodePointBeforeCursor());
            if (iM5660a != 1) {
                i = iM5660a;
            }
            iMax = Math.max(0, iM5854r - i);
        }
        if (z2) {
            this.mRichInputConnection.setSelection(iMax, iMax);
            return;
        }
        // TI-6: one setSelection per intermediate position, each an editor round-trip plus a
        // full text-context reload. NOT collapsed to a single setSelection(iMax, iMax): the
        // stepping IS the behaviour of the `allowBatchedCursorMove == false` setting - the z2
        // branch above is the batched form - so collapsing it would make that toggle a no-op.
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

    /**
     * Moves the cursor {@code count} positions to the right (or sends {@code KEYCODE_DPAD_RIGHT}
     * with optional shift for selection). Cancels any active composing first.
     *
     * @param settings current settings values (used for layout direction and batching)
     * @param count    number of positions to move
     * @param select   {@code true} to extend the selection while moving
     */
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

    /**
     * Moves the cursor {@code count} positions to the left (or sends {@code KEYCODE_DPAD_LEFT}
     * with optional shift for selection). Cancels any active composing first.
     *
     * @param settings current settings values (used for layout direction and batching)
     * @param count    number of positions to move
     * @param select   {@code true} to extend the selection while moving
     */
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

    /**
     * Moves the cursor {@code count} lines upward by sending {@code KEYCODE_DPAD_UP}.
     * Cancels any active composing first.
     *
     * @param count  number of lines to move
     * @param select {@code true} to extend the selection while moving
     */
    public void moveCursorUp(int count, boolean select) {
        mCancelComposing.cancelComposingAndTouchEvent();
        for (int i = 0; i < count; i++) {
            sendKeyEventWithMeta(KeyEvent.KEYCODE_DPAD_UP, select ? KeyEvent.META_SHIFT_ON : 0);
        }
    }

    /**
     * Moves the cursor {@code count} lines downward by sending {@code KEYCODE_DPAD_DOWN}.
     * Cancels any active composing first.
     *
     * @param count  number of lines to move
     * @param select {@code true} to extend the selection while moving
     */
    public void moveCursorDown(int count, boolean select) {
        mCancelComposing.cancelComposingAndTouchEvent();
        for (int i = 0; i < count; i++) {
            sendKeyEventWithMeta(KeyEvent.KEYCODE_DPAD_DOWN, select ? KeyEvent.META_SHIFT_ON : 0);
        }
    }
}

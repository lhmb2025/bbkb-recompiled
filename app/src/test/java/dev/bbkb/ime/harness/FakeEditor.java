package dev.bbkb.ime.harness;

import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.CorrectionInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputContentInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Inspectable {@link InputConnection} with {@code BaseInputConnection} semantics:
 * {@link #setComposingText} with no region inserts at the selection and makes it the region;
 * every mutation queues a {@link SelectionEvent} the harness delivers later (the binder delay),
 * with batch edits coalesced. App reactions are scriptable via {@link EditorActionBehavior}.
 */
public class FakeEditor implements InputConnection {

    /** One queued onUpdateSelection delivery. */
    public static final class SelectionEvent {
        public final int oldSelStart, oldSelEnd, newSelStart, newSelEnd, candStart, candEnd;
        SelectionEvent(int os, int oe, int ns, int ne, int cs, int ce) {
            oldSelStart = os; oldSelEnd = oe; newSelStart = ns; newSelEnd = ne;
            candStart = cs; candEnd = ce;
        }
        @Override public String toString() {
            return "sel[" + oldSelStart + "," + oldSelEnd + "]->[" + newSelStart + "," + newSelEnd
                    + "] cand[" + candStart + "," + candEnd + "]";
        }
    }

    /** Scriptable app reaction to performEditorAction. */
    public interface EditorActionBehavior {
        /** Runs after the action is recorded; mutate the editor to mimic the app. */
        void onAction(FakeEditor editor, int actionId);
    }

    private final StringBuilder mText = new StringBuilder();
    private int mSelStart = 0, mSelEnd = 0;
    private int mComposingStart = -1, mComposingEnd = -1;

    private int mBatchDepth = 0;
    private boolean mDirtyInBatch = false;
    private int mBatchOldSelStart, mBatchOldSelEnd;

    /** Selection last reported to the IME (the "old" values of the next event). */
    private int mReportedSelStart = 0, mReportedSelEnd = 0;

    private final ArrayDeque<SelectionEvent> mPendingSelectionEvents = new ArrayDeque<>();

    /** Ordered log of every InputConnection call, for order-sensitive assertions. */
    public final List<String> callLog = new ArrayList<>();
    /** Editor actions received via performEditorAction. */
    public final List<Integer> editorActions = new ArrayList<>();
    /** Raw key events received via sendKeyEvent. */
    public final List<KeyEvent> sentKeyEvents = new ArrayList<>();
    /** Text reads (sync round-trips on a device), counted apart from {@link #callLog}. */
    public int getTextBeforeCursorCalls = 0;
    public int getTextAfterCursorCalls = 0;

    private EditorActionBehavior mActionBehavior = null;
    private boolean mConnectionValid = true;

    public void setEditorActionBehavior(EditorActionBehavior behavior) { mActionBehavior = behavior; }

    /** Simulate the app revoking the connection (field died). All calls return false. */
    public void invalidate() { mConnectionValid = false; }

    /** App-side text set (launcher instant search, message-send clear). Queues an event. */
    public void appSetText(String text, int selStart, int selEnd) {
        int os = mReportedSelStart, oe = mReportedSelEnd;
        mText.setLength(0);
        mText.append(text);
        mComposingStart = -1; mComposingEnd = -1;   // app-side setText drops composing
        mSelStart = clamp(selStart); mSelEnd = clamp(selEnd);
        queueSelectionEvent(os, oe);
    }

    /** App-side caret move; unlike {@link #appSetText} keeps text and composing region. Queues an event. */
    public void appSetSelection(int selStart, int selEnd) {
        int os = mReportedSelStart, oe = mReportedSelEnd;
        mSelStart = clamp(selStart); mSelEnd = clamp(selEnd);
        queueSelectionEvent(os, oe);
    }

    public String getText() { return mText.toString(); }
    public int getSelStart() { return mSelStart; }
    public int getSelEnd() { return mSelEnd; }
    public int getComposingStart() { return mComposingStart; }
    public int getComposingEnd() { return mComposingEnd; }
    public boolean hasComposingRegion() { return mComposingStart != -1; }
    public String getComposingText() {
        return hasComposingRegion() ? mText.substring(mComposingStart, mComposingEnd) : "";
    }

    /** Drain queued selection events (delivered by the harness to InputLogic). */
    public SelectionEvent pollSelectionEvent() { return mPendingSelectionEvents.poll(); }
    public boolean hasPendingSelectionEvents() { return !mPendingSelectionEvents.isEmpty(); }
    public int pendingSelectionEventCount() { return mPendingSelectionEvents.size(); }
    /** Drop queued events without delivering (a swallowed/never-delivered update). */
    public void dropPendingSelectionEvents() { mPendingSelectionEvents.clear(); }

    private int clamp(int offset) { return Math.max(0, Math.min(offset, mText.length())); }

    private void beginMutation() {
        if (mBatchDepth > 0) {
            if (!mDirtyInBatch) {
                mDirtyInBatch = true;
                mBatchOldSelStart = mReportedSelStart;
                mBatchOldSelEnd = mReportedSelEnd;
            }
        }
    }

    private void endMutation() {
        if (mBatchDepth == 0) {
            queueSelectionEvent(mReportedSelStart, mReportedSelEnd);
        }
    }

    private void queueSelectionEvent(int oldStart, int oldEnd) {
        mPendingSelectionEvents.add(new SelectionEvent(
                oldStart, oldEnd, mSelStart, mSelEnd, mComposingStart, mComposingEnd));
        mReportedSelStart = mSelStart;
        mReportedSelEnd = mSelEnd;
    }

    /** Replace [start,end) with text; returns end position of inserted text. */
    private int replace(int start, int end, CharSequence text) {
        mText.replace(start, end, text.toString());
        return start + text.length();
    }

    @Override
    public boolean commitText(CharSequence text, int newCursorPosition) {
        if (!mConnectionValid) return false;
        callLog.add("commitText(\"" + text + "\"," + newCursorPosition + ")");
        beginMutation();
        int start, end;
        if (hasComposingRegion()) {
            start = mComposingStart; end = mComposingEnd;
        } else {
            start = Math.min(mSelStart, mSelEnd); end = Math.max(mSelStart, mSelEnd);
        }
        int insEnd = replace(start, end, text);
        mComposingStart = -1; mComposingEnd = -1;
        // newCursorPosition: >0 => relative to end of inserted text (1 = right after)
        int cursor = newCursorPosition > 0
                ? insEnd + newCursorPosition - 1
                : start + newCursorPosition;
        mSelStart = mSelEnd = clamp(cursor);
        endMutation();
        return true;
    }

    @Override
    public boolean setComposingText(CharSequence text, int newCursorPosition) {
        if (!mConnectionValid) return false;
        callLog.add("setComposingText(\"" + text + "\"," + newCursorPosition + ")");
        beginMutation();
        int start, end;
        if (hasComposingRegion()) {
            start = mComposingStart; end = mComposingEnd;
        } else {
            // With no composing region the text goes in at the selection and becomes the region.
            start = Math.min(mSelStart, mSelEnd); end = Math.max(mSelStart, mSelEnd);
        }
        int insEnd = replace(start, end, text);
        if (text.length() == 0) {
            mComposingStart = -1; mComposingEnd = -1;
        } else {
            mComposingStart = start; mComposingEnd = insEnd;
        }
        int cursor = newCursorPosition > 0
                ? insEnd + newCursorPosition - 1
                : start + newCursorPosition;
        mSelStart = mSelEnd = clamp(cursor);
        endMutation();
        return true;
    }

    @Override
    public boolean finishComposingText() {
        if (!mConnectionValid) return false;
        callLog.add("finishComposingText()");
        // Mirrors TextView: a pure region drop still reports an update (candidates end changes).
        beginMutation();
        mComposingStart = -1; mComposingEnd = -1;
        endMutation();
        return true;
    }

    @Override
    public boolean setComposingRegion(int start, int end, android.view.inputmethod.TextAttribute textAttribute) {
        return setComposingRegion(start, end);
    }

    @Override
    public boolean setComposingRegion(int start, int end) {
        if (!mConnectionValid) return false;
        callLog.add("setComposingRegion(" + start + "," + end + ")");
        beginMutation();
        int s = clamp(Math.min(start, end)), e = clamp(Math.max(start, end));
        if (s == e) { mComposingStart = -1; mComposingEnd = -1; }
        else { mComposingStart = s; mComposingEnd = e; }
        endMutation();
        return true;
    }

    @Override
    public boolean deleteSurroundingText(int beforeLength, int afterLength) {
        if (!mConnectionValid) return false;
        callLog.add("deleteSurroundingText(" + beforeLength + "," + afterLength + ")");
        beginMutation();
        int selLo = Math.min(mSelStart, mSelEnd), selHi = Math.max(mSelStart, mSelEnd);
        // Like BaseInputConnection, never touch the composing text: widen the kept span to the
        // whole region and delete around it, so a live region loses the text before the word.
        int keepLo = selLo, keepHi = selHi;
        if (hasComposingRegion()) {
            keepLo = Math.min(keepLo, mComposingStart);
            keepHi = Math.max(keepHi, mComposingEnd);
        }
        int delBeforeStart = Math.max(0, keepLo - beforeLength);
        int delAfterEnd = Math.min(mText.length(), keepHi + afterLength);
        // delete after first so before-indices stay valid
        mText.delete(keepHi, delAfterEnd);
        mText.delete(delBeforeStart, keepLo);
        int removedBefore = keepLo - delBeforeStart;
        mSelStart -= removedBefore; mSelEnd -= removedBefore;
        // adjust composing region if present
        if (hasComposingRegion()) {
            mComposingStart = adjustIndexAfterDelete(mComposingStart, delBeforeStart, removedBefore, keepHi, delAfterEnd);
            mComposingEnd = adjustIndexAfterDelete(mComposingEnd, delBeforeStart, removedBefore, keepHi, delAfterEnd);
            if (mComposingStart >= mComposingEnd) { mComposingStart = -1; mComposingEnd = -1; }
        }
        endMutation();
        return true;
    }

    private int adjustIndexAfterDelete(int idx, int delBeforeStart, int removedBefore, int selHi, int delAfterEnd) {
        int i = idx;
        if (i > selHi) i = Math.max(selHi, i - (delAfterEnd - selHi));
        if (i > delBeforeStart) i = Math.max(delBeforeStart, i - removedBefore);
        return clamp(i);
    }

    @Override
    public boolean setSelection(int start, int end) {
        if (!mConnectionValid) return false;
        callLog.add("setSelection(" + start + "," + end + ")");
        beginMutation();
        mSelStart = clamp(start); mSelEnd = clamp(end);
        endMutation();
        return true;
    }

    @Override
    public CharSequence getTextBeforeCursor(int n, int flags) {
        getTextBeforeCursorCalls++;
        if (!mConnectionValid) return null;
        int lo = Math.min(mSelStart, mSelEnd);
        return mText.substring(Math.max(0, lo - n), lo);
    }

    @Override
    public CharSequence getTextAfterCursor(int n, int flags) {
        getTextAfterCursorCalls++;
        if (!mConnectionValid) return null;
        int hi = Math.max(mSelStart, mSelEnd);
        return mText.substring(hi, Math.min(mText.length(), hi + n));
    }

    @Override
    public CharSequence getSelectedText(int flags) {
        if (!mConnectionValid) return null;
        if (mSelStart == mSelEnd) return null;
        return mText.substring(Math.min(mSelStart, mSelEnd), Math.max(mSelStart, mSelEnd));
    }

    @Override
    public boolean performEditorAction(int actionId) {
        if (!mConnectionValid) return false;
        callLog.add("performEditorAction(" + actionId + ")");
        editorActions.add(actionId);
        if (mActionBehavior != null) mActionBehavior.onAction(this, actionId);
        return true;
    }

    @Override
    public boolean sendKeyEvent(KeyEvent event) {
        if (!mConnectionValid) return false;
        callLog.add("sendKeyEvent(" + event.getKeyCode() + "," +
                (event.getAction() == KeyEvent.ACTION_DOWN ? "down" : "up") + ")");
        sentKeyEvents.add(event);
        return true;
    }

    @Override
    public boolean beginBatchEdit() {
        if (!mConnectionValid) return false;
        mBatchDepth++;
        return true;
    }

    @Override
    public boolean endBatchEdit() {
        if (mBatchDepth > 0 && --mBatchDepth == 0 && mDirtyInBatch) {
            mDirtyInBatch = false;
            queueSelectionEvent(mBatchOldSelStart, mBatchOldSelEnd);
        }
        return mBatchDepth > 0;
    }

    @Override public boolean commitCompletion(CompletionInfo text) { callLog.add("commitCompletion"); return true; }
    @Override public boolean commitCorrection(CorrectionInfo correctionInfo) { callLog.add("commitCorrection"); return true; }
    @Override public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
        return deleteSurroundingText(beforeLength, afterLength);   // ASCII-only scenarios
    }
    @Override public int getCursorCapsMode(int reqModes) { return 0; }
    @Override public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) { return null; }
    @Override public boolean performContextMenuAction(int id) { callLog.add("contextMenu(" + id + ")"); return true; }
    @Override public boolean performPrivateCommand(String action, Bundle data) { return true; }
    @Override public boolean reportFullscreenMode(boolean enabled) { return true; }
    @Override public boolean requestCursorUpdates(int cursorUpdateMode) { callLog.add("requestCursorUpdates(" + cursorUpdateMode + ")"); return true; }
    @Override public boolean clearMetaKeyStates(int states) { return true; }
    @Override public void closeConnection() { callLog.add("closeConnection"); }
    @Override public boolean commitContent(InputContentInfo inputContentInfo, int flags, Bundle opts) { return false; }
    @Override public Handler getHandler() { return null; }
}

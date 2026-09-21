package dev.bbkb.ime.core.textinput.connection;

import static dev.bbkb.ime.core.shared.StringHelper.*;

import android.inputmethodservice.InputMethodService;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.util.Log;
import android.view.KeyEvent;

import dev.bbkb.ime.BuildConfig;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.CorrectionInfo;
import android.view.inputmethod.InputConnection;

import dev.bbkb.ime.core.engine.NuanceSDKManager;
import dev.bbkb.ime.core.suggestion.PrevWordsInfo;
import dev.bbkb.ime.core.shared.StringHelper;
import dev.bbkb.ime.personaldictionary.tokenizer.BlackBerryTokenizer;
import dev.bbkb.ime.core.settings.util.SpacingAndPunctuation;
import dev.bbkb.ime.core.textinput.CapsModeUtils;
import dev.bbkb.ime.core.shared.EmojiTextAnalyzer;
import dev.bbkb.ime.core.shared.ScriptUtils;
import dev.bbkb.ime.core.shared.SpannableStringUtils;
import com.blackberry.nuanceshim.NuanceSDK;

import java.util.ArrayList;
import java.util.List;



public class RichInputConnection {

    private static final String TAG = "RichInputConnection";

    private final InputMethodService mImeService;

    private int mCursorStart = -1;

    private int mCursorEnd = -1;

    private final StringBuilder mTextBeforeCursor = new StringBuilder();

    private final StringBuilder mTextAfterCursor = new StringBuilder();

    private final StringBuilder mComposingText = new StringBuilder();

    private int mComposingRegionLength = 0;

    private ArrayList<TextChangeListener> mTextChangeListeners = new ArrayList<>();

    private SpannableStringBuilder mTempSpannable = new SpannableStringBuilder();

    private boolean mHasBackgroundSpan = false;

    private CharSequence mCachedCapsText = null;

    private int mCachedCapsFlags = 0;

    private boolean mCachedCapsLocaleFlag = false;

    private int mCachedCapsResult = 0;

    private boolean mMonitorCursorUpdates = false;

    private InputConnection mInputConnection = null;

    private int mBatchedEditNestLevel = 0;

    
    public interface TextChangeListener {
        void onCursorPositionChanged(int position);

        void onTextDeleted(int position, int length);

        void onTextChanged(int start, int length, CharSequence text, TextChangeType type);

        void onComposingTextUpdated(CharSequence text);
    }

    
    public enum TextChangeType {
        COMPOSING,
        COMMIT,
        FINISH
    }

    /**
     * Constructs a RichInputConnection wrapping the given IME service. The connection starts
     * disconnected (cursor at -1) until {@link #resetConnection} or {@link #beginBatchEdit}
     * establishes an {@link android.view.inputmethod.InputConnection}.
     *
     * @param inputMethodService the host {@link android.inputmethodservice.InputMethodService}
     */
    public RichInputConnection(InputMethodService inputMethodService) {
        this.mImeService = inputMethodService;
    }

    /**
     * Registers a {@link TextChangeListener} to receive callbacks whenever text is committed,
     * deleted, composing text changes, or the cursor position changes. Duplicate registrations
     * are silently ignored.
     *
     * @param textChangeListenerVar the listener to add; no-op if {@code null} or already added
     */
    public void addTextChangeListener(TextChangeListener textChangeListenerVar) {
        if (textChangeListenerVar == null || this.mTextChangeListeners.indexOf(textChangeListenerVar) != -1) {
            return;
        }
        this.mTextChangeListeners.add(textChangeListenerVar);
    }

    private void notifyComposingTextUpdated(CharSequence charSequence) {
        for (int n = 0, size = this.mTextChangeListeners.size(); n < size; n++) {
            this.mTextChangeListeners.get(n).onComposingTextUpdated(charSequence);
        }
    }

    private void notifyTextChanged(int i, int i2, CharSequence charSequence, TextChangeType textChangeTypeVar) {
        for (int n = 0, size = this.mTextChangeListeners.size(); n < size; n++) {
            this.mTextChangeListeners.get(n).onTextChanged(i, i2, charSequence, textChangeTypeVar);
        }
    }

    private void notifyTextDeleted(int i, int i2) {
        for (int n = 0, size = this.mTextChangeListeners.size(); n < size; n++) {
            this.mTextChangeListeners.get(n).onTextDeleted(i, i2);
        }
    }

    private void notifyCursorPositionChanged(int i) {
        for (int n = 0, size = this.mTextChangeListeners.size(); n < size; n++) {
            this.mTextChangeListeners.get(n).onCursorPositionChanged(i);
        }
    }

    /**
     * Begins a batch edit on the current input connection, refreshing the connection reference
     * from the IME service on the first nesting level. Batch edits must be balanced with
     * {@link #endBatchEdit()} calls. Nesting beyond level 1 logs an error but does not throw.
     */
    public void beginBatchEdit() {
        int i = this.mBatchedEditNestLevel + 1;
        this.mBatchedEditNestLevel = i;
        if (i == 1) {
            this.mInputConnection = this.mImeService.getCurrentInputConnection();
            InputConnection inputConnection = this.mInputConnection;
            if (inputConnection != null) {
                inputConnection.beginBatchEdit();
                return;
            }
            return;
        }
        if (BuildConfig.DEBUG) Log.e(TAG, "Nest level too deep : " + this.mBatchedEditNestLevel);
    }

    /**
     * Ends the current batch edit. If the nesting level returns to zero, the batch edit is
     * flushed to the underlying {@link android.view.inputmethod.InputConnection}. Calling this
     * without a matching {@link #beginBatchEdit()} logs an error.
     */
    public void endBatchEdit() {
        InputConnection inputConnection;
        if (this.mBatchedEditNestLevel <= 0) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Batch edit not in progress!");
        }
        int i = this.mBatchedEditNestLevel - 1;
        this.mBatchedEditNestLevel = i;
        if (i != 0 || (inputConnection = this.mInputConnection) == null) {
            return;
        }
        inputConnection.endBatchEdit();
    }

    /**
     * Resets the connection state to the given cursor position, reloads the text context
     * from the editor, and optionally finishes any active composing region. Called when
     * {@code onUpdateSelection} fires or when the IME reconnects to the editor.
     *
     * @param i  the new cursor start position
     * @param i2 the new cursor end position
     * @param z  if {@code true}, also calls {@link #finishComposingText()} on the connection
     * @return {@code true} if the text context was loaded successfully; {@code false} if the
     *         editor is unavailable
     */
    public boolean resetConnection(int i, int i2, boolean z) {
        if (BuildConfig.DEBUG) Log.d("TEXT_EDIT_DEBUG", "RIC.resetConnection: newPos=[" + i + "," + i2 + "] finishComposing=" + z + " composingText='" + mComposingText + "' composingLen=" + mComposingRegionLength);
        if (this.mComposingRegionLength > 0) {
            notifyTextChanged(this.mCursorStart - this.mComposingText.length(), this.mComposingRegionLength, this.mComposingText, TextChangeType.FINISH);
        }
        this.mCursorStart = i;
        this.mCursorEnd = i2;
        this.mComposingText.setLength(0);
        this.mComposingRegionLength = 0;
        if (!loadTextBeforeCursor()) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Will try to retrieve text later.");
            return false;
        }
        InputConnection inputConnection = this.mInputConnection;
        if (inputConnection != null && z) {
            inputConnection.finishComposingText();
        }
        if (i == i2) {
            recalibrateCursorPosition();
        }
        if (this.mInputConnection == null) {
            return true;
        }
        notifyCursorPositionChanged(this.mCursorStart);
        return true;
    }

    private boolean loadTextBeforeCursor() {
        this.mTextBeforeCursor.setLength(0);
        this.mInputConnection = this.mImeService.getCurrentInputConnection();
        InputConnection inputConnection = this.mInputConnection;
        CharSequence textBeforeCursor = inputConnection == null ? null : inputConnection.getTextBeforeCursor(NuanceSDK.MAX_CONTEXT_LENGTH, 1);
        if (textBeforeCursor == null) {
            this.mCursorStart = -1;
            this.mCursorEnd = -1;
            if (BuildConfig.DEBUG) Log.d(TAG, "Unable to connect to the editor to retrieve text before cursor.");
            return false;
        }
        this.mTextBeforeCursor.append(textBeforeCursor);
        return loadTextAfterCursor();
    }

    /**
     * Clears the cached input connection, cursor position, and text buffers. Called when the
     * IME loses its connection to the editor (e.g., on {@code onFinishInput}). Subsequent
     * calls to text-reading methods will return {@code null} until the connection is restored.
     */
    public void invalidateConnection() {
        this.mInputConnection = null;
        this.mCachedCapsText = null;
        this.mTextAfterCursor.setLength(0);
        this.mTextBeforeCursor.setLength(0);
        this.mCursorStart = -1;
        this.mCursorEnd = -1;
        this.mCachedCapsFlags = 0;
        this.mCachedCapsLocaleFlag = false;
        this.mCachedCapsResult = 0;
    }

    private boolean loadTextAfterCursor() {
        this.mTextAfterCursor.setLength(0);
        InputConnection inputConnection = this.mInputConnection;
        CharSequence textAfterCursor = inputConnection == null ? null : inputConnection.getTextAfterCursor(NuanceSDK.MAX_CONTEXT_LENGTH, 1);
        if (textAfterCursor == null) {
            this.mCursorStart = -1;
            this.mCursorEnd = -1;
            if (BuildConfig.DEBUG) Log.d(TAG, "Unable to connect to the editor to retrieve text after cursor.");
            return false;
        }
        this.mTextAfterCursor.append(textAfterCursor);
        return true;
    }

    /**
     * Returns {@code true} if the internal composing text buffer length matches the length
     * of the composing region tracked by the editor. A mismatch indicates the editor has
     * modified the composing text from underneath the IME.
     *
     * @return {@code true} if composing buffer and region length are in sync
     */
    public boolean isComposingRegionSynced() {
        return this.mComposingText.length() == this.mComposingRegionLength;
    }

    /**
     * Commits the current composing text as final (non-composing) text. Updates internal
     * buffers and notifies listeners, then calls
     * {@link android.view.inputmethod.InputConnection#finishComposingText()} on the editor.
     */
    public void finishComposingText() {
        if (BuildConfig.DEBUG) Log.d("TEXT_EDIT_DEBUG", "RIC.finishComposingText: composingText='" + mComposingText + "' composingLen=" + mComposingRegionLength + " cursorStart=" + mCursorStart);
        int length;
        if (!isComposingRegionSynced()) {
            loadTextAfterCursor();
        }
        int i = this.mComposingRegionLength;
        if (i > 0 && (length = i - this.mComposingText.length()) >= 0 && this.mTextAfterCursor.length() >= length) {
            notifyTextChanged(this.mCursorStart - this.mComposingText.length(), this.mComposingRegionLength, ((Object) this.mComposingText) + this.mTextAfterCursor.substring(0, length), TextChangeType.FINISH);
        }
        this.mTextBeforeCursor.append((CharSequence) this.mComposingText);
        this.mComposingText.setLength(0);
        this.mComposingRegionLength = 0;
        this.mHasBackgroundSpan = false;
        InputConnection inputConnection = this.mInputConnection;
        if (inputConnection != null) {
            inputConnection.finishComposingText();
        }
    }

    /**
     * Commits the given text to the editor, replacing any active composing region.
     * Delegates to {@link #commitTextWithHighlight} with no highlight color.
     *
     * @param charSequence the text to commit
     * @param i            cursor position after commit (typically 1 = after text)
     */
    public void commitText(CharSequence charSequence, int i) {
        commitTextWithHighlight(charSequence, i, 0, charSequence.length());
    }

    /**
     * Commits the given text to the editor, optionally applying a background highlight color
     * to the committed span (used for auto-correction and add-to-dictionary indicators).
     *
     * @param charSequence the text to commit
     * @param i            cursor position after commit (typically 1 = after text)
     * @param i2           highlight background color, or 0 for no highlight
     * @param i3           end index of the highlight span within {@code charSequence}
     */
    public void commitTextWithHighlight(CharSequence charSequence, int i, int i2, int i3) {
        if (this.mInputConnection != null) {
            notifyTextChanged(this.mCursorStart - this.mComposingText.length(), this.mComposingRegionLength, charSequence, TextChangeType.COMMIT);
        }
        this.mTextBeforeCursor.append(charSequence);
        this.mCursorStart += charSequence.length() - this.mComposingText.length();
        // Audit SS-3: the cache advanced as if the cursor always lands immediately after the
        // committed text, ignoring newCursorPosition. The manual-pick path commits with i==2
        // (skip the separator already sitting after the cursor, see CommitController's
        // mShouldAppendSpace), so the cache trailed the editor by one until onUpdateSelection
        // healed it — and anything reading the cursor in between (word ranges, recorrection)
        // saw the wrong position. InputConnection semantics: i>0 places the cursor i-1
        // characters past the end of the inserted text.
        if (i > 1) {
            this.mCursorStart += i - 1;
        }
        this.mCursorEnd = this.mCursorStart;
        this.mComposingText.setLength(0);
        this.mComposingRegionLength = 0;
        this.mHasBackgroundSpan = false;
        InputConnection inputConnection = this.mInputConnection;
        if (inputConnection != null) {
            if (i2 == 0) {
                inputConnection.commitText(charSequence, i);
                return;
            }
            this.mTempSpannable.clear();
            this.mTempSpannable.append(charSequence);
            this.mTempSpannable.setSpan(new BackgroundColorSpan(i2), 0, Math.min(i3, charSequence.length()), 289);
            this.mInputConnection.commitText(this.mTempSpannable, i);
            this.mHasBackgroundSpan = true;
        }
    }

    /**
     * Removes any lingering background highlight spans by calling
     * {@link #finishComposingText()} when a background span is present. Should only be called
     * when there is no active composing text.
     */
    public void clearBackgroundSpans() {
        if (this.mHasBackgroundSpan) {
            if (this.mComposingText.length() > 0) {
                if (BuildConfig.DEBUG) Log.e(TAG, "clearSpansWithComposingFlags should be called when composing text is empty.");
            } else {
                finishComposingText();
                // The highlight was committed with Spanned.SPAN_COMPOSING (flag 289), so the
                // editor treated the word as still being edited and its spell checker skipped
                // it; the parser never returns to a word once typing has moved past it, so the
                // red underline never came (KEY2 report, 2026-09-20). Now that the span is
                // finished, ask for the check the editor missed.
                requestSpellCheck();
            }
        }
    }

    /**
     * Asks the editor to spell-check its content (API 31+; a no-op below). Used after text the
     * IME had marked as composing becomes ordinary text, which the editor's own spell-check
     * parser does not revisit on its own.
     */
    public void requestSpellCheck() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
            return;
        }
        InputConnection inputConnection = this.mInputConnection;
        if (inputConnection != null) {
            inputConnection.performSpellCheck();
        }
    }

    /**
     * Returns the text currently selected in the editor, or {@code null} if no connection
     * is available.
     *
     * @param i flags passed to {@link android.view.inputmethod.InputConnection#getSelectedText}
     * @return the selected text, or {@code null}
     */
    public CharSequence getSelectedText(int i) {
        InputConnection inputConnection = this.mInputConnection;
        if (inputConnection == null) {
            return null;
        }
        return inputConnection.getSelectedText(i);
    }

    /**
     * Returns {@code true} if the cursor position is known and greater than zero.
     * A position of -1 means the connection is not established.
     *
     * @return {@code true} if cursor start is positive
     */
    public boolean hasCursorPosition() {
        return this.mCursorStart > 0;
    }

    /**
     * Returns the auto-capitalization mode for the current cursor position based on the text
     * context before the cursor. Results are cached per (flags, locale, text) triple.
     *
     * @param i      input type flags from {@link android.view.inputmethod.EditorInfo#inputType}
     * @param c0806f spacing and punctuation rules for the current language
     * @param z      {@code true} to restrict to non-CJK caps modes
     * @return a bitmask of caps mode flags (see {@link android.text.method.TextKeyListener}),
     *         or 0 if caps are not applicable
     */
    public int getCapsMode(int i, SpacingAndPunctuation c0806f, boolean z) {
        CharSequence charSequence;
        this.mInputConnection = this.mImeService.getCurrentInputConnection();
        if (this.mInputConnection == null) {
            return 0;
        }
        if (!TextUtils.isEmpty(this.mComposingText)) {
            return z ? i & 12288 : i & 4096;
        }
        if (TextUtils.isEmpty(this.mTextBeforeCursor) && this.mCursorStart != 0 && !loadTextBeforeCursor()) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Unable to connect to the editor. Setting caps mode without knowing text.");
        }
        try {
            CharSequence charSequenceSubSequence = this.mTextBeforeCursor.subSequence(0, this.mTextBeforeCursor.length());
            if (this.mCachedCapsFlags == i && this.mCachedCapsLocaleFlag == z && (charSequence = this.mCachedCapsText) != null && charSequence.equals(charSequenceSubSequence)) {
                return this.mCachedCapsResult;
            }
            this.mCachedCapsFlags = i;
            this.mCachedCapsLocaleFlag = z;
            this.mCachedCapsText = charSequenceSubSequence;
            this.mCachedCapsResult = CapsModeUtils.getCapsMode(charSequenceSubSequence, i, c0806f, z);
            return this.mCachedCapsResult;
        } catch (IndexOutOfBoundsException unused) {
            return 0;
        }
    }

    /**
     * Returns the Unicode code point immediately before the cursor, using the cached text buffer.
     *
     * @return the code point before the cursor, or -1 if nothing precedes it
     */
    public int getCodePointBeforeCursor() {
        int length = this.mTextBeforeCursor.length();
        if (length < 1) {
            return -1;
        }
        return Character.codePointBefore(this.mTextBeforeCursor, length);
    }

    /**
     * Returns the Unicode code point immediately after the cursor by fetching one character
     * from the editor's after-cursor text.
     *
     * @return the code point after the cursor, or -1 if nothing follows it
     */
    public int getCodePointAfterCursor() {
        CharSequence charSequenceM5830b = getTextAfterCursor(2, 0);
        if (charSequenceM5830b != null && charSequenceM5830b.length() >= 1) {
            return Character.codePointAt(charSequenceM5830b, 0);
        }
        return -1;
    }

    /**
     * Returns up to {@code i} characters of text before the cursor. Uses the cached buffer
     * when available and falls back to the live editor connection if the cache is insufficient.
     *
     * @param i  maximum number of characters to return
     * @param i2 flags (e.g., {@link android.view.inputmethod.InputConnection#GET_TEXT_WITH_STYLES})
     * @return text before cursor, or {@code null} if disconnected
     */
    public CharSequence getTextBeforeCursor(int i, int i2) {
        final int beforeLen = this.mTextBeforeCursor.length();
        final int composingLen = this.mComposingText.length();
        int length = beforeLen + composingLen;
        int i3 = this.mCursorStart;
        if (-1 != i3 && (length >= i || length >= i3)) {
            // TI-22: only the last `i` characters are ever returned, so build just those instead of
            // copying both buffers in full (plus a String copy of the composing text) and then
            // deleting the front. Most callers on the keystroke path ask for 1, 2 or 48 chars.
            final int skip = Math.max(0, length - i);
            StringBuilder sb = new StringBuilder(length - skip);
            if (skip < beforeLen) {
                sb.append(this.mTextBeforeCursor, skip, beforeLen);
                sb.append(this.mComposingText);
            } else {
                sb.append(this.mComposingText, skip - beforeLen, composingLen);
            }
            return sb;
        }
        this.mInputConnection = this.mImeService.getCurrentInputConnection();
        InputConnection inputConnection = this.mInputConnection;
        if (inputConnection == null) {
            return null;
        }
        return inputConnection.getTextBeforeCursor(i, i2);
    }

    /**
     * Returns up to {@code i} characters of text after the cursor. Uses the cached buffer
     * when available and falls back to the live editor connection if the cache is insufficient.
     *
     * @param i  maximum number of characters to return
     * @param i2 flags (e.g., {@link android.view.inputmethod.InputConnection#GET_TEXT_WITH_STYLES})
     * @return text after cursor, or {@code null} if disconnected
     */
    public CharSequence getTextAfterCursor(int i, int i2) {
        if (-1 != this.mCursorStart) {
            if (this.mTextAfterCursor.length() > i) {
                return this.mTextAfterCursor.substring(0, i);
            }
            return this.mTextAfterCursor.toString();
        }
        this.mInputConnection = this.mImeService.getCurrentInputConnection();
        InputConnection inputConnection = this.mInputConnection;
        if (inputConnection == null) {
            return null;
        }
        return inputConnection.getTextAfterCursor(i, i2);
    }

    /**
     * Deletes {@code i} characters before the cursor and {@code i2} characters after it,
     * updating all internal buffers and notifying listeners before forwarding to the editor.
     *
     * @param i  number of characters to delete before the cursor
     * @param i2 number of characters to delete after the cursor
     */
    public void deleteSurroundingText(int i, int i2) {
        if (BuildConfig.DEBUG) Log.d("TEXT_EDIT_DEBUG", "RIC.deleteSurroundingText: before=" + i + " after=" + i2 + " cursorStart=" + mCursorStart + " composing='" + mComposingText + "'");
        if (this.mInputConnection != null) {
            if (i > 0) {
                notifyTextDeleted(this.mCursorStart, i);
            }
            if (i2 > 0) {
                notifyTextChanged(this.mCursorStart, i2, (CharSequence) null, TextChangeType.COMPOSING);
            }
        }
        int length = this.mComposingText.length() - i;
        if (length >= 0) {
            this.mComposingText.setLength(length);
            this.mComposingRegionLength = this.mComposingText.length();
        } else {
            this.mComposingText.setLength(0);
            this.mComposingRegionLength = 0;
            this.mTextBeforeCursor.setLength(Math.max(this.mTextBeforeCursor.length() + length, 0));
        }
        if (i2 > 0) {
            this.mTextAfterCursor.delete(0, i2);
        }
        int i3 = this.mCursorStart;
        if (i3 > i) {
            this.mCursorStart = i3 - i;
            this.mCursorEnd -= i;
        } else {
            this.mCursorEnd -= i3;
            this.mCursorStart = 0;
        }
        InputConnection inputConnection = this.mInputConnection;
        if (inputConnection != null) {
            inputConnection.deleteSurroundingText(i, i2);
        }
    }

    /**
     * Performs the IME action with the given action code on the current editor connection.
     * Refreshes the connection reference first.
     *
     * @param i the action code (e.g., {@link android.view.inputmethod.EditorInfo#IME_ACTION_DONE})
     */
    public void performEditorAction(int i) {
        this.mInputConnection = this.mImeService.getCurrentInputConnection();
        InputConnection inputConnection = this.mInputConnection;
        if (inputConnection != null) {
            inputConnection.performEditorAction(i);
        }
    }

    /**
     * Sends a {@link KeyEvent} to the editor, updating internal text and cursor buffers for
     * ENTER, BACKSPACE, and printable key events before forwarding to the connection.
     *
     * @param keyEvent the key event to dispatch
     */
    public void sendKeyEvent(KeyEvent keyEvent) {
        if (keyEvent.getAction() == 0) {
            int keyCode = keyEvent.getKeyCode();
            if (keyCode != 0) {
                switch (keyCode) {
                    case 66:
                        this.mTextBeforeCursor.append("\n");
                        this.mCursorStart++;
                        this.mCursorEnd = this.mCursorStart;
                        break;
                    case 67: {
                        // Delete one CODE POINT from the cache mirrors, not one char —
                        // a char-sized delete leaves a lone surrogate behind when the
                        // last character is an emoji or other supplementary code point.
                        int deletedChars = 1;
                        if (this.mComposingText.length() == 0) {
                            int len = this.mTextBeforeCursor.length();
                            if (len > 0) {
                                deletedChars = Character.charCount(Character.codePointBefore(this.mTextBeforeCursor, len));
                                this.mTextBeforeCursor.delete(len - deletedChars, len);
                            }
                        } else {
                            int len = this.mComposingText.length();
                            deletedChars = Character.charCount(Character.codePointBefore(this.mComposingText, len));
                            this.mComposingText.delete(len - deletedChars, len);
                        }
                        int i = this.mCursorStart;
                        if (i > 0 && i == this.mCursorEnd) {
                            this.mCursorStart = Math.max(0, i - deletedChars);
                        }
                        this.mCursorEnd = this.mCursorStart;
                        break;
                    }
                    default:
                        int unicodeChar = keyEvent.getUnicodeChar();
                        if (Character.isValidCodePoint(unicodeChar) && unicodeChar != 0) {
                            String strM5463a = new String(Character.toChars(unicodeChar));
                            this.mTextBeforeCursor.append(strM5463a);
                            this.mCursorStart += strM5463a.length();
                            this.mCursorEnd = this.mCursorStart;
                            break;
                        }
                        break;
                }
            } else if (keyEvent.getCharacters() != null) {
                this.mTextBeforeCursor.append(keyEvent.getCharacters());
                this.mCursorStart += keyEvent.getCharacters().length();
                this.mCursorEnd = this.mCursorStart;
            }
        }
        InputConnection inputConnection = this.mInputConnection;
        if (inputConnection != null) {
            inputConnection.sendKeyEvent(keyEvent);
        }
    }

    /**
     * Marks the region {@code [i, i2)} in the editor as the active composing region,
     * updating internal buffers to reflect what text is now under composition.
     *
     * @param i  start of the composing region (inclusive, in editor coordinates)
     * @param i2 end of the composing region (exclusive, in editor coordinates)
     */
    public void setComposingRegion(int i, int i2) {
        int i3 = i2 - i;
        CharSequence charSequenceM5815a = getTextBeforeCursor(i3 + NuanceSDK.MAX_CONTEXT_LENGTH, 0);
        this.mTextBeforeCursor.setLength(0);
        if (!TextUtils.isEmpty(charSequenceM5815a)) {
            int iMax = Math.max(charSequenceM5815a.length() - (this.mCursorStart - i), 0);
            this.mComposingText.append(charSequenceM5815a.subSequence(iMax, charSequenceM5815a.length()));
            notifyComposingTextUpdated(this.mComposingText);
            this.mTextBeforeCursor.append(charSequenceM5815a.subSequence(0, iMax));
        }
        this.mComposingRegionLength = i3;
        CharSequence charSequenceM5830b = getTextAfterCursor(NuanceSDK.MAX_CONTEXT_LENGTH, 0);
        this.mTextAfterCursor.setLength(0);
        if (!TextUtils.isEmpty(charSequenceM5830b)) {
            this.mTextAfterCursor.append(charSequenceM5830b.subSequence(Math.max(i2 - this.mCursorStart, 0), charSequenceM5830b.length()));
        }
        InputConnection inputConnection = this.mInputConnection;
        if (inputConnection != null) {
            inputConnection.setComposingRegion(i, i2);
        }
    }

    /**
     * Sets the active composing text in the editor, replacing any previous composing text.
     * Updates internal cursor and buffer state, then forwards to the editor connection.
     *
     * @param charSequence the new composing text
     * @param i            cursor position relative to the composing text (1 = end)
     */
    public void setComposingText(CharSequence charSequence, int i) {
        if (BuildConfig.DEBUG) Log.d("TEXT_EDIT_DEBUG", "RIC.setComposingText: newText='" + charSequence + "' cursorPos=" + i + " cursorStart=" + mCursorStart + " prevComposing='" + mComposingText + "'");
        if (this.mInputConnection != null) {
            notifyTextChanged(this.mCursorStart - this.mComposingText.length(), this.mComposingRegionLength, charSequence, TextChangeType.COMPOSING);
        }
        this.mCursorStart += charSequence.length() - this.mComposingText.length();
        this.mCursorEnd = this.mCursorStart;
        this.mComposingText.setLength(0);
        this.mComposingText.append(charSequence);
        this.mComposingRegionLength = this.mComposingText.length();
        InputConnection inputConnection = this.mInputConnection;
        if (inputConnection != null) {
            inputConnection.setComposingText(charSequence, i);
        }
    }

    /**
     * Moves the editor selection to {@code [i, i2)}, reloads the before-cursor text buffer,
     * and updates the internal cursor position. Both positions must be non-negative.
     *
     * @param i  the new selection start
     * @param i2 the new selection end
     * @return {@code true} if the text context was reloaded successfully
     */
    public boolean setSelection(int i, int i2) {
        if (BuildConfig.DEBUG) Log.d("TEXT_EDIT_DEBUG", "RIC.setSelection: [" + i + "," + i2 + "] prevCursor=[" + mCursorStart + "," + mCursorEnd + "] composing='" + mComposingText + "'");
        if (i < 0 || i2 < 0) {
            return false;
        }
        this.mCursorStart = i;
        this.mCursorEnd = i2;
        InputConnection inputConnection = this.mInputConnection;
        if (inputConnection == null || inputConnection.setSelection(i, i2)) {
            return loadTextBeforeCursor();
        }
        return false;
    }

    /**
     * Moves the editor cursor to a position <em>inside</em> the active composing region while
     * keeping this class's text-model invariant intact: {@link #mTextBeforeCursor} must hold
     * only <em>committed</em> text before the composing region, never any part of the
     * composing text itself.
     *
     * <p>A plain {@link #setSelection} reloads {@link #mTextBeforeCursor} from the editor,
     * and with the cursor mid-region the editor reports text <em>up to the cursor</em> —
     * which includes the composing prefix. Since {@link #mComposingText} still holds the
     * full composing word, {@link #getTextBeforeCursor} would then double-count the prefix
     * (and {@link #getCodePointBeforeCursor} would return a character from inside the
     * word). This method trims the composing prefix back off after the reload so all
     * readers see the same semantics as the normal cursor-at-end composing state.
     *
     * @param newPos         the new cursor position (must lie inside the composing region)
     * @param composingStart editor position where the composing region begins
     * @return {@code true} if the selection was applied and the text context reloaded
     */
    public boolean setSelectionWithinComposing(int newPos, int composingStart) {
        if (!setSelection(newPos, newPos)) {
            return false;
        }
        int composingPrefixLen = newPos - composingStart;
        int length = this.mTextBeforeCursor.length();
        if (composingPrefixLen > 0 && length >= composingPrefixLen) {
            this.mTextBeforeCursor.setLength(length - composingPrefixLen);
        }
        return true;
    }

    /**
     * Submits a correction notification to the editor. Used after auto-correction to allow
     * the editor to update undo history and word-error highlights.
     *
     * @param correctionInfo describes the original and corrected text
     */
    public void commitCorrection(CorrectionInfo correctionInfo) {
        InputConnection inputConnection = this.mInputConnection;
        if (inputConnection != null) {
            inputConnection.commitCorrection(correctionInfo);
        }
    }

    /**
     * Commits an application-provided completion to the editor (e.g., from
     * {@link android.view.inputmethod.EditorInfo#TYPE_CLASS_TEXT} with application completions).
     * Updates internal buffers to reflect the committed text.
     *
     * @param completionInfo the completion to commit
     */
    public void commitCompletion(CompletionInfo completionInfo) {
        CharSequence text = completionInfo.getText();
        if (text == null) {
            text = "";
        }
        this.mTextBeforeCursor.append(text);
        this.mCursorStart += text.length() - this.mComposingText.length();
        this.mCursorEnd = this.mCursorStart;
        this.mComposingText.setLength(0);
        this.mComposingRegionLength = 0;
        InputConnection inputConnection = this.mInputConnection;
        if (inputConnection != null) {
            inputConnection.commitCompletion(completionInfo);
        }
    }

    /**
     * Builds and returns a {@link PrevWordsInfo} context object from the text before and after
     * the cursor. Used to provide bigram context to the dictionary and learning system.
     *
     * <p>TI-2: this used to take {@code PrevWordsInfo.EMPTY_PREV_WORDS_INFO} - a
     * {@code public static final} singleton - write the current context into it, and return it.
     * There was therefore exactly ONE PrevWordsInfo in the process: every
     * {@code CommitEventRecord.prevWordsInfo} "snapshot" taken at commit time aliased it and was
     * silently rewritten by the next keystroke, and the suggestion worker read the same fields off
     * the main thread while the main thread wrote them. Each call now returns a fresh instance.
     *
     * @param c0806f spacing and punctuation rules (unused in current implementation)
     * @param i      context depth hint (unused in current implementation)
     * @return a freshly allocated, populated {@link PrevWordsInfo}
     */
    public PrevWordsInfo getPrevWordsInfo(SpacingAndPunctuation c0806f, int i) {
        return new PrevWordsInfo(PrevWordsInfo.WordInfo.EMPTY_WORD_INFO, getTextContextBefore());
    }

    private static boolean isPartOfWord(int i, SpacingAndPunctuation c0806f, int i2) {
        return c0806f.isWordConnector(i) || (!c0806f.isWordSeparator(i) && ScriptUtils.isLetterPartOfScript(i, i2));
    }

    /**
     * Returns a {@link CursorWordRange} describing the word at the cursor, optionally anchored to
     * the given {@code charSequence} if it matches the text immediately before the cursor.
     * Falls back to {@link #getWordRangeAtCursor} for the general case.
     *
     * @param charSequence an optional text anchor (e.g., the just-committed gesture word)
     * @param c0806f       spacing and punctuation rules for word boundary detection
     * @param i            locale flags for word character classification
     * @return a {@link CursorWordRange} for the word at cursor, or {@code null} if unavailable
     */
    public CursorWordRange getWordAtCursor(CharSequence charSequence, SpacingAndPunctuation c0806f, int i) {
        if (charSequence == null) {
            return null;
        }
        if (isTextBeforeCursor(charSequence)) {
            int length = charSequence.length();
            return new CursorWordRange(SpannableStringUtils.concatWithNonParagraphSuggestionSpansOnly(charSequence), 0, length, length, false);
        }
        return getWordRangeAtCursor(c0806f, i);
    }

    /**
     * Finds the word range surrounding the current cursor position by scanning backwards
     * and forwards through the text for word boundaries. Handles CJK (no-space) locales,
     * supplementary code points, URL/email detection, and word-connector characters.
     *
     * @param c0806f spacing and punctuation rules for word boundary detection
     * @param i      locale flags for word character classification
     * @return a {@link CursorWordRange} describing the word range, or {@code null} if not in a word
     */
    public CursorWordRange getWordRangeAtCursor(SpacingAndPunctuation c0806f, int i) {
        int i2;
        int i3;
        int length;
        CharSequence charSequenceM5815a = getTextBeforeCursor(NuanceSDK.MAX_CONTEXT_LENGTH, 1);
        CharSequence charSequenceM5830b = getTextAfterCursor(NuanceSDK.MAX_CONTEXT_LENGTH, 1);
        if (charSequenceM5815a == null || charSequenceM5830b == null) {
            return null;
        }
        int iM5739a = charSequenceM5815a.length() > 0 ? EmojiTextAnalyzer.findEmoticonShortcodeLength(true, charSequenceM5815a, Character.codePointBefore(charSequenceM5815a, charSequenceM5815a.length())) : 0;
        if (charSequenceM5815a.length() > 0 && !c0806f.currentLanguageHasSpaces && iM5739a == 0) {
            List<String> sequence = BlackBerryTokenizer.toSequence(new BlackBerryTokenizer(NuanceSDKManager.getInstance().getPrimaryLanguage()).split(charSequenceM5815a.toString()));
            int size = sequence.size();
            if (size <= 0 || (length = sequence.get(size - 1).length()) <= 0) {
                return null;
            }
            int length2 = charSequenceM5815a.length() - length;
            return new CursorWordRange(charSequenceM5815a, length2, charSequenceM5815a.length(), charSequenceM5815a.length(), SpannableStringUtils.hasUrlSpans(charSequenceM5815a, length2, charSequenceM5815a.length()));
        }
        int length3 = charSequenceM5815a.length();
        while (true) {
            if (length3 <= 0) {
                i2 = 0;
                break;
            }
            int iCodePointBefore = Character.codePointBefore(charSequenceM5815a, length3);
            if (iM5739a == 0) {
                if (!isPartOfWord(iCodePointBefore, c0806f, i) && !Character.isDigit(iCodePointBefore)) {
                    i2 = (Character.isWhitespace(iCodePointBefore) ? 1 : 0) + 0;
                    break;
                }
                length3--;
                if (Character.isSupplementaryCodePoint(iCodePointBefore)) {
                    length3--;
                }
            } else {
                i2 = (Character.isWhitespace(iCodePointBefore) ? 1 : 0) + 0;
                break;
            }
        }
        int i4 = i2 + (length3 == 0 ? 1 : 0);
        int i5 = -1;
        while (true) {
            i5++;
            if (i5 >= charSequenceM5830b.length()) {
                break;
            }
            int iCodePointAt = Character.codePointAt(charSequenceM5830b, i5);
            if (!isPartOfWord(iCodePointAt, c0806f, i) && !Character.isDigit(iCodePointAt)) {
                i4 += Character.isWhitespace(iCodePointAt) ? 1 : 0;
                break;
            }
            if (Character.isSupplementaryCodePoint(iCodePointAt)) {
                i5++;
            }
        }
        boolean z = i4 + (i5 == charSequenceM5830b.length() ? 1 : 0) < 2;
        int length4 = z ? charSequenceM5815a.length() : 0;
        while (length4 > 0) {
            int iCodePointBefore2 = Character.codePointBefore(charSequenceM5815a, length4);
            if (Character.isWhitespace(iCodePointBefore2)) {
                break;
            }
            length4--;
            if (Character.isSupplementaryCodePoint(iCodePointBefore2)) {
                length4--;
            }
        }
        int length5 = z ? -1 : charSequenceM5830b.length();
        while (true) {
            length5++;
            if (length5 >= charSequenceM5830b.length()) {
                break;
            }
            int iCodePointAt2 = Character.codePointAt(charSequenceM5830b, length5);
            if (Character.isWhitespace(iCodePointAt2)) {
                break;
            }
            if (Character.isSupplementaryCodePoint(iCodePointAt2)) {
                length5++;
            }
        }
        if (!z || (length3 == length4 && i5 == length5)) {
            i3 = length3;
            length5 = i5;
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append(charSequenceM5815a.subSequence(length4, charSequenceM5815a.length()));
            sb.append(charSequenceM5830b.subSequence(0, length5));
            if (StringHelper.looksLikeURL(sb) || looksLikeEmail(sb)) {
                i3 = length4;
            } else {
                i3 = length3;
            }
        }
        return new CursorWordRange(SpannableStringUtils.concatWithNonParagraphSuggestionSpansOnly(charSequenceM5815a, charSequenceM5830b), i3, charSequenceM5815a.length() + length5, charSequenceM5815a.length(), SpannableStringUtils.hasUrlSpans(charSequenceM5815a, i3, charSequenceM5815a.length()) || SpannableStringUtils.hasUrlSpans(charSequenceM5830b, 0, length5));
    }

    /**
     * Returns {@code true} if there is a word character immediately before the cursor (or
     * before a trailing word-connector character). Also returns {@code true} when a word
     * character exists after the cursor, to support mid-word cursor placement detection.
     *
     * @param c0806f spacing and punctuation rules
     * @return {@code true} if a word character precedes the cursor
     */
    public boolean hasWordBeforeCursor(SpacingAndPunctuation c0806f) {
        if (hasWordAfterCursor(c0806f)) {
            return true;
        }
        String string = this.mTextBeforeCursor.toString();
        int length = string.length();
        int iCodePointBefore = length == 0 ? -1 : string.codePointBefore(length);
        if (c0806f.isWordConnector(iCodePointBefore)) {
            int iCharCount = length - Character.charCount(iCodePointBefore);
            iCodePointBefore = iCharCount == 0 ? -1 : string.codePointBefore(iCharCount);
        }
        return (-1 == iCodePointBefore || c0806f.isWordSeparator(iCodePointBefore) || c0806f.isWordConnector(iCodePointBefore)) ? false : true;
    }

    /**
     * Returns {@code true} if the character immediately after the cursor is a word character
     * (not a word separator or word connector).
     *
     * @param c0806f spacing and punctuation rules
     * @return {@code true} if the character after the cursor begins a word
     */
    public boolean hasWordAfterCursor(SpacingAndPunctuation c0806f) {
        CharSequence charSequenceM5830b = getTextAfterCursor(1, 0);
        if (TextUtils.isEmpty(charSequenceM5830b)) {
            return false;
        }
        int iCodePointAt = Character.codePointAt(charSequenceM5830b, 0);
        return (c0806f.isWordSeparator(iCodePointAt) || c0806f.isWordConnector(iCodePointAt)) ? false : true;
    }

    /**
     * Returns {@code true} if the character immediately after the cursor is contained in
     * the given set string. Used to determine whether auto-spacing should be suppressed.
     *
     * @param str a string whose code points form the membership set; {@code null} means empty set
     * @return {@code true} if the after-cursor character is in {@code str}
     */
    public boolean isCharAfterCursorInSet(String str) {
        CharSequence charSequenceM5830b = getTextAfterCursor(1, 0);
        if (TextUtils.isEmpty(charSequenceM5830b)) {
            return false;
        }
        return (str == null || str.indexOf(Character.codePointAt(charSequenceM5830b, 0)) == -1) ? false : true;
    }

    /**
     * Deletes the single space character immediately before the cursor, if one is present.
     * Used to remove auto-appended spaces before punctuation.
     */
    public void deleteTrailingSpace() {
        if (32 == getCodePointBeforeCursor()) {
            deleteSurroundingText(1, 0);
        }
    }

    /**
     * Returns {@code true} if the text immediately before the cursor equals {@code charSequence}.
     * Used to verify that a specific string is still present before performing a revert.
     *
     * @param charSequence the text to match against the text before the cursor
     * @return {@code true} if the before-cursor text ends with {@code charSequence}
     */
    public boolean isTextBeforeCursor(CharSequence charSequence) {
        return TextUtils.equals(charSequence, getTextBeforeCursor(charSequence.length(), 0));
    }

    /**
     * Reverts a double-space-to-period substitution by replacing the {@code ". "} sequence
     * immediately before the cursor with a single space. Logs and returns {@code false} if
     * the expected sequence is not found.
     *
     * @return {@code true} if the revert was performed; {@code false} otherwise
     */
    public boolean revertDoubleSpacePeriod() {
        if (!TextUtils.equals(". ", getTextBeforeCursor(2, 0))) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Tried to revert double-space combo but we didn't find \". \" just before the cursor.");
            return false;
        }
        deleteSurroundingText(2, 0);
        commitText(" ", 1);
        return true;
    }

    /**
     * Reverts a space-before-punctuation swap by exchanging the space and punctuation characters
     * in the two-character sequence immediately before the cursor. Logs and returns {@code false}
     * if the expected {@code "x "} pattern is not found.
     *
     * @return {@code true} if the revert was performed; {@code false} otherwise
     */
    public boolean revertSwapPunctuation() {
        CharSequence charSequenceM5815a = getTextBeforeCursor(2, 0);
        // TI-35: isEmpty() only rules out length 0, but charAt(1) needs length 2. With the cursor
        // one character into the field getTextBeforeCursor(2, 0) legitimately returns one char,
        // and this threw StringIndexOutOfBoundsException off the backspace path.
        if (charSequenceM5815a == null || charSequenceM5815a.length() < 2 || ' ' != charSequenceM5815a.charAt(1)) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Tried to revert a swap of punctuation but we didn't find a space just before the cursor.");
            return false;
        }
        deleteSurroundingText(2, 0);
        commitText(" " + ((Object) charSequenceM5815a.subSequence(0, 1)), 1);
        return true;
    }

    /**
     * Returns {@code true} if the selection described by the old values {@code (i, i3)} and
     * new values {@code (i2, i4)} has not meaningfully changed relative to the internally
     * tracked cursor position. Used by {@code onUpdateSelection} to avoid redundant resets.
     *
     * @param i  old selection start
     * @param i2 new selection start
     * @param i3 old selection end
     * @param i4 new selection end
     * @return {@code true} if the selection change can be considered a no-op
     */
    public boolean isSelectionUnchanged(int i, int i2, int i3, int i4) {
        if (this.mCursorStart == i2 && this.mCursorEnd == i4) {
            return true;
        }
        // With the cursor unknown (invalidateConnection, e.g. right after a hardware Enter was
        // handed to the app) the monotonic-move test below multiplies through -1 and calls a
        // genuine jump such as [5,5] -> [0,0] - the app emptying the field - "unchanged", so the
        // clear was ignored. Unknown means the editor's report is the only position we have:
        // take it unless it says nothing moved.
        if (this.mCursorStart < 0 || this.mCursorEnd < 0) {
            return i == i2 && i3 == i4;
        }
        return !(this.mCursorStart == i && this.mCursorEnd == i3 && (i != i2 || i3 != i4)) && i2 == i4 && (i2 - i) * (this.mCursorStart - i2) >= 0 && (i4 - i3) * (this.mCursorEnd - i4) >= 0;
    }

    /**
     * Asks the editor, not the cache, whether it holds no text at all on either side of the
     * cursor. An unreachable editor (null answers) is not "empty": the caller must not act on
     * what it cannot see. Two round-trips, so callers reserve it for rare events.
     *
     * @return {@code true} only when the editor answered and both sides are empty
     */
    public boolean isEditorEmpty() {
        this.mInputConnection = this.mImeService.getCurrentInputConnection();
        InputConnection inputConnection = this.mInputConnection;
        if (inputConnection == null) {
            return false;
        }
        CharSequence before = inputConnection.getTextBeforeCursor(1, 0);
        CharSequence after = inputConnection.getTextAfterCursor(1, 0);
        return before != null && after != null && before.length() == 0 && after.length() == 0;
    }

    /**
     * Returns {@code true} if the text currently before the cursor appears to be part of a URL.
     * Used to suppress auto-spacing after word commits in URL fields.
     *
     * @return {@code true} if text before cursor looks like a URL
     */
    public boolean looksLikeURL() {
        return StringHelper.looksLikeURL(this.mTextBeforeCursor);
    }

    /**
     * Returns {@code true} if the text before the cursor ends with a quote character that
     * immediately follows a digit (e.g., feet/inches notation like {@code 6'}). Used to
     * suppress quote swap punctuation in those cases.
     *
     * @return {@code true} if text ends with a quote after a digit
     */
    public boolean endsWithQuoteAfterDigit() {
        return StringHelper.endsWithQuoteAfterDigit(this.mTextBeforeCursor);
    }

    /**
     * Re-measures the cursor start position by counting characters in the text returned by
     * the editor. Corrects drift that can occur when the editor updates text outside of IME
     * control. Also adjusts the cursor end position to keep start ≤ end.
     */
    public void recalibrateCursorPosition() {
        CharSequence charSequenceM5815a = getTextBeforeCursor(NuanceSDK.MAX_CONTEXT_LENGTH, 0);
        if (charSequenceM5815a == null) {
            this.mCursorEnd = -1;
            this.mCursorStart = -1;
            return;
        }
        int length = charSequenceM5815a.length();
        if (length < 1024) {
            int i = this.mCursorStart;
            if (length > i || i < 1024) {
                boolean z = this.mCursorStart == this.mCursorEnd;
                this.mCursorStart = length;
                if (z || this.mCursorStart > this.mCursorEnd) {
                    this.mCursorEnd = this.mCursorStart;
                }
            }
        }
    }

    /**
     * Returns the cached cursor start (selection anchor) position in the editor, or -1
     * if the connection is not established.
     *
     * @return cursor start position
     */
    public int getCursorStart() {
        return this.mCursorStart;
    }

    /**
     * Returns the cached cursor end (selection focus) position in the editor, or -1
     * if the connection is not established.
     *
     * @return cursor end position
     */
    public int getCursorEnd() {
        return this.mCursorEnd;
    }

    /**
     * Returns {@code true} if the editor has a non-collapsed selection
     * (i.e., cursor start and end differ).
     *
     * @return {@code true} if text is currently selected
     */
    public boolean hasSelection() {
        return this.mCursorEnd != this.mCursorStart;
    }

    /**
     * Returns {@code true} if the connection to the editor is established
     * (cursor start is not -1).
     *
     * @return {@code true} if the editor connection is active
     */
    public boolean isConnected() {
        return -1 != this.mCursorStart;
    }

    /**
     * Requests cursor anchor info updates from the editor. When monitoring is enabled,
     * the IME receives {@code onUpdateCursorAnchorInfo} callbacks for smooth floating
     * cursor and more-keys panel positioning.
     *
     * @param z  {@code true} to enable monitoring mode (continuous updates)
     * @param z2 {@code true} to request an immediate one-shot update
     * @return {@code true} if the editor accepted the request
     */
    public boolean requestCursorUpdates(boolean z, boolean z2) {
        this.mInputConnection = this.mImeService.getCurrentInputConnection();
        InputConnection inputConnection = this.mInputConnection;
        boolean z3 = false;
        boolean requested = inputConnection != null
                && inputConnection.requestCursorUpdates((z ? InputConnection.CURSOR_UPDATE_MONITOR : 0) | (z2 ? InputConnection.CURSOR_UPDATE_IMMEDIATE : 0));
        if (requested && z) {
            z3 = true;
        }
        this.mMonitorCursorUpdates = z3;
        return requested;
    }

    /**
     * Returns {@code true} if monitoring mode was successfully enabled via
     * {@link #requestCursorUpdates}.
     *
     * @return {@code true} if cursor anchor info monitoring is active
     */
    public boolean isMonitoringCursorUpdates() {
        return this.mMonitorCursorUpdates;
    }

    /**
     * Returns up to 146 characters of text before the cursor from the internal buffer,
     * suitable for use as NuanceSDK context. Returns {@code null} if the buffer is empty.
     *
     * @return context string (at most 146 chars), or {@code null} if empty
     */
    public String getTextContextBefore() {
        // TI-11: this used to make a String copy of the whole buffer, copy that into a new
        // StringBuilder, and then usually substring it - three copies of up to MAX_CONTEXT_LENGTH
        // chars to return at most 146. It is called at least twice per keystroke.
        int length = this.mTextBeforeCursor.length();
        if (length <= 0) {
            return null;
        }
        if (length > 146) {
            return this.mTextBeforeCursor.substring(length - 146);
        }
        return this.mTextBeforeCursor.toString();
    }
}

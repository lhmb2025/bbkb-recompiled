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

import dev.bbkb.ime.core.engine.PrimaryEngineFacts;
import dev.bbkb.ime.core.suggestion.PrevWordsInfo;
import dev.bbkb.ime.core.shared.StringHelper;
import dev.bbkb.ime.personaldictionary.tokenizer.BlackBerryTokenizer;
import dev.bbkb.ime.core.settings.util.SpacingAndPunctuation;
import dev.bbkb.ime.core.textinput.CapsModeUtils;
import dev.bbkb.ime.core.shared.EmojiTextAnalyzer;
import dev.bbkb.ime.core.shared.ScriptUtils;
import dev.bbkb.ime.core.shared.SpannableStringUtils;
import com.blackberry.nuanceshim.NuanceSDK;

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

    private SpannableStringBuilder mTempSpannable = new SpannableStringBuilder();

    private boolean mHasBackgroundSpan = false;

    private CharSequence mCachedCapsText = null;

    private int mCachedCapsFlags = 0;

    private boolean mCachedCapsLocaleFlag = false;

    private int mCachedCapsResult = 0;

    private boolean mMonitorCursorUpdates = false;

    private InputConnection mInputConnection = null;

    private int mBatchedEditNestLevel = 0;

    /**
     * Below this window on the shrinking side, a cursor move reloads instead of shifting the cache,
     * so readers that never fall back to the editor (engine context 146 chars, caps mode, a
     * 10 x 48 char grapheme scan) see what a reload would give them.
     */
    static final int MIN_LOCAL_SHIFT_WINDOW = 512;

    /** Bound on the moves whose selection reports have not arrived yet. */
    private static final int MAX_PENDING_ECHOES = 16;

    /**
     * Cap on the before-cursor cache: commits append without bound and caps mode / the quote check
     * walk all of it. Past this size the front is dropped back to the load size.
     */
    static final int MAX_TEXT_BEFORE_CURSOR = NuanceSDK.MAX_CONTEXT_LENGTH * 2;

    /**
     * Both caches are the last successful load plus our own edits. False after a failed load,
     * invalidateConnection, or a key that can move the cursor unseen; only while true does a
     * cursor move shift the caches instead of reloading.
     */
    private boolean mTextCacheLoaded = false;

    /** The last after-cursor load returned less than asked for: the cache runs to the end of the text. */
    private boolean mTextAfterReachesEnd = false;

    /**
     * Collapsed {@link #setSelection} moves not yet echoed by onUpdateSelection, {from, to} in send
     * order. A keypad drag sends one per frame, so on a direction reversal a trailing report points
     * the "wrong" way and must not be read as an external move.
     */
    private final java.util.ArrayDeque<int[]> mPendingEchoes = new java.util.ArrayDeque<>();

    public RichInputConnection(InputMethodService inputMethodService) {
        this.mImeService = inputMethodService;
    }

    /** Trims to what a fresh load would hold, so readers asking for more go to the editor as after a load. */
    private void trimTextBeforeCursor() {
        final int length = this.mTextBeforeCursor.length();
        if (length <= MAX_TEXT_BEFORE_CURSOR) {
            return;
        }
        int cut = length - NuanceSDK.MAX_CONTEXT_LENGTH;
        // Never leave half of a surrogate pair at the new front.
        if (Character.isLowSurrogate(this.mTextBeforeCursor.charAt(cut))) {
            cut++;
        }
        this.mTextBeforeCursor.delete(0, cut);
    }

    /** Refreshes the connection from the service at nest level 1; deeper nesting only logs. */
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
     * Moves to [i, i2] and reloads the text context; {@code z} also finishes composing.
     * @param i the new cursor start position
     * @param i2 the new cursor end position
     * @param z if {@code true}, also calls {@link #finishComposingText()} on the connection
     * @return false if the editor is unavailable
     */
    public boolean resetConnection(int i, int i2, boolean z) {
        if (BuildConfig.DEBUG) Log.d("TEXT_EDIT_DEBUG", "RIC.resetConnection: newPos=[" + i + "," + i2 + "] finishComposing=" + z + " composingText='" + mComposingText + "' composingLen=" + mComposingRegionLength);
        this.mCursorStart = i;
        this.mCursorEnd = i2;
        this.mComposingText.setLength(0);
        this.mComposingRegionLength = 0;
        // The editor's report wins: earlier moves that have not echoed yet no longer describe it.
        this.mPendingEchoes.clear();
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
        return true;
    }

    private boolean loadTextBeforeCursor() {
        this.mTextCacheLoaded = false;
        this.mTextBeforeCursor.setLength(0);
        this.mInputConnection = this.mImeService.getCurrentInputConnection();
        InputConnection inputConnection = this.mInputConnection;
        // Flags 0, not GET_TEXT_WITH_STYLES: spans would be parceled only to be dropped here.
        CharSequence textBeforeCursor = inputConnection == null ? null : inputConnection.getTextBeforeCursor(NuanceSDK.MAX_CONTEXT_LENGTH, 0);
        if (textBeforeCursor == null) {
            this.mCursorStart = -1;
            this.mCursorEnd = -1;
            if (BuildConfig.DEBUG) Log.d(TAG, "Unable to connect to the editor to retrieve text before cursor.");
            return false;
        }
        this.mTextBeforeCursor.append(textBeforeCursor);
        this.mTextCacheLoaded = loadTextAfterCursor();
        return this.mTextCacheLoaded;
    }

    /** Drops the connection and all caches; text reads return null until the next reset. */
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
        this.mTextCacheLoaded = false;
        this.mTextAfterReachesEnd = false;
        this.mPendingEchoes.clear();
    }

    private boolean loadTextAfterCursor() {
        this.mTextAfterCursor.setLength(0);
        this.mTextAfterReachesEnd = false;
        InputConnection inputConnection = this.mInputConnection;
        CharSequence textAfterCursor = inputConnection == null ? null : inputConnection.getTextAfterCursor(NuanceSDK.MAX_CONTEXT_LENGTH, 0);
        if (textAfterCursor == null) {
            this.mTextCacheLoaded = false;
            this.mCursorStart = -1;
            this.mCursorEnd = -1;
            if (BuildConfig.DEBUG) Log.d(TAG, "Unable to connect to the editor to retrieve text after cursor.");
            return false;
        }
        this.mTextAfterCursor.append(textAfterCursor);
        this.mTextAfterReachesEnd = textAfterCursor.length() < NuanceSDK.MAX_CONTEXT_LENGTH;
        return true;
    }

    /** False when the editor changed the composing text underneath us. */
    public boolean isComposingRegionSynced() {
        return this.mComposingText.length() == this.mComposingRegionLength;
    }

    public void finishComposingText() {
        if (BuildConfig.DEBUG) Log.d("TEXT_EDIT_DEBUG", "RIC.finishComposingText: composingText='" + mComposingText + "' composingLen=" + mComposingRegionLength + " cursorStart=" + mCursorStart);
        if (!isComposingRegionSynced()) {
            loadTextAfterCursor();
        }
        this.mTextBeforeCursor.append((CharSequence) this.mComposingText);
        trimTextBeforeCursor();
        this.mComposingText.setLength(0);
        this.mComposingRegionLength = 0;
        this.mHasBackgroundSpan = false;
        InputConnection inputConnection = this.mInputConnection;
        if (inputConnection != null) {
            inputConnection.finishComposingText();
        }
    }

    /**
     * @param i cursor position after commit (typically 1 = after text)
     */
    public void commitText(CharSequence charSequence, int i) {
        commitTextWithHighlight(charSequence, i, 0, charSequence.length());
    }

    /**
     * Commits text, highlighting [0, i3) with background color {@code i2} (0 = none), as used
     * for autocorrect and add-to-dictionary marks. {@code i} is the newCursorPosition.
     * @param i cursor position after commit (typically 1 = after text)
     * @param i2 highlight background color, or 0 for no highlight
     * @param i3 end index of the highlight span within {@code charSequence}
     */
    public void commitTextWithHighlight(CharSequence charSequence, int i, int i2, int i3) {
        this.mTextBeforeCursor.append(charSequence);
        trimTextBeforeCursor();
        this.mCursorStart += charSequence.length() - this.mComposingText.length();
        // newCursorPosition i > 0 lands i-1 chars past the text; the manual-pick path uses i == 2
        // to skip the separator already after the cursor.
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

    /** Finishes a lingering highlight span; call only with no composing text. */
    public void clearBackgroundSpans() {
        if (this.mHasBackgroundSpan) {
            if (this.mComposingText.length() > 0) {
                if (BuildConfig.DEBUG) Log.e(TAG, "clearSpansWithComposingFlags should be called when composing text is empty.");
            } else {
                finishComposingText();
                // The highlight carried SPAN_COMPOSING (flags 289), so the editor's spell checker
                // skipped the word and never revisits it: ask for the check now.
                requestSpellCheck();
            }
        }
    }

    /** API 31+ only. For text that was composing, which the editor's spell checker does not revisit. */
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
     * @param i flags passed to {@link android.view.inputmethod.InputConnection#getSelectedText}
     */
    public CharSequence getSelectedText(int i) {
        InputConnection inputConnection = this.mInputConnection;
        if (inputConnection == null) {
            return null;
        }
        return inputConnection.getSelectedText(i);
    }

    /** Cursor known and past 0 (-1 means not connected). */
    public boolean hasCursorPosition() {
        return this.mCursorStart > 0;
    }

    /**
     * Caps mode at the cursor, cached per (inputType flags {@code i}, {@code z}, text).
     * @param i input type flags from {@link android.view.inputmethod.EditorInfo#inputType}
     * @param c0806f spacing and punctuation rules for the current language
     * @param z {@code true} to restrict to non-CJK caps modes
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

    /** From the cache; -1 if nothing precedes the cursor. */
    public int getCodePointBeforeCursor() {
        int length = this.mTextBeforeCursor.length();
        if (length < 1) {
            return -1;
        }
        return Character.codePointBefore(this.mTextBeforeCursor, length);
    }

    /** -1 if nothing follows the cursor. */
    public int getCodePointAfterCursor() {
        CharSequence charSequenceM5830b = getTextAfterCursor(2, 0);
        if (charSequenceM5830b != null && charSequenceM5830b.length() >= 1) {
            return Character.codePointAt(charSequenceM5830b, 0);
        }
        return -1;
    }

    /**
     * Up to {@code i} chars from the cache (including composing), else from the editor; null if disconnected.
     * @param i maximum number of characters to return
     * @param i2 flags (e.g., {@link android.view.inputmethod.InputConnection#GET_TEXT_WITH_STYLES})
     */
    public CharSequence getTextBeforeCursor(int i, int i2) {
        final int beforeLen = this.mTextBeforeCursor.length();
        final int composingLen = this.mComposingText.length();
        int length = beforeLen + composingLen;
        int i3 = this.mCursorStart;
        if (-1 != i3 && (length >= i || length >= i3)) {
            // Copy only the last `i` chars: keystroke-path callers ask for 1, 2 or 48.
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
     * Up to {@code i} chars from the cache while the cursor is known, else from the editor.
     * @param i maximum number of characters to return
     * @param i2 flags (e.g., {@link android.view.inputmethod.InputConnection#GET_TEXT_WITH_STYLES})
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
     * @param i number of characters to delete before the cursor
     * @param i2 number of characters to delete after the cursor
     */
    public void deleteSurroundingText(int i, int i2) {
        if (BuildConfig.DEBUG) Log.d("TEXT_EDIT_DEBUG", "RIC.deleteSurroundingText: before=" + i + " after=" + i2 + " cursorStart=" + mCursorStart + " composing='" + mComposingText + "'");
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
     * @param i the action code (e.g., {@link android.view.inputmethod.EditorInfo#IME_ACTION_DONE})
     */
    public void performEditorAction(int i) {
        this.mInputConnection = this.mImeService.getCurrentInputConnection();
        InputConnection inputConnection = this.mInputConnection;
        if (inputConnection != null) {
            inputConnection.performEditorAction(i);
        }
    }

    /** Mirrors Enter, Backspace and printable keys into the cache before forwarding. */
    public void sendKeyEvent(KeyEvent keyEvent) {
        if (keyEvent.getAction() == 0) {
            int keyCode = keyEvent.getKeyCode();
            if (keyCode != 0) {
                switch (keyCode) {
                    case 66:
                        this.mTextBeforeCursor.append("\n");
                        trimTextBeforeCursor();
                        this.mCursorStart++;
                        this.mCursorEnd = this.mCursorStart;
                        break;
                    case 67: {
                        // Delete one code point, not one char, or an emoji leaves a lone surrogate.
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
                            trimTextBeforeCursor();
                            this.mCursorStart += strM5463a.length();
                            this.mCursorEnd = this.mCursorStart;
                            break;
                        }
                        // A key that types nothing (a DPAD arrow) can move the editor's cursor
                        // under the cache; only a reload may say where, not a local shift.
                        this.mTextCacheLoaded = false;
                        break;
                }
            } else if (keyEvent.getCharacters() != null) {
                this.mTextBeforeCursor.append(keyEvent.getCharacters());
                trimTextBeforeCursor();
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
     * Marks editor range [i, i2) as composing and moves that text into the composing buffer.
     * @param i start of the composing region (inclusive, in editor coordinates)
     * @param i2 end of the composing region (exclusive, in editor coordinates)
     */
    public void setComposingRegion(int i, int i2) {
        int i3 = i2 - i;
        CharSequence charSequenceM5815a = getTextBeforeCursor(i3 + NuanceSDK.MAX_CONTEXT_LENGTH, 0);
        this.mTextBeforeCursor.setLength(0);
        if (!TextUtils.isEmpty(charSequenceM5815a)) {
            int iMax = Math.max(charSequenceM5815a.length() - (this.mCursorStart - i), 0);
            this.mComposingText.append(charSequenceM5815a.subSequence(iMax, charSequenceM5815a.length()));
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
     * @param i cursor position relative to the composing text (1 = end)
     */
    public void setComposingText(CharSequence charSequence, int i) {
        if (BuildConfig.DEBUG) Log.d("TEXT_EDIT_DEBUG", "RIC.setComposingText: newText='" + charSequence + "' cursorPos=" + i + " cursorStart=" + mCursorStart + " prevComposing='" + mComposingText + "'");
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
     * Selects [i, i2). A collapsed move with no composing text inside the cached window shifts
     * the cache ({@link #shiftCachedText}); anything else reloads.
     * @param i the new selection start
     * @param i2 the new selection end
     * @return true if the text context is in place for the new position
     */
    public boolean setSelection(int i, int i2) {
        if (BuildConfig.DEBUG) Log.d("TEXT_EDIT_DEBUG", "RIC.setSelection: [" + i + "," + i2 + "] prevCursor=[" + mCursorStart + "," + mCursorEnd + "] composing='" + mComposingText + "'");
        if (i < 0 || i2 < 0) {
            return false;
        }
        final int fromStart = this.mCursorStart;
        final int fromEnd = this.mCursorEnd;
        final boolean canShift = canShiftCachedText(i, i2);
        // Only a plain caret move is matched against its echo.
        final boolean caretMove = fromStart >= 0 && fromStart == fromEnd && i == i2
                && this.mComposingText.length() == 0 && this.mComposingRegionLength == 0;
        this.mCursorStart = i;
        this.mCursorEnd = i2;
        InputConnection inputConnection = this.mInputConnection;
        if (inputConnection == null || inputConnection.setSelection(i, i2)) {
            if (inputConnection != null && caretMove) {
                recordPendingEcho(fromStart, i);
            }
            if (canShift && inputConnection != null) {
                shiftCachedText(i - fromStart);
                return true;
            }
            return loadTextBeforeCursor();
        }
        return false;
    }

    /**
     * Needs collapsed carets, no composing text ({@link #setSelectionWithinComposing} fixes that
     * layout after a reload), a loaded cache, and a target leaving the shrinking side at least
     * {@link #MIN_LOCAL_SHIFT_WINDOW} chars or reaching the text's end. A zero move (a drag pressing
     * an end) reloads, picking up text the app added there.
     * @param i the new selection start
     * @param i2 the new selection end
     */
    private boolean canShiftCachedText(int i, int i2) {
        final int from = this.mCursorStart;
        if (!this.mTextCacheLoaded || from < 0 || from != this.mCursorEnd || i != i2 || i == from
                || this.mComposingText.length() != 0 || this.mComposingRegionLength != 0) {
            return false;
        }
        final int delta = i - from;
        if (delta < 0) {
            final int before = this.mTextBeforeCursor.length();
            if (-delta > before) {
                return false;
            }
            // before == from: the window already reaches the start of the text.
            return before == from || before + delta >= MIN_LOCAL_SHIFT_WINDOW;
        }
        final int after = this.mTextAfterCursor.length();
        if (delta > after) {
            return false;
        }
        return this.mTextAfterReachesEnd || after - delta >= MIN_LOCAL_SHIFT_WINDOW;
    }

    /** Moves {@code delta} chars across the cursor (positive: after to before) and trims the growing side to load size. */
    private void shiftCachedText(int delta) {
        final StringBuilder before = this.mTextBeforeCursor;
        final StringBuilder after = this.mTextAfterCursor;
        if (delta > 0) {
            before.append(after, 0, delta);
            after.delete(0, delta);
            if (before.length() > NuanceSDK.MAX_CONTEXT_LENGTH) {
                before.delete(0, before.length() - NuanceSDK.MAX_CONTEXT_LENGTH);
            }
        } else {
            final int split = before.length() + delta;
            after.insert(0, before, split, before.length());
            before.setLength(split);
            if (after.length() > NuanceSDK.MAX_CONTEXT_LENGTH) {
                after.setLength(NuanceSDK.MAX_CONTEXT_LENGTH);
                // What was cut is real text past the window.
                this.mTextAfterReachesEnd = false;
            }
        }
    }

    private void recordPendingEcho(int from, int to) {
        if (from == to) {
            return; // An editor reports no change for a move to where it already is.
        }
        if (this.mPendingEchoes.size() >= MAX_PENDING_ECHOES) {
            this.mPendingEchoes.pollFirst();
        }
        this.mPendingEchoes.addLast(new int[]{from, to});
    }

    /**
     * True if the report matches a recorded {from, to} caret move. Drops it and every earlier
     * pair, since reports arrive in send order and those echoes were folded or lost.
     */
    private boolean consumePendingEcho(int oldStart, int newStart, int oldEnd, int newEnd) {
        if (this.mPendingEchoes.isEmpty() || oldStart != oldEnd || newStart != newEnd) {
            return false;
        }
        int index = 0;
        for (int[] echo : this.mPendingEchoes) {
            if (echo[0] == oldStart && echo[1] == newStart) {
                for (int n = 0; n <= index; n++) {
                    this.mPendingEchoes.pollFirst();
                }
                return true;
            }
            index++;
        }
        return false;
    }

    /**
     * Moves the caret inside the composing region. {@link #mTextBeforeCursor} must hold only text
     * before the region, but the reload returns text up to the caret, so the composing prefix is
     * trimmed back off (otherwise readers double-count it).
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

    public void commitCorrection(CorrectionInfo correctionInfo) {
        InputConnection inputConnection = this.mInputConnection;
        if (inputConnection != null) {
            inputConnection.commitCorrection(correctionInfo);
        }
    }

    public void commitCompletion(CompletionInfo completionInfo) {
        CharSequence text = completionInfo.getText();
        if (text == null) {
            text = "";
        }
        this.mTextBeforeCursor.append(text);
        trimTextBeforeCursor();
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
     * Returns a fresh instance every call: commit snapshots keep it and the suggestion worker
     * reads it off the main thread, so it must never be shared. Both parameters are unused.
     * @param c0806f spacing and punctuation rules (unused in current implementation)
     * @param i context depth hint (unused in current implementation)
     */
    public PrevWordsInfo getPrevWordsInfo(SpacingAndPunctuation c0806f, int i) {
        return new PrevWordsInfo(PrevWordsInfo.WordInfo.EMPTY_WORD_INFO, getTextContextBefore());
    }

    /**
     * @param i the code point to test
     * @param c0806f spacing and punctuation rules for word boundary detection
     * @param i2 the script id the word is in
     */
    private static boolean isPartOfWord(int i, SpacingAndPunctuation c0806f, int i2) {
        return c0806f.isWordConnector(i) || (!c0806f.isWordSeparator(i) && ScriptUtils.isLetterPartOfScript(i, i2));
    }

    /**
     * Uses {@code charSequence} as the word when it is exactly the text before the cursor, else {@link #getWordRangeAtCursor}.
     * @param c0806f spacing and punctuation rules for word boundary detection
     * @param i locale flags for word character classification
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
     * Word around the cursor; no-space languages use the tokenizer. {@code i} is the script id. Null if unavailable.
     * @param c0806f spacing and punctuation rules for word boundary detection
     * @param i locale flags for word character classification
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
            List<String> sequence = BlackBerryTokenizer.toSequence(new BlackBerryTokenizer(PrimaryEngineFacts.primaryLanguage()).split(charSequenceM5815a.toString()));
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
     * A word char before the cursor (skipping one trailing connector), or any word char after it.
     * @param c0806f spacing and punctuation rules
     */
    public boolean hasWordBeforeCursor(SpacingAndPunctuation c0806f) {
        if (hasWordAfterCursor(c0806f)) {
            return true;
        }
        // Read in place: this runs at the first letter of every word.
        final StringBuilder before = this.mTextBeforeCursor;
        int length = before.length();
        int iCodePointBefore = length == 0 ? -1 : Character.codePointBefore(before, length);
        if (c0806f.isWordConnector(iCodePointBefore)) {
            int iCharCount = length - Character.charCount(iCodePointBefore);
            iCodePointBefore = iCharCount == 0 ? -1 : Character.codePointBefore(before, iCharCount);
        }
        return (-1 == iCodePointBefore || c0806f.isWordSeparator(iCodePointBefore) || c0806f.isWordConnector(iCodePointBefore)) ? false : true;
    }

    /**
     * @param c0806f spacing and punctuation rules
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
     * {@code str} null means the empty set.
     * @param str a string whose code points form the membership set
     */
    public boolean isCharAfterCursorInSet(String str) {
        CharSequence charSequenceM5830b = getTextAfterCursor(1, 0);
        if (TextUtils.isEmpty(charSequenceM5830b)) {
            return false;
        }
        return (str == null || str.indexOf(Character.codePointAt(charSequenceM5830b, 0)) == -1) ? false : true;
    }

    public void deleteTrailingSpace() {
        if (32 == getCodePointBeforeCursor()) {
            deleteSurroundingText(1, 0);
        }
    }

    public boolean isTextBeforeCursor(CharSequence charSequence) {
        return TextUtils.equals(charSequence, getTextBeforeCursor(charSequence.length(), 0));
    }

    /** Replaces {@code ". "} before the cursor with a space; false if it is not there. */
    public boolean revertDoubleSpacePeriod() {
        if (!TextUtils.equals(". ", getTextBeforeCursor(2, 0))) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Tried to revert double-space combo but we didn't find \". \" just before the cursor.");
            return false;
        }
        deleteSurroundingText(2, 0);
        commitText(" ", 1);
        return true;
    }

    /** Turns {@code "x "} before the cursor into {@code " x"}; false if the pattern is not there. */
    public boolean revertSwapPunctuation() {
        CharSequence charSequenceM5815a = getTextBeforeCursor(2, 0);
        // Length check: one char into the field this returns a single char.
        if (charSequenceM5815a == null || charSequenceM5815a.length() < 2 || ' ' != charSequenceM5815a.charAt(1)) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Tried to revert a swap of punctuation but we didn't find a space just before the cursor.");
            return false;
        }
        deleteSurroundingText(2, 0);
        commitText(" " + ((Object) charSequenceM5815a.subSequence(0, 1)), 1);
        return true;
    }

    /**
     * Whether a report (old i..i3, new i2..i4) tells us nothing new, so onUpdateSelection can skip a reset.
     * @param i old selection start
     * @param i2 new selection start
     * @param i3 old selection end
     * @param i4 new selection end
     */
    public boolean isSelectionUnchanged(int i, int i2, int i3, int i4) {
        final boolean echo = consumePendingEcho(i, i2, i3, i4);
        if (this.mCursorStart == i2 && this.mCursorEnd == i4) {
            return true;
        }
        // A trailing echo of our own move: later moves already updated the cache. Otherwise a
        // reversing drag resets to a stale position.
        if (echo && this.mCursorStart >= 0 && this.mCursorStart == this.mCursorEnd) {
            return true;
        }
        // Cursor unknown: the test below would multiply through -1 and call a real jump (the app
        // clearing the field) unchanged. Take the report unless it says nothing moved.
        if (this.mCursorStart < 0 || this.mCursorEnd < 0) {
            return i == i2 && i3 == i4;
        }
        return !(this.mCursorStart == i && this.mCursorEnd == i3 && (i != i2 || i3 != i4)) && i2 == i4 && (i2 - i) * (this.mCursorStart - i2) >= 0 && (i4 - i3) * (this.mCursorEnd - i4) >= 0;
    }

    /**
     * Asks the editor, not the cache. True only when it answered and both sides are empty (an
     * unreachable editor is not empty). Two round-trips: rare events only.
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
     * Asks the editor whether the composing word's halves still surround the cursor: an app can
     * change text without moving it, so no selection update arrives. False only when the editor
     * answered and disagrees. Two round-trips: rare events only.
     */
    public boolean editorHasTextAroundCursor(String before, String after) {
        this.mInputConnection = this.mImeService.getCurrentInputConnection();
        InputConnection inputConnection = this.mInputConnection;
        if (inputConnection == null) {
            return true;
        }
        CharSequence actualBefore = inputConnection.getTextBeforeCursor(before.length(), 0);
        CharSequence actualAfter = inputConnection.getTextAfterCursor(after.length(), 0);
        if (actualBefore == null || actualAfter == null) {
            return true;
        }
        return before.contentEquals(actualBefore) && after.contentEquals(actualAfter);
    }

    public boolean looksLikeURL() {
        return StringHelper.looksLikeURL(this.mTextBeforeCursor);
    }

    /** Feet/inches like {@code 6'}: suppresses quote swap punctuation. */
    public boolean endsWithQuoteAfterDigit() {
        return StringHelper.endsWithQuoteAfterDigit(this.mTextBeforeCursor);
    }

    /** Re-measures the cursor from the before-cursor text length when near the start, fixing drift. */
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

    /** -1 when not connected. */
    public int getCursorStart() {
        return this.mCursorStart;
    }

    /** -1 when not connected. */
    public int getCursorEnd() {
        return this.mCursorEnd;
    }

    public boolean hasSelection() {
        return this.mCursorEnd != this.mCursorStart;
    }

    public boolean isConnected() {
        return -1 != this.mCursorStart;
    }

    /**
     * {@code z}: CURSOR_UPDATE_MONITOR, {@code z2}: CURSOR_UPDATE_IMMEDIATE. Returns whether the editor accepted.
     * @param z {@code true} to enable monitoring mode (continuous updates)
     * @param z2 {@code true} to request an immediate one-shot update
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

    public boolean isMonitoringCursorUpdates() {
        return this.mMonitorCursorUpdates;
    }

    /** Last 146 chars before the cursor (engine context), or null if empty. Runs twice per keystroke. */
    public String getTextContextBefore() {
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

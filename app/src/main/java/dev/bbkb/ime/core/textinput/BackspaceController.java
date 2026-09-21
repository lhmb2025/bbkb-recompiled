package dev.bbkb.ime.core.textinput;

import static dev.bbkb.ime.core.shared.StringHelper.*;

import android.text.TextUtils;
import android.util.Log;

import dev.bbkb.ime.BuildConfig;
import dev.bbkb.ime.core.textinput.controller.SmartPunctuationAnalyzer;

import dev.bbkb.ime.core.keyevent.InputEvent;
import dev.bbkb.ime.core.keyevent.InputEventContext;
import dev.bbkb.ime.core.keyevent.InputSource;
import dev.bbkb.ime.core.settings.util.SettingsValues;
import dev.bbkb.ime.core.textinput.composing.CommitEventRecord;
import dev.bbkb.ime.core.textinput.connection.RichInputConnection;
import dev.bbkb.ime.core.shared.GraphemeUtils;
import com.blackberry.nuanceshim.NuanceSDK;
import dev.bbkb.ime.core.engine.NuanceSDKManager;
import dev.bbkb.ime.core.textinput.composing.TouchHighlightTracker;

import java.util.List;

/**
 * Handles all backspace and delete operations: single-character backspace,
 * accelerated word-delete, shift+backspace selection delete, and swipe-to-delete.
 *
 * <p>Owns the repeat-count and delete-state fields that were previously scattered
 * across {@link InputLogic}. Also owns the shift-held and backspace-active flags
 * used to gate selection-delete and auto-correct suppression.</p>
 *
 * <p>Extracted from {@link InputLogic} in Phase 4.3 of the text editing reorganization.
 * Calls back into {@link InputLogic} for shared composing and commit operations.</p>
 */
class BackspaceController {

    private static final String TAG = "BackspaceController";

    /**
     * Repeat count at which hold-to-delete stops removing characters and starts removing whole
     * words. Both repeat paths in this class use it: the accelerated one in
     * {@link #handleAcceleratedDelete} and the ordinary one in {@link #handleBackspace}, which
     * differ only in whether gesture input is available.
     */
    private static final int WORD_DELETE_REPEAT_THRESHOLD = 40;

    /**
     * Past this many repeats the accelerated path stops shrinking the gap between word deletes
     * and settles on one word every two repeats.
     */
    private static final int FAST_WORD_DELETE_REPEAT = 44;

    /**
     * Gap, in repeats, between word deletes on the ordinary (non-accelerated) repeat path once
     * {@link #WORD_DELETE_REPEAT_THRESHOLD} is passed.
     */
    private static final int ORDINARY_WORD_DELETE_INTERVAL = 6;

    private final InputLogic mInputLogic;

    int mDeleteRepeatCount;
    int mDeleteWordCount;
    int mDeleteAccelThreshold;
    int mLastDeleteState;
    boolean mIsShiftHeld;
    boolean mIsBackspaceActive;

    /**
     * Creates a new {@code BackspaceController} bound to the given {@link InputLogic} instance.
     *
     * @param inputLogic the owning {@link InputLogic}; used to access shared composing,
     *                   connection, and commit state
     */
    BackspaceController(InputLogic inputLogic) {
        this.mInputLogic = inputLogic;
    }

    /**
     * Resets all delete-repeat counters to their initial state.
     * Called at the start of each new key sequence and on input session reset.
     */
    void resetDeleteCounters() {
        this.mDeleteRepeatCount = 0;
        this.mDeleteWordCount = 0;
        this.mDeleteAccelThreshold = 0;
    }

    /**
     * Fully resets all backspace state: counters, last-delete state, and modifier flags.
     */
    void reset() {
        resetDeleteCounters();
        this.mLastDeleteState = 0;
        this.mIsShiftHeld = false;
        this.mIsBackspaceActive = false;
    }

    /**
     * Determines whether a backspace event should be processed by the IME's composing logic
     * rather than passed through as a raw key event.
     *
     * @param event           the backspace input event
     * @param deleteCursorState current cursor-unavailable flag (1 if cursor unknown, else 0)
     * @param lastDeleteState delete state from the previous backspace event
     * @param ric             the rich input connection
     * @return {@code true} if the IME should handle the backspace; {@code false} to pass through
     */
    boolean shouldProcessBackspace(InputEvent event, int deleteCursorState, int lastDeleteState, RichInputConnection ric) {
        return ric.hasCursorPosition() || !event.isKeyRepeat() || (event.isKeyRepeat() && lastDeleteState == deleteCursorState);
    }

    private void deleteSelection() {
        RichInputConnection ric = mInputLogic.mRichInputConnection;
        int selectionSize = ric.getCursorEnd() - ric.getCursorStart();
        ric.setSelection(ric.getCursorEnd(), ric.getCursorEnd());
        if (selectionSize > 0) {
            ric.deleteSurroundingText(selectionSize, 0);
        } else {
            ric.deleteSurroundingText(0, selectionSize * (-1));
        }
    }

    private boolean deleteByCharacterSwipe(SettingsValues settings, int selectionStart) {
        int composingLength;
        int deletePos;
        RichInputConnection ric = mInputLogic.mRichInputConnection;
        dev.bbkb.ime.core.textinput.composing.ComposingTextTracker ct = mInputLogic.mComposingTracker;
        TouchHighlightTracker tht = mInputLogic.mTouchHighlightTracker;

        if (ct.isComposing() || tht.isHighlightActive()) {
            composingLength = ct.isComposing() ? ct.getCodePointCount() : tht.getPendingCharLength();
            ric.finishComposingText();
            tht.clear();
            ct.clearAll();
        } else {
            composingLength = 0;
        }
        mInputLogic.clearComposingText(true);
        CharSequence textBefore = ric.getTextBeforeCursor(NuanceSDK.MAX_CONTEXT_LENGTH, 0);
        int length = textBefore == null ? 0 : textBefore.length();
        if ((composingLength > 0 ? composingLength : length) == 0) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Failed to get text before cursor despite expected selection start of " + selectionStart);
            return false;
        }
        int codePointBefore = Character.codePointBefore(textBefore, length);
        if (codePointBefore == 10) {
            deletePos = length - 1;
        } else if (length <= 0) {
            deletePos = length;
        } else if (composingLength <= 0) {
            deletePos = (Character.isSupplementaryCodePoint(codePointBefore) ? length - 1 : length) - 1;
        } else {
            deletePos = length - composingLength;
        }
        int deleteCount = length - deletePos;
        if (deleteCount > 0) {
            if (BuildConfig.DEBUG) Log.d(TAG, deleteCount + " chars deleted on swipe.");
            ric.deleteSurroundingText(deleteCount, 0);
            return true;
        }
        if (BuildConfig.DEBUG) Log.w(TAG, "Failed to delete anything on delete gesture despite text before cursor");
        return false;
    }

    private boolean deleteByWordSwipe(SettingsValues settings, int selectionStart) {
        int wordLength;
        int deletePos;
        RichInputConnection ric = mInputLogic.mRichInputConnection;
        dev.bbkb.ime.core.textinput.composing.ComposingTextTracker ct = mInputLogic.mComposingTracker;
        TouchHighlightTracker tht = mInputLogic.mTouchHighlightTracker;

        if (ct.isComposing() || tht.isHighlightActive()) {
            // Delete the CURRENT (uncommitted) word directly, sized from the composing
            // tracker itself. Sizing it from getTextBeforeCursor after
            // finishComposingText is unreliable — the connection's cached text can
            // still exclude the composing region at that point, which made this path
            // tokenize the PREVIOUS word and delete that instead, leaving the
            // just-committed word floating after the cursor.
            String composingWord = ct.getComposingText();
            int beforeLen = ct.getComposingTextBeforeCursor().length();
            int afterLen = composingWord.length() - beforeLen;
            ric.finishComposingText();
            tht.clear();
            ct.clearAll();
            mInputLogic.clearComposingText(true);
            if (composingWord.length() > 0) {
                ric.deleteSurroundingText(beforeLen, Math.max(0, afterLen));
                return true;
            }
        }
        mInputLogic.clearComposingText(true);
        CharSequence textBefore = ric.getTextBeforeCursor(NuanceSDK.MAX_CONTEXT_LENGTH, 0);
        int length = textBefore == null ? 0 : textBefore.length();
        List<String> sequence;
        int size;
        if (length <= 0
                || (size = (sequence = dev.bbkb.ime.personaldictionary.tokenizer.BlackBerryTokenizer.toSequence(
                        new dev.bbkb.ime.personaldictionary.tokenizer.BlackBerryTokenizer(
                                NuanceSDKManager.getInstance().getPrimaryLanguage()).split(textBefore.toString()))).size()) <= 0
                || (wordLength = sequence.get(size - 1).length()) <= 0) {
            wordLength = length;
        }
        if (wordLength == 0) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Failed to get text before cursor despite expected selection start of " + selectionStart);
            return false;
        }
        int cpBefore = Character.codePointBefore(textBefore, length);
        if (cpBefore == 10) {
            deletePos = length - 1;
        } else {
            int cp = cpBefore;
            int pos = length;
            int skipped = 0;
            while (pos > 0 && Character.isWhitespace(cp) && cp != 10) {
                if (Character.isSupplementaryCodePoint(cp)) {
                    pos--;
                }
                pos--;
                if (pos > 0) {
                    cp = Character.codePointBefore(textBefore, pos);
                    skipped++;
                }
            }
            int tokenStart = (length - wordLength) - skipped;
            if (pos <= 0) {
                deletePos = pos;
            } else if (Character.isIdeographic(cp)) {
                if (Character.isSupplementaryCodePoint(cp)) {
                    pos--;
                }
                deletePos = pos - 1;
            } else {
                while (pos > 0 && !Character.isWhitespace(cp) && pos > tokenStart
                        && (pos >= length - 1 || !settings.spacingAndPunctuation.isSwipeDeleteDelimiter(cp))) {
                    if (Character.isSupplementaryCodePoint(cp)) {
                        pos--;
                    }
                    pos--;
                    if (pos > 0) {
                        cp = Character.codePointBefore(textBefore, pos);
                    }
                }
                deletePos = pos;
            }
        }
        int deleteCount = length - deletePos;
        if (deleteCount > 0) {
            if (BuildConfig.DEBUG) Log.d(TAG, deleteCount + " chars deleted on swipe.");
            ric.deleteSurroundingText(deleteCount, 0);
            // Unlearn: a swipe-deleted word is an explicit rejection. Remove it from the
            // dynamic language model so bad commits (especially misrecognized gestures)
            // stop self-reinforcing — without this, every accepted misrecognition
            // permanently boosts itself and the DLM degenerates (the 'help'-over-'hello'
            // pathology). Deleting from the DLM never touches the static lexicon.
            String deletedWord = textBefore.subSequence(deletePos, length).toString().trim();
            if (!deletedWord.isEmpty()) {
                com.blackberry.nuanceshim.NuanceSDK sdk = NuanceSDKManager.getInstance();
                if (sdk != null && !sdk.deleteDLMWord(deletedWord)) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "swipe-delete unlearn: '" + deletedWord + "' not in DLM");
                }
            }
            return true;
        }
        if (BuildConfig.DEBUG) Log.w(TAG, "Failed to delete anything on delete gesture despite text before cursor");
        return false;
    }

    private boolean deleteWord(InputEventContext context) {
        return handleSwipeDelete(context.settingsValues);
    }

    /**
     * Handles a swipe-delete gesture. Deletes the selection if active, deletes to the beginning
     * in password fields, or deletes by character (CJK) or by word (other locales).
     *
     * @param settings current settings values
     * @return {@code true} if any text was deleted
     */
    boolean handleSwipeDelete(SettingsValues settings) {
        mInputLogic.mHasModifiedEvent = false;
        RichInputConnection ric = mInputLogic.mRichInputConnection;
        if (ric.hasSelection()) {
            deleteSelection();
            return true;
        }
        int selStart = ric.getCursorStart();
        if (selStart <= 0) {
            return false;
        }
        if (mInputLogic.isPasswordField()) {
            mInputLogic.cancelComposingAndTouchEvent();
            ric.deleteSurroundingText(selStart, 0);
            return true;
        }
        if (mInputLogic.isCjkLocale()) {
            return deleteByCharacterSwipe(settings, selStart);
        }
        return deleteByWordSwipe(settings, selStart);
    }

    /**
     * Removes one input event's worth of text from the LIVE composing word and republishes the
     * shortened word to the editor. The one place either repeat path may shorten a composing
     * word: {@link #handleBackspace}'s ordinary branch and {@link #handleAcceleratedDelete}'s
     * repeat branch both call it, so the two cannot drift apart again.
     *
     * <p>A composing word must never be shortened with
     * {@link RichInputConnection#deleteSurroundingText}: that call is defined to leave the
     * composing text alone and delete the text <em>around</em> it, so it would eat the
     * already-committed sentence to the left while the half-typed word stayed put.</p>
     *
     * <p>Callers are responsible for the guards: composing is live, the word was not
     * auto-corrected (both callers commit that word before deleting anything, so the region is
     * finished by the time text is deleted around it), and the password / all-digits
     * cancellation at the top of {@link #handleBackspace} has already run.</p>
     *
     * @param event   the backspace input event, handed to the tracker to shorten its buffer
     * @param context the current input event context
     */
    private void deleteFromComposingText(InputEvent event, InputEventContext context) {
        RichInputConnection ric = mInputLogic.mRichInputConnection;
        dev.bbkb.ime.core.textinput.composing.ComposingTextTracker ct = mInputLogic.mComposingTracker;
        if (ct.isPredictionMode()) {
            String composingText = ct.getComposingText();
            ct.clearAll();
            ct.setLastCommittedWord(composingText);
            if (!TextUtils.isEmpty(composingText)) {
                mInputLogic.mDictionaryLoader.unlearnWord(composingText);
            }
        } else {
            ct.processInputEvent(event);
        }
        if (ct.isComposing()) {
            mInputLogic.setComposingTextInternal(mInputLogic.getComposingTextWithIndicator(ct.getComposingText()), 1);
        } else {
            ric.commitText("", 1);
            ct.resetRecorrection();
        }
        context.setShouldUpdateSuggestions();
    }

    /**
     * Handles accelerated (key-repeat) deletion: deletes forward or backward by character,
     * and escalates to word-delete as the repeat count increases. Also triggers recorrection
     * when predictions are enabled and the cursor lands beside a word.
     *
     * @param event       the backspace input event (needed to shorten a live composing word)
     * @param shiftHeld   {@code true} if Shift is held (deletes forward instead of backward)
     * @param context     the current input event context
     * @param symbolPage  symbol page order / shift state passed to recorrection
     */
    private void handleAcceleratedDelete(InputEvent event, boolean shiftHeld, InputEventContext context, int symbolPage) {
        RichInputConnection ric = mInputLogic.mRichInputConnection;
        dev.bbkb.ime.core.textinput.composing.ComposingTextTracker ct = mInputLogic.mComposingTracker;
        // FIX-BKSP, second entrance to the same hole. The ordinary path opens its `else` arm by
        // committing an auto-corrected composing word before it deletes anything; this path has
        // to do the same, because everything below deletes AROUND a live composing region
        // rather than out of it. Reachable after a swiped word: the first (non-repeat) press
        // recorrects and stamps wasAutoCorrected, and the repeats that follow land here with a
        // composing region open, whereupon the character delete ate the space and the word in
        // front of it. No revert here: the first press of a hold is never a repeat, so the
        // auto-correct revert has already had its keystroke.
        if (ct.isComposing() && ct.wasAutoCorrected()) {
            mInputLogic.commitWord(context.settingsValues, ct.getComposingText(),
                    CommitEventRecord.CommitType.DECIDED_WORD, "", InputSource.UNKNOWN);
        }
        boolean deleted = false;
        int cpBefore = ric.getCodePointBeforeCursor();
        int cpAfter = ric.getCodePointAfterCursor();
        // Forward-delete only on Shift. The original condition also forward-deleted when
        // cpBefore == -1, but some editors return "" for getTextBeforeCursor() while a
        // composing span is active, making cpBefore == -1 even with text to the left —
        // the same quirk already fixed in the non-repeat path below (see handleBackspace).
        if (cpAfter != -1 && shiftHeld) {
            ric.deleteSurroundingText(0, Character.isSupplementaryCodePoint(cpAfter) ? 2 : 1);
            return;
        }
        if (!mInputLogic.mComposingTracker.isComposing() && cpBefore == -1) {
            ric.deleteSurroundingText(1, 0);
            return;
        }
        // F3 defect 4: the repeat schedule.
        //
        // This used to arm mDeleteAccelThreshold to 2 on the first repeat and delete NOTHING,
        // delete a whole WORD on the second, then push the threshold to 11 so that the next
        // eight repeats deleted nothing at all. Holding backspace on a gesture-capable device
        // therefore never removed a single character: one swallowed repeat, then words.
        //
        // Hold-to-delete is meant to escalate — characters first, words once the hold has gone
        // on long enough. The escalation point is WORD_DELETE_REPEAT_THRESHOLD, the count at
        // which the non-accelerated repeat path further down handleBackspace makes exactly the
        // same switch. The two paths differ only in whether gesture input happens to be ready,
        // which has nothing to do with how fast delete should escalate, so they use one number.
        if (this.mDeleteWordCount == 0 && this.mDeleteAccelThreshold == 0) {
            this.mDeleteAccelThreshold = WORD_DELETE_REPEAT_THRESHOLD;
        }
        if (this.mDeleteRepeatCount >= this.mDeleteAccelThreshold) {
            deleteWord(context);
            this.mDeleteWordCount++;
            // Word deletes then come closer together — gaps of 9, 8, 7 ... settling at a flat 2
            // once the hold passes FAST_WORD_DELETE_REPEAT. Re-seeded from the live repeat
            // count rather than accumulated onto the old threshold: with the `>=` test above, an
            // accumulated threshold could fall behind the count and fire a word delete on every
            // repeat. (The original `==` test had the opposite failure mode — a single missed
            // repeat stopped word deletes for the rest of the hold.)
            int gap = this.mDeleteRepeatCount >= FAST_WORD_DELETE_REPEAT
                    ? 2
                    : Math.max(2, 10 - this.mDeleteWordCount);
            this.mDeleteAccelThreshold = this.mDeleteRepeatCount + gap;
            deleted = true;
        } else if (ct.isComposing() && !ct.wasAutoCorrected()) {
            // FIX-BKSP: a repeat over a half-typed word shortens THAT word, exactly as the
            // ordinary (non-repeat) branch of handleBackspace does — same guards, same code.
            // The character delete below would call deleteSurroundingText with the composing
            // region still live, which by contract deletes the text AROUND the region: holding
            // backspace ate the committed sentence to the left of the word one character at a
            // time while the word itself never changed.
            //
            // Returns rather than falling through to the recorrection call: the ordinary path's
            // composing branch does not recorrect either (it is an `else if` around it). A
            // word that is still being composed already owns the suggestion strip, which
            // setShouldUpdateSuggestions refreshes.
            deleteFromComposingText(event, context);
            return;
        } else if (cpBefore != -1) {
            // Below the escalation point every repeat takes one grapheme cluster — the same unit
            // and the same scan the non-accelerated path uses, so an emoji goes in one repeat
            // rather than leaving a lone surrogate behind.
            ric.deleteSurroundingText(
                    GraphemeUtils.getGraphemeLengthBeforeCursor(ric.getTextBeforeCursor(48, 0), cpBefore), 0);
            // `deleted` deliberately stays false: a character repeat still falls through to the
            // recorrection call below, exactly as a character delete does on the non-accelerated
            // path. Only a word delete suppresses it.
        }
        if (deleted || !context.settingsValues.isPredictionsEnabled
                || !context.settingsValues.spacingAndPunctuation.currentLanguageHasSpaces
                || ric.hasWordAfterCursor(context.settingsValues.spacingAndPunctuation)) {
            return;
        }
        mInputLogic.mComposingTracker.setWasAutoCorrected(false);
        mInputLogic.performRecorrection(context.settingsValues, true, symbolPage);
    }

    /**
     * Handles a backspace event through the full delete pipeline: composing-text backspace,
     * accelerated delete on repeat, selection delete, auto-correction revert, and raw deletion.
     *
     * @param event     the backspace input event
     * @param context   the current input event context
     * @param settings  current settings values
     * @param symbolPage symbol page order / shift state
     */
    void handleBackspace(InputEvent event, InputEventContext context, SettingsValues settings, int symbolPage) {
        int iM5844h;
        int length;
        RichInputConnection ric = mInputLogic.mRichInputConnection;
        dev.bbkb.ime.core.textinput.composing.ComposingTextTracker ct = mInputLogic.mComposingTracker;

        if (ct.isComposing() && (mInputLogic.isPasswordField() || ct.isAllDigits())) {
            mInputLogic.cancelComposingAndTouchEvent();
        }
        boolean batchAutoCorrect = false;
        mInputLogic.mCommitType = 0;
        if (context.getInputSource() != InputSource.INTERNAL && context.getInputSource() != InputSource.UNKNOWN) {
            this.mDeleteRepeatCount++;
        }
        boolean shiftHeld = this.mIsShiftHeld || context.isShiftPressed();
        if (BuildConfig.DEBUG) Log.d("TEXT_EDIT_DEBUG", "handleBackspace: isComposing=" + ct.isComposing() + " composing='" + ct.getComposingText() + "' isCursorMoved=" + ct.isCursorMoved() + " wasAutoCorrected=" + ct.wasAutoCorrected() + " shiftHeld=" + shiftHeld + " cursor=[" + ric.getCursorStart() + "," + ric.getCursorEnd() + "]");
        context.setUiUpdateMode((!event.isKeyRepeat() || ric.getCursorStart() <= 0) ? 1 : 2);
        if (shiftHeld && ct.isComposing()) {
            mInputLogic.resetComposingAndSelect(ric.getCursorStart(), ric.getCursorEnd(), true);
        }
        if (ct.isCursorMoved() && ct.isComposing()) {
            // Cursor is positioned inside the composing word. Finish composing
            // (the text in the editor stays; only the composing span is cleared)
            // and fall through to the standard backspace path so a character
            // is actually deleted. Previously this branch truncated the composing
            // buffer to the cursor and returned, which made the first backspace
            // a no-op visible to the user.
            int cursorStart = ric.getCursorStart();
            int cursorEnd = ric.getCursorEnd();
            if (BuildConfig.DEBUG) {
            Log.d("TEXT_EDIT_DEBUG", "handleBackspace: cursorMoved in composing -> resetComposingAndSelect, then normal delete"
                    + " composing='" + ct.getComposingText() + "'"
                    + " composingCursorPos=" + ct.getComposingCursorPos()
                    + " cursor=[" + cursorStart + "," + cursorEnd + "]");
            }
            mInputLogic.resetComposingAndSelect(cursorStart, cursorEnd, false);
            // Fall through.
        }
        if (ric.hasSelection()) {
            deleteSelection();
            return;
        }
        boolean hasNoCoordinates = event.hasNoCoordinates();
        if (event.isKeyRepeat() && ((mInputLogic.mIme.isGestureInputReady() && hasNoCoordinates) || (mInputLogic.mIme.isVkbGestureAvailable() && !hasNoCoordinates))) {
            handleAcceleratedDelete(event, shiftHeld, context, symbolPage);
        } else if (ct.isComposing() && !ct.wasAutoCorrected()) {
            deleteFromComposingText(event, context);
        } else {
            if (ct.isComposing() && ct.wasAutoCorrected()) {
                mInputLogic.commitWord(context.settingsValues, ct.getComposingText(), CommitEventRecord.CommitType.DECIDED_WORD, "", InputSource.UNKNOWN);
            }
            int cpBefore = ric.getCodePointBeforeCursor();
            // F3 defect 3: the revert-auto-correction gate.
            //
            // It used to additionally require that the code point before the cursor was NOT a
            // word separator, which is unreachable in practice. An auto-correction is committed
            // together with the separator the user typed to end the word (handleSeparatorInput
            // -> autoCorrectAndCommit -> commitWordExtended commits "the" and then " "), so by
            // the time backspace arrives the character before the cursor IS that separator.
            // Backspace-to-undo-a-correction therefore never fired from the keyboard at all -
            // only through the swipe-delete coordinate below, which bypasses the whole gate.
            // The give-away was that the isUsuallyFollowedBySpace() term could not affect the
            // result: every symbol in symbols_followed_by_space is a word separator too, so the
            // separator test already implied it. The expression had been written against the
            // helper's NAME rather than its (inverted) value.
            //
            // What survives is the exclusion that second term was written for: when the cursor
            // sits right after one of ".,;:!?)]}&" the keystroke must fall through to the
            // double-space-period / swap-punctuation reverts further down this method, which
            // own the undo for those. Everything else - the committed word itself, or the plain
            // separator committed with it - reverts.
            boolean isFollowedBySpaceSymbol = settings.isUsuallyFollowedBySpace(cpBefore);
            boolean revertGateOpen = !isFollowedBySpaceSymbol;
            if (mInputLogic.mEventDispatcher.isRevertEligible() && (revertGateOpen || event.mX == InputEvent.COORD_SWIPE_DELETE_REVERT)) {
                mInputLogic.revertAutoCorrection(context, context.settingsValues);
                return;
            }
            String lastCommitted = mInputLogic.mLastCommittedText;
            if (lastCommitted != null && !mInputLogic.mLastCommitFromVoice && ric.isTextBeforeCursor((CharSequence) lastCommitted)) {
                if (dev.bbkb.ime.core.shared.EmojiTextAnalyzer.isEmoji(mInputLogic.mLastCommittedText)) {
                    length = GraphemeUtils.getGraphemeLengthBeforeCursor(ric.getTextBeforeCursor(48, 0), ric.getCodePointBeforeCursor());
                } else {
                    length = mInputLogic.mLastCommittedText.length();
                }
                ric.deleteSurroundingText(length, 0);
                mInputLogic.mLastCommittedText = null;
                return;
            }
            if (1 == context.commitType) {
                mInputLogic.resetSpaceTimestamp();
                if (ric.revertDoubleSpacePeriod()) {
                    context.setShouldUpdateSuggestions();
                    ct.setShiftState(0);
                    return;
                }
            } else if (2 == context.commitType && ric.revertSwapPunctuation()) {
                return;
            }
            if (-1 == ric.getCursorEnd()) {
                if (BuildConfig.DEBUG) Log.e(TAG, "Backspace when we don't know the selection position");
            }
            if (context.settingsValues.isBeamReaderPackage()
                    || -1 == ric.getCursorEnd()
                    || (ric.getCursorEnd() == 0 && ric.getCodePointAfterCursor() == -1 && ric.getCodePointBeforeCursor() == -1)) {
                mInputLogic.sendKeyEvent(67);
                if (this.mDeleteRepeatCount > 20) {
                    mInputLogic.sendKeyEvent(67);
                }
            } else {
                int cpBefore2 = ric.getCodePointBeforeCursor();
                int cpAfter = ric.getCodePointAfterCursor();
                // Removed automatic forward-delete trigger (cpBefore2 == -1).
                // When cursor is repositioned mid-word via tap, some editors return "" for
                // getTextBeforeCursor() while a composing span is still active (they return
                // text before the composing span start rather than text before the cursor position).
                // This made cpBefore2 == -1 even with text to the left of the cursor, causing
                // backspace to forward-delete the character to the RIGHT instead of the left
                // (original condition: cpAfter != -1 && (shiftHeld || cpBefore2 == -1)).
                // Intentional forward delete remains available via Shift+Backspace (shiftHeld).
                if (cpAfter != -1 && shiftHeld) {
                    ric.deleteSurroundingText(0, GraphemeUtils.getGraphemeLengthAfterCursor(ric.getTextAfterCursor(48, 0), cpAfter));
                } else {
                    if (cpBefore2 == -1) {
                        ric.deleteSurroundingText(1, 0);
                        return;
                    }
                    int repeatCount = this.mDeleteRepeatCount;
                    if (repeatCount >= WORD_DELETE_REPEAT_THRESHOLD) {
                        if ((repeatCount - WORD_DELETE_REPEAT_THRESHOLD) % ORDINARY_WORD_DELETE_INTERVAL == 0) {
                            deleteWord(context);
                        }
                    } else {
                        ric.deleteSurroundingText(GraphemeUtils.getGraphemeLengthBeforeCursor(ric.getTextBeforeCursor(48, 0), cpBefore2), 0);
                        if (this.mDeleteRepeatCount > 20 && (iM5844h = ric.getCodePointBeforeCursor()) != -1) {
                            ric.deleteSurroundingText(Character.isSupplementaryCodePoint(iM5844h) ? 2 : 1, 0);
                        }
                    }
                }
            }
            if (context.settingsValues.isPredictionsEnabled
                    && context.settingsValues.spacingAndPunctuation.currentLanguageHasSpaces
                    // Same gate as above, so recorrection is skipped only for the one case that
                    // reached here while still revert-eligible (a followed-by-space symbol,
                    // which the punctuation reverts own).
                    && ((!mInputLogic.mEventDispatcher.isRevertEligible() || revertGateOpen)
                        && context.getInputSource() != InputSource.INTERNAL)) {
                mInputLogic.performRecorrection(context.settingsValues, true, symbolPage);
                if (mInputLogic.mEventDispatcher.isBatchInput() && !ct.wasAutoCorrected()) {
                    batchAutoCorrect = true;
                }
                ct.setWasAutoCorrected(batchAutoCorrect);
            }
        }
        SmartPunctuationAnalyzer smartPunctuation = mInputLogic.mSmartPunctuationAnalyzer;
        if (smartPunctuation != null && smartPunctuation.isDumbMode() && ct.isComposing()) {
            mInputLogic.mSmartPunctuationAnalyzer.clearDumbMode();
        }
        if (ct.isComposing() && ct.isSingleCodePoint()) {
            ct.setShiftState(context.symbolPageOrder);
        }
    }
}

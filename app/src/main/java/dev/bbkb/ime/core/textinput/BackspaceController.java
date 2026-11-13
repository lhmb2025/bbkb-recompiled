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
import dev.bbkb.ime.core.engine.EngineOps;
import dev.bbkb.ime.core.engine.PrimaryEngineFacts;
import dev.bbkb.ime.core.textinput.composing.TouchHighlightTracker;

import java.util.List;

/**
 * Backspace and delete: character backspace, hold-to-delete escalation, Shift forward delete,
 * selection delete and swipe-to-delete. Owns the repeat counters and the shift-held and
 * backspace-active flags.
 */
class BackspaceController {

    private static final String TAG = "BackspaceController";

    /** Repeat count where hold-to-delete switches from characters to words, on both repeat paths. */
    private static final int WORD_DELETE_REPEAT_THRESHOLD = 40;

    /** Past this many repeats the accelerated path deletes a word every two repeats. */
    private static final int FAST_WORD_DELETE_REPEAT = 44;

    /** Repeats between word deletes on the ordinary repeat path past the threshold. */
    private static final int ORDINARY_WORD_DELETE_INTERVAL = 6;

    private final InputLogic mInputLogic;

    int mDeleteRepeatCount;
    int mDeleteWordCount;
    int mDeleteAccelThreshold;
    int mLastDeleteState;
    boolean mIsShiftHeld;
    boolean mIsBackspaceActive;

    BackspaceController(InputLogic inputLogic) {
        this.mInputLogic = inputLogic;
    }

    /** Called at the start of each key sequence and on session reset. */
    void resetDeleteCounters() {
        this.mDeleteRepeatCount = 0;
        this.mDeleteWordCount = 0;
        this.mDeleteAccelThreshold = 0;
    }

    void reset() {
        resetDeleteCounters();
        this.mLastDeleteState = 0;
        this.mIsShiftHeld = false;
        this.mIsBackspaceActive = false;
    }

    /**
     * Whether the IME handles this backspace rather than passing the raw key through.
     *
     * @param deleteCursorState 1 if the cursor position is unknown, else 0
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
            // Size the delete from the tracker: after finishComposingText the cached text can
            // still exclude the composing region, so tokenizing would hit the previous word.
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
                                PrimaryEngineFacts.primaryLanguage()).split(textBefore.toString()))).size()) <= 0
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
            // A swipe-deleted word is a rejection: unlearn it from the DLM so misrecognitions
            // do not self-reinforce. The static lexicon is untouched.
            final String deletedWord = textBefore.subSequence(deletePos, length).toString().trim();
            if (!deletedWord.isEmpty()) {
                EngineOps.post("swipeDeleteUnlearn", sdk -> {
                    if (!sdk.deleteDLMWord(deletedWord)) {
                        if (BuildConfig.DEBUG) Log.d(TAG, "swipe-delete unlearn: '" + deletedWord + "' not in DLM");
                    }
                });
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
     * Swipe-delete: the selection, everything before the cursor in password fields, else one
     * character (CJK) or one word. Returns whether anything was deleted.
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
     * Shortens the live composing word and republishes it; the only way either path may do so.
     * Never use {@link RichInputConnection#deleteSurroundingText} on a composing word: it
     * deletes around the region. Callers check composing, not auto-corrected, password/digits.
     */
    private void deleteFromComposingText(InputEvent event, InputEventContext context) {
        RichInputConnection ric = mInputLogic.mRichInputConnection;
        dev.bbkb.ime.core.textinput.composing.ComposingTextTracker ct = mInputLogic.mComposingTracker;
        if (ct.isPredictionMode()) {
            // A word held open after a gesture goes in one keystroke, a rejection: unlearn it.
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
     * Uses the tracked cursor, never the before-cursor text, which some editors return empty
     * while a composing span is active mid-word.
     */
    private static boolean isAtStartOfField(RichInputConnection ric) {
        return ric.getCursorStart() == 0 && !ric.hasSelection();
    }

    /** Key-repeat delete on gesture-capable input: characters first, then escalating to words. */
    private void handleAcceleratedDelete(InputEvent event, boolean shiftHeld, InputEventContext context, int symbolPage) {
        RichInputConnection ric = mInputLogic.mRichInputConnection;
        dev.bbkb.ime.core.textinput.composing.ComposingTextTracker ct = mInputLogic.mComposingTracker;
        // Commit an auto-corrected composing word first: everything below deletes around a live
        // region. No revert here; the first press of a hold already had it.
        if (ct.isComposing() && ct.wasAutoCorrected()) {
            mInputLogic.commitWord(context.settingsValues, ct.getComposingText(),
                    CommitEventRecord.CommitType.DECIDED_WORD, "", InputSource.UNKNOWN);
        }
        boolean deleted = false;
        int cpBefore = ric.getCodePointBeforeCursor();
        int cpAfter = ric.getCodePointAfterCursor();
        // Forward delete on Shift or at the start of the field (see isAtStartOfField).
        if (cpAfter != -1 && (shiftHeld || isAtStartOfField(ric))) {
            ric.deleteSurroundingText(0, Character.isSupplementaryCodePoint(cpAfter) ? 2 : 1);
            return;
        }
        if (!mInputLogic.mComposingTracker.isComposing() && cpBefore == -1) {
            ric.deleteSurroundingText(1, 0);
            return;
        }
        if (this.mDeleteWordCount == 0 && this.mDeleteAccelThreshold == 0) {
            this.mDeleteAccelThreshold = WORD_DELETE_REPEAT_THRESHOLD;
        }
        if (this.mDeleteRepeatCount >= this.mDeleteAccelThreshold) {
            deleteWord(context);
            this.mDeleteWordCount++;
            // Gaps of 9, 8, 7 ... down to 2. Re-seeded from the live count: an accumulated
            // threshold could fall behind it and fire on every repeat.
            int gap = this.mDeleteRepeatCount >= FAST_WORD_DELETE_REPEAT
                    ? 2
                    : Math.max(2, 10 - this.mDeleteWordCount);
            this.mDeleteAccelThreshold = this.mDeleteRepeatCount + gap;
            deleted = true;
        } else if (ct.isComposing() && !ct.wasAutoCorrected()) {
            // Shorten the composing word itself; no recorrection, as on the ordinary path.
            deleteFromComposingText(event, context);
            return;
        } else if (cpBefore != -1) {
            // One grapheme cluster per repeat, so an emoji never leaves a lone surrogate.
            ric.deleteSurroundingText(
                    GraphemeUtils.getGraphemeLengthBeforeCursor(ric.getTextBeforeCursor(48, 0), cpBefore), 0);
            // `deleted` stays false: only a word delete suppresses recorrection.
        }
        if (deleted || !context.settingsValues.isPredictionsEnabled
                || !context.settingsValues.spacingAndPunctuation.currentLanguageHasSpaces
                || ric.hasWordAfterCursor(context.settingsValues.spacingAndPunctuation)) {
            return;
        }
        mInputLogic.mComposingTracker.setWasAutoCorrected(false);
        mInputLogic.performRecorrection(context.settingsValues, true, symbolPage);
    }

    /** The full backspace pipeline: composing, repeat, selection, revert, then raw delete. */
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
            // Cursor inside the composing word: drop the span and fall through so a character
            // is actually deleted.
            int cursorStart = ric.getCursorStart();
            int cursorEnd = ric.getCursorEnd();
            if (BuildConfig.DEBUG) {
            Log.d("TEXT_EDIT_DEBUG", "handleBackspace: cursorMoved in composing -> resetComposingAndSelect, then normal delete"
                    + " composing='" + ct.getComposingText() + "'"
                    + " composingCursorPos=" + ct.getComposingCursorPos()
                    + " cursor=[" + cursorStart + "," + cursorEnd + "]");
            }
            mInputLogic.resetComposingAndSelect(cursorStart, cursorEnd, false);
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
            // Revert the auto-correction unless the cursor follows one of ".,;:!?)]}&", whose
            // undo belongs to the double-space-period / swap-punctuation reverts below. The
            // keyboard revert also drops a whitespace separator; swipe-delete keeps it.
            boolean isFollowedBySpaceSymbol = settings.isUsuallyFollowedBySpace(cpBefore);
            boolean revertGateOpen = !isFollowedBySpaceSymbol;
            boolean isSwipeDeleteRevert = event.mX == InputEvent.COORD_SWIPE_DELETE_REVERT;
            if (mInputLogic.mEventDispatcher.isRevertEligible() && (revertGateOpen || isSwipeDeleteRevert)) {
                mInputLogic.revertAutoCorrection(context, context.settingsValues, !isSwipeDeleteRevert);
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
                // Forward delete on Shift or at the start of the field (see isAtStartOfField).
                if (cpAfter != -1 && (shiftHeld || isAtStartOfField(ric))) {
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
                    // Same gate as the revert above.
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

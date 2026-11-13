package dev.bbkb.ime.core.textinput;

import android.text.TextUtils;
import android.util.Log;
import android.view.inputmethod.CorrectionInfo;

import dev.bbkb.ime.BuildConfig;
import dev.bbkb.ime.core.ime.UIUpdateHandler;
import dev.bbkb.ime.core.engine.EngineOps;
import dev.bbkb.ime.core.keyevent.InputEventContext;
import dev.bbkb.ime.core.keyevent.InputSource;
import dev.bbkb.ime.core.keyevent.ModifierResetReason;
import dev.bbkb.ime.core.settings.util.SettingsValues;
import dev.bbkb.ime.core.suggestion.PrevWordsInfo;
import dev.bbkb.ime.core.suggestion.SuggestedWords;
import dev.bbkb.ime.core.textinput.composing.CommitEventRecord;
import dev.bbkb.ime.core.locale.LocaleUtils;
import dev.bbkb.ime.core.suggestion.SuggestionSpanBuilder;
import dev.bbkb.ime.keyboard.KeyboardSwitcher;

/**
 * The commit pipeline: one execution path ({@link #execute}) fed by a {@link CommitRequest}, plus
 * adapters that resolve each caller's intent (correction, marker stripping, separator) into a
 * request. The auto-correct gate runs at two sites, {@link InputLogic#handleSeparatorInput} and
 * {@link #autoCorrectAndCommitExtended}, because they see different inputs.
 */
class CommitController {

    private static final String TAG = "CommitController";

    /** Engine suggestion markers, a lower/upper pair; both are stripped before commit. */
    private static final String MARKER_BOUNDARY_LOWER = "%b";
    private static final String MARKER_BOUNDARY = "%B";

    /** Suggestion kind for a personal-dictionary or shipped substitution macro. */
    static final int KIND_SUBSTITUTION = 7;

    /**
     * Macros (kind 7) expand even with auto-correct off, but never in fields that disallow
     * suggestions (password, email, URI, {@code NO_SUGGESTIONS}). Other kinds need auto-correct
     * on; kind 0 (the typed word) never commits as a correction.
     */
    static boolean shouldCommitAutoCorrectCandidate(String acWord, int kind, boolean autoCorrectEnabled,
            boolean suggestionsAllowedInEditor) {
        if (acWord == null) {
            return false;
        }
        // Kind 7 must not fall through to the auto-correct term, which would re-admit it.
        if (kind == KIND_SUBSTITUTION) {
            return suggestionsAllowedInEditor;
        }
        return autoCorrectEnabled && kind != 0;
    }

    private final InputLogic mInputLogic;

    CommitController(InputLogic inputLogic) {
        this.mInputLogic = inputLogic;
    }

    /**
     * Writes one resolved request to the editor. {@code settings} may be null only for RAW;
     * {@code handler} and {@code eventContext} are required only for PREDICTION_WORD.
     */
    CommitResult execute(SettingsValues settings, CommitRequest request,
            UIUpdateHandler handler, InputEventContext eventContext) {
        switch (request.protocol) {
            case RAW:
                return executeRaw(request);
            case PREDICTION_WORD:
                return executePredictionWord(settings, request, handler, eventContext);
            case WORD:
            default:
                return executeWord(settings, request);
        }
    }

    private CommitResult executeRaw(CommitRequest request) {
        mInputLogic.mRichInputConnection.commitText(request.text, 1);
        return new CommitResult(request.text.length(), false, true);
    }

    /**
     * Span-decorated word commit with revert record and learning. AUTO_CORRECT adds the marker
     * tail delete and {@link CorrectionInfo}, each only when the request carries its field.
     */
    private CommitResult executeWord(SettingsValues settings, CommitRequest request) {
        final String word = request.text;
        SuggestedWords currentSuggestions = mInputLogic.mCurrentSuggestions;
        if (BuildConfig.DEBUG) {
            android.util.Log.d("SUGG_COMMIT_DEBUG", "execute: " + request
                    + " currentSuggestionsSize=" + currentSuggestions.size()
                    + " composing='" + mInputLogic.mComposingTracker.getComposingText() + "'");
        }
        boolean isBatchInput = request.commitType == CommitEventRecord.CommitType.BATCH_INPUT_WORD;
        // With learning off the commit still carries its alternatives, but nothing learns.
        boolean learningAllowed = mInputLogic.mIme.getDynamicLearningManager().isLearningAllowed();
        CharSequence payload = request.withSuggestionSpan
                ? SuggestionSpanBuilder.getTextWithSuggestionSpan(
                        mInputLogic.mIme, word, currentSuggestions, isBatchInput, learningAllowed)
                : word;
        PrevWordsInfo prevWordsInfo = mInputLogic.mRichInputConnection.getPrevWordsInfo(
                settings.spacingAndPunctuation, mInputLogic.mComposingTracker.isComposing() ? 2 : 1);
        // Consume-once: a stale flag would skip the cursor past a character that isn't there.
        boolean shouldAppendSpace = mInputLogic.mShouldAppendSpace;
        mInputLogic.mShouldAppendSpace = false;
        mInputLogic.mRichInputConnection.commitText(payload, shouldAppendSpace ? 2 : 1);
        int length = payload.length();
        final String separator = request.trailingSeparator;
        boolean separatorCommitted = false;
        if (separator.codePointCount(0, separator.length()) == 1
                && ((mInputLogic.isWordSeparator(settings, separator.codePointAt(0))
                        || request.forceSeparator) && !shouldAppendSpace)) {
            separatorCommitted = true;
        }
        if (separatorCommitted) {
            mInputLogic.mRichInputConnection.commitText(separator, 1);
            length += separator.length();
            if (request.commitType == CommitEventRecord.CommitType.MANUAL_PICK || isBatchInput) {
                mInputLogic.mHasModifiedEvent = true;
            }
        }
        mInputLogic.mEventDispatcher = mInputLogic.mComposingTracker.createEventDispatcher(
                request.commitType, payload, separator, prevWordsInfo);
        SuggestedWords.SuggestedWordInfo matched =
                SuggestedWords.findByNuanceWord(currentSuggestions, word);
        if (learningAllowed && matched != null && !TextUtils.isEmpty(word)
                && word.equalsIgnoreCase(matched.word) && !word.equals(matched.word)) {
            // Record the user's casing in the DLM. Posted ahead of the learn op below.
            final String casedWord = word;
            EngineOps.post("casingAddWord", sdk -> sdk.addWord(casedWord));
        }
        if (request.learn) {
            // Always end the word in the engine, or the next request returns completions of
            // the committed word instead of next-word predictions.
            if (matched != null || isBatchInput) {
                mInputLogic.mIme.getDynamicLearningManager().learn(matched);
            } else {
                // No candidate carried this word: learn it by content, which also ends it.
                mInputLogic.mIme.getDynamicLearningManager().learnTypedWord(word);
            }
        }
        mInputLogic.mTouchHighlightTracker.clear();
        if (mInputLogic.mSmartPunctuationAnalyzer != null) {
            mInputLogic.mSmartPunctuationAnalyzer.clearDumbMode();
        }
        // Otherwise readers before the next-word predictions arrive (e.g. the empty-prediction
        // fallback) keep showing the committed word's suggestions.
        mInputLogic.mCurrentSuggestions = SuggestedWords.EMPTY;

        if (request.reason == CommitRequest.Reason.AUTO_CORRECT) {
            if (request.rawCandidate != null && request.rawCandidate.endsWith(MARKER_BOUNDARY)) {
                mInputLogic.mRichInputConnection.deleteSurroundingText(1, 0);
            }
            if (request.typedWordBefore != null && !request.typedWordBefore.equals(word)) {
                mInputLogic.mIme.playKeyFeedback(-20, 1);
                mInputLogic.mRichInputConnection.commitCorrection(new CorrectionInfo(
                        mInputLogic.mRichInputConnection.getCursorEnd() - length,
                        request.typedWordBefore, word));
            }
        }
        return new CommitResult(length, separatorCommitted, true);
    }

    /**
     * CJK / Japanese strip commit: no span, learns via the picked-suggestion record, then
     * re-establishes the composing region and refreshes suggestions synchronously.
     */
    private CommitResult executePredictionWord(SettingsValues settings, CommitRequest request,
            UIUpdateHandler handler, InputEventContext eventContext) {
        final SuggestedWords.SuggestedWordInfo picked = request.pickedInfo;
        mInputLogic.mRichInputConnection.commitText(processClipboardMarker(picked.word), 1);
        PrevWordsInfo prevWordsInfo = mInputLogic.mRichInputConnection.getPrevWordsInfo(
                settings.spacingAndPunctuation, mInputLogic.mComposingTracker.isComposing() ? 2 : 1);
        boolean skipLearningBefore = mInputLogic.mEventDispatcher.skipLearning;
        if (LocaleUtils.isCurrentSubtypeChinese()) {
            mInputLogic.mEventDispatcher =
                    mInputLogic.mComposingTracker.createEventDispatcherForPickedSuggestion(
                            request.commitType, picked, prevWordsInfo, skipLearningBefore);
            mInputLogic.mIme.getDynamicLearningManager()
                    .learn(picked, mInputLogic.mEventDispatcher.skipLearning);
        } else {
            boolean isJapanese = LocaleUtils.isCurrentSubtypeJapanese();
            mInputLogic.mIme.getDynamicLearningManager().learn(picked, isJapanese);
            if (isJapanese && picked.kindAndFlags == 7) {
                if (BuildConfig.DEBUG) {
                    Log.d("PIPELINE", "IME:inputLogic:nuanceClear"
                            + " reason=japaneseSuggestionKind7"
                            + " word=" + picked.word
                            + " thread=" + Thread.currentThread().getName());
                }
                // Posted before the tracker's own clearAll op; tolerates a missing engine.
                EngineOps.post("japaneseKind7Clear", sdk -> sdk.clear());
                mInputLogic.mComposingTracker.clearAll();
            }
            mInputLogic.mEventDispatcher =
                    mInputLogic.mComposingTracker.createEventDispatcherForPickedSuggestion(
                            request.commitType, picked, prevWordsInfo, skipLearningBefore);
        }
        boolean skipLearningAfter = mInputLogic.mEventDispatcher.skipLearning;
        mInputLogic.setComposingTextInternal(mInputLogic.mComposingTracker.getComposingText(), 1);
        eventContext.markKeyHandled();
        handler.cancelPendingSuggestionUpdates();
        mInputLogic.updateSuggestionsSync(settings, 1);
        // Consume-once, as in executeWord.
        boolean shouldAppendSpace = mInputLogic.mShouldAppendSpace;
        mInputLogic.mShouldAppendSpace = false;
        final String separator = request.trailingSeparator;
        boolean separatorCommitted = false;
        if (separator.codePointCount(0, separator.length()) == 1
                && mInputLogic.isWordSeparator(settings, separator.codePointAt(0))
                && !shouldAppendSpace) {
            separatorCommitted = true;
        }
        if (separatorCommitted) {
            mInputLogic.mRichInputConnection.commitText(separator, 1);
            if (request.commitType == CommitEventRecord.CommitType.MANUAL_PICK) {
                mInputLogic.mHasModifiedEvent = true;
            }
        }
        mInputLogic.mTouchHighlightTracker.clear();
        if (mInputLogic.mSmartPunctuationAnalyzer != null) {
            mInputLogic.mSmartPunctuationAnalyzer.clearDumbMode();
        }
        return new CommitResult(picked.word.length(), separatorCommitted, !skipLearningAfter);
    }

    /**
     * Commits the composing word, or flushes pending touch text. Used before focus change or voice.
     * @param c0804d current settings values
     */
    void commitComposingOrReset(SettingsValues c0804d) {
        if (mInputLogic.mComposingTracker.isComposing()) {
            mInputLogic.mRichInputConnection.beginBatchEdit();
            commitTypedWord(c0804d, "", InputSource.INTERNAL);
            mInputLogic.mRichInputConnection.endBatchEdit();
            return;
        }
        commitTouchEventText();
    }

    /**
     * Like {@link #commitComposingOrReset}, but resets instead when the cursor moved mid-word.
     * @param c0804d current settings values
     * @param enumC0690f the input source that triggered this flush
     */
    void commitOrResetComposing(SettingsValues c0804d, InputSource enumC0690f) {
        if (mInputLogic.mComposingTracker.isComposing()) {
            if (mInputLogic.mComposingTracker.isCursorMoved()) {
                mInputLogic.resetComposingAndSelect(mInputLogic.mRichInputConnection.getCursorStart(), mInputLogic.mRichInputConnection.getCursorEnd(), true);
                return;
            } else {
                commitTypedWord(c0804d, "", enumC0690f);
                return;
            }
        }
        commitTouchEventText();
    }

    /**
     * @param c0804d current settings values (unused by the RAW protocol)
     * @param i the Unicode code point to commit
     * @param enumC0690f the input source that triggered this character commit
     */
    void commitCharacter(SettingsValues c0804d, int i, InputSource enumC0690f) {
        execute(c0804d, CommitRequest.character(new String(Character.toChars(i)), enumC0690f),
                null, null);
    }

    /**
     * Flushes pending multi-tap text. While composing it only re-sends the composing region (a
     * display refresh, so not through {@link #execute}).
     */
    void commitTouchEventText() {
        if (mInputLogic.mTouchHighlightTracker.isHighlightActive()) {
            String pendingChar = mInputLogic.mTouchHighlightTracker.consumePendingChar();
            if (mInputLogic.mComposingTracker.isComposing()) {
                mInputLogic.mRichInputConnection.setComposingText(mInputLogic.getComposingTextWithIndicator(mInputLogic.mComposingTracker.getComposingText()), 1);
            } else {
                execute(null, CommitRequest.touchText(pendingChar, InputSource.INTERNAL),
                        null, null);
            }
        }
    }

    /**
     * Voice or palette text; only called when the payload was not already written as a separator.
     * @param c0804d current settings values
     * @param enumC0690f the input source that triggered the commit
     */
    void commitVoiceText(SettingsValues c0804d, String text, InputSource enumC0690f) {
        execute(c0804d, CommitRequest.voiceText(text, enumC0690f), null, null);
    }

    /**
     * A swipe is one word, but the list can hold kind-8 phrase candidates. Takes the best
     * space-free candidate, else the first token of the top entry.
     */
    private static String pickSingleWordGestureCandidate(SuggestedWords words) {
        if (words == null || words.isEmpty()) {
            return null;
        }
        String top = words.getWord(0);
        if (top == null || top.indexOf(' ') < 0) {
            return top;
        }
        for (int i = 1; i < words.size(); i++) {
            String w = words.getWord(i);
            if (w != null && !w.isEmpty() && w.indexOf(' ') < 0) {
                return w;
            }
        }
        return top.substring(0, top.indexOf(' '));
    }

    /**
     * @param c0804d current settings values
     * @param c0666ac the gesture suggestion list (top word at index 0 is committed)
     * @param enumC0690f the input source
     */
    void commitGestureSuggestion(SettingsValues c0804d, SuggestedWords c0666ac, InputSource enumC0690f) {
        String word = pickSingleWordGestureCandidate(c0666ac);
        if (TextUtils.isEmpty(word)) {
            mInputLogic.mComposingTracker.resetPredictionState();
            mInputLogic.mIme.uiUpdateHandler.postShowSuggestions(SuggestedWords.EMPTY);
            return;
        }
        mInputLogic.mRichInputConnection.beginBatchEdit();
        execute(c0804d, CommitRequest.gesture(word, resolveSeparatorFor(word), enumC0690f),
                null, null);
        mInputLogic.mJustCommitted = true;
        mInputLogic.mRichInputConnection.endBatchEdit();
        mInputLogic.mEventDispatcher.disableRevert();
        mInputLogic.mCommitType = 0;
        KeyboardSwitcher c0979iM4088ac = mInputLogic.mIme.getKeyboardSwitcher();
        if (c0979iM4088ac != null) {
            c0979iM4088ac.setAlphabetKeyboard(mInputLogic.getCapsMode(c0804d), mInputLogic.getConfigParserResult());
        }
        // The commit spent a tapped Shift; only the Shift span resets.
        mInputLogic.mIme.getPhysicalKeyboardStateTracker()
                .resetModifiers(ModifierResetReason.MANUAL_SHIFT_SPENT);
        mInputLogic.mIme.uiUpdateHandler.postUpdateGestureSuggestions(0);
    }

    /** The engine's separator after {@code word} here, or a space before the dictionary loads. */
    String resolveSeparatorFor(String word) {
        if (mInputLogic.mDictionaryLoader.isDictionaryReady()) {
            return mInputLogic.mDictionaryLoader.getMainDictionary().getWordSeparator(
                    mInputLogic.mRichInputConnection.getTextContextBefore() + word);
        }
        return " ";
    }

    /**
     * Commits the composing word verbatim plus separator {@code str}; no-op when not composing.
     * @param c0804d current settings values
     * @param str the separator to append after the word (may be empty)
     * @param enumC0690f the input source that triggered the commit
     */
    void commitTypedWord(SettingsValues c0804d, String str, InputSource enumC0690f) {
        if (!mInputLogic.mComposingTracker.isComposing()) {
            return;
        }
        String word = mInputLogic.mComposingTracker.getComposingText();
        if (word.length() <= 0) {
            return;
        }
        execute(c0804d, CommitRequest.typedWord(word, str, enumC0690f), null, null);
    }

    /**
     * @param c0804d current settings values
     * @param str the separator text that ended the word
     * @param handlerC0650c UI update handler
     * @param enumC0690f the input source that triggered the commit
     * @return {@code true} if the separator payload was already committed
     */
    boolean autoCorrectAndCommit(SettingsValues c0804d, String str, UIUpdateHandler handlerC0650c, InputSource enumC0690f) {
        return autoCorrectAndCommitExtended(c0804d, str, handlerC0650c, enumC0690f, false);
    }

    /**
     * Commits the auto-correction or the typed word. Does nothing when no word is composing.
     *
     * @param z force the separator append even for a word character
     * @param c0804d current settings values
     * @param str the word separator to append after the commit
     * @param handlerC0650c UI update handler used to flush pending async suggestion requests
     * @param enumC0690f the input source that triggered this commit
     * @return {@code true} if {@code str} was already written, so the caller must not write it
     */
    boolean autoCorrectAndCommitExtended(SettingsValues c0804d, String str, UIUpdateHandler handlerC0650c, InputSource enumC0690f, boolean z) {
        mInputLogic.flushPendingSuggestions(c0804d, handlerC0650c);
        String candidate = mInputLogic.mComposingTracker.getAutoCorrection();
        String typedWord = mInputLogic.mComposingTracker.getComposingText();
        // The text-input and voice paths reach this without handleSeparatorInput's gate.
        String chosen = shouldCommitAutoCorrectCandidate(
                candidate,
                mInputLogic.mComposingTracker.getAutoCorrectionScore(),
                c0804d.isAutoCorrectionEnabledPerUserSettings,
                c0804d.editorCapabilities.shouldShowSuggestions) ? candidate : typedWord;
        if (chosen == null) {
            return false;
        }
        if (TextUtils.isEmpty(typedWord)) {
            // Return, never throw: the caller still owns the separator payload.
            return false;
        }
        CommitResult result = execute(
                c0804d,
                CommitRequest.autoCorrected(
                        processClipboardMarker(chosen), candidate, typedWord, str, z, enumC0690f),
                handlerC0650c, null);
        return result.separatorCommitted;
    }

    /**
     * Strips the engine markers from a suggestion; a leading {@code %b} also deletes one char.
     * @param str the raw suggestion string (may contain engine markers)
     */
    public String processClipboardMarker(String str) {
        if (str.contains(MARKER_BOUNDARY_LOWER)) {
            if (str.startsWith(MARKER_BOUNDARY_LOWER)) {
                // Finish composing first: the cache models this delete as trimming the composing
                // tail, while the editor deletes the char before the region.
                if (mInputLogic.mComposingTracker.isComposing()) {
                    mInputLogic.mRichInputConnection.finishComposingText();
                }
                mInputLogic.mRichInputConnection.deleteSurroundingText(1, 0);
            }
            // Literal replace: a ".%b" regex would also strip the user's preceding character.
            str = str.replace(MARKER_BOUNDARY_LOWER, "");
        }
        if (str.contains(MARKER_BOUNDARY)) {
            // %B carries the following boundary char with it.
            str = str.replaceAll(java.util.regex.Pattern.quote(MARKER_BOUNDARY) + ".", "");
            str = str.replace(MARKER_BOUNDARY, "");
        }
        return str;
    }

    /**
     * @param c0804d current settings values
     * @param str the word to commit
     * @param str2 the word separator to append (may be empty)
     * @param enumC0690f the input source that triggered the commit
     * @return characters written (word plus any separator)
     */
    int commitWord(SettingsValues c0804d, String str, CommitEventRecord.CommitType commitTypeVar, String str2, InputSource enumC0690f) {
        return commitWordExtended(c0804d, str, commitTypeVar, str2, enumC0690f, false);
    }

    /**
     * Generic word commit; never issues a {@link CorrectionInfo}, even for {@code DECIDED_WORD}.
     *
     * @param z force the separator append even for a word character
     * @param c0804d current settings values
     * @param str the word text to commit
     * @param str2 the separator to append after the word
     * @param enumC0690f the input source that triggered the commit
     */
    int commitWordExtended(SettingsValues c0804d, String str, CommitEventRecord.CommitType commitTypeVar, String str2, InputSource enumC0690f, boolean z) {
        return execute(c0804d, CommitRequest.word(str, commitTypeVar, str2, z, enumC0690f),
                null, null).lengthWritten;
    }

    /**
     * Commits a CJK / Japanese strip pick; returns whether to record the pick.
     * @param c0804d current settings values
     * @param enumC0690f the input source that triggered the commit
     * @param c0920g the current input event context
     * @param handlerC0650c UI update handler
     * @param str the separator that ended the pick
     */
    boolean commitPredictionWord(SettingsValues c0804d, SuggestedWords.SuggestedWordInfo suggestedWordInfoVar, CommitEventRecord.CommitType commitTypeVar2, InputSource enumC0690f, InputEventContext c0920g, UIUpdateHandler handlerC0650c, String str) {
        return execute(
                c0804d,
                CommitRequest.predictionPick(suggestedWordInfoVar, commitTypeVar2, str, enumC0690f),
                handlerC0650c, c0920g).shouldRecordPick;
    }
}

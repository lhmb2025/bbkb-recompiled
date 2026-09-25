package dev.bbkb.ime.core.textinput;

import android.text.TextUtils;
import android.util.Log;
import android.view.inputmethod.CorrectionInfo;

import dev.bbkb.ime.BuildConfig;
import dev.bbkb.ime.core.ime.UIUpdateHandler;
import dev.bbkb.ime.core.engine.NuanceSDKManager;
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
 * The commit pipeline: ONE execution path ({@link #execute}) fed by a {@link CommitRequest}, plus
 * the thin adapters that resolve each caller's intent into such a request.
 *
 * <h2>Shape (Phase 1c)</h2>
 *
 * <p>This class used to expose ten methods that each decided <em>what</em> to commit and then
 * wrote to the editor themselves. The decision logic was duplicated across them (the auto-correct
 * gate is still applied at two independent sites, {@link InputLogic#handleSeparatorInput} and
 * {@link #autoCorrectAndCommitExtended}, because those two see different inputs), the differences
 * between paths were implicit in which method you called, and a caller could not find out what
 * the pipeline had done — {@code commitVoiceInput} double-committed a palette emoji for exactly
 * that reason (audit SS-1).
 *
 * <p>Now every editor write for a commit goes through {@link #execute}. The methods above it are
 * adapters: they resolve the payload (pick the correction, strip engine markers, choose the
 * separator) and build a {@link CommitRequest}. The differences that used to be spread across ten
 * bodies are that request's fields.
 *
 * <p>Three <em>protocols</em> remain inside {@code execute}, because the editor genuinely sees
 * three different call sequences — a span-decorated word, a CJK prediction pick, and a bare
 * {@code commitText}. They are selected by {@link CommitRequest#protocol}, not by which method
 * the caller reached for. See {@link CommitRequest} for why that distinction is the important one.
 */
class CommitController {

    private static final String TAG = "CommitController";

    /**
     * NuanceSDK suggestion markers (Issue #14).
     *
     * <p>GX-32/TI-18: these two were {@code "%ClipboardViewHolder"} and {@code "%TextChangeType"} -
     * the de-obfuscation pass's class names substituted into string LITERALS. Recovered from the
     * original APK ({@code sources/dev/bbkb/ime/core/c/a.java}, method
     * {@code b(String)}), which reads
     * {@code if (str.contains("%b")) { if (str.startsWith("%b")) deleteSurroundingText(1,0);
     * str = str.replaceAll(".%b","").replace("%b",""); }} followed by the {@code "%B"} pass.
     * The engine's markers are a lower/upper pair, which is also why
     * {@code core/utils/d.java} strips {@code "%B"} case-insensitively.
     *
     * <p>Two consequences of the corrupted values: a real {@code %b} marker was never stripped and
     * committed as raw text, and anything a user literally typed containing {@code %TextChangeType}
     * was silently deleted. The old {@code MARKER_CLIPBOARD_LOWER} constant is gone - both
     * constants held the same original literal, so the doubled {@code replace} was one operation
     * written twice.
     */
    private static final String MARKER_BOUNDARY_LOWER = "%b";
    private static final String MARKER_BOUNDARY = "%B";

    /**
     * {@code SuggestedWordInfo} kind for a SUBSTITUTION - a personal-dictionary macro, either a
     * user's own or one of the shipped ones in {@code assets/substitution_macros}
     * ({@code i -> I}, {@code bb -> BlackBerry}, {@code alot -> a lot}, the {@code %D}/{@code %T}
     * date macros). Inserted at index 1 by
     * {@code NuanceSDKDictionaryBridge.shouldAutoCorrect}, mirroring the original's
     * {@code sources/dev/bbkb/ime/core/r.java:208}.
     */
    static final int KIND_SUBSTITUTION = 7;

    /**
     * FIX-MACRO / D-3: the original's auto-correct commit gate, restored.
     *
     * <p>{@code sources/dev/bbkb/ime/core/c/a.java:461} picks the TYPED word when
     * {@code this.f.t() == null || (iU != 7 && (!dVar.ab || iU == 0))}, where {@code iU} is the
     * candidate's kind and {@code dVar.ab} is the per-user auto-correct setting. Negated, the
     * CANDIDATE commits when {@code acWord != null && (kind == 7 || (acEnabled && kind != 0))}.
     *
     * <p>The {@code kind == 7} disjunct is the escape hatch: a macro expands even when the user
     * has auto-correct switched off. We had only {@code acEnabled && kind != 0}, so turning
     * auto-correct off silently turned every macro off with it. Ordinary spelling corrections
     * (kind 1/2/3) still obey the setting, and kind 0 - "the word the user typed" - never
     * commits as a correction, in the original as here.
     *
     * <p><b>DELIBERATE DIVERGENCE from the original:</b> {@code suggestionsAllowedInEditor}.
     * The original consults no editor predicate here, so its escape hatch ignores the field it
     * is typing into - a macro expands in a password box as readily as in a message. Every OTHER
     * correction is already held back by the editor's own declaration:
     * {@code SettingsValues.isAutoCorrectionEnabledPerUserSettings} is
     * {@code isAutoCorrectEnabled && !editorCapabilities.noAutoCorrect}, and {@code noAutoCorrect}
     * is set for {@code TYPE_TEXT_FLAG_NO_SUGGESTIONS} and for any single-line field that did not
     * opt in. Kind 7 was the one path left that overrode the app. Gating it on
     * {@link dev.bbkb.ime.core.textinput.connection.EditorCapabilities#shouldShowSuggestions}
     * closes that: no expansion in password, email-address, URI, filter, {@code NO_SUGGESTIONS}
     * or force-ASCII fields, where silently rewriting what was typed can corrupt a credential.
     * That same flag already honours the user's "Force suggestions" override for non-password
     * fields, so anyone who wants macros everywhere still has the switch.
     */
    static boolean shouldCommitAutoCorrectCandidate(String acWord, int kind, boolean autoCorrectEnabled,
            boolean suggestionsAllowedInEditor) {
        if (acWord == null) {
            return false;
        }
        // Not folded into one expression: kind 7 must NOT fall through to the auto-correct term
        // below, which would re-admit the macro whenever auto-correct happens to be on.
        if (kind == KIND_SUBSTITUTION) {
            return suggestionsAllowedInEditor;
        }
        return autoCorrectEnabled && kind != 0;
    }

    private final InputLogic mInputLogic;

    CommitController(InputLogic inputLogic) {
        this.mInputLogic = inputLogic;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  THE ONE EXECUTION PATH
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Write one resolved {@link CommitRequest} to the editor. Every commit in the typing path
     * ends here.
     *
     * <p>{@code settings} is unused by {@link CommitRequest.Protocol#RAW} — a bare code-point
     * commit consults nothing — so {@link #commitTouchEventText()}, which has no settings of its
     * own, may pass {@code null}. The other two protocols require it.
     *
     * <p>{@code handler} and {@code eventContext} are needed only by
     * {@link CommitRequest.Protocol#PREDICTION_WORD}, which cancels pending suggestion updates
     * and marks the key handled as part of committing; the others accept {@code null}.
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

    /** One {@code commitText} and nothing else: a lone code point, or a flushed multi-tap char. */
    private CommitResult executeRaw(CommitRequest request) {
        // TI-8: this used to branch on DeviceProfile.isPreMarshmallow() to route numeric-editor
        // commits through postAtFrontOfQueue. That predicate is SDK_INT < VERSION_CODES.M and
        // minSdk is 23, so the branch (and its anonymous Runnable allocation site, on the commit
        // hot path) was unreachable.
        mInputLogic.mRichInputConnection.commitText(request.text, 1);
        return new CommitResult(request.text.length(), false, true);
    }

    /**
     * The ordinary word commit: attach a {@link android.text.style.SuggestionSpan}, write the word
     * (and the separator if the append gate lets it through), build the {@link CommitEventRecord}
     * that makes the commit revertible, notify the learning subsystem, and reset touch-highlight
     * and cached-suggestion state.
     *
     * <p>For {@link CommitRequest.Reason#AUTO_CORRECT} two further stages run at the end: the
     * engine-marker tail delete, and the {@link CorrectionInfo} round trip that tells the editor a
     * correction happened. Both are guarded on the request actually carrying the fields they need
     * ({@code rawCandidate} / {@code typedWordBefore}), so a {@code DECIDED_WORD} request built by
     * {@link #commitWordExtended} — which has no candidate and no correction to report — behaves
     * exactly as it did before this path was unified.
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
        CharSequence payload = request.withSuggestionSpan
                ? SuggestionSpanBuilder.getTextWithSuggestionSpan(
                        mInputLogic.mIme, word, currentSuggestions, isBatchInput)
                : word;
        PrevWordsInfo prevWordsInfo = mInputLogic.mRichInputConnection.getPrevWordsInfo(
                settings.spacingAndPunctuation, mInputLogic.mComposingTracker.isComposing() ? 2 : 1);
        // F9: consume-once. The flag is set per manual pick (cursor sits before an existing
        // separator); without clearing it here, a later unrelated commit could reuse the
        // stale value and skip the cursor past a character that isn't there.
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
        if (matched != null && !TextUtils.isEmpty(word) && word.equalsIgnoreCase(matched.word)
                && !word.equals(matched.word)) {
            // Typed casing differs from the Nuance entry (e.g. auto-capitalized sentence
            // start): record the user's casing in the dynamic language model.
            NuanceSDKManager.getInstance().addWord(word);
        }
        if (request.learn) {
            // Always transition the engine out of the just-typed word. Previously the
            // case-mismatch branch above returned without this step, leaving the engine's
            // character buffer holding the typed letters — the next prediction request then
            // returned completions of the committed word instead of next-word predictions.
            // See docs/archived/2026-05_composing-and-ckb-gestures/2026-05_post-commit-next-word-prediction_investigation.md. learn either records the
            // pick via selectionListSelectWord or clears the engine, both of which end the word.
            mInputLogic.mIme.getDynamicLearningManager().learn(matched);
        }
        mInputLogic.mTouchHighlightTracker.clear();
        if (mInputLogic.mSmartPunctuationAnalyzer != null) {
            mInputLogic.mSmartPunctuationAnalyzer.clearDumbMode();
        }
        // Clear the cached suggestions for the just-committed word. Otherwise
        // any code that reads mCurrentSuggestions before the next-word predictions
        // arrive (e.g. the empty-prediction fallback in SuggestionRequestQueue)
        // will keep displaying the same word's predictions on the strip.
        mInputLogic.mCurrentSuggestions = SuggestedWords.EMPTY;

        // ── AUTO_CORRECT-only tail stages ──────────────────────────────────────
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
     * The CJK / Japanese prediction-strip commit. Unlike {@link #executeWord} it writes the
     * suggestion text with no {@code SuggestionSpan}, learns through the picked-suggestion record,
     * re-establishes the composing region afterwards, and refreshes suggestions synchronously.
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
                // Audit EB-8: guard the documented-nullable singleton.
                com.blackberry.nuanceshim.NuanceSDK sdkCT = NuanceSDKManager.getInstance();
                if (sdkCT != null) sdkCT.clear();
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
        // F9: consume-once, same rationale as executeWord.
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

    // ═══════════════════════════════════════════════════════════════════════════
    //  ADAPTERS — resolve a caller's intent into a CommitRequest
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Commits the current composing text (if any) as a typed word, or flushes
     * any pending touch-event text if not composing. Used to flush partial input before
     * focus change or before voice input begins.
     *
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
     * Commits the current composing text, or resets composing state to idle if the cursor
     * has been moved mid-word. Falls back to flushing any pending touch-event text when
     * not composing.
     *
     * @param c0804d     current settings values
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
     * Commits a single Unicode code point to the editor.
     *
     * @param c0804d     current settings values (unused by the RAW protocol; kept for the
     *                   signature the typing path calls through)
     * @param i          the Unicode code point to commit
     * @param enumC0690f the input source that triggered this character commit
     */
    void commitCharacter(SettingsValues c0804d, int i, InputSource enumC0690f) {
        execute(c0804d, CommitRequest.character(new String(Character.toChars(i)), enumC0690f),
                null, null);
    }

    /**
     * Flushes any pending touch-event text to the editor. If composing text is active, updates
     * the composing span; otherwise commits the accumulated touch-event string as plain text.
     *
     * <p>Only the second branch is a commit. The first re-sends the composing region, which is a
     * display refresh rather than a commit, so it does not go through {@link #execute}.
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
     * Commits a voice-recognition result, or a palette payload from the text-input path.
     *
     * <p>{@link InputLogic#commitVoiceInput} calls this only when the auto-correct commit ahead of
     * it did not already write the same payload as its trailing separator (audit SS-1).
     */
    void commitVoiceText(SettingsValues c0804d, String text, InputSource enumC0690f) {
        execute(c0804d, CommitRequest.voiceText(text, enumC0690f), null, null);
    }

    /**
     * A single CKB swipe is one word by definition, but the engine's prediction list can
     * contain multi-word phrase candidates (kind=8 bigrams like "same time", "end of"). If
     * one is ranked first, committing getWord(0) verbatim deposits two words from one trace.
     * Prefer the highest-ranked space-free candidate; if the list is all phrases, fall back
     * to the first token of the top entry rather than the whole phrase.
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
     * Commits the top gesture (swipe) suggestion as a typed word, appending the appropriate
     * word separator, updating keyboard state, and requesting a gesture suggestions update.
     *
     * @param c0804d     current settings values
     * @param c0666ac    the gesture suggestion list (top word at index 0 is committed)
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
        // The commit spent the manual (tapped, released) Shift, so the Shift span goes and nothing
        // else does — the scope MANUAL_SHIFT_SPENT names, and the reset contract's own entry point.
        mInputLogic.mIme.getPhysicalKeyboardStateTracker()
                .resetModifiers(ModifierResetReason.MANUAL_SHIFT_SPENT);
        mInputLogic.mIme.uiUpdateHandler.postUpdateGestureSuggestions(0);
    }

    /**
     * The separator the engine wants after {@code word} in the current context, or a plain space
     * when the dictionary is not loaded yet. Shared by the gesture and manual-pick adapters,
     * which resolved it with identical code.
     */
    String resolveSeparatorFor(String word) {
        if (mInputLogic.mDictionaryLoader.isDictionaryReady()) {
            return mInputLogic.mDictionaryLoader.getMainDictionary().getWordSeparator(
                    mInputLogic.mRichInputConnection.getTextContextBefore() + word);
        }
        return " ";
    }

    /**
     * Commits the current composing text as a user-typed word followed by the given separator.
     * No-op if no composing text is active.
     *
     * @param c0804d     current settings values
     * @param str        the separator to append after the word (may be empty)
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
     * Flushes pending suggestions then commits the composing text, applying an auto-correction
     * if one is eligible.
     *
     * @return {@code true} if the separator payload was already committed — see SS-1.
     */
    boolean autoCorrectAndCommit(SettingsValues c0804d, String str, UIUpdateHandler handlerC0650c, InputSource enumC0690f) {
        return autoCorrectAndCommitExtended(c0804d, str, handlerC0650c, enumC0690f, false);
    }

    /**
     * Resolves whether the auto-correction or the typed word should be committed, and commits it.
     *
     * <p><b>Precondition:</b> a word must be composing. With nothing composing the resolved word
     * is the empty string, which is not null, so the "impossible" guard below fires. Every
     * production caller checks {@code isComposing()} (or a non-empty composing length) first;
     * {@code CommitEntryPointsCharacterisationTest} pins the throw so a future adapter that
     * forgets is caught by a test rather than by a crash on a device.
     *
     * @param c0804d        current settings values
     * @param str           the word separator to append after the commit
     * @param handlerC0650c UI update handler used to flush pending async suggestion requests
     * @param enumC0690f    the input source that triggered this commit
     * @param z             {@code true} to force appending the separator even if it is a word
     *                      character
     * @return {@code true} if this call already committed {@code str} (the separator payload)
     *         into the editor, so the caller must not commit it again. See audit SS-1.
     */
    boolean autoCorrectAndCommitExtended(SettingsValues c0804d, String str, UIUpdateHandler handlerC0650c, InputSource enumC0690f, boolean z) {
        mInputLogic.flushPendingSuggestions(c0804d, handlerC0650c);
        String candidate = mInputLogic.mComposingTracker.getAutoCorrection();
        String typedWord = mInputLogic.mComposingTracker.getComposingText();
        // FIX-MACRO / D-3: this site re-derives the word to commit independently of
        // InputLogic.handleSeparatorInput, so the kind-7 escape has to be applied here too -
        // autoCorrectAndCommitExtended is also reached from the text-input and voice paths,
        // which never pass through that gate. (The original at c/a.java:253 consults no setting
        // at all here; we keep ours so ordinary corrections behave exactly as before.)
        String chosen = shouldCommitAutoCorrectCandidate(
                candidate,
                mInputLogic.mComposingTracker.getAutoCorrectionScore(),
                c0804d.isAutoCorrectionEnabledPerUserSettings,
                c0804d.editorCapabilities.shouldShowSuggestions) ? candidate : typedWord;
        if (chosen == null) {
            // Nothing was committed at all, so the caller still owns the separator payload.
            return false;
        }
        if (TextUtils.isEmpty(typedWord)) {
            // Nothing is composing, so there is no word to correct or commit. Every production
            // caller checks isComposing() first; when one does not, the caller still owns the
            // separator payload (owner decision 2026-09-22: return, never throw).
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
     * Strips the NuanceSDK suggestion markers ({@code %b} and {@code %B}) from a suggestion word
     * before it is committed to the editor.
     *
     * @param str the raw suggestion string (may contain engine markers)
     * @return the cleaned string with all markers removed
     */
    public String processClipboardMarker(String str) {
        if (str.contains(MARKER_BOUNDARY_LOWER)) {
            if (str.startsWith(MARKER_BOUNDARY_LOWER)) {
                // Side-effect: delete placeholder char inserted when clipboard suggestion was set up.
                // Audit SS-5: RichInputConnection models deleteSurroundingText as trimming the
                // composing TAIL, but a real editor deletes the character BEFORE the composing
                // region — so issuing this while composing left the cache and the editor disagreeing
                // (composing length and cursor off by one) for the rest of the commit. Retire the
                // composing region first so the delete means the same thing on both sides.
                if (mInputLogic.mComposingTracker.isComposing()) {
                    mInputLogic.mRichInputConnection.finishComposingText();
                }
                mInputLogic.mRichInputConnection.deleteSurroundingText(1, 0);
            }
            // FIX Issue #14: literal replace instead of the original's regex replaceAll(".%b", ""),
            // which matched ANY char + "%b" - overly broad, could strip user text.
            str = str.replace(MARKER_BOUNDARY_LOWER, "");
        }
        if (str.contains(MARKER_BOUNDARY)) {
            // "%B." regex intentionally strips %B + following boundary char from NuanceSDK
            str = str.replaceAll(java.util.regex.Pattern.quote(MARKER_BOUNDARY) + ".", "");
            str = str.replace(MARKER_BOUNDARY, "");
        }
        return str;
    }

    /**
     * Commits the given word through the word protocol.
     *
     * @param c0804d        current settings values
     * @param str           the word to commit
     * @param commitTypeVar the commit type (typed, auto-corrected, manual pick, etc.)
     * @param str2          the word separator to append (may be empty)
     * @param enumC0690f    the input source that triggered the commit
     * @return the total character length written to the editor (word + any separator)
     */
    int commitWord(SettingsValues c0804d, String str, CommitEventRecord.CommitType commitTypeVar, String str2, InputSource enumC0690f) {
        return commitWordExtended(c0804d, str, commitTypeVar, str2, enumC0690f, false);
    }

    /**
     * Commits the given word through the word protocol, optionally forcing the separator append.
     *
     * <p>The generic adapter: it carries no auto-correct candidate and no pre-commit typed word,
     * so {@link #executeWord}'s correction stages stay inactive even for a {@code DECIDED_WORD}
     * commit type. A caller that does want the {@link CorrectionInfo} round trip goes through
     * {@link #autoCorrectAndCommitExtended}.
     *
     * @param z {@code true} to force appending the separator even if it is a word character
     * @return the total character length written to the editor (word length + separator length)
     */
    int commitWordExtended(SettingsValues c0804d, String str, CommitEventRecord.CommitType commitTypeVar, String str2, InputSource enumC0690f, boolean z) {
        return execute(c0804d, CommitRequest.word(str, commitTypeVar, str2, z, enumC0690f),
                null, null).lengthWritten;
    }

    /**
     * Commits a CJK / Japanese prediction-strip pick.
     *
     * @return {@code true} if the learning subsystem should record this pick
     */
    boolean commitPredictionWord(SettingsValues c0804d, SuggestedWords.SuggestedWordInfo suggestedWordInfoVar, CommitEventRecord.CommitType commitTypeVar2, InputSource enumC0690f, InputEventContext c0920g, UIUpdateHandler handlerC0650c, String str) {
        return execute(
                c0804d,
                CommitRequest.predictionPick(suggestedWordInfoVar, commitTypeVar2, str, enumC0690f),
                handlerC0650c, c0920g).shouldRecordPick;
    }
}

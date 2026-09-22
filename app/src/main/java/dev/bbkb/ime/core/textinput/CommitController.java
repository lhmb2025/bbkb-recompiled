package dev.bbkb.ime.core.textinput;

import android.text.TextUtils;
import android.util.Log;
import android.view.inputmethod.CorrectionInfo;
import android.view.inputmethod.EditorInfo;

import dev.bbkb.ime.BuildConfig;
import dev.bbkb.ime.core.BlackBerryIME;
import dev.bbkb.ime.core.ime.UIUpdateHandler;
import dev.bbkb.ime.core.engine.NuanceSDKManager;
import dev.bbkb.ime.core.keyevent.InputEventContext;
import dev.bbkb.ime.core.keyevent.InputSource;
import dev.bbkb.ime.core.settings.util.SettingsValues;
import dev.bbkb.ime.core.suggestion.PrevWordsInfo;
import dev.bbkb.ime.core.suggestion.SuggestedWords;
import dev.bbkb.ime.core.textinput.composing.CommitEventRecord;
import dev.bbkb.ime.core.locale.LocaleUtils;
import dev.bbkb.ime.core.suggestion.SuggestionSpanBuilder;
import dev.bbkb.ime.keyboard.KeyboardSwitcher;

/**
 * Encapsulates all commit-related logic extracted from {@link InputLogic}.
 *
 * <p>Owns the pipeline for committing typed words, auto-corrections, gesture suggestions,
 * manual picks, prediction words, and raw characters to the editor, as well as the
 * clipboard-marker stripping pass applied to suggestion strings before commit.</p>
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
     * <p>TI-8: this used to branch on {@code DeviceProfile.isPreMarshmallow()} to route numeric-
     * editor commits through {@code postAtFrontOfQueue}. That predicate is
     * {@code SDK_INT < VERSION_CODES.M} and {@code minSdk} is 23, so the branch (and its anonymous
     * Runnable allocation site, on the commit hot path) was unreachable.
     *
     * @param c0804d     current settings values
     * @param i          the Unicode code point to commit
     * @param enumC0690f the input source that triggered this character commit
     */
    void commitCharacter(SettingsValues c0804d, int i, InputSource enumC0690f) {
        mInputLogic.mRichInputConnection.commitText(new String(Character.toChars(i)), 1);
    }

    /**
     * Flushes any pending touch-event text to the editor. If composing text is active, updates
     * the composing span; otherwise commits the accumulated touch-event string as plain text.
     */
    void commitTouchEventText() {
        if (mInputLogic.mTouchHighlightTracker.isHighlightActive()) {
            String pendingChar = mInputLogic.mTouchHighlightTracker.consumePendingChar();
            if (mInputLogic.mComposingTracker.isComposing()) {
                mInputLogic.mRichInputConnection.setComposingText(mInputLogic.getComposingTextWithIndicator(mInputLogic.mComposingTracker.getComposingText()), 1);
            } else {
                mInputLogic.mRichInputConnection.commitText(pendingChar, 1);
            }
        }
    }

    /**
     * Commits the top gesture (swipe) suggestion as a typed word, appending the appropriate
     * word separator, updating keyboard state, and requesting a gesture suggestions update.
     *
     * @param c0804d     current settings values
     * @param c0666ac    the gesture suggestion list (top word at index 0 is committed)
     * @param enumC0690f the input source (typically TOUCH)
     */
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

    void commitGestureSuggestion(SettingsValues c0804d, SuggestedWords c0666ac, InputSource enumC0690f) {
        String strMo4204c;
        String strMo4284a = pickSingleWordGestureCandidate(c0666ac);
        if (TextUtils.isEmpty(strMo4284a)) {
            mInputLogic.mComposingTracker.resetPredictionState();
            mInputLogic.mIme.uiUpdateHandler.postShowSuggestions(SuggestedWords.EMPTY);
            return;
        }
        mInputLogic.mRichInputConnection.beginBatchEdit();
        if (mInputLogic.mDictionaryLoader.isDictionaryReady()) {
            strMo4204c = mInputLogic.mDictionaryLoader.getMainDictionary().getWordSeparator(mInputLogic.mRichInputConnection.getTextContextBefore() + strMo4284a);
        } else {
            strMo4204c = " ";
        }
        commitWord(c0804d, strMo4284a, CommitEventRecord.CommitType.BATCH_INPUT_WORD, strMo4204c, enumC0690f);
        mInputLogic.mJustCommitted = true;
        mInputLogic.mRichInputConnection.endBatchEdit();
        mInputLogic.mEventDispatcher.disableRevert();
        mInputLogic.mCommitType = 0;
        KeyboardSwitcher c0979iM4088ac = mInputLogic.mIme.getKeyboardSwitcher();
        if (c0979iM4088ac != null) {
            c0979iM4088ac.setAlphabetKeyboard(mInputLogic.getCapsMode(c0804d), mInputLogic.getConfigParserResult());
        }
        mInputLogic.mIme.getPhysicalKeyboardStateTracker().clearManualShift();
        mInputLogic.mIme.uiUpdateHandler.postUpdateGestureSuggestions(0);
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
        if (mInputLogic.mComposingTracker.isComposing()) {
            String strM4360m = mInputLogic.mComposingTracker.getComposingText();
            if (strM4360m.length() > 0) {
                commitWord(c0804d, strM4360m, CommitEventRecord.CommitType.USER_TYPED_WORD, str, enumC0690f);
            }
        }
    }

    /**
     * Flushes pending suggestions then commits the composing text, applying an auto-correction
     * if one is eligible. Delegates to {@link #autoCorrectAndCommitExtended}.
     *
     * @param c0804d       current settings values
     * @param str          the word separator to append after the commit
     * @param handlerC0650c UI update handler used to flush async suggestion requests
     * @param enumC0690f   the input source that triggered this commit
     */
    /** @return {@code true} if the separator payload was already committed — see SS-1. */
    boolean autoCorrectAndCommit(SettingsValues c0804d, String str, UIUpdateHandler handlerC0650c, InputSource enumC0690f) {
        return autoCorrectAndCommitExtended(c0804d, str, handlerC0650c, enumC0690f, false);
    }

    /**
     * Full auto-correct-and-commit pipeline: flushes pending suggestions, resolves whether
     * an auto-correction or the typed word should be committed, commits it, and issues a
     * {@link android.view.inputmethod.CorrectionInfo} to the editor when an auto-correction
     * was applied.
     *
     * @param c0804d       current settings values
     * @param str          the word separator to append after the commit
     * @param handlerC0650c UI update handler used to flush pending async suggestion requests
     * @param enumC0690f   the input source that triggered this commit
     * @param z            {@code true} to force appending the separator even if it is a word character
     */
    /**
     * @return {@code true} if this call already committed {@code str} (the separator payload)
     *         into the editor, so the caller must not commit it again. See audit SS-1.
     */
    boolean autoCorrectAndCommitExtended(SettingsValues c0804d, String str, UIUpdateHandler handlerC0650c, InputSource enumC0690f, boolean z) {
        mSeparatorCommittedByLastCommitWord = false;
        mInputLogic.flushPendingSuggestions(c0804d, handlerC0650c);
        String strM4367t = mInputLogic.mComposingTracker.getAutoCorrection();
        String strM4360m = mInputLogic.mComposingTracker.getComposingText();
        // FIX-MACRO / D-3: this site re-derives the word to commit independently of
        // InputLogic.handleSeparatorInput, so the kind-7 escape has to be applied here too -
        // autoCorrectAndCommitExtended is also reached from the text-input and voice paths,
        // which never pass through that gate. (The original at c/a.java:253 consults no setting
        // at all here; we keep ours so ordinary corrections behave exactly as before.)
        String str2 = shouldCommitAutoCorrectCandidate(
                strM4367t,
                mInputLogic.mComposingTracker.getAutoCorrectionScore(),
                c0804d.isAutoCorrectionEnabledPerUserSettings,
                c0804d.editorCapabilities.shouldShowSuggestions) ? strM4367t : strM4360m;
        if (str2 != null) {
            if (TextUtils.isEmpty(strM4360m)) {
                throw new RuntimeException("We have an auto-correction but the typed word is empty? Impossible! I must commit suicide.");
            }
            String strM4471b = processClipboardMarker(str2);
            int iM4380a = commitWordExtended(c0804d, strM4471b, CommitEventRecord.CommitType.DECIDED_WORD, str, enumC0690f, z);
            if (strM4367t != null && strM4367t.endsWith(MARKER_BOUNDARY)) {
                mInputLogic.mRichInputConnection.deleteSurroundingText(1, 0);
            }
            if (strM4360m.equals(strM4471b)) {
                return mSeparatorCommittedByLastCommitWord;
            }
            mInputLogic.mIme.playKeyFeedback(-20, 1);
            mInputLogic.mRichInputConnection.commitCorrection(new CorrectionInfo(mInputLogic.mRichInputConnection.getCursorEnd() - iM4380a, strM4360m, strM4471b));
        }
        // str2 == null means nothing was committed at all, and the flag is still false from the
        // reset above — commitWordExtended is the only thing that ever sets it true.
        return mSeparatorCommittedByLastCommitWord;
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
     * Commits the given word to the editor using the extended commit pipeline.
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
     * Core commit pipeline: attaches a {@link android.text.style.SuggestionSpan} to the word,
     * commits it (and optionally the separator) to the editor, creates the
     * {@link CommitEventRecord} for revert eligibility, notifies the learning subsystem, and
     * resets touch-highlight and gesture state.
     *
     * @param c0804d        current settings values
     * @param str           the word to commit
     * @param commitTypeVar the commit type (typed, auto-corrected, manual pick, gesture, etc.)
     * @param str2          the word separator to append (may be empty)
     * @param enumC0690f    the input source that triggered the commit
     * @param z             {@code true} to force appending the separator even if it is a word character
     * @return the total character length written to the editor (word length + separator length)
     */
    /**
     * Whether the most recent {@link #commitWordExtended} appended the separator payload itself.
     * Audit SS-1: the append is conditional (single code point, separator-class, no pending
     * auto-space), and a caller holding the same payload has no other way to know.
     */
    private boolean mSeparatorCommittedByLastCommitWord;

    int commitWordExtended(SettingsValues c0804d, String str, CommitEventRecord.CommitType commitTypeVar, String str2, InputSource enumC0690f, boolean z) {
        SuggestedWords c0666ac = mInputLogic.mCurrentSuggestions;
        if (BuildConfig.DEBUG) {
        android.util.Log.d("SUGG_COMMIT_DEBUG", "commitWordExtended: word='" + str + "' commitType=" + commitTypeVar
                + " currentSuggestionsSize=" + c0666ac.size()
                + " composing='" + mInputLogic.mComposingTracker.getComposingText() + "'");
        }
        boolean z2 = false;
        boolean z3 = commitTypeVar == CommitEventRecord.CommitType.BATCH_INPUT_WORD;
        CharSequence charSequenceM3932a = SuggestionSpanBuilder.getTextWithSuggestionSpan(mInputLogic.mIme, str, c0666ac, z3);
        PrevWordsInfo prevWordsInfoM5812a = mInputLogic.mRichInputConnection.getPrevWordsInfo(c0804d.spacingAndPunctuation, mInputLogic.mComposingTracker.isComposing() ? 2 : 1);
        // F9: consume-once. The flag is set per manual pick (cursor sits before an existing
        // separator); without clearing it here, a later unrelated commit could reuse the
        // stale value and skip the cursor past a character that isn't there.
        boolean shouldAppendSpace = mInputLogic.mShouldAppendSpace;
        mInputLogic.mShouldAppendSpace = false;
        mInputLogic.mRichInputConnection.commitText(charSequenceM3932a, shouldAppendSpace ? 2 : 1);
        int length = charSequenceM3932a.length();
        if (str2.codePointCount(0, str2.length()) == 1 && ((mInputLogic.isWordSeparator(c0804d, str2.codePointAt(0)) || z) && !shouldAppendSpace)) {
            z2 = true;
        }
        // Audit SS-1: record whether this call consumed the separator, so callers that also
        // hold the payload know not to commit it a second time. handleSeparatorInput already
        // knew via its own control flow; commitVoiceInput had no way to tell and double-committed.
        mSeparatorCommittedByLastCommitWord = z2;
        if (z2) {
            mInputLogic.mRichInputConnection.commitText(str2, 1);
            length += str2.length();
            if (commitTypeVar == CommitEventRecord.CommitType.MANUAL_PICK || z3) {
                mInputLogic.mHasModifiedEvent = true;
            }
        }
        mInputLogic.mEventDispatcher = mInputLogic.mComposingTracker.createEventDispatcher(commitTypeVar, charSequenceM3932a, str2, prevWordsInfoM5812a);
        SuggestedWords.SuggestedWordInfo aVarM4278SuggestedWordInfo = SuggestedWords.findByNuanceWord(c0666ac, str);
        if (aVarM4278SuggestedWordInfo != null && !TextUtils.isEmpty(str) && str.equalsIgnoreCase(aVarM4278SuggestedWordInfo.word) && !str.equals(aVarM4278SuggestedWordInfo.word)) {
            // Typed casing differs from the Nuance entry (e.g. auto-capitalized sentence
            // start): record the user's casing in the dynamic language model.
            NuanceSDKManager.getInstance().addWord(str);
        }
        // Always transition the engine out of the just-typed word. Previously the
        // case-mismatch branch above returned without this step, leaving the engine's
        // character buffer holding the typed letters — the next prediction request then
        // returned completions of the committed word instead of next-word predictions.
        // See docs/archived/2026-05_composing-and-ckb-gestures/2026-05_post-commit-next-word-prediction_investigation.md. learn either records the
        // pick via selectionListSelectWord or clears the engine, both of which end the word.
        mInputLogic.mIme.getDynamicLearningManager().learn(aVarM4278SuggestedWordInfo);
        mInputLogic.mTouchHighlightTracker.clear();
        if (mInputLogic.mSmartPunctuationAnalyzer != null) mInputLogic.mSmartPunctuationAnalyzer.clearDumbMode();
        // Clear the cached suggestions for the just-committed word. Otherwise
        // any code that reads mCurrentSuggestions before the next-word predictions
        // arrive (e.g. the empty-prediction fallback in SuggestionRequestQueue)
        // will keep displaying the same word's predictions on the strip.
        mInputLogic.mCurrentSuggestions = SuggestedWords.EMPTY;
        return length;
    }

    /**
     * Commits a CJK / Japanese prediction-strip pick. Unlike {@link #commitWord}, this path
     * commits the suggestion text directly (no {@code SuggestionSpan}), creates a
     * picked-suggestion {@link CommitEventRecord}, notifies the learning subsystem, updates
     * composing state, and appends a separator if applicable.
     *
     * @param c0804d             current settings values
     * @param suggestedWordInfoVar the suggestion chosen by the user
     * @param commitTypeVar2     commit type (typically {@code MANUAL_PICK})
     * @param enumC0690f         the input source
     * @param c0920g             current input event context
     * @param handlerC0650c      UI update handler used to cancel pending suggestion requests
     * @param str                separator to append after the committed word (may be empty)
     * @return {@code true} if the learning subsystem should record this pick
     */
    boolean commitPredictionWord(SettingsValues c0804d, SuggestedWords.SuggestedWordInfo suggestedWordInfoVar, CommitEventRecord.CommitType commitTypeVar2, InputSource enumC0690f, InputEventContext c0920g, UIUpdateHandler handlerC0650c, String str) {
        mInputLogic.mRichInputConnection.commitText(processClipboardMarker(suggestedWordInfoVar.word), 1);
        PrevWordsInfo prevWordsInfoM5812a = mInputLogic.mRichInputConnection.getPrevWordsInfo(c0804d.spacingAndPunctuation, mInputLogic.mComposingTracker.isComposing() ? 2 : 1);
        boolean z = mInputLogic.mEventDispatcher.skipLearning;
        if (LocaleUtils.isCurrentSubtypeChinese()) {
            mInputLogic.mEventDispatcher = mInputLogic.mComposingTracker.createEventDispatcherForPickedSuggestion(commitTypeVar2, suggestedWordInfoVar, prevWordsInfoM5812a, z);
            mInputLogic.mIme.getDynamicLearningManager().learn(suggestedWordInfoVar, mInputLogic.mEventDispatcher.skipLearning);
        } else {
            boolean zM5514d = LocaleUtils.isCurrentSubtypeJapanese();
            mInputLogic.mIme.getDynamicLearningManager().learn(suggestedWordInfoVar, zM5514d);
            if (zM5514d && suggestedWordInfoVar.kindAndFlags == 7) {
                if (BuildConfig.DEBUG) {
                    Log.d("PIPELINE", "IME:inputLogic:nuanceClear" +
                        " reason=japaneseSuggestionKind7" +
                        " word=" + suggestedWordInfoVar.word +
                        " thread=" + Thread.currentThread().getName());
                }
                // Audit EB-8: guard the documented-nullable singleton.
                com.blackberry.nuanceshim.NuanceSDK sdkCT = NuanceSDKManager.getInstance();
                if (sdkCT != null) sdkCT.clear();
                mInputLogic.mComposingTracker.clearAll();
            }
            mInputLogic.mEventDispatcher = mInputLogic.mComposingTracker.createEventDispatcherForPickedSuggestion(commitTypeVar2, suggestedWordInfoVar, prevWordsInfoM5812a, z);
        }
        boolean z2 = mInputLogic.mEventDispatcher.skipLearning;
        mInputLogic.setComposingTextInternal(mInputLogic.mComposingTracker.getComposingText(), 1);
        c0920g.markKeyHandled();
        handlerC0650c.cancelPendingSuggestionUpdates();
        mInputLogic.updateSuggestionsSync(c0804d, 1);
        // F9: consume-once, same rationale as commitWordExtended.
        boolean shouldAppendSpace = mInputLogic.mShouldAppendSpace;
        mInputLogic.mShouldAppendSpace = false;
        boolean z3 = false;
        if (str.codePointCount(0, str.length()) == 1 && mInputLogic.isWordSeparator(c0804d, str.codePointAt(0)) && !shouldAppendSpace) {
            z3 = true;
        }
        if (z3) {
            mInputLogic.mRichInputConnection.commitText(str, 1);
            if (commitTypeVar2 == CommitEventRecord.CommitType.MANUAL_PICK) {
                mInputLogic.mHasModifiedEvent = true;
            }
        }
        boolean z4 = !z2;
        mInputLogic.mTouchHighlightTracker.clear();
        if (mInputLogic.mSmartPunctuationAnalyzer != null) mInputLogic.mSmartPunctuationAnalyzer.clearDumbMode();
        return z4;
    }
}

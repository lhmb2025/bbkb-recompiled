package dev.bbkb.ime.core.textinput;

import static dev.bbkb.ime.core.shared.StringHelper.*;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.util.Log;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;

import dev.bbkb.ime.BuildConfig;

import dev.bbkb.ime.core.suggestion.SuggestionRequestQueue;
import dev.bbkb.ime.core.suggestion.SuggestionSpanBuilder;
import dev.bbkb.ime.core.suggestion.SuggestionUpdater;
import dev.bbkb.ime.core.textinput.composing.CommitEventRecord;
import dev.bbkb.ime.core.suggestion.SuggestionEngine;
import dev.bbkb.ime.core.textinput.composing.TouchHighlightTracker;
import dev.bbkb.ime.core.textinput.connection.EditorCapabilities;
import dev.bbkb.ime.core.engine.learning.DynamicLearningManager;
import dev.bbkb.ime.keyboard.inputboard.clipboard.ClipboardController;
import dev.bbkb.ime.personaldictionary.tokenizer.BlackBerryTokenizer;
import dev.bbkb.ime.core.shared.ProfileDetector;
import android.view.inputmethod.CursorAnchorInfo;
import dev.bbkb.ime.core.BlackBerryIME;
import dev.bbkb.ime.core.ime.UIUpdateHandler;
import dev.bbkb.ime.core.SymbolPageProvider;
import dev.bbkb.ime.core.textinput.composing.ComposingSnapshot;
import dev.bbkb.ime.core.textinput.composing.ComposingTextTracker;
import dev.bbkb.ime.core.engine.DictionaryLoader;
import dev.bbkb.ime.core.engine.EngineOps;
import dev.bbkb.ime.core.engine.EngineThreads;
import dev.bbkb.ime.core.textinput.controller.CursorController;
import dev.bbkb.ime.core.textinput.controller.PunctuationController;
import dev.bbkb.ime.core.textinput.controller.SmartPunctuationAnalyzer;
import dev.bbkb.ime.core.engine.NuanceSDKManager;
import dev.bbkb.ime.core.suggestion.PrevWordsInfo;
import dev.bbkb.ime.core.textinput.connection.RichInputConnection;
import dev.bbkb.ime.core.locale.SubtypeManager;
import dev.bbkb.ime.core.suggestion.SuggestedWords;
import dev.bbkb.ime.core.textinput.composing.TouchPointerCoordTracker;
import dev.bbkb.ime.core.keyevent.InputEvent;
import dev.bbkb.ime.core.keyevent.InputEventContext;
import dev.bbkb.ime.core.settings.util.SettingsManager;
import dev.bbkb.ime.core.settings.util.SettingsValues;
import dev.bbkb.ime.core.settings.util.SuggestionStripSettings;
import dev.bbkb.ime.core.settings.util.SpacingAndPunctuation;
import dev.bbkb.ime.keyboard.auxbar.suggestions.SuggestionStripListener;
import dev.bbkb.ime.keyboard.inputboard.clipboard.ClipboardUtil;
import dev.bbkb.ime.core.device.profile.DeviceProfile;
import dev.bbkb.ime.core.keyevent.InputSource;
import dev.bbkb.ime.core.locale.LocaleUtils;
import dev.bbkb.ime.core.shared.Logger;
import dev.bbkb.ime.core.subtypeswitcher.InputTypeUtils;
import dev.bbkb.ime.core.shared.EmojiTextAnalyzer;
import dev.bbkb.ime.core.shared.ScriptUtils;
import dev.bbkb.ime.keyboard.Keyboard;
import dev.bbkb.ime.keyboard.ProximityGrid;
import dev.bbkb.ime.keyboard.KeyboardSwitcher;
import dev.bbkb.ime.keyboard.internal.TextDecorator;
import dev.bbkb.ime.keyboard.internal.TextDecoratorUiOperator;
import com.blackberry.nuanceshim.NuanceSDK;

import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import dev.bbkb.ime.core.shared.InputPathDebug;
import dev.bbkb.ime.core.textinput.connection.CursorWordRange;

public final class InputLogic implements NuanceSDK.AutoCommitCallback {

    private static final String TAG = "InputLogic";
    String mLastCommittedText;

    boolean mLastCommitFromVoice;

    /**
     * The composing region holds a dictation partial (unknown to the composing tracker), replaced
     * by {@link #commitVoiceInput} or settled by {@link #finishVoiceComposingText}.
     */
    private boolean mVoiceComposing;

    boolean mIsAutoCorrectActive;

    private PunctuationController mPunctuationController;

    private CursorController mCursorController;

    private RecorrectionController mRecorrectionController;

    BackspaceController mBackspaceController;

    private SuggestionCoordinator mSuggestionCoordinator;

    private CommitController mCommitController;

    SmartPunctuationAnalyzer mSmartPunctuationAnalyzer;

    boolean mHasModifiedEvent;

    public final SuggestionEngine mSuggestionEngine;

    public final ComposingTextTracker mComposingTracker;

    public final TouchHighlightTracker mTouchHighlightTracker;

    public final RichInputConnection mRichInputConnection;

    final BlackBerryIME mIme;

    final SuggestionStripListener mSuggestionStripListener;

    SuggestionRequestQueue mSuggestionRequestQueue;

    int mCommitType;

    final DictionaryLoader mDictionaryLoader;

    private long mLastGestureTimestamp;

    // Written on the UI thread, read on the suggestion worker; volatile gives visibility only,
    // not atomicity of compound sequences.
    public volatile SuggestedWords mCurrentSuggestions = SuggestedWords.EMPTY;

    final TextDecorator mMoreKeysController = new TextDecorator(new TextDecorator.Listener() {
        /**
         * @param str the word to add to the user dictionary
         */
        @Override
        public void onWordCommit(String str) {
            InputLogic.this.mIme.addWordToUserDictionary(str);
            InputLogic.this.mIme.dismissMoreSuggestions();
        }
    });

    public CommitEventRecord mEventDispatcher = CommitEventRecord.IDLE;


    private final TreeSet<Long> mTrackedKeyEvents = new TreeSet<>();


    boolean mJustCommitted = false;

    boolean mShouldAppendSpace = false;

    private boolean mWasAtOrDot = false;

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    /**
     * Bumped on session teardown or reset. Work posted to {@link #mMainHandler} from a background
     * thread captures it at post time and is dropped at delivery on a mismatch.
     */
    private int mSessionGeneration = 0;

    /** See {@link #mSessionGeneration}. */
    public int getSessionGeneration() {
        return this.mSessionGeneration;
    }

    boolean mLastDynamicLearningEnabled = false;

    int mLastInputType = -2;

    /** Part of the {@link #updateDynamicLearningState()} cache key with {@link #mLastInputType}: incognito is an imeOptions bit. */
    int mLastImeOptions = 0;


    /**
     * @param interfaceC0840e the suggestion strip listener for displaying and hiding suggestions
     * @param c0691g the dictionary and language-pack loader
     */
    public InputLogic(BlackBerryIME blackBerryIME, SuggestionStripListener interfaceC0840e, DictionaryLoader c0691g) {
        this.mSuggestionRequestQueue = SuggestionRequestQueue.DISABLED_INSTANCE;
        this.mIme = blackBerryIME;
        // Runs from BlackBerryIME's field initialiser, before onCreate, so it must not touch the
        // engine (getInstance() loads the native library). The manager installs the handler once
        // the engine exists; the tracker fetches the engine lazily.
        NuanceSDKManager.setPrimaryAutoCommitHandler(this);
        this.mSuggestionStripListener = interfaceC0840e;
        this.mComposingTracker = new ComposingTextTracker(NuanceSDKManager::getInstance);
        this.mTouchHighlightTracker = new TouchHighlightTracker();
        this.mRichInputConnection = new RichInputConnection(blackBerryIME);
        this.mPunctuationController = new PunctuationController(
                this.mRichInputConnection,
                (settings, codePoint, source) -> commitCharacter(settings, codePoint, source)
        );
        this.mCursorController = new CursorController(
                this.mRichInputConnection,
                this::cancelComposingAndTouchEvent,
                () -> this.mIme.isCurrentLanguageRtl()
        );
        this.mBackspaceController = new BackspaceController(this);
        this.mSuggestionRequestQueue = new ContextCapturingRequestQueue(blackBerryIME);
        this.mSuggestionEngine = new SuggestionEngine(c0691g);
        this.mDictionaryLoader = c0691g;
        this.mSmartPunctuationAnalyzer = null;
        this.mHasModifiedEvent = false;
        this.mLastCommitFromVoice = false;
        this.mSuggestionCoordinator = new SuggestionCoordinator(this);
        this.mCommitController = new CommitController(this);
        this.mRecorrectionController = new RecorrectionController(this);
    }

    /** Clears the engine's character buffer on the engine thread, in order with the tracker's feeds; tolerates a missing SDK. */
    private static void clearEngineBuffer() {
        EngineOps.post("clearEngineBuffer", sdk -> sdk.clear());
    }

    /**
     * Resets all transient input state for a new input session and reconnects the suggestion
     * worker and cursor-update tracking. {@code str} is the converter name (empty removes it).
     * @param str the converter name (empty removes it)
     * @param c0804d current settings values
     */
    public void resetInputState(String str, SettingsValues c0804d) {
        this.mLastCommittedText = null;
        clearTouchEventProcessor(true);
        this.mComposingTracker.setConverterByName(str);
        clearComposingText(true);
        clearEngineBuffer();
        this.mBackspaceController.reset();
        this.mCommitType = 0;
        this.mCurrentSuggestions = SuggestedWords.EMPTY;
        this.mRichInputConnection.recalibrateCursorPosition();
        resetSpaceTimestamp();
        SuggestionRequestQueue c0675b = SuggestionRequestQueue.DISABLED_INSTANCE;
        SuggestionRequestQueue c0675b2 = this.mSuggestionRequestQueue;
        if (c0675b == c0675b2) {
            this.mSuggestionRequestQueue = new ContextCapturingRequestQueue(this.mIme);
        } else {
            c0675b2.cancelAll();
        }
        this.mRichInputConnection.requestCursorUpdates(true, true);
        this.mMoreKeysController.reset();
        this.mSmartPunctuationAnalyzer = new SmartPunctuationAnalyzer();
        this.mHasModifiedEvent = false;
        // A new session invalidates anything posted against the old one.
        this.mMainHandler.removeCallbacksAndMessages(null);
        this.mSessionGeneration++;
        // A gesture from the previous session must not commit into this one.
        NuanceSDKManager.consumeGesturePending();
        // Consume-later flags must not outlive the session and perturb the first interaction
        // in the next field.
        this.mSuggestionEngine.setBlockAutoCorrect(false);
        this.mShouldAppendSpace = false;
        this.mJustCommitted = false;
        this.mWasAtOrDot = false;
        // Ids leak when a key-up is delivered elsewhere; a stale one defeats the untracked-repeat guard.
        this.mTrackedKeyEvents.clear();
    }

    /**
     * @param str converter name to install after reset
     * @param c0804d current settings values
     */
    public void fullReset(String str, SettingsValues c0804d) {
        cancelInput();
        resetInputState(str, c0804d);
    }

    /**
     * Commits the composing word with no separator, or flushes pending touch-event text.
     * @param c0804d current settings values
     */
    public void commitComposingOrReset(SettingsValues c0804d) {
        mCommitController.commitComposingOrReset(c0804d);
    }

    /** Drops pending composing text uncommitted, invalidates the connection and cancels suggestion requests. */
    public void cancelInput() {
        if (this.mComposingTracker.isComposing() || this.mTouchHighlightTracker.isHighlightActive()) {
            this.mRichInputConnection.finishComposingText();
            this.mTouchHighlightTracker.clear();
            this.mComposingTracker.clearAll();
        }
        clearComposingText(true);
        this.mRichInputConnection.invalidateConnection();
        this.mSuggestionRequestQueue.cancelAll();
        // cancelAll() only purges the worker queue; main-thread posts (the auto-commit callback)
        // would commit into the next editor. Drop them; the generation bump catches late ones.
        this.mMainHandler.removeCallbacksAndMessages(null);
        this.mSessionGeneration++;
        // gesturePending is process-lifetime; clearing it lets the batch-commit handler
        // recognise a reply that outlived its session.
        NuanceSDKManager.consumeGesturePending();
        this.mCurrentSuggestions = SuggestedWords.EMPTY;
    }

    /** Shuts down background workers and releases dictionary resources; call when the IME is destroyed. */
    public void shutdown() {
        SuggestionRequestQueue c0675b = this.mSuggestionRequestQueue;
        this.mSuggestionRequestQueue = SuggestionRequestQueue.DISABLED_INSTANCE;
        c0675b.shutdown();
        this.mDictionaryLoader.closeAndReset();
    }

    /**
     * Commits voice-recognised text, replacing any composing text (auto-space, leading dots
     * stripped). {@code z}: the dictation included punctuation (suppresses autocaps).
     * @param c0804d current settings values
     * @param i symbol page order / shift state
     * @param z {@code true} if voice input included punctuation (suppresses autocaps)
     * @param handlerC0650c UI update handler for posting suggestion refreshes
     */
    public InputEventContext commitVoiceInput(SettingsValues c0804d, InputEvent event, int i, boolean z, UIUpdateHandler handlerC0650c) {
        this.mHasModifiedEvent = false;
        this.mVoiceComposing = false;
        String string = event.getOutputText().toString();
        InputEventContext c0920g = new InputEventContext(c0804d, event, SystemClock.uptimeMillis(), this.mCommitType, getShiftState(c0804d, i));
        this.mRichInputConnection.beginBatchEdit();
        // autoCorrectAndCommit may commit `string` itself (a single separator-class code point,
        // such as a one-char emoji), so it reports that and we must not commit it again.
        boolean separatorAlreadyCommitted = false;
        if (this.mComposingTracker.isComposing()) {
            separatorAlreadyCommitted = autoCorrectAndCommit(c0804d, string, handlerC0650c, InputSource.INTERNAL);
        } else {
            clearComposingText(true);
        }
        this.mIme.suggestionUpdater.requestDelayed(SuggestionUpdater.Reason.AFTER_KEYSTROKE);
        String strM4443a = stripLeadingDot(string);
        if (4 == this.mCommitType) {
            appendAutoSpace(c0804d, InputSource.INTERNAL);
        }
        if (!separatorAlreadyCommitted) {
            this.mCommitController.commitVoiceText(c0804d, strM4443a, InputSource.INTERNAL);
        }
        this.mRichInputConnection.endBatchEdit();
        this.mCommitType = 0;
        this.mLastCommittedText = strM4443a;
        this.mLastCommitFromVoice = z;
        c0920g.markKeyHandled();
        c0920g.setUiUpdateMode(1);
        return c0920g;
    }

    /**
     * Shows a dictation partial as composing text. A typed composing word is committed first, or
     * the partial would overwrite it while the tracker still believes it is there. A partial never
     * makes the tracker compose, so "tracker is composing" always means a typed word.
     */
    public void setVoiceComposingText(String text) {
        this.mRichInputConnection.beginBatchEdit();
        if (this.mComposingTracker.isComposing()) {
            autoCorrectAndCommit(this.mIme.getSettingsValues(), "", this.mIme.uiUpdateHandler, InputSource.INTERNAL);
            if (this.mComposingTracker.isComposing()) {
                // Nothing was committable: retire the word as it stands.
                this.mRichInputConnection.finishComposingText();
                clearComposingText(true);
            }
            this.mVoiceComposing = false;
        }
        if (this.mVoiceComposing || !TextUtils.isEmpty(text)) {
            this.mVoiceComposing = true;
            this.mRichInputConnection.setComposingText(text, 1);
        }
        this.mRichInputConnection.endBatchEdit();
    }

    /**
     * Dictation ended with no final result: keep the partial as ordinary text. A typed word that
     * has since taken the composing region is left alone.
     */
    public void finishVoiceComposingText() {
        if (!this.mVoiceComposing) {
            return;
        }
        this.mVoiceComposing = false;
        if (!this.mComposingTracker.isComposing()) {
            this.mRichInputConnection.beginBatchEdit();
            this.mRichInputConnection.finishComposingText();
            this.mRichInputConnection.endBatchEdit();
        }
    }

    /** Whether the add-word hint may apply: only a typed (or resumed) word can be unknown. */
    private static boolean mayBeUnknownWord(SuggestedWords.SuggestedWordInfo suggestedWordInfoVar) {
        return suggestedWordInfoVar.isKind(0) || suggestedWordInfoVar.isKind(10);
    }

    /**
     * After a manual pick: the add-word hint when the typed word is unknown, else the post-pick
     * update when {@code requestUpdate}. The lookup runs on the engine thread and delivers on the
     * main thread; a pick from an ended session shows nothing.
     */
    private void showAddWordHintOrUpdate(final String checkWord, final String hintWord, final boolean requestUpdate) {
        final int generation = getSessionGeneration();
        EngineThreads.post(() -> {
            final boolean unknown = !this.mDictionaryLoader.isWordValid(checkWord, true);
            Runnable deliver = () -> {
                if (getSessionGeneration() != generation) {
                    return;
                }
                if (unknown) {
                    this.mSuggestionStripListener.showAddToDictionaryHint(hintWord);
                } else if (requestUpdate) {
                    this.mIme.suggestionUpdater.requestDelayed(SuggestionUpdater.Reason.AFTER_MANUAL_PICK);
                }
            };
            if (Looper.myLooper() == Looper.getMainLooper()) {
                deliver.run();   // the tests run engine ops inline on the main thread
            } else {
                this.mIme.uiUpdateHandler.post(deliver);
            }
        });
    }

    /**
     * Commits a word tapped in the suggestion strip (prediction path for CJK) and offers add-to-dictionary for unknown words.
     * @param c0804d current settings values
     * @param interfaceC0648a keyboard view callback (used for symbol page order)
     * @param i symbol page order / shift state
     * @param handlerC0650c UI update handler
     * @param enumC0690f input source (typically TOUCH for suggestion strip picks)
     */
    public InputEventContext handleManualPick(SettingsValues c0804d, SuggestedWords.SuggestedWordInfo suggestedWordInfoVar, SymbolPageProvider interfaceC0648a, int i, UIUpdateHandler handlerC0650c, InputSource enumC0690f) {
        String strMo4204c;
        InputEventContext c0920g;
        boolean zM4410a;
        SuggestedWords c0666ac = this.mCurrentSuggestions;
        String strM4471b = processClipboardMarker(suggestedWordInfoVar.word);
        resetSpaceTimestamp();
        if (strM4471b.length() == 1 && c0666ac.isAutoCorrection()) {
            return processInputEvent(c0804d, InputEvent.createForSuggestionWithFirstChar(suggestedWordInfoVar), interfaceC0648a, InputSource.UNKNOWN, i, handlerC0650c);
        }
        InputEventContext c0920g2 = new InputEventContext(c0804d, InputEvent.createForSuggestion(suggestedWordInfoVar), SystemClock.uptimeMillis(), this.mCommitType, interfaceC0648a.getSymbolPageOrder());
        c0920g2.markKeyHandled();
        this.mRichInputConnection.beginBatchEdit();
        if (suggestedWordInfoVar.isKind(6)) {
            this.mCurrentSuggestions = SuggestedWords.EMPTY;
            this.mSuggestionStripListener.setNeutralSuggestionStrip();
            c0920g2.setUiUpdateMode(1);
            clearComposingText(true);
            this.mRichInputConnection.commitCompletion(suggestedWordInfoVar.completionInfo);
            this.mRichInputConnection.endBatchEdit();
            return c0920g2;
        }
        if (this.mDictionaryLoader.isDictionaryReady()) {
            strMo4204c = this.mDictionaryLoader.getMainDictionary().getWordSeparator(this.mRichInputConnection.getTextContextBefore() + strM4471b);
        } else {
            strMo4204c = " ";
        }
        boolean zM4407a = mayBeUnknownWord(suggestedWordInfoVar);
        this.mShouldAppendSpace = this.mRichInputConnection.isCharAfterCursorInSet(strMo4204c);
        if (LocaleUtils.isChineseOrJapanese(SubtypeManager.getInstance().getCurrentSubtypeLocale())) {
            c0920g = c0920g2;
            zM4410a = commitPredictionWord(c0804d, suggestedWordInfoVar, CommitEventRecord.CommitType.MANUAL_PICK, enumC0690f, c0920g2, handlerC0650c, strMo4204c);
        } else {
            c0920g = c0920g2;
            commitWord(c0804d, strM4471b, CommitEventRecord.CommitType.MANUAL_PICK, strMo4204c, enumC0690f);
            zM4410a = true;
        }
        this.mJustCommitted = true;
        this.mRichInputConnection.endBatchEdit();
        this.mEventDispatcher.disableRevert();
        this.mCommitType = 0;
        c0920g.setUiUpdateMode(1);
        if (zM4407a) {
            showAddWordHintOrUpdate(suggestedWordInfoVar.word, strM4471b, zM4410a);
        } else if (zM4410a) {
            this.mIme.suggestionUpdater.requestDelayed(SuggestionUpdater.Reason.AFTER_MANUAL_PICK);
        }
        return c0920g;
    }

    /**
     * Tracks cursor moves within the composing word and resets composing state when the cursor
     * jumps elsewhere. Returns {@code true} if the change was meaningful.
     * @param i old selection start
     * @param i2 old selection end
     * @param i3 new selection start
     * @param i4 new selection end
     * @param c0804d current settings values
     */
    public boolean onUpdateSelection(int i, int i2, int i3, int i4, SettingsValues c0804d) {
        if (this.mRichInputConnection.isSelectionUnchanged(i, i3, i2, i4)) {
            if (BuildConfig.DEBUG) Log.d("TEXT_EDIT_DEBUG", "onUpdateSelection: UNCHANGED old=[" + i + "," + i2 + "] new=[" + i3 + "," + i4 + "] composing='" + mComposingTracker.getComposingText() + "'");
            return false;
        }
        this.mHasModifiedEvent = false;
        this.mCommitType = 0;
        boolean z = (i == i3 && i2 == i4 && this.mComposingTracker.isComposing()) ? false : true;
        boolean z2 = (i == i2 && i3 == i4) ? false : true;
        int i5 = i3 - i;
        if (BuildConfig.DEBUG) Log.d("TEXT_EDIT_DEBUG", "onUpdateSelection: old=[" + i + "," + i2 + "] new=[" + i3 + "," + i4 + "] z=" + z + " z2=" + z2 + " delta=" + i5 + " composing='" + mComposingTracker.getComposingText() + "' cursorMoved=" + mComposingTracker.isCursorMoved());
        // An app emptying the field (a Send button) reports the same [len,len] -> [0,0] jump as a
        // caret placed at the start of a field-initial composing word; only an empty editor tells
        // them apart. Without this the sent word comes back in front of the next key.
        boolean editorEmptied = z && i3 == 0 && i4 == 0 && this.mRichInputConnection.isEditorEmpty();
        if (z2 || !c0804d.shouldShowLxxButton || (z && (editorEmptied || !this.mComposingTracker.moveCursorByCharCount(i5)))) {
            if (BuildConfig.DEBUG) Log.d("TEXT_EDIT_DEBUG", "onUpdateSelection -> resetComposingAndSelect([" + i3 + "," + i4 + "]) editorEmptied=" + editorEmptied);
            // The emptied field's suggestions describe a word that is no longer there.
            resetComposingAndSelect(i3, i4, editorEmptied);
        } else {
            if (BuildConfig.DEBUG) Log.d("TEXT_EDIT_DEBUG", "onUpdateSelection -> commitPath: resetConnection([" + i3 + "," + i4 + "])");
            updateDynamicLearningState();
            this.mRichInputConnection.resetConnection(i3, i4, false);
            commitTouchEventText();
        }
        this.mMoreKeysController.reset();
        this.mRichInputConnection.clearBackgroundSpans();
        this.mIme.uiUpdateHandler.postUpdateShiftState(true, true);
        return true;
    }

    /**
     * Word content on both sides of the cursor: a deliberate mid-word placement that skips recorrection.
     * @param c0804d current settings values
     */
    private boolean isCursorMidWord(SettingsValues c0804d) {
        boolean hasWordCharAfterCursor = this.mRichInputConnection.hasWordAfterCursor(c0804d.spacingAndPunctuation);
        return hasWordCharAfterCursor && this.mRichInputConnection.hasWordBeforeCursor(c0804d.spacingAndPunctuation);
    }

    /** Tracked-key id: device id in the high word (the long cast before the shift matters), key code in the low. */
    private long getKeyEventId(KeyEvent keyEvent) {
        return ((long) keyEvent.getDeviceId() << 32) + keyEvent.getKeyCode();
    }

    public boolean isTrackedKeyRepeat(KeyEvent keyEvent) {
        return keyEvent.getRepeatCount() > 0 && this.mTrackedKeyEvents.contains(Long.valueOf(getKeyEventId(keyEvent)));
    }

    /** A repeat for a key held before the IME got focus (never tracked); such events are discarded. */
    public boolean isUntrackedKeyRepeat(KeyEvent keyEvent) {
        return keyEvent.getRepeatCount() > 0 && !this.mTrackedKeyEvents.contains(Long.valueOf(getKeyEventId(keyEvent)));
    }

    public boolean isKeyTracked(KeyEvent keyEvent) {
        return this.mTrackedKeyEvents.contains(Long.valueOf(getKeyEventId(keyEvent)));
    }

    public void trackKeyEvent(KeyEvent keyEvent) {
        this.mTrackedKeyEvents.add(Long.valueOf(getKeyEventId(keyEvent)));
    }

    public boolean untrackKeyEvent(KeyEvent keyEvent) {
        return this.mTrackedKeyEvents.remove(Long.valueOf(getKeyEventId(keyEvent)));
    }

    /**
     * Shift-held tracking for on-screen keys: Shift (-1) sets it, any key but backspace (-5) clears it.
     * @param i the key code of the pressed soft key
     */
    public void onSoftKeyDown(int i) {
        if (i == -1) {
            this.mBackspaceController.mIsShiftHeld = true;
        } else if (i != -5) {
            this.mBackspaceController.mIsShiftHeld = false;
        }
    }

    /**
     * @param i the key code of the released soft key
     */
    public void onSoftKeyUp(int i) {
        if (i != -5) {
            this.mBackspaceController.mIsShiftHeld = false;
        }
    }

    /** Updates the shift-held and backspace-active flags used by accelerated delete. */
    public void onHardwareKeyDown(KeyEvent keyEvent) {
        int keyCode = keyEvent.getKeyCode();
        if (keyEvent.getRepeatCount() == 0) {
            if (keyCode == 59 || keyCode == 60) {
                this.mBackspaceController.mIsShiftHeld = true;
            } else if (keyCode != 67) {
                this.mBackspaceController.mIsShiftHeld = false;
            }
            if (keyCode == 67) {
                this.mBackspaceController.mIsBackspaceActive = true;
            }
        }
    }

    public void onHardwareKeyUp(KeyEvent keyEvent) {
        if (keyEvent.getKeyCode() != 67) {
            this.mBackspaceController.mIsShiftHeld = false;
        } else {
            this.mBackspaceController.mIsBackspaceActive = false;
        }
    }

    /**
     * Central input pipeline: keyboard-state transform, at-dot substitution, auto-capitalize,
     * gesture end, functional keys, separators and character keys.
     * @param c0804d current settings values
     * @param interfaceC0648a keyboard view callback (provides shift state and symbol page)
     * @param enumC0690f the source of the event (TOUCH, HARDWARE, INTERNAL, etc.)
     * @param i symbol page order / shift state
     * @param handlerC0650c UI update handler for posting deferred updates
     */
    public InputEventContext processInputEvent(SettingsValues c0804d, InputEvent event, SymbolPageProvider interfaceC0648a, InputSource enumC0690f, int i, UIUpdateHandler handlerC0650c) {
        Keyboard c0965eM6834l;

        int i2 = event.mCodePoint;
        KeyboardSwitcher c0979iM4088ac = this.mIme.getKeyboardSwitcher();
        InputEvent c0914a2 = (c0979iM4088ac != null
                && (c0979iM4088ac.isMainKeyboardShowing() || c0979iM4088ac.isInSymbolMode()))
                ? InputEvent.createFromSwitchedKeyboard(event) : event;

        InputEventContext c0920g = new InputEventContext(c0804d, c0914a2, SystemClock.uptimeMillis(), this.mCommitType, getShiftState(c0804d, interfaceC0648a.getSymbolPageOrder()));
        c0920g.setShiftPressed(interfaceC0648a.isManualShiftAndShiftPressing());
        c0920g.setInputSource(enumC0690f);

        // Delete acceleration resets unless this is a backspace within 200 ms of the last event.
        if (c0914a2.mKeyCode != -5 || c0920g.timestamp > this.mLastGestureTimestamp + 200) {
            this.mBackspaceController.resetDeleteCounters();
        }

        this.mSuggestionEngine.setSkipNuanceCheck(false);
        this.mLastGestureTimestamp = c0920g.timestamp;
        this.mRichInputConnection.beginBatchEdit();

        if (!this.mComposingTracker.isComposing()) {
            this.mIsAutoCorrectActive = false;
        }

        InputEvent processedEvent = isEmailVariationField() ? handleAtDotSubstitution(c0914a2) : c0914a2;

        if (c0804d.isAutoCapsEnabled && !processedEvent.isModifierKey()) {
            processedEvent = autoCapitalize(processedEvent, c0920g);
        }

        if (processedEvent.mCodePoint != 32) {
            resetSpaceTimestamp();
            this.mJustCommitted = false;
        }

        boolean isSpaceKey = false;
        boolean hasProcessedGesture = false;
        boolean hasModifiedEvent = false;
        boolean shouldContinueProcessing = false;

        while (processedEvent != null) {
            if (processedEvent.isGestureEnd()) {
                this.mHasModifiedEvent = false;
                handleGestureEnd(processedEvent, c0920g);
            } else if (processedEvent.isFunctionalKeyEvent()) {
                handleFunctionalKeyEvent(processedEvent, c0804d, c0920g, i);
            } else {
                SmartPunctuationAnalyzer smartPunctuation = this.mSmartPunctuationAnalyzer;
                if (smartPunctuation != null) {
                    if (smartPunctuation.isDumbMode() && processedEvent.mCodePoint == 32) {
                        this.mSmartPunctuationAnalyzer.clearDumbMode();
                        this.mSuggestionEngine.setBlockAutoCorrect(false);
                    }

                    if (isGestureEnabled() && !hasModifiedEvent) {
                        SmartPunctuationAnalyzer.Result punctuationResult = processGestureRecognition(processedEvent, c0920g);
                        hasProcessedGesture = punctuationResult.appendAutoSpace;
                        InputEvent modifiedEvent = punctuationResult.event;

                        if (punctuationResult.eventsPrepended && !LocaleUtils.isCurrentSubtypeJapanese()) {
                            // Re-run the loop on the rewritten event chain.
                            c0920g.setInputSource(InputSource.INTERNAL);
                            processedEvent = modifiedEvent;
                            hasModifiedEvent = true;
                            shouldContinueProcessing = true;
                            continue;
                        }
                    }
                }

                processCharacterKeyEvent(processedEvent, c0804d, c0920g, i, handlerC0650c, shouldContinueProcessing);
                isSpaceKey = processedEvent.mCodePoint == 32;
            }

            processedEvent = processedEvent.mNextEvent;
        }

        if (c0920g.isKeyHandled()) {
            this.mHasModifiedEvent = (c0920g.wasAutoCorrectApplied() && isSpaceKey) || hasProcessedGesture;
        }

        if (!c0920g.wasAutoCorrectApplied() && c0914a2.mKeyCode != -1 && c0914a2.mKeyCode != -2 && c0914a2.mKeyCode != -3 && !isWordSeparator(c0920g.settingsValues, i2) && (this.mComposingTracker.isComposing() || c0914a2.mKeyCode != -5)) {
            this.mEventDispatcher.disableRevert();
        }

        if (-5 != c0914a2.mKeyCode) {
            this.mLastCommittedText = null;
        }

        this.mRichInputConnection.endBatchEdit();
        return c0920g;
    }

    public boolean hasSelectionOrCursor() {
        return this.mRichInputConnection.hasSelection() || this.mRichInputConnection.getCursorStart() > 0;
    }

    /**
     * Deletes the selection, everything in a password field, else a character (CJK) or a word. Returns whether anything was deleted.
     * @param c0804d current settings values
     */
    public boolean handleSwipeDelete(SettingsValues c0804d) {
        return this.mBackspaceController.handleSwipeDelete(c0804d);
    }


    /**
     * Flushes composing state and re-enables the suggestion worker, e.g. after a tap moves the cursor into a committed word.
     * @param c0804d current settings values
     * @param i shift state / symbol page order for the suggestion context
     * @param handlerC0650c UI update handler for clearing stale pending suggestion updates
     */
    public void onSuggestionStripUpdate(SettingsValues c0804d, int i, UIUpdateHandler handlerC0650c) {
        this.mSuggestionRequestQueue.enableSuggestions();
        handlerC0650c.postShowSuggestions(SuggestedWords.EMPTY);
        handlerC0650c.cancelPendingSuggestionUpdates();
        this.mRichInputConnection.beginBatchEdit();
        if (!this.mComposingTracker.isComposing()) {
            this.mRichInputConnection.clearBackgroundSpans();
            commitTouchEventText();
        } else if (this.mComposingTracker.isCursorMoved()) {
            resetComposingAndSelect(this.mRichInputConnection.getCursorStart(), this.mRichInputConnection.getCursorEnd(), true);
        }
        int iM5844h = this.mRichInputConnection.getCodePointBeforeCursor();
        if (Character.isLetterOrDigit(iM5844h) || c0804d.isUsuallyFollowedBySpace(iM5844h)) {
            boolean z = i != getCapsMode(c0804d);
            this.mCommitType = 4;
            if (!z) {
                this.mIme.resetKeyboardState();
            }
        }
        this.mRichInputConnection.endBatchEdit();
        this.mComposingTracker.setShiftState(getShiftState(c0804d, i));
    }

    /**
     * Requests suggestions for the composing word; results arrive asynchronously at the strip listener.
     * @param c0804d current settings values (not forwarded)
     */
    public void requestSuggestions(SettingsValues c0804d) {
        if (!needsSuggestionLookups(c0804d)) {
            return;
        }
        this.mSuggestionRequestQueue.requestSuggestions();
    }

    /**
     * Whether a candidate lookup serves anyone: suggestions, auto-correction, or CJK input. When
     * none, the per-keystroke lookup is skipped; the word is still learned at commit
     * ({@link DynamicLearningManager#learnTypedWord}). A pending swipe still needs predictions.
     */
    static boolean needsSuggestionLookups(SettingsValues sv) {
        return sv.isPredictionsEnabled
                || sv.isAutoCorrectionEnabledPerUserSettings
                || sv.editorCapabilities.shouldShowChineseSuggestions
                || sv.editorCapabilities.shouldShowJapaneseSuggestions
                || LocaleUtils.isCurrentSubtypeChinese()
                || LocaleUtils.isCurrentSubtypeJapanese();
    }

    /**
     * @param enumC0690f the input source that triggered the prediction request
     */
    public void requestPredictions(InputSource enumC0690f) {
        if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "InputLogic.requestPredictions: source=" + enumC0690f + " — refreshing suggestion strip (does NOT commit anything)");
        SettingsValues sv = SettingsManager.getInstance().getSettingsValues();
        if (sv != null && !needsSuggestionLookups(sv) && !NuanceSDKManager.isGesturePending()) {
            return;
        }
        this.mSuggestionRequestQueue.requestPredictions(enumC0690f);
    }

    /**
     * Disables the suggestion worker and clears the strip, for editors without suggestions.
     * @param handlerC0650c UI update handler used to clear the strip immediately
     */
    public void disableSuggestions(UIUpdateHandler handlerC0650c) {
        this.mSuggestionRequestQueue.disableSuggestions();
        handlerC0650c.postShowSuggestions(SuggestedWords.EMPTY);
    }

    /**
     * Stores the auto-correct candidate in the tracker, caches the list, and refreshes the composing indicator.
     * @param c0666ac the new suggested words (may be {@link SuggestedWords#EMPTY})
     * @param c0804d current settings values
     * @param handlerC0650c UI update handler (unused here)
     */
    public void onSuggestionsReceived(SuggestedWords c0666ac, SettingsValues c0804d, UIUpdateHandler handlerC0650c) {
        String strMo4284a;
        int iM4296a;
        // Tracker writes need a composing word (a reply may cross a session teardown). The
        // mCurrentSuggestions write below must stay ungated: it is also the gesture-commit
        // channel, and a gesture is not composing.
        if (SuggestedWords.EMPTY != c0666ac && this.mComposingTracker.isComposing()) {
            // The "score" stored is the candidate's suggestion kind; handleSeparatorInput treats
            // nonzero as AC eligible, relying on a will-auto-correct candidate never being kind 0.
            if (c0666ac.mWillAutoCorrect) {
                strMo4284a = c0666ac.getWord(SuggestedWords.getMinSuggestionsIndex());
                iM4296a = c0666ac.getWordInfo(SuggestedWords.getMinSuggestionsIndex()).getKind();
            } else {
                strMo4284a = c0666ac.mAutoCorrectWord;
                iM4296a = 0;
            }
            if (BuildConfig.DEBUG) {
            android.util.Log.d("PKB_SPACE_AUTOCORRECT_DEBUG", "onSuggestionsReceived: willAC=" + c0666ac.mWillAutoCorrect
                + " acWord='" + strMo4284a + "' score=" + iM4296a
                + " suggCount=" + c0666ac.size()
                + " composing='" + this.mComposingTracker.getComposingText() + "'"
                + " thread=" + Thread.currentThread().getName());
            }
            if (c0666ac.mRequestEditCount >= 0) {
                this.mComposingTracker.setAutoCorrection(strMo4284a, iM4296a, c0666ac.mRequestEditCount);
            } else {
                this.mComposingTracker.setAutoCorrection(strMo4284a, iM4296a);
            }
            if (c0666ac.size() > SuggestedWords.getMinSuggestionsIndex()) {
                this.mComposingTracker.setPickedSuggestion(c0666ac.getWordInfo(SuggestedWords.getMinSuggestionsIndex()));
            }
        }
        this.mCurrentSuggestions = c0666ac;
        boolean z = c0666ac.mWillAutoCorrect;
        if (!this.mComposingTracker.isComposing() || this.mBackspaceController.mIsBackspaceActive) {
            return;
        }
        if (isCjkLocale()) {
            if (!LocaleUtils.isCurrentSubtypePinyin()) {
                return;
            }
        }
        if (LocaleUtils.isCurrentSubtypeJapanese()) {
            return;
        }
        // The resend only changes the auto-correct indicator (off when a word follows the
        // cursor), so skip the setComposingText IPC unless that state changes.
        boolean effectiveAutoCorrect = z && !this.mRichInputConnection.hasWordAfterCursor(c0804d.spacingAndPunctuation);
        if (this.mIsAutoCorrectActive == effectiveAutoCorrect) {
            return;
        }
        this.mIsAutoCorrectActive = effectiveAutoCorrect;
        if (!this.mComposingTracker.isCursorMoved()) {
            // After the connection was invalidated (Enter editor action) there is no composing
            // region, so setComposingText would insert a duplicate; catches an in-flight reply.
            if (this.mRichInputConnection.getCursorStart() == -1) {
                return;
            }
            setComposingTextInternal(getComposingTextWithIndicator(this.mComposingTracker.getComposingText()), 1);
        }
    }

    /**
     * @param c0920g the current input event context
     */
    private void handleGestureEnd(InputEvent event, InputEventContext c0920g) {
        CharSequence charSequenceM5933n = event.getOutputText();
        if (!TextUtils.isEmpty(charSequenceM5933n)) {
            this.mRichInputConnection.commitText(charSequenceM5933n, 1);
            c0920g.markKeyHandled();
        }
        if (this.mComposingTracker.isComposing()) {
            setComposingTextInternal(this.mComposingTracker.getComposingText(), 1);
            c0920g.markKeyHandled();
            c0920g.setShouldUpdateSuggestions();
        }
    }

    /**
     * @param c0804d current settings values
     * @param c0920g the current input event context
     * @param i the symbol page order
     */
    private void handleFunctionalKeyEvent(InputEvent event, SettingsValues c0804d, InputEventContext c0920g, int i) {
        int i2 = event.mKeyCode;
        if (i2 == -43) {
            DeviceProfile.setForceVkbMode(false);
            return;
        }
        if (i2 == -37 || i2 == -34) {
            return;
        }
        if (i2 != 10) {
            switch (i2) {
                case -41:
                    this.mIme.updateSuggestionsFromSubtype(InputSource.SOFTWARE);
                    return;
                case -40:
                case -39:
                    return;
                default:
                    switch (i2) {
                        case -29:
                        case -28:
                        case -27:
                        case -25:
                        case -24:
                        case -23:
                            return;
                        case -26:
                            InputSettingsLauncher.invokeInputSettings(this.mIme);
                            return;
                        case -22:
                            toggleKeyboardDirection(event.isKeyRepeat());
                            return;
                        default:
                            switch (i2) {
                                case -16:
                                case -15:
                                case -14:
                                case -13:
                                case -11:
                                case -7 /* -7 */:
                                    return;
                                case -12:
                                    break;
                                case -10:
                                    launchVoiceInput();
                                    return;
                                case -9:
                                    performEditorActionById(7);
                                    return;
                                case -8 /* -8 */:
                                    performEditorActionById(5);
                                    return;
                                case -6 /* -6 */:
                                    toggleFullscreenMode();
                                    return;
                                case -5 /* -5 */:
                                    if ((c0920g.getInputSource() == InputSource.HARDWARE) && !this.mComposingTracker.isComposing() && !this.mEventDispatcher.isRevertEligible()) {
                                        if (!isCursorMidWord(c0804d)) {
                                            performRecorrection(c0804d, true, i);
                                        }
                                    }
                                    int i3 = !this.mRichInputConnection.hasCursorPosition() ? 1 : 0;
                                    if (shouldProcessBackspace(event, i3, this.mBackspaceController.mLastDeleteState, this.mRichInputConnection)) {
                                        handleBackspace(event, c0920g, c0804d, i);
                                        c0920g.markKeyHandled();
                                    }
                                    int i4 = this.mBackspaceController.mDeleteRepeatCount;
                                    if (i4 == 0 || i4 == 1) {
                                        this.mBackspaceController.mLastDeleteState = i3;
                                        return;
                                    }
                                    return;
                                default:
                                    switch (i2) {
                                        case -3 /* -3 */:
                                        case -2 /* -2 */:
                                        case 0:
                                            return;
                                        case -1 /* -1 */:
                                            c0920g.setUiUpdateMode(1);
                                            if (this.mCurrentSuggestions.isValidInputStyle() && !this.mRichInputConnection.hasSelection()) {
                                                c0920g.setShouldUpdateSuggestions();
                                            }
                                            return;
                                        default:
                                            throw new RuntimeException("Unknown key code : " + event.mKeyCode);
                                    }
                            }
                    }
            }
        }
        handleEnterKey(c0920g);
        c0920g.markKeyHandled();
    }

    /**
     * Whether the IME handles this backspace rather than passing it through: {@code false} only
     * for a repeat with the cursor unknown ({@code i} = 1) and the delete state {@code i2} unchanged.
     * @param i current cursor-unavailable flag (1 if cursor position unknown, else 0)
     * @param i2 delete state from the previous backspace event
     * @param c0909x the rich input connection
     */
    public boolean shouldProcessBackspace(InputEvent event, int i, int i2, RichInputConnection c0909x) {
        return this.mBackspaceController.shouldProcessBackspace(event, i, i2, c0909x);
    }

    public void pasteFromClipboard() {
        pasteText(ClipboardUtil.getClipboardText(this.mIme));
    }

    /** Finishes any composing text, then commits {@code charSequence} (no-op when null). */
    public void pasteText(CharSequence charSequence) {
        if (charSequence != null) {
            this.mRichInputConnection.beginBatchEdit();
            if (this.mComposingTracker.isComposing() || this.mTouchHighlightTracker.isHighlightActive()) {
                this.mRichInputConnection.finishComposingText();
                this.mTouchHighlightTracker.clear();
                this.mComposingTracker.clearAll();
            }
            this.mRichInputConnection.commitText(charSequence, 1);
            this.mComposingTracker.clearAll();
            this.mRichInputConnection.endBatchEdit();
            this.mIme.suggestionUpdater.requestDelayedLocaleAware(SuggestionUpdater.Reason.AFTER_KEYSTROKE);
        }
    }

    /**
     * @param c0804d current settings values
     * @param c0920g the current input event context
     * @param i the symbol page order
     * @param handlerC0650c UI update handler
     * @param z true when the event is a smart-punctuation rewrite being re-run
     */
    private void processCharacterKeyEvent(InputEvent event, SettingsValues c0804d, InputEventContext c0920g, int i, UIUpdateHandler handlerC0650c, boolean z) {
        c0920g.markKeyHandled();
        if (event.mCodePoint == 10) {
            handleEnterKey(c0920g);
            return;
        }
        if ((c0920g.getInputSource() == InputSource.HARDWARE) && !this.mComposingTracker.isComposing() && !LocaleUtils.isCurrentSubtypePinyin()) {
            // A deliberate mid-word caret inserts there; recorrecting would reopen the word and
            // snap the caret to its end.
            if (!isCursorMidWord(c0804d)) {
                performRecorrection(c0804d, true, i);
            }
        }
        routeKeyEvent(event, c0920g, handlerC0650c, z);
    }

    /**
     * @param c0920g the current input event context
     * @param handlerC0650c UI update handler
     * @param z true when the event is a smart-punctuation rewrite being re-run
     */
    private void routeKeyEvent(InputEvent event, InputEventContext c0920g, UIUpdateHandler handlerC0650c, boolean z) {
        if (!this.mComposingTracker.isComposing()) {
            this.mRichInputConnection.clearBackgroundSpans();
            if (this.mSuggestionStripListener.isShowingMoreSuggestions()) {
                this.mSuggestionStripListener.dismissMoreSuggestions();
                this.mMoreKeysController.reset();
            }
        }
        int i = event.mCodePoint;
        this.mCommitType = 0;
        if (isWordSeparator(c0920g.settingsValues, i)) {
            handleSeparatorInput(event, c0920g, handlerC0650c);
            return;
        }
        if (4 == c0920g.commitType) {
            if (this.mComposingTracker.isCursorMoved()) {
                resetComposingAndSelect(this.mRichInputConnection.getCursorStart(), this.mRichInputConnection.getCursorEnd(), true);
            } else {
                commitTypedWord(c0920g.settingsValues, "", c0920g.getInputSource());
            }
        }
        handleCharacterInput(event, c0920g.settingsValues, c0920g, z);
    }

    /**
     * @param c0804d current settings values
     * @param c0920g the current input event context
     * @param z true when the event is a smart-punctuation rewrite being re-run
     */
    private void handleCharacterInput(InputEvent event, SettingsValues c0804d, InputEventContext c0920g, boolean z) {
        EditorInfo editorInfoM4500g;
        int i = event.mCodePoint;
        boolean zM4354g = this.mComposingTracker.isComposing();
        if (4 == c0920g.commitType && !c0804d.isWordConnector(i)) {
            if (zM4354g) {
                throw new RuntimeException("Should not be composing here");
            }
            appendAutoSpace(c0804d, c0920g.getInputSource());
        }
        if (this.mComposingTracker.isCursorMoved() && zM4354g) {
            if (isComposingWordStillInEditor()) {
                insertCodePointInsideComposingWord(event.mCodePoint, c0920g);
                return;
            }
            // The word is no longer in the editor: drop it and type the key as a new word.
            resetComposingAndSelect(this.mRichInputConnection.getCursorStart(), this.mRichInputConnection.getCursorEnd(), true);
            zM4354g = this.mComposingTracker.isComposing();
        }
        boolean z2 = this.mComposingTracker.hasInputMethodConverter() && ((editorInfoM4500g = getCurrentEditorInfo()) == null || !(InputTypeUtils.isDateTimeInputType(editorInfoM4500g.inputType) || InputTypeUtils.isNumberInputType(editorInfoM4500g.inputType) || InputTypeUtils.isPhoneInputType(editorInfoM4500g.inputType)));
        if (!zM4354g && c0804d.isWordCodePoint(i) && ((c0804d.shouldShowLxxButton || z2) && (!LocaleUtils.isCurrentSubtypeJapanese() || !isAnyPasswordField()))) {
            zM4354g = !c0804d.spacingAndPunctuation.isWordConnector(i);
            clearComposingText(false);
        }
        if (this.mTouchHighlightTracker.onInputEvent(event, c0804d.textHighlightColorForMultiTap)) {
            if (zM4354g) {
                if (event.isShiftLocked()) {
                    this.mComposingTracker.processInputEvent(InputEvent.createHardwareKeyPress(67, -5, null, false, event.getTimestamp()));
                }
                if (z && this.mComposingTracker.getComposingText().length() > 0) {
                    autoCorrectAndCommitExtended(this.mIme.getSettingsValues(), event.getOutputText().toString(), this.mIme.uiUpdateHandler, InputSource.SOFTWARE, z);
                    c0920g.setAutoCorrectApplied();
                    c0920g.setShouldUpdateSuggestions();
                    return;
                } else {
                    String composingBeforeShift = this.mComposingTracker.getComposingText();
                    this.mComposingTracker.processInputEventWithShift(event, c0920g.symbolPageOrder);
                    setComposingTextInternal(getComposingTextWithIndicator(this.mComposingTracker.getComposingText()), 1);
                    c0920g.setShouldUpdateSuggestions();
                    return;
                }
            }
            if (this.mTouchHighlightTracker.isHighlightActive()) {
                this.mRichInputConnection.finishComposingText();
            } else if (shouldStripSpace(event, c0920g) && trySwapPunctuation(event, c0920g)) {
                this.mCommitType = 3;
            } else {
                commitCharacter(c0804d, i, c0920g.getInputSource());
            }
        }
    }

    /**
     * A separator keystroke in four ordered stages: {@link #resolveSpacelessSpaceSuppression},
     * {@link #retireComposingWordForSeparator}, {@link #appendAutoSpaceBeforeSeparatorIfOwed},
     * {@link #applySeparatorDisposition}. The order is load-bearing: {@code shouldStripSpace} may
     * delete a trailing space, and the last two stages read the editor after the word commit.
     * @param c0920g the current input event context
     * @param handlerC0650c UI update handler
     */
    private void handleSeparatorInput(InputEvent event, InputEventContext c0920g, UIUpdateHandler handlerC0650c) {
        final int codePoint = event.mCodePoint;
        final SettingsValues c0804d = c0920g.settingsValues;

        // Read before the commit below changes what is composing.
        final boolean suppressSpace = resolveSpacelessSpaceSuppression(c0804d, codePoint);

        if (this.mComposingTracker.isCursorMoved()) {
            resetComposingAndSelect(this.mRichInputConnection.getCursorStart(), this.mRichInputConnection.getCursorEnd(), true);
        }
        if (BuildConfig.DEBUG) {
        android.util.Log.d("PKB_SPACE_AUTOCORRECT_DEBUG", "handleSeparatorInput: codePoint=" + codePoint + " src=" + c0920g.getInputSource()
            + " composing=" + this.mComposingTracker.isComposing()
            + " composingText='" + this.mComposingTracker.getComposingText() + "'"
            + " acEnabledPerUser=" + c0804d.isAutoCorrectionEnabledPerUserSettings
            + " acMode=" + c0804d.autoCorrectionMode);
        }

        // True when a word was committed, which means the separator was already written.
        final boolean wordCommitted = retireComposingWordForSeparator(
                event, c0920g, handlerC0650c, codePoint, suppressSpace);

        // Not a pure read: may delete a trailing space, which the quote-after-digit read must see.
        final boolean stripSpace = shouldStripSpace(event, c0920g);
        final boolean quoteAfterDigit = 34 == codePoint && this.mRichInputConnection.endsWithQuoteAfterDigit();

        appendAutoSpaceBeforeSeparatorIfOwed(c0920g, c0804d, codePoint, quoteAfterDigit);

        applySeparatorDisposition(event, c0920g, c0804d, codePoint,
                wordCommitted, suppressSpace, quoteAfterDigit, stripSpace);

        c0920g.setUiUpdateMode(1);
    }

    /**
     * Whether the composing word around the caret is still in the editor. The in-word insert
     * rewrites the whole word from the tracker's copy, so a stale copy (e.g. after an app-side
     * undo/redo with no selection update) would write a deleted word back.
     */
    private boolean isComposingWordStillInEditor() {
        String composing = this.mComposingTracker.getComposingText();
        int caret = Character.offsetByCodePoints(composing, 0, this.mComposingTracker.getComposingCursorPos());
        boolean present = this.mRichInputConnection.editorHasTextAroundCursor(
                composing.substring(0, caret), composing.substring(caret));
        if (!present && BuildConfig.DEBUG) {
            Log.d("TEXT_EDIT_DEBUG", "handleCharacterInput: composing '" + composing + "' is gone from the editor; dropping it");
        }
        return present;
    }

    /**
     * Inserts at the caret inside the composing word. Order matters: derive the region start
     * before mutating the tracker, replace the whole region, then restore the caret with
     * {@link RichInputConnection#setSelectionWithinComposing} (a plain setSelection corrupts the RIC text model).
     * @param c0920g the current input event context
     */
    private void insertCodePointInsideComposingWord(int codePoint, InputEventContext c0920g) {
        String oldComposing = this.mComposingTracker.getComposingText();
        int oldCursorCharOffset = Character.offsetByCodePoints(oldComposing, 0,
                this.mComposingTracker.getComposingCursorPos());
        int composingStart = this.mRichInputConnection.getCursorEnd() - oldCursorCharOffset;

        this.mComposingTracker.insertCodePointAtCursor(codePoint);
        String fullText = this.mComposingTracker.getComposingText();
        CharSequence display = getComposingTextWithIndicator(fullText);
        setComposingTextInternal(display, 1);

        int newCursorCharOffset = Character.offsetByCodePoints(fullText, 0,
                this.mComposingTracker.getComposingCursorPos());
        int desiredCursor = composingStart + newCursorCharOffset;
        this.mRichInputConnection.setSelectionWithinComposing(desiredCursor, composingStart);
        if (BuildConfig.DEBUG) {
        android.util.Log.d("TEXT_EDIT_DEBUG", "handleCharacterInput: insertedAtCursor codePoint=" + codePoint
                + " composingNow='" + fullText + "' composingStart=" + composingStart
                + " newCursorCharOffset=" + newCursorCharOffset + " setSelection=" + desiredCursor);
        }
        c0920g.setShouldUpdateSuggestions();
    }

    /**
     * A space in a language written without spaces, with a word composing: it ends the word but is not inserted.
     * @param c0804d current settings values
     */
    private boolean resolveSpacelessSpaceSuppression(SettingsValues c0804d, int codePoint) {
        return 32 == codePoint
                && !c0804d.spacingAndPunctuation.currentLanguageHasSpaces
                && this.mComposingTracker.isComposing();
    }

    /**
     * Commits the composing word with the separator as trailing payload, or flushes touch-event
     * text. Returns {@code true} if a word was committed (the separator must not be written again).
     * @param c0920g the current input event context
     * @param handlerC0650c UI update handler
     */
    private boolean retireComposingWordForSeparator(InputEvent event, InputEventContext c0920g,
            UIUpdateHandler handlerC0650c, int codePoint, boolean suppressSpace) {
        if (!this.mComposingTracker.isComposing()) {
            commitTouchEventText();
            return false;
        }
        final SettingsValues c0804d = c0920g.settingsValues;
        flushPendingSuggestions(c0804d, handlerC0650c);
        String separatorPayload = suppressSpace ? "" : new String(Character.toChars(codePoint));
        if (isCjkLocale() || LocaleUtils.isCurrentSubtypeJapanese()) {
            handleCjkAutoCorrect(c0804d, c0920g, handlerC0650c, separatorPayload);
            return true;
        }
        int iM4368u = this.mComposingTracker.getAutoCorrectionScore();
        String acWord = this.mComposingTracker.getAutoCorrection();
        if (BuildConfig.DEBUG) {
        android.util.Log.d("PKB_SPACE_AUTOCORRECT_DEBUG", "handleSeparatorInput: acWord='" + acWord + "' acScore=" + iM4368u
            + " willTryAC=" + CommitController.shouldCommitAutoCorrectCandidate(acWord, iM4368u, c0804d.isAutoCorrectionEnabledPerUserSettings, c0804d.editorCapabilities.shouldShowSuggestions)
            + " src=" + c0920g.getInputSource());
        }
        // Kind 7 (substitution/macro) commits regardless of the auto-correct setting, but not in
        // a field that wants no suggestions.
        if (CommitController.shouldCommitAutoCorrectCandidate(acWord, iM4368u, c0804d.isAutoCorrectionEnabledPerUserSettings, c0804d.editorCapabilities.shouldShowSuggestions)) {
            autoCorrectAndCommit(c0804d, separatorPayload, handlerC0650c, c0920g.getInputSource());
            c0920g.setAutoCorrectApplied();
        } else {
            if (BuildConfig.DEBUG) {
            android.util.Log.d("PKB_SPACE_AUTOCORRECT_DEBUG", "handleSeparatorInput: AC REJECTED reason=" +
                (acWord == null ? "acWord_null"
                    : iM4368u == CommitController.KIND_SUBSTITUTION ? "macro_in_no_suggestions_field"
                    : iM4368u == 0 ? "kind_zero" : "ac_disabled"));
            }
            commitTypedWord(c0804d, separatorPayload, c0920g.getInputSource());
        }
        return true;
    }

    /**
     * Inserts the space the previous commit deferred ({@code mCommitType == 4}) before a separator
     * usually preceded by one. Not for a quote after a digit (an inch mark) or clustering symbols ({@code ?!}).
     * @param c0920g the current input event context
     * @param c0804d current settings values
     */
    private void appendAutoSpaceBeforeSeparatorIfOwed(InputEventContext c0920g,
            SettingsValues c0804d, int codePoint, boolean quoteAfterDigit) {
        if (4 != c0920g.commitType) {
            return;
        }
        boolean owed = false;
        if (34 == codePoint) {
            owed = !quoteAfterDigit;
        } else if (!c0804d.spacingAndPunctuation.clustersWithSymbols(codePoint)
                || !c0804d.spacingAndPunctuation.clustersWithSymbols(this.mRichInputConnection.getCodePointBeforeCursor())) {
            owed = c0804d.isUsuallyPrecededBySpace(codePoint);
        }
        if (owed) {
            appendAutoSpace(c0804d, c0920g.getInputSource());
        }
    }

    /**
     * One disposition per separator, in priority order: double-space period, punctuation swap,
     * plain space, anything else; each sets the {@link #mCommitType} the next key reads. The
     * short-circuits matter: the first two calls mutate the editor.
     * @param c0920g the current input event context
     * @param c0804d current settings values
     */
    private void applySeparatorDisposition(InputEvent event, InputEventContext c0920g,
            SettingsValues c0804d, int codePoint, boolean wordCommitted, boolean suppressSpace,
            boolean quoteAfterDigit, boolean stripSpace) {
        if (!wordCommitted && handleDoubleSpacePeriod(event, c0920g)) {
            this.mCommitType = 1;
            c0920g.setShouldUpdateSuggestions();
            return;
        }
        if (stripSpace && trySwapPunctuation(event, c0920g)) {
            this.mCommitType = 2;
            this.mSuggestionStripListener.setNeutralSuggestionStrip();
            return;
        }
        if (32 == codePoint) {
            this.mCommitType = 3;
            recordSpaceTimestamp(c0920g);
            if (wordCommitted || this.mCurrentSuggestions.isEmpty() || c0920g.isKeyHandled()) {
                c0920g.setShouldUpdateSuggestions();
            }
            if (!suppressSpace && !wordCommitted) {
                commitCharacter(c0804d, codePoint, c0920g.getInputSource());
            }
            return;
        }
        if ((4 == c0920g.commitType && c0804d.isUsuallyFollowedBySpace(codePoint))
                || (34 == codePoint && quoteAfterDigit)) {
            this.mCommitType = 4;
        }
        if (!wordCommitted) {
            commitCharacter(c0804d, codePoint, c0920g.getInputSource());
        }
        this.mSuggestionStripListener.setNeutralSuggestionStrip();
    }

    private boolean performEditorAction() {
        EditorInfo editorInfoM4500g = getCurrentEditorInfo();
        int iM5766a = InputTypeUtils.getImeOptionsActionIdFromEditorInfo(editorInfoM4500g);
        if (256 == iM5766a) {
            performEditorActionById(editorInfoM4500g.actionId);
            return true;
        }
        if (1 == iM5766a) {
            return false;
        }
        performEditorActionById(iM5766a);
        return true;
    }

    /**
     * Commits the composing word, or resets it when the caret moved inside it; else flushes touch-event text.
     * @param c0804d current settings values
     * @param enumC0690f the input source that triggered this flush
     */
    public void commitOrResetComposing(SettingsValues c0804d, InputSource enumC0690f) {
        mCommitController.commitOrResetComposing(c0804d, enumC0690f);
    }

    /** Neither multi-line flag is set. */
    private boolean isSingleLineField() {
        EditorInfo editorInfoM4500g = getCurrentEditorInfo();
        return (editorInfoM4500g.inputType & InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE) == 0
                && (editorInfoM4500g.inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0;
    }

    /**
     * @param c0920g the current input event context
     */
    private void handleEnterKey(InputEventContext c0920g) {
        boolean z;
        boolean z2;

        EditorInfo editorInfo = getCurrentEditorInfo();
        int imeAction = editorInfo != null ? (editorInfo.imeOptions & 255) : 0;
        int inputType = editorInfo != null ? editorInfo.inputType : 0;
        
        if ((isCjkLocale() || LocaleUtils.isCurrentSubtypeJapanese()) && this.mComposingTracker.isComposing()) {
            String strM4360m = this.mComposingTracker.getComposingText();
            commitOrResetComposing(c0920g.settingsValues, c0920g.getInputSource());
            c0920g.setUiUpdateMode(1);
            c0920g.setShouldUpdateSuggestions();
            if (strM4360m.length() <= 0 || !strM4360m.matches("[A-Za-z]+")) {
                return;
            }
            commitCharacter(c0920g.settingsValues, 32, c0920g.getInputSource());
            return;
        }
        boolean z4 = imeAction == 1;  // IME_ACTION_NONE
        boolean z5 = imeAction == 0;  // IME_ACTION_UNSPECIFIED
        boolean isHardwareInput = c0920g.getInputSource() == InputSource.HARDWARE;

        // Shift+Enter always inserts a newline. A Shift consumed by an Alt chord does not count
        // (manual shift is pending only once every Shift is released).
        boolean isShiftPressed = c0920g.isShiftPressed()
                || this.mIme.getPhysicalKeyboardStateTracker().getModifierState().isManualShiftPending();

        // Multi-line with a NONE/UNSPECIFIED action inserts a newline. IME_FLAG_NO_ENTER_ACTION is
        // deliberately ignored: it only hides the action button, and search fields set it.
        boolean isMultiline = editorInfo != null && (editorInfo.inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0;
        boolean shouldInsertNewline = isShiftPressed || (isMultiline && (z4 || z5));

        if (dev.bbkb.ime.core.device.profile.DeviceProfile.isPkb()) {
            z = !isHardwareInput && (isSingleLineField() || !z4);
            z2 = !isHardwareInput || shouldInsertNewline;
        } else {
            z = isSingleLineField() || !(z4 || z5);
            z2 = !isHardwareInput || shouldInsertNewline;
        }
        if (z ? performEditorAction() : false) {
            return;
        }
        if (z2) {
            commitOrResetComposing(c0920g.settingsValues, c0920g.getInputSource());
        }
        c0920g.setUiUpdateMode(1);
        c0920g.setShouldUpdateSuggestions();
        
        if (z2) {
            commitCharacter(c0920g.settingsValues, 10, c0920g.getInputSource());
        } else {
            // No clearAll() here: KeyEventProcessor's Enter consumer must finishComposingText()
            // before performEditorAction (keeps the WebView cursor after the word), then clearAll()
            // and cancel the queued suggestion update, or a late reply re-inserts the word.
            c0920g.setPendingEditorAction(true);
        }
    }

    /**
     * @param c0920g the current input event context
     * @param c0804d current settings values
     * @param i the symbol page order
     */
    private void handleBackspace(InputEvent event, InputEventContext c0920g, SettingsValues c0804d, int i) {
        this.mBackspaceController.handleBackspace(event, c0920g, c0804d, i);
    }

    private void launchVoiceInput() {
        this.mIme.switchToNextSubtype(InputSource.HARDWARE);
    }

    /**
     * @param z true when the key is auto-repeating
     */
    private void toggleKeyboardDirection(boolean z) {
        this.mIme.updateSymbolShift(z);
    }

    /**
     * @param c0920g the current input event context
     */
    private boolean trySwapPunctuation(InputEvent event, InputEventContext c0920g) {
        return this.mPunctuationController.trySwapPunctuation(event, c0920g);
    }

    /**
     * @param c0920g the current input event context
     */
    private boolean shouldStripSpace(InputEvent event, InputEventContext c0920g) {
        return this.mPunctuationController.shouldStripSpace(event, c0920g);
    }

    /**
     * Arms double-space-to-period detection for the next space.
     * @param c0920g the current input event context
     */
    public void recordSpaceTimestamp(InputEventContext c0920g) {
        this.mPunctuationController.recordSpaceTimestamp(c0920g);
    }

    public void resetSpaceTimestamp() {
        this.mPunctuationController.resetSpaceTimestamp();
    }

    /**
     * @param c0920g the current input event context
     */
    private boolean handleDoubleSpacePeriod(InputEvent event, InputEventContext c0920g) {
        return this.mPunctuationController.handleDoubleSpacePeriod(event, c0920g, this.mJustCommitted);
    }

    /**
     * Blocks (up to 1 s) for suggestions and updates the strip; only where async delivery is not feasible.
     * @param c0804d current settings values
     * @param i update reason code forwarded to the worker
     */
    public void updateSuggestionsSync(SettingsValues c0804d, int i) {
        mSuggestionCoordinator.updateSuggestionsSync(c0804d, i);
    }

    /**
     * @param c0804d current settings values
     * @param i update reason code forwarded to the worker
     */
    public void updateSuggestionsAsync(SettingsValues c0804d, int i) {
        mSuggestionCoordinator.updateSuggestionsAsync(c0804d, i);
    }

    /**
     * Reopens the word under the cursor as composing and shows its candidates; {@code z} puts the word itself first.
     * @param c0804d current settings values
     * @param z {@code true} to include the tapped word itself as the first suggestion
     * @param i symbol page order / shift state used for suggestion context
     */
    public void performRecorrection(SettingsValues c0804d, final boolean z, int i) {
        mRecorrectionController.performRecorrection(c0804d, z, i);
    }

    /**
     * @param c0920g the current input event context
     * @param c0804d current settings values
     */
    void revertAutoCorrection(InputEventContext c0920g, SettingsValues c0804d, boolean dropWhitespaceSeparator) {
        mRecorrectionController.revertAutoCorrection(c0920g, c0804d, dropWhitespaceSeparator);
    }

    /**
     * @param c0804d current settings values
     * @param i the symbol page order (unused, shift comes from caps mode)
     */
    int getShiftState(SettingsValues c0804d, int i) {
        KeyboardSwitcher c0979iM4088ac = this.mIme.getKeyboardSwitcher();
        
        if (c0979iM4088ac != null && c0979iM4088ac.isMainKeyboardShowing()) {
            return 0;
        }
        // Always derive from caps mode: i is a symbol page order, not a shift state.
        int iM4490d = getCapsMode(c0804d);
        if ((iM4490d & 4096) != 0) {
            return 7;
        }
        return iM4490d != 0 ? 5 : 0;
    }

    /**
     * {@link android.text.TextUtils} caps-mode flags, or 0 when auto-caps is off, unsupported or no editor.
     * @param c0804d current settings values
     */
    public int getCapsMode(SettingsValues c0804d) {
        EditorInfo editorInfoM4500g;
        Locale localeM4259h = SubtypeManager.getInstance().getCurrentSubtypeLocale();
        String language = localeM4259h != null ? localeM4259h.getLanguage() : "";
        if (!c0804d.isAutoCapsEnabled || !ScriptUtils.isUnsupportedScript(language) || !this.mIme.isShiftChording() || (editorInfoM4500g = getCurrentEditorInfo()) == null) {
            return 0;
        }
        return this.mRichInputConnection.getCapsMode(editorInfoM4500g.inputType, c0804d.spacingAndPunctuation, 4 == this.mCommitType);
    }

    /** Always {@code -1}: no recapitalize state exists. */
    public int getConfigParserResult() {
        return -1;
    }

    public EditorInfo getCurrentEditorInfo() {
        return this.mIme.getCurrentInputEditorInfo();
    }

    /**
     * @param c0806f spacing and punctuation rules (unused in current implementation)
     * @param i context depth hint (unused in current implementation)
     */
    PrevWordsInfo getPrevWordsInfo(SpacingAndPunctuation c0806f, int i) {
        return this.mRichInputConnection.getPrevWordsInfo(c0806f, i);
    }

    /**
     * @param i the editor action id
     */
    private void performEditorActionById(int i) {
        this.mRichInputConnection.performEditorAction(i);
    }

    /**
     * Drops a leading '.' (before a letter) when the editor already has '.' before the cursor.
     * @param str the string to process
     */
    public String stripLeadingDot(String str) {
        int length;
        if (str.length() <= 1 || str.charAt(0) != '.' || !Character.isLetter(str.charAt(1))) {
            return str;
        }
        this.mCommitType = 0;
        CharSequence charSequenceM5815a = this.mRichInputConnection.getTextBeforeCursor(2, 0);
        return (charSequenceM5815a != null && (length = charSequenceM5815a.length()) >= 1 && 46 == Character.codePointBefore(charSequenceM5815a, length)) ? str.substring(1) : str;
    }

    private void toggleFullscreenMode() {
        this.mIme.showInputOptions();
    }

    /**
     * @param i selection start
     * @param i2 selection end
     * @param z true to also neutralise the suggestion strip
     */
    void resetComposingAndSelect(int i, int i2, boolean z) {
        if (BuildConfig.DEBUG) Log.d("TEXT_EDIT_DEBUG", "resetComposingAndSelect: pos=[" + i + "," + i2 + "] clearSuggestions=" + z + " wasComposing='" + mComposingTracker.getComposingText() + "'");
        boolean z2 = this.mComposingTracker.isComposing() || this.mTouchHighlightTracker.isHighlightActive();
        clearComposingText(true);
        if (z) {
            this.mSuggestionStripListener.setNeutralSuggestionStrip();
        }
        updateDynamicLearningState();
        this.mRichInputConnection.resetConnection(i, i2, z2);
        this.mTouchHighlightTracker.clear();
        clearEngineBuffer();
    }

    /**
     * Clears the composing tracker; {@code z} also drops the last commit record (no revert).
     * @param z {@code true} to also reset the event dispatcher
     */
    public void clearComposingText(boolean z) {
        this.mComposingTracker.clearAll();
        if (z) {
            this.mEventDispatcher = CommitEventRecord.IDLE;
        }
    }

    /**
     * @param z true to commit an active multi-tap first
     */
    private void clearTouchEventProcessor(boolean z) {
        if (this.mTouchHighlightTracker.isHighlightActive()) {
            if (z) {
                this.mIme.getMultitapEventHandler().commitMultitapIfActive();
            }
            this.mTouchHighlightTracker.clear();
        }
    }

    /**
     * @param str the typed word to put first
     * @param c0666ac the suggestions to append after the typed word
     */
    SuggestedWords createSuggestionsWithTypedWord(String str, SuggestedWords c0666ac) {
        return mSuggestionCoordinator.createSuggestionsWithTypedWord(str, c0666ac);
    }

    /**
     * @param str the composing text to decorate
     */
    CharSequence getComposingTextWithIndicator(String str) {
        if (this.mTouchHighlightTracker.isHighlightActive()) {
            return this.mTouchHighlightTracker.applyHighlight(str);
        }
        return this.mIsAutoCorrectActive ? SuggestionSpanBuilder.getTextWithAutoCorrectionIndicator(this.mIme, str) : str;
    }

    /**
     * Sends a key-down + key-up pair with no meta state.
     * @param i the Android key code to send
     */
    public void sendKeyEvent(int i) {
        sendKeyEventWithMeta(i, 0);
    }

    /**
     * @param i the Android key code
     */
    public void sendKeyDown(int i) {
        long jUptimeMillis = SystemClock.uptimeMillis();
        this.mRichInputConnection.sendKeyEvent(new KeyEvent(jUptimeMillis, jUptimeMillis, 0, i, 0, 0, -1, 0, 6));
    }

    /**
     * @param i the Android key code
     */
    public void sendKeyUp(int i) {
        long jUptimeMillis = SystemClock.uptimeMillis();
        this.mRichInputConnection.sendKeyEvent(new KeyEvent(jUptimeMillis, jUptimeMillis, 1, i, 0, 0, -1, 0, 6));
    }

    /**
     * Sends a key-down + key-up pair with meta state {@code i2}.
     * @param i the Android key code
     * @param i2 the meta state bitmask (e.g., {@link android.view.KeyEvent#META_SHIFT_ON})
     */
    public void sendKeyEventWithMeta(int i, int i2) {
        CursorController.sendKeyEventWithMeta(this.mRichInputConnection, i, i2);
    }

    /**
     * @param i the Android key code
     * @param i2 the meta state bitmask
     */
    public void sendKeyDownWithMeta(int i, int i2) {
        long jUptimeMillis = SystemClock.uptimeMillis();
        this.mRichInputConnection.sendKeyEvent(new KeyEvent(jUptimeMillis, jUptimeMillis, 0, i, 0, i2, -1, 0, 6));
    }

    /**
     * @param i the Android key code
     * @param i2 the meta state bitmask
     */
    public void sendKeyUpWithMeta(int i, int i2) {
        this.mRichInputConnection.sendKeyEvent(new KeyEvent(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), 1, i, 0, i2, -1, 0, 6));
    }

    /**
     * @param c0804d current settings values (unused by the RAW protocol)
     * @param i the Unicode code point to commit
     * @param enumC0690f the input source that triggered this character commit
     */
    private void commitCharacter(SettingsValues c0804d, final int i, InputSource enumC0690f) {
        mCommitController.commitCharacter(c0804d, i, enumC0690f);
    }

    /** Flushes pending touch-event text: updates the composing span, or commits it as plain text. */
    public void commitTouchEventText() {
        mCommitController.commitTouchEventText();
    }

    /**
     * Enables or disables dynamic learning for the current editor; cached on the preference,
     * inputType and imeOptions. Runs before {@code loadSettings} rebuilds {@link EditorCapabilities},
     * so it reads the live {@link EditorInfo}, never the settings copy.
     */
    public void updateDynamicLearningState() {
        SettingsValues settingsValues = SettingsManager.getInstance().getSettingsValues();
        boolean z = settingsValues.isDynamicLearningEnabled;
        EditorInfo editorInfoM4500g = getCurrentEditorInfo();
        if (this.mLastDynamicLearningEnabled == z && (editorInfoM4500g == null
                || (editorInfoM4500g.inputType == this.mLastInputType && editorInfoM4500g.imeOptions == this.mLastImeOptions))) {
            return;
        }
        DynamicLearningManager learning = this.mIme.getDynamicLearningManager();
        boolean noPersonalizedLearning = EditorCapabilities.hasNoPersonalizedLearningFlag(editorInfoM4500g);
        learning.setLearningPreferenceEnabled(z);
        learning.setNoPersonalizedLearning(noPersonalizedLearning);
        ClipboardController clipboard = this.mIme.getClipboardController();
        if (clipboard != null) {
            clipboard.setNoPersonalizedLearning(noPersonalizedLearning);
        }
        if (!z) {
            learning.setDynamicLearningEnabled(false);
        } else {
            boolean zM4428f = editorInfoM4500g != null ? isLearningAllowed(editorInfoM4500g) : false;
            learning.setDynamicLearningEnabled(zM4428f);
        }
        this.mLastDynamicLearningEnabled = z;
        if (editorInfoM4500g != null) {
            this.mLastInputType = editorInfoM4500g.inputType;
            this.mLastImeOptions = editorInfoM4500g.imeOptions;
        }
    }

    /**
     * The editor permits learning: its input type allows it and it is not incognito
     * ({@link EditorInfo#IME_FLAG_NO_PERSONALIZED_LEARNING}). The user's preference applies on top.
     */
    public static boolean isLearningAllowed(EditorInfo editorInfo) {
        return isLearningEnabledForInputType(editorInfo.inputType)
                && !EditorCapabilities.hasNoPersonalizedLearningFlag(editorInfo);
    }

    /**
     * False for password, URI, email-address, filter, phonetic and non-text input types.
     * @param i the input type bitmask from the editor's {@link android.view.inputmethod.EditorInfo}
     */
    public static boolean isLearningEnabledForInputType(int i) {
        int i2 = i & 4080;
        return (InputTypeUtils.isPasswordInputType(i) || InputTypeUtils.isVisiblePasswordInputType(i) || ((524288 & i) != 0 && i2 == 160) || !InputTypeUtils.isTextClass(i) || InputTypeUtils.isUriVariation(i2) || InputTypeUtils.isFilterVariation(i2) || InputTypeUtils.isPhoneticVariation(i2)) ? false : true;
    }

    /**
     * @param c0804d current settings values
     * @param enumC0690f the input source that triggered the commit
     */
    private void appendAutoSpace(SettingsValues c0804d, InputSource enumC0690f) {
        this.mPunctuationController.appendAutoSpace(c0804d, enumC0690f);
    }

    /**
     * Commits the top swipe suggestion (index 0) with its separator.
     * @param c0804d current settings values
     * @param c0666ac the gesture suggestion list (top word at index 0 is committed)
     * @param enumC0690f the input source (typically TOUCH)
     */
    public void commitGestureSuggestion(SettingsValues c0804d, SuggestedWords c0666ac, InputSource enumC0690f) {
        mCommitController.commitGestureSuggestion(c0804d, c0666ac, enumC0690f);
    }

    /**
     * Commits the composing word verbatim followed by separator {@code str}; no-op when not composing.
     * @param c0804d current settings values
     * @param str the separator to append after the word (may be empty)
     * @param enumC0690f the input source that triggered the commit
     */
    public void commitTypedWord(SettingsValues c0804d, String str, InputSource enumC0690f) {
        mCommitController.commitTypedWord(c0804d, str, enumC0690f);
    }

    /**
     * Removes {@code word}, committed as typed at a swipe's start, when the contact turned out to
     * be a flick-up pick. Only if nothing is composing and the word is right before the cursor.
     */
    public boolean takeBackWordCommittedForSwipe(String word) {
        if (word == null || word.isEmpty() || this.mComposingTracker.isComposing()
                || !this.mRichInputConnection.isTextBeforeCursor(word)) {
            return false;
        }
        this.mRichInputConnection.deleteSurroundingText(word.length(), 0);
        return true;
    }

    /**
     * @param c0804d current settings values
     * @param str the separator text that ended the word
     * @param handlerC0650c UI update handler
     * @param enumC0690f the input source that triggered the commit
     * @return {@code true} if the separator payload was already committed.
     */
    private boolean autoCorrectAndCommit(SettingsValues c0804d, String str, UIUpdateHandler handlerC0650c, InputSource enumC0690f) {
        return mCommitController.autoCorrectAndCommit(c0804d, str, handlerC0650c, enumC0690f);
    }

    /**
     * @param c0804d current settings values
     * @param str the separator text that ended the word
     * @param handlerC0650c UI update handler used to flush pending async suggestion requests
     * @param enumC0690f the input source that triggered this commit
     * @param z force the separator append even for a word character
     */
    private void autoCorrectAndCommitExtended(SettingsValues c0804d, String str, UIUpdateHandler handlerC0650c, InputSource enumC0690f, boolean z) {
        mCommitController.autoCorrectAndCommitExtended(c0804d, str, handlerC0650c, enumC0690f, z);
    }

    /**
     * Strips internal clipboard and engine boundary markers from a suggestion before commit.
     * @param str the raw suggestion string (may contain internal markers)
     */
    public String processClipboardMarker(String str) {
        return mCommitController.processClipboardMarker(str);
    }

    /**
     * Replaces the last character with an accented variant, keeping the composing tracker and
     * RichInputConnection in step (never a raw InputConnection).
     */
    public void replaceLastCharWithAccent(String accentChar) {
        this.mRichInputConnection.beginBatchEdit();

        if (this.mComposingTracker.isComposing()) {
            String composing = this.mComposingTracker.getComposingText();
            TouchPointerCoordTracker coords = this.mComposingTracker.getCoordinateTracker();
            if (composing.length() > 0) {
                int lastStart = composing.offsetByCodePoints(composing.length(), -1);
                accentChar = matchCase(composing.substring(lastStart), accentChar, currentLocale());
                CharSequence trimmed = composing.subSequence(0, lastStart);
                if (trimmed.length() > 0) {
                    int[] codePoints = toCodePointArray(trimmed);
                    this.mComposingTracker.setComposingFromCodePoints(codePoints, this.mIme.getKeyCoordinates(codePoints), coords.getIntentionalFlags());
                } else {
                    this.mComposingTracker.clearAll();
                }
            }
            this.mComposingTracker.processInputEvent(InputEvent.createFromSwitchedKeyboard(InputEvent.createTextInputEvent(accentChar, -1)));
            this.mRichInputConnection.setComposingText(this.mComposingTracker.getComposingText(), 1);
        } else {
            CharSequence before = this.mRichInputConnection.getTextBeforeCursor(2, 0);
            if (before != null && before.length() > 0) {
                int lastStart = Character.offsetByCodePoints(before, before.length(), -1);
                accentChar = matchCase(before.subSequence(lastStart, before.length()).toString(), accentChar, currentLocale());
            }
            this.mRichInputConnection.deleteSurroundingText(1, 0);
            this.mRichInputConnection.commitText(accentChar, 1);
        }

        this.mRichInputConnection.endBatchEdit();
    }

    /** The letter a picked accent would replace (last composing code point, else the one before the cursor), or "". */
    public String letterAnAccentWouldReplace() {
        if (this.mComposingTracker.isComposing()) {
            String composing = this.mComposingTracker.getComposingText();
            if (composing.isEmpty()) return "";
            return composing.substring(composing.offsetByCodePoints(composing.length(), -1));
        }
        CharSequence before = this.mRichInputConnection.getTextBeforeCursor(2, 0);
        if (before == null || before.length() == 0) return "";
        return before.subSequence(Character.offsetByCodePoints(before, before.length(), -1), before.length()).toString();
    }

    /** [matchCase] over a list: the accent bar's entries in the case of [replaced]. */
    public static java.util.List<String> matchCase(String replaced, java.util.List<String> accents, Locale locale) {
        if (replaced == null || replaced.isEmpty() || !Character.isUpperCase(replaced.codePointAt(0))) return accents;
        java.util.ArrayList<String> out = new java.util.ArrayList<>(accents.size());
        for (String accent : accents) out.add(matchCase(replaced, accent, locale));
        return out;
    }

    /**
     * The accent in the case of the letter it replaces ("К" with қ picked gives "Қ"). Non-capital
     * replaced letters and non-letter accents are left as listed.
     */
    static String matchCase(String replaced, String accent, Locale locale) {
        if (replaced == null || replaced.isEmpty() || accent == null || accent.isEmpty()) return accent;
        int replacedCode = replaced.codePointAt(0);
        if (!Character.isUpperCase(replacedCode)) return accent;
        if (!Character.isLetter(accent.codePointAt(0))) return accent;
        String upper = accent.toUpperCase(locale);
        // An upper case with more code points (ß to SS) keeps its lower case.
        return upper.codePointCount(0, upper.length()) == accent.codePointCount(0, accent.length()) ? upper : accent;
    }

    private static Locale currentLocale() {
        try {
            Locale locale = SubtypeManager.getInstance().getCurrentSubtypeLocale();
            return locale != null ? locale : Locale.getDefault();
        } catch (RuntimeException e) {
            return Locale.getDefault();
        }
    }

    /**
     * @param c0804d current settings values
     * @param c0920g the current input event context
     * @param handlerC0650c UI update handler
     * @param str the separator text that ended the word
     */
    private void handleCjkAutoCorrect(SettingsValues c0804d, InputEventContext c0920g, UIUpdateHandler handlerC0650c, String str) {
        flushPendingSuggestions(c0804d, handlerC0650c);
        SuggestedWords.SuggestedWordInfo suggestedWordInfoVarM4369V = this.mComposingTracker.getPickedSuggestion();
        if (suggestedWordInfoVarM4369V != null) {
            if (TextUtils.isEmpty(suggestedWordInfoVarM4369V.word)) {
                throw new RuntimeException("We have an first prediction candidate but the typedword is empty? Impossible!");
            }
            commitPredictionWord(c0804d, this.mComposingTracker.getPickedSuggestion(), CommitEventRecord.CommitType.DECIDED_WORD, c0920g.getInputSource(), c0920g, handlerC0650c, str);
            return;
        }
        if (this.mComposingTracker.isCursorMoved()) {
            resetComposingAndSelect(this.mRichInputConnection.getCursorStart(), this.mRichInputConnection.getCursorEnd(), true);
        } else if (LocaleUtils.isCurrentSubtypePinyin() && str.length() == 1 && '0' <= str.charAt(0) && str.charAt(0) <= '9') {
            commitTypedWord(c0920g.settingsValues, "", c0920g.getInputSource());
            this.mRichInputConnection.commitText(str, 1);
        } else {
            commitTypedWord(c0920g.settingsValues, "", c0920g.getInputSource());
        }
        c0920g.setUiUpdateMode(1);
        c0920g.setShouldUpdateSuggestions();
    }

    /**
     * @param c0804d current settings values
     * @param handlerC0650c UI update handler used to cancel pending async requests
     */
    void flushPendingSuggestions(SettingsValues c0804d, UIUpdateHandler handlerC0650c) {
        mSuggestionCoordinator.flushPendingSuggestions(c0804d, handlerC0650c);
    }

    /**
     * @param c0804d current settings values
     * @param str the word text to commit
     * @param str2 the separator to append after the word
     * @param enumC0690f the input source that triggered the commit
     */
    int commitWord(SettingsValues c0804d, String str, CommitEventRecord.CommitType commitTypeVar, String str2, InputSource enumC0690f) {
        return mCommitController.commitWord(c0804d, str, commitTypeVar, str2, enumC0690f);
    }

    /**
     * @param c0804d current settings values
     * @param str the word text to commit
     * @param str2 the separator to append after the word
     * @param enumC0690f the input source that triggered the commit
     * @param z force the separator append even for a word character
     */
    private int commitWordExtended(SettingsValues c0804d, String str, CommitEventRecord.CommitType commitTypeVar, String str2, InputSource enumC0690f, boolean z) {
        return mCommitController.commitWordExtended(c0804d, str, commitTypeVar, str2, enumC0690f, z);
    }

    /**
     * @param c0804d current settings values
     * @param enumC0690f the input source that triggered the commit
     * @param c0920g the current input event context
     * @param handlerC0650c UI update handler
     * @param str the separator that ended the pick
     */
    private boolean commitPredictionWord(SettingsValues c0804d, SuggestedWords.SuggestedWordInfo suggestedWordInfoVar, CommitEventRecord.CommitType commitTypeVar2, InputSource enumC0690f, InputEventContext c0920g, UIUpdateHandler handlerC0650c, String str) {
        return mCommitController.commitPredictionWord(c0804d, suggestedWordInfoVar, commitTypeVar2, enumC0690f, c0920g, handlerC0650c, str);
    }

    /**
     * Resets the connection after select-all or a keyboard switch; {@code z} updates shift state.
     * A failed reset with retries left ({@code i > 0}) is re-posted and returns {@code false}.
     * @param z {@code true} to trigger a shift-state update after reset
     * @param i retry count
     * @param handlerC0650c UI update handler
     */
    public boolean handleSelectAll(boolean z, int i, UIUpdateHandler handlerC0650c) {
        boolean z2 = this.mRichInputConnection.hasSelection() || !this.mRichInputConnection.isConnected();
        updateDynamicLearningState();
        RichInputConnection c0909x = this.mRichInputConnection;
        if (!c0909x.resetConnection(c0909x.getCursorStart(), this.mRichInputConnection.getCursorEnd(), z2) && i > 0) {
            handlerC0650c.postUpdateSwitchKeyboard(z, i - 1);
            return false;
        }
        this.mRichInputConnection.recalibrateCursorPosition();
        if (z) {
            handlerC0650c.postUpdateShiftState(true, true);
        }
        return true;
    }

    /**
     * Runs a suggestion request on the engine thread. {@code i} is the symbol page order,
     * {@code i2} the request flags.
     * @param c0804d current settings values
     * @param c1073o keyboard animator providing key-position context
     * @param i shift state / symbol page order
     * @param i2 suggestion request flags
     */
    public void requestSuggestionsWithContext(Context context, SettingsValues c0804d, ProximityGrid c1073o, int i, int i2, SuggestionEngine.SuggestionCallback aVar) {
        // Editor-derived inputs come from the main-thread snapshot; a request sent without one
        // (off the main thread, or a plain queue in a test) reads them here.
        RequestContext snapshot = aVar instanceof ContextCarryingCallback
                ? ((ContextCarryingCallback) aVar).context : null;
        if (snapshot == null) {
            snapshot = captureRequestContext(c0804d, i);
        }
        // Engine thread: read only the snapshot, never the tracker.
        final ComposingSnapshot composing = snapshot.composing;
        if (!composing.isComposing) {
            final int engineShift = composing.engineShiftState();
            EngineOps.post("requestShiftState", sdk -> sdk.setShiftState(engineShift));
        }
        this.mSuggestionEngine.setAutoCorrectMode(c0804d.autoCorrectionMode);
        if (BuildConfig.DEBUG) {
        android.util.Log.d("PKB_SPACE_AUTOCORRECT_DEBUG", "requestSuggestionsWithContext: acMode=" + c0804d.autoCorrectionMode
            + " acEnabled=" + c0804d.isAutoCorrectionEnabledPerUserSettings
            + " composing='" + composing.text + "'"
            + " isPredMode=" + composing.isPredictionMode);
        }
        this.mSuggestionEngine.getSuggestedWords(context, composing, snapshot.prevWordsInfo, c1073o, new SuggestionStripSettings(c0804d.blockPotentiallyOffensiveWords, c0804d.additionalFeaturesSettings, snapshot.fromWorkProfile), c0804d.isAutoCorrectionEnabledPerUserSettings, i2, aVar);
    }

    /**
     * Editor state a suggestion request reads, snapshotted on the main thread at send time (a
     * worker-side read races main-thread edits). Cheap: only the last 146 characters of context
     * are copied. The composing word is copied too; under FIFO it matches the engine's buffer.
     */
    static final class RequestContext {
        final PrevWordsInfo prevWordsInfo;
        final int shiftState;
        final boolean fromWorkProfile;
        /** The current keyboard's grid, read on the main thread; null when no keyboard is up. */
        final ProximityGrid proximityGrid;
        /** The composing state the request is for; never null. */
        final ComposingSnapshot composing;

        RequestContext(PrevWordsInfo prevWordsInfo, int shiftState, boolean fromWorkProfile) {
            this(prevWordsInfo, shiftState, fromWorkProfile, null, ComposingSnapshot.EMPTY);
        }

        RequestContext(PrevWordsInfo prevWordsInfo, int shiftState, boolean fromWorkProfile, ProximityGrid proximityGrid) {
            this(prevWordsInfo, shiftState, fromWorkProfile, proximityGrid, ComposingSnapshot.EMPTY);
        }

        RequestContext(PrevWordsInfo prevWordsInfo, int shiftState, boolean fromWorkProfile, ProximityGrid proximityGrid, ComposingSnapshot composing) {
            this.prevWordsInfo = prevWordsInfo;
            this.shiftState = shiftState;
            this.fromWorkProfile = fromWorkProfile;
            this.proximityGrid = proximityGrid;
            this.composing = composing != null ? composing : ComposingSnapshot.EMPTY;
        }
    }

    /** The snapshot's proximity grid (null without a snapshot or keyboard), so the worker never reads the keyboard. */
    public static ProximityGrid snapshotProximityGrid(SuggestionEngine.SuggestionCallback callback) {
        return callback instanceof ContextCarryingCallback ? ((ContextCarryingCallback) callback).context.proximityGrid : null;
    }

    /** A request's callback with the snapshot it was sent with; the reply passes straight through. */
    static final class ContextCarryingCallback implements SuggestionEngine.SuggestionCallback {
        final RequestContext context;
        final SuggestionEngine.SuggestionCallback delegate;

        ContextCarryingCallback(RequestContext context, SuggestionEngine.SuggestionCallback delegate) {
            this.context = context;
            this.delegate = delegate;
        }

        @Override
        public void onSuggestionsReady(SuggestedWords suggestedWords) {
            this.delegate.onSuggestionsReady(suggestedWords);
        }
    }

    RequestContext captureRequestContext(SettingsValues settings, int symbolPageOrder) {
        android.view.inputmethod.InputBinding binding = this.mIme.getCurrentInputBinding();
        KeyboardSwitcher switcher = this.mIme.getKeyboardSwitcher();
        dev.bbkb.ime.keyboard.Keyboard keyboard = switcher != null ? switcher.getCurrentKeyboard() : null;
        int shiftState = getShiftState(settings, symbolPageOrder);
        // A request with nothing composed carries the shift state its predictions' case reads; set
        // here on the main thread so the engine thread never touches the tracker.
        this.mComposingTracker.setShiftStateIfNotComposing(shiftState);
        return new RequestContext(
                getPrevWordsInfo(settings.spacingAndPunctuation, this.mComposingTracker.isComposing() ? 2 : 1),
                shiftState,
                binding != null && ProfileDetector.isInputFromWorkProfile(binding),
                keyboard != null ? keyboard.getProximityGrid() : null,
                ComposingSnapshot.of(this.mComposingTracker));
    }

    /** Every producer reaches the worker through {@code sendToWorkerThread}: the one place to snapshot on the main thread. */
    private final class ContextCapturingRequestQueue extends SuggestionRequestQueue {
        ContextCapturingRequestQueue(BlackBerryIME ime) {
            super(ime, InputLogic.this);
        }

        @Override
        public void sendToWorkerThread(int what, boolean keep, SuggestionEngine.SuggestionCallback callback) {
            super.sendToWorkerThread(what, keep, withRequestContext(callback));
        }
    }

    SuggestionEngine.SuggestionCallback withRequestContext(SuggestionEngine.SuggestionCallback callback) {
        // Off the main thread the capture would race; leave such a request to read at run time.
        if (callback == null || callback instanceof ContextCarryingCallback
                || Looper.myLooper() != Looper.getMainLooper()) {
            return callback;
        }
        SettingsValues settings = SettingsManager.getInstance().getSettingsValues();
        if (settings == null) {
            return callback;
        }
        SymbolPageProvider symbolPages = this.mIme.getSymbolPageProvider();
        int symbolPageOrder = symbolPages == null ? 0 : symbolPages.getSymbolPageOrder();
        return new ContextCarryingCallback(captureRequestContext(settings, symbolPageOrder), callback);
    }

    /**
     * @param i the new cursor position, as in InputConnection.setComposingText
     */
    void setComposingTextInternal(CharSequence charSequence, int i) {
        if (BuildConfig.DEBUG) Log.d("TEXT_EDIT_DEBUG", "setComposingTextInternal: text='" + charSequence + "' cursorPos=" + i);
        setComposingTextWithHighlight(charSequence, i, 0, 0, charSequence.length());
    }

    /**
     * @param i the new cursor position, as in InputConnection.setComposingText
     * @param i2 highlight background color, 0 for none
     * @param i3 highlight start offset
     * @param i4 highlight end offset
     */
    void setComposingTextWithHighlight(CharSequence charSequence, int i, int i2, int i3, int i4) {
        if (charSequence.length() == 0) {
            Logger.debug(TAG, "setComposingTextInternalWithBackgroundColor(): no composing text to set.");
            return;
        }
        if (i2 != 0) {
            SpannableString spannableString = new SpannableString(charSequence);
            spannableString.setSpan(new BackgroundColorSpan(i2), i3, Math.min(i4, spannableString.length() - i3),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE | Spanned.SPAN_COMPOSING);
            charSequence = spannableString;
        }
        this.mRichInputConnection.setComposingText(charSequence, i);
    }

    /**
     * @param interfaceC1076r the timer proxy to install
     */
    public void setTimerProxy(TextDecoratorUiOperator interfaceC1076r) {
        this.mMoreKeysController.setTimerProxy(interfaceC1076r);
    }

    public void updateCursorAnchorInfo(CursorAnchorInfo cursorAnchorInfo) {
        this.mMoreKeysController.onUpdateCursorAnchorInfo(cursorAnchorInfo);
    }

    /**
     * @param z {@code true} to enable
     */
    public void setMoreKeysEnabled(boolean z) {
        this.mMoreKeysController.setShouldShow(z);
    }

    /** Dismisses any more-keys popup and clears background highlight spans. */
    public void dismissMoreKeys() {
        this.mRichInputConnection.clearBackgroundSpans();
        this.mMoreKeysController.reset();
    }

    /** An email-variation editor with no extra input-type flags; gates the @/. substitution. */
    private boolean isEmailVariationField() {
        EditorInfo editorInfoM4500g = getCurrentEditorInfo();
        if (editorInfoM4500g == null) {
            return false;
        }
        int i = editorInfoM4500g.inputType;
        return InputTypeUtils.isEmailVariation(i & 4080) && (i & 16773120) == 0;
    }

    private InputEvent handleAtDotSubstitution(InputEvent event) {
        boolean z = false;
        if (!DeviceProfile.current().hasShiftedSymbolKeyboard()) {
            return event;
        }
        if (event.isKeyPressEvent() && event.mCodePoint == 32 && !this.mWasAtOrDot) {
            // Space types '@' until the field has one, then '.'; the cached context window suffices.
            CharSequence charSequenceM5815a = this.mRichInputConnection.getTextBeforeCursor(NuanceSDK.MAX_CONTEXT_LENGTH, 0);
            if (charSequenceM5815a != null) {
                boolean hasAt = TextUtils.indexOf(charSequenceM5815a, '@') >= 0;
                int resultCode = hasAt ? 46 : 64;
                return InputEvent.createKeyPress(resultCode, event.mKeyCode, event.mX, event.mY, event.getTimestamp(), event.isKeyRepeat());
            }
            return event;
        }
        if (event.isKeyPressEvent() && event.mKeyCode == -5) {
            CharSequence charSequenceM5815a2 = this.mRichInputConnection.getTextBeforeCursor(1, 0);
            if (charSequenceM5815a2 == null || charSequenceM5815a2.length() <= 0) {
                return event;
            }
            this.mWasAtOrDot = charSequenceM5815a2.charAt(0) == '@' || charSequenceM5815a2.charAt(0) == '.';
            return event;
        }
        if (this.mWasAtOrDot && event.mCodePoint == 32) {
            z = true;
        }
        this.mWasAtOrDot = z;
        return event;
    }

    /**
     * @param c0920g the current input event context
     */
    private InputEvent autoCapitalize(InputEvent event, InputEventContext c0920g) {
        int iM5460a;
        return (c0920g.symbolPageOrder != 5 || event.isFunctionalKeyEvent() || !Character.isLetter(event.mCodePoint) || (iM5460a = toUpperCaseCodePointWithGreek(event.mCodePoint, true, c0920g.settingsValues.locale, true)) == -21) ? event : InputEvent.createKeyPress(iM5460a, event.mKeyCode, event.mX, event.mY, event.getTimestamp(), event.isKeyRepeat());
    }

    private boolean isGestureEnabled() {
        EditorInfo editorInfoM4500g = getCurrentEditorInfo();
        if (editorInfoM4500g == null) {
            return false;
        }
        int i = editorInfoM4500g.inputType;
        int i2 = i & 4080;
        return (!InputTypeUtils.isAutoSpaceFriendlyType(i) || InputTypeUtils.isUriVariation(i2) || InputTypeUtils.isEmailVariation(i2)) ? false : true;
    }

    /**
     * @param c0920g the current input event context
     */
    private SmartPunctuationAnalyzer.Result processGestureRecognition(InputEvent event, InputEventContext c0920g) {
        String strM4361n = this.mComposingTracker.getComposingTextBeforeCursor();
        String contextBefore = this.mRichInputConnection.getTextContextBefore();
        SmartPunctuationAnalyzer.Result punctuationResult = this.mSmartPunctuationAnalyzer.analyze((contextBefore != null ? contextBefore : "") + strM4361n, event, this.mHasModifiedEvent, c0920g.settingsValues, c0920g.getInputSource() == InputSource.INTERNAL);
        this.mSuggestionEngine.setBlockAutoCorrect(this.mSmartPunctuationAnalyzer.isDumbMode());
        if (punctuationResult.backspaceInjected) {
            this.mEventDispatcher.disableRevert();
        }
        return punctuationResult;
    }

    /**
     * True when {@code i} cannot be word content. Unlike {@link SettingsValues#isWordSeparator(int)}
     * (the separator table), this tests word content, or under CJK the per-script "not separators"
     * table; keep that double negative (see {@link SettingsValues#isNotWordSeparatorForLocale(int, java.util.Locale)}).
     * @param c0804d current settings values
     * @param i the code point to test
     */
    boolean isWordSeparator(SettingsValues c0804d, int i) {
        if (isCjkLocale()) {
            return !c0804d.isNotWordSeparatorForLocale(i, SubtypeManager.getInstance().getCurrentSubtypeLocale());
        }
        return !c0804d.isWordCodePoint(i);
    }

    /** Drops composing and touch-event text uncommitted, finishes the region and neutralises the strip. */
    public void cancelComposingAndTouchEvent() {
        boolean zM4354g = this.mComposingTracker.isComposing();
        boolean zM4834a = this.mTouchHighlightTracker.isHighlightActive();
        if (zM4354g) {
            clearComposingText(true);
        }
        if (zM4834a) {
            this.mTouchHighlightTracker.clear();
        }
        if (zM4354g || zM4834a) {
            this.mRichInputConnection.finishComposingText();
            this.mSuggestionStripListener.setNeutralSuggestionStrip();
        }
    }

    /**
     * Cursor moves cancel composing first; {@code z} extends the selection.
     * @param c0804d current settings values (used for layout direction and batching)
     * @param i number of positions to move
     * @param z {@code true} to extend the selection while moving
     */
    public void moveCursorRight(SettingsValues c0804d, int i, boolean z) {
        this.mCursorController.moveCursorRight(c0804d, i, z);
    }

    /**
     * @param c0804d current settings values (used for layout direction and batching)
     * @param i number of positions to move
     * @param z {@code true} to extend the selection while moving
     */
    public void moveCursorLeft(SettingsValues c0804d, int i, boolean z) {
        this.mCursorController.moveCursorLeft(c0804d, i, z);
    }

    /**
     * @param i number of lines to move
     * @param z {@code true} to extend the selection while moving
     */
    public void moveCursorUp(int i, boolean z) {
        this.mCursorController.moveCursorUp(i, z);
    }

    /**
     * @param i number of lines to move
     * @param z {@code true} to extend the selection while moving
     */
    public void moveCursorDown(int i, boolean z) {
        this.mCursorController.moveCursorDown(i, z);
    }

    boolean isCjkLocale() {
        return LocaleUtils.isCurrentSubtypeChinese();
    }

    public boolean isPasswordField() {
        EditorInfo editorInfoM4500g = getCurrentEditorInfo();
        return editorInfoM4500g != null && InputTypeUtils.isPasswordInputType(editorInfoM4500g.inputType);
    }

    /** Any password variation (visible, web or numeric). */
    public boolean isAnyPasswordField() {
        EditorInfo editorInfoM4500g = getCurrentEditorInfo();
        return editorInfoM4500g != null && InputTypeUtils.isAnyPasswordInputType(editorInfoM4500g.inputType);
    }

    /**
     * The separator the dictionary recommends after {@code str} in context; a space with no dictionary.
     * @param str the word whose trailing separator should be resolved
     */
    public String getWordSeparator(String str) {
        if (!this.mDictionaryLoader.isDictionaryReady()) {
            return " ";
        }
        return this.mDictionaryLoader.getMainDictionary().getWordSeparator(this.mRichInputConnection.getTextContextBefore() + str);
    }

    /**
     * Engine auto-commit callback; may fire on a native thread, so the commit is moved to the main thread.
     * @param str the word the engine committed
     */
    @Override
    public void onAutoCommitWord(String str) {
        if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "InputLogic.onAutoCommitWord (NuanceSDK auto-commit callback): word='" + str + "' — this is the gesture-typed word arriving from native");
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "InputLogic.onAutoCommitWord: committing on main thread — word='" + str + "'");
            this.mRichInputConnection.commitText(str, 1);
            if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "InputLogic.onAutoCommitWord: commitText completed — requesting predictions");
            requestPredictions(InputSource.HARDWARE);
        } else {
            if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "InputLogic.onAutoCommitWord: posting commit to main handler — word='" + str + "' currentThread=" + Thread.currentThread().getName());
            // Refuse to commit into a different session than the one that posted.
            final int postedGeneration = this.mSessionGeneration;
            this.mMainHandler.post(() -> {
                if (postedGeneration != this.mSessionGeneration) {
                    if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "InputLogic.onAutoCommitWord: DROPPING posted commit — session changed (posted=" + postedGeneration + " now=" + this.mSessionGeneration + ") word='" + str + "'");
                    return;
                }
                if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "InputLogic.onAutoCommitWord: executing posted commit — word='" + str + "'");
                this.mRichInputConnection.commitText(str, 1);
                if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "InputLogic.onAutoCommitWord: posted commitText completed — requesting predictions");
                requestPredictions(InputSource.HARDWARE);
            });
        }
    }

    /**
     * @param str text before the cursor
     * @param i cursor position within the recorrection word
     * @param str2 the recorrection word
     */
    void updateNuanceContext(String str, int i, String str2) {
        mSuggestionCoordinator.updateNuanceContext(str, i, str2);
    }

    /**
     * @param str the word to learn
     */
    private void commitWordToDictionary(String str) {
        mSuggestionCoordinator.commitWordToDictionary(str);
    }
}

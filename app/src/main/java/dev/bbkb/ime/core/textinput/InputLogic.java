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
import dev.bbkb.ime.core.textinput.connection.TextContextTracker;
import dev.bbkb.ime.personaldictionary.tokenizer.BlackBerryTokenizer;
import dev.bbkb.ime.core.shared.ProfileDetector;
import android.view.inputmethod.CursorAnchorInfo;
import dev.bbkb.ime.core.BlackBerryIME;
import dev.bbkb.ime.core.ime.UIUpdateHandler;
import dev.bbkb.ime.core.SymbolPageProvider;
import dev.bbkb.ime.core.textinput.composing.ComposingTextTracker;
import dev.bbkb.ime.core.engine.DictionaryLoader;
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

    // F12: volatile — written on the UI thread (onSuggestionsReceived, commitWordExtended)
    // but read from the suggestion worker thread (SuggestionRequestQueue's empty-result
    // fallback and SuggestionCoordinator's callbacks). Volatile guarantees visibility;
    // full atomicity of compound sequences is a W6b concern.
    public volatile SuggestedWords mCurrentSuggestions = SuggestedWords.EMPTY;

    final TextDecorator mMoreKeysController = new TextDecorator(new TextDecorator.Listener() {
        @Override
        public void onWordCommit(String str) {
            InputLogic.this.mIme.addWordToUserDictionary(str);
            InputLogic.this.mIme.dismissMoreSuggestions();
        }
    });

    public CommitEventRecord mEventDispatcher = CommitEventRecord.IDLE;


    private final TreeSet<Long> mTrackedKeyEvents = new TreeSet<>();

    /**
     * Assigned in the constructor, not here: it now takes {@link #mRichInputConnection}, which a
     * field initializer would capture as null (field initializers run ahead of the constructor
     * body, where that connection is built).
     */
    private final TextContextTracker mTextContextTracker;

    boolean mJustCommitted = false;

    boolean mShouldAppendSpace = false;

    private boolean mWasAtOrDot = false;

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    /**
     * Bumped whenever the input session is torn down or reset (see {@link #cancelInput()} and
     * {@link #resetInputState}). Work posted to {@link #mMainHandler} from a background thread
     * captures the value at post time and must re-check it at delivery: a generation mismatch
     * means the editor it was destined for is gone. Audit DW-1.
     */
    private int mSessionGeneration = 0;

    /**
     * Audit DW-1/DW-3: bumped by {@link #cancelInput()} and {@link #resetInputState} so work
     * dispatched under a previous input session can be recognised and dropped on delivery.
     */
    public int getSessionGeneration() {
        return this.mSessionGeneration;
    }

    boolean mLastDynamicLearningEnabled = false;

    int mLastInputType = -2;


    /**
     * Constructs the InputLogic engine and wires it to the given IME, suggestion strip, and
     * dictionary loader. Initializes all sub-systems: composing tracker, touch event processor,
     * rich input connection, suggestion worker, text decorator, and gesture processor.
     *
     * @param blackBerryIME  the host {@link BlackBerryIME} instance
     * @param interfaceC0840e the suggestion strip listener for displaying and hiding suggestions
     * @param c0691g         the dictionary and language-pack loader
     */
    public InputLogic(BlackBerryIME blackBerryIME, SuggestionStripListener interfaceC0840e, DictionaryLoader c0691g) {
        this.mSuggestionRequestQueue = SuggestionRequestQueue.DISABLED_INSTANCE;
        this.mIme = blackBerryIME;
        // FIX-D2: null when the engine failed to load; this runs from BlackBerryIME's field
        // initialiser, so an unguarded call crashed the service before onCreate.
        com.blackberry.nuanceshim.NuanceSDK engine = NuanceSDKManager.getInstance();
        if (engine != null) engine.setAutoCommitCallbackHandler(this);
        this.mSuggestionStripListener = interfaceC0840e;
        this.mComposingTracker = new ComposingTextTracker(NuanceSDKManager.getInstance());
        this.mTouchHighlightTracker = new TouchHighlightTracker();
        this.mRichInputConnection = new RichInputConnection(blackBerryIME);
        this.mTextContextTracker = new TextContextTracker(this.mRichInputConnection);
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
        this.mSuggestionRequestQueue = new SuggestionRequestQueue(blackBerryIME, this);
        this.mSuggestionEngine = new SuggestionEngine(c0691g);
        this.mDictionaryLoader = c0691g;
        this.mSmartPunctuationAnalyzer = null;
        this.mHasModifiedEvent = false;
        this.mLastCommitFromVoice = false;
        this.mSuggestionCoordinator = new SuggestionCoordinator(this);
        this.mCommitController = new CommitController(this);
        this.mRecorrectionController = new RecorrectionController(this);
    }

    /**
     * Resets all transient input state (composing text, timestamps, delete counters, commit
     * type) and reconnects the suggestion worker and cursor-update tracking to the editor.
     * Called at the start of each new input session (e.g., when focusing a new text field).
     *
     * @param str    converter name to install (may be empty to remove any active converter)
     * @param c0804d current settings values
     */
    /** Clear the engine's character buffer, tolerating an unavailable SDK (audit EB-8). */
    private static void clearEngineBuffer() {
        com.blackberry.nuanceshim.NuanceSDK sdk = NuanceSDKManager.getInstance();
        if (sdk != null) sdk.clear();
    }

    public void resetInputState(String str, SettingsValues c0804d) {
        this.mLastCommittedText = null;
        clearTouchEventProcessor(true);
        this.mComposingTracker.setConverterByName(str);
        clearComposingText(true);
        // Audit EB-8: getInstance() is documented to return null when native init failed, and
        // these engine-clear sites dereferenced it unguarded — an NPE on the session-reset path.
        clearEngineBuffer();
        this.mBackspaceController.reset();
        this.mCommitType = 0;
        this.mCurrentSuggestions = SuggestedWords.EMPTY;
        this.mRichInputConnection.addTextChangeListener(this.mTextContextTracker);
        this.mRichInputConnection.recalibrateCursorPosition();
        resetSpaceTimestamp();
        SuggestionRequestQueue c0675b = SuggestionRequestQueue.DISABLED_INSTANCE;
        SuggestionRequestQueue c0675b2 = this.mSuggestionRequestQueue;
        if (c0675b == c0675b2) {
            this.mSuggestionRequestQueue = new SuggestionRequestQueue(this.mIme, this);
        } else {
            c0675b2.cancelAll();
        }
        this.mRichInputConnection.requestCursorUpdates(true, true);
        this.mMoreKeysController.reset();
        this.mSmartPunctuationAnalyzer = new SmartPunctuationAnalyzer();
        this.mHasModifiedEvent = false;
        // Audit DW-1: a new session invalidates anything posted against the old one.
        this.mMainHandler.removeCallbacksAndMessages(null);
        this.mSessionGeneration++;
        // Audit DW-2/LC-5: see cancelInput — a gesture from the previous session must not
        // commit into this one.
        NuanceSDKManager.consumeGesturePending();
        // Audit LC-12/LC-13: these consume-later flags outlived the session and perturbed the
        // FIRST interaction in the next field — dumb-mode suppressing auto-correct in an email
        // field, a stale append-space shifting the next commit's cursor, a stale @/. state
        // changing the first space substitution.
        this.mSuggestionEngine.setBlockAutoCorrect(false);
        this.mShouldAppendSpace = false;
        this.mJustCommitted = false;
        this.mWasAtOrDot = false;
        // Audit LC-10: tracked-key ids leak when a key-up is delivered elsewhere (editor
        // unbound mid-hold), and a stale entry defeats the untracked-repeat guard afterwards.
        this.mTrackedKeyEvents.clear();
    }

    /**
     * Cancels any in-flight input then delegates to {@link #resetInputState}. Called when
     * the IME loses focus or switches editors completely.
     *
     * @param str    converter name to install after reset
     * @param c0804d current settings values
     */
    public void fullReset(String str, SettingsValues c0804d) {
        cancelInput();
        resetInputState(str, c0804d);
    }

    /**
     * Commits the current composing text as a typed word (with no separator), or flushes
     * any pending touch-event text if not composing. Used to flush partial input before
     * a focus change or before voice input begins.
     *
     * @param c0804d current settings values
     */
    public void commitComposingOrReset(SettingsValues c0804d) {
        mCommitController.commitComposingOrReset(c0804d);
    }

    /**
     * Cancels any pending composing or touch-event text without committing it, then invalidates
     * the editor connection and clears all in-flight suggestion requests. Used when the IME
     * is about to go idle.
     */
    public void cancelInput() {
        if (this.mComposingTracker.isComposing() || this.mTouchHighlightTracker.isHighlightActive()) {
            this.mRichInputConnection.finishComposingText();
            this.mTouchHighlightTracker.clear();
            this.mComposingTracker.clearAll();
        }
        clearComposingText(true);
        this.mRichInputConnection.invalidateConnection();
        this.mSuggestionRequestQueue.cancelAll();
        // Audit DW-1: cancelAll() only purges the WORKER queue; main-thread posts (the Nuance
        // auto-commit callback) survived teardown and committed into the next editor. Drop
        // them, and invalidate any that slip in after this point via the generation bump.
        this.mMainHandler.removeCallbacksAndMessages(null);
        this.mSessionGeneration++;
        // Audit DW-2/LC-5: gesturePending is a process-lifetime flag. Clearing it here is what
        // lets the batch-commit handler recognise a reply that outlived its session.
        NuanceSDKManager.consumeGesturePending();
        this.mCurrentSuggestions = SuggestedWords.EMPTY;
        resetTextContextTracker();
    }

    /**
     * Shuts down all background workers owned by this InputLogic instance and releases
     * dictionary resources. Must be called when the IME is destroyed.
     */
    public void shutdown() {
        SuggestionRequestQueue c0675b = this.mSuggestionRequestQueue;
        this.mSuggestionRequestQueue = SuggestionRequestQueue.DISABLED_INSTANCE;
        c0675b.shutdown();
        this.mDictionaryLoader.closeAndReset();
    }

    /**
     * Commits text produced by voice recognition, replacing any active composing text.
     * Applies auto-space if needed, strips leading dots, and posts a suggestion update.
     *
     * @param c0804d        current settings values
     * @param event        the input event carrying the voice-recognised text
     * @param i             symbol page order / shift state
     * @param z             {@code true} if voice input included punctuation (suppresses autocaps)
     * @param handlerC0650c UI update handler for posting suggestion refreshes
     * @return an {@link InputEventContext} describing the commit result
     */
    public InputEventContext commitVoiceInput(SettingsValues c0804d, InputEvent event, int i, boolean z, UIUpdateHandler handlerC0650c) {
        this.mHasModifiedEvent = false;
        String string = event.getOutputText().toString();
        InputEventContext c0920g = new InputEventContext(c0804d, event, SystemClock.uptimeMillis(), this.mCommitType, getShiftState(c0804d, i));
        this.mRichInputConnection.beginBatchEdit();
        // Audit SS-1: when a word is composing, autoCorrectAndCommit may commit `string` itself
        // — commitWordExtended appends the payload when it is a single separator-class code
        // point with no pending auto-space, which is exactly what a one-char BMP emoji from the
        // palette is. The unconditional commitText below then committed it a second time
        // ("k" + ✅ produced "k✅✅"). handleSeparatorInput has always avoided this via its own
        // control flow; this path had no way to tell, so the pipeline now reports it.
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
            this.mRichInputConnection.commitText(strM4443a, 1);
        }
        this.mRichInputConnection.endBatchEdit();
        this.mCommitType = 0;
        this.mLastCommittedText = strM4443a;
        this.mLastCommitFromVoice = z;
        c0920g.markKeyHandled();
        c0920g.setUiUpdateMode(1);
        return c0920g;
    }

    private boolean isUnknownWord(SuggestedWords.SuggestedWordInfo suggestedWordInfoVar) {
        return (suggestedWordInfoVar.isKind(0) || suggestedWordInfoVar.isKind(10)) && !this.mDictionaryLoader.isWordValid(suggestedWordInfoVar.word, true);
    }

    /**
     * Handles the user tapping a word in the suggestion strip. Commits the picked word
     * (applying auto-correction or prediction logic for CJK), triggers an add-to-dictionary
     * prompt for unknown words, and returns an {@link InputEventContext} describing the outcome.
     *
     * @param c0804d             current settings values
     * @param suggestedWordInfoVar the suggestion that was tapped
     * @param interfaceC0648a    keyboard view callback (used for symbol page order)
     * @param i                  symbol page order / shift state
     * @param handlerC0650c      UI update handler
     * @param enumC0690f         input source (typically TOUCH for suggestion strip picks)
     * @return an {@link InputEventContext} describing the committed word and UI update needs
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
        boolean zM4407a = isUnknownWord(suggestedWordInfoVar);
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
            this.mSuggestionStripListener.showAddToDictionaryHint(strM4471b);
        } else if (zM4410a) {
            this.mIme.suggestionUpdater.requestDelayed(SuggestionUpdater.Reason.AFTER_MANUAL_PICK);
        }
        return c0920g;
    }

    /**
     * Called by the IME when the editor reports a selection change. Detects cursor movement
     * within composing text and resets composing or recorrection state when the cursor has
     * jumped outside the expected range.
     *
     * @param i    old selection start
     * @param i2   new selection start
     * @param i3   old selection end
     * @param i4   new selection end
     * @param c0804d current settings values
     * @return {@code true} if the selection change was meaningful and state was updated
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
        // An app that takes the text and empties the field (Messages' Send button, a launcher
        // clearing its search box) reports the same selection jump as a caret placed at the
        // start of the composing word: [len,len] -> [0,0]. When the composing word begins the
        // field, moveCursorByCharCount(-len) absorbs that jump as an in-word cursor move, the
        // tracker keeps the word with its cursor at 0, and the next key is inserted in front of
        // it - the sent message comes back ("Correct" + H -> "HCorrect", KEY2, 2026-09-20). The
        // one thing the two cases do not share is the editor's text, so ask for it: nothing on
        // either side of the cursor means the word is gone, and so is the session.
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
     * Composite id for the tracked-key set: device id in the high word, key code in the low word.
     *
     * <p>TI-4: this used to be {@code (keyEvent.getDeviceId() << 32) + keyCode}. {@code getDeviceId()}
     * returns an {@code int} and {@code int << 32} shifts by {@code 32 % 32 == 0}, so the device id
     * stayed in the low word and simply added to the key code - device 1/keycode 66 collided with
     * device 2/keycode 65, and a key-up on one device untracked the other device's held key.
     */
    /**
     * TI-12: {@code true} when the cursor sits inside a word, i.e. the character after it is word
     * content and there is word content before it too. The backspace path and the hardware
     * character path both need this to skip recorrection on a deliberate mid-word cursor
     * placement; they used to inline it, between them making five editor round-trips where two
     * suffice (one of the five fed a local that was never read).
     */
    private boolean isCursorMidWord(SettingsValues c0804d) {
        boolean hasWordCharAfterCursor = this.mRichInputConnection.hasWordAfterCursor(c0804d.spacingAndPunctuation);
        return hasWordCharAfterCursor && this.mRichInputConnection.hasWordBeforeCursor(c0804d.spacingAndPunctuation);
    }

    private long getKeyEventId(KeyEvent keyEvent) {
        return ((long) keyEvent.getDeviceId() << 32) + keyEvent.getKeyCode();
    }

    /**
     * Returns {@code true} if this hardware key event is a repeat for a key that was
     * registered via {@link #trackKeyEvent}. Used to distinguish genuine auto-repeat
     * events from spurious repeats received before the IME gained focus.
     *
     * @param keyEvent the hardware key event to test
     * @return {@code true} if this is a tracked key repeat
     */
    public boolean isTrackedKeyRepeat(KeyEvent keyEvent) {
        return keyEvent.getRepeatCount() > 0 && this.mTrackedKeyEvents.contains(Long.valueOf(getKeyEventId(keyEvent)));
    }

    /**
     * Returns {@code true} if this key event has the repeat flag set but the key was
     * <em>not</em> registered via {@link #trackKeyEvent}. Such events are generated
     * for keys held before the IME received focus and should be discarded.
     *
     * @param keyEvent the hardware key event to test
     * @return {@code true} if this is a spurious, untracked key repeat
     */
    public boolean isUntrackedKeyRepeat(KeyEvent keyEvent) {
        return keyEvent.getRepeatCount() > 0 && !this.mTrackedKeyEvents.contains(Long.valueOf(getKeyEventId(keyEvent)));
    }

    /**
     * Returns {@code true} if the key code of the given event is currently tracked
     * (i.e., a key-down was seen and the key has not yet been released).
     *
     * @param keyEvent the hardware key event whose key code to check
     * @return {@code true} if the key is currently held and tracked
     */
    public boolean isKeyTracked(KeyEvent keyEvent) {
        return this.mTrackedKeyEvents.contains(Long.valueOf(getKeyEventId(keyEvent)));
    }

    /**
     * Registers a hardware key-down event so that subsequent repeat events for the same
     * key can be identified as tracked repeats. Call from {@code onHardwareKeyDown}.
     *
     * @param keyEvent the key-down event to register
     */
    public void trackKeyEvent(KeyEvent keyEvent) {
        this.mTrackedKeyEvents.add(Long.valueOf(getKeyEventId(keyEvent)));
    }

    /**
     * Removes the key code of the given event from the tracking set on key-up.
     * Balances a preceding {@link #trackKeyEvent} call.
     *
     * @param keyEvent the key-up event whose key code to untrack
     * @return {@code true} if the key was present in the tracking set and was removed
     */
    public boolean untrackKeyEvent(KeyEvent keyEvent) {
        return this.mTrackedKeyEvents.remove(Long.valueOf(getKeyEventId(keyEvent)));
    }

    /**
     * Handles a soft (on-screen) key press for shift-held tracking.
     * Sets the internal shift-held flag when the shift key ({@code -1}) is pressed.
     *
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
     * Handles a soft (on-screen) key release for shift-held tracking.
     * Clears the shift-held flag for any key release other than backspace.
     *
     * @param i the key code of the released soft key
     */
    public void onSoftKeyUp(int i) {
        if (i != -5) {
            this.mBackspaceController.mIsShiftHeld = false;
        }
    }

    /**
     * Handles a hardware key-down event. On the first press (not repeat) it updates the
     * shift-held and backspace-active flags and records shift key state for accelerated delete.
     *
     * @param keyEvent the hardware key-down event
     */
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

    /**
     * Handles a hardware key-up event. Clears the shift-held flag (for non-backspace keys)
     * and the backspace-active flag (for backspace).
     *
     * @param keyEvent the hardware key-up event
     */
    public void onHardwareKeyUp(KeyEvent keyEvent) {
        if (keyEvent.getKeyCode() != 67) {
            this.mBackspaceController.mIsShiftHeld = false;
        } else {
            this.mBackspaceController.mIsBackspaceActive = false;
        }
    }

    /**
     * Central input event processing entry point. Dispatches the event through the full
     * pipeline: keyboard-state transform, auto-capitalize, at-dot substitution, gesture
     * recognition, functional key handling (backspace, enter, cursor movement), separator
     * handling, and character key handling.
     *
     * @param c0804d         current settings values
     * @param event         the input event (character, functional key, or gesture)
     * @param interfaceC0648a keyboard view callback (provides shift state and symbol page)
     * @param enumC0690f     the source of the event (TOUCH, HARDWARE, INTERNAL, etc.)
     * @param i              symbol page order / shift state
     * @param handlerC0650c  UI update handler for posting deferred updates
     * @return an {@link InputEventContext} describing the outcome and required UI updates
     */
    public InputEventContext processInputEvent(SettingsValues c0804d, InputEvent event, SymbolPageProvider interfaceC0648a, InputSource enumC0690f, int i, UIUpdateHandler handlerC0650c) {
        Keyboard c0965eM6834l;

        int i2 = event.mCodePoint;
        // TI-1/TI-25/TI-40: this used to build an unread `charDisplay` string (a char[], a String
        // and two concatenations per keystroke), call two classification predicates into empty
        // branches, and route the event through a three-local if/else whose two arms were
        // identical. Only the switched-keyboard rewrite ever mattered.
        KeyboardSwitcher c0979iM4088ac = this.mIme.getKeyboardSwitcher();
        InputEvent c0914a2 = (c0979iM4088ac != null
                && (c0979iM4088ac.isMainKeyboardShowing() || c0979iM4088ac.isInSymbolMode()))
                ? InputEvent.createFromSwitchedKeyboard(event) : event;

        // Create the gesture event with proper timing and configuration
        InputEventContext c0920g = new InputEventContext(c0804d, c0914a2, SystemClock.uptimeMillis(), this.mCommitType, getShiftState(c0804d, interfaceC0648a.getSymbolPageOrder()));
        c0920g.setShiftPressed(interfaceC0648a.isManualShiftAndShiftPressing());
        c0920g.setInputSource(enumC0690f);

        // Reset gesture tracking if this is a new gesture sequence
        if (c0914a2.mKeyCode != -5 || c0920g.timestamp > this.mLastGestureTimestamp + 200) {
            this.mBackspaceController.resetDeleteCounters();
        }

        // Update internal state
        this.mSuggestionEngine.setSkipNuanceCheck(false);
        this.mLastGestureTimestamp = c0920g.timestamp;
        this.mRichInputConnection.beginBatchEdit();

        if (!this.mComposingTracker.isComposing()) {
            this.mIsAutoCorrectActive = false;
        }

        // Apply transformations if needed
        InputEvent processedEvent = isEmailVariationField() ? handleAtDotSubstitution(c0914a2) : c0914a2;

        if (c0804d.isAutoCapsEnabled && !processedEvent.isModifierKey()) {
            processedEvent = autoCapitalize(processedEvent, c0920g);
        }

        // Handle space key special case
        if (processedEvent.mCodePoint != 32) {
            resetSpaceTimestamp();
            this.mJustCommitted = false;
        }

        // Process the event chain with proper control flow
        boolean isSpaceKey = false;
        boolean hasProcessedGesture = false;
        boolean hasModifiedEvent = false;
        boolean shouldContinueProcessing = false;

        while (processedEvent != null) {
            if (processedEvent.isGestureEnd()) {
                // Handle gesture end events
                this.mHasModifiedEvent = false;
                handleGestureEnd(processedEvent, c0920g);
            } else if (processedEvent.isFunctionalKeyEvent()) {
                // Handle gesture move events
                handleFunctionalKeyEvent(processedEvent, c0804d, c0920g, i);
            } else {
                // Handle regular key events
                SmartPunctuationAnalyzer smartPunctuation = this.mSmartPunctuationAnalyzer;
                if (smartPunctuation != null) {
                    // Handle space key gesture completion
                    if (smartPunctuation.isDumbMode() && processedEvent.mCodePoint == 32) {
                        this.mSmartPunctuationAnalyzer.clearDumbMode();
                        this.mSuggestionEngine.setBlockAutoCorrect(false);
                    }

                    // Process gesture recognition if enabled and not already processed
                    if (isGestureEnabled() && !hasModifiedEvent) {
                        SmartPunctuationAnalyzer.Result punctuationResult = processGestureRecognition(processedEvent, c0920g);
                        hasProcessedGesture = punctuationResult.appendAutoSpace;
                        InputEvent modifiedEvent = punctuationResult.event;

                        if (punctuationResult.eventsPrepended && !LocaleUtils.isCurrentSubtypeJapanese()) {
                            // Apply gesture modification and continue processing
                            c0920g.setInputSource(InputSource.INTERNAL);
                            processedEvent = modifiedEvent;
                            hasModifiedEvent = true;
                            shouldContinueProcessing = true;
                            continue;
                        }
                    }
                }

                // Process the current event
                processCharacterKeyEvent(processedEvent, c0804d, c0920g, i, handlerC0650c, shouldContinueProcessing);
                isSpaceKey = processedEvent.mCodePoint == 32;
            }

            // Move to next event in chain
            processedEvent = processedEvent.mNextEvent;
        }

        // Handle post-processing based on gesture state
        if (c0920g.isKeyHandled()) {
            this.mHasModifiedEvent = (c0920g.wasAutoCorrectApplied() && isSpaceKey) || hasProcessedGesture;
        }

        // Handle dictionary and learning updates
        if (!c0920g.wasAutoCorrectApplied() && c0914a2.mKeyCode != -1 && c0914a2.mKeyCode != -2 && c0914a2.mKeyCode != -3 && !isWordSeparator(c0920g.settingsValues, i2) && (this.mComposingTracker.isComposing() || c0914a2.mKeyCode != -5)) {
            this.mEventDispatcher.disableRevert();
        }

        if (-5 != c0914a2.mKeyCode) {
            this.mLastCommittedText = null;
        }

        this.mRichInputConnection.endBatchEdit();
        return c0920g;
    }

    /**
     * Returns {@code true} if the editor has either an active text selection or a known
     * cursor position greater than zero. Used to guard operations that require a valid cursor.
     *
     * @return {@code true} if the connection is active and cursor/selection is available
     */
    public boolean hasSelectionOrCursor() {
        return this.mRichInputConnection.hasSelection() || this.mRichInputConnection.getCursorStart() > 0;
    }

    /**
     * Handles a swipe-delete gesture on the keyboard. Deletes the selection if text is
     * selected, deletes to the beginning in password fields, or deletes by character (CJK)
     * or by word (other locales) for normal text.
     *
     * @param c0804d current settings values
     * @return {@code true} if any text was deleted; {@code false} if nothing could be deleted
     */
    public boolean handleSwipeDelete(SettingsValues c0804d) {
        return this.mBackspaceController.handleSwipeDelete(c0804d);
    }


    /**
     * Called when a suggestion strip update is needed (e.g., after a tap that repositions
     * the cursor into a committed word). Flushes composing state, enables the suggestion
     * worker, and sets the composing tracker's shift state.
     *
     * @param c0804d        current settings values
     * @param i             shift state / symbol page order for the suggestion context
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
     * Requests word suggestions for the current composing text from the suggestion worker.
     * Results are delivered asynchronously via the suggestion strip listener.
     *
     * @param c0804d current settings values (not forwarded; used by callers to gate the call)
     */
    public void requestSuggestions(SettingsValues c0804d) {
        this.mSuggestionRequestQueue.requestSuggestions();
    }

    /**
     * Requests next-word predictions from the suggestion worker.
     *
     * @param enumC0690f the input source that triggered the prediction request
     */
    public void requestPredictions(InputSource enumC0690f) {
        if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "InputLogic.requestPredictions: source=" + enumC0690f + " — refreshing suggestion strip (does NOT commit anything)");
        this.mSuggestionRequestQueue.requestPredictions(enumC0690f);
    }

    /**
     * Disables the suggestion worker and clears the suggestion strip display. Called when
     * the active editor type does not support suggestions (e.g., password or numeric fields).
     *
     * @param handlerC0650c UI update handler used to clear the strip immediately
     */
    public void disableSuggestions(UIUpdateHandler handlerC0650c) {
        this.mSuggestionRequestQueue.disableSuggestions();
        handlerC0650c.postShowSuggestions(SuggestedWords.EMPTY);
    }

    /**
     * Called when the suggestion worker delivers a new set of suggestions. Stores the
     * auto-correction candidate and highlight word in the composing tracker, caches the
     * full list, and updates the auto-correct indicator on the composing text span.
     *
     * @param c0666ac       the new suggested words (may be {@link SuggestedWords#EMPTY})
     * @param c0804d        current settings values
     * @param handlerC0650c UI update handler (unused in this method; reserved for future use)
     */
    public void onSuggestionsReceived(SuggestedWords c0666ac, SettingsValues c0804d, UIUpdateHandler handlerC0650c) {
        String strMo4284a;
        int iM4296a;
        // Audit DW-3: the two tracker writes below used to run BEFORE the isComposing guard
        // at the bottom of this method, so a worker reply that crossed a session teardown
        // stamped the PREVIOUS field's auto-correct candidate and picked suggestion onto a
        // tracker holding no word at all. Setting an auto-correction for a word that does not
        // exist is meaningless by construction, so the guard belongs above them.
        //
        // Deliberately NOT hoisted with it: the mCurrentSuggestions write below, and the
        // mIsBackspaceActive half of the original guard. mCurrentSuggestions doubles as the
        // gesture-commit channel (SuggestionRequestQueue:118-130 — the gesture word arrives
        // through it when the engine returns empty), and a gesture is not composing, so
        // gating that write on isComposing() would starve gesture commit. That is the EB-2
        // mistake and it is not repeatable here without gesture coverage in the harness; the
        // cache half of DW-3 stays open on purpose.
        if (SuggestedWords.EMPTY != c0666ac && this.mComposingTracker.isComposing()) {
            // F11 (audit note): the "score" stored below is actually the suggestion KIND
            // of the auto-correct candidate — the separator-commit path treats nonzero as
            // "AC eligible" (handleSeparatorInput checks getAutoCorrectionScore() != 0).
            // This works only under the unstated invariant that a will-auto-correct
            // candidate never has kind 0. Renaming/typing this properly is a W6b task;
            // do not change the logic without auditing SuggestedWords kind semantics.
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
            this.mComposingTracker.setAutoCorrection(strMo4284a, iM4296a);
            if (c0666ac.size() > SuggestedWords.getMinSuggestionsIndex()) {
                this.mComposingTracker.setPickedSuggestion(c0666ac.getWordInfo(SuggestedWords.getMinSuggestionsIndex()));
            }
        }
        this.mCurrentSuggestions = c0666ac;
        boolean z = c0666ac.mWillAutoCorrect;
        if (!this.mComposingTracker.isComposing() || this.mBackspaceController.mIsBackspaceActive) {
            return;
        }
        if (this.mIsAutoCorrectActive == z && (SuggestedWords.EMPTY == c0666ac)) {
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
        this.mIsAutoCorrectActive = z;
        if (this.mRichInputConnection.hasWordAfterCursor(c0804d.spacingAndPunctuation)) {
            this.mIsAutoCorrectActive = false;
        }
        if (!this.mComposingTracker.isCursorMoved()) {
            // Defense in depth: never re-send composing text after the connection was
            // invalidated (cursor position unknown — the Enter editor-action path). The
            // editor has no composing region there, so setComposingText would INSERT a
            // duplicate of the word instead of replacing the region. Primary fix lives
            // in KeyEventProcessor's Enter consumer (tracker clearAll + update cancel);
            // this guard catches a worker reply that was already in flight.
            if (this.mRichInputConnection.getCursorStart() == -1) {
                return;
            }
            setComposingTextInternal(getComposingTextWithIndicator(this.mComposingTracker.getComposingText()), 1);
        }
    }

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
                                        // FIX: Skip recorrection when cursor is mid-word (same fix as character insertion)
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
     * Determines whether a backspace event should be processed by the IME's composing logic
     * rather than passed through as a raw key event. Returns {@code false} only when the
     * cursor position is unknown, the event is a key repeat, and the delete state has not
     * changed since the last repeat — indicating we are in a pass-through scenario.
     *
     * @param event the backspace input event
     * @param i      current cursor-unavailable flag (1 if cursor position unknown, else 0)
     * @param i2     delete state from the previous backspace event
     * @param c0909x the rich input connection
     * @return {@code true} if the IME should handle the backspace; {@code false} to pass through
     */
    public boolean shouldProcessBackspace(InputEvent event, int i, int i2, RichInputConnection c0909x) {
        return this.mBackspaceController.shouldProcessBackspace(event, i, i2, c0909x);
    }

    /**
     * Reads the primary clip from the system clipboard and pastes its text at the current
     * cursor position via {@link #pasteText}.
     */
    public void pasteFromClipboard() {
        pasteText(ClipboardUtil.getClipboardText(this.mIme));
    }

    /**
     * Pastes the given text at the current cursor position. Commits any active composing
     * or touch-event text first, then inserts the paste content as committed text.
     *
     * @param charSequence the text to paste; no-op if {@code null}
     */
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

    private void processCharacterKeyEvent(InputEvent event, SettingsValues c0804d, InputEventContext c0920g, int i, UIUpdateHandler handlerC0650c, boolean z) {
        c0920g.markKeyHandled();
        if (event.mCodePoint == 10) {
            handleEnterKey(c0920g);
            return;
        }
        if ((c0920g.getInputSource() == InputSource.HARDWARE) && !this.mComposingTracker.isComposing() && !LocaleUtils.isCurrentSubtypePinyin()) {
            // FIX: Skip recorrection when cursor is mid-word.
            // When user deliberately positions cursor mid-word, characters should insert there,
            // not cause the word to become uncommitted with cursor snapping to end.
            // hasWordAfterCursor returns true if char AFTER cursor is a word char (not separator/connector)
            // hasWordBeforeCursor returns true if cursor is adjacent to word content
            // Both true = cursor is mid-word
            // TI-12: hasWordAfterCursor/hasWordBeforeCursor each cost an editor round-trip, and
            // this block used to make five of them (one of them for a local that was never read).
            if (!isCursorMidWord(c0804d)) {
                performRecorrection(c0804d, true, i);
            }
        }
        routeKeyEvent(event, c0920g, handlerC0650c, z);
    }

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
            // Cursor is inside the composing word (state ≈ CURSOR_INSIDE, except this also
            // fires in the corner case where the tracker is simultaneously in prediction
            // mode with non-empty buffer). Insert the new code point AT the cursor
            // (preserving trailing characters), update the editor composing region with the
            // full new text, and place the editor cursor at the matching position inside
            // the composing region. Previously this branch called truncateToComposingCursor
            // and let the normal append path run, which silently dropped every character
            // after the cursor — see docs/archived/2026-05_composing-and-ckb-gestures/2026-05_composing-spec-and-tests_reference.md #6.
            //
            // Compute the editor's composing-region start BEFORE mutating the tracker:
            // editor cursor = composingStart + chars-before-cursor-within-composing.
            String oldComposing = this.mComposingTracker.getComposingText();
            int oldCursorCharOffset = Character.offsetByCodePoints(oldComposing, 0,
                    this.mComposingTracker.getComposingCursorPos());
            int composingStart = this.mRichInputConnection.getCursorEnd() - oldCursorCharOffset;

            this.mComposingTracker.insertCodePointAtCursor(event.mCodePoint);
            String fullText = this.mComposingTracker.getComposingText();
            CharSequence display = getComposingTextWithIndicator(fullText);
            setComposingTextInternal(display, 1);

            int newCursorCharOffset = Character.offsetByCodePoints(fullText, 0,
                    this.mComposingTracker.getComposingCursorPos());
            int desiredCursor = composingStart + newCursorCharOffset;
            // Not plain setSelection: that would leave the RIC text model holding the
            // composing prefix in BOTH mTextBeforeCursor and mComposingText, corrupting
            // every reader (caps mode, punctuation, backspace decisions) until the next
            // resetConnection. See docs/archived/2026-06_fable-audits-and-gesture-rebuild/2026-06_composition-pipeline_audit.md F5.
            this.mRichInputConnection.setSelectionWithinComposing(desiredCursor, composingStart);
            if (BuildConfig.DEBUG) {
            android.util.Log.d("TEXT_EDIT_DEBUG", "handleCharacterInput: insertedAtCursor codePoint=" + event.mCodePoint
                    + " composingNow='" + fullText + "' composingStart=" + composingStart
                    + " newCursorCharOffset=" + newCursorCharOffset + " setSelection=" + desiredCursor);
            }
            c0920g.setShouldUpdateSuggestions();
            return;
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

    private void handleSeparatorInput(InputEvent event, InputEventContext c0920g, UIUpdateHandler handlerC0650c) {
        boolean z;
        int i = event.mCodePoint;
        SettingsValues c0804d = c0920g.settingsValues;
        boolean zM5182e = false;
        boolean z2 = 32 == i && !c0804d.spacingAndPunctuation.currentLanguageHasSpaces && this.mComposingTracker.isComposing();
        if (this.mComposingTracker.isCursorMoved()) {
            resetComposingAndSelect(this.mRichInputConnection.getCursorStart(), this.mRichInputConnection.getCursorEnd(), true);
        }
        if (BuildConfig.DEBUG) {
        android.util.Log.d("PKB_SPACE_AUTOCORRECT_DEBUG", "handleSeparatorInput: codePoint=" + i + " src=" + c0920g.getInputSource()
            + " composing=" + this.mComposingTracker.isComposing()
            + " composingText='" + this.mComposingTracker.getComposingText() + "'"
            + " acEnabledPerUser=" + c0804d.isAutoCorrectionEnabledPerUserSettings
            + " acMode=" + c0804d.autoCorrectionMode);
        }
        if (this.mComposingTracker.isComposing()) {
            flushPendingSuggestions(c0804d, handlerC0650c);
            String strM5463a = z2 ? "" : new String(Character.toChars(i));
            if (isCjkLocale() || LocaleUtils.isCurrentSubtypeJapanese()) {
                handleCjkAutoCorrect(c0804d, c0920g, handlerC0650c, strM5463a);
            } else {
                int iM4368u = this.mComposingTracker.getAutoCorrectionScore();
                String acWord = this.mComposingTracker.getAutoCorrection();
                if (BuildConfig.DEBUG) {
                android.util.Log.d("PKB_SPACE_AUTOCORRECT_DEBUG", "handleSeparatorInput: acWord='" + acWord + "' acScore=" + iM4368u
                    + " willTryAC=" + CommitController.shouldCommitAutoCorrectCandidate(acWord, iM4368u, c0804d.isAutoCorrectionEnabledPerUserSettings, c0804d.editorCapabilities.shouldShowSuggestions)
                    + " src=" + c0920g.getInputSource());
                }
                // FIX-MACRO / D-3: kind 7 (a substitution/macro) commits regardless of the
                // auto-correct setting, but NOT in a field that declared it wants no suggestions
                // - see CommitController.shouldCommitAutoCorrectCandidate for the original's
                // expression (c/a.java:461) and for why we diverge on that last term.
                if (CommitController.shouldCommitAutoCorrectCandidate(acWord, iM4368u, c0804d.isAutoCorrectionEnabledPerUserSettings, c0804d.editorCapabilities.shouldShowSuggestions)) {
                    autoCorrectAndCommit(c0804d, strM5463a, handlerC0650c, c0920g.getInputSource());
                    c0920g.setAutoCorrectApplied();
                } else {
                    if (BuildConfig.DEBUG) {
                    android.util.Log.d("PKB_SPACE_AUTOCORRECT_DEBUG", "handleSeparatorInput: AC REJECTED reason=" +
                        (acWord == null ? "acWord_null"
                            : iM4368u == CommitController.KIND_SUBSTITUTION ? "macro_in_no_suggestions_field"
                            : iM4368u == 0 ? "kind_zero" : "ac_disabled"));
                    }
                    commitTypedWord(c0804d, strM5463a, c0920g.getInputSource());
                }
            }
            z = true;
        } else {
            commitTouchEventText();
            z = false;
        }
        boolean zM4416c = shouldStripSpace(event, c0920g);
        boolean z3 = 34 == i && this.mRichInputConnection.endsWithQuoteAfterDigit();
        if (4 == c0920g.commitType) {
            if (34 == i) {
                zM5182e = !z3;
            } else if (!c0804d.spacingAndPunctuation.clustersWithSymbols(i) || !c0804d.spacingAndPunctuation.clustersWithSymbols(this.mRichInputConnection.getCodePointBeforeCursor())) {
                zM5182e = c0804d.isUsuallyPrecededBySpace(i);
            }
        }
        if (zM5182e) {
            appendAutoSpace(c0804d, c0920g.getInputSource());
        }
        if (!z && handleDoubleSpacePeriod(event, c0920g)) {
            this.mCommitType = 1;
            c0920g.setShouldUpdateSuggestions();
        } else if (zM4416c && trySwapPunctuation(event, c0920g)) {
            this.mCommitType = 2;
            this.mSuggestionStripListener.setNeutralSuggestionStrip();
        } else if (32 == i) {
            if (!this.mCurrentSuggestions.isAutoCorrection()) {
                this.mCommitType = 3;
            }
            recordSpaceTimestamp(c0920g);
            if (z || this.mCurrentSuggestions.isEmpty() || c0920g.isKeyHandled()) {
                c0920g.setShouldUpdateSuggestions();
            }
            if (!z2 && !z) {
                commitCharacter(c0804d, i, c0920g.getInputSource());
            }
        } else {
            if ((4 == c0920g.commitType && c0804d.isUsuallyFollowedBySpace(i)) || (34 == i && z3)) {
                this.mCommitType = 4;
            }
            if (!z) {
                commitCharacter(c0804d, i, c0920g.getInputSource());
            }
            this.mSuggestionStripListener.setNeutralSuggestionStrip();
        }
        c0920g.setUiUpdateMode(1);
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
     * Commits the current composing text, or resets composing state to idle if the cursor
     * has been moved mid-word. Falls back to flushing any pending touch-event text when
     * not composing.
     *
     * @param c0804d     current settings values
     * @param enumC0690f the input source that triggered this flush
     */
    public void commitOrResetComposing(SettingsValues c0804d, InputSource enumC0690f) {
        mCommitController.commitOrResetComposing(c0804d, enumC0690f);
    }

    /**
     * TI-32: {@code true} when the editor is single-line, i.e. neither multi-line flag is set. The
     * raw constants were {@code 262144} ({@link InputType#TYPE_TEXT_FLAG_IME_MULTI_LINE}) and
     * {@code 131072} ({@link InputType#TYPE_TEXT_FLAG_MULTI_LINE}); the method was called
     * {@code editorSupportsSuggestions()} and a comment in {@link #handleEnterKey} claimed it
     * tested the NO_SUGGESTIONS/AUTO_COMPLETE flags. It never did. Renamed, not re-specified -
     * the Enter-key decisions below are written against the multi-line test they actually got.
     */
    private boolean isSingleLineField() {
        EditorInfo editorInfoM4500g = getCurrentEditorInfo();
        return (editorInfoM4500g.inputType & InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE) == 0
                && (editorInfoM4500g.inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0;
    }

    private void handleEnterKey(InputEventContext c0920g) {
        boolean z;
        boolean z2;
        
        // FIX: Get actual IME action from EditorInfo instead of using incorrectly-passed symbol-page-order value
        // The old value came from getSymbolPageOrder() which is unrelated to IME actions
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
        // FIX: Use imeAction from EditorInfo instead of c0920g.symbolPageOrder
        boolean z4 = imeAction == 1;  // IME_ACTION_NONE
        boolean z5 = imeAction == 0;  // IME_ACTION_UNSPECIFIED
        boolean isHardwareInput = c0920g.getInputSource() == InputSource.HARDWARE;
        
        // Check if Shift is pressed - Shift+Enter always inserts newline
        boolean isShiftPressed = c0920g.isShiftPressed() || this.mIme.getPhysicalKeyboardStateTracker().isManualShiftAndShiftPressing();
        
        // Check the multiline flag directly instead of !isSingleLineField() (TI-32: that method was
        // called editorSupportsSuggestions() and was believed to check NO_SUGGESTIONS/AUTO_COMPLETE;
        // it does not). For multiline fields with NONE/UNSPECIFIED action
        // (like email compose body), Enter should always insert a newline.
        // IME_FLAG_NO_ENTER_ACTION (0x40000000) is intentionally excluded: it is a UI flag that
        // controls whether the action button is shown in the IME, not a signal that Enter should
        // insert a newline. Including it caused Enter to insert a newline (and double-commit the
        // composing word) in search fields that set this flag, instead of submitting the search.
        boolean isMultiline = editorInfo != null && (editorInfo.inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0;
        boolean shouldInsertNewline = isShiftPressed || (isMultiline && (z4 || z5));
        
        // FIX: Use DeviceProfile.isPkb() instead of the broken legacy version check
        // That check was just testing Android version < 23, not actual PKB detection
        if (dev.bbkb.ime.core.device.profile.DeviceProfile.isPkb()) {
            z = !isHardwareInput && (isSingleLineField() || !z4);
            z2 = !isHardwareInput || shouldInsertNewline;
        } else {
            z = isSingleLineField() || !(z4 || z5);
            // Non-PKB devices should also respect IME action for hardware input
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
            // Do NOT call clearAll() here: the tracker must stay composing so the
            // pendingEditorAction consumer (KeyEventProcessor's Enter block) can finalize
            // the word with finishComposingText() BEFORE performEditorAction — the order
            // that keeps the cursor after the word in WebView editors (Outlook fix).
            // The consumer is then responsible for clearAll() + cancelling the delayed
            // suggestion update queued above; leaving either behind lets the late
            // suggestion reply re-insert the word (launcher-search duplication bug).
            c0920g.setPendingEditorAction(true);
        }
    }

    private void handleBackspace(InputEvent event, InputEventContext c0920g, SettingsValues c0804d, int i) {
        this.mBackspaceController.handleBackspace(event, c0920g, c0804d, i);
    }

    private void launchVoiceInput() {
        this.mIme.switchToNextSubtype(InputSource.HARDWARE);
    }

    private void toggleKeyboardDirection(boolean z) {
        this.mIme.updateSymbolShift(z);
    }

    private boolean trySwapPunctuation(InputEvent event, InputEventContext c0920g) {
        return this.mPunctuationController.trySwapPunctuation(event, c0920g);
    }

    private boolean shouldStripSpace(InputEvent event, InputEventContext c0920g) {
        return this.mPunctuationController.shouldStripSpace(event, c0920g);
    }

    /**
     * Records the timestamp of the current event as the last-space timestamp, enabling
     * double-space-to-period detection on the following space press.
     *
     * @param c0920g the current input event context whose timestamp is recorded
     */
    public void recordSpaceTimestamp(InputEventContext c0920g) {
        this.mPunctuationController.recordSpaceTimestamp(c0920g);
    }

    /**
     * Clears the last-space timestamp, disabling double-space-to-period detection until
     * the next space is typed.
     */
    public void resetSpaceTimestamp() {
        this.mPunctuationController.resetSpaceTimestamp();
    }

    private boolean handleDoubleSpacePeriod(InputEvent event, InputEventContext c0920g) {
        return this.mPunctuationController.handleDoubleSpacePeriod(event, c0920g, this.mJustCommitted);
    }

    /**
     * Synchronously requests suggestions from the suggestion worker and updates the
     * suggestion strip. Blocks the caller until results are ready (up to 1 second).
     * Used only in situations where async delivery is not feasible.
     *
     * @param c0804d current settings values
     * @param i      update reason code forwarded to the worker
     */
    public void updateSuggestionsSync(SettingsValues c0804d, int i) {
        mSuggestionCoordinator.updateSuggestionsSync(c0804d, i);
    }

    /**
     * Asynchronously requests suggestions or predictions from the suggestion worker.
     * Results are delivered to the UI update handler via {@code postShowSuggestionStrip}.
     *
     * @param c0804d current settings values
     * @param i      update reason code forwarded to the worker
     */
    public void updateSuggestionsAsync(SettingsValues c0804d, int i) {
        mSuggestionCoordinator.updateSuggestionsAsync(c0804d, i);
    }

    /**
     * Attempts to enter re-correction mode for the word under the cursor. Reads the word
     * range at the cursor, validates it, sets up the composing region, fetches correction
     * candidates from the suggestion worker, and displays them in the strip.
     *
     * @param c0804d current settings values
     * @param z      {@code true} to include the tapped word itself as the first suggestion
     * @param i      symbol page order / shift state used for suggestion context
     */
    public void performRecorrection(SettingsValues c0804d, final boolean z, int i) {
        mRecorrectionController.performRecorrection(c0804d, z, i);
    }

    void revertAutoCorrection(InputEventContext c0920g, SettingsValues c0804d) {
        mRecorrectionController.revertAutoCorrection(c0920g, c0804d);
    }

    int getShiftState(SettingsValues c0804d, int i) {
        KeyboardSwitcher c0979iM4088ac = this.mIme.getKeyboardSwitcher();
        
        if (c0979iM4088ac != null && c0979iM4088ac.isMainKeyboardShowing()) {
            return 0;
        }
        // FIX: Always analyze caps mode for predictions, not just when i==5.
        // Previously, symbol page order values (like 1) were being returned directly
        // and incorrectly interpreted as shift states, causing capitalization after
        // committing suggestions mid-sentence.
        int iM4490d = getCapsMode(c0804d);
        if ((iM4490d & 4096) != 0) {
            return 7;
        }
        return iM4490d != 0 ? 5 : 0;
    }

    /**
     * Returns the current auto-capitalization mode for the editor, or {@code 0} if
     * auto-caps is disabled, the locale does not support it, or the editor is not active.
     *
     * @param c0804d current settings values
     * @return a bitmask of {@link android.text.TextUtils} caps mode flags, or 0
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

    /**
     * Returns the result code from the config parser, or {@code -1} if no config rule applies.
     *
     * <p>Always {@code -1}. The {@code RecapitalizeStatus} this used to consult was never
     * started: its only {@code start()} call was in its own constructor, immediately followed by
     * {@code stop()}, and {@link #resetInputState} then called {@code disable()}, so no later
     * {@code start()} could take. The class was deleted; all three consumers
     * ({@code BlackBerryIME}, {@code CommitController}, {@code UnifiedInputBoardManager}) already
     * received {@code -1}.
     *
     * @return {@code -1}
     */
    public int getConfigParserResult() {
        return -1;
    }

    /**
     * Returns the {@link EditorInfo} for the editor that currently has input focus.
     * Delegates to {@link BlackBerryIME#getCurrentInputEditorInfo()}.
     *
     * @return the current editor's metadata, or {@code null} if no editor is focused
     */
    public EditorInfo getCurrentEditorInfo() {
        return this.mIme.getCurrentInputEditorInfo();
    }

    PrevWordsInfo getPrevWordsInfo(SpacingAndPunctuation c0806f, int i) {
        return this.mRichInputConnection.getPrevWordsInfo(c0806f, i);
    }

    private void performEditorActionById(int i) {
        this.mRichInputConnection.performEditorAction(i);
    }

    /**
     * Strips a leading '.' from a voice-recognised word if the preceding character in the
     * editor is already a '.', preventing double-dot sequences.
     *
     * @param str the string to process
     * @return the input string with a spurious leading dot removed, or the original string
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
     * Clears all composing state in the composing tracker. If {@code z} is {@code true},
     * also resets the event dispatcher to idle, discarding any pending auto-correction info.
     *
     * @param z {@code true} to also reset the event dispatcher
     */
    public void clearComposingText(boolean z) {
        this.mComposingTracker.clearAll();
        if (z) {
            this.mEventDispatcher = CommitEventRecord.IDLE;
        }
    }

    private void clearTouchEventProcessor(boolean z) {
        if (this.mTouchHighlightTracker.isHighlightActive()) {
            if (z) {
                this.mIme.getMultitapEventHandler().commitMultitapIfActive();
            }
            this.mTouchHighlightTracker.clear();
        }
    }

    SuggestedWords createSuggestionsWithTypedWord(String str, SuggestedWords c0666ac) {
        return mSuggestionCoordinator.createSuggestionsWithTypedWord(str, c0666ac);
    }

    CharSequence getComposingTextWithIndicator(String str) {
        if (this.mTouchHighlightTracker.isHighlightActive()) {
            return this.mTouchHighlightTracker.applyHighlight(str);
        }
        return this.mIsAutoCorrectActive ? SuggestionSpanBuilder.getTextWithAutoCorrectionIndicator(this.mIme, str) : str;
    }

    /**
     * Synthesises a key-down + key-up pair for the given key code with no meta state and
     * sends it through the input connection.
     *
     * @param i the Android key code to send
     */
    public void sendKeyEvent(int i) {
        sendKeyEventWithMeta(i, 0);
    }

    /**
     * Sends a key-down event for the given key code with no meta state.
     *
     * @param i the Android key code
     */
    public void sendKeyDown(int i) {
        long jUptimeMillis = SystemClock.uptimeMillis();
        this.mRichInputConnection.sendKeyEvent(new KeyEvent(jUptimeMillis, jUptimeMillis, 0, i, 0, 0, -1, 0, 6));
    }

    /**
     * Sends a key-up event for the given key code with no meta state.
     *
     * @param i the Android key code
     */
    public void sendKeyUp(int i) {
        long jUptimeMillis = SystemClock.uptimeMillis();
        this.mRichInputConnection.sendKeyEvent(new KeyEvent(jUptimeMillis, jUptimeMillis, 1, i, 0, 0, -1, 0, 6));
    }

    /**
     * Synthesises a key-down + key-up pair for the given key code with the specified meta state.
     *
     * @param i  the Android key code
     * @param i2 the meta state bitmask (e.g., {@link android.view.KeyEvent#META_SHIFT_ON})
     */
    public void sendKeyEventWithMeta(int i, int i2) {
        // TI-36: this body was a byte-for-byte duplicate of CursorController's private copy.
        CursorController.sendKeyEventWithMeta(this.mRichInputConnection, i, i2);
    }

    /**
     * Sends only a key-down event for the given key code with the specified meta state.
     *
     * @param i  the Android key code
     * @param i2 the meta state bitmask
     */
    public void sendKeyDownWithMeta(int i, int i2) {
        long jUptimeMillis = SystemClock.uptimeMillis();
        this.mRichInputConnection.sendKeyEvent(new KeyEvent(jUptimeMillis, jUptimeMillis, 0, i, 0, i2, -1, 0, 6));
    }

    /**
     * Sends only a key-up event for the given key code with the specified meta state.
     *
     * @param i  the Android key code
     * @param i2 the meta state bitmask
     */
    public void sendKeyUpWithMeta(int i, int i2) {
        this.mRichInputConnection.sendKeyEvent(new KeyEvent(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), 1, i, 0, i2, -1, 0, 6));
    }

    private void commitCharacter(SettingsValues c0804d, final int i, InputSource enumC0690f) {
        mCommitController.commitCharacter(c0804d, i, enumC0690f);
    }

    /**
     * Flushes any pending touch-event text to the editor. If composing text is active, updates
     * the composing span; otherwise commits the accumulated touch-event string as plain text.
     */
    public void commitTouchEventText() {
        mCommitController.commitTouchEventText();
    }

    /**
     * Checks whether dynamic learning should be enabled or disabled for the current editor
     * and input type, and updates the text context tracker and learning engine accordingly.
     * Short-circuits if neither the learning preference nor the input type has changed.
     */
    public void updateDynamicLearningState() {
        SettingsValues settingsValues = SettingsManager.getInstance().getSettingsValues();
        boolean z = settingsValues.isDynamicLearningEnabled;
        EditorInfo editorInfoM4500g = getCurrentEditorInfo();
        if (this.mLastDynamicLearningEnabled == z && (editorInfoM4500g == null || editorInfoM4500g.inputType == this.mLastInputType)) {
            return;
        }
        if (!z) {
            this.mTextContextTracker.setEnabled(false);
            this.mIme.getDynamicLearningManager().setDynamicLearningEnabled(false);
        } else {
            this.mTextContextTracker.updateLocale(SubtypeManager.getInstance().getCurrentSubtypeLocale(), settingsValues.spacingAndPunctuation);
            boolean zM4428f = editorInfoM4500g != null ? isLearningEnabledForInputType(editorInfoM4500g.inputType) : false;
            this.mTextContextTracker.setEnabled(zM4428f);
            this.mIme.getDynamicLearningManager().setDynamicLearningEnabled(zM4428f);
        }
        this.mLastDynamicLearningEnabled = z;
        this.mLastInputType = editorInfoM4500g != null ? editorInfoM4500g.inputType : this.mLastInputType;
    }

    /**
     * Returns {@code true} if dynamic learning (personal dictionary updates) should be
     * enabled for the given {@link android.view.inputmethod.EditorInfo#inputType}. Learning
     * is disabled for password, URI, email-address, numeric, phone, and similar fields.
     *
     * @param i the input type bitmask from the editor's {@link android.view.inputmethod.EditorInfo}
     * @return {@code true} if learning is permitted for this input type
     */
    public static boolean isLearningEnabledForInputType(int i) {
        int i2 = i & 4080;
        return (InputTypeUtils.isPasswordInputType(i) || InputTypeUtils.isVisiblePasswordInputType(i) || ((524288 & i) != 0 && i2 == 160) || !InputTypeUtils.isTextClass(i) || InputTypeUtils.isUriVariation(i2) || InputTypeUtils.isFilterVariation(i2) || InputTypeUtils.isPhoneticVariation(i2)) ? false : true;
    }

    /**
     * Resets the text context tracker, discarding any accumulated typing history used
     * for dynamic learning and context-based predictions.
     */
    public void resetTextContextTracker() {
        this.mTextContextTracker.invalidate();
    }

    /**
     * Directly enables or disables dynamic learning in the text context tracker.
     * Called when the user changes the learning preference at runtime.
     *
     * @param z {@code true} to enable learning; {@code false} to disable
     */
    public void setDynamicLearningEnabled(boolean z) {
        this.mTextContextTracker.setEnabled(z);
    }

    private void appendAutoSpace(SettingsValues c0804d, InputSource enumC0690f) {
        this.mPunctuationController.appendAutoSpace(c0804d, enumC0690f);
    }

    /**
     * Commits the top gesture (swipe) suggestion as a typed word, appending the appropriate
     * word separator, updating keyboard state, and requesting a gesture suggestions update.
     *
     * @param c0804d     current settings values
     * @param c0666ac    the gesture suggestion list (top word at index 0 is committed)
     * @param enumC0690f the input source (typically TOUCH)
     */
    public void commitGestureSuggestion(SettingsValues c0804d, SuggestedWords c0666ac, InputSource enumC0690f) {
        mCommitController.commitGestureSuggestion(c0804d, c0666ac, enumC0690f);
    }

    /**
     * Commits the current composing text as a user-typed word followed by the given separator.
     * No-op if no composing text is active.
     *
     * @param c0804d     current settings values
     * @param str        the separator to append after the word (may be empty)
     * @param enumC0690f the input source that triggered the commit
     */
    public void commitTypedWord(SettingsValues c0804d, String str, InputSource enumC0690f) {
        mCommitController.commitTypedWord(c0804d, str, enumC0690f);
    }

    /** @return {@code true} if the separator payload was already committed — see SS-1. */
    private boolean autoCorrectAndCommit(SettingsValues c0804d, String str, UIUpdateHandler handlerC0650c, InputSource enumC0690f) {
        return mCommitController.autoCorrectAndCommit(c0804d, str, handlerC0650c, enumC0690f);
    }

    private void autoCorrectAndCommitExtended(SettingsValues c0804d, String str, UIUpdateHandler handlerC0650c, InputSource enumC0690f, boolean z) {
        mCommitController.autoCorrectAndCommitExtended(c0804d, str, handlerC0650c, enumC0690f, z);
    }

    /**
     * Strips internal clipboard placeholder markers ({@code MARKER_CLIPBOARD},
     * {@code MARKER_CLIPBOARD_LOWER}) and NuanceSDK boundary markers ({@code MARKER_BOUNDARY})
     * from a suggestion word before it is committed to the editor.
     *
     * @param str the raw suggestion string (may contain internal markers)
     * @return the cleaned string with all markers removed
     */
    public String processClipboardMarker(String str) {
        return mCommitController.processClipboardMarker(str);
    }

    /**
     * Replace the last character with an accented variant, maintaining proper state
     * in both ComposingTextTracker and RichInputConnection.
     * Mirrors the accent-replacement logic of the deleted AccentBarController, which was lost
     * when AuxBarManager replaced it and routed through a raw InputConnection bypass.
     */
    public void replaceLastCharWithAccent(String accentChar) {
        this.mRichInputConnection.beginBatchEdit(); // beginBatchEdit

        if (this.mComposingTracker.isComposing()) {
            // Composing text active: modify composing text tracker
            String composing = this.mComposingTracker.getComposingText();
            TouchPointerCoordTracker coords = this.mComposingTracker.getCoordinateTracker();
            if (composing.length() > 0) {
                int lastStart = composing.offsetByCodePoints(composing.length(), -1);
                CharSequence trimmed = composing.subSequence(0, lastStart);
                if (trimmed.length() > 0) {
                    int[] codePoints = toCodePointArray(trimmed);
                    this.mComposingTracker.setComposingFromCodePoints(codePoints, this.mIme.getKeyCoordinates(codePoints), coords.getIntentionalFlags());
                } else {
                    this.mComposingTracker.clearAll();
                }
            }
            // Add accent character to composing text
            this.mComposingTracker.processInputEvent(InputEvent.createFromSwitchedKeyboard(InputEvent.createTextInputEvent(accentChar, -1)));
            // Update editor via setComposingText through RichInputConnection
            this.mRichInputConnection.setComposingText(this.mComposingTracker.getComposingText(), 1);
        } else {
            // No composing text: delete base char and commit accent through RichInputConnection
            this.mRichInputConnection.deleteSurroundingText(1, 0);
            this.mRichInputConnection.commitText(accentChar, 1);
        }

        this.mRichInputConnection.endBatchEdit(); // endBatchEdit
    }

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

    void flushPendingSuggestions(SettingsValues c0804d, UIUpdateHandler handlerC0650c) {
        mSuggestionCoordinator.flushPendingSuggestions(c0804d, handlerC0650c);
    }

    int commitWord(SettingsValues c0804d, String str, CommitEventRecord.CommitType commitTypeVar, String str2, InputSource enumC0690f) {
        return mCommitController.commitWord(c0804d, str, commitTypeVar, str2, enumC0690f);
    }

    private int commitWordExtended(SettingsValues c0804d, String str, CommitEventRecord.CommitType commitTypeVar, String str2, InputSource enumC0690f, boolean z) {
        return mCommitController.commitWordExtended(c0804d, str, commitTypeVar, str2, enumC0690f, z);
    }

    private boolean commitPredictionWord(SettingsValues c0804d, SuggestedWords.SuggestedWordInfo suggestedWordInfoVar, CommitEventRecord.CommitType commitTypeVar2, InputSource enumC0690f, InputEventContext c0920g, UIUpdateHandler handlerC0650c, String str) {
        return mCommitController.commitPredictionWord(c0804d, suggestedWordInfoVar, commitTypeVar2, enumC0690f, c0920g, handlerC0650c, str);
    }

    /**
     * Handles a select-all or keyboard-switch event. Resets the input connection state,
     * recalibrates the cursor, and triggers a shift-state update if requested.
     *
     * @param z             {@code true} to trigger a shift-state update after reset
     * @param i             retry count; if the reset fails and {@code i > 0}, a deferred
     *                      keyboard-switch update is posted
     * @param handlerC0650c UI update handler
     * @return {@code true} if the connection reset succeeded; {@code false} if it was deferred
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
     * Requests suggestions enriched with keyboard animation context. Used by the text decorator
     * to obtain suggestions that consider the visual layout and user-visible key positions.
     *
     * @param context  application context
     * @param c0804d   current settings values
     * @param c1073o   keyboard animator providing key-position context
     * @param i        shift state / symbol page order
     * @param i2       suggestion request flags
     * @param i3       dead: the sequence number, always -1. Kept only because
     *                 {@code BlackBerryIME.runSuggestionRequest} (coordinator-owned) still
     *                 declares and forwards it; drop both together.
     * @param aVar     callback to receive suggestions when ready
     */
    public void requestSuggestionsWithContext(Context context, SettingsValues c0804d, ProximityGrid c1073o, int i, int i2, SuggestionEngine.SuggestionCallback aVar) {
        this.mComposingTracker.setShiftStateIfNotComposing(getShiftState(c0804d, i));
        // FIX 2025-03-29: Wire autoCorrectionMode setting into SuggestionEngine so that
        // mAutoCorrectMode is not always 0 (Java default). Previously setAutoCorrectMode()
        // and setAutoCorrectThreshold() existed but were never called from the settings path.
        this.mSuggestionEngine.setAutoCorrectMode(c0804d.autoCorrectionMode);
        if (BuildConfig.DEBUG) {
        android.util.Log.d("PKB_SPACE_AUTOCORRECT_DEBUG", "requestSuggestionsWithContext: acMode=" + c0804d.autoCorrectionMode
            + " acEnabled=" + c0804d.isAutoCorrectionEnabledPerUserSettings
            + " composing='" + this.mComposingTracker.getComposingText() + "'"
            + " isPredMode=" + this.mComposingTracker.isPredictionMode());
        }
        this.mSuggestionEngine.getSuggestedWords(context, this.mComposingTracker, getPrevWordsInfo(c0804d.spacingAndPunctuation, this.mComposingTracker.isComposing() ? 2 : 1), c1073o, new SuggestionStripSettings(c0804d.blockPotentiallyOffensiveWords, c0804d.additionalFeaturesSettings, this.mIme.getCurrentInputBinding() == null ? false : ProfileDetector.isInputFromWorkProfile(this.mIme.getCurrentInputBinding())), c0804d.isAutoCorrectionEnabledPerUserSettings, i2, aVar);
    }

    void setComposingTextInternal(CharSequence charSequence, int i) {
        if (BuildConfig.DEBUG) Log.d("TEXT_EDIT_DEBUG", "setComposingTextInternal: text='" + charSequence + "' cursorPos=" + i);
        setComposingTextWithHighlight(charSequence, i, 0, 0, charSequence.length());
    }

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
     * Installs a timer proxy in the more-keys controller, enabling timed key-repeat and
     * long-press handling for more-key popups.
     *
     * @param interfaceC1076r the timer proxy to install
     */
    public void setTimerProxy(TextDecoratorUiOperator interfaceC1076r) {
        this.mMoreKeysController.setTimerProxy(interfaceC1076r);
    }

    /**
     * Forwards updated cursor anchor information to the more-keys controller so that
     * more-key popups can be positioned relative to the cursor on screen.
     *
     * @param cursorAnchorInfo the latest cursor anchor info
     */
    public void updateCursorAnchorInfo(CursorAnchorInfo cursorAnchorInfo) {
        this.mMoreKeysController.onUpdateCursorAnchorInfo(cursorAnchorInfo);
    }

    /**
     * Enables or disables the more-keys (long-press popup) controller.
     *
     * @param z {@code true} to enable; {@code false} to disable
     */
    public void setMoreKeysEnabled(boolean z) {
        this.mMoreKeysController.setShouldShow(z);
    }

    /**
     * Dismisses any visible more-keys popup and clears background highlight spans
     * from the editor text.
     */
    public void dismissMoreKeys() {
        this.mRichInputConnection.clearBackgroundSpans();
        this.mMoreKeysController.reset();
    }

    /**
     * TI-13: {@code true} for an email-variation editor with no extra input-type flags set. Named
     * {@code isTextInputType()} until the audit; the name said nothing about what it tests and it
     * gates only the @/. substitution below.
     */
    private boolean isEmailVariationField() {
        EditorInfo editorInfoM4500g = getCurrentEditorInfo();
        if (editorInfoM4500g == null) {
            return false;
        }
        int i = editorInfoM4500g.inputType;
        return InputTypeUtils.isEmailVariation(i & 4080) && (i & 16773120) == 0;
    }

    private InputEvent handleAtDotSubstitution(InputEvent event) {
        boolean z = false; // Initialize z to false by default
        if (!DeviceProfile.current().hasShiftedSymbolKeyboard()) {
            return event;
        }
        if (event.isKeyPressEvent() && event.mCodePoint == 32 && !this.mWasAtOrDot) {
            // TI-5: this used to ask the editor for the ENTIRE document before the cursor over IPC,
            // materialise it as a String and scan all of it, on every space press in an email
            // field. The cached context window is what the rule actually needs.
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

    private SmartPunctuationAnalyzer.Result processGestureRecognition(InputEvent event, InputEventContext c0920g) {
        String strM4361n = this.mComposingTracker.getComposingTextBeforeCursor();
        // TI-11: getTextContextBefore() copies the before-cursor buffer; it used to be called twice
        // in this one expression, on every gesture-enabled keystroke.
        String contextBefore = this.mRichInputConnection.getTextContextBefore();
        SmartPunctuationAnalyzer.Result punctuationResult = this.mSmartPunctuationAnalyzer.analyze((contextBefore != null ? contextBefore : "") + strM4361n, event, this.mHasModifiedEvent, c0920g.settingsValues, c0920g.getInputSource() == InputSource.INTERNAL);
        this.mSuggestionEngine.setBlockAutoCorrect(this.mSmartPunctuationAnalyzer.isDumbMode());
        if (punctuationResult.backspaceInjected) {
            this.mEventDispatcher.disableRevert();
        }
        return punctuationResult;
    }

    /**
     * True when {@code i} is a word SEPARATOR - i.e. NOT part of a word.
     *
     * <p>ST-7/F3: this used to be named for the opposite of what it returns. Every caller was
     * written against the value rather than the name, so the rename is the only thing that
     * changed here - see {@code routeKeyEvent}, which feeds a {@code true} result to
     * {@code handleSeparatorInput}, and {@code CommitController}, which uses it to decide
     * whether a one-code-point string is a separator worth committing after the word.</p>
     *
     * <p>Distinct from {@link SettingsValues#isWordSeparator(int)}: that one consults the
     * {@code spacingAndPunctuation} separator table; this one asks whether the code point can
     * be word CONTENT at all (letter/digit/word-connector/combining mark) or - under a CJK
     * locale - whether it is absent from the per-script "not separators" table. Keep the
     * double negative in the CJK arm; see the note on
     * {@link SettingsValues#isNotWordSeparatorForLocale(int, java.util.Locale)}.</p>
     */
    boolean isWordSeparator(SettingsValues c0804d, int i) {
        if (isCjkLocale()) {
            return !c0804d.isNotWordSeparatorForLocale(i, SubtypeManager.getInstance().getCurrentSubtypeLocale());
        }
        return !c0804d.isWordCodePoint(i);
    }

    /**
     * Cancels any active composing text or touch-event accumulation without committing,
     * finishes the composing region in the editor, and hides the suggestion strip.
     */
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
     * Moves the cursor {@code i} positions to the right (or sends {@code KEYCODE_DPAD_RIGHT}
     * with optional shift for selection). Cancels any active composing first.
     *
     * @param c0804d current settings values (used for layout direction and batching)
     * @param i      number of positions to move
     * @param z      {@code true} to extend the selection while moving
     */
    public void moveCursorRight(SettingsValues c0804d, int i, boolean z) {
        this.mCursorController.moveCursorRight(c0804d, i, z);
    }

    /**
     * Moves the cursor {@code i} positions to the left (or sends {@code KEYCODE_DPAD_LEFT}
     * with optional shift for selection). Cancels any active composing first.
     *
     * @param c0804d current settings values (used for layout direction and batching)
     * @param i      number of positions to move
     * @param z      {@code true} to extend the selection while moving
     */
    public void moveCursorLeft(SettingsValues c0804d, int i, boolean z) {
        this.mCursorController.moveCursorLeft(c0804d, i, z);
    }

    /**
     * Moves the cursor {@code i} lines upward by sending {@code KEYCODE_DPAD_UP}.
     * Cancels any active composing first.
     *
     * @param i number of lines to move
     * @param z {@code true} to extend the selection while moving
     */
    public void moveCursorUp(int i, boolean z) {
        this.mCursorController.moveCursorUp(i, z);
    }

    /**
     * Moves the cursor {@code i} lines downward by sending {@code KEYCODE_DPAD_DOWN}.
     * Cancels any active composing first.
     *
     * @param i number of lines to move
     * @param z {@code true} to extend the selection while moving
     */
    public void moveCursorDown(int i, boolean z) {
        this.mCursorController.moveCursorDown(i, z);
    }

    boolean isCjkLocale() {
        return LocaleUtils.isCurrentSubtypeChinese();
    }

    /**
     * Returns {@code true} if the current editor's input type is a password variation.
     *
     * @return {@code true} if the focused field is a password field
     */
    public boolean isPasswordField() {
        EditorInfo editorInfoM4500g = getCurrentEditorInfo();
        return editorInfoM4500g != null && InputTypeUtils.isPasswordInputType(editorInfoM4500g.inputType);
    }

    /**
     * Returns {@code true} if the current editor is any kind of password field (visible, web or
     * numeric).
     *
     * <p>TI-13: this was called {@code isUriField()} and documented as a URI test while testing
     * {@code isAnyPasswordInputType}. Its one caller is the Japanese composing guard in
     * {@code handleCharacterInput}, which suppresses composition in password fields - the
     * behaviour is right, the name was not. Renamed to match what it does rather than changing
     * the predicate, which would have flipped that guard.
     *
     * @return {@code true} if the focused field is a password field of any variation
     */
    public boolean isAnyPasswordField() {
        EditorInfo editorInfoM4500g = getCurrentEditorInfo();
        return editorInfoM4500g != null && InputTypeUtils.isAnyPasswordInputType(editorInfoM4500g.inputType);
    }

    /**
     * Returns the word separator that the dictionary recommends following {@code str} in
     * the current text context. Falls back to a space if no dictionary is loaded.
     *
     * @param str the word whose trailing separator should be resolved
     * @return the recommended separator string (e.g., {@code " "} or {@code ", "})
     */
    public String getWordSeparator(String str) {
        if (!this.mDictionaryLoader.isDictionaryReady()) {
            return " ";
        }
        return this.mDictionaryLoader.getMainDictionary().getWordSeparator(this.mRichInputConnection.getTextContextBefore() + str);
    }

    /**
     * NuanceSDK auto-commit callback. Invoked (possibly from a native thread) when the
     * Nuance engine decides to auto-commit text. Ensures the commit is dispatched on the
     * main thread before forwarding to the input connection.
     *
     * @param str the text to commit, as determined by the Nuance engine
     */
    @Override // com.blackberry.nuanceshim.NuanceSDK.AutoCommitCallback
    public void onAutoCommitWord(String str) {
        if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "InputLogic.onAutoCommitWord (NuanceSDK auto-commit callback): word='" + str + "' — this is the gesture-typed word arriving from native");
        // FIX Issue 1+11: NuanceSDK JNI callback may fire from native thread.
        // All RichInputConnection mutations must happen on the main thread.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "InputLogic.onAutoCommitWord: committing on main thread — word='" + str + "'");
            this.mRichInputConnection.commitText(str, 1);
            if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "InputLogic.onAutoCommitWord: commitText completed — requesting predictions");
            requestPredictions(InputSource.HARDWARE);
        } else {
            if (InputPathDebug.on()) Logger.info("CKB_SWIPE_TYPE_DEBUG", "InputLogic.onAutoCommitWord: posting commit to main handler — word='" + str + "' currentThread=" + Thread.currentThread().getName());
            // Audit DW-1: this post used to be uncancellable and unguarded — if the user left
            // the field between the native callback and delivery, the gesture word committed
            // into whatever editor was current by then. Capture the session generation and
            // refuse to commit into a different session (cancelInput also drops the post).
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

    void updateNuanceContext(String str, int i, String str2) {
        mSuggestionCoordinator.updateNuanceContext(str, i, str2);
    }

    private void commitWordToDictionary(String str) {
        mSuggestionCoordinator.commitWordToDictionary(str);
    }
}

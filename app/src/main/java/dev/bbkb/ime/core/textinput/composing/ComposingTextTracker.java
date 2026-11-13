package dev.bbkb.ime.core.textinput.composing;

import static dev.bbkb.ime.core.shared.StringHelper.*;

import android.text.SpannableStringBuilder;
import android.text.TextUtils;

import dev.bbkb.ime.core.inputmethod.HangulInputProcessor;
import dev.bbkb.ime.core.textinput.composing.CommitEventRecord;
import dev.bbkb.ime.core.inputmethod.RomajiInputProcessor;
import dev.bbkb.ime.core.engine.EngineFacts;
import dev.bbkb.ime.core.engine.EngineThreads;
import dev.bbkb.ime.core.engine.PrimaryEngineFacts;
import dev.bbkb.ime.core.inputmethod.AbstractInputProcessor;
import dev.bbkb.ime.core.inputmethod.InputMethodCallback;
import dev.bbkb.ime.core.inputmethod.TextComposer;
import dev.bbkb.ime.core.keyevent.InputEvent;
import dev.bbkb.ime.core.suggestion.PrevWordsInfo;
import dev.bbkb.ime.core.suggestion.SuggestedWords;
import dev.bbkb.ime.core.shared.CoordinateUtils;
import dev.bbkb.ime.core.locale.LocaleUtils;
import dev.bbkb.ime.core.shared.StringHelper;
import dev.bbkb.ime.core.shared.ScriptUtils;
import com.blackberry.nuanceshim.NuanceSDK;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import dev.bbkb.ime.BuildConfig;



public final class ComposingTextTracker {

    private static final HashMap<String, Class<? extends InputMethodCallback>> CONVERTER_REGISTRY;

    private static final Map<Character, Character> STROKE_TO_NUMBER_MAP = new HashMap<>();

    private static final Map<Character, Character> CANGJIE_RADICAL_TO_KEY_MAP;

    private String mConverterName;

    private String mAutoCorrection;

    private int mAutoCorrectionScore;

    /**
     * Bumped by every edit that drops the auto-correction candidate. A suggestion request records it,
     * so a reply arriving after a later keystroke can be told apart from a current one.
     */
    private int mEditCount;

    /** {@link #mEditCount} as of the request that produced {@link #mAutoCorrection}; -1 = none. */
    private int mAutoCorrectionEditCount = -1;

    private SuggestedWords.SuggestedWordInfo mPickedSuggestion;

    private boolean mIsGestureInput;

    final RecorrectionState mRecorrectionState = new RecorrectionState();

    private int mEventIndex;

    private boolean mIsPredictionMode;

    private String mLastCommittedWord;

    private boolean wasAutoCorrected;

    private int mUpperCaseCount;

    private int mDigitCount;

    private int mShiftState;

    private TriState mCachedUrlCheck;

    private TriState mCachedEmailCheck;

    private int mCodePointCount;

    private int mComposingCursorPos;

    private boolean mFirstLetterUppercase;

    private InputMethodCallback mInputMethodConverter;

    /** Volatile: resolved inside an engine-thread op ({@link #engine()}) and read by later ops. */
    private volatile NuanceSDK mNuanceSDK;

    /**
     * The engine this tracker was built with (the spell checker's secondary engine), or null for the
     * IME's tracker, which resolves the primary inside each op ({@link #mEngineSource}).
     */
    private final NuanceSDK mFixedSdk;

    /**
     * Engine calls never run on the calling thread: each is recorded here with values captured on the
     * caller, and one tracker call's steps post as a single FIFO op ({@link #flushEngineSteps}) so nothing
     * lands between a clear and its re-feed. Reads use {@link #queryEngine}. Guarded by this monitor.
     */
    private final ArrayList<EngineStep> mPendingSteps = new ArrayList<>();

    /** Nesting depth of the tracker calls building the current op; the outermost one posts it. */
    private int mBatchDepth;

    /**
     * How long a converter language (Korean, Telex, Romaji) waits for its composed text. Only bounds a
     * wait behind a language load; after it the unconverted text stays.
     */
    private static final long CONVERTER_QUERY_TIMEOUT_MS = 2000L;

    /** {@link #CONVERTER_QUERY_TIMEOUT_MS}; shortened by the timeout test only. */
    private static volatile long sConverterQueryTimeoutMs = CONVERTER_QUERY_TIMEOUT_MS;

    /** Tests: the converter query's bound; a value of 0 or less restores the default. */
    static void setConverterQueryTimeoutForTests(long timeoutMs) {
        sConverterQueryTimeoutMs = timeoutMs > 0 ? timeoutMs : CONVERTER_QUERY_TIMEOUT_MS;
    }

    /** Debug builds log the first converter fallback only, not one per key. */
    private static volatile boolean sLoggedConverterFallback;

    /** One engine call of a tracker method, run on the engine thread against this tracker's engine. */
    private interface EngineStep {
        void run(NuanceSDK sdk);
    }

    /** An engine read a tracker method needs the answer to, run after the pending steps. */
    private interface EngineQuery<T> {
        T run(NuanceSDK sdk);
    }

    /**
     * Engine supplier for a tracker built without one: the IME's tracker is built in the service
     * constructor, before the engine exists, so it resolves the engine at first real use. Null otherwise.
     */
    private final EngineSource mEngineSource;

    /** Supplies the engine on first use; may return null while it is unavailable. */
    public interface EngineSource {
        NuanceSDK get();
    }

    private SpannableStringBuilder mComposingText = new SpannableStringBuilder();

    private TouchPointerCoordTracker mCoordinateTracker = new TouchPointerCoordTracker(48);

    private enum TriState {
        NOT_INITIALIZED,
        FALSE,
        TRUE
    }

    static {
        STROKE_TO_NUMBER_MAP.put((char) 19968, (char) 1);
        STROKE_TO_NUMBER_MAP.put((char) 20008, (char) 2);
        STROKE_TO_NUMBER_MAP.put((char) 20031, (char) 3);
        STROKE_TO_NUMBER_MAP.put((char) 20022, (char) 4);
        STROKE_TO_NUMBER_MAP.put((char) 20059, (char) 5);
        STROKE_TO_NUMBER_MAP.put('*', (char) 6);
        CANGJIE_RADICAL_TO_KEY_MAP = new HashMap();
        CANGJIE_RADICAL_TO_KEY_MAP.put((char) 65287, Character.valueOf(NuanceSDK.ET9CPSYLLABLEDELIMITER));
        CANGJIE_RADICAL_TO_KEY_MAP.put((char) 25163, 'Q');
        CANGJIE_RADICAL_TO_KEY_MAP.put((char) 30000, 'W');
        CANGJIE_RADICAL_TO_KEY_MAP.put((char) 27700, 'E');
        CANGJIE_RADICAL_TO_KEY_MAP.put((char) 21475, 'R');
        CANGJIE_RADICAL_TO_KEY_MAP.put((char) 24319, 'T');
        CANGJIE_RADICAL_TO_KEY_MAP.put((char) 21340, 'Y');
        CANGJIE_RADICAL_TO_KEY_MAP.put((char) 23665, 'U');
        CANGJIE_RADICAL_TO_KEY_MAP.put((char) 25096, 'I');
        CANGJIE_RADICAL_TO_KEY_MAP.put((char) 20154, 'O');
        CANGJIE_RADICAL_TO_KEY_MAP.put((char) 24515, 'P');
        CANGJIE_RADICAL_TO_KEY_MAP.put((char) 26085, 'A');
        CANGJIE_RADICAL_TO_KEY_MAP.put((char) 23608, 'S');
        CANGJIE_RADICAL_TO_KEY_MAP.put((char) 26408, 'D');
        CANGJIE_RADICAL_TO_KEY_MAP.put((char) 28779, 'F');
        CANGJIE_RADICAL_TO_KEY_MAP.put((char) 22303, 'G');
        CANGJIE_RADICAL_TO_KEY_MAP.put((char) 31481, 'H');
        CANGJIE_RADICAL_TO_KEY_MAP.put((char) 21313, 'J');
        CANGJIE_RADICAL_TO_KEY_MAP.put((char) 22823, 'K');
        CANGJIE_RADICAL_TO_KEY_MAP.put((char) 20013, 'L');
        CANGJIE_RADICAL_TO_KEY_MAP.put((char) 37325, 'Z');
        CANGJIE_RADICAL_TO_KEY_MAP.put((char) 38627, 'X');
        CANGJIE_RADICAL_TO_KEY_MAP.put((char) 37329, 'C');
        CANGJIE_RADICAL_TO_KEY_MAP.put((char) 22899, 'V');
        CANGJIE_RADICAL_TO_KEY_MAP.put((char) 26376, 'B');
        CANGJIE_RADICAL_TO_KEY_MAP.put((char) 24339, 'N');
        CANGJIE_RADICAL_TO_KEY_MAP.put((char) 19968, 'M');
        CONVERTER_REGISTRY = new HashMap<>();
        CONVERTER_REGISTRY.put("VietnameseTelexConverter", TextComposer.class);
        CONVERTER_REGISTRY.put("HangulConverter", HangulInputProcessor.class);
        CONVERTER_REGISTRY.put("RomajiConverter", RomajiInputProcessor.class);
    }

    public ComposingTextTracker(NuanceSDK nuanceSDK) {
        this(nuanceSDK, null);
    }

    /**
     * A tracker that resolves its engine at the first call that needs it, so constructing it never
     * creates (or waits for) the engine. A null answer is not remembered: the next use asks again.
     */
    public ComposingTextTracker(EngineSource engineSource) {
        this(null, engineSource);
    }

    private ComposingTextTracker(NuanceSDK nuanceSDK, EngineSource engineSource) {
        this.mNuanceSDK = nuanceSDK;
        this.mFixedSdk = nuanceSDK;
        this.mEngineSource = engineSource;
        this.mComposingText.clear();
        clearAutoCorrection();
        this.mIsGestureInput = false;
        this.mEventIndex = 0;
        this.mIsPredictionMode = false;
        this.mComposingCursorPos = 0;
        this.mLastCommittedWord = null;
        this.wasAutoCorrected = false;
        recalculateTextStats(false);
        this.mInputMethodConverter = null;
    }

    /**
     * Resolved on first use, which can wait for the engine's background start. Only called inside
     * engine-thread ops.
     */
    private NuanceSDK engine() {
        NuanceSDK sdk = this.mNuanceSDK;
        if (sdk == null && this.mEngineSource != null) {
            sdk = this.mEngineSource.get();
            if (sdk != null) {
                this.mNuanceSDK = sdk;
            }
        }
        return sdk;
    }

    /**
     * What this tracker may know about its engine without calling it (the published facts): the
     * fixed engine's own for the spell checker's tracker, the primary's otherwise.
     */
    private EngineFacts facts() {
        return this.mFixedSdk != null ? PrimaryEngineFacts.of(this.mFixedSdk) : PrimaryEngineFacts.get();
    }

    /** Opens a tracker call whose engine steps are posted together when the outermost one ends. */
    private void beginEngineBatch() {
        this.mBatchDepth++;
    }

    /** Closes {@link #beginEngineBatch}; the outermost close posts the op. */
    private void endEngineBatch() {
        if (--this.mBatchDepth <= 0) {
            this.mBatchDepth = 0;
            flushEngineSteps();
        }
    }

    /** Records one engine call; posted at once when no tracker call is building an op. */
    private void engineStep(EngineStep step) {
        this.mPendingSteps.add(step);
        if (this.mBatchDepth == 0) {
            flushEngineSteps();
        }
    }

    private EngineStep[] drainEngineSteps() {
        if (this.mPendingSteps.isEmpty()) {
            return null;
        }
        EngineStep[] steps = this.mPendingSteps.toArray(new EngineStep[0]);
        this.mPendingSteps.clear();
        return steps;
    }

    /** Posts the pending steps to the engine thread as one op (inline when already there). */
    private void flushEngineSteps() {
        final EngineStep[] steps = drainEngineSteps();
        if (steps == null) {
            return;
        }
        final NuanceSDK fixed = this.mFixedSdk;
        EngineThreads.post(() -> {
            NuanceSDK sdk = fixed != null ? fixed : engine();
            if (sdk == null) {
                return;
            }
            for (EngineStep step : steps) {
                step.run(sdk);
            }
        });
    }

    /**
     * Runs the pending steps then {@code query} in one engine op, waiting up to the timeout. Returns
     * {@code fallback} on a timeout (the op still runs later, so feeds are never lost) or with no engine.
     * The op may touch only captured values: the caller holds this monitor while it waits.
     */
    private <T> T queryEngine(final T fallback, final EngineQuery<T> query) {
        final EngineStep[] steps = drainEngineSteps();
        final NuanceSDK fixed = this.mFixedSdk;
        return EngineThreads.query(() -> {
            NuanceSDK sdk = fixed != null ? fixed : engine();
            if (sdk == null) {
                return fallback;
            }
            if (steps != null) {
                for (EngineStep step : steps) {
                    step.run(sdk);
                }
            }
            return query.run(sdk);
        }, sConverterQueryTimeoutMs, fallback);
    }

    /** A copy of the coordinates for an engine op, which must not share the live tracker. */
    private TouchPointerCoordTracker copyCoordinates() {
        TouchPointerCoordTracker copy = new TouchPointerCoordTracker(48);
        copy.copyFrom(this.mCoordinateTracker);
        return copy;
    }

    /**
     * Debug builds: the engine thread must never read the IME's tracker (it would deadlock, see
     * {@link ComposingSnapshot}). The spell checker's fixed-engine tracker is read there legitimately.
     */
    private void assertNotReadOnEngineThread(String what) {
        if (BuildConfig.DEBUG && this.mFixedSdk == null && EngineThreads.onEngineThread()) {
            throw new IllegalStateException("ComposingTextTracker." + what + " read on the engine "
                    + "thread; use the request's ComposingSnapshot");
        }
    }

    public synchronized boolean hasInputMethodConverter() {
        assertNotReadOnEngineThread("hasInputMethodConverter");
        return this.mInputMethodConverter != null;
    }

    /** The tracker's shift state (0, 1, 3, 5, 7); see {@link #setShiftState}. */
    public synchronized int getShiftState() {
        return this.mShiftState;
    }

    /**
     * @param converter the converter to install, or {@code null} to remove the current one
     */
    public void setInputMethodConverter(AbstractInputProcessor converter) {
        this.mInputMethodConverter = converter;
    }

    public synchronized InputMethodCallback.ConverterType getConverterType() {
        return this.mInputMethodConverter != null ? this.mInputMethodConverter.getConverterType() : InputMethodCallback.ConverterType.NOT_DEFINED;
    }

    /**
     * Installs the converter registered under {@code converterName}; empty, null or unknown names clear it.
     * @param converterName the converter name, or empty/null to clear the converter
     */
    public synchronized void setConverterByName(String converterName) {
        if (TextUtils.isEmpty(converterName)) {
            this.mConverterName = "";
            this.mInputMethodConverter = null;
            return;
        }
        if (!converterName.equals(this.mConverterName)) {
            this.mConverterName = converterName;
            // An unknown converter is a configuration problem, not a reason to crash on a locale switch.
            Class<? extends InputMethodCallback> converterClass = CONVERTER_REGISTRY.get(this.mConverterName);
            if (converterClass == null) {
                if (BuildConfig.DEBUG) android.util.Log.w("ComposingTextTracker", "Converter " + converterName + " is requested, but it is not listed in IME");
                this.mInputMethodConverter = null;
                return;
            }
            try {
                this.mInputMethodConverter = converterClass.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException("Unable to instantiate converter: " + converterName, e);
            }
        }
    }

    /** Resets all composing-session state, including the engine's shift state. */
    public synchronized void clearAll() {
        this.mComposingText.clear();
        this.mCoordinateTracker.reset();
        if (this.mInputMethodConverter != null) {
            this.mInputMethodConverter.reset();
        }
        clearAutoCorrection();
        this.mUpperCaseCount = 0;
        this.mDigitCount = 0;
        this.mFirstLetterUppercase = false;
        this.mIsGestureInput = false;
        this.mIsPredictionMode = false;
        this.mComposingCursorPos = 0;
        this.mLastCommittedWord = null;
        this.wasAutoCorrected = false;
        this.mPickedSuggestion = null;
        this.mCodePointCount = 0;
        this.mShiftState = 0;
        // This tracker's engine, never the primary: the spell checker's tracker wraps the secondary
        // engine, and zeroing the primary from its IPC thread broke the IME's shift state.
        engineStep(sdk -> sdk.setShiftState(0));
        this.mRecorrectionState.reset();
        recalculateTextStats(false);
    }

    /**
     * Inserts {@code codePoint} at the composing cursor and advances it. The engine is append-only,
     * so a mid-buffer edit clears and re-feeds the whole buffer.
     */
    public final synchronized void insertCodePointAtCursor(int codePoint) {
        if (!Character.isValidCodePoint(codePoint)) {
            return;
        }
        int charIndex = Character.offsetByCodePoints(this.mComposingText, 0, this.mComposingCursorPos);
        String inserted = new String(Character.toChars(codePoint));
        if (BuildConfig.DEBUG) {
        android.util.Log.d("TEXT_EDIT_DEBUG", "insertCodePointAtCursor: composingBefore='" + this.mComposingText
                + "' cursorCodePt=" + this.mComposingCursorPos + " charIndex=" + charIndex
                + " inserting='" + inserted + "'");
        }
        this.mComposingText.insert(charIndex, inserted);
        this.mComposingCursorPos += 1;
        // Full recount: the false path only recounts when the buffer shrank, and the append path's
        // incremental counting does not run for this insert.
        recalculateTextStats(true);
        // The append path clears any pending auto-correction on every event; the candidate
        // computed for the pre-edit text must not survive the edit either.
        clearAutoCorrection();
        rebuildNuanceFromComposing();
    }

    /** Deletes the code point before the composing cursor (no-op at the start) and re-feeds the engine. */
    public final synchronized void deleteCodePointBeforeCursor() {
        if (this.mComposingCursorPos <= 0 || this.mComposingText.length() == 0) {
            return;
        }
        int charIndexAfter = Character.offsetByCodePoints(this.mComposingText, 0, this.mComposingCursorPos);
        int deletedCp = Character.codePointBefore(this.mComposingText, charIndexAfter);
        int charIndexBefore = charIndexAfter - Character.charCount(deletedCp);
        if (BuildConfig.DEBUG) {
        android.util.Log.d("TEXT_EDIT_DEBUG", "deleteCodePointBeforeCursor: composingBefore='" + this.mComposingText
                + "' cursorCodePt=" + this.mComposingCursorPos + " deleting=[" + charIndexBefore + "," + charIndexAfter + ")");
        }
        this.mComposingText.delete(charIndexBefore, charIndexAfter);
        this.mComposingCursorPos -= 1;
        recalculateTextStats(false);
        // The pending auto-correction was computed for the pre-edit text.
        clearAutoCorrection();
        rebuildNuanceFromComposing();
    }

    /**
     * Clears the engine and re-feeds the composing buffer (the engine is append-only). CJK candidates
     * can be worse after a mid-word edit, since the engine does not know where it happened.
     */
    private void rebuildNuanceFromComposing() {
        // One op, so nothing can run between the clear and the re-feed.
        final InputMethodCallback converter = this.mInputMethodConverter;
        InputMethodCallback.ConverterType type = converter != null ? converter.getConverterType() : InputMethodCallback.ConverterType.NOT_DEFINED;
        if (type == InputMethodCallback.ConverterType.HANGUL || type == InputMethodCallback.ConverterType.VIETNAMESE_TELEX) {
            // These converters feed the engine jamo or base letters plus accents, never composed
            // characters. processSymbols keeps no converter state, so it may run on the engine thread.
            final String text = this.mComposingText.toString();
            engineStep(sdk -> {
                sdk.clear();
                converter.processSymbols(text, sdk);
            });
            return;
        }
        final boolean strokeMode = facts().isChineseStrokeMode();
        int len = this.mComposingText.length();
        StringBuilder symbols = new StringBuilder(len);
        int i = 0;
        while (i < len) {
            int cp = Character.codePointAt(this.mComposingText, i);
            if (cp <= 0xFFFF) {
                char c = (char) cp;
                Character strokeKey = STROKE_TO_NUMBER_MAP.get(c);
                Character cangjieKey = CANGJIE_RADICAL_TO_KEY_MAP.get(c);
                if (strokeMode && strokeKey != null) {
                    symbols.append(strokeKey.charValue());
                } else if (cangjieKey != null) {
                    symbols.append(cangjieKey.charValue());
                } else {
                    symbols.append(c);
                }
            }
            i += Character.charCount(cp);
        }
        final String feed = symbols.toString();
        engineStep(sdk -> {
            sdk.clear();
            for (int k = 0; k < feed.length(); k++) {
                sdk.processKeyBySymbol(feed.charAt(k));
            }
        });
    }

    /**
     * @param forceFullRecount true to recount digits and capitals even if the text grew
     */
    private final synchronized void recalculateTextStats(boolean forceFullRecount) {
        int i = this.mCodePointCount;
        TriState aVar = TriState.NOT_INITIALIZED;
        this.mCachedUrlCheck = aVar;
        this.mCachedEmailCheck = aVar;
        int length = this.mComposingText.length();
        int iCharCount = 0;
        this.mCodePointCount = Character.codePointCount(this.mComposingText, 0, length);
        if (forceFullRecount || i > this.mCodePointCount) {
            this.mDigitCount = 0;
            this.mUpperCaseCount = 0;
            while (iCharCount < length) {
                int iCodePointAt = Character.codePointAt(this.mComposingText, iCharCount);
                if (Character.isUpperCase(iCodePointAt)) {
                    this.mUpperCaseCount++;
                } else if (Character.isDigit(iCodePointAt)) {
                    this.mDigitCount++;
                }
                iCharCount += Character.charCount(iCodePointAt);
            }
        }
    }

    public synchronized int getCodePointCount() {
        return this.mCodePointCount;
    }

    public synchronized boolean isSingleCodePoint() {
        return getCodePointCount() == 1;
    }

    public synchronized boolean isComposing() {
        assertNotReadOnEngineThread("isComposing");
        return getCodePointCount() > 0;
    }

    /** True if composing is non-empty and every non-whitespace character is a digit. */
    public synchronized boolean isAllDigits() {
        if (getCodePointCount() > 0) {
            int nonWhitespace = 0;
            for (int k = 0; k < this.mComposingText.length(); k++) {
                if (!Character.isWhitespace(this.mComposingText.charAt(k))) {
                    nonWhitespace++;
                }
            }
            return this.mDigitCount == nonWhitespace;
        }
        return false;
    }

    public synchronized TouchPointerCoordTracker getCoordinateTracker() {
        return this.mCoordinateTracker;
    }

    /**
     * @param inputEvent the input event to process (key press or backspace)
     */
    public synchronized void processInputEvent(InputEvent inputEvent) {
        processInputEventWithShift(inputEvent, 0);
    }

    /**
     * @param shiftState keyboard shift state (from {@code InputEventContext.symbolPageOrder})
     * @param inputEvent the input event to process
     */
    public synchronized void processInputEventWithShift(InputEvent inputEvent, int shiftState) {
        if (inputEvent == null) {
            return;
        }
        beginEngineBatch();
        try {
            processInputEventInBatch(inputEvent, shiftState);
        } finally {
            endEngineBatch();
        }
    }

    /**
     * {@link #processInputEventWithShift}; its engine calls become one op (see {@link #mPendingSteps}).
     * @param inputEvent the input event to process (key press or backspace)
     * @param shiftState the shift state to apply
     */
    private void processInputEventInBatch(InputEvent inputEvent, int shiftState) {
        int iM4319b;
        boolean z;
        int length = this.mComposingText.length();
        if (-5 == inputEvent.mKeyCode) {
            boolean zM4314I = this.mInputMethodConverter != null ? processConverterBackspace() : false;
            if (zM4314I || length <= 0) {
                z = zM4314I;
                iM4319b = 0;
            } else {
                deleteLastCodePoint(length);
                z = zM4314I;
                iM4319b = -1;
            }
        } else {
            iM4319b = appendInputEventText(inputEvent);
            z = false;
        }
        recalculateTextStats(false);
        this.mComposingCursorPos = this.mCodePointCount;
        if (this.mCodePointCount == 0) {
            this.mFirstLetterUppercase = false;
        }
        updateCoordinatesAndCaps(inputEvent, iM4319b, length);
        clearAutoCorrection();
        if (length == 0) {
            engineStep(sdk -> sdk.clear());
            if (iM4319b == 1) {
                setShiftState(shiftState);
            }
        }
        processNuanceAndConverter(z ? false : true, inputEvent, iM4319b);
    }

    /**
     * @param composingLength the current composing text length
     */
    private void deleteLastCodePoint(int composingLength) {
        int iCodePointBefore = Character.codePointBefore(this.mComposingText, composingLength);
        if (composingLength == 1 && !HangulInputProcessor.isHangulSyllable(iCodePointBefore)) {
            this.mComposingText.clear();
            this.mCoordinateTracker.truncate(0);
            engineStep(sdk -> sdk.clear());
        } else {
            this.mComposingText.delete(composingLength - Character.charCount(iCodePointBefore), composingLength);
            engineStep(sdk -> sdk.clearOneSymbol());
            TouchPointerCoordTracker c0697m = this.mCoordinateTracker;
            c0697m.truncate(c0697m.getPointerSize() - 1);
        }
    }

    private boolean processConverterBackspace() {
        // The Telex revert ends with an engine clearOneSymbol, so it runs on the engine thread on copies
        // and is awaited. A timeout counts as nothing reverted; the late revert still pops its history.
        final InputMethodCallback converter = this.mInputMethodConverter;
        final String text = this.mComposingText.toString();
        final TouchPointerCoordTracker coords = copyCoordinates();
        InputMethodCallback.ConversionResult aVarMo4304b = queryEngine(null,
                sdk -> converter.revertLastConversion(text, coords, sdk));
        if (aVarMo4304b == null) {
            return false;
        }
        this.mComposingText = new SpannableStringBuilder(aVarMo4304b.text);
        if (!this.mIsPredictionMode) {
            this.mCoordinateTracker = aVarMo4304b.coordTracker;
        }
        return true;
    }

    /**
     * @param inputEvent the input event to process (key press or backspace)
     */
    private int appendInputEventText(InputEvent inputEvent) {
        CharSequence charSequenceM5933n = inputEvent.getOutputText();
        int iCodePointCount = Character.codePointCount(charSequenceM5933n, 0, charSequenceM5933n.length());
        if (!TextUtils.isEmpty(charSequenceM5933n)) {
            this.mComposingText.append(charSequenceM5933n);
        }
        return iCodePointCount;
    }

    /**
     * @param inputEvent the input event to process (key press or backspace)
     * @param codePointDelta the number of code points just added
     * @param startIndex the char offset where they start
     */
    private void updateCoordinatesAndCaps(InputEvent inputEvent, int codePointDelta, int startIndex) {
        boolean z;
        if (codePointDelta > 0) {
            boolean z2 = false;
            if (this.mIsPredictionMode) {
                z = false;
            } else {
                int i3 = inputEvent.mX;
                int i4 = inputEvent.mY;
                boolean zM5931l = inputEvent.isFromSwitchedKeyboard();
                int iCharCount = startIndex;
                z = false;
                int i5 = 0;
                while (i5 < codePointDelta && iCharCount < this.mComposingText.length()) {
                    int iCodePointAt = Character.codePointAt(this.mComposingText, iCharCount);
                    boolean zIsUpperCase = Character.isUpperCase(iCodePointAt);
                    if (zIsUpperCase) {
                        this.mUpperCaseCount++;
                    }
                    if (Character.isDigit(iCodePointAt)) {
                        this.mDigitCount++;
                    }
                    this.mCoordinateTracker.addPointer(iCodePointAt, i3, i4, 0, 0, zM5931l ? 1 : 0);
                    i5++;
                    iCharCount += Character.charCount(iCodePointAt);
                    z = zIsUpperCase;
                }
            }
            if (1 == this.mCodePointCount) {
                this.mFirstLetterUppercase = z;
                return;
            }
            if (this.mFirstLetterUppercase && !z) {
                z2 = true;
            }
            this.mFirstLetterUppercase = z2;
        }
    }

    /**
     * @param runConverter true to feed the converter (false when it already handled a backspace)
     * @param inputEvent the input event to process (key press or backspace)
     * @param codePointDelta the code point count change (negative for a delete)
     */
    private void processNuanceAndConverter(boolean runConverter, InputEvent inputEvent, int codePointDelta) {
        final InputMethodCallback converter = this.mInputMethodConverter;
        CharSequence charSequenceM5933n = inputEvent.getOutputText();
        EngineStep feed = null;
        if (codePointDelta > 0) {
            InputMethodCallback.ConverterType type = getConverterType();
            if ((type == InputMethodCallback.ConverterType.HANGUL || type == InputMethodCallback.ConverterType.VIETNAMESE_TELEX) && converter != null) {
                final String text = charSequenceM5933n.toString();
                feed = sdk -> converter.processSymbols(text, sdk);
            } else {
                // Each character's symbol is decided here from the published facts (no engine call)
                // and the op only replays it. The script lookup allocates, so it is resolved lazily.
                int iM5599a = Integer.MIN_VALUE;
                Boolean strokeMode = null;
                final int n = charSequenceM5933n.length();
                final char[] symbols = new char[n];
                final boolean[] explicit = new boolean[n];
                for (int i2 = 0; i2 < n; i2++) {
                    char cCharAt = charSequenceM5933n.charAt(i2);
                    Character strokeKey = STROKE_TO_NUMBER_MAP.get(cCharAt);
                    Character cangjieKey = CANGJIE_RADICAL_TO_KEY_MAP.get(cCharAt);
                    if (strokeKey != null && (strokeMode != null ? strokeMode
                            : (strokeMode = facts().isChineseStrokeMode()))) {
                        symbols[i2] = strokeKey.charValue();
                    } else if (cangjieKey != null) {
                        symbols[i2] = cangjieKey.charValue();
                    } else if (cCharAt > 127 && cCharAt != 305
                            && (iM5599a != Integer.MIN_VALUE ? iM5599a
                                : (iM5599a = ScriptUtils.getScriptFromLocale(PrimaryEngineFacts.primaryLanguage(facts())))) == 14) {
                        symbols[i2] = cCharAt;
                        explicit[i2] = true;
                    } else {
                        symbols[i2] = cCharAt;
                    }
                }
                feed = sdk -> {
                    for (int k = 0; k < symbols.length; k++) {
                        if (explicit[k]) {
                            sdk.addExplicitSymb(symbols[k]);
                        } else {
                            sdk.processKeyBySymbol(symbols[k]);
                        }
                    }
                };
            }
        }
        updateRecorrectionSnapshot();
        if (converter != null && runConverter) {
            // A converter language shows the engine's composed text, so feed and conversion run as
            // one bounded query on copies. On a timeout the unconverted text stays.
            final EngineStep feedStep = feed;
            final String composing = this.mComposingText.toString();
            final TouchPointerCoordTracker coords = copyCoordinates();
            InputMethodCallback.ConversionResult result = queryEngine(null, sdk -> {
                if (feedStep != null) {
                    feedStep.run(sdk);
                }
                return converter.convert(composing, coords, sdk);
            });
            if (result != null && result.text != null) {
                this.mComposingText = new SpannableStringBuilder(result.text);
                recalculateTextStats(true);
                this.mComposingCursorPos = this.mCodePointCount;
            } else if (BuildConfig.DEBUG && !sLoggedConverterFallback) {
                sLoggedConverterFallback = true;
                android.util.Log.w("ComposingTextTracker", "converter query fell back to the "
                        + "unconverted text (engine busy or unavailable); logged once");
            }
        } else if (feed != null) {
            engineStep(feed);
        }
    }

    /**
     * In code point units; equal to the code point count means at the end.
     * @param position the new in-composing cursor position (0 = before first character)
     */
    public synchronized void setComposingCursorPosition(int position) {
        this.mComposingCursorPos = position;
    }

    public synchronized boolean isCursorMoved() {
        return this.mComposingCursorPos != this.mCodePointCount;
    }

    public synchronized int getComposingCursorPos() {
        return this.mComposingCursorPos;
    }

    /**
     * Moves the composing cursor by {@code charDelta} UTF-16 chars (positive is right); false unless the move
     * is exact. Landing on position 0 returns true so {@code onUpdateSelection} keeps the word composing.
     * @param charDelta the number of code points to move (positive = right, negative = left)
     */
    public synchronized boolean moveCursorByCharCount(int charDelta) {
        int i2;
        int iCharCount;
        if (this.mInputMethodConverter != null) {
            this.mInputMethodConverter.reset();
        }
        int i3 = this.mComposingCursorPos;
        int[] iArrM5474a = toCodePointArray(this.mComposingText);
        if (charDelta >= 0) {
            i2 = i3;
            iCharCount = 0;
            while (iCharCount < charDelta && i2 < this.mCodePointCount) {
                iCharCount += Character.charCount(iArrM5474a[i2]);
                i2++;
            }
        } else {
            if (i3 > this.mCodePointCount) {
                return false;
            }
            i2 = i3;
            iCharCount = 0;
            while (iCharCount > charDelta && i2 > 0) {
                i2--;
                iCharCount -= Character.charCount(iArrM5474a[i2]);
            }
        }
        if (iCharCount != charDelta) {
            if (BuildConfig.DEBUG) {
            android.util.Log.d("TEXT_EDIT_DEBUG", "moveCursorByCharCount: FAILED charDelta=" + charDelta
                    + " achievedCharCount=" + iCharCount + " newCodePtPos=" + i2
                    + " composing='" + this.mComposingText + "'");
            }
            return false;
        }
        this.mComposingCursorPos = i2;
        if (BuildConfig.DEBUG) {
        android.util.Log.d("TEXT_EDIT_DEBUG", "moveCursorByCharCount: charDelta=" + charDelta
                + " newCodePtPos=" + i2 + " mCodePointCount=" + this.mCodePointCount
                + " composing='" + this.mComposingText + "'");
        }
        return true;
    }

    /** The composing text now holds a just-committed word kept open for next-word prediction. */
    public synchronized void enterPredictionMode() {
        this.mIsPredictionMode = true;
        this.mIsGestureInput = false;
        this.wasAutoCorrected = false;
    }

    /** Leaves prediction mode; does not clear the composing text. */
    public synchronized void resetPredictionState() {
        this.mIsGestureInput = false;
        this.mIsPredictionMode = false;
        this.mLastCommittedWord = null;
        this.wasAutoCorrected = false;
    }

    /**
     * Rebuilds the composing text by replaying code points as touch events. {@code coordinates} holds
     * {@link CoordinateUtils} x,y pairs; a non-zero {@code upperCaseFlags} entry forces upper case.
     * @param codePoints array of Unicode code points
     * @param coordinates flattened coordinate array (x,y pairs per pointer) from {@link dev.bbkb.ime.core.shared.CoordinateUtils}
     * @param upperCaseFlags per-code-point upper-case override flags (non-zero = force upper case)
     */
    public synchronized void setComposingFromCodePoints(int[] codePoints, int[] coordinates, int[] upperCaseFlags) {
        // One engine op for the whole rebuild.
        beginEngineBatch();
        try {
            setComposingFromCodePointsInBatch(codePoints, coordinates, upperCaseFlags);
        } finally {
            endEngineBatch();
        }
    }

    /**
     * @param codePoints the code points
     * @param coordinates interleaved x,y coordinates per code point
     * @param upperCaseFlags per code point, nonzero forces upper case
     */
    private void setComposingFromCodePointsInBatch(int[] codePoints, int[] coordinates, int[] upperCaseFlags) {
        clearAll();
        int length = codePoints.length;
        int length2 = upperCaseFlags.length;
        int i = 0;
        while (true) {
            boolean z = true;
            if (i >= length) {
                break;
            }
            if (i >= length2 || upperCaseFlags[i] == 0) {
                z = false;
            }
            processInputEvent(InputEvent.createTouchEvent(codePoints[i], CoordinateUtils.xFromArray(coordinates, i), CoordinateUtils.yFromArray(coordinates, i), z));
            i++;
        }
        // Must not set mIsGestureInput: only non-gesture paths (recorrection, spell checker, accent
        // substitution) rebuild here, and the flag gates auto-correction.
    }

    /**
     * @param codePoints array of Unicode code points
     * @param coordinates flattened coordinate array (x,y pairs per pointer)
     */
    public synchronized void setComposingFromCodePoints(int[] codePoints, int[] coordinates) {
        setComposingFromCodePoints(codePoints, coordinates, new int[0]);
    }

    public synchronized String getComposingText() {
        assertNotReadOnEngineThread("getComposingText");
        return this.mComposingText.toString();
    }

    public synchronized String getComposingTextBeforeCursor() {
        if (this.mComposingCursorPos >= this.mCodePointCount) {
            return getComposingText();
        }
        return this.mComposingText.subSequence(0, Character.offsetByCodePoints(this.mComposingText, 0, this.mComposingCursorPos)).toString();
    }

    /**
     * While composing: the first typed letter was uppercase. Otherwise only the sentence-start shift
     * states (1, 5).
     */
    public synchronized boolean shouldCapitalizeFirstLetter() {
        boolean isComposing = isComposing();
        boolean result = isComposing ? this.mFirstLetterUppercase : (this.mShiftState == 1 || this.mShiftState == 5);
        return result;
    }

    /** All composing letters uppercase or, for one letter or less, caps lock (shift state 3 or 7). */
    public synchronized boolean isAllCaps() {
        if (getCodePointCount() <= 1) {
            return this.mShiftState == 7 || this.mShiftState == 3;
        }
        return this.mUpperCaseCount == getCodePointCount();
    }

    /** Shift state 1 (manual) or 5 (auto) at a sentence start. */
    public synchronized boolean isStartOfSentence() {
        boolean z;
        z = true;
        if (this.mShiftState != 5) {
            if (this.mShiftState != 1) {
                z = false;
            }
        }
        return z;
    }

    public synchronized boolean hasMultipleUpperCase() {
        return this.mUpperCaseCount > 1;
    }

    public synchronized boolean hasDigits() {
        return this.mDigitCount > 0;
    }

    /**
     * Shift states: 0 unshifted, 1 manual shift, 3 caps lock, 5 auto shift, 7 auto caps lock. No-op
     * if unchanged.
     * @param shiftState the shift state integer (0 = unshifted, 1 = manual shifted, 3 = caps-lock, 5 = auto-shifted, 7 = auto caps-lock)
     */
    public synchronized void setShiftState(int shiftState) {
        if (this.mShiftState != shiftState) {
            this.mShiftState = shiftState;
            final int nuanceState = getKeyboardShiftState();
            // The op skips a missing engine.
            engineStep(sdk -> sdk.setShiftState(nuanceState));
        }
    }

    private int getKeyboardShiftState() {
        return toEngineShiftState(this.mShiftState);
    }

    /**
     * The engine's shift state (0 none, 1 shift, 2 lock) for a tracker shift state.
     * @param shiftState the tracker shift state
     */
    public static int toEngineShiftState(int shiftState) {
        int nuanceState;
        if (shiftState == 1) {
            nuanceState = 1;  // MANUAL_SHIFTED → SHIFT
        } else if (shiftState == 3) {
            nuanceState = 2;  // SHIFT_LOCKED → SHIFT_LOCK
        } else if (shiftState == 5) {
            nuanceState = 1;  // Auto-caps → SHIFT
        } else if (shiftState == 7) {
            nuanceState = 2;  // Auto variant → SHIFT_LOCK
        } else {
            nuanceState = 0;  // UNSHIFTED → NO_SHIFT
        }
        return nuanceState;
    }

    /**
     * No-op while composing, so the composing shift state is not overwritten.
     * @param shiftState the shift state to apply when not composing
     */
    public synchronized void setShiftStateIfNotComposing(int shiftState) {
        if (!isComposing()) {
            // Delegate so the engine's shift state stays in sync.
            setShiftState(shiftState);
        }
    }

    /**
     * Pass {@code null}/0 to clear; a score of 0 means none.
     * @param autoCorrection the auto-correction word, or {@code null} to clear
     * @param score confidence score (0 = disabled/none)
     */
    public synchronized void setAutoCorrection(String autoCorrection, int score) {
        setAutoCorrection(autoCorrection, score, this.mEditCount);
    }

    /**
     * Stores the auto-correction candidate from a suggestion request made at {@code editCount}
     * (see {@link #getEditCount()}). A request made before the latest edit leaves
     * {@link #hasAutoCorrectionForComposingText()} false.
     * @param autoCorrection the auto-correction word, or {@code null} to clear
     * @param score confidence score (0 = disabled/none)
     */
    public synchronized void setAutoCorrection(String autoCorrection, int score, int editCount) {
        this.mAutoCorrection = autoCorrection;
        this.mAutoCorrectionScore = score;
        this.mAutoCorrectionEditCount = editCount;
    }

    /** Drops the auto-correction candidate because the composing text it was computed for changed. */
    private void clearAutoCorrection() {
        this.mAutoCorrection = null;
        this.mAutoCorrectionScore = 0;
        this.mEditCount++;
    }

    /** Returns the composing-text edit count; record it when making a suggestion request. */
    public synchronized int getEditCount() {
        assertNotReadOnEngineThread("getEditCount");
        return this.mEditCount;
    }

    /**
     * Returns {@code true} if the stored auto-correction came from a request made since the
     * composing text last changed, so {@link #getAutoCorrection()} answers for the current text.
     */
    public synchronized boolean hasAutoCorrectionForComposingText() {
        return this.mAutoCorrectionEditCount == this.mEditCount;
    }

    public synchronized String getAutoCorrection() {
        return this.mAutoCorrection;
    }

    /** 0 means no auto-correction or it is disabled. */
    public synchronized int getAutoCorrectionScore() {
        return this.mAutoCorrectionScore;
    }

    /** The highlighted suggestion, kept as the pending pick for CJK commit-on-separator. */
    public synchronized void setPickedSuggestion(SuggestedWords.SuggestedWordInfo suggestedWordInfo) {
        this.mPickedSuggestion = suggestedWordInfo;
    }

    public synchronized SuggestedWords.SuggestedWordInfo getPickedSuggestion() {
        return this.mPickedSuggestion;
    }

    /** Cached until the composing text changes. */
    public synchronized boolean looksLikeURL() {
        if (this.mCachedUrlCheck == TriState.NOT_INITIALIZED) {
            this.mCachedUrlCheck = StringHelper.looksLikeURL(this.mComposingText) ? TriState.TRUE : TriState.FALSE;
        }
        return this.mCachedUrlCheck == TriState.TRUE;
    }

    /** Cached until the composing text changes. */
    public synchronized boolean looksLikeEmail() {
        if (this.mCachedEmailCheck == TriState.NOT_INITIALIZED) {
            this.mCachedEmailCheck = StringHelper.looksLikeEmail(this.mComposingText) ? TriState.TRUE : TriState.FALSE;
        }
        return this.mCachedEmailCheck == TriState.TRUE;
    }

    public synchronized boolean isGestureInput() {
        assertNotReadOnEngineThread("isGestureInput");
        return this.mIsGestureInput;
    }

    public synchronized void resetRecorrection() {
        this.mRecorrectionState.reset();
    }

    /**
     * @param cursorPosition recorrection start position, in characters before the cursor
     * @param originalWord the typed word before any edits
     */
    public synchronized void setRecorrectionInfo(int cursorPosition, String originalWord) {
        this.mRecorrectionState.setInfo(cursorPosition, originalWord);
    }

    public synchronized void updateRecorrectionSnapshot() {
        this.mRecorrectionState.updateSnapshot(this.mComposingText.toString(), this.mEventIndex);
    }

    /** Whether the recorrected word changed (decides a dictionary learning update). */
    public synchronized boolean hasRecorrectionChanged() {
        return this.mRecorrectionState.hasChanged();
    }

    /** In characters-before-cursor units, or -1 outside recorrection. */
    public synchronized int getRecorrectionCursorPosition() {
        return this.mRecorrectionState.getCursorPosition();
    }

    public synchronized String getRecorrectionOriginalWord() {
        return this.mRecorrectionState.getOriginalWord();
    }

    public synchronized String getRecorrectionCurrentWord() {
        return this.mRecorrectionState.getCurrentWord();
    }

    /**
     * Typically the character count between the recorrection start and the cursor.
     * @param eventIndex the new event index value
     */
    public synchronized void setEventIndex(int eventIndex) {
        this.mEventIndex = eventIndex;
    }

    /**
     * Records the just-committed word for auto-correction revert and learning, then clears the
     * composing state. Revert stays enabled only for DECIDED_WORD, MANUAL_PICK and BATCH_INPUT_WORD.
     * @param separator the word separator that followed the committed word
     */
    public synchronized CommitEventRecord createEventDispatcher(CommitEventRecord.CommitType commitType, CharSequence charSequence, String separator, PrevWordsInfo prevWordsInfo) {
        CommitEventRecord c0713o;
        c0713o = new CommitEventRecord(this.mCoordinateTracker, this.mComposingText.toString(), charSequence, separator, prevWordsInfo, this.mShiftState, commitType, false);
        if (commitType != CommitEventRecord.CommitType.DECIDED_WORD && commitType != CommitEventRecord.CommitType.MANUAL_PICK && commitType != CommitEventRecord.CommitType.BATCH_INPUT_WORD) {
            c0713o.disableRevert();
        }
        clearAll();
        return c0713o;
    }

    /**
     * Record for a picked suggestion. When the pick covers only a prefix of the composing buffer (CJK
     * pinyin/stroke/BPMF, Japanese inline word), that prefix is split off and the rest stays composing.
     * @param previousPickWasPartial {@code true} if learning should be skipped for this commit
     */
    public synchronized CommitEventRecord createEventDispatcherForPickedSuggestion(CommitEventRecord.CommitType commitType, SuggestedWords.SuggestedWordInfo suggestedWordInfo, PrevWordsInfo prevWordsInfo, boolean previousPickWasPartial) {
        String string;
        int iIndexOf;
        CommitEventRecord c0713o;
        if (suggestedWordInfo.nuanceWordInfo != null) {
            string = suggestedWordInfo.nuanceWordInfo.spell;
        } else {
            string = this.mComposingText.toString();
        }
        if (LocaleUtils.isCurrentSubtypeJapanese()) {
            // The split point needs the engine's inline word now: a bounded query; on a timeout
            // the whole composing text is taken as the pick, as with no inline word.
            String inlineWord = queryEngine("", sdk -> sdk.getInlineWord());
            if (!TextUtils.isEmpty(inlineWord)) {
                string = this.mComposingText.subSequence(0, this.mComposingText.length() - inlineWord.length()).toString();
            }
        }
        String str = suggestedWordInfo.word;
        boolean z2 = string.length() < this.mComposingText.length();
        if (z2) {
            Locale locale = Locale.getDefault();
            if (facts().isCurrLocaleChinese() && suggestedWordInfo.nuanceWordInfo != null && !TextUtils.isEmpty(suggestedWordInfo.nuanceWordInfo.spell)) {
                iIndexOf = getChineseSpellLength(string, suggestedWordInfo);
            } else {
                iIndexOf = this.mComposingText.toString().toLowerCase(locale).indexOf(string.toLowerCase(locale)) + string.length();
            }
        } else {
            iIndexOf = -1;
        }
        if (iIndexOf > -1 && this.mCoordinateTracker.getPointerSize() > iIndexOf) {
            TouchPointerCoordTracker c0697mM4701c = this.mCoordinateTracker.copyFirst(iIndexOf);
            String string3 = this.mComposingText.toString();
            if (string3.length() > iIndexOf) {
                string3 = string3.substring(0, iIndexOf);
            }
            CommitEventRecord c0713o2 = new CommitEventRecord(c0697mM4701c, string3, str, "", prevWordsInfo, this.mShiftState, commitType, z2);
            this.mCoordinateTracker.dropFirstSamples(iIndexOf);
            this.mComposingText.delete(0, iIndexOf);
            // Code points, not UTF-16 chars, and refresh the stats for the post-split word.
            this.mCodePointCount = Character.codePointCount(this.mComposingText, 0, this.mComposingText.length());
            this.mComposingCursorPos = this.mCodePointCount;
            recalculateTextStats(false);
            c0713o = c0713o2;
        } else {
            c0713o = new CommitEventRecord(this.mCoordinateTracker, this.mComposingText.toString(), str, "", prevWordsInfo, this.mShiftState, commitType, z2);
            if (commitType != CommitEventRecord.CommitType.DECIDED_WORD && commitType != CommitEventRecord.CommitType.MANUAL_PICK && commitType != CommitEventRecord.CommitType.BATCH_INPUT_WORD) {
                c0713o.disableRevert();
            }
            clearAll();
        }
        return c0713o;
    }

    /**
     * @param pickedSpell the spell prefix the pick consumed
     */
    private synchronized int getChineseSpellLength(String pickedSpell, SuggestedWords.SuggestedWordInfo suggestedWordInfo) {
        EngineFacts engineFacts = facts();
        if (engineFacts.isChineseStrokeMode()) {
            suggestedWordInfo.nuanceWordInfo.spell = pickedSpell + this.mComposingText.charAt(pickedSpell.length());
            return pickedSpell.length() + 1;
        }
        if (engineFacts.isChineseCangjieMode()) {
            return suggestedWordInfo.nuanceWordInfo.spell.length();
        }
        if (engineFacts.isChineseBPMFMode()) {
            // The BPMF case table lives in the engine (both helpers take its lock), so the whole
            // spell is converted in one bounded query; on a timeout it is left as it was.
            final String spell = suggestedWordInfo.nuanceWordInfo.spell;
            String lowered = queryEngine(spell, sdk -> {
                StringBuilder sb = new StringBuilder();
                for (char c : spell.toCharArray()) {
                    if (sdk.isBpmfUpperCaseSymbol(c)) {
                        sb.append(sdk.convertBpmfSymbolToLower(c));
                    } else {
                        sb.append(c);
                    }
                }
                return sb.toString();
            });
            if (lowered == null) {
                lowered = spell;
            }
            suggestedWordInfo.nuanceWordInfo.spell = lowered;
            return lowered.length();
        }
        return this.mComposingText.toString().toLowerCase(Locale.getDefault()).indexOf(pickedSpell.toLowerCase(Locale.getDefault())) + pickedSpell.length();
    }

    public synchronized boolean isPredictionMode() {
        assertNotReadOnEngineThread("isPredictionMode");
        return this.mIsPredictionMode;
    }

    /**
     * Context for next-word prediction requests; {@code null} clears.
     * @param word the word that was just committed, or {@code null} to clear
     */
    public synchronized void setLastCommittedWord(String word) {
        this.mLastCommittedWord = word;
    }

    public synchronized String getLastCommittedWord() {
        return this.mLastCommittedWord;
    }

    /** Whether the last commit was an auto-correction (allows revert on backspace). */
    public synchronized boolean wasAutoCorrected() {
        return this.wasAutoCorrected;
    }

    /**
     * @param wasAutoCorrected {@code true} if the word was auto-corrected, {@code false} if it was user-typed
     */
    public synchronized void setWasAutoCorrected(boolean wasAutoCorrected) {
        this.wasAutoCorrected = wasAutoCorrected;
    }
}

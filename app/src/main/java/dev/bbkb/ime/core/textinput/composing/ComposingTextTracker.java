package dev.bbkb.ime.core.textinput.composing;

import static dev.bbkb.ime.core.shared.StringHelper.*;

import android.text.SpannableStringBuilder;
import android.text.TextUtils;

import dev.bbkb.ime.core.inputmethod.HangulInputProcessor;
import dev.bbkb.ime.core.textinput.composing.CommitEventRecord;
import dev.bbkb.ime.core.inputmethod.RomajiInputProcessor;
import dev.bbkb.ime.core.engine.NuanceSDKManager;
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

    private NuanceSDK mNuanceSDK;

    private SpannableStringBuilder mComposingText = new SpannableStringBuilder();

    private TouchPointerCoordTracker mCoordinateTracker = new TouchPointerCoordTracker(48);

    
    /**
     * TI-39: tri-state for the URL/email caches below. Was the jadx name {@code a}, and public -
     * the last un-renamed public type in this package's de-obfuscation scope. Nothing outside this
     * class ever used it, so it is private now.
     */
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

    /**
     * Constructs a new ComposingTextTracker bound to the given NuanceSDK instance.
     * Resets all composing, prediction, and recorrection state to initial values.
     *
     * @param nuanceSDK the NuanceSDK instance used to process key symbols and shift state
     */
    public ComposingTextTracker(NuanceSDK nuanceSDK) {
        this.mNuanceSDK = nuanceSDK;
        this.mComposingText.clear();
        setAutoCorrection((String) null, 0);
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
     * Returns whether a language-specific input method converter (e.g. Hangul, Vietnamese Telex)
     * is currently installed and active.
     *
     * @return {@code true} if an input method converter is set
     */
    public synchronized boolean hasInputMethodConverter() {
        return this.mInputMethodConverter != null;
    }

    /**
     * Installs the given input method converter. Called directly when a converter instance
     * is available rather than looking it up by name.
     *
     * @param abstractC0679d the converter to install, or {@code null} to remove the current one
     */
    public void setInputMethodConverter(AbstractInputProcessor abstractC0679d) {
        this.mInputMethodConverter = abstractC0679d;
    }

    /**
     * Returns the type of the currently active input method converter.
     *
     * @return the converter type enum value, or {@link InputMethodCallback.ConverterType#NOT_DEFINED} if
     *         no converter is installed
     */
    public synchronized InputMethodCallback.ConverterType getConverterType() {
        return this.mInputMethodConverter != null ? this.mInputMethodConverter.getConverterType() : InputMethodCallback.ConverterType.NOT_DEFINED;
    }

    /**
     * Looks up a converter by its registered name and installs it. Known names are
     * {@code "VietnameseTelexConverter"}, {@code "HangulConverter"}, and
     * {@code "RomajiConverter"}. Passing an empty or null name removes the current converter.
     *
     * @param str the converter name, or empty/null to clear the converter
     * @throws RuntimeException if the name is non-empty but not registered, or instantiation fails
     */
    public synchronized void setConverterByName(String str) {
        if (TextUtils.isEmpty(str)) {
            this.mConverterName = "";
            this.mInputMethodConverter = null;
            return;
        }
        if (!str.equals(this.mConverterName)) {
            this.mConverterName = str;
            // TI-9: Class.newInstance() is deprecated since Java 9 - it propagates checked
            // exceptions without declaring them, which is why this needed three catch clauses,
            // one of them for the NPE thrown by an unknown registry key. An unknown converter is
            // a configuration problem, not a reason to crash the IME on a locale switch.
            Class<? extends InputMethodCallback> converterClass = CONVERTER_REGISTRY.get(this.mConverterName);
            if (converterClass == null) {
                if (BuildConfig.DEBUG) android.util.Log.w("ComposingTextTracker", "Converter " + str + " is requested, but it is not listed in IME");
                this.mInputMethodConverter = null;
                return;
            }
            try {
                this.mInputMethodConverter = converterClass.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException("Unable to instantiate converter: " + str, e);
            }
        }
    }

    /**
     * Resets all composing-session state: clears the composing text buffer, coordinate tracker,
     * auto-correction, shift state, prediction mode, and recorrection tracking. Also resets
     * the NuanceSDK shift state to 0.
     */
    public synchronized void clearAll() {
        this.mComposingText.clear();
        this.mCoordinateTracker.reset();
        if (this.mInputMethodConverter != null) {
            this.mInputMethodConverter.reset();
        }
        setAutoCorrection((String) null, 0);
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
        // Audit EB-5: was NuanceSDKManager.getInstance() — the PRIMARY engine — while every other
        // engine call in this class uses the injected instance. The spell-checker runs in this
        // same process on a tracker wrapping the SECONDARY engine, so each spellCheckWord zeroed
        // the IME's primary shift state from an IPC thread while leaving the secondary's untouched.
        // Same-value suppression in setShiftState then kept it un-repaired until the tracker's own
        // shift value genuinely changed. IME behaviour is unchanged: InputLogic's tracker wraps
        // getInstance() anyway (InputLogic.java:167).
        if (this.mNuanceSDK != null) {
            this.mNuanceSDK.setShiftState(0);
        }
        this.mRecorrectionState.reset();
        recalculateTextStats(false);
    }

    /**
     * Inserts the given code point at the in-composing cursor position, preserving any
     * characters on either side. Advances {@link #mComposingCursorPos} past the inserted
     * code point. Rebuilds {@link #mNuanceSDK} state from scratch (NuanceSDK is append-only,
     * so insert-in-middle requires a clear + re-feed of the full buffer).
     *
     * <p>Use this when the user types a character with the cursor inside an active
     * composing word; the previous behaviour (truncate-then-append) silently dropped
     * every character after the cursor.
     *
     * @param codePoint Unicode code point to insert
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
        // Force a full stats recount: recalculateTextStats(false) only recounts when the
        // buffer shrank, and the append path's incremental counting in
        // updateCoordinatesAndCaps does not run for this insert.
        recalculateTextStats(true);
        // The append path clears any pending auto-correction on every event; the candidate
        // computed for the pre-edit text must not survive the edit either.
        setAutoCorrection((String) null, 0);
        rebuildNuanceFromComposing();
    }

    /**
     * Deletes one code point immediately before the in-composing cursor position,
     * preserving any characters after the cursor. Moves the cursor back by one. Rebuilds
     * {@link #mNuanceSDK} state from scratch (same constraint as
     * {@link #insertCodePointAtCursor(int)}).
     *
     * <p>No-op if the cursor is at the start of composing or composing is empty.
     */
    public final synchronized void deleteCodePointBeforeCursor() {
        if (this.mComposingCursorPos <= 0 || this.mComposingText.length() == 0) {
            return;
        }
        int charIndexAfter = Character.offsetByCodePoints(this.mComposingText, 0, this.mComposingCursorPos);
        // TI-23: the CharSequence overload reads the code point in place; the old
        // mComposingText.toString().codePointBefore(...) copied the whole buffer first.
        int deletedCp = Character.codePointBefore(this.mComposingText, charIndexAfter);
        int charIndexBefore = charIndexAfter - Character.charCount(deletedCp);
        if (BuildConfig.DEBUG) {
        android.util.Log.d("TEXT_EDIT_DEBUG", "deleteCodePointBeforeCursor: composingBefore='" + this.mComposingText
                + "' cursorCodePt=" + this.mComposingCursorPos + " deleting=[" + charIndexBefore + "," + charIndexAfter + ")");
        }
        this.mComposingText.delete(charIndexBefore, charIndexAfter);
        this.mComposingCursorPos -= 1;
        recalculateTextStats(false);
        // Same rationale as insertCodePointAtCursor: the pending auto-correction was
        // computed for the pre-edit text and must not survive the edit.
        setAutoCorrection((String) null, 0);
        rebuildNuanceFromComposing();
    }

    /**
     * Clears the NuanceSDK character buffer and re-feeds the entire composing buffer in
     * order. NuanceSDK is append-only — there is no "insert/delete at position N" entry
     * point — so any mid-buffer edit requires a full clear + re-feed to keep engine state
     * coherent. This is a soft regression for CJK mid-word-edit candidate quality (the
     * engine rebuilds without knowing where the edit happened) but is correct for
     * non-CJK locales.
     */
    private void rebuildNuanceFromComposing() {
        this.mNuanceSDK.clear();
        int len = this.mComposingText.length();
        int i = 0;
        while (i < len) {
            int cp = Character.codePointAt(this.mComposingText, i);
            if (cp <= 0xFFFF) {
                char c = (char) cp;
                // TI-37: see processNuanceAndConverter - one lookup, one box.
                Character strokeKey = STROKE_TO_NUMBER_MAP.get(c);
                Character cangjieKey = CANGJIE_RADICAL_TO_KEY_MAP.get(c);
                if (this.mNuanceSDK.isChineseStrokeMode() && strokeKey != null) {
                    this.mNuanceSDK.processKeyBySymbol(strokeKey.charValue());
                } else if (cangjieKey != null) {
                    this.mNuanceSDK.processKeyBySymbol(cangjieKey.charValue());
                } else {
                    this.mNuanceSDK.processKeyBySymbol(c);
                }
            }
            i += Character.charCount(cp);
        }
    }

    private final synchronized void recalculateTextStats(boolean z) {
        int i = this.mCodePointCount;
        TriState aVar = TriState.NOT_INITIALIZED;
        this.mCachedUrlCheck = aVar;
        this.mCachedEmailCheck = aVar;
        int length = this.mComposingText.length();
        int iCharCount = 0;
        this.mCodePointCount = Character.codePointCount(this.mComposingText, 0, length);
        if (z || i > this.mCodePointCount) {
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

    /**
     * Returns the number of Unicode code points currently in the composing text buffer.
     *
     * @return code point count, 0 if not composing
     */
    public synchronized int getCodePointCount() {
        return this.mCodePointCount;
    }

    /**
     * Returns {@code true} if the composing text contains exactly one Unicode code point.
     * Used to decide whether to show single-character suggestions.
     *
     * @return {@code true} if code point count equals 1
     */
    public synchronized boolean isSingleCodePoint() {
        return getCodePointCount() == 1;
    }

    /**
     * Returns {@code true} if there is any active composing text (i.e., at least one code point).
     *
     * @return {@code true} if composing text is non-empty
     */
    public synchronized boolean isComposing() {
        return getCodePointCount() > 0;
    }

    /**
     * Returns {@code true} if every non-whitespace character in the composing text is a digit.
     * Used to suppress auto-correction in numeric input sequences.
     *
     * @return {@code true} if composing is non-empty and entirely digits
     */
    public synchronized boolean isAllDigits() {
        if (getCodePointCount() > 0) {
            // TI-10: this used to be
            // `mDigitCount == mComposingText.toString().replaceAll("\\s+", "").length()`, which
            // compiled a Pattern and made two full string copies to obtain a length - once per
            // key-repeat tick on the backspace path.
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

    /**
     * Returns the touch pointer coordinate tracker associated with the current composing session.
     * Coordinates are used by the NuanceSDK for gesture and touch-based suggestion scoring.
     *
     * @return the active {@link TouchPointerCoordTracker}
     */
    public synchronized TouchPointerCoordTracker getCoordinateTracker() {
        return this.mCoordinateTracker;
    }

    /**
     * Processes an input event and updates the composing text buffer, using shift state 0.
     * Delegates to {@link #processInputEventWithShift(InputEvent, int)}.
     *
     * @param c0914a the input event to process (key press or backspace)
     */
    public synchronized void processInputEvent(InputEvent c0914a) {
        processInputEventWithShift(c0914a, 0);
    }

    /**
     * Processes an input event and updates the composing text buffer with the given shift state.
     * Handles backspace (removes last code point or delegates to converter), appends typed text,
     * updates coordinates, updates NuanceSDK, and runs any active converter transform.
     *
     * @param c0914a the input event to process
     * @param i      the current keyboard shift state (from {@code InputEventContext.symbolPageOrder})
     */
    public synchronized void processInputEventWithShift(InputEvent c0914a, int i) {
        int iM4319b;
        boolean z;
        if (c0914a == null) {
            return;
        }
        int length = this.mComposingText.length();
        if (-5 == c0914a.mKeyCode) {
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
            iM4319b = appendInputEventText(c0914a);
            z = false;
        }
        recalculateTextStats(false);
        this.mComposingCursorPos = this.mCodePointCount;
        if (this.mCodePointCount == 0) {
            this.mFirstLetterUppercase = false;
        }
        updateCoordinatesAndCaps(c0914a, iM4319b, length);
        setAutoCorrection((String) null, 0);
        if (length == 0) {
            this.mNuanceSDK.clear();
            if (iM4319b == 1) {
                setShiftState(i);
            }
        }
        processNuanceAndConverter(z ? false : true, c0914a, iM4319b);
    }

    private void deleteLastCodePoint(int i) {
        // TI-23: no full-buffer String copy just to read one code point.
        int iCodePointBefore = Character.codePointBefore(this.mComposingText, i);
        if (i == 1 && !HangulInputProcessor.isHangulSyllable(iCodePointBefore)) {
            this.mComposingText.clear();
            this.mCoordinateTracker.truncate(0);
            this.mNuanceSDK.clear();
        } else {
            this.mComposingText.delete(i - Character.charCount(iCodePointBefore), i);
            this.mNuanceSDK.clearOneSymbol();
            TouchPointerCoordTracker c0697m = this.mCoordinateTracker;
            c0697m.truncate(c0697m.getPointerSize() - 1);
        }
    }

    private boolean processConverterBackspace() {
        InputMethodCallback.ConversionResult aVarMo4304b = this.mInputMethodConverter.revertLastConversion(this.mComposingText, this.mCoordinateTracker, this.mNuanceSDK);
        if (aVarMo4304b == null) {
            return false;
        }
        this.mComposingText = new SpannableStringBuilder(aVarMo4304b.text);
        if (!this.mIsPredictionMode) {
            this.mCoordinateTracker = aVarMo4304b.coordTracker;
        }
        return true;
    }

    private int appendInputEventText(InputEvent c0914a) {
        CharSequence charSequenceM5933n = c0914a.getOutputText();
        int iCodePointCount = Character.codePointCount(charSequenceM5933n, 0, charSequenceM5933n.length());
        if (!TextUtils.isEmpty(charSequenceM5933n)) {
            this.mComposingText.append(charSequenceM5933n);
        }
        return iCodePointCount;
    }

    private void updateCoordinatesAndCaps(InputEvent c0914a, int i, int i2) {
        boolean z;
        if (i > 0) {
            boolean z2 = false;
            if (this.mIsPredictionMode) {
                z = false;
            } else {
                int i3 = c0914a.mX;
                int i4 = c0914a.mY;
                boolean zM5931l = c0914a.isFromSwitchedKeyboard();
                int iCharCount = i2;
                z = false;
                int i5 = 0;
                while (i5 < i && iCharCount < this.mComposingText.length()) {
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

    private void processNuanceAndConverter(boolean z, InputEvent c0914a, int i) {
        InputMethodCallback interfaceC0673c;
        InputMethodCallback interfaceC0673c2;
        CharSequence charSequenceM5933n = c0914a.getOutputText();
        if (i > 0) {
            if (getConverterType() == InputMethodCallback.ConverterType.HANGUL && (interfaceC0673c2 = this.mInputMethodConverter) != null) {
                interfaceC0673c2.processSymbols(charSequenceM5933n, this.mNuanceSDK);
            } else if (getConverterType() == InputMethodCallback.ConverterType.VIETNAMESE_TELEX && (interfaceC0673c = this.mInputMethodConverter) != null) {
                interfaceC0673c.processSymbols(charSequenceM5933n, this.mNuanceSDK);
            } else {
                int iM5599a = ScriptUtils.getScriptFromLocale(this.mNuanceSDK.getPrimaryLanguage());
                for (int i2 = 0; i2 < charSequenceM5933n.length(); i2++) {
                    char cCharAt = charSequenceM5933n.charAt(i2);
                    // TI-37: single map lookup each instead of containsKey()+get(), and one
                    // Character box per lookup instead of two.
                    Character strokeKey = STROKE_TO_NUMBER_MAP.get(cCharAt);
                    Character cangjieKey = CANGJIE_RADICAL_TO_KEY_MAP.get(cCharAt);
                    if (this.mNuanceSDK.isChineseStrokeMode() && strokeKey != null) {
                        this.mNuanceSDK.processKeyBySymbol(strokeKey.charValue());
                    } else if (cangjieKey != null) {
                        this.mNuanceSDK.processKeyBySymbol(cangjieKey.charValue());
                    } else if (iM5599a == 14 && cCharAt > 127 && cCharAt != 305) {
                        this.mNuanceSDK.addExplicitSymb(cCharAt);
                    } else {
                        this.mNuanceSDK.processKeyBySymbol(cCharAt);
                    }
                }
            }
        }
        updateRecorrectionSnapshot();
        InputMethodCallback interfaceC0673c3 = this.mInputMethodConverter;
        if (interfaceC0673c3 != null && z) {
            this.mComposingText = new SpannableStringBuilder(interfaceC0673c3.convert(this.mComposingText, this.mCoordinateTracker, this.mNuanceSDK).text);
            recalculateTextStats(true);
            this.mComposingCursorPos = this.mCodePointCount;
        }
    }

    /**
     * Sets the cursor position within the composing text, in code point units.
     * A position equal to the code point count means the cursor is at the end.
     *
     * @param i the new in-composing cursor position (0 = before first character)
     */
    public synchronized void setComposingCursorPosition(int i) {
        this.mComposingCursorPos = i;
    }

    /**
     * Returns {@code true} if the in-composing cursor has been moved away from the end
     * of the composing text (i.e., the user navigated backwards within the composing span).
     *
     * @return {@code true} if cursor position differs from code point count
     */
    public synchronized boolean isCursorMoved() {
        return this.mComposingCursorPos != this.mCodePointCount;
    }

    public synchronized int getComposingCursorPos() {
        return this.mComposingCursorPos;
    }

    /**
     * Moves the in-composing cursor by the given number of code points.
     * Positive values move forward; negative values move backward.
     * Returns {@code false} if the movement cannot be satisfied exactly (e.g., hits a boundary
     * or a supplementary character boundary misalignment).
     *
     * <p>A successful move that lands on position 0 (immediately before the first character)
     * returns {@code true}. The original implementation returned {@code false} there, which
     * made {@code onUpdateSelection} treat the move as a failure and commit the word —
     * inconsistent with every other in-word position, which keeps the word composing.
     * Deliberate divergence from the original; see docs/archived/2026-06_fable-audits-and-gesture-rebuild/2026-06_composition-pipeline_audit.md F4.
     *
     * @param i the number of code points to move (positive = right, negative = left)
     * @return {@code true} if the cursor moved by exactly {@code i} code points
     */
    public synchronized boolean moveCursorByCharCount(int i) {
        int i2;
        int iCharCount;
        if (this.mInputMethodConverter != null) {
            this.mInputMethodConverter.reset();
        }
        int i3 = this.mComposingCursorPos;
        int[] iArrM5474a = toCodePointArray(this.mComposingText);
        if (i >= 0) {
            i2 = i3;
            iCharCount = 0;
            while (iCharCount < i && i2 < this.mCodePointCount) {
                iCharCount += Character.charCount(iArrM5474a[i2]);
                i2++;
            }
        } else {
            if (i3 > this.mCodePointCount) {
                return false;
            }
            i2 = i3;
            iCharCount = 0;
            while (iCharCount > i && i2 > 0) {
                i2--;
                iCharCount -= Character.charCount(iArrM5474a[i2]);
            }
        }
        if (iCharCount != i) {
            if (BuildConfig.DEBUG) {
            android.util.Log.d("TEXT_EDIT_DEBUG", "moveCursorByCharCount: FAILED charDelta=" + i
                    + " achievedCharCount=" + iCharCount + " newCodePtPos=" + i2
                    + " composing='" + this.mComposingText + "'");
            }
            return false;
        }
        this.mComposingCursorPos = i2;
        if (BuildConfig.DEBUG) {
        android.util.Log.d("TEXT_EDIT_DEBUG", "moveCursorByCharCount: charDelta=" + i
                + " newCodePtPos=" + i2 + " mCodePointCount=" + this.mCodePointCount
                + " composing='" + this.mComposingText + "'");
        }
        return true;
    }

    /**
     * Switches to prediction mode. In this mode the composing text represents a just-committed
     * word that is being held open for bigram-based next-word prediction, rather than live
     * character composition. Clears gesture and auto-correct flags.
     */
    public synchronized void enterPredictionMode() {
        this.mIsPredictionMode = true;
        this.mIsGestureInput = false;
        this.wasAutoCorrected = false;
    }

    /**
     * Exits prediction mode and clears gesture-input and auto-correct flags.
     * Does not clear the composing text buffer itself.
     */
    public synchronized void resetPredictionState() {
        this.mIsGestureInput = false;
        this.mIsPredictionMode = false;
        this.mLastCommittedWord = null;
        this.wasAutoCorrected = false;
    }

    /**
     * Rebuilds the composing text buffer from arrays of code points, touch coordinates, and
     * upper-case override flags. Used when re-opening a committed word for recorrection.
     * Clears all existing state first, then replays the code points as touch events.
     *
     * @param iArr  array of Unicode code points
     * @param iArr2 flattened coordinate array (x,y pairs per pointer) from
     *              {@link dev.bbkb.ime.core.shared.CoordinateUtils}
     * @param iArr3 per-code-point upper-case override flags (non-zero = force upper case)
     */
    public synchronized void setComposingFromCodePoints(int[] iArr, int[] iArr2, int[] iArr3) {
        clearAll();
        int length = iArr.length;
        int length2 = iArr3.length;
        int i = 0;
        while (true) {
            boolean z = true;
            if (i >= length) {
                break;
            }
            if (i >= length2 || iArr3[i] == 0) {
                z = false;
            }
            processInputEvent(InputEvent.createTouchEvent(iArr[i], CoordinateUtils.xFromArray(iArr2, i), CoordinateUtils.yFromArray(iArr2, i), z));
            i++;
        }
        // NOTE: this method rebuilds composing text from code points + coordinates and is called
        // ONLY by non-gesture paths — recorrection (RecorrectionController.performRecorrection,
        // which runs on ordinary typing via UIUpdateHandler), the spell checker, and accent
        // substitution. The real swipe path never routes through here; it goes through the engine's
        // trace recogniser and commits its result separately. This site used to set
        // mIsGestureInput = true unconditionally, which mislabelled every recorrection rebuild as a
        // swipe. Because isGestureInput() gates auto-correction (SuggestionEngine cond_notGesture),
        // that silently disabled auto-correct for TYPED words after the first recorrection pass.
        // mIsGestureInput is only ever meaningfully true on the batch path; this method must not
        // touch it. (Confirmed 2026-08-12: notGesture=false on every keystroke traced back here.)
    }

    /**
     * Rebuilds the composing text buffer from arrays of code points and touch coordinates,
     * with no upper-case override flags. Delegates to
     * {@link #setComposingFromCodePoints(int[], int[], int[])}.
     *
     * @param iArr  array of Unicode code points
     * @param iArr2 flattened coordinate array (x,y pairs per pointer)
     */
    public synchronized void setComposingFromCodePoints(int[] iArr, int[] iArr2) {
        setComposingFromCodePoints(iArr, iArr2, new int[0]);
    }

    /**
     * Returns the full current composing text as a plain {@link String}.
     *
     * @return the composing text, or an empty string if not composing
     */
    public synchronized String getComposingText() {
        return this.mComposingText.toString();
    }

    /**
     * Returns the portion of the composing text that precedes the in-composing cursor.
     * When the cursor is at the end, this is identical to {@link #getComposingText()}.
     *
     * @return composing text up to the in-composing cursor position
     */
    public synchronized String getComposingTextBeforeCursor() {
        if (this.mComposingCursorPos >= this.mCodePointCount) {
            return getComposingText();
        }
        return this.mComposingText.subSequence(0, Character.offsetByCodePoints(this.mComposingText, 0, this.mComposingCursorPos)).toString();
    }

    /**
     * Returns {@code true} if the first character of the next committed word should be
     * capitalized. During composition, this reflects whether the first typed letter was
     * uppercase. During prediction, this is true only for sentence-start shift states (1 or 5).
     *
     * @return {@code true} if the word should start with a capital letter
     */
    public synchronized boolean shouldCapitalizeFirstLetter() {
        // FIX: For predictions (not composing), only return true for start-of-sentence states (1 or 5)
        // Previously returned true for ANY non-zero state, causing incorrect capitalization after
        // committing a suggestion mid-sentence
        boolean isComposing = isComposing();
        boolean result = isComposing ? this.mFirstLetterUppercase : (this.mShiftState == 1 || this.mShiftState == 5);
        return result;
    }

    /**
     * Returns {@code true} if all composing characters are uppercase, or if the shift state
     * indicates caps-lock (states 3 or 7). Used to decide whether to commit words in all-caps.
     *
     * @return {@code true} if the word is in all-caps
     */
    public synchronized boolean isAllCaps() {
        if (getCodePointCount() <= 1) {
            return this.mShiftState == 7 || this.mShiftState == 3;
        }
        return this.mUpperCaseCount == getCodePointCount();
    }

    /**
     * Returns {@code true} if the current shift state indicates the start of a sentence
     * (auto-caps after sentence separator, or manual shift at sentence start).
     * Corresponds to shift states 1 (MANUAL_SHIFTED) and 5 (AUTO_SHIFTED).
     *
     * @return {@code true} if the cursor is at a sentence-start position
     */
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

    /**
     * Returns {@code true} if more than one uppercase letter has been typed in the current
     * composing session. Used to detect intentional all-caps words (e.g. acronyms).
     *
     * @return {@code true} if upper-case count exceeds 1
     */
    public synchronized boolean hasMultipleUpperCase() {
        return this.mUpperCaseCount > 1;
    }

    /**
     * Returns {@code true} if at least one digit character has been typed in the current
     * composing session. Used to suppress certain auto-corrections for mixed words.
     *
     * @return {@code true} if digit count is greater than 0
     */
    public synchronized boolean hasDigits() {
        return this.mDigitCount > 0;
    }

    /**
     * Sets the keyboard shift state for the current composing session and propagates it
     * to the NuanceSDK. Has no effect if the value is unchanged.
     *
     * @param i the shift state integer (0 = unshifted, 1 = manual shifted, 3 = caps-lock,
     *          5 = auto-shifted, 7 = auto caps-lock)
     */
    public synchronized void setShiftState(int i) {
        if (this.mShiftState != i) {
            this.mShiftState = i;
            int nuanceState = getKeyboardShiftState();
            // FIX-D2: null when the engine failed to load (as clearAll already allows). Every
            // suggestion request starts here via setShiftStateIfNotComposing.
            if (this.mNuanceSDK != null) {
                this.mNuanceSDK.setShiftState(nuanceState);
            }
        }
    }

    private int getKeyboardShiftState() {
        int i = this.mShiftState;
        int nuanceState;
        if (i == 1) {
            nuanceState = 1;  // MANUAL_SHIFTED → SHIFT
        } else if (i == 3) {
            nuanceState = 2;  // SHIFT_LOCKED → SHIFT_LOCK
        } else if (i == 5) {
            nuanceState = 1;  // Auto-caps → SHIFT
        } else if (i == 7) {
            nuanceState = 2;  // Auto variant → SHIFT_LOCK
        } else {
            nuanceState = 0;  // UNSHIFTED → NO_SHIFT
        }
        return nuanceState;
    }

    /**
     * Sets the keyboard shift state only if there is currently no active composing text.
     * A no-op when composition is in progress, to avoid overwriting the composing shift state.
     *
     * @param i the shift state to apply when not composing
     */
    public synchronized void setShiftStateIfNotComposing(int i) {
        if (!isComposing()) {
            // Delegate so the NuanceSDK shift state stays in sync with the tracker's;
            // previously this set mShiftState directly and the engine kept the old state
            // until the next setShiftState call.
            setShiftState(i);
        }
    }

    /**
     * Stores the current auto-correction candidate and its confidence score.
     * Pass {@code null}/0 to clear any pending auto-correction.
     *
     * @param str the auto-correction word, or {@code null} to clear
     * @param i   confidence score (0 = disabled/none)
     */
    public synchronized void setAutoCorrection(String str, int i) {
        this.mAutoCorrection = str;
        this.mAutoCorrectionScore = i;
    }

    /**
     * Returns the current auto-correction candidate word, or {@code null} if none is set.
     *
     * @return auto-correction candidate, or {@code null}
     */
    public synchronized String getAutoCorrection() {
        return this.mAutoCorrection;
    }

    /**
     * Returns the confidence score of the current auto-correction candidate.
     * A score of 0 means no auto-correction is available or it is disabled.
     *
     * @return auto-correction confidence score
     */
    public synchronized int getAutoCorrectionScore() {
        return this.mAutoCorrectionScore;
    }

    /**
     * Stores the suggestion word that is currently highlighted in the suggestion strip
     * as the pending pick for CJK commit-on-separator logic.
     *
     * @param suggestedWordInfoVar the highlighted suggestion, or {@code null} to clear
     */
    public synchronized void setPickedSuggestion(SuggestedWords.SuggestedWordInfo suggestedWordInfoVar) {
        this.mPickedSuggestion = suggestedWordInfoVar;
    }

    /**
     * Returns the suggestion word that was last set via {@link #setPickedSuggestion}, or
     * {@code null} if none is pending.
     *
     * @return the pending picked suggestion, or {@code null}
     */
    public synchronized SuggestedWords.SuggestedWordInfo getPickedSuggestion() {
        return this.mPickedSuggestion;
    }

    /**
     * Returns {@code true} if the composing text appears to be part of a URL.
     * The result is cached; it is recomputed only after the composing text changes.
     *
     * @return {@code true} if composing text looks like a URL fragment
     */
    public synchronized boolean looksLikeURL() {
        if (this.mCachedUrlCheck == TriState.NOT_INITIALIZED) {
            this.mCachedUrlCheck = StringHelper.looksLikeURL(this.mComposingText) ? TriState.TRUE : TriState.FALSE;
        }
        return this.mCachedUrlCheck == TriState.TRUE;
    }

    /**
     * Returns {@code true} if the composing text appears to be part of an email address.
     * The result is cached; it is recomputed only after the composing text changes.
     *
     * @return {@code true} if composing text looks like an email address fragment
     */
    public synchronized boolean looksLikeEmail() {
        if (this.mCachedEmailCheck == TriState.NOT_INITIALIZED) {
            this.mCachedEmailCheck = StringHelper.looksLikeEmail(this.mComposingText) ? TriState.TRUE : TriState.FALSE;
        }
        return this.mCachedEmailCheck == TriState.TRUE;
    }

    /**
     * Returns {@code true} if the current composing text was built from a gesture (swipe)
     * path rather than individual key presses.
     *
     * @return {@code true} if gesture input is active
     */
    public synchronized boolean isGestureInput() {
        return this.mIsGestureInput;
    }

    /**
     * Clears all recorrection tracking state: cursor position, original word, and current-word
     * snapshot. Called when a recorrection session ends or is abandoned.
     */
    public synchronized void resetRecorrection() {
        this.mRecorrectionState.reset();
    }

    /**
     * Records the start cursor position and original word for the current recorrection session.
     * Called when the user taps on a previously committed word to re-edit it.
     *
     * @param i   cursor position at which recorrection begins (in characters before cursor)
     * @param str the original word text before any edits
     */
    public synchronized void setRecorrectionInfo(int i, String str) {
        this.mRecorrectionState.setInfo(i, str);
    }

    /**
     * Snapshots the current composing text into the recorrection tracker when the event index
     * matches the recorrection cursor position. Called after each key event during recorrection
     * to keep the current-word snapshot up to date.
     */
    public synchronized void updateRecorrectionSnapshot() {
        this.mRecorrectionState.updateSnapshot(this.mComposingText.toString(), this.mEventIndex);
    }

    /**
     * Returns {@code true} if the user has modified the recorrected word from its original text.
     * Used to decide whether to submit a dictionary learning update.
     *
     * @return {@code true} if the current-word snapshot differs from the original word
     */
    public synchronized boolean hasRecorrectionChanged() {
        return this.mRecorrectionState.hasChanged();
    }

    /**
     * Returns the cursor start position that was recorded when the current recorrection session
     * began (in characters-before-cursor units).
     *
     * @return recorrection start position, or -1 if not in recorrection
     */
    public synchronized int getRecorrectionCursorPosition() {
        return this.mRecorrectionState.getCursorPosition();
    }

    /**
     * Returns the original word text that was present when recorrection began, before any
     * user edits in the current session.
     *
     * @return original recorrection word, or {@code null} if not in recorrection
     */
    public synchronized String getRecorrectionOriginalWord() {
        return this.mRecorrectionState.getOriginalWord();
    }

    /**
     * Returns the most recent snapshot of the composing text during the current recorrection
     * session, updated by {@link #updateRecorrectionSnapshot()}.
     *
     * @return current recorrection word snapshot, or {@code null} if not in recorrection
     */
    public synchronized String getRecorrectionCurrentWord() {
        return this.mRecorrectionState.getCurrentWord();
    }

    /**
     * Sets the event index counter used to synchronize recorrection snapshots with cursor
     * position. Typically set to the number of characters between the recorrection start
     * and the current cursor position.
     *
     * @param i the new event index value
     */
    public synchronized void setEventIndex(int i) {
        this.mEventIndex = i;
    }

    /**
     * Creates an {@link CommitEventRecord} that captures the just-committed word's metadata
     * (typed text, committed text, separator, context, coordinates, shift state, commit type)
     * for auto-correction revert and learning, then clears the composing state.
     * <p>
     * The dispatcher's learning flag is disabled for DECIDED_WORD, MANUAL_PICK, and
     * BATCH_INPUT_WORD commit types (since those already represent final decisions).
     *
     * @param commitTypeVar         the commit type enum value
     * @param charSequence the committed text (the final word as displayed)
     * @param str          the word separator that followed the committed word
     * @param prevWordsInfo context words preceding this word for n-gram learning
     * @return a populated {@link CommitEventRecord} for this commit event
     */
    public synchronized CommitEventRecord createEventDispatcher(CommitEventRecord.CommitType commitTypeVar, CharSequence charSequence, String str, PrevWordsInfo prevWordsInfo) {
        CommitEventRecord c0713o;
        c0713o = new CommitEventRecord(this.mCoordinateTracker, this.mComposingText.toString(), charSequence, str, prevWordsInfo, this.mShiftState, commitTypeVar, false);
        if (commitTypeVar != CommitEventRecord.CommitType.DECIDED_WORD && commitTypeVar != CommitEventRecord.CommitType.MANUAL_PICK && commitTypeVar != CommitEventRecord.CommitType.BATCH_INPUT_WORD) {
            c0713o.disableRevert();
        }
        clearAll();
        return c0713o;
    }

    /**
     * Creates an {@link CommitEventRecord} for a picked suggestion word. Handles partial-word
     * splits for CJK pinyin/stroke/BPMF and prediction scenarios where the typed prefix is
     * shorter than the composing buffer. Updates the input connection wrapper with the typed
     * and picked word strings for learning purposes.
     *
     * @param commitTypeVar               the commit type (typically DECIDED_WORD or MANUAL_PICK)
     * @param suggestedWordInfoVar2 the suggestion word info that was picked
     * @param prevWordsInfo      context words preceding this word
     * @param c0841t             the input connection wrapper used to record the typed/picked pair
     * @param z                  {@code true} if learning should be skipped for this commit
     * @return a populated {@link CommitEventRecord} for this pick event
     */
    public synchronized CommitEventRecord createEventDispatcherForPickedSuggestion(CommitEventRecord.CommitType commitTypeVar, SuggestedWords.SuggestedWordInfo suggestedWordInfoVar2, PrevWordsInfo prevWordsInfo, boolean z) {
        String string;
        int iIndexOf;
        CommitEventRecord c0713o;
        if (suggestedWordInfoVar2.nuanceWordInfo != null) {
            string = suggestedWordInfoVar2.nuanceWordInfo.spell;
        } else {
            string = this.mComposingText.toString();
        }
        if (LocaleUtils.isCurrentSubtypeJapanese()) {
            String inlineWord = this.mNuanceSDK.getInlineWord();
            if (!TextUtils.isEmpty(inlineWord)) {
                string = this.mComposingText.subSequence(0, this.mComposingText.length() - inlineWord.length()).toString();
            }
        }
        String str = suggestedWordInfoVar2.word;
        boolean z2 = string.length() < this.mComposingText.length();
        if (z2) {
            Locale locale = Locale.getDefault();
            if (this.mNuanceSDK.isCurrLocaleChinese() && suggestedWordInfoVar2.nuanceWordInfo != null && !TextUtils.isEmpty(suggestedWordInfoVar2.nuanceWordInfo.spell)) {
                iIndexOf = getChineseSpellLength(string, suggestedWordInfoVar2);
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
            CommitEventRecord c0713o2 = new CommitEventRecord(c0697mM4701c, string3, str, "", prevWordsInfo, this.mShiftState, commitTypeVar, z2);
            this.mCoordinateTracker.dropFirstSamples(iIndexOf);
            this.mComposingText.delete(0, iIndexOf);
            // Audit SS-4: this branch used mComposingText.length() — a UTF-16 CHAR count — where
            // the canonical form elsewhere is Character.codePointCount, and it skipped the stats
            // refresh, leaving upper/digit counts and the URL/email caches describing the
            // pre-split word. Wrong for any supplementary-plane text (emoji, rare CJK).
            this.mCodePointCount = Character.codePointCount(this.mComposingText, 0, this.mComposingText.length());
            this.mComposingCursorPos = this.mCodePointCount;
            recalculateTextStats(false);
            c0713o = c0713o2;
        } else {
            c0713o = new CommitEventRecord(this.mCoordinateTracker, this.mComposingText.toString(), str, "", prevWordsInfo, this.mShiftState, commitTypeVar, z2);
            if (commitTypeVar != CommitEventRecord.CommitType.DECIDED_WORD && commitTypeVar != CommitEventRecord.CommitType.MANUAL_PICK && commitTypeVar != CommitEventRecord.CommitType.BATCH_INPUT_WORD) {
                c0713o.disableRevert();
            }
            clearAll();
        }
        return c0713o;
    }

    private synchronized int getChineseSpellLength(String str, SuggestedWords.SuggestedWordInfo suggestedWordInfoVar) {
        if (this.mNuanceSDK.isChineseStrokeMode()) {
            suggestedWordInfoVar.nuanceWordInfo.spell = str + this.mComposingText.charAt(str.length());
            return str.length() + 1;
        }
        if (this.mNuanceSDK.isChineseCangjieMode()) {
            return suggestedWordInfoVar.nuanceWordInfo.spell.length();
        }
        if (this.mNuanceSDK.isChineseBPMFMode()) {
            StringBuilder sb = new StringBuilder();
            for (char c : suggestedWordInfoVar.nuanceWordInfo.spell.toCharArray()) {
                if (this.mNuanceSDK.isBpmfUpperCaseSymbol(c)) {
                    sb.append(this.mNuanceSDK.convertBpmfSymbolToLower(c));
                } else {
                    sb.append(c);
                }
            }
            suggestedWordInfoVar.nuanceWordInfo.spell = sb.toString();
            return sb.length();
        }
        return this.mComposingText.toString().toLowerCase(Locale.getDefault()).indexOf(str.toLowerCase(Locale.getDefault())) + str.length();
    }

    /**
     * Returns {@code true} if prediction mode is currently active (entered via
     * {@link #enterPredictionMode()}).
     *
     * @return {@code true} if in prediction mode
     */
    public synchronized boolean isPredictionMode() {
        return this.mIsPredictionMode;
    }

    /**
     * Records the most recently committed word. Used as context for downstream bigram
     * prediction requests.
     *
     * @param str the word that was just committed, or {@code null} to clear
     */
    public synchronized void setLastCommittedWord(String str) {
        this.mLastCommittedWord = str;
    }

    /**
     * Returns the most recently committed word as recorded by {@link #setLastCommittedWord},
     * or {@code null} if none has been set this session.
     *
     * @return last committed word, or {@code null}
     */
    public synchronized String getLastCommittedWord() {
        return this.mLastCommittedWord;
    }

    /**
     * Returns {@code true} if the last committed word was the result of an auto-correction
     * rather than exactly what the user typed. Used to decide whether to allow revert-on-backspace.
     *
     * @return {@code true} if the last commit was an auto-correction
     */
    public synchronized boolean wasAutoCorrected() {
        return this.wasAutoCorrected;
    }

    /**
     * Sets the flag indicating whether the last committed word was auto-corrected.
     *
     * @param z {@code true} if the word was auto-corrected, {@code false} if it was user-typed
     */
    public synchronized void setWasAutoCorrected(boolean z) {
        this.wasAutoCorrected = z;
    }
}

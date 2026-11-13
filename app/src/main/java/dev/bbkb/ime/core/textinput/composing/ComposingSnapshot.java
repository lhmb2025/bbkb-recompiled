package dev.bbkb.ime.core.textinput.composing;

import dev.bbkb.ime.core.inputmethod.InputMethodCallback;
import dev.bbkb.ime.core.shared.StringHelper;

/**
 * The composing state a suggestion request was made for, taken on the main thread when the
 * request is sent ({@code InputLogic.captureRequestContext}) and read on the engine thread when it
 * runs. The engine thread must never read the IME's synchronized {@link ComposingTextTracker}: the
 * main thread may hold it while waiting on an engine query, which would deadlock.
 *
 * <p>The engine buffer can differ by the time the request runs (the typed-word guard's lookup
 * overwrites it), so the bridge still replays this text before it builds. Immutable.
 */
public final class ComposingSnapshot {

    /** No composing text, unshifted; what a request with no tracker sees. */
    public static final ComposingSnapshot EMPTY = new ComposingSnapshot("", false, false, false,
            false, false, false, false, false, false,
            InputMethodCallback.ConverterType.NOT_DEFINED, null, 0, 0);

    public final String text;
    public final boolean isComposing;
    public final boolean isPredictionMode;
    public final boolean isGestureInput;
    public final boolean hasDigits;
    public final boolean hasMultipleUpperCase;
    public final boolean isAllCaps;
    public final boolean shouldCapitalizeFirstLetter;
    public final boolean isStartOfSentence;
    public final boolean hasInputMethodConverter;
    public final InputMethodCallback.ConverterType converterType;
    public final String lastCommittedWord;
    /** The tracker's shift state (0, 1, 3, 5, 7), as {@link ComposingTextTracker#setShiftState} takes it. */
    public final int shiftState;
    /** {@link ComposingTextTracker#getEditCount()} when the snapshot was taken. */
    public final int editCount;

    private Boolean mLooksLikeUrl;
    private Boolean mLooksLikeEmail;

    ComposingSnapshot(String text, boolean isComposing, boolean isPredictionMode,
            boolean isGestureInput, boolean hasDigits, boolean hasMultipleUpperCase,
            boolean isAllCaps, boolean shouldCapitalizeFirstLetter, boolean isStartOfSentence,
            boolean hasInputMethodConverter, InputMethodCallback.ConverterType converterType,
            String lastCommittedWord, int shiftState, int editCount) {
        this.text = text != null ? text : "";
        this.isComposing = isComposing;
        this.isPredictionMode = isPredictionMode;
        this.isGestureInput = isGestureInput;
        this.hasDigits = hasDigits;
        this.hasMultipleUpperCase = hasMultipleUpperCase;
        this.isAllCaps = isAllCaps;
        this.shouldCapitalizeFirstLetter = shouldCapitalizeFirstLetter;
        this.isStartOfSentence = isStartOfSentence;
        this.hasInputMethodConverter = hasInputMethodConverter;
        this.converterType = converterType != null ? converterType : InputMethodCallback.ConverterType.NOT_DEFINED;
        this.lastCommittedWord = lastCommittedWord;
        this.shiftState = shiftState;
        this.editCount = editCount;
    }

    /**
     * A snapshot of {@code tracker} read in one hold of its monitor (spell checker and request
     * fallback; the IME's requests take theirs on the main thread). Null gives {@link #EMPTY}.
     */
    public static ComposingSnapshot of(ComposingTextTracker tracker) {
        if (tracker == null) {
            return EMPTY;
        }
        // One consistent state through the public getters (re-entrant), which a test can stub.
        synchronized (tracker) {
            return new ComposingSnapshot(tracker.getComposingText(), tracker.isComposing(),
                    tracker.isPredictionMode(), tracker.isGestureInput(), tracker.hasDigits(),
                    tracker.hasMultipleUpperCase(), tracker.isAllCaps(),
                    tracker.shouldCapitalizeFirstLetter(), tracker.isStartOfSentence(),
                    tracker.hasInputMethodConverter(), tracker.getConverterType(),
                    tracker.getLastCommittedWord(), tracker.getShiftState(),
                    tracker.getEditCount());
        }
    }

    /** {@link ComposingTextTracker#looksLikeURL()} for {@link #text}, computed on first use. */
    public boolean looksLikeURL() {
        Boolean cached = mLooksLikeUrl;
        if (cached == null) {
            cached = StringHelper.looksLikeURL(this.text);
            mLooksLikeUrl = cached;
        }
        return cached;
    }

    /** {@link ComposingTextTracker#looksLikeEmail()} for {@link #text}, computed on first use. */
    public boolean looksLikeEmail() {
        Boolean cached = mLooksLikeEmail;
        if (cached == null) {
            cached = StringHelper.looksLikeEmail(this.text);
            mLooksLikeEmail = cached;
        }
        return cached;
    }

    /** The engine's shift state for {@link #shiftState} (what the tracker feeds it). */
    public int engineShiftState() {
        return ComposingTextTracker.toEngineShiftState(this.shiftState);
    }

    @Override
    public String toString() {
        return "ComposingSnapshot{text='" + text + "', composing=" + isComposing
                + ", prediction=" + isPredictionMode + ", shift=" + shiftState
                + ", edit=" + editCount + "}";
    }
}

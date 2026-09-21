package dev.bbkb.ime.core.inputmethod;

import dev.bbkb.ime.core.textinput.composing.TouchPointerCoordTracker;
import com.blackberry.nuanceshim.NuanceSDK;

import java.util.ArrayList;



public abstract class AbstractInputProcessor implements InputMethodCallback {

    public boolean historyEnabled = false;

    protected ArrayList<CharSequence> textHistory = new ArrayList<>();

    protected ArrayList<TouchPointerCoordTracker> trackerHistory = new ArrayList<>();

    protected abstract CharSequence convertText(CharSequence charSequence, NuanceSDK nuanceSDK);

    /**
     * Convert {@code source} through {@link #convertText} and re-align the per-character touch
     * coordinates onto the converted text.
     *
     * <p>This is the coordinate-realignment step that keeps {@link TouchPointerCoordTracker} in
     * sync with converted text for Vietnamese / Hangul / Romaji — i.e. it produces the gesture
     * coordinates the decoder later consumes. The two strings are walked in lock-step by code
     * point: while they agree, each output code point inherits the source's coordinates; at the
     * first divergence the result's {@code codePointCount}/{@code charCount} are truncated to the
     * common prefix and every code point from there on is emitted with no coordinates.
     *
     * <p>Audit DK-39: this was raw jadx output — ten pre-declared loop temporaries named
     * {@code i}..{@code i8} plus {@code i9}/{@code i10}/{@code i11}/{@code z2}, and array locals
     * carrying the original obfuscated method suffixes. Renamed and scoped; the control flow is
     * unchanged.
     */
    @Override // dev.bbkb.ime.core.inputmethod.InputMethodCallback
    public InputMethodCallback.ConversionResult convert(CharSequence charSequence, TouchPointerCoordTracker sourceTracker, NuanceSDK nuanceSDK) {
        InputMethodCallback.ConversionResult result = new InputMethodCallback.ConversionResult();
        result.text = convertText(charSequence, nuanceSDK);
        String source = charSequence.toString();
        String converted = result.text.toString();
        int convertedCharLength = converted.length();
        int convertedCodePointCount = Character.codePointCount(converted, 0, convertedCharLength);
        result.codePointCount = convertedCodePointCount;
        result.charCount = convertedCharLength;
        if (!source.equals(converted)) {
            int sourceCharLength = source.length();
            int pointerCount = sourceTracker.getPointerSize();
            int[] xs = sourceTracker.getXCoordinates();
            int[] ys = sourceTracker.getYCoordinates();
            int[] intentionalFlags = sourceTracker.getIntentionalFlags();
            result.coordTracker = new TouchPointerCoordTracker(convertedCodePointCount);
            int outIndex = 0;         // code-point index into the converted text
            int inCharIndex = 0;      // char offset into the source text
            int outCharIndex = 0;     // char offset into the converted text
            boolean divergenceRecorded = false;
            while (outIndex < convertedCodePointCount) {
                int nextInCharIndex;
                int sourceCodePoint;
                int sourceX;
                int sourceY;
                int intentionalFlag;
                if (inCharIndex < sourceCharLength) {
                    sourceCodePoint = source.codePointAt(inCharIndex);
                    nextInCharIndex = inCharIndex + Character.charCount(sourceCodePoint);
                    if (outIndex < pointerCount) {
                        sourceX = xs[outIndex];
                        sourceY = ys[outIndex];
                        intentionalFlag = intentionalFlags[outIndex];
                    } else {
                        sourceX = -1;
                        sourceY = -1;
                        intentionalFlag = 0;
                    }
                } else {
                    nextInCharIndex = inCharIndex;
                    sourceCodePoint = -1;
                    sourceX = -1;
                    sourceY = -1;
                    intentionalFlag = 0;
                }

                int nextOutCharIndex;
                int convertedCodePoint;
                if (outCharIndex < convertedCharLength) {
                    convertedCodePoint = converted.codePointAt(outCharIndex);
                    nextOutCharIndex = outCharIndex + Character.charCount(convertedCodePoint);
                } else {
                    nextOutCharIndex = outCharIndex;
                    convertedCodePoint = -1;
                }

                int emittedX;
                int emittedY;
                boolean nextDivergenceRecorded;
                if (sourceCodePoint == convertedCodePoint && sourceCodePoint != -1) {
                    // Still aligned: this output code point keeps the source's coordinates.
                    nextDivergenceRecorded = divergenceRecorded;
                    emittedX = sourceX;
                    emittedY = sourceY;
                } else if (divergenceRecorded) {
                    // Past the first divergence: no coordinates for anything downstream.
                    nextDivergenceRecorded = divergenceRecorded;
                    emittedX = -1;
                    emittedY = -1;
                    intentionalFlag = 0;
                } else {
                    // First divergence: truncate the reported counts to the common prefix.
                    result.codePointCount = outIndex;
                    result.charCount = inCharIndex;
                    emittedX = -1;
                    emittedY = -1;
                    intentionalFlag = 0;
                    nextDivergenceRecorded = true;
                }
                // NOTE: the SOURCE code point is what gets recorded, not the converted one.
                result.coordTracker.addPointerAt(outIndex, sourceCodePoint, emittedX, emittedY, 0, 0, intentionalFlag);
                outIndex++;
                inCharIndex = nextInCharIndex;
                outCharIndex = nextOutCharIndex;
                divergenceRecorded = nextDivergenceRecorded;
            }
        } else {
            result.coordTracker = new TouchPointerCoordTracker(sourceTracker.getPointerSize());
            result.coordTracker.setTo(sourceTracker);
        }
        if (this.historyEnabled) {
            this.textHistory.add(result.text);
            this.trackerHistory.add(result.coordTracker);
        }
        return result;
    }

    /**
     * Pop the last conversion and return the one before it.
     *
     * <p>Audit DK-39 asked why this boundary is {@code size <= 0} while {@code TextComposer}'s
     * override uses {@code size >= 0}. They are not the same predicate on the same state: here
     * the result is read from {@code lastIndex - 1}, so there must be a *previous* entry — with a
     * single entry there is nothing to revert to. {@code TextComposer} seeds {@code textHistory}
     * from the composing text when it is empty and substitutes {@code ""} for
     * {@code lastIndex == 0}, so index 0 is a valid state to revert to there.
     */
    @Override // dev.bbkb.ime.core.inputmethod.InputMethodCallback
    public InputMethodCallback.ConversionResult revertLastConversion(CharSequence charSequence, TouchPointerCoordTracker sourceTracker, NuanceSDK nuanceSDK) {
        if (!this.historyEnabled) {
            return null;
        }
        int lastIndex = this.textHistory.size() - 1;
        if (lastIndex <= 0) {
            return null;
        }
        this.textHistory.remove(lastIndex);
        this.trackerHistory.remove(lastIndex);
        int previousIndex = lastIndex - 1;
        InputMethodCallback.ConversionResult result = new InputMethodCallback.ConversionResult();
        result.text = this.textHistory.get(previousIndex);
        result.coordTracker = this.trackerHistory.get(previousIndex);
        return result;
    }

    @Override // dev.bbkb.ime.core.inputmethod.InputMethodCallback
    public void reset() {
        this.historyEnabled = false;
        this.textHistory.clear();
        this.trackerHistory.clear();
    }

    @Override // dev.bbkb.ime.core.inputmethod.InputMethodCallback
    public InputMethodCallback.ConverterType getConverterType() {
        return InputMethodCallback.ConverterType.NOT_DEFINED;
    }
}

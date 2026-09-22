package dev.bbkb.ime.core.inputmethod;

import dev.bbkb.ime.core.textinput.composing.TouchPointerCoordTracker;
import com.blackberry.nuanceshim.NuanceSDK;



public interface InputMethodCallback {

    
    public static class ConversionResult {

        public CharSequence text;

        public TouchPointerCoordTracker coordTracker;

        public int codePointCount;

        public int charCount;
    }

    
    public enum ConverterType {
        NOT_DEFINED,
        VIETNAMESE_TELEX,
        HANGUL,
        JAPANESE_ROMAJI
    }

    ConversionResult convert(CharSequence charSequence, TouchPointerCoordTracker c0697m, NuanceSDK nuanceSDK);

    void reset();

    void processSymbols(CharSequence charSequence, NuanceSDK nuanceSDK);

    ConversionResult revertLastConversion(CharSequence charSequence, TouchPointerCoordTracker c0697m, NuanceSDK nuanceSDK);

    ConverterType getConverterType();
}

package dev.bbkb.ime.core.inputmethod;

import dev.bbkb.ime.core.textinput.composing.TouchPointerCoordTracker;
import com.blackberry.nuanceshim.NuanceSDK;

/**
 * Korean (Hangul) composition through the engine: syllables (U+AC00 to U+D7A3) are decomposed,
 * Compatibility Jamo (U+3130 to U+318F) are normalised to standard Jamo, anything else is fed as
 * is; {@link #convertText} returns {@code buildHangul()}.
 */
public class HangulInputProcessor extends AbstractInputProcessor {

    /**
     * Unused for Hangul (words are built through {@link #processSymbols}); always null.
     *
     * @param sourceTracker the touch-coordinate tracker (ignored)
     */
    @Override
    public InputMethodCallback.ConversionResult revertLastConversion(CharSequence charSequence, TouchPointerCoordTracker sourceTracker, NuanceSDK nuanceSDK) {
        return null;
    }

    /** Resets the session and turns history on. */
    @Override
    public void reset() {
        super.reset();
        this.historyEnabled = true;
    }

    @Override
    public InputMethodCallback.ConverterType getConverterType() {
        return InputMethodCallback.ConverterType.HANGUL;
    }

    /** @param c the char to test for a composed Hangul syllable */
    private boolean isHangulSyllable(char c) {
        return Character.UnicodeBlock.HANGUL_SYLLABLES.equals(Character.UnicodeBlock.of(c));
    }

    /** @param codePoint the code point to test for a composed Hangul syllable */
    public static boolean isHangulSyllable(int codePoint) {
        return Character.UnicodeBlock.HANGUL_SYLLABLES.equals(Character.UnicodeBlock.of(codePoint));
    }

    /** @param c the char to test for a Hangul Compatibility Jamo */
    private boolean isCompatibilityJamo(char c) {
        return Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO.equals(Character.UnicodeBlock.of(c));
    }

    /** Feeds each character into the engine's Hangul composer (see the class doc). */
    @Override
    public void processSymbols(CharSequence charSequence, NuanceSDK nuanceSDK) {
        for (int i = 0; i < charSequence.length(); i++) {
            char cCharAt = charSequence.charAt(i);
            if (isHangulSyllable(cCharAt)) {
                for (char c : nuanceSDK.decodeHangul(Character.toString(cCharAt), false).toCharArray()) {
                    nuanceSDK.processKeyBySymbol(c);
                }
            } else if (isCompatibilityJamo(cCharAt)) {
                char cCompatibilityJamoToJamoTransform = nuanceSDK.compatibilityJamoToJamoTransform(cCharAt);
                if (cCompatibilityJamoToJamoTransform != 0) {
                    nuanceSDK.processKeyBySymbol(cCompatibilityJamoToJamoTransform);
                }
            } else {
                nuanceSDK.processKeyBySymbol(cCharAt);
            }
        }
    }

    /** The Hangul word assembled from the symbols fed so far. */
    @Override
    public String convertText(CharSequence charSequence, NuanceSDK nuanceSDK) {
        return nuanceSDK.buildHangul();
    }
}

package dev.bbkb.ime.core.inputmethod;

import dev.bbkb.ime.core.textinput.composing.TouchPointerCoordTracker;
import com.blackberry.nuanceshim.NuanceSDK;

/**
 * Language-specific input processor for Korean (Hangul) text composition.
 *
 * <p>Implements {@link AbstractInputProcessor} to handle the Hangul syllable-block
 * assembly algorithm via NuanceSDK. Accepts a sequence of Hangul syllables or
 * Compatibility Jamo codepoints and feeds them through the NuanceSDK Hangul
 * composition engine one symbol at a time.
 *
 * <p>There are three character types handled on input:
 * <ul>
 *   <li>Fully-composed Hangul syllable (U+AC00–U+D7A3): decomposed via
 *       {@code NuanceSDK.decodeHangul()} and fed character by character.</li>
 *   <li>Hangul Compatibility Jamo (U+3130–U+318F): converted to standard Jamo
 *       via {@code NuanceSDK.compatibilityJamoToJamoTransform()} then fed.</li>
 *   <li>Any other character: fed directly as a symbol.</li>
 * </ul>
 *
 * <p>The composed word is retrieved via {@link #convertText}, which delegates to
 * {@code NuanceSDK.buildHangul()}.
 *
 * @see AbstractInputProcessor
 * @see dev.bbkb.ime.core.inputmethod.InputMethodCallback.ConverterType#HANGUL
 */
public class HangulInputProcessor extends AbstractInputProcessor {

    /**
     * Not used for Hangul composition — Hangul words are assembled entirely through
     * {@link #processSymbols}. Always returns {@code null}.
     */
    @Override // dev.bbkb.ime.core.inputmethod.AbstractInputProcessor, dev.bbkb.ime.core.inputmethod.InputMethodCallback
    public InputMethodCallback.ConversionResult revertLastConversion(CharSequence charSequence, TouchPointerCoordTracker c0697m, NuanceSDK nuanceSDK) {
        return null;
    }

    /**
     * Resets the Hangul composition session and marks this processor as active
     * ({@code historyEnabled = true}).
     */
    @Override // dev.bbkb.ime.core.inputmethod.AbstractInputProcessor, dev.bbkb.ime.core.inputmethod.InputMethodCallback
    public void reset() {
        super.reset();
        this.historyEnabled = true;
    }

    /**
     * Returns the input method type identifier for this processor.
     *
     * @return {@link InputMethodCallback.ConverterType#HANGUL}
     */
    @Override // dev.bbkb.ime.core.inputmethod.AbstractInputProcessor, dev.bbkb.ime.core.inputmethod.InputMethodCallback
    public InputMethodCallback.ConverterType getConverterType() {
        return InputMethodCallback.ConverterType.HANGUL;
    }

    /**
     * Returns {@code true} if {@code c} is a fully-composed Hangul syllable
     * (Unicode block {@code HANGUL_SYLLABLES}, U+AC00–U+D7A3).
     */
    private boolean isHangulSyllable(char c) {
        return Character.UnicodeBlock.HANGUL_SYLLABLES.equals(Character.UnicodeBlock.of(c));
    }

    /**
     * Returns {@code true} if the given code point is a fully-composed Hangul syllable
     * (Unicode block {@code HANGUL_SYLLABLES}, U+AC00–U+D7A3).
     *
     * @param i Unicode code point to test
     */
    public static boolean isHangulSyllable(int i) {
        return Character.UnicodeBlock.HANGUL_SYLLABLES.equals(Character.UnicodeBlock.of(i));
    }

    /**
     * Returns {@code true} if {@code c} is a Hangul Compatibility Jamo character
     * (Unicode block {@code HANGUL_COMPATIBILITY_JAMO}, U+3130–U+318F).
     */
    private boolean isCompatibilityJamo(char c) {
        return Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO.equals(Character.UnicodeBlock.of(c));
    }

    /**
     * Feeds each character in {@code charSequence} into the NuanceSDK Hangul composition
     * engine. Fully-composed syllables are first decomposed; Compatibility Jamo are
     * normalized to standard Jamo; all other characters are passed through directly.
     *
     * @param charSequence the input text to process (may contain syllables, jamo, or other chars)
     * @param nuanceSDK    the NuanceSDK instance to feed symbols into
     */
    @Override // dev.bbkb.ime.core.inputmethod.InputMethodCallback
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

    /**
     * Retrieves the fully-assembled Hangul word from NuanceSDK after all symbols
     * have been fed via {@link #processSymbols}.
     *
     * @param charSequence the raw composing text (used as context by the base class)
     * @param nuanceSDK    the NuanceSDK instance to query
     * @return the assembled Hangul word string
     */
    @Override // dev.bbkb.ime.core.inputmethod.AbstractInputProcessor
    public String convertText(CharSequence charSequence, NuanceSDK nuanceSDK) {
        return nuanceSDK.buildHangul();
    }
}

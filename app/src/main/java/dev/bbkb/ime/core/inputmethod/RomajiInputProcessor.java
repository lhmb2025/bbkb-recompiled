package dev.bbkb.ime.core.inputmethod;

import dev.bbkb.ime.core.textinput.composing.TouchPointerCoordTracker;
import com.blackberry.nuanceshim.NuanceSDK;

/**
 * Language-specific input processor for Japanese Romaji text composition.
 *
 * <p>Implements {@link AbstractInputProcessor} to integrate with NuanceSDK's Japanese
 * Romaji inline-word prediction. Unlike {@link HangulInputProcessor} (Hangul), this processor
 * does not feed individual symbols into NuanceSDK — symbol feeding is handled upstream.
 * Its sole responsibility is resolving which word to commit when composition ends:
 * it compares the user's raw typed text against NuanceSDK's current inline-word
 * prediction and returns whichever is the better match.
 *
 * <p>Resolution logic in {@link #convertText}:
 * <ol>
 *   <li>If both the typed text and the NuanceSDK inline word are all-ASCII letters,
 *       and the typed text starts with the inline word (case-insensitive), the typed
 *       text is preferred (the user has typed beyond the prediction).</li>
 *   <li>Otherwise the NuanceSDK inline word is used.</li>
 * </ol>
 *
 * @see AbstractInputProcessor
 * @see dev.bbkb.ime.core.inputmethod.InputMethodCallback.ConverterType#JAPANESE_ROMAJI
 */

public class RomajiInputProcessor extends AbstractInputProcessor {

    /**
     * No-op for Japanese Romaji. Symbol feeding into NuanceSDK is handled
     * elsewhere in the input pipeline; this processor only resolves the final word.
     */
    @Override // dev.bbkb.ime.core.inputmethod.InputMethodCallback
    public void processSymbols(CharSequence charSequence, NuanceSDK nuanceSDK) {
    }

    /**
     * Not used for Japanese Romaji composition. Always returns {@code null}.
     */
    @Override // dev.bbkb.ime.core.inputmethod.AbstractInputProcessor, dev.bbkb.ime.core.inputmethod.InputMethodCallback
    public InputMethodCallback.ConversionResult revertLastConversion(CharSequence charSequence, TouchPointerCoordTracker c0697m, NuanceSDK nuanceSDK) {
        return null;
    }

    /**
     * Resets the Romaji composition session. Sets {@code historyEnabled = false} to mark
     * this processor as inactive after reset (contrast with Hangul, which sets it
     * {@code true}).
     */
    @Override // dev.bbkb.ime.core.inputmethod.AbstractInputProcessor, dev.bbkb.ime.core.inputmethod.InputMethodCallback
    public void reset() {
        super.reset();
        this.historyEnabled = false;
    }

    /**
     * Returns the input method type identifier for this processor.
     *
     * @return {@link InputMethodCallback.ConverterType#JAPANESE_ROMAJI}
     */
    @Override // dev.bbkb.ime.core.inputmethod.AbstractInputProcessor, dev.bbkb.ime.core.inputmethod.InputMethodCallback
    public InputMethodCallback.ConverterType getConverterType() {
        return InputMethodCallback.ConverterType.JAPANESE_ROMAJI;
    }

    /**
     * Resolves which word to commit at the end of a Romaji composition session.
     *
     * <p>Returns the user's typed text ({@code charSequence}) if it is all ASCII letters
     * and starts with (case-insensitively) NuanceSDK's current inline-word prediction,
     * indicating the user has typed beyond the prediction. Otherwise returns the
     * NuanceSDK inline word.
     *
     * @param charSequence the raw composing text the user has typed
     * @param nuanceSDK    the NuanceSDK instance to query for the inline word
     * @return the word to commit
     */
    @Override // dev.bbkb.ime.core.inputmethod.AbstractInputProcessor
    public String convertText(CharSequence charSequence, NuanceSDK nuanceSDK) {
        String inlineWord = nuanceSDK.getInlineWord();
        String charSeqStr = charSequence.toString();
        java.util.Locale primaryLocale = nuanceSDK.getPrimaryLanguage();
        return (isAllLetters(charSeqStr) && isAllLetters(inlineWord) && charSeqStr != null && inlineWord != null && primaryLocale != null && charSeqStr.toLowerCase(primaryLocale).startsWith(inlineWord.toLowerCase(primaryLocale))) ? charSeqStr : inlineWord;
    }

    /**
     * Returns {@code true} if every character in {@code str} is an ASCII letter
     * (a–z or A–Z). Returns {@code false} for empty or null strings.
     *
     * @param str the string to test
     */
    private static boolean isAllLetters(String str) {
        if (android.text.TextUtils.isEmpty(str)) {
            return false;
        }
        for (char c : str.toCharArray()) {
            // Audit DK-22: this read `(c <= 'a' || c >= 'z') && (c <= 'A' || c >= 'Z')`, an
            // off-by-one decompilation artifact that rejects the boundary letters themselves —
            // so isAllLetters("a"), ("za"), ("Amazon") … all returned false. isAllLetters gates
            // the "prefer the user's typed text over the engine's inline word" rule in
            // convertText, so any Romaji word containing an 'a' or 'z' silently fell back to the
            // NuanceSDK inline word even after the user had typed past it.
            if ((c < 'a' || c > 'z') && (c < 'A' || c > 'Z')) {
                return false;
            }
        }
        return true;
    }
}

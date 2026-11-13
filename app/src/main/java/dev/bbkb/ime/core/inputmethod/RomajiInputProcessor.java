package dev.bbkb.ime.core.inputmethod;

import dev.bbkb.ime.core.textinput.composing.TouchPointerCoordTracker;
import com.blackberry.nuanceshim.NuanceSDK;

/**
 * Japanese Romaji: symbols are fed upstream; this only picks the word to commit. The typed text
 * wins when it and the engine's inline word are all ASCII letters and the typed text starts with
 * the inline word (the user typed past the prediction); otherwise the inline word.
 */
public class RomajiInputProcessor extends AbstractInputProcessor {
    @Override
    public void processSymbols(CharSequence charSequence, NuanceSDK nuanceSDK) {
        // Romaji symbols are fed elsewhere in the pipeline.
    }

    /**
     * Unused for Romaji; always null.
     *
     * @param sourceTracker the touch-coordinate tracker (ignored)
     */
    @Override
    public InputMethodCallback.ConversionResult revertLastConversion(CharSequence charSequence, TouchPointerCoordTracker sourceTracker, NuanceSDK nuanceSDK) {
        return null;
    }

    /** Resets the session and turns history off. */
    @Override
    public void reset() {
        super.reset();
        this.historyEnabled = false;
    }

    @Override
    public InputMethodCallback.ConverterType getConverterType() {
        return InputMethodCallback.ConverterType.JAPANESE_ROMAJI;
    }

    /** The word to commit (see the class doc). */
    @Override
    public String convertText(CharSequence charSequence, NuanceSDK nuanceSDK) {
        String inlineWord = nuanceSDK.getInlineWord();
        String charSeqStr = charSequence.toString();
        java.util.Locale primaryLocale = nuanceSDK.getPrimaryLanguage();
        return (isAllLetters(charSeqStr) && isAllLetters(inlineWord) && charSeqStr != null && inlineWord != null && primaryLocale != null && charSeqStr.toLowerCase(primaryLocale).startsWith(inlineWord.toLowerCase(primaryLocale))) ? charSeqStr : inlineWord;
    }

    /** @param text the string to test: true when non-empty and all ASCII letters */
    private static boolean isAllLetters(String text) {
        if (android.text.TextUtils.isEmpty(text)) {
            return false;
        }
        for (char c : text.toCharArray()) {
            if ((c < 'a' || c > 'z') && (c < 'A' || c > 'Z')) {
                return false;
            }
        }
        return true;
    }
}

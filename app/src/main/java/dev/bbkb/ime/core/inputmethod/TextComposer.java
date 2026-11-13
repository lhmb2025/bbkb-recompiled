package dev.bbkb.ime.core.inputmethod;

import dev.bbkb.ime.core.textinput.composing.TouchPointerCoordTracker;
import com.blackberry.nuanceshim.NuanceSDK;

import java.text.Normalizer;



public class TextComposer extends AbstractInputProcessor {
    @Override
    public void reset() {
        super.reset();
        this.historyEnabled = true;
    }

    /** Vietnamese letters and combining marks fed as explicit symbols; sorted for binarySearch. */
    private static final char[] VIETNAMESE_COMPOSABLE_CHARS = {
        224, 225, 226, 227, 232, 233, 234, 236,
        237, 242, 243, 244, 245, 249, 250, 253,
        259, 273, 297, 361, 417, 432, 768, 769,
        771, 777, 803, 7841, 7843, 7845, 7847, 7849,
        7851, 7853, 7855, 7857, 7859, 7861, 7863, 7865,
        7867, 7869, 7871, 7873, 7875, 7877, 7879, 7881,
        7883, 7885, 7887, 7889, 7891, 7893, 7895, 7897,
        7899, 7901, 7903, 7905, 7907, 7909, 7911, 7913,
        7915, 7917, 7919, 7921, 7923, 7925, 7927, 7929,
    };

    @Override
    public InputMethodCallback.ConverterType getConverterType() {
        return InputMethodCallback.ConverterType.VIETNAMESE_TELEX;
    }

    @Override
    public String convertText(CharSequence charSequence, NuanceSDK nuanceSDK) {
        String string = charSequence.toString();
        if (string.length() == 0) {
            return string;
        }
        String inlineWord = nuanceSDK.getInlineWord();
        int i = 0;
        int i2 = 0;
        while (i < inlineWord.length() && i < string.length()) {
            if (!Character.isLetter(inlineWord.charAt(i))) {
                i2++;
            }
            if (!Character.isLetter(string.charAt(i))) {
                i++;
            }
            if (i2 < inlineWord.length() && i < string.length() && Character.isUpperCase(string.charAt(i))) {
                StringBuilder sb = new StringBuilder(inlineWord);
                sb.setCharAt(i2, Character.toUpperCase(inlineWord.charAt(i2)));
                inlineWord = sb.toString();
            }
            i++;
            i2++;
        }
        return !Normalizer.isNormalized(inlineWord, Normalizer.Form.NFC) ? Normalizer.normalize(inlineWord, Normalizer.Form.NFC) : inlineWord;
    }

    /** @param sourceTracker the touch-coordinate tracker, returned unchanged with the reverted text */
    @Override
    public InputMethodCallback.ConversionResult revertLastConversion(CharSequence charSequence, TouchPointerCoordTracker sourceTracker, NuanceSDK nuanceSDK) {
        if (!this.historyEnabled) {
            return null;
        }
        InputMethodCallback.ConversionResult aVar = new InputMethodCallback.ConversionResult();
        if (charSequence.length() > 0) {
            if (this.textHistory.size() == 0) {
                for (int i = 1; i <= charSequence.length(); i++) {
                    this.textHistory.add(charSequence.subSequence(0, i));
                }
            }
            int size = this.textHistory.size() - 1;
            if (size >= 0) {
                this.textHistory.remove(size);
                nuanceSDK.clearOneSymbol();
                aVar.text = size > 0 ? this.textHistory.get(size - 1) : "";
                aVar.coordTracker = sourceTracker;
                return aVar;
            }
        }
        return null;
    }

    @Override
    public void processSymbols(CharSequence charSequence, NuanceSDK nuanceSDK) {
        for (int i = 0; i < charSequence.length(); i++) {
            char cCharAt = charSequence.charAt(i);
            if (java.util.Arrays.binarySearch(VIETNAMESE_COMPOSABLE_CHARS, cCharAt) >= 0) {
                nuanceSDK.addExplicitSymb(cCharAt);
            } else {
                nuanceSDK.processKeyBySymbol(cCharAt);
            }
        }
    }
}

package dev.bbkb.ime.keyboard.internal;

import android.content.res.TypedArray;



public abstract class KeyStyle {

    private final KeyboardTextsSet mTextsSet;

    public abstract int getInt(TypedArray typedArray, int index, int defaultValue);

    public abstract String[] getStringArray(TypedArray typedArray, int index, boolean withAdditionalTexts);

    public abstract String getString(TypedArray typedArray, int index);

    public abstract int getFlags(TypedArray typedArray, int index);

    public String[] parseStringArray(TypedArray typedArray, int index) {
        return getStringArray(typedArray, index, false);
    }

    protected KeyStyle(KeyboardTextsSet keyboardTextsSet) {
        this.mTextsSet = keyboardTextsSet;
    }

    protected String parseString(TypedArray typedArray, int index) {
        if (typedArray.hasValue(index)) {
            return this.mTextsSet.getResolvedText(typedArray.getString(index));
        }
        return null;
    }

    protected String[] parseStringArrayInternal(TypedArray typedArray, int index, boolean withAdditionalTexts) {
        if (!typedArray.hasValue(index)) {
            return null;
        }
        boolean z2 = withAdditionalTexts && this.mTextsSet.hasAdditionalTexts();
        return KeySpecParser.splitKeySpecsWithFlag(this.mTextsSet.resolveTextReferences(typedArray.getString(index), z2), z2);
    }
}

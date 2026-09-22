package dev.bbkb.ime.keyboard.internal;

import android.content.res.TypedArray;



public abstract class KeyStyle {

    private final KeyboardTextsSet mTextsSet;

    public abstract int getInt(TypedArray typedArray, int i, int i2);

    public abstract String[] getStringArray(TypedArray typedArray, int i, boolean z);

    public abstract String getString(TypedArray typedArray, int i);

    public abstract int getFlags(TypedArray typedArray, int i);

    public String[] parseStringArray(TypedArray typedArray, int i) {
        return getStringArray(typedArray, i, false);
    }

    protected KeyStyle(KeyboardTextsSet c1029al) {
        this.mTextsSet = c1029al;
    }

    protected String parseString(TypedArray typedArray, int i) {
        if (typedArray.hasValue(i)) {
            return this.mTextsSet.getResolvedText(typedArray.getString(i));
        }
        return null;
    }

    protected String[] parseStringArrayInternal(TypedArray typedArray, int i, boolean z) {
        if (!typedArray.hasValue(i)) {
            return null;
        }
        boolean z2 = z && this.mTextsSet.hasAdditionalTexts();
        return KeySpecParser.splitKeySpecsWithFlag(this.mTextsSet.resolveTextReferences(typedArray.getString(i), z2), z2);
    }
}

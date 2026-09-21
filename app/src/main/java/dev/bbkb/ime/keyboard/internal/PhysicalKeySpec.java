package dev.bbkb.ime.keyboard.internal;

import android.content.res.TypedArray;

import dev.bbkb.ime.R;



public class PhysicalKeySpec {

    public final String label;

    public final String[] moreKeys;

    public final String[] multitapKeys;

    public final String[] characterMapKeys;

    public PhysicalKeySpec(String str, TypedArray typedArray, KeyStyle keyStyle) {
        this.label = str;
        this.moreKeys = filterMoreKeyFlags(keyStyle.getStringArray(typedArray, R.styleable.Keyboard_PhysicalKey_moreKeys, true), "%");
        this.multitapKeys = keyStyle.parseStringArray(typedArray, R.styleable.Keyboard_PhysicalKey_multitapKeys);
        this.characterMapKeys = keyStyle.parseStringArray(typedArray, R.styleable.Keyboard_PhysicalKey_characterMapKeys);
    }

    private String[] filterMoreKeyFlags(String[] strArr, String str) {
        boolean z;
        if (strArr == null) {
            return strArr;
        }
        int length = strArr.length;
        int i = 0;
        while (true) {
            if (i >= length) {
                z = false;
                break;
            }
            if (strArr[i].equals(str)) {
                z = true;
                break;
            }
            i++;
        }
        if (!z) {
            return strArr;
        }
        int length2 = strArr.length;
        String[] strArr2 = new String[length2 - 1];
        int i2 = 0;
        for (int i3 = 0; i3 < length2; i3++) {
            if (!strArr[i3].equals(str)) {
                strArr2[i2] = strArr[i3];
                i2++;
            }
        }
        return strArr2;
    }

    public String toString() {
        return this.label;
    }
}

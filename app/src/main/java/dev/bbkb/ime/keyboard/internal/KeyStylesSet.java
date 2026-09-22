package dev.bbkb.ime.keyboard.internal;

import android.content.res.TypedArray;
import android.util.SparseArray;

import dev.bbkb.ime.core.shared.XmlParseUtils;
import dev.bbkb.ime.R;

import org.xmlpull.v1.XmlPullParser;

import java.util.Arrays;
import java.util.HashMap;



public final class KeyStylesSet {

    private static final String TAG = "KeyStylesSet";

    private final HashMap<String, KeyStyle> mStyles = new HashMap<>();

    private final KeyboardTextsSet mTextsSet;

    private final KeyStyle mEmptyKeyStyle;

    public KeyStylesSet(KeyboardTextsSet c1029al) {
        this.mTextsSet = c1029al;
        this.mEmptyKeyStyle = new EmptyKeyStyle(c1029al);
        this.mStyles.put("<empty>", this.mEmptyKeyStyle);
    }

    
    /** AOSP: the fallback style that reads attributes straight off the TypedArray. */
    private static final class EmptyKeyStyle extends KeyStyle {
        EmptyKeyStyle(KeyboardTextsSet c1029al) {
            super(c1029al);
        }

        @Override // dev.bbkb.ime.keyboard.internal.KeyStyle
        public String[] getStringArray(TypedArray typedArray, int i, boolean z) {
            return parseStringArrayInternal(typedArray, i, z);
        }

        @Override // dev.bbkb.ime.keyboard.internal.KeyStyle
        public String getString(TypedArray typedArray, int i) {
            return parseString(typedArray, i);
        }

        @Override // dev.bbkb.ime.keyboard.internal.KeyStyle
        public int getInt(TypedArray typedArray, int i, int i2) {
            return typedArray.getInt(i, i2);
        }

        @Override // dev.bbkb.ime.keyboard.internal.KeyStyle
        public int getFlags(TypedArray typedArray, int i) {
            return typedArray.getInt(i, 0);
        }
    }

    
    /** AOSP: a style declared by a &lt;key-style&gt; element, inheriting from its parent style. */
    private static final class DeclaredKeyStyle extends KeyStyle {

        private final HashMap<String, KeyStyle> mStyles;

        private final String mParentStyleName;

        private final SparseArray<Object> mStyleAttributes;

        public DeclaredKeyStyle(String str, KeyboardTextsSet c1029al, HashMap<String, KeyStyle> map) {
            super(c1029al);
            this.mStyleAttributes = new SparseArray<>();
            this.mParentStyleName = str;
            this.mStyles = map;
        }

        @Override // dev.bbkb.ime.keyboard.internal.KeyStyle
        public String[] getStringArray(TypedArray typedArray, int i, boolean z) {
            if (typedArray.hasValue(i)) {
                return parseStringArrayInternal(typedArray, i, z);
            }
            Object obj = this.mStyleAttributes.get(i);
            if (obj != null) {
                String[] strArr = (String[]) obj;
                return (String[]) Arrays.copyOf(strArr, strArr.length);
            }
            return this.mStyles.get(this.mParentStyleName).getStringArray(typedArray, i, z);
        }

        @Override // dev.bbkb.ime.keyboard.internal.KeyStyle
        public String getString(TypedArray typedArray, int i) {
            if (typedArray.hasValue(i)) {
                return parseString(typedArray, i);
            }
            Object obj = this.mStyleAttributes.get(i);
            if (obj != null) {
                return (String) obj;
            }
            return this.mStyles.get(this.mParentStyleName).getString(typedArray, i);
        }

        @Override // dev.bbkb.ime.keyboard.internal.KeyStyle
        public int getInt(TypedArray typedArray, int i, int i2) {
            if (typedArray.hasValue(i)) {
                return typedArray.getInt(i, i2);
            }
            Object obj = this.mStyleAttributes.get(i);
            if (obj != null) {
                return ((Integer) obj).intValue();
            }
            return this.mStyles.get(this.mParentStyleName).getInt(typedArray, i, i2);
        }

        @Override // dev.bbkb.ime.keyboard.internal.KeyStyle
        public int getFlags(TypedArray typedArray, int i) {
            int iMo7101c = this.mStyles.get(this.mParentStyleName).getFlags(typedArray, i);
            Integer num = (Integer) this.mStyleAttributes.get(i);
            return typedArray.getInt(i, 0) | (num != null ? num.intValue() : 0) | iMo7101c;
        }

        public void readKeyAttributes(TypedArray typedArray) {
            readString(typedArray, R.styleable.Keyboard_Key_altCode);
            readString(typedArray, R.styleable.Keyboard_Key_keySpec);
            readString(typedArray, R.styleable.Keyboard_Key_keyHintLabel);
            readBoolean(typedArray, R.styleable.Keyboard_Key_moreKeys);
            readBoolean(typedArray, R.styleable.Keyboard_Key_additionalMoreKeys);
            readFlags(typedArray, R.styleable.Keyboard_Key_keyLabelFlags);
            readString(typedArray, R.styleable.Keyboard_Key_keyIconDisabled);
            readString(typedArray, R.styleable.Keyboard_Key_keyIconActive);
            readInt(typedArray, R.styleable.Keyboard_Key_maxMoreKeysColumn);
            readInt(typedArray, R.styleable.Keyboard_Key_backgroundType);
            readFlags(typedArray, R.styleable.Keyboard_Key_keyActionFlags);
        }

        private void readString(TypedArray typedArray, int i) {
            if (typedArray.hasValue(i)) {
                this.mStyleAttributes.put(i, parseString(typedArray, i));
            }
        }

        private void readInt(TypedArray typedArray, int i) {
            if (typedArray.hasValue(i)) {
                this.mStyleAttributes.put(i, Integer.valueOf(typedArray.getInt(i, 0)));
            }
        }

        private void readFlags(TypedArray typedArray, int i) {
            if (typedArray.hasValue(i)) {
                Integer num = (Integer) this.mStyleAttributes.get(i);
                this.mStyleAttributes.put(i, Integer.valueOf(typedArray.getInt(i, 0) | (num != null ? num.intValue() : 0)));
            }
        }

        private void readBoolean(TypedArray typedArray, int i) {
            if (typedArray.hasValue(i)) {
                this.mStyleAttributes.put(i, parseStringArrayInternal(typedArray, i, false));
            }
        }
    }

    public void parseKeyStyleAttributes(TypedArray typedArray, TypedArray typedArray2, XmlPullParser xmlPullParser) throws XmlParseUtils.ParseException {
        String string = typedArray.getString(R.styleable.Keyboard_KeyStyle_styleName);
        String string2 = "<empty>";
        if (typedArray.hasValue(R.styleable.Keyboard_KeyStyle_parentStyle)) {
            string2 = typedArray.getString(R.styleable.Keyboard_KeyStyle_parentStyle);
            if (!this.mStyles.containsKey(string2)) {
                throw new XmlParseUtils.ParseException("Unknown parentStyle " + string2, xmlPullParser);
            }
        }
        DeclaredKeyStyle aVar = new DeclaredKeyStyle(string2, this.mTextsSet, this.mStyles);
        aVar.readKeyAttributes(typedArray2);
        this.mStyles.put(string, aVar);
    }

    public KeyStyle getKeyStyle(TypedArray typedArray, XmlPullParser xmlPullParser) throws XmlParseUtils.ParseException {
        if (!typedArray.hasValue(R.styleable.Keyboard_Key_keyStyle)) {
            return getEmptyKeyStyle();
        }
        String string = typedArray.getString(R.styleable.Keyboard_Key_keyStyle);
        if (!this.mStyles.containsKey(string)) {
            throw new XmlParseUtils.ParseException("Unknown key style: " + string, xmlPullParser);
        }
        return this.mStyles.get(string);
    }

    public KeyStyle getEmptyKeyStyle() {
        return this.mEmptyKeyStyle;
    }
}

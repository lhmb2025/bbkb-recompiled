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

    /** @param keyboardTextsSet texts used to resolve style values */
    public KeyStylesSet(KeyboardTextsSet keyboardTextsSet) {
        this.mTextsSet = keyboardTextsSet;
        this.mEmptyKeyStyle = new EmptyKeyStyle(keyboardTextsSet);
        this.mStyles.put("<empty>", this.mEmptyKeyStyle);
    }

    /** Fallback style that reads attributes straight off the TypedArray. */
    private static final class EmptyKeyStyle extends KeyStyle {
        EmptyKeyStyle(KeyboardTextsSet keyboardTextsSet) {
            super(keyboardTextsSet);
        }

        @Override
        public String[] getStringArray(TypedArray typedArray, int index, boolean withAdditionalTexts) {
            return parseStringArrayInternal(typedArray, index, withAdditionalTexts);
        }

        @Override
        public String getString(TypedArray typedArray, int index) {
            return parseString(typedArray, index);
        }

        @Override
        public int getInt(TypedArray typedArray, int index, int defaultValue) {
            return typedArray.getInt(index, defaultValue);
        }

        @Override
        public int getFlags(TypedArray typedArray, int index) {
            return typedArray.getInt(index, 0);
        }
    }

    /** A style declared by a &lt;key-style&gt; element, inheriting from its parent style. */
    private static final class DeclaredKeyStyle extends KeyStyle {

        private final HashMap<String, KeyStyle> mStyles;

        private final String mParentStyleName;

        private final SparseArray<Object> mStyleAttributes;

        /**
         * @param parentStyleName parent style name
         * @param keyboardTextsSet texts used to resolve style values
         */
        public DeclaredKeyStyle(String parentStyleName, KeyboardTextsSet keyboardTextsSet, HashMap<String, KeyStyle> map) {
            super(keyboardTextsSet);
            this.mStyleAttributes = new SparseArray<>();
            this.mParentStyleName = parentStyleName;
            this.mStyles = map;
        }

        @Override
        public String[] getStringArray(TypedArray typedArray, int index, boolean withAdditionalTexts) {
            if (typedArray.hasValue(index)) {
                return parseStringArrayInternal(typedArray, index, withAdditionalTexts);
            }
            Object obj = this.mStyleAttributes.get(index);
            if (obj != null) {
                String[] strArr = (String[]) obj;
                return (String[]) Arrays.copyOf(strArr, strArr.length);
            }
            return this.mStyles.get(this.mParentStyleName).getStringArray(typedArray, index, withAdditionalTexts);
        }

        @Override
        public String getString(TypedArray typedArray, int index) {
            if (typedArray.hasValue(index)) {
                return parseString(typedArray, index);
            }
            Object obj = this.mStyleAttributes.get(index);
            if (obj != null) {
                return (String) obj;
            }
            return this.mStyles.get(this.mParentStyleName).getString(typedArray, index);
        }

        @Override
        public int getInt(TypedArray typedArray, int index, int defaultValue) {
            if (typedArray.hasValue(index)) {
                return typedArray.getInt(index, defaultValue);
            }
            Object obj = this.mStyleAttributes.get(index);
            if (obj != null) {
                return ((Integer) obj).intValue();
            }
            return this.mStyles.get(this.mParentStyleName).getInt(typedArray, index, defaultValue);
        }

        @Override
        public int getFlags(TypedArray typedArray, int index) {
            int iMo7101c = this.mStyles.get(this.mParentStyleName).getFlags(typedArray, index);
            Integer num = (Integer) this.mStyleAttributes.get(index);
            return typedArray.getInt(index, 0) | (num != null ? num.intValue() : 0) | iMo7101c;
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

        private void readString(TypedArray typedArray, int index) {
            if (typedArray.hasValue(index)) {
                this.mStyleAttributes.put(index, parseString(typedArray, index));
            }
        }

        private void readInt(TypedArray typedArray, int index) {
            if (typedArray.hasValue(index)) {
                this.mStyleAttributes.put(index, Integer.valueOf(typedArray.getInt(index, 0)));
            }
        }

        private void readFlags(TypedArray typedArray, int index) {
            if (typedArray.hasValue(index)) {
                Integer num = (Integer) this.mStyleAttributes.get(index);
                this.mStyleAttributes.put(index, Integer.valueOf(typedArray.getInt(index, 0) | (num != null ? num.intValue() : 0)));
            }
        }

        private void readBoolean(TypedArray typedArray, int index) {
            if (typedArray.hasValue(index)) {
                this.mStyleAttributes.put(index, parseStringArrayInternal(typedArray, index, false));
            }
        }
    }

    public void parseKeyStyleAttributes(TypedArray typedArray, TypedArray keyAttrs, XmlPullParser xmlPullParser) throws XmlParseUtils.ParseException {
        String string = typedArray.getString(R.styleable.Keyboard_KeyStyle_styleName);
        String string2 = "<empty>";
        if (typedArray.hasValue(R.styleable.Keyboard_KeyStyle_parentStyle)) {
            string2 = typedArray.getString(R.styleable.Keyboard_KeyStyle_parentStyle);
            if (!this.mStyles.containsKey(string2)) {
                throw new XmlParseUtils.ParseException("Unknown parentStyle " + string2, xmlPullParser);
            }
        }
        DeclaredKeyStyle aVar = new DeclaredKeyStyle(string2, this.mTextsSet, this.mStyles);
        aVar.readKeyAttributes(keyAttrs);
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

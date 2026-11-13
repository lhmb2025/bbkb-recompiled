package dev.bbkb.ime.keyboard.internal;

import android.content.res.Resources;
import android.content.res.TypedArray;
import android.util.Xml;

import dev.bbkb.ime.R;
import dev.bbkb.ime.core.device.ResourceConfigManager;

import org.xmlpull.v1.XmlPullParser;

import java.util.ArrayDeque;



public final class KeyboardRow {

    private final KeyboardParams mParams;

    private final int mRowHeight;

    private final ArrayDeque<RowAttributes> mRowAttributesStack = new ArrayDeque<>();

    private final int mCurrentY;

    private float mCurrentX;

    
    private static class RowAttributes {

        public final float mDefaultKeyWidth;

        public final float mDefaultSpacerWidth;

        public final int mDefaultKeyLabelFlags;

        public final int mDefaultBackgroundType;

        public RowAttributes(TypedArray typedArray, float defaultKeyWidth, float defaultSpacerWidth, int keyboardWidth) {
            this.mDefaultKeyWidth = typedArray.getFraction(R.styleable.Keyboard_Key_keyWidth, keyboardWidth, keyboardWidth, defaultKeyWidth);
            this.mDefaultSpacerWidth = typedArray.getFraction(R.styleable.Keyboard_Key_spacerWidth, keyboardWidth, keyboardWidth, defaultSpacerWidth);
            this.mDefaultKeyLabelFlags = typedArray.getInt(R.styleable.Keyboard_Key_keyLabelFlags, 0);
            this.mDefaultBackgroundType = typedArray.getInt(R.styleable.Keyboard_Key_backgroundType, 1);
        }

        public RowAttributes(TypedArray typedArray, RowAttributes defaultRowAttr, int keyboardWidth) {
            this.mDefaultKeyWidth = typedArray.getFraction(R.styleable.Keyboard_Key_keyWidth, keyboardWidth, keyboardWidth, defaultRowAttr.mDefaultKeyWidth);
            this.mDefaultSpacerWidth = typedArray.getFraction(R.styleable.Keyboard_Key_spacerWidth, keyboardWidth, keyboardWidth, defaultRowAttr.mDefaultSpacerWidth);
            this.mDefaultKeyLabelFlags = typedArray.getInt(R.styleable.Keyboard_Key_keyLabelFlags, 0) | defaultRowAttr.mDefaultKeyLabelFlags;
            this.mDefaultBackgroundType = typedArray.getInt(R.styleable.Keyboard_Key_backgroundType, defaultRowAttr.mDefaultBackgroundType);
        }
    }

    public KeyboardRow(Resources resources, KeyboardParams keyboardParams, XmlPullParser xmlPullParser, int y) {
        this.mParams = keyboardParams;
        TypedArray typedArrayObtainAttributes = resources.obtainAttributes(Xml.asAttributeSet(xmlPullParser), R.styleable.Keyboard);
        this.mRowHeight = (int) ResourceConfigManager.getFractionOrDimensionOrDefault(typedArrayObtainAttributes, R.styleable.Keyboard_rowHeight, keyboardParams.mBaseHeight, keyboardParams.mDefaultRowHeight);
        typedArrayObtainAttributes.recycle();
        TypedArray typedArrayObtainAttributes2 = resources.obtainAttributes(Xml.asAttributeSet(xmlPullParser), R.styleable.Keyboard_Key);
        this.mRowAttributesStack.push(new RowAttributes(typedArrayObtainAttributes2, keyboardParams.mDefaultKeyWidth, keyboardParams.mDefaultSpacerWidth, keyboardParams.mBaseWidth));
        typedArrayObtainAttributes2.recycle();
        this.mCurrentY = y;
        this.mCurrentX = 0.0f;
    }

    public int getRowHeight() {
        return this.mRowHeight;
    }

    public void pushRowAttributes(TypedArray typedArray) {
        this.mRowAttributesStack.push(new RowAttributes(typedArray, this.mRowAttributesStack.peek(), this.mParams.mBaseWidth));
    }

    public void popRowAttributes() {
        this.mRowAttributesStack.pop();
    }

    public float getDefaultKeyWidth() {
        return this.mRowAttributesStack.peek().mDefaultKeyWidth;
    }

    public float getDefaultSpacerWidth() {
        return this.mRowAttributesStack.peek().mDefaultSpacerWidth;
    }

    public int getDefaultKeyLabelFlags() {
        return this.mRowAttributesStack.peek().mDefaultKeyLabelFlags;
    }

    public int getDefaultBackgroundType() {
        return this.mRowAttributesStack.peek().mDefaultBackgroundType;
    }

    public void setXPos(float keyXPos) {
        this.mCurrentX = keyXPos;
    }

    public void advanceXPos(float width) {
        this.mCurrentX += width;
    }

    public int getY() {
        return this.mCurrentY;
    }

    public float getKeyX(TypedArray typedArray) {
        if (typedArray == null || !typedArray.hasValue(R.styleable.Keyboard_Key_keyXPos)) {
            return this.mCurrentX;
        }
        float fraction = typedArray.getFraction(R.styleable.Keyboard_Key_keyXPos, this.mParams.mBaseWidth, this.mParams.mBaseWidth, 0.0f);
        if (fraction >= 0.0f) {
            return fraction + this.mParams.mLeftPadding;
        }
        return Math.max(fraction + (this.mParams.mOccupiedWidth - this.mParams.mRightPadding), this.mCurrentX);
    }

    public float getKeyWidth(TypedArray typedArray, float keyXPos) {
        if (typedArray == null) {
            return getDefaultKeyWidth();
        }
        if (ResourceConfigManager.getIntOrDefault(typedArray, R.styleable.Keyboard_Key_keyWidth, 0) == -1) {
            return (this.mParams.mOccupiedWidth - this.mParams.mRightPadding) - keyXPos;
        }
        return typedArray.getFraction(R.styleable.Keyboard_Key_keyWidth, this.mParams.mBaseWidth, this.mParams.mBaseWidth, getDefaultKeyWidth());
    }

    public float getSpacerWidth(TypedArray typedArray, float keyXPos) {
        if (typedArray == null) {
            return getDefaultKeyWidth();
        }
        float fraction = typedArray.getFraction(R.styleable.Keyboard_Key_spacerWidth, this.mParams.mBaseWidth, this.mParams.mBaseWidth, getDefaultSpacerWidth());
        return fraction < 0.0f ? getKeyWidth(typedArray, keyXPos) : fraction;
    }
}

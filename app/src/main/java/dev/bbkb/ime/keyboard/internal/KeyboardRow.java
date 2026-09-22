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

        public RowAttributes(TypedArray typedArray, float f, float f2, int i) {
            this.mDefaultKeyWidth = typedArray.getFraction(R.styleable.Keyboard_Key_keyWidth, i, i, f);
            this.mDefaultSpacerWidth = typedArray.getFraction(R.styleable.Keyboard_Key_spacerWidth, i, i, f2);
            this.mDefaultKeyLabelFlags = typedArray.getInt(R.styleable.Keyboard_Key_keyLabelFlags, 0);
            this.mDefaultBackgroundType = typedArray.getInt(R.styleable.Keyboard_Key_backgroundType, 1);
        }

        public RowAttributes(TypedArray typedArray, RowAttributes aVar, int i) {
            this.mDefaultKeyWidth = typedArray.getFraction(R.styleable.Keyboard_Key_keyWidth, i, i, aVar.mDefaultKeyWidth);
            this.mDefaultSpacerWidth = typedArray.getFraction(R.styleable.Keyboard_Key_spacerWidth, i, i, aVar.mDefaultSpacerWidth);
            this.mDefaultKeyLabelFlags = typedArray.getInt(R.styleable.Keyboard_Key_keyLabelFlags, 0) | aVar.mDefaultKeyLabelFlags;
            this.mDefaultBackgroundType = typedArray.getInt(R.styleable.Keyboard_Key_backgroundType, aVar.mDefaultBackgroundType);
        }
    }

    public KeyboardRow(Resources resources, KeyboardParams c1025ah, XmlPullParser xmlPullParser, int i) {
        this.mParams = c1025ah;
        TypedArray typedArrayObtainAttributes = resources.obtainAttributes(Xml.asAttributeSet(xmlPullParser), R.styleable.Keyboard);
        this.mRowHeight = (int) ResourceConfigManager.getFractionOrDimensionOrDefault(typedArrayObtainAttributes, R.styleable.Keyboard_rowHeight, c1025ah.mBaseHeight, c1025ah.mDefaultRowHeight);
        typedArrayObtainAttributes.recycle();
        TypedArray typedArrayObtainAttributes2 = resources.obtainAttributes(Xml.asAttributeSet(xmlPullParser), R.styleable.Keyboard_Key);
        this.mRowAttributesStack.push(new RowAttributes(typedArrayObtainAttributes2, c1025ah.mDefaultKeyWidth, c1025ah.mDefaultSpacerWidth, c1025ah.mBaseWidth));
        typedArrayObtainAttributes2.recycle();
        this.mCurrentY = i;
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

    public void setXPos(float f) {
        this.mCurrentX = f;
    }

    public void advanceXPos(float f) {
        this.mCurrentX += f;
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

    public float getKeyWidth(TypedArray typedArray, float f) {
        if (typedArray == null) {
            return getDefaultKeyWidth();
        }
        if (ResourceConfigManager.getIntOrDefault(typedArray, R.styleable.Keyboard_Key_keyWidth, 0) == -1) {
            return (this.mParams.mOccupiedWidth - this.mParams.mRightPadding) - f;
        }
        return typedArray.getFraction(R.styleable.Keyboard_Key_keyWidth, this.mParams.mBaseWidth, this.mParams.mBaseWidth, getDefaultKeyWidth());
    }

    public float getSpacerWidth(TypedArray typedArray, float f) {
        if (typedArray == null) {
            return getDefaultKeyWidth();
        }
        float fraction = typedArray.getFraction(R.styleable.Keyboard_Key_spacerWidth, this.mParams.mBaseWidth, this.mParams.mBaseWidth, getDefaultSpacerWidth());
        return fraction < 0.0f ? getKeyWidth(typedArray, f) : fraction;
    }
}

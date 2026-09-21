package dev.bbkb.ime.keyboard.internal;

import android.graphics.Typeface;

import dev.bbkb.ime.core.device.ResourceConfigManager;
import dev.bbkb.ime.keyboard.KeyboardColorManager;



public final class KeyDrawParams {

    public Typeface typeface;

    public int letterSizePixels;

    public int labelSizePixels;

    public int largeLetterSizePixels;

    public int hintLetterSizePixels;

    public int shiftedLetterHintSizePixels;

    public int multilineHintLabelSizePixels;

    public int hintLabelSizePixels;

    public int previewTextSizePixels;

    // Colours are pulled at draw time from KeyboardColorManager, the single source of truth.
    
    public int getTextColor() {
        return KeyboardColorManager.INSTANCE.getIconColor();
    }
    
    public int getTextInactivatedColor() {
        return KeyboardColorManager.INSTANCE.getIconColor(KeyboardColorManager.ALPHA_SECONDARY);
    }
    
    public int getTextShadowColor() {
        // Shadow color removed from simplified system - return subtle black
        return KeyboardColorManager.INSTANCE.applyAlpha(0xFF000000, 0.3f);
    }
    
    public int getFunctionalTextColor() {
        return KeyboardColorManager.INSTANCE.getIconColor();
    }
    
    public int getHintLetterColor() {
        return KeyboardColorManager.INSTANCE.getHintColor();
    }
    
    public int getHintLabelColor() {
        return KeyboardColorManager.INSTANCE.getHintColor();
    }
    
    public int getShiftedLetterHintInactivatedColor() {
        return KeyboardColorManager.INSTANCE.getIconColor(KeyboardColorManager.ALPHA_SECONDARY);
    }
    
    public int getShiftedLetterHintActivatedColor() {
        return KeyboardColorManager.INSTANCE.getIconColor();
    }
    
    public float hintLabelVerticalAdjustment;

    public float labelOffCenterRatio;

    public float labelVerticalBaselineRatio;

    public float hintLabelOffCenterRatio;

    public float multilineHintLabelVerticalAdjustmentRatio;

    /**
     * Alpha (0-255) applied to key icons/text while the alt-code fade runs. The
     * decompiler mapped this slot to the name "keyboardHeight"; it never held a
     * height (KeyboardView resets it to 255 per draw). AOSP heritage: KeyDrawParams.mAnimAlpha.
     */
    public int animAlpha;

    private static float getFloatOrDefault(float f, float f2) {
        return f != 0.0f ? f : f2;
    }

    public KeyDrawParams() {
    }

    private KeyDrawParams(KeyDrawParams source) {
        this.typeface = source.typeface;
        this.letterSizePixels = source.letterSizePixels;
        this.labelSizePixels = source.labelSizePixels;
        this.largeLetterSizePixels = source.largeLetterSizePixels;
        this.hintLetterSizePixels = source.hintLetterSizePixels;
        this.shiftedLetterHintSizePixels = source.shiftedLetterHintSizePixels;
        this.multilineHintLabelSizePixels = source.multilineHintLabelSizePixels;
        this.hintLabelSizePixels = source.hintLabelSizePixels;
        this.previewTextSizePixels = source.previewTextSizePixels;
        this.hintLabelVerticalAdjustment = source.hintLabelVerticalAdjustment;
        this.labelOffCenterRatio = source.labelOffCenterRatio;
        this.labelVerticalBaselineRatio = source.labelVerticalBaselineRatio;
        this.hintLabelOffCenterRatio = source.hintLabelOffCenterRatio;
        this.multilineHintLabelVerticalAdjustmentRatio = source.multilineHintLabelVerticalAdjustmentRatio;
        this.animAlpha = source.animAlpha;
    }

    public void applyKeyStyleData(int i, KeyVisualAttributes source) {
        if (source == null) {
            return;
        }
        if (source.typeface != null) {
            this.typeface = source.typeface;
        }
        this.letterSizePixels = computeSizeInPixels(i, source.letterSizeUnit, source.letterSize, this.letterSizePixels);
        this.labelSizePixels = computeSizeInPixels(i, source.labelSizeUnit, source.labelSize, this.labelSizePixels);
        this.largeLetterSizePixels = computeSizeFromRatio(i, source.largeLetterRatio, this.largeLetterSizePixels);
        this.hintLetterSizePixels = computeSizeFromRatio(i, source.hintLetterRatio, this.hintLetterSizePixels);
        this.shiftedLetterHintSizePixels = computeSizeFromRatio(i, source.shiftedLetterHintRatio, this.shiftedLetterHintSizePixels);
        this.multilineHintLabelSizePixels = computeSizeFromRatio(i, source.multilineHintLabelRatio, this.multilineHintLabelSizePixels);
        this.hintLabelSizePixels = computeSizeFromRatio(i, source.hintLabelRatio, this.hintLabelSizePixels);
        this.previewTextSizePixels = computeSizeFromRatio(i, source.previewTextRatio, this.previewTextSizePixels);
        this.hintLabelVerticalAdjustment = getFloatOrDefault(source.hintLabelVerticalAdjustment, this.hintLabelVerticalAdjustment);
        this.labelOffCenterRatio = getFloatOrDefault(source.labelOffCenterRatio, this.labelOffCenterRatio);
        this.labelVerticalBaselineRatio = getFloatOrDefault(source.labelVerticalBaselineRatio, this.labelVerticalBaselineRatio);
        this.hintLabelOffCenterRatio = getFloatOrDefault(source.hintLabelOffCenterRatio, this.hintLabelOffCenterRatio);
        this.multilineHintLabelVerticalAdjustmentRatio = getFloatOrDefault(source.multilineHintLabelVerticalAdjustmentRatio, this.multilineHintLabelVerticalAdjustmentRatio);
    }

    public KeyDrawParams createWithStyleData(int keyboardHeight, KeyVisualAttributes styleData) {
        if (styleData == null) {
            return this;
        }
        KeyDrawParams source = new KeyDrawParams(this);
        source.applyKeyStyleData(keyboardHeight, styleData);
        return source;
    }

    private static int computeSizeInPixels(int i, int i2, float f, int i3) {
        return ResourceConfigManager.isValidPixelSize(i2) ? i2 : ResourceConfigManager.isValidFraction(f) ? (int) (i * f) : i3;
    }

    private static int computeSizeFromRatio(int i, float f, int i2) {
        return ResourceConfigManager.isValidFraction(f) ? (int) (i * f) : i2;
    }
}

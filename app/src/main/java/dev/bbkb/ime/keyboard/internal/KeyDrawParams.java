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

    // Colours are read at draw time from KeyboardColorManager.

    public int getTextColor() {
        return KeyboardColorManager.INSTANCE.getIconColor();
    }
    
    public int getTextInactivatedColor() {
        return KeyboardColorManager.INSTANCE.getIconColor(KeyboardColorManager.ALPHA_SECONDARY);
    }
    
    public int getTextShadowColor() {
        // Fixed subtle black shadow.
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

    /** Alpha (0-255) for key icons/text during the alt-code fade; KeyboardView resets it to 255 per draw. */
    public int animAlpha;

    private static float getFloatOrDefault(float value, float defaultValue) {
        return value != 0.0f ? value : defaultValue;
    }

    public KeyDrawParams() {
        // Fields start at their defaults.
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

    /** @param keyHeight key height the size ratios apply to */
    public void applyKeyStyleData(int keyHeight, KeyVisualAttributes source) {
        if (source == null) {
            return;
        }
        if (source.typeface != null) {
            this.typeface = source.typeface;
        }
        this.letterSizePixels = computeSizeInPixels(keyHeight, source.letterSizeUnit, source.letterSize, this.letterSizePixels);
        this.labelSizePixels = computeSizeInPixels(keyHeight, source.labelSizeUnit, source.labelSize, this.labelSizePixels);
        this.largeLetterSizePixels = computeSizeFromRatio(keyHeight, source.largeLetterRatio, this.largeLetterSizePixels);
        this.hintLetterSizePixels = computeSizeFromRatio(keyHeight, source.hintLetterRatio, this.hintLetterSizePixels);
        this.shiftedLetterHintSizePixels = computeSizeFromRatio(keyHeight, source.shiftedLetterHintRatio, this.shiftedLetterHintSizePixels);
        this.multilineHintLabelSizePixels = computeSizeFromRatio(keyHeight, source.multilineHintLabelRatio, this.multilineHintLabelSizePixels);
        this.hintLabelSizePixels = computeSizeFromRatio(keyHeight, source.hintLabelRatio, this.hintLabelSizePixels);
        this.previewTextSizePixels = computeSizeFromRatio(keyHeight, source.previewTextRatio, this.previewTextSizePixels);
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

    private static int computeSizeInPixels(int keyHeight, int pixelSize, float ratio, int defaultSize) {
        return ResourceConfigManager.isValidPixelSize(pixelSize) ? pixelSize : ResourceConfigManager.isValidFraction(ratio) ? (int) (keyHeight * ratio) : defaultSize;
    }

    private static int computeSizeFromRatio(int keyHeight, float ratio, int defaultSize) {
        return ResourceConfigManager.isValidFraction(ratio) ? (int) (keyHeight * ratio) : defaultSize;
    }
}

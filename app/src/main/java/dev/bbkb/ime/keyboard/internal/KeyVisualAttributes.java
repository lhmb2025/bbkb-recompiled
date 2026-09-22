package dev.bbkb.ime.keyboard.internal;

import android.content.res.TypedArray;
import android.graphics.Typeface;
import android.util.SparseIntArray;

import dev.bbkb.ime.R;
import dev.bbkb.ime.core.device.ResourceConfigManager;



public final class KeyVisualAttributes {

    public final Typeface typeface;

    public final float letterSize;

    public final int letterSizeUnit;

    public final float labelSize;

    public final int labelSizeUnit;

    public final float largeLetterRatio;

    public final float hintLetterRatio;

    public final float shiftedLetterHintRatio;

    public final float multilineHintLabelRatio;

    public final float hintLabelRatio;

    public final float previewTextRatio;

    public final int textColor;

    public final int textInactivatedColor;

    public final int textShadowColor;

    public final int functionalTextColor;

    public final int hintLetterColor;

    public final int hintLabelColor;

    public final int shiftedLetterHintInactivatedColor;

    public final int shiftedLetterHintActivatedColor;

    public final int previewTextColor;

    public final float hintLabelVerticalAdjustment;

    public final float multilineHintLabelVerticalAdjustmentRatio;

    public final float labelOffCenterRatio;

    public final float labelVerticalBaselineRatio;

    public final float hintLabelOffCenterRatio;

    // Updated to use proper R.styleable.Keyboard_Key_* indices instead of hardcoded values from C1123a
    private static final int[] STYLE_ATTRIBUTE_IDS = {
        R.styleable.Keyboard_Key_keyTypeface,                                    // 36
        R.styleable.Keyboard_Key_keyLetterSize,                                  // 22
        R.styleable.Keyboard_Key_keyLabelSize,                                   // 19
        R.styleable.Keyboard_Key_keyLargeLetterRatio,                           // 21
        R.styleable.Keyboard_Key_keyHintLetterRatio,                            // 13
        R.styleable.Keyboard_Key_keyShiftedLetterHintRatio,                     // 30
        R.styleable.Keyboard_Key_keyMultilineHintLabelRatio,                    // 24
        R.styleable.Keyboard_Key_keyHintLabelRatio,                             // 10
        R.styleable.Keyboard_Key_keyPreviewTextRatio,                           // 27
        R.styleable.Keyboard_Key_keyTextColor,                                   // 33
        R.styleable.Keyboard_Key_keyTextInactivatedColor,                       // 34
        R.styleable.Keyboard_Key_keyTextShadowColor,                            // 35
        R.styleable.Keyboard_Key_functionalTextColor,                           // 3
        R.styleable.Keyboard_Key_keyHintLetterColor,                            // 12
        R.styleable.Keyboard_Key_keyHintLabelColor,                             // 8
        R.styleable.Keyboard_Key_keyShiftedLetterHintInactivatedColor,         // 29
        R.styleable.Keyboard_Key_keyShiftedLetterHintActivatedColor,           // 28
        R.styleable.Keyboard_Key_keyPreviewTextColor,                           // 26
        R.styleable.Keyboard_Key_keyHintLabelVerticalAdjustment,               // 11
        R.styleable.Keyboard_Key_keyLabelOffCenterRatio,                        // 18
        R.styleable.Keyboard_Key_keyLabelVerticalBaselineRatio,                 // 20
        R.styleable.Keyboard_Key_keyHintLabelOffCenterRatio                     // 9
    };

    private static final SparseIntArray STYLE_ATTRIBUTE_SET = new SparseIntArray();

    static {
        for (int i : STYLE_ATTRIBUTE_IDS) {
            STYLE_ATTRIBUTE_SET.put(i, 1);
        }
    }

    public static KeyVisualAttributes createIfHasStyleAttributes(TypedArray typedArray) {
        int indexCount = typedArray.getIndexCount();
        for (int i = 0; i < indexCount; i++) {
            if (STYLE_ATTRIBUTE_SET.get(typedArray.getIndex(i), 0) != 0) {
                return new KeyVisualAttributes(typedArray);
            }
        }
        return null;
    }

    private KeyVisualAttributes(TypedArray typedArray) {
        if (typedArray.hasValue(R.styleable.Keyboard_Key_keyTypeface)) {
            this.typeface = Typeface.create(typedArray.getString(R.styleable.Keyboard_Key_keyFontFamily), typedArray.getInt(R.styleable.Keyboard_Key_keyTypeface, 0));
        } else {
            this.typeface = null;
        }
        this.letterSize = ResourceConfigManager.getFractionOrInvalid(typedArray, R.styleable.Keyboard_Key_keyLetterSize);
        this.letterSizeUnit = ResourceConfigManager.getDimensionPixelSizeOrInvalid(typedArray, R.styleable.Keyboard_Key_keyLetterSize);
        this.labelSize = ResourceConfigManager.getFractionOrInvalid(typedArray, R.styleable.Keyboard_Key_keyLabelSize);
        this.labelSizeUnit = ResourceConfigManager.getDimensionPixelSizeOrInvalid(typedArray, R.styleable.Keyboard_Key_keyLabelSize);
        this.largeLetterRatio = ResourceConfigManager.getFractionOrInvalid(typedArray, R.styleable.Keyboard_Key_keyLargeLetterRatio);
        this.hintLetterRatio = ResourceConfigManager.getFractionOrInvalid(typedArray, R.styleable.Keyboard_Key_keyHintLetterRatio);
        this.shiftedLetterHintRatio = ResourceConfigManager.getFractionOrInvalid(typedArray, R.styleable.Keyboard_Key_keyShiftedLetterHintRatio);
        this.multilineHintLabelRatio = ResourceConfigManager.getFractionOrInvalid(typedArray, R.styleable.Keyboard_Key_keyMultilineHintLabelRatio);
        this.hintLabelRatio = ResourceConfigManager.getFractionOrInvalid(typedArray, R.styleable.Keyboard_Key_keyHintLabelRatio);
        this.previewTextRatio = ResourceConfigManager.getFractionOrInvalid(typedArray, R.styleable.Keyboard_Key_keyPreviewTextRatio);
        this.textColor = typedArray.getColor(R.styleable.Keyboard_Key_keyTextColor, 0);
        this.textInactivatedColor = typedArray.getColor(R.styleable.Keyboard_Key_keyTextInactivatedColor, 0);
        this.textShadowColor = typedArray.getColor(R.styleable.Keyboard_Key_keyTextShadowColor, 0);
        this.functionalTextColor = typedArray.getColor(R.styleable.Keyboard_Key_functionalTextColor, 0);
        this.hintLetterColor = typedArray.getColor(R.styleable.Keyboard_Key_keyHintLetterColor, 0);
        this.hintLabelColor = typedArray.getColor(R.styleable.Keyboard_Key_keyHintLabelColor, 0);
        this.shiftedLetterHintInactivatedColor = typedArray.getColor(R.styleable.Keyboard_Key_keyShiftedLetterHintInactivatedColor, 0);
        this.shiftedLetterHintActivatedColor = typedArray.getColor(R.styleable.Keyboard_Key_keyShiftedLetterHintActivatedColor, 0);
        this.previewTextColor = typedArray.getColor(R.styleable.Keyboard_Key_keyPreviewTextColor, 0);
        this.hintLabelVerticalAdjustment = ResourceConfigManager.getFractionOrDefault(typedArray, R.styleable.Keyboard_Key_keyHintLabelVerticalAdjustment, 0.0f);
        this.multilineHintLabelVerticalAdjustmentRatio = ResourceConfigManager.getFractionOrDefault(typedArray, R.styleable.Keyboard_Key_keyMultilineHintLabelVerticalAdjustmentRatio, 0.0f);
        this.labelOffCenterRatio = ResourceConfigManager.getFractionOrDefault(typedArray, R.styleable.Keyboard_Key_keyLabelOffCenterRatio, 0.0f);
        this.labelVerticalBaselineRatio = ResourceConfigManager.getFractionOrDefault(typedArray, R.styleable.Keyboard_Key_keyLabelVerticalBaselineRatio, 0.0f);
        this.hintLabelOffCenterRatio = ResourceConfigManager.getFractionOrDefault(typedArray, R.styleable.Keyboard_Key_keyHintLabelOffCenterRatio, 0.0f);
    }

    public KeyVisualAttributes(KeyVisualAttributes source) {
        Typeface typeface = source.typeface;
        this.typeface = typeface == null ? null : Typeface.create(typeface, typeface.getStyle());
        this.letterSize = source.letterSize;
        this.letterSizeUnit = source.letterSizeUnit;
        this.labelSize = source.labelSize;
        this.labelSizeUnit = source.labelSizeUnit;
        this.largeLetterRatio = source.largeLetterRatio;
        this.hintLetterRatio = source.hintLetterRatio;
        this.shiftedLetterHintRatio = source.shiftedLetterHintRatio;
        this.multilineHintLabelRatio = source.multilineHintLabelRatio;
        this.hintLabelRatio = source.hintLabelRatio;
        this.previewTextRatio = source.previewTextRatio;
        this.textColor = source.textColor;
        this.textInactivatedColor = source.textInactivatedColor;
        this.textShadowColor = source.textShadowColor;
        this.functionalTextColor = source.functionalTextColor;
        this.hintLetterColor = source.hintLetterColor;
        this.hintLabelColor = source.hintLabelColor;
        this.shiftedLetterHintInactivatedColor = source.shiftedLetterHintInactivatedColor;
        this.shiftedLetterHintActivatedColor = source.shiftedLetterHintActivatedColor;
        this.previewTextColor = source.previewTextColor;
        this.hintLabelVerticalAdjustment = source.hintLabelVerticalAdjustment;
        this.multilineHintLabelVerticalAdjustmentRatio = source.multilineHintLabelVerticalAdjustmentRatio;
        this.labelOffCenterRatio = source.labelOffCenterRatio;
        this.labelVerticalBaselineRatio = source.labelVerticalBaselineRatio;
        this.hintLabelOffCenterRatio = source.hintLabelOffCenterRatio;
    }
}

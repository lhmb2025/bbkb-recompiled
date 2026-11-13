package dev.bbkb.ime.core.subtypeswitcher;

import android.text.InputType;
import android.view.inputmethod.EditorInfo;

import dev.bbkb.ime.core.device.detection.RomFeatureChecker;

/** Editor input-type predicates. */
public final class InputTypeUtils {
    /** AOSP's sentinel for "the editor supplied its own action label". */
    public static final int IME_ACTION_CUSTOM_LABEL = EditorInfo.IME_MASK_ACTION + 1;

    private static final int[] SUPPRESSING_AUTO_SPACES_FIELD_VARIATIONS = {
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
    };

    private static final int[] MESSAGE_FIELD_VARIATIONS = {
            InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT,
            InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE,
            InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE,
    };

    /** Class plus variation, flags stripped. */
    private static final int MASK_CLASS_AND_VARIATION =
            InputType.TYPE_MASK_CLASS | InputType.TYPE_MASK_VARIATION;

    private InputTypeUtils() {
        // Static utility, no instances.
    }

    /** @param variation an input-type variation */
    public static boolean isUriVariation(int variation) {
        return variation == InputType.TYPE_TEXT_VARIATION_URI;
    }

    /** @param variation an input-type variation */
    public static boolean isPhoneticVariation(int variation) {
        return variation == InputType.TYPE_TEXT_VARIATION_PHONETIC;
    }

    /** @param variation an input-type variation */
    public static boolean isFilterVariation(int variation) {
        return variation == InputType.TYPE_TEXT_VARIATION_FILTER;
    }

    /** @param inputType an EditorInfo input type */
    public static boolean isVisiblePasswordInputType(int inputType) {
        return (inputType & MASK_CLASS_AND_VARIATION)
                == (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
    }

    /** @param inputType an EditorInfo input type */
    public static boolean isTextClass(int inputType) {
        return (inputType & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT;
    }

    /** @param inputType an EditorInfo input type */
    public static boolean isDateTimeInputType(int inputType) {
        final int classAndVariation = inputType & MASK_CLASS_AND_VARIATION;
        return classAndVariation == (InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_DATE)
                || classAndVariation == InputType.TYPE_CLASS_DATETIME
                || classAndVariation == (InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_TIME);
    }

    /** @param inputType an EditorInfo input type */
    public static boolean isNumberInputType(int inputType) {
        // The mask strips TYPE_NUMBER_FLAG_SIGNED/DECIMAL, so the two flagged comparisons never match.
        final int classAndVariation = inputType & MASK_CLASS_AND_VARIATION;
        return classAndVariation == (InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD)
                || classAndVariation == (InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED)
                || classAndVariation == (InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL)
                || classAndVariation == InputType.TYPE_CLASS_NUMBER;
    }

    /** @param inputType an EditorInfo input type */
    public static boolean isPhoneInputType(int inputType) {
        return inputType == InputType.TYPE_CLASS_PHONE;
    }

    /** @param inputType an EditorInfo input type */
    public static boolean isNonMultiLineTextType(int inputType) {
        return (inputType & InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE) == 0
                && (inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0;
    }

    /** @param inputType class plus variation of an input type */
    private static boolean isWebPasswordInputType(int inputType) {
        return inputType == (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD);
    }

    /** @param inputType class plus variation of an input type */
    private static boolean isNumberPasswordInputType(int inputType) {
        return inputType == (InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
    }

    /** @param inputType class plus variation of an input type */
    private static boolean isTextPasswordInputType(int inputType) {
        return inputType == (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    }

    /** @param variation an input-type variation */
    private static boolean isWebEmailAddressVariation(int variation) {
        return variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS;
    }

    /** @param variation an input-type variation */
    public static boolean isEmailVariation(int variation) {
        return variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || isWebEmailAddressVariation(variation);
    }

    /** @param inputType an EditorInfo input type */
    public static boolean isPasswordInputType(int inputType) {
        final int classAndVariation = inputType & MASK_CLASS_AND_VARIATION;
        return isTextPasswordInputType(classAndVariation)
                || isWebPasswordInputType(classAndVariation)
                || isNumberPasswordInputType(classAndVariation);
    }

    /** @param inputType an EditorInfo input type */
    public static boolean isAnyPasswordInputType(int inputType) {
        return isVisiblePasswordInputType(inputType) || isPasswordInputType(inputType);
    }

    /** @param inputType an EditorInfo input type */
    public static boolean isAutoSpaceFriendlyType(int inputType) {
        if (InputType.TYPE_CLASS_TEXT != (inputType & InputType.TYPE_MASK_CLASS)) {
            return false;
        }
        final int variation = inputType & InputType.TYPE_MASK_VARIATION;
        for (final int suppressing : SUPPRESSING_AUTO_SPACES_FIELD_VARIATIONS) {
            if (variation == suppressing) {
                return false;
            }
        }
        return true;
    }

    public static int getImeOptionsActionIdFromEditorInfo(EditorInfo editorInfo) {
        // No hasPhysicalKeyboard() clause: with it, touch-only devices inserted a newline instead
        // of submitting the editor action.
        if ((editorInfo.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
                && (isNonMultiLineTextType(editorInfo.inputType)
                    || isDoneOrNextAction(editorInfo)
                    || !RomFeatureChecker.isGoogleServicesDisabled())) {
            return EditorInfo.IME_ACTION_NONE;
        }
        if (editorInfo.actionLabel != null) {
            return IME_ACTION_CUSTOM_LABEL;
        }
        return editorInfo.imeOptions & EditorInfo.IME_MASK_ACTION;
    }

    /** @param inputType an EditorInfo input type */
    public static boolean isMessagingField(int inputType) {
        if (InputType.TYPE_CLASS_TEXT != (inputType & InputType.TYPE_MASK_CLASS)) {
            return false;
        }
        final int variation = inputType & InputType.TYPE_MASK_VARIATION;
        for (final int message : MESSAGE_FIELD_VARIATIONS) {
            if (variation == message) {
                return true;
            }
        }
        return false;
    }

    public static boolean isDoneOrNextAction(EditorInfo editorInfo) {
        final int action = editorInfo.imeOptions & EditorInfo.IME_MASK_ACTION;
        return action == EditorInfo.IME_ACTION_DONE || action == EditorInfo.IME_ACTION_NEXT;
    }
}

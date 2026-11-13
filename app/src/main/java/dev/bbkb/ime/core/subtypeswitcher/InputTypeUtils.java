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

    /** @param i an input-type variation */
    public static boolean isUriVariation(int i) {
        return i == InputType.TYPE_TEXT_VARIATION_URI;
    }

    /** @param i an input-type variation */
    public static boolean isPhoneticVariation(int i) {
        return i == InputType.TYPE_TEXT_VARIATION_PHONETIC;
    }

    /** @param i an input-type variation */
    public static boolean isFilterVariation(int i) {
        return i == InputType.TYPE_TEXT_VARIATION_FILTER;
    }

    /** @param i an EditorInfo input type */
    public static boolean isVisiblePasswordInputType(int i) {
        return (i & MASK_CLASS_AND_VARIATION)
                == (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
    }

    /** @param i an EditorInfo input type */
    public static boolean isTextClass(int i) {
        return (i & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT;
    }

    /** @param i an EditorInfo input type */
    public static boolean isDateTimeInputType(int i) {
        final int classAndVariation = i & MASK_CLASS_AND_VARIATION;
        return classAndVariation == (InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_DATE)
                || classAndVariation == InputType.TYPE_CLASS_DATETIME
                || classAndVariation == (InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_TIME);
    }

    /** @param i an EditorInfo input type */
    public static boolean isNumberInputType(int i) {
        // The mask strips TYPE_NUMBER_FLAG_SIGNED/DECIMAL, so the two flagged comparisons never match.
        final int classAndVariation = i & MASK_CLASS_AND_VARIATION;
        return classAndVariation == (InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD)
                || classAndVariation == (InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED)
                || classAndVariation == (InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL)
                || classAndVariation == InputType.TYPE_CLASS_NUMBER;
    }

    /** @param i an EditorInfo input type */
    public static boolean isPhoneInputType(int i) {
        return i == InputType.TYPE_CLASS_PHONE;
    }

    /** @param i an EditorInfo input type */
    public static boolean isNonMultiLineTextType(int i) {
        return (i & InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE) == 0
                && (i & InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0;
    }

    /** @param i class plus variation of an input type */
    private static boolean isWebPasswordInputType(int i) {
        return i == (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD);
    }

    /** @param i class plus variation of an input type */
    private static boolean isNumberPasswordInputType(int i) {
        return i == (InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
    }

    /** @param i class plus variation of an input type */
    private static boolean isTextPasswordInputType(int i) {
        return i == (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    }

    /** @param i an input-type variation */
    private static boolean isWebEmailAddressVariation(int i) {
        return i == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS;
    }

    /** @param i an input-type variation */
    public static boolean isEmailVariation(int i) {
        return i == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || isWebEmailAddressVariation(i);
    }

    /** @param i an EditorInfo input type */
    public static boolean isPasswordInputType(int i) {
        final int classAndVariation = i & MASK_CLASS_AND_VARIATION;
        return isTextPasswordInputType(classAndVariation)
                || isWebPasswordInputType(classAndVariation)
                || isNumberPasswordInputType(classAndVariation);
    }

    /** @param i an EditorInfo input type */
    public static boolean isAnyPasswordInputType(int i) {
        return isVisiblePasswordInputType(i) || isPasswordInputType(i);
    }

    /** @param i an EditorInfo input type */
    public static boolean isAutoSpaceFriendlyType(int i) {
        if (InputType.TYPE_CLASS_TEXT != (i & InputType.TYPE_MASK_CLASS)) {
            return false;
        }
        final int variation = i & InputType.TYPE_MASK_VARIATION;
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

    /** @param i an EditorInfo input type */
    public static boolean isMessagingField(int i) {
        if (InputType.TYPE_CLASS_TEXT != (i & InputType.TYPE_MASK_CLASS)) {
            return false;
        }
        final int variation = i & InputType.TYPE_MASK_VARIATION;
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

package dev.bbkb.ime.core.subtypeswitcher;

import android.os.Build;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;

import dev.bbkb.ime.core.device.detection.RomFeatureChecker;


/**
 * Editor input-type predicates.
 *
 * <p>GD-42/CT-22: this used to {@code implement InputType} (the constant-interface anti-pattern)
 * purely to import the constants, and then wrote every one of them as a bare integer anyway. The
 * values are unchanged; only their spelling is.
 */
public final class InputTypeUtils {

    /** AOSP's sentinel for "the editor supplied its own action label" — {@code IME_MASK_ACTION + 1}. */
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

    /** {@code TYPE_MASK_CLASS | TYPE_MASK_VARIATION} — class plus variation, flags stripped. */
    private static final int MASK_CLASS_AND_VARIATION =
            InputType.TYPE_MASK_CLASS | InputType.TYPE_MASK_VARIATION;

    private InputTypeUtils() {
    }

    public static boolean isUriVariation(int i) {
        return i == InputType.TYPE_TEXT_VARIATION_URI;
    }

    public static boolean isPhoneticVariation(int i) {
        return i == InputType.TYPE_TEXT_VARIATION_PHONETIC;
    }

    public static boolean isFilterVariation(int i) {
        return i == InputType.TYPE_TEXT_VARIATION_FILTER;
    }

    public static boolean isVisiblePasswordInputType(int i) {
        return (i & MASK_CLASS_AND_VARIATION)
                == (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
    }

    public static boolean isTextClass(int i) {
        return (i & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT;
    }

    public static boolean isDateTimeInputType(int i) {
        final int classAndVariation = i & MASK_CLASS_AND_VARIATION;
        return classAndVariation == (InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_DATE)
                || classAndVariation == InputType.TYPE_CLASS_DATETIME
                || classAndVariation == (InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_TIME);
    }

    public static boolean isNumberInputType(int i) {
        // NOTE (unchanged behaviour): the mask strips TYPE_NUMBER_FLAG_SIGNED/DECIMAL, which live
        // above TYPE_MASK_VARIATION, so the two flagged comparisons below can never match. They
        // are kept because removing them would be a behaviour claim this pass does not make.
        final int classAndVariation = i & MASK_CLASS_AND_VARIATION;
        return classAndVariation == (InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD)
                || classAndVariation == (InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED)
                || classAndVariation == (InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL)
                || classAndVariation == InputType.TYPE_CLASS_NUMBER;
    }

    public static boolean isPhoneInputType(int i) {
        return i == InputType.TYPE_CLASS_PHONE;
    }

    public static boolean isNonMultiLineTextType(int i) {
        return (i & InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE) == 0
                && (i & InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0;
    }

    private static boolean isWebPasswordInputType(int i) {
        return i == (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD);
    }

    private static boolean isNumberPasswordInputType(int i) {
        return i == (InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
    }

    private static boolean isTextPasswordInputType(int i) {
        return i == (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    }

    private static boolean isWebEmailAddressVariation(int i) {
        return i == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS;
    }

    public static boolean isEmailVariation(int i) {
        return i == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || isWebEmailAddressVariation(i);
    }

    public static boolean isPasswordInputType(int i) {
        final int classAndVariation = i & MASK_CLASS_AND_VARIATION;
        return isTextPasswordInputType(classAndVariation)
                || isWebPasswordInputType(classAndVariation)
                || isNumberPasswordInputType(classAndVariation);
    }

    public static boolean isAnyPasswordInputType(int i) {
        return isVisiblePasswordInputType(i) || isPasswordInputType(i);
    }

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
        // The condition deliberately does NOT include !DeviceProfile.current().hasPhysicalKeyboard():
        // with that clause this always returned IME_ACTION_NONE on non-PKB devices whenever
        // IME_FLAG_NO_ENTER_ACTION was set, so performEditorAction() returned false and the caller
        // fell through to inserting a newline instead of submitting the editor action.
        if ((editorInfo.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
                && (isNonMultiLineTextType(editorInfo.inputType)
                    || isDoneOrNextAction(editorInfo)
                    || !RomFeatureChecker.isGoogleServicesDisabled()
                    || Build.VERSION.SDK_INT <= Build.VERSION_CODES.M)) {
            return EditorInfo.IME_ACTION_NONE;
        }
        if (editorInfo.actionLabel != null) {
            return IME_ACTION_CUSTOM_LABEL;
        }
        return editorInfo.imeOptions & EditorInfo.IME_MASK_ACTION;
    }

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

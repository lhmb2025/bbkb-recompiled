package dev.bbkb.ime.core.textinput.connection;

import android.text.InputType;
import android.util.Log;
import android.view.inputmethod.EditorInfo;

import dev.bbkb.ime.core.locale.LocaleUtils;
import dev.bbkb.ime.core.subtypeswitcher.InputTypeUtils;

import java.util.Locale;
import dev.bbkb.ime.BuildConfig;



/**
 * What the focused field allows (suggestions, autocorrect, gestures, voice), derived from its
 * {@link EditorInfo}. Built on every {@code onStartInput} and held in
 * {@link dev.bbkb.ime.core.settings.util.SettingsValues}; fields are read directly.
 * When {@code editorInfo} is null or the input class is not {@code TYPE_CLASS_TEXT} (and the raw
 * inputType is not {@code 0x20}), every flag is false.
 */
public final class EditorCapabilities {

    private static final String TAG = "EditorCapabilities";

    public final String packageName;

    public final boolean noAutoCorrect;

    public final boolean isPassword;

    public final boolean shouldShowSuggestions;
    
    public final boolean shouldSupportGestureInput;

    public final boolean shouldShowChineseSuggestions;

    public final boolean shouldShowJapaneseSuggestions;

    public final boolean hasAppSpecifiedCompletions;

    public final boolean shouldInsertSpaces;

    public final boolean isMicrophoneAllowed;

    public final boolean normalVariation;

    /** {@code TYPE_TEXT_FLAG_MULTI_LINE} is set: Enter inserts a newline instead of the IME action. */
    public final boolean isMultiLine;

    /**
     * {@link EditorInfo#IME_FLAG_NO_PERSONALIZED_LEARNING} (incognito): suggestions stay on, learning stops.
     * Learning sinks read {@code DynamicLearningManager} instead, set from the live EditorInfo in
     * {@code InputLogic.updateDynamicLearningState()}, which runs before {@code loadSettings} rebuilds this.
     */
    public final boolean noPersonalizedLearning;

    private final int rawInputType;

    private final EditorInfo editorInfo;

    private final String keyboardPackageName;

    /**
     * @param keyboardPackageName namespace for {@code privateImeOptions} opt-out keys
     * @param forceSuggestions    the "Force suggestions" setting: overrides a no-suggestions field (not passwords)
     */
    public EditorCapabilities(EditorInfo editorInfo, boolean allowsAppSpecifiedCompletions, String keyboardPackageName, Locale locale, boolean forceSuggestions) {
        this.editorInfo = editorInfo;
        this.keyboardPackageName = keyboardPackageName;
        this.packageName = editorInfo != null ? editorInfo.packageName : null;
        boolean isNormalTextVariation = false;
        int inputType = editorInfo != null ? editorInfo.inputType : InputType.TYPE_NULL;
        int inputClass = inputType & InputType.TYPE_MASK_CLASS;
        this.rawInputType = inputType;
        this.noPersonalizedLearning = hasNoPersonalizedLearningFlag(editorInfo);
        this.isPassword = InputTypeUtils.isPasswordInputType(inputType) || InputTypeUtils.isVisiblePasswordInputType(inputType);
        if (inputClass != InputType.TYPE_CLASS_TEXT && inputType != InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS) {
            if (editorInfo == null) {
                if (BuildConfig.DEBUG) Log.w(TAG, "No editor info for this field. Bug?");
            } else if (inputType == InputType.TYPE_NULL) {
                if (BuildConfig.DEBUG) Log.i(TAG, "InputType.TYPE_NULL is specified");
            } else if (inputClass == InputType.TYPE_NULL) {
                if (BuildConfig.DEBUG) Log.w(TAG, String.format("Unexpected input class: inputType=0x%08x imeOptions=0x%08x", Integer.valueOf(inputType), Integer.valueOf(editorInfo.imeOptions)));
            }
            this.shouldShowSuggestions = false;
            this.shouldSupportGestureInput = false;
            this.shouldShowChineseSuggestions = false;
            this.shouldShowJapaneseSuggestions = false;
            this.noAutoCorrect = false;
            this.hasAppSpecifiedCompletions = false;
            this.shouldInsertSpaces = false;
            this.isMicrophoneAllowed = false;
            this.normalVariation = false;
            this.isMultiLine = false;
            return;
        }
        int inputVariation = inputType & InputType.TYPE_MASK_VARIATION;
        boolean hasNoSuggestionsFlag = (InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS & inputType) != 0;
        boolean isMultiLine = (InputType.TYPE_TEXT_FLAG_MULTI_LINE & inputType) != 0;
        boolean hasAutoCorrectFlag = (InputType.TYPE_TEXT_FLAG_AUTO_CORRECT & inputType) != 0;
        boolean hasAutoCompleteFlag = (InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE & inputType) != 0;
        // IME_FLAG_FORCE_ASCII disabling suggestions is intended (AOSP LatinIME does the same).
        boolean forceAscii = (editorInfo.imeOptions & EditorInfo.IME_FLAG_FORCE_ASCII) != 0;
        // "Force suggestions" overrides a no-suggestions text field, never a password.
        this.shouldShowSuggestions = (forceSuggestions && !this.isPassword)
                || !(this.isPassword || InputTypeUtils.isEmailVariation(inputVariation) || inputVariation == InputType.TYPE_TEXT_VARIATION_URI || inputVariation == InputType.TYPE_TEXT_VARIATION_FILTER || hasNoSuggestionsFlag || forceAscii);
        boolean isPasswordField = this.isPassword;
        this.shouldSupportGestureInput = !isPasswordField;
        this.shouldShowChineseSuggestions = (isPasswordField || forceAscii || !LocaleUtils.isChinese(locale)) ? false : true;
        this.shouldShowJapaneseSuggestions = (this.isPassword || forceAscii || !LocaleUtils.isJapanese(locale)) ? false : true;
        this.shouldInsertSpaces = InputTypeUtils.isAutoSpaceFriendlyType(inputType);
        
        this.isMicrophoneAllowed = !(this.isPassword || InputTypeUtils.isEmailVariation(inputVariation) || (inputVariation == InputType.TYPE_TEXT_VARIATION_URI) || isAppMicrophoneDisabled());
        // Autocorrect only when the field sets TYPE_TEXT_FLAG_AUTO_CORRECT or is multi-line
        // (stricter than stock IMEs, on purpose).
        this.noAutoCorrect = (inputVariation == InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT && !hasAutoCorrectFlag)
                || hasNoSuggestionsFlag
                || !(hasAutoCorrectFlag || isMultiLine);
        this.hasAppSpecifiedCompletions = hasAutoCompleteFlag && allowsAppSpecifiedCompletions;
        if (inputVariation != InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                && inputVariation != InputType.TYPE_TEXT_VARIATION_PASSWORD
                && inputVariation != InputType.TYPE_TEXT_VARIATION_PHONETIC
                && inputVariation != InputType.TYPE_TEXT_VARIATION_URI
                && inputVariation != InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                && inputVariation != InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
                && inputVariation != InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) {
            isNormalTextVariation = true;
        }
        this.normalVariation = isNormalTextVariation;
        this.isMultiLine = isMultiLine;
    }

    public boolean isSameInputType(EditorInfo editorInfo) {
        return editorInfo.inputType == this.rawInputType;
    }

    /** The app hid the mic key via {@code "<package>.noMicrophoneKey"} or legacy {@code "nm"}. */
    private boolean isAppMicrophoneDisabled() {
        return hasPrivateImeOption(this.keyboardPackageName, "noMicrophoneKey", this.editorInfo) || hasPrivateImeOption(null, "nm", this.editorInfo);
    }

    @Override
    public String toString() {
        Object[] objArr = new Object[12];
        objArr[0] = getClass().getSimpleName();
        objArr[1] = Integer.valueOf(this.rawInputType);
        objArr[2] = this.noAutoCorrect ? " noAutoCorrect" : "";
        objArr[3] = this.isPassword ? " password" : "";
        objArr[4] = this.shouldShowSuggestions ? " shouldShowSuggestions" : "";
        objArr[5] = this.shouldSupportGestureInput ? " shouldSupportGestureInput" : "";
        objArr[6] = this.shouldShowChineseSuggestions ? " shouldShowChineseSuggestions" : "";
        objArr[7] = this.shouldShowJapaneseSuggestions ? " shouldShowJapaneseSuggestions" : "";
        objArr[8] = this.hasAppSpecifiedCompletions ? " appSpecified" : "";
        objArr[9] = this.shouldInsertSpaces ? " insertSpaces" : "";
        objArr[10] = this.isMultiLine ? " multiLine" : "";
        objArr[11] = this.packageName;
        return String.format("%s: inputType=0x%08x%s%s%s%s%s%s%s%s%s targetApp=%s\n", objArr);
    }

    public static boolean hasNoPersonalizedLearningFlag(EditorInfo editorInfo) {
        return editorInfo != null
                && (editorInfo.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0;
    }

    /** {@code privateImeOptions} (comma-separated) contains {@code "<namespace>.<optionKey>"}, or the bare key when namespace is null. */
    public static boolean hasPrivateImeOption(String namespace, String optionKey, EditorInfo editorInfo) {
        if (editorInfo == null) {
            return false;
        }
        if (namespace != null) {
            optionKey = namespace + "." + optionKey;
        }
        return (!android.text.TextUtils.isEmpty(editorInfo.privateImeOptions) && java.util.Arrays.asList(editorInfo.privateImeOptions.split(",")).contains(optionKey));
    }

    public boolean shouldShowSuggestions() {
        return this.shouldShowSuggestions;
    }

    public boolean shouldShowChineseSuggestions() {
        return this.shouldShowChineseSuggestions;
    }

    public boolean shouldShowJapaneseSuggestions() {
        return this.shouldShowJapaneseSuggestions;
    }
}

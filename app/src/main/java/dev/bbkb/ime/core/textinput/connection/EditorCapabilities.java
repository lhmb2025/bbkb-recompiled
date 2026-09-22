package dev.bbkb.ime.core.textinput.connection;

import android.text.InputType;
import android.util.Log;
import android.view.inputmethod.EditorInfo;

import dev.bbkb.ime.core.locale.LocaleUtils;
import dev.bbkb.ime.core.subtypeswitcher.InputTypeUtils;

import java.util.Locale;
import dev.bbkb.ime.BuildConfig;



/**
 * Captures the capabilities and constraints of the currently focused editor (text field),
 * derived from its {@link EditorInfo}. An instance is created on every
 * {@code onStartInput} call and stored inside
 * {@link dev.bbkb.ime.core.settings.util.SettingsValues} so that all parts
 * of the IME can make input-behaviour decisions — autocorrect, word suggestions, gesture
 * input, voice input, language-specific strip variants, etc. — without re-inspecting the
 * raw {@link EditorInfo} themselves.
 *
 * <p>Public fields are {@code final} and intended to be read directly. The small set of
 * accessor methods ({@link #shouldShowSuggestions()}, {@link #shouldShowChineseSuggestions()}, {@link #shouldShowJapaneseSuggestions()}) exist only
 * because some call sites were compiled against method references rather than field
 * accesses.</p>
 *
 * <p>When {@code editorInfo} is {@code null} or the field's input class is not
 * {@link android.view.inputmethod.InputType#TYPE_CLASS_TEXT} (and not the special dialer
 * value {@code 0x20}), all capability flags default to {@code false}.</p>
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

    /**
     * {@code true} when the field accepts multi-line input
     * ({@link android.view.inputmethod.InputType#TYPE_TEXT_FLAG_MULTI_LINE} is set).
     * Key input handling should use this to decide whether the Enter key inserts a newline
     * or triggers the editor's IME action (Send, Search, Done, Go, Next, etc.).
     */
    public final boolean isMultiLine;

    private final int rawInputType;

    private final EditorInfo editorInfo;

    private final String keyboardPackageName;

    /**
     * Constructs an {@code EditorCapabilities} by inspecting the supplied {@link EditorInfo}.
     *
     * @param editorInfo                    the {@link EditorInfo} for the focused text field,
     *                                      or {@code null} when no field is active
     * @param allowsAppSpecifiedCompletions {@code true} if the host app may supply its own
     *                                      completion candidates (gates {@link #hasAppSpecifiedCompletions})
     * @param keyboardPackageName           the keyboard's own package name; used to namespace
     *                                      {@code privateImeOptions} opt-out keys
     * @param locale                        the current IME locale; determines whether
     *                                      Chinese/Japanese suggestion variants are enabled
     * @param forceSuggestions              Bug #3 escape hatch: when {@code true}, overrides the
     *                                      editor's "no suggestions" declaration and forces
     *                                      {@link #shouldShowSuggestions} on for normal (non-password)
     *                                      text fields. Controlled by the "Force suggestions" setting.
     */
    public EditorCapabilities(EditorInfo editorInfo, boolean allowsAppSpecifiedCompletions, String keyboardPackageName, Locale locale, boolean forceSuggestions) {
        this.editorInfo = editorInfo;
        this.keyboardPackageName = keyboardPackageName;
        this.packageName = editorInfo != null ? editorInfo.packageName : null;
        boolean isNormalTextVariation = false;
        int inputType = editorInfo != null ? editorInfo.inputType : InputType.TYPE_NULL;
        int inputClass = inputType & InputType.TYPE_MASK_CLASS;
        this.rawInputType = inputType;
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
        // TI-17 (checked against the original APK and refuted): the binding to IME_FLAG_FORCE_ASCII
        // is correct and deliberate. The original's core/l.java computes the same three suggestion
        // predicates from compat/e.a(imeOptions), which tests exactly IME_FLAG_FORCE_ASCII, and
        // AOSP LatinIME's InputAttributes does the same. Only the local's name was misleading.
        boolean forceAscii = (editorInfo.imeOptions & EditorInfo.IME_FLAG_FORCE_ASCII) != 0;
        // Bug #3 escape hatch: when the user enables "Force suggestions", override an editor's
        // no-suggestions declaration for normal text fields. Passwords are still excluded for
        // security. This only affects TYPE_CLASS_TEXT fields (the early-return branch above
        // already excluded numeric/phone/datetime classes, which never get word suggestions).
        this.shouldShowSuggestions = (forceSuggestions && !this.isPassword)
                || !(this.isPassword || InputTypeUtils.isEmailVariation(inputVariation) || inputVariation == InputType.TYPE_TEXT_VARIATION_URI || inputVariation == InputType.TYPE_TEXT_VARIATION_FILTER || hasNoSuggestionsFlag || forceAscii);
        boolean isPasswordField = this.isPassword;
        this.shouldSupportGestureInput = !isPasswordField;
        this.shouldShowChineseSuggestions = (isPasswordField || forceAscii || !LocaleUtils.isChinese(locale)) ? false : true;
        this.shouldShowJapaneseSuggestions = (this.isPassword || forceAscii || !LocaleUtils.isJapanese(locale)) ? false : true;
        this.shouldInsertSpaces = InputTypeUtils.isAutoSpaceFriendlyType(inputType);
        
        this.isMicrophoneAllowed = !(this.isPassword || InputTypeUtils.isEmailVariation(inputVariation) || (inputVariation == InputType.TYPE_TEXT_VARIATION_URI) || isAppMicrophoneDisabled());
        // Original BB design (confirmed by Smali): autocorrect is active only when the field
        // explicitly opts in via TYPE_TEXT_FLAG_AUTO_CORRECT, OR the field is multi-line.
        // Single-line fields without the flag have noAutoCorrect=true. More conservative than
        // stock Android IME behavior, but matches the original BB keyboard intent.
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

    /**
     * Returns {@code true} if the supplied {@link EditorInfo} has the same raw
     * {@code inputType} as the one this instance was constructed from. Useful for
     * detecting mid-session input-type changes that would require re-evaluating
     * capabilities.
     *
     * @param editorInfo the editor info to compare
     * @return {@code true} if the input types match
     */
    public boolean isSameInputType(EditorInfo editorInfo) {
        return editorInfo.inputType == this.rawInputType;
    }

    /**
     * Returns {@code true} if the host application has requested that the microphone
     * (voice-input) key be hidden. Checks for the qualified option
     * {@code "<keyboardPackageName>.noMicrophoneKey"} or the legacy short-form
     * {@code "nm"} in {@link EditorInfo#privateImeOptions}.
     *
     * @return {@code true} if the application has opted out of the microphone key
     */
    private boolean isAppMicrophoneDisabled() {
        return hasPrivateImeOption(this.keyboardPackageName, "noMicrophoneKey", this.editorInfo) || hasPrivateImeOption(null, "nm", this.editorInfo);
    }

    /**
     * Returns a human-readable summary of this instance for logging, listing the raw
     * {@code inputType} value and the names of all active capability flags.
     *
     * @return formatted capability string, e.g.
     *         {@code "EditorCapabilities: inputType=0x00000001 shouldShowSuggestions targetApp=com.example"}
     */
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

    /**
     * Checks whether a given option key is present in {@link EditorInfo#privateImeOptions}.
     *
     * <p>{@code privateImeOptions} is treated as a comma-separated list of keys. If
     * {@code namespace} is non-{@code null}, the fully-qualified key tested is
     * {@code "<namespace>.<optionKey>"}; otherwise {@code optionKey} is matched
     * directly.</p>
     *
     * @param namespace  optional namespace prefix (typically the keyboard package name),
     *                   or {@code null} to match the bare key
     * @param optionKey  the option key to search for
     * @param editorInfo the editor whose {@link EditorInfo#privateImeOptions} are inspected
     * @return {@code true} if the (possibly namespaced) key is found
     */
    public static boolean hasPrivateImeOption(String namespace, String optionKey, EditorInfo editorInfo) {
        if (editorInfo == null) {
            return false;
        }
        if (namespace != null) {
            optionKey = namespace + "." + optionKey;
        }
        return (!android.text.TextUtils.isEmpty(editorInfo.privateImeOptions) && java.util.Arrays.asList(editorInfo.privateImeOptions.split(",")).contains(optionKey));
    }

    /**
     * Returns {@code true} if the suggestion strip should be shown for this editor field.
     *
     * @return the value of {@link #shouldShowSuggestions}
     */
    public boolean shouldShowSuggestions() {
        return this.shouldShowSuggestions;
    }

    /**
     * Returns {@code true} if the Chinese-specific suggestion strip variant should be shown.
     *
     * @return the value of {@link #shouldShowChineseSuggestions}
     */
    public boolean shouldShowChineseSuggestions() {
        return this.shouldShowChineseSuggestions;
    }

    /**
     * Returns {@code true} if the Japanese-specific suggestion strip variant should be shown.
     *
     * @return the value of {@link #shouldShowJapaneseSuggestions}
     */
    public boolean shouldShowJapaneseSuggestions() {
        return this.shouldShowJapaneseSuggestions;
    }
}

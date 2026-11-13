package dev.bbkb.ime.keyboard.internal;

import android.text.TextUtils;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodSubtype;

import dev.bbkb.ime.core.locale.ResourceLocaleUtils;
import dev.bbkb.ime.core.subtypeswitcher.InputTypeUtils;
import dev.bbkb.ime.keyboard.KeyboardBuilder;

import java.util.Arrays;
import java.util.Locale;



public final class KeyboardId {

    public final InputMethodSubtype mSubtype;

    /**
     * Layout set the keyboard is built from ({@code qwerty}, {@code colemak}), or null if the
     * builder named none. Part of the identity because a chosen letter layout makes the same
     * subtype build a different keyboard.
     */
    public final String mKeyboardLayoutSet;

    public final Locale mLocale;

    public final int mWidth;

    public final int mHeight;

    public final int mMode;

    public final int mElementId;

    public final EditorInfo mEditorInfo;

    public final boolean mClobberSettingsKey;

    public final boolean mVoiceKeyEnabled;

    public final boolean mLanguageSwitchKeyEnabled;

    public final boolean mLanguageQuickSwitchKeyEnabled;

    public final boolean mInputBoardBarEnabled;

    public final String mCustomActionLabel;

    private final int mHashCode;

    /** Element family flags; the three families are disjoint. */
    private static final int ALPHABET = 1;
    private static final int SYMBOLS = 2;
    private static final int PKB = 4;

    /**
     * Named elements as {id, family flags, name}. Ids are the {@code elementName} enum in attrs.xml
     * (used as raw literals); names are what {@code <case latin:keyboardLayoutSetElement>} matches.
     * Unlisted ids (13..34, 112..134, 38) have no name or family. 35..37 count as ALPHABET; only
     * the PKB alphabet ids count as PKB.
     */
    private static final Object[] ELEMENTS = {
            0, ALPHABET, "alphabet",
            1, ALPHABET, "alphabetManualShifted",
            2, ALPHABET, "alphabetAutomaticShifted",
            3, ALPHABET, "alphabetShiftLocked",
            4, ALPHABET, "alphabetShiftLockShifted",
            5, SYMBOLS, "symbols0",
            6, SYMBOLS, "symbols1",
            7, SYMBOLS, "symbols2",
            8, SYMBOLS, "symbolsCustom",
            9, 0, "phone",
            10, 0, "phoneSymbols",
            11, 0, "number",
            12, 0, "emojiRecents",
            35, ALPHABET, "uri",
            36, ALPHABET, "password",
            37, ALPHABET, "postal",
            39, 0, "arrows",
            40, 0, "passwordKeeper",
            41, 0, "numberSubPanel",
            42, 0, "numberPad",
            43, 0, "unifiedInputMenuPool",
            100, PKB, "alphabetPkb",
            101, PKB, "alphabetManualShiftedPkb",
            102, PKB, "alphabetAutomaticShiftedPkb",
            103, PKB, "alphabetShiftLockedPkb",
            104, PKB, "alphabetShiftLockShiftedPkb",
            105, 0, "symbols0Pkb",
            106, 0, "symbols1Pkb",
            107, 0, "symbols2Pkb",
            108, 0, "symbolsPkbCustom",
            109, 0, "phonePkb",
            110, 0, "phoneSymbolsPkb",
            111, 0, "numberPkb",
            135, PKB, "uriPkb",
            136, PKB, "passwordPkb",
            137, PKB, "postalPkb",
            138, 0, "unifiedInputMenuPkb",
            139, 0, "arrowsPkb",
            140, 0, "passwordKeeperPkb",
            141, 0, "numberSubPanelPkb",
    };

    /** Indexed by mode; also the values {@code <case latin:mode="…">} matches against. */
    private static final String[] MODE_NAMES = {"text", "url", "email", "im", "phone", "number",
            "date", "time", "datetime", "lowerRightCornerIsEnterKey", "postal", "password"};

    /** Index of {@code id}'s row in {@link #ELEMENTS}, or -1 when the id is unnamed. */
    private static int elementRow(int id) {
        for (int row = 0; row < ELEMENTS.length; row += 3) {
            if ((Integer) ELEMENTS[row] == id) {
                return row;
            }
        }
        return -1;
    }

    private static boolean isElementInFamily(int id, int family) {
        int row = elementRow(id);
        return row >= 0 && ((Integer) ELEMENTS[row + 1] & family) != 0;
    }

    /** @param elementId element id */
    public static String elementIdToName(int elementId) {
        int row = elementRow(elementId);
        return row < 0 ? null : (String) ELEMENTS[row + 2];
    }

    /** @param mode keyboard mode */
    public static String modeName(int mode) {
        return mode >= 0 && mode < MODE_NAMES.length ? MODE_NAMES[mode] : null;
    }

    /**
     * @param elementId element id
     * @param params builder params supplying the rest of the identity
     */
    public KeyboardId(int elementId, KeyboardBuilder.Params params) {
        this.mSubtype = params.mSubtype;
        this.mKeyboardLayoutSet = params.keyboardLayoutSet();
        this.mLocale = ResourceLocaleUtils.getSubtypeLocale(this.mSubtype);
        this.mWidth = params.mKeyboardWidth;
        this.mHeight = params.mKeyboardHeight;
        this.mMode = params.mMode;
        this.mElementId = elementId;
        this.mEditorInfo = params.mEditorInfo;
        this.mClobberSettingsKey = params.mClobberSettingsKey;
        this.mVoiceKeyEnabled = params.mVoiceKeyEnabled;
        this.mLanguageSwitchKeyEnabled = params.mLanguageSwitchKeyEnabled;
        this.mLanguageQuickSwitchKeyEnabled = params.mLanguageQuickSwitchKeyEnabled;
        this.mInputBoardBarEnabled = params.mInputBoardBarEnabled;
        this.mCustomActionLabel = this.mEditorInfo.actionLabel != null ? this.mEditorInfo.actionLabel.toString() : null;
        this.mHashCode = computeHashCode(this);
    }

    private static int computeHashCode(KeyboardId keyboardId) {
        return Arrays.hashCode(new Object[]{Integer.valueOf(keyboardId.mElementId), Integer.valueOf(keyboardId.mMode), Integer.valueOf(keyboardId.mWidth), Integer.valueOf(keyboardId.mHeight), Boolean.valueOf(keyboardId.passwordInput()), Boolean.valueOf(keyboardId.mClobberSettingsKey), Boolean.valueOf(keyboardId.mVoiceKeyEnabled), Boolean.valueOf(keyboardId.mLanguageSwitchKeyEnabled), Boolean.valueOf(keyboardId.mLanguageQuickSwitchKeyEnabled), Boolean.valueOf(keyboardId.mInputBoardBarEnabled), Boolean.valueOf(keyboardId.isMultiLine()), Integer.valueOf(keyboardId.imeAction()), keyboardId.mCustomActionLabel, Boolean.valueOf(keyboardId.navigateNext()), Boolean.valueOf(keyboardId.navigatePrevious()), keyboardId.mSubtype, keyboardId.mKeyboardLayoutSet});
    }

    /**
     * Layout set {@code <case latin:keyboardLayoutSet>} matches against: the built one (so Dvorak
     * gets its {@code dvorak} bottom-row cases whatever the subtype says), else the subtype's.
     */
    public String keyboardLayoutSet() {
        return this.mKeyboardLayoutSet != null ? this.mKeyboardLayoutSet : ResourceLocaleUtils.getKeyboardLayoutSetName(this.mSubtype);
    }

    private boolean equalsInternal(KeyboardId other) {
        if (other == this) {
            return true;
        }
        return other.mElementId == this.mElementId && other.mMode == this.mMode && other.mWidth == this.mWidth && other.mHeight == this.mHeight && other.passwordInput() == passwordInput() && other.mClobberSettingsKey == this.mClobberSettingsKey && other.mVoiceKeyEnabled == this.mVoiceKeyEnabled && other.mLanguageSwitchKeyEnabled == this.mLanguageSwitchKeyEnabled && other.mLanguageQuickSwitchKeyEnabled == this.mLanguageQuickSwitchKeyEnabled && other.mInputBoardBarEnabled == this.mInputBoardBarEnabled && other.isMultiLine() == isMultiLine() && other.imeAction() == imeAction() && TextUtils.equals(other.mCustomActionLabel, this.mCustomActionLabel) && other.navigateNext() == navigateNext() && other.navigatePrevious() == navigatePrevious() && other.mSubtype.equals(this.mSubtype) && TextUtils.equals(other.mKeyboardLayoutSet, this.mKeyboardLayoutSet);
    }

    public boolean isAlphabetKeyboard() {
        return isElementInFamily(this.mElementId, ALPHABET);
    }

    public boolean isSymbolsKeyboard() {
        return isElementInFamily(this.mElementId, SYMBOLS);
    }

    public boolean isPkbKeyboard() {
        return isElementInFamily(this.mElementId, PKB);
    }

    public boolean navigateNext() {
        return (this.mEditorInfo.imeOptions & 134217728) != 0 || imeAction() == 5;
    }

    public boolean navigatePrevious() {
        return (this.mEditorInfo.imeOptions & 67108864) != 0 || imeAction() == 7;
    }

    public boolean passwordInput() {
        int i = this.mEditorInfo.inputType;
        return InputTypeUtils.isPasswordInputType(i) || InputTypeUtils.isVisiblePasswordInputType(i);
    }

    public boolean isNumberLayout() {
        return InputTypeUtils.isNumberInputType(this.mEditorInfo.inputType);
    }

    public boolean isPhoneLayout() {
        return InputTypeUtils.isPhoneInputType(this.mEditorInfo.inputType);
    }

    public boolean isDateTimeLayout() {
        return InputTypeUtils.isDateTimeInputType(this.mEditorInfo.inputType);
    }

    public boolean isMultiLine() {
        return (this.mEditorInfo.inputType & 131072) != 0;
    }

    public int imeAction() {
        return InputTypeUtils.getImeOptionsActionIdFromEditorInfo(this.mEditorInfo);
    }

    public boolean equals(Object other) {
        return (other instanceof KeyboardId) && equalsInternal((KeyboardId) other);
    }

    public int hashCode() {
        return this.mHashCode;
    }

    public String toString() {
        Locale locale = Locale.ROOT;
        Object[] objArr = new Object[16];
        objArr[0] = elementIdToName(this.mElementId);
        objArr[1] = this.mLocale;
        objArr[2] = this.mSubtype.getExtraValueOf("KeyboardLayoutSet");
        objArr[3] = Integer.valueOf(this.mWidth);
        objArr[4] = Integer.valueOf(this.mHeight);
        objArr[5] = modeName(this.mMode);
        objArr[6] = actionName(imeAction());
        objArr[7] = navigateNext() ? " navigateNext" : "";
        objArr[8] = navigatePrevious() ? " navigatePrevious" : "";
        objArr[9] = this.mClobberSettingsKey ? " clobberSettingsKey" : "";
        objArr[10] = passwordInput() ? " passwordInput" : "";
        objArr[11] = this.mVoiceKeyEnabled ? "" : " voiceDictationKeyDisabled";
        objArr[12] = this.mLanguageSwitchKeyEnabled ? " languageSwitchKeyEnabled" : "";
        objArr[13] = this.mLanguageQuickSwitchKeyEnabled ? " languageQuickSwitchKeyEnabled" : "";
        objArr[14] = this.mInputBoardBarEnabled ? "" : " inputBoardBarDisabled";
        objArr[15] = isMultiLine() ? " isMultiLine" : "";
        return String.format(locale, "[%s %s:%s %dx%d %s %s%s%s%s%s%s%s%s%s%s]", objArr);
    }

    public static boolean equivalentEditorInfo(EditorInfo editorInfo, EditorInfo otherEditorInfo) {
        if (editorInfo == null && otherEditorInfo == null) {
            return true;
        }
        return editorInfo != null && otherEditorInfo != null && editorInfo.inputType == otherEditorInfo.inputType && editorInfo.imeOptions == otherEditorInfo.imeOptions && TextUtils.equals(editorInfo.privateImeOptions, otherEditorInfo.privateImeOptions);
    }

    /**
     * True when both describe the same editor instance. {@link #equivalentEditorInfo} only answers
     * "same keyboard?"; two fields with identical options must not be conflated when deciding
     * whether an input-lifecycle callback can be discarded.
     */
    public static boolean sameEditor(EditorInfo editorInfo, EditorInfo otherEditorInfo) {
        if (editorInfo == null && otherEditorInfo == null) {
            return true;
        }
        return editorInfo != null && otherEditorInfo != null
                && equivalentEditorInfo(editorInfo, otherEditorInfo)
                && editorInfo.fieldId == otherEditorInfo.fieldId
                && TextUtils.equals(editorInfo.packageName, otherEditorInfo.packageName);
    }

    /** @param actionId IME action, or 256 for a custom action label */
    public static String actionName(int actionId) {
        return actionId == 256 ? "actionCustomLabel" : imeActionName(actionId);
    }

    private static String imeActionName(int actionId) {
        int action = actionId & EditorInfo.IME_MASK_ACTION;
        switch (action) {
            case EditorInfo.IME_ACTION_UNSPECIFIED:
                return "actionUnspecified";
            case EditorInfo.IME_ACTION_NONE:
                return "actionNone";
            case EditorInfo.IME_ACTION_GO:
                return "actionGo";
            case EditorInfo.IME_ACTION_SEARCH:
                return "actionSearch";
            case EditorInfo.IME_ACTION_SEND:
                return "actionSend";
            case EditorInfo.IME_ACTION_NEXT:
                return "actionNext";
            case EditorInfo.IME_ACTION_DONE:
                return "actionDone";
            case EditorInfo.IME_ACTION_PREVIOUS:
                return "actionPrevious";
            default:
                return "actionUnknown(" + action + ")";
        }
    }

    public boolean isTypingKeyboard() {
        int i = this.mElementId;
        return (i == 38 || i == 138) ? false : true;
    }
}

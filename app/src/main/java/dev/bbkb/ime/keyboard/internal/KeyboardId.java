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
     * Every named element as {id, family flags, name}. The ids are the {@code elementName} enum
     * values in res/values/attrs.xml and are referenced from XML and code as raw literals; the
     * names are XML-facing — they are what {@code <case latin:keyboardLayoutSetElement="…">}
     * matches against in {@code KeyboardXMLParser.matchTypedValue}. An id not listed here (the
     * 13..34 and 112..134 holes, 38) has no name and belongs to no family.
     *
     * <p>Family quirks kept as found: 35..37 (uri/password/postal) count as ALPHABET; only the
     * PKB alphabet ids count as PKB, so the PKB symbols/phone/number ids belong to no family.
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

    public static String elementIdToName(int i) {
        int row = elementRow(i);
        return row < 0 ? null : (String) ELEMENTS[row + 2];
    }

    public static String modeName(int i) {
        return i >= 0 && i < MODE_NAMES.length ? MODE_NAMES[i] : null;
    }

    public KeyboardId(int i, KeyboardBuilder.Params dVar) {
        this.mSubtype = dVar.mSubtype;
        this.mLocale = ResourceLocaleUtils.getSubtypeLocale(this.mSubtype);
        this.mWidth = dVar.mKeyboardWidth;
        this.mHeight = dVar.mKeyboardHeight;
        this.mMode = dVar.mMode;
        this.mElementId = i;
        this.mEditorInfo = dVar.mEditorInfo;
        this.mClobberSettingsKey = dVar.mClobberSettingsKey;
        this.mVoiceKeyEnabled = dVar.mVoiceKeyEnabled;
        this.mLanguageSwitchKeyEnabled = dVar.mLanguageSwitchKeyEnabled;
        this.mLanguageQuickSwitchKeyEnabled = dVar.mLanguageQuickSwitchKeyEnabled;
        this.mInputBoardBarEnabled = dVar.mInputBoardBarEnabled;
        this.mCustomActionLabel = this.mEditorInfo.actionLabel != null ? this.mEditorInfo.actionLabel.toString() : null;
        this.mHashCode = computeHashCode(this);
    }

    private static int computeHashCode(KeyboardId c0977g) {
        return Arrays.hashCode(new Object[]{Integer.valueOf(c0977g.mElementId), Integer.valueOf(c0977g.mMode), Integer.valueOf(c0977g.mWidth), Integer.valueOf(c0977g.mHeight), Boolean.valueOf(c0977g.passwordInput()), Boolean.valueOf(c0977g.mClobberSettingsKey), Boolean.valueOf(c0977g.mVoiceKeyEnabled), Boolean.valueOf(c0977g.mLanguageSwitchKeyEnabled), Boolean.valueOf(c0977g.mLanguageQuickSwitchKeyEnabled), Boolean.valueOf(c0977g.mInputBoardBarEnabled), Boolean.valueOf(c0977g.isMultiLine()), Integer.valueOf(c0977g.imeAction()), c0977g.mCustomActionLabel, Boolean.valueOf(c0977g.navigateNext()), Boolean.valueOf(c0977g.navigatePrevious()), c0977g.mSubtype});
    }

    private boolean equalsInternal(KeyboardId c0977g) {
        if (c0977g == this) {
            return true;
        }
        return c0977g.mElementId == this.mElementId && c0977g.mMode == this.mMode && c0977g.mWidth == this.mWidth && c0977g.mHeight == this.mHeight && c0977g.passwordInput() == passwordInput() && c0977g.mClobberSettingsKey == this.mClobberSettingsKey && c0977g.mVoiceKeyEnabled == this.mVoiceKeyEnabled && c0977g.mLanguageSwitchKeyEnabled == this.mLanguageSwitchKeyEnabled && c0977g.mLanguageQuickSwitchKeyEnabled == this.mLanguageQuickSwitchKeyEnabled && c0977g.mInputBoardBarEnabled == this.mInputBoardBarEnabled && c0977g.isMultiLine() == isMultiLine() && c0977g.imeAction() == imeAction() && TextUtils.equals(c0977g.mCustomActionLabel, this.mCustomActionLabel) && c0977g.navigateNext() == navigateNext() && c0977g.navigatePrevious() == navigatePrevious() && c0977g.mSubtype.equals(this.mSubtype);
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

    public boolean equals(Object obj) {
        return (obj instanceof KeyboardId) && equalsInternal((KeyboardId) obj);
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

    public static boolean equivalentEditorInfo(EditorInfo editorInfo, EditorInfo editorInfo2) {
        if (editorInfo == null && editorInfo2 == null) {
            return true;
        }
        return editorInfo != null && editorInfo2 != null && editorInfo.inputType == editorInfo2.inputType && editorInfo.imeOptions == editorInfo2.imeOptions && TextUtils.equals(editorInfo.privateImeOptions, editorInfo2.privateImeOptions);
    }

    /**
     * True when the two EditorInfos describe the *same editor instance*, not merely two editors
     * that would produce the same keyboard. {@link #equivalentEditorInfo} answers the latter —
     * which is the right question for "do I need to rebuild the layout?" but the wrong one for
     * "may I discard this input-lifecycle callback?": two different fields with identical
     * inputType/imeOptions are equivalent yet must not be conflated (audit LC-2).
     */
    public static boolean sameEditor(EditorInfo editorInfo, EditorInfo editorInfo2) {
        if (editorInfo == null && editorInfo2 == null) {
            return true;
        }
        return editorInfo != null && editorInfo2 != null
                && equivalentEditorInfo(editorInfo, editorInfo2)
                && editorInfo.fieldId == editorInfo2.fieldId
                && TextUtils.equals(editorInfo.packageName, editorInfo2.packageName);
    }

    public static String actionName(int i) {
        return i == 256 ? "actionCustomLabel" : imeActionName(i);
    }

    private static String imeActionName(int i) {
        int action = i & EditorInfo.IME_MASK_ACTION;
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

package dev.bbkb.ime.keyboard.internal;

import static dev.bbkb.ime.core.shared.StringHelper.*;

import android.text.TextUtils;

import dev.bbkb.ime.core.Constants;
import dev.bbkb.ime.keyboard.Key;

import java.util.Locale;



public class MoreKeySpec {

    private static MoreKeySpec EMPTY;

    private final int mCode;

    private final String mLabel;

    private final int mIconId;

    private final String mOutputText;

    /**
     * @param label label
     * @param iconId icon id
     * @param code code
     * @param outputText output text
     */
    public MoreKeySpec(String label, int iconId, int code, String outputText) {
        this.mLabel = label;
        this.mIconId = iconId;
        this.mCode = code;
        this.mOutputText = outputText;
    }

    /**
     * @param moreKeySpec more-key spec
     * @param needsToUpperCase true to upper-case for a shifted keyboard
     */
    public MoreKeySpec(String moreKeySpec, boolean needsToUpperCase, Locale locale) {
        if (TextUtils.isEmpty(moreKeySpec)) {
            throw new KeySpecParser.KeySpecParserError("Empty key spec");
        }
        if (needsToUpperCase && locale == null) {
            throw new IllegalArgumentException("Invalid null locale reference");
        }
        this.mLabel = toUpperCaseWithGreek(KeySpecParser.getLabel(moreKeySpec), needsToUpperCase, locale, true);
        int iM5460a = toUpperCaseCodePointWithGreek(KeySpecParser.getCode(moreKeySpec), needsToUpperCase, locale, true);
        String strM5466a = null;
        if (iM5460a != -21) {
            strM5466a = toUpperCaseWithGreek(KeySpecParser.getOutputText(moreKeySpec), needsToUpperCase, locale, true);
        } else if (moreKeySpec != null) {
            iM5460a = -4;
            strM5466a = this.mLabel;
        }
        this.mCode = iM5460a;
        this.mOutputText = strM5466a;
        this.mIconId = KeySpecParser.getIconId(moreKeySpec);
    }

    /**
     * @param keySpec key spec
     * @param labelOverride label overriding the spec's, or null
     * @param shiftedHintCode code to use when the spec has none, or -21
     * @param needsToUpperCase true to upper-case for a shifted keyboard
     */
    public MoreKeySpec(String keySpec, String labelOverride, int shiftedHintCode, boolean needsToUpperCase, Locale locale) {
        if (needsToUpperCase && locale == null) {
            throw new IllegalArgumentException("Invalid null locale reference");
        }
        this.mIconId = KeySpecParser.getIconId(keySpec);
        int iM7454c = KeySpecParser.getCode(keySpec);
        if (labelOverride != null) {
            this.mLabel = labelOverride;
        } else if (iM7454c >= 65536) {
            this.mLabel = new StringBuilder().appendCodePoint(iM7454c).toString();
        } else {
            String labelStr = KeySpecParser.getLabel(keySpec);
            this.mLabel = ((iM7454c != 32 && needsToUpperCase && labelStr != null) ? labelStr.toUpperCase(locale) : labelStr);
        }
        String outputStr = KeySpecParser.getOutputText(keySpec);
        String strM5465a = ((needsToUpperCase && outputStr != null) ? outputStr.toUpperCase(locale) : outputStr);
        if (iM7454c == -21 && TextUtils.isEmpty(strM5465a) && !TextUtils.isEmpty(this.mLabel)) {
            if ((this.mLabel).codePointCount(0, (this.mLabel).length()) != 1) {
                strM5465a = this.mLabel;
                this.mCode = -4;
            } else if (shiftedHintCode != -21) {
                this.mCode = shiftedHintCode;
            } else {
                this.mCode = this.mLabel.codePointAt(0);
            }
        } else if (iM7454c == -21 && strM5465a != null) {
            if ((strM5465a).codePointCount(0, (strM5465a).length()) == 1) {
                this.mCode = strM5465a.codePointAt(0);
                strM5465a = null;
            } else {
                this.mCode = -4;
            }
        } else {
            this.mCode = toUpperCaseCodePoint(iM7454c, needsToUpperCase, locale);
        }
        this.mOutputText = strM5465a;
    }

    public static MoreKeySpec getEmpty() {
        if (EMPTY == null) {
            EMPTY = new MoreKeySpec("", 0, -21, "");
        }
        return EMPTY;
    }

    public int hashCode() {
        int i = (((this.mCode + 31) * 31) + this.mIconId) * 31;
        String str = this.mLabel;
        int iHashCode = (i + (str == null ? 0 : str.hashCode())) * 31;
        String str2 = this.mOutputText;
        return iHashCode + (str2 != null ? str2.hashCode() : 0);
    }

    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof MoreKeySpec)) {
            return false;
        }
        MoreKeySpec c1065x = (MoreKeySpec) obj;
        return this.mCode == c1065x.mCode && this.mIconId == c1065x.mIconId && TextUtils.equals(this.mLabel, c1065x.mLabel) && TextUtils.equals(this.mOutputText, c1065x.mOutputText);
    }

    public String toString() {
        String str;
        if (this.mIconId == 0) {
            str = this.mLabel;
        } else {
            str = "!icon/" + KeyboardIconSet.getIconName(this.mIconId);
        }
        int i = this.mCode;
        String printableCode = i == -4 ? this.mOutputText : Constants.printableCode(i);
        if ((str).codePointCount(0, (str).length()) == 1 && str.codePointAt(0) == this.mCode) {
            return printableCode;
        }
        return str + "|" + printableCode;
    }

    public int getCode() {
        return this.mCode;
    }

    public int getIconId() {
        return this.mIconId;
    }

    public String getLabel() {
        return this.mLabel;
    }

    public String getOutputText() {
        return this.mOutputText;
    }

    /**
     * Builds a plain key from this spec with the keyboard's default key metrics.
     * @param x x
     * @param y y
     * @param labelFlags label flags
     */
    public Key buildKey(int x, int y, int labelFlags, KeyboardParams keyboardParams) {
        return new Key(this, MoreKeySpec.getEmpty(), KeyHintPosition.HIDDEN, null, labelFlags, 1, x, y, keyboardParams.mDefaultKeyWidth, keyboardParams.mDefaultRowHeight, keyboardParams.mHorizontalGap, keyboardParams.mVerticalGap, null, null, null, 0, 2, -1);
    }
}

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

    public MoreKeySpec(String str, int i, int i2, String str2) {
        this.mLabel = str;
        this.mIconId = i;
        this.mCode = i2;
        this.mOutputText = str2;
    }

    public MoreKeySpec(String str, boolean z, Locale locale) {
        if (TextUtils.isEmpty(str)) {
            throw new KeySpecParser.KeySpecParserError("Empty key spec");
        }
        if (z && locale == null) {
            throw new IllegalArgumentException("Invalid null locale reference");
        }
        this.mLabel = toUpperCaseWithGreek(KeySpecParser.getLabel(str), z, locale, true);
        int iM5460a = toUpperCaseCodePointWithGreek(KeySpecParser.getCode(str), z, locale, true);
        String strM5466a = null;
        if (iM5460a != -21) {
            strM5466a = toUpperCaseWithGreek(KeySpecParser.getOutputText(str), z, locale, true);
        } else if (str != null) {
            iM5460a = -4;
            strM5466a = this.mLabel;
        }
        this.mCode = iM5460a;
        this.mOutputText = strM5466a;
        this.mIconId = KeySpecParser.getIconId(str);
    }

    public MoreKeySpec(String str, String str2, int i, boolean z, Locale locale) {
        if (z && locale == null) {
            throw new IllegalArgumentException("Invalid null locale reference");
        }
        this.mIconId = KeySpecParser.getIconId(str);
        int iM7454c = KeySpecParser.getCode(str);
        if (str2 != null) {
            this.mLabel = str2;
        } else if (iM7454c >= 65536) {
            this.mLabel = new StringBuilder().appendCodePoint(iM7454c).toString();
        } else {
            String labelStr = KeySpecParser.getLabel(str);
            this.mLabel = ((iM7454c != 32 && z && labelStr != null) ? labelStr.toUpperCase(locale) : labelStr);
        }
        String outputStr = KeySpecParser.getOutputText(str);
        String strM5465a = ((z && outputStr != null) ? outputStr.toUpperCase(locale) : outputStr);
        if (iM7454c == -21 && TextUtils.isEmpty(strM5465a) && !TextUtils.isEmpty(this.mLabel)) {
            if ((this.mLabel).codePointCount(0, (this.mLabel).length()) != 1) {
                strM5465a = this.mLabel;
                this.mCode = -4;
            } else if (i != -21) {
                this.mCode = i;
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
            this.mCode = toUpperCaseCodePoint(iM7454c, z, locale);
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
     * Build a plain key from this spec at the given position, using the keyboard's default key
     * metrics. Came from the MoreKeySpec subclass, which existed only to add this and a
     * constructor that delegated straight to super (§5.9).
     */
    public Key buildKey(int i, int i2, int i3, KeyboardParams c1025ah) {
        return new Key(this, MoreKeySpec.getEmpty(), KeyHintPosition.HIDDEN, null, i3, 1, i, i2, c1025ah.mDefaultKeyWidth, c1025ah.mDefaultRowHeight, c1025ah.mHorizontalGap, c1025ah.mVerticalGap, null, null, null, 0, 2, -1);
    }
}

package dev.bbkb.ime.keyboard;

import static dev.bbkb.ime.core.shared.StringHelper.*;

import android.content.res.TypedArray;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;

import dev.bbkb.ime.R;
import dev.bbkb.ime.core.Constants;
import dev.bbkb.ime.core.settings.util.SettingsManager;
import dev.bbkb.ime.keyboard.internal.KeyStyle;
import dev.bbkb.ime.keyboard.internal.KeyHintPosition;
import dev.bbkb.ime.keyboard.internal.KeyVisualAttributes;
import dev.bbkb.ime.keyboard.internal.KeyboardRow;
import dev.bbkb.ime.keyboard.internal.MoreKeySpec;
import dev.bbkb.ime.keyboard.internal.KeyboardIconSet;
import dev.bbkb.ime.keyboard.internal.MoreKeySpec;
import dev.bbkb.ime.keyboard.internal.KeySpecParser;
import dev.bbkb.ime.keyboard.internal.KeyboardParams;
import dev.bbkb.ime.keyboard.internal.KeyDrawParams;
import com.blackberry.nuanceshim.NuanceSDK;

import java.util.Arrays;
import java.util.Locale;



public class Key implements Comparable<Key> {

    /** {@code keyLabelFlags} bit: this key must not draw a hint label. */
    private static final int LABEL_FLAGS_DISABLE_HINT_LABEL = 0x40000000;

    /** {@code keyLabelFlags} bit: do not merge the layout's additional moreKeys into this key. */
    private static final int LABEL_FLAGS_DISABLE_ADDITIONAL_MORE_KEYS = 0x20000000;

    /** {@code moreKeys} bit: the more-keys panel draws labels, not just icons ({@code !hasLabels!}). */
    private static final int MORE_KEYS_FLAGS_HAS_LABELS = 0x40000000;

    /** {@code moreKeys} bit: the more-keys panel draws dividers ({@code !needsDividers!}). */
    private static final int MORE_KEYS_FLAGS_NEEDS_DIVIDERS = 0x20000000;

    /** {@code moreKeys} bit: {@code !noPanelAutoMoreKey!}. */
    private static final int MORE_KEYS_FLAGS_NO_PANEL_AUTO_MORE_KEY = 0x10000000;

    private final MoreKeySpec keySpec;

    public int backgroundType;

    protected boolean isEnabled;

    public String outputText;

    public final int scanCode;

    private final String hintLabel;

    private final int labelFlags;

    private final int width; // x coordinate

    private final int height; // y coordinate

    private final int centerX;

    private final int centerY;

    private final Rect hitBox;

    private final MoreKeySpec[] moreKeys;

    private final String[] keyLabelSet;

    private final int moreKeysFlags;

    private final int actionFlags;

    private final KeyVisualAttributes keyData;

    private final LongPressKeyData longPressData;

    private final int hashCode;

    private boolean isPressed;

    private boolean isActive;

    private boolean isHighlighted;

    private static boolean shouldPreserveCase(int i, int i2) {
        if ((i & 65536) != 0) {
            return false;
        }
        switch (i2) {
            case 1:  // alphabetManualShifted
            case 2:  // alphabetAutomaticShifted
            case 3:  // alphabetShiftLocked
            case 4:  // alphabetShiftLockShifted
                return true;
        }
        return false;
    }

    
    private static final class LongPressKeyData {

        public final MoreKeySpec longPressKeySpec;

        public final KeyHintPosition longPressKeyHintPosition;

        public final int longPressCode;

        public final int activeIconId;

        public final int disabledIconId;

        public final int[] backgroundIconIds = new int[10];

        public final int visualInsetsLeft;

        public final int visualInsetsRight;

        private LongPressKeyData(MoreKeySpec c1065x, KeyHintPosition enumC0963c, int i, int i2, int i3, int[] iArr, int i4, int i5) {
            this.longPressKeySpec = c1065x;
            this.longPressKeyHintPosition = enumC0963c;
            this.longPressCode = i;
            this.activeIconId = i2;
            this.disabledIconId = i3;
            for (int i6 = 0; i6 < iArr.length; i6++) {
                this.backgroundIconIds[i6] = iArr[i6];
            }
            this.visualInsetsLeft = i4;
            this.visualInsetsRight = i5;
        }

        public static LongPressKeyData create(MoreKeySpec c1065x, KeyHintPosition enumC0963c, int i, int i2, int i3, int[] iArr, int i4, int i5) {
            if ((c1065x == null || c1065x.equals(MoreKeySpec.getEmpty())) && enumC0963c == KeyHintPosition.HIDDEN) {
                if (i == -21 && i2 == 0 && i4 == 0 && i5 == 0) {
                    boolean z = false;
                    for (int i6 : iArr) {
                        if (i6 != 0) {
                            z = true;
                        }
                    }
                    if (!z) {
                        return null;
                    }
                }
            }
            return new LongPressKeyData(c1065x, enumC0963c, i, i2, i3, iArr, i4, i5);
        }
    }

    public Key(MoreKeySpec c1065x, MoreKeySpec c1065x2, KeyHintPosition enumC0963c, String str, int i, int i2, int i3, int i4, int i5, int i6, int i7, int i8, String str2, MoreKeySpec[] c1034aqArr, String[] strArr, int i9, int i10, int i11) {
        int i12;
        this.hitBox = new Rect();
        this.isEnabled = true;
        this.isActive = true;
        this.isHighlighted = false;
        if (c1065x == null) {
            throw new IllegalArgumentException("Invalid null keySpec argument.");
        }
        this.keySpec = c1065x;
        this.height = i6 - i8;
        this.width = i5 - i7;
        this.hintLabel = str;
        this.labelFlags = i;
        this.backgroundType = i2;
        this.actionFlags = i10;
        if (c1034aqArr != null) {
            this.moreKeys = new MoreKeySpec[c1034aqArr.length];
            System.arraycopy(c1034aqArr, 0, this.moreKeys, 0, c1034aqArr.length);
        } else {
            this.moreKeys = null;
        }
        if (strArr != null) {
            this.keyLabelSet = new String[strArr.length];
            System.arraycopy(strArr, 0, this.keyLabelSet, 0, strArr.length);
            i12 = i9;
        } else {
            this.keyLabelSet = null;
            i12 = i9;
        }
        this.moreKeysFlags = i12;
        this.longPressData = LongPressKeyData.create(c1065x2, enumC0963c, -21, 0, 0, new int[10], 0, 0);
        this.isActive = this.keySpec.getCode() != -21;
        this.centerX = (i7 / 2) + i3;
        this.centerY = i4;
        this.hitBox.set(i3, i4, i3 + i5 + 1, i4 + i6);
        this.keyData = null;
        this.outputText = str2;
        this.scanCode = i11;
        this.hashCode = calculateHashCode(this);
    }

    public Key(String str, String str2, KeyHintPosition enumC0963c, TypedArray typedArray, KeyStyle abstractC1067z, KeyboardParams c1025ah, KeyboardRow c1027aj) throws NumberFormatException {
        int iCodePointAt;
        String strM6198T;
        String strM6173a;
        MoreKeySpec[] c1034aqArr;
        MoreKeySpec c1065x;
        this.hitBox = new Rect();
        this.isEnabled = true;
        this.isActive = true;
        this.isHighlighted = false;
        float f = isSpacerKey() ? 0.0f : c1025ah.mHorizontalGap;
        int iM7177a = c1027aj.getRowHeight();
        float fM7180b = c1027aj.getKeyX(typedArray);
        float fM7181b = isSpacerKey() ? c1027aj.getSpacerWidth(typedArray, fM7180b) : c1027aj.getKeyWidth(typedArray, fM7180b);
        int iM7188g = c1027aj.getY();
        float f2 = fM7180b + fM7181b;
        if (Math.round(f2) > f2) {
            this.width = Math.round((fM7181b - f) + 0.5f);
        } else {
            this.width = Math.round(fM7181b - f);
        }
        this.centerX = Math.round((f / 2.0f) + fM7180b);
        this.centerY = iM7188g;
        int iRound = Math.round(typedArray.getFraction(R.styleable.Keyboard_Key_keyHeight, iM7177a, iM7177a, iM7177a));
        this.height = iRound - c1025ah.mVerticalGap;
        this.hitBox.set(Math.round(fM7180b), iM7188g, Math.round(f2) + 1, iRound + iM7188g);
        c1027aj.setXPos(f2);
        this.backgroundType = abstractC1067z.getInt(typedArray, R.styleable.Keyboard_Key_backgroundType, c1027aj.getDefaultBackgroundType());
        int i = c1025ah.mBaseWidth;
        int iRound2 = Math.round(typedArray.getFraction(R.styleable.Keyboard_Key_visualInsetsLeft, i, i, 0.0f));
        int iRound3 = Math.round(typedArray.getFraction(R.styleable.Keyboard_Key_visualInsetsRight, i, i, 0.0f));
        int[] iArrM7451a = KeySpecParser.getIconIds(abstractC1067z.parseStringArray(typedArray, R.styleable.Keyboard_Key_keyIconsBackgroundType));
        this.labelFlags = c1027aj.getDefaultKeyLabelFlags() | abstractC1067z.getFlags(typedArray, R.styleable.Keyboard_Key_keyLabelFlags);
        boolean zM6177c = shouldPreserveCase(this.labelFlags, c1025ah.mId.mElementId);
        Locale locale = c1025ah.mId.mLocale;
        // Named rather than positional: these two were passed to LongPressKeyData.create() in the
        // wrong order, so activeIconId held the DISABLED icon and disabledIconId held the ACTIVE
        // one. getIcon() draws disabledIconId when a key is inactive, so a disabled key rendered
        // its ACTIVE icon — looking normal, or brighter than normal, while being untappable. That
        // is why a disabled emoji/voice key in a URL bar was indistinguishable from a working one.
        final int disabledIconId = KeySpecParser.getIconId(abstractC1067z.getString(typedArray, R.styleable.Keyboard_Key_keyIconDisabled));
        final int activeIconId = KeySpecParser.getIconId(abstractC1067z.getString(typedArray, R.styleable.Keyboard_Key_keyIconActive));
        String str3 = (this.labelFlags & 262144) != 0 ? c1025ah.mId.mCustomActionLabel : null;
        if ((this.labelFlags & LABEL_FLAGS_DISABLE_HINT_LABEL) != 0) {
            this.hintLabel = null;
        } else {
            String hintLabelValue = abstractC1067z.getString(typedArray, R.styleable.Keyboard_Key_keyHintLabel);
            this.hintLabel = (zM6177c && hintLabelValue != null) ? hintLabelValue.toUpperCase(locale) : hintLabelValue;
        }
        if (hasShiftedLetterHint() && isShiftedLetterActivated()) {
            String str4 = this.hintLabel;
            iCodePointAt = str4 != null ? str4.codePointAt(0) : -21;
        } else {
            iCodePointAt = -21;
        }
        this.keySpec = new MoreKeySpec(str, str3, iCodePointAt, zM6177c, locale);
        int iMo7101c = abstractC1067z.getFlags(typedArray, R.styleable.Keyboard_Key_keyActionFlags);
        String[] strArrMo7099a = abstractC1067z.getStringArray(typedArray, R.styleable.Keyboard_Key_moreKeys, true);
        int iMo7097a = abstractC1067z.getInt(typedArray, R.styleable.Keyboard_Key_maxMoreKeysColumn, c1025ah.mMaxMoreKeysKeyboardColumn) | 0;
        int iM7278a = KeySpecParser.getIntValue(strArrMo7099a, "!autoColumnOrder!", -1);
        iMo7097a = iM7278a > 0 ? (iM7278a & 255) | 256 : iMo7097a;
        int iM7278a2 = KeySpecParser.getIntValue(strArrMo7099a, "!fixedColumnOrder!", -1);
        iMo7097a = iM7278a2 > 0 ? (iM7278a2 & 255) | 768 : iMo7097a;
        iMo7097a = KeySpecParser.getBooleanValue(strArrMo7099a, "!hasLabels!") ? iMo7097a | MORE_KEYS_FLAGS_HAS_LABELS : iMo7097a;
        iMo7097a = KeySpecParser.getBooleanValue(strArrMo7099a, "!needsDividers!") ? iMo7097a | MORE_KEYS_FLAGS_NEEDS_DIVIDERS : iMo7097a;
        this.moreKeysFlags = KeySpecParser.getBooleanValue(strArrMo7099a, "!noPanelAutoMoreKey!") ? iMo7097a | MORE_KEYS_FLAGS_NO_PANEL_AUTO_MORE_KEY : iMo7097a;
        String[] strArrM7283a = KeySpecParser.insertAdditionalMoreKeys(strArrMo7099a, (this.labelFlags & LABEL_FLAGS_DISABLE_ADDITIONAL_MORE_KEYS) != 0 ? null : abstractC1067z.parseStringArray(typedArray, R.styleable.Keyboard_Key_additionalMoreKeys));
        if (getCode() > 0) {
            String strM5463a = new String(Character.toChars(getCode()));
            String strM6173a2 = toUpperCaseLabel(strM5463a, zM6177c, locale, str);
            String str5 = zM6177c ? strM5463a : null;
            strM6173a = strM6173a2;
            strM6198T = str5;
        } else if (getCode() == -4) {
            strM6173a = toUpperCaseLabel(getKeySpecOutputText(), zM6177c, locale, str);
            strM6198T = zM6177c ? getKeySpecOutputText() : null;
        } else {
            strM6198T = null;
            strM6173a = null;
        }
        int i2 = strM6173a != null ? 1 : 0;
        int length = strArrM7283a != null ? strArrM7283a.length : 0;
        if (length > 0 || i2 > 0) {
            iMo7101c |= 8;
            MoreKeySpec[] c1034aqArr2 = new MoreKeySpec[i2 + length];
            if (strM6173a != null) {
                c1034aqArr2[0] = new MoreKeySpec(strM6173a, false, locale);
            }
            int i3 = i2;
            for (int i4 = 0; i4 < length; i4++) {
                c1034aqArr2[i3] = new MoreKeySpec(strArrM7283a[i4], zM6177c, locale);
                if (strM6198T == null || !locale.getLanguage().equals("el") || !c1034aqArr2[i3].getLabel().equals(strM6198T)) {
                    i3++;
                }
            }
            if (i3 != c1034aqArr2.length) {
                c1034aqArr = new MoreKeySpec[i3];
                System.arraycopy(c1034aqArr2, 0, c1034aqArr, 0, i3);
            } else {
                c1034aqArr = c1034aqArr2;
            }
            this.moreKeys = c1034aqArr;
        } else {
            this.moreKeys = null;
        }
        this.keyLabelSet = abstractC1067z.getStringArray(typedArray, R.styleable.Keyboard_Key_multitapKeys, true);
        int iM5459a = toUpperCaseCodePoint(KeySpecParser.getCodeFromSpec(abstractC1067z.getString(typedArray, R.styleable.Keyboard_Key_altCode), -21), zM6177c, locale);
        int iMo7097a2 = abstractC1067z.getInt(typedArray, R.styleable.Keyboard_Key_keyMapScanCode, -1);
        if (str2 == null) {
            c1065x = MoreKeySpec.getEmpty();
        } else {
            iMo7101c |= 8;
            c1065x = new MoreKeySpec(str2, zM6177c, locale);
        }
        // create(..., int activeIconId, int disabledIconId, ...) — order matters, see above.
        this.longPressData = LongPressKeyData.create(c1065x, enumC0963c, iM5459a, activeIconId, disabledIconId, iArrM7451a, iRound2, iRound3);
        this.keyData = KeyVisualAttributes.createIfHasStyleAttributes(typedArray);
        this.actionFlags = iMo7101c;
        this.isActive = this.keySpec.getCode() != -21;
        this.outputText = abstractC1067z.getString(typedArray, R.styleable.Keyboard_Key_keyStyle);
        this.scanCode = iMo7097a2;
        this.hashCode = calculateHashCode(this);
    }

    protected Key(Key key) {
        this.hitBox = new Rect();
        this.isEnabled = true;
        this.isActive = true;
        this.isHighlighted = false;
        this.keySpec = key.keySpec;
        this.hintLabel = key.hintLabel;
        this.labelFlags = key.labelFlags;
        this.width = key.width;
        this.height = key.height;
        this.centerX = key.centerX;
        this.centerY = key.centerY;
        this.hitBox.set(key.hitBox);
        this.moreKeys = key.moreKeys == null ? null : (MoreKeySpec[]) key.moreKeys.clone();
        this.keyLabelSet = key.keyLabelSet;
        this.moreKeysFlags = key.moreKeysFlags;
        this.backgroundType = key.backgroundType;
        this.actionFlags = key.actionFlags;
        this.keyData = key.keyData;
        this.longPressData = key.longPressData;
        this.hashCode = key.hashCode;
        this.isPressed = key.isPressed;
        this.isActive = key.isActive;
        this.isHighlighted = key.isHighlighted;
        this.scanCode = -1;
    }

    /**
     * Clones {@code prototype} (spec, code, icons, moreKeys, flags) onto the position and
     * size of {@code slot}. Used by the UIM bar to reorder its toggle keys: prototypes are
     * parsed from XML — the only way a key keeps its keyIconActive/keyIconDisabled pair —
     * and only the geometry is taken from the slot being filled.
     */
    public Key(Key prototype, Key slot) {
        this.hitBox = new Rect();
        this.isEnabled = true;
        this.isHighlighted = false;
        this.keySpec = prototype.keySpec;
        this.hintLabel = prototype.hintLabel;
        this.labelFlags = prototype.labelFlags;
        this.width = slot.width;
        this.height = slot.height;
        this.centerX = slot.centerX;
        this.centerY = slot.centerY;
        this.hitBox.set(slot.hitBox);
        this.moreKeys = prototype.moreKeys == null ? null : (MoreKeySpec[]) prototype.moreKeys.clone();
        this.keyLabelSet = prototype.keyLabelSet;
        this.moreKeysFlags = prototype.moreKeysFlags;
        this.backgroundType = prototype.backgroundType;
        this.actionFlags = prototype.actionFlags;
        this.keyData = prototype.keyData;
        this.longPressData = prototype.longPressData;
        this.outputText = prototype.outputText;
        this.scanCode = prototype.scanCode;
        this.isActive = prototype.keySpec.getCode() != -21;
        this.hashCode = calculateHashCode(this);
    }

    public Key(Key key, MoreKeySpec c1065x, MoreKeySpec[] c1034aqArr) {
        this.hitBox = new Rect();
        this.isEnabled = true;
        this.isActive = true;
        this.isHighlighted = false;
        this.keySpec = c1065x;
        this.hintLabel = key.hintLabel;
        this.labelFlags = key.labelFlags;
        this.width = key.width;
        this.height = key.height;
        this.centerX = key.centerX;
        this.centerY = key.centerY;
        this.hitBox.set(key.hitBox);
        this.moreKeys = c1034aqArr == null ? null : (MoreKeySpec[]) c1034aqArr.clone();
        this.keyLabelSet = key.keyLabelSet;
        this.moreKeysFlags = key.moreKeysFlags;
        this.backgroundType = key.backgroundType;
        this.actionFlags = key.actionFlags;
        this.keyData = key.keyData;
        this.longPressData = key.longPressData;
        this.hashCode = key.hashCode;
        this.isPressed = key.isPressed;
        this.isActive = key.isActive;
        this.isHighlighted = key.isHighlighted;
        this.scanCode = -1;
    }

    private static int calculateHashCode(Key key) {
        Object[] objArr = new Object[13];
        int iHashCode = 0;
        objArr[0] = Integer.valueOf(key.centerX);
        objArr[1] = Integer.valueOf(key.centerY);
        objArr[2] = Integer.valueOf(key.width);
        objArr[3] = Integer.valueOf(key.height);
        objArr[4] = Integer.valueOf(key.keySpec.hashCode());
        objArr[5] = key.hintLabel;
        objArr[6] = Integer.valueOf(key.backgroundType);
        objArr[7] = Integer.valueOf(Arrays.hashCode(key.moreKeys));
        objArr[8] = Integer.valueOf(Arrays.hashCode(key.keyLabelSet));
        objArr[9] = key.getKeySpecOutputText();
        objArr[10] = Integer.valueOf(key.actionFlags);
        objArr[11] = Integer.valueOf(key.labelFlags);
        LongPressKeyData c0942b = key.longPressData;
        if (c0942b != null && c0942b.longPressKeySpec != null) {
            iHashCode = key.longPressData.longPressKeySpec.hashCode();
        }
        objArr[12] = Integer.valueOf(iHashCode);
        return Arrays.hashCode(objArr);
    }

    private boolean equalsInternal(Key key) {
        if (this == key) {
            return true;
        }
        return key.centerX == this.centerX && key.centerY == this.centerY && key.width == this.width && key.height == this.height && this.keySpec.equals(key.keySpec) && TextUtils.equals(key.hintLabel, this.hintLabel) && key.backgroundType == this.backgroundType && Arrays.equals(key.moreKeys, this.moreKeys) && Arrays.equals(key.keyLabelSet, this.keyLabelSet) && key.actionFlags == this.actionFlags && key.labelFlags == this.labelFlags;
    }

    @Override // java.lang.Comparable
    public int compareTo(Key key) {
        if (equalsInternal(key)) {
            return 0;
        }
        return this.hashCode > key.hashCode ? 1 : -1;
    }

    public int hashCode() {
        return this.hashCode;
    }

    public boolean equals(Object obj) {
        return (obj instanceof Key) && equalsInternal((Key) obj);
    }

    public String toString() {
        return getCodeString() + " " + getX() + "," + getY() + " " + getWidth() + "x" + getHeight();
    }

    public String getCodeString() {
        int iM6232c = getCode();
        if (iM6232c == -4) {
            return getKeySpecOutputText();
        }
        return Constants.printableCode(iM6232c);
    }

    public MoreKeySpec getKeySpec() {
        return this.keySpec;
    }

    public int getCode() {
        return this.keySpec != null ? this.keySpec.getCode() : 0;
    }

    public int getLongPressCode() {
        LongPressKeyData c0942b = this.longPressData;
        if (c0942b != null) {
            return c0942b.longPressKeySpec.getCode();
        }
        return -21;
    }

    public String getLabel() {
        return this.keySpec != null ? this.keySpec.getLabel() : null;
    }

    public String getLongPressLabel() {
        LongPressKeyData c0942b = this.longPressData;
        if (c0942b != null) {
            return c0942b.longPressKeySpec.getLabel();
        }
        return null;
    }

    public String getOutputText() {
        return this.outputText;
    }

    public String getHintLabel() {
        return this.hintLabel;
    }

    public MoreKeySpec[] getMoreKeys() {
        return this.moreKeys;
    }

    public int getActionFlags() {
        return this.actionFlags;
    }

    public int getLabelFlags() {
        return this.labelFlags;
    }

    public int getBackgroundType() {
        return this.backgroundType;
    }

    public String[] getKeyLabelSet() {
        String[] strArr = this.keyLabelSet;
        if (strArr != null) {
            return (String[]) Arrays.copyOf(strArr, strArr.length);
        }
        return null;
    }

    public void setLeftEdge(KeyboardParams c1025ah) {
        this.hitBox.left = c1025ah.mLeftPadding;
    }

    public void setRightEdge(KeyboardParams c1025ah) {
        this.hitBox.right = c1025ah.mOccupiedWidth - c1025ah.mRightPadding;
    }

    public void setTopEdge(KeyboardParams c1025ah) {
        this.hitBox.top = c1025ah.mTopPadding;
    }

    public void setBottomEdge(KeyboardParams c1025ah) {
        this.hitBox.bottom = c1025ah.mOccupiedHeight + c1025ah.mBottomPadding;
    }

    public final boolean isSpacerKey() {
        return this instanceof SpacerKey;
    }

    public final boolean isEnabled() {
        return this.isEnabled;
    }

    public final boolean isFunctionalKey() {
        return this.backgroundType == 5;
    }

    public final boolean isActionKey() {
        return this.backgroundType == 1;
    }

    public final boolean isShiftKey() {
        return getCode() == -1;
    }

    public final boolean isControlKey() {
        return SettingsManager.getInstance().getSettingsValues().isVkbControlModeEnabled && getCode() == -3;
    }

    public final boolean isModifierKey() {
        int iM6232c = getCode();
        return iM6232c == -1 || iM6232c == -3 || iM6232c == -15;
    }

    public final boolean isRepeatable() {
        return (this.actionFlags & 1) != 0;
    }

    public final boolean isNoKeyPreview() {
        return (this.actionFlags & 2) != 0;
    }

    public final boolean isAltCodeWhileTyping() {
        return (this.actionFlags & 4) != 0;
    }

    public final boolean hasMoreKeys() {
        return (this.actionFlags & 8) != 0 && (this.labelFlags & 131072) == 0;
    }

    public KeyVisualAttributes getKeyData() {
        return this.keyData;
    }

    public final Typeface getTypeface(KeyDrawParams c1061t) {
        int i = this.labelFlags & 48;
        if (i == 16) {
            return Typeface.DEFAULT;
        }
        if (i == 32) {
            return Typeface.MONOSPACE;
        }
        return c1061t.typeface;
    }

    public final int getLabelSize(KeyDrawParams c1061t) {
        int i = this.labelFlags & 448;
        if (i == 64) {
            return c1061t.largeLetterSizePixels;
        }
        if (i == 128) {
            return c1061t.letterSizePixels;
        }
        if (i == 192) {
            return c1061t.labelSizePixels;
        }
        if (i != 320) {
            return getLabel().codePointCount(0, getLabel().length()) == 1 ? c1061t.letterSizePixels : c1061t.labelSizePixels;
        }
        return c1061t.hintLabelSizePixels;
    }

    public final int getLabelColor(KeyDrawParams c1061t) {
        if ((this.labelFlags & 524288) != 0) {
            return c1061t.getFunctionalTextColor();
        }
        return isShiftedLetterActivated() ? c1061t.getTextInactivatedColor() : c1061t.getTextColor();
    }

    public final int resolveHintLabelSize(KeyDrawParams c1061t) {
        if (hasHintLabel()) {
            return c1061t.hintLabelSizePixels;
        }
        if (hasShiftedLetterHint()) {
            return c1061t.shiftedLetterHintSizePixels;
        }
        if (hasMultilineHintLabel()) {
            return c1061t.multilineHintLabelSizePixels;
        }
        return c1061t.hintLetterSizePixels;
    }

    public final int resolveHintLabelColor(KeyDrawParams c1061t) {
        if (hasHintLabel()) {
            return c1061t.getHintLabelColor();
        }
        if (hasShiftedLetterHint()) {
            return isShiftedLetterActivated() ? c1061t.getShiftedLetterHintActivatedColor() : c1061t.getShiftedLetterHintInactivatedColor();
        }
        return c1061t.getHintLetterColor();
    }

    public final String getVisibleLabel() {
        return isShiftedLetterActivated() ? this.hintLabel : getLabel();
    }

    private boolean hasSingleLetterVisibleLabel() {
        return (this.labelFlags & 128) != 0 || getVisibleLabel().codePointCount(0, getVisibleLabel().length()) == 1;
    }

    public final int getLetterSize(KeyDrawParams c1061t) {
        if (hasSingleLetterVisibleLabel()) {
            return c1061t.previewTextSizePixels;
        }
        return c1061t.letterSizePixels;
    }

    public Typeface getLetterTypeface(KeyDrawParams c1061t) {
        if (hasSingleLetterVisibleLabel()) {
            return getTypeface(c1061t);
        }
        return Typeface.DEFAULT_BOLD;
    }

    public final boolean isAlignHintLabelToBottom(int i) {
        return ((i | this.labelFlags) & 2) != 0;
    }

    public final boolean isAlignIconToBottom() {
        return (this.labelFlags & 4) != 0;
    }

    public final boolean isAlignLabelOffCenter() {
        return (this.labelFlags & 8) != 0;
    }

    public final boolean hasLongPressKey() {
        LongPressKeyData c0942b = this.longPressData;
        return (c0942b == null || c0942b.longPressKeySpec == null || this.longPressData.longPressKeySpec.equals(MoreKeySpec.getEmpty())) ? false : true;
    }

    public KeyHintPosition getLongPressKeyHintPosition() {
        LongPressKeyData c0942b = this.longPressData;
        return c0942b != null ? c0942b.longPressKeyHintPosition : KeyHintPosition.HIDDEN;
    }

    public final boolean hasPopupHint() {
        return (this.labelFlags & 512) != 0;
    }

    public final boolean hasShiftedLetterHint() {
        return ((this.labelFlags & NuanceSDK.MAX_CONTEXT_LENGTH) == 0 || TextUtils.isEmpty(this.hintLabel)) ? false : true;
    }

    public final boolean hasHintLabel() {
        return (this.labelFlags & 2048) != 0;
    }

    public final boolean hasMultilineHintLabel() {
        return ((this.labelFlags & 8192) == 0 || TextUtils.isEmpty(this.hintLabel)) ? false : true;
    }

    public final boolean needsAutoXScale() {
        return (this.labelFlags & 16384) != 0;
    }

    public final boolean needsAutoScale() {
        return (this.labelFlags & 49152) == 49152;
    }

    public final boolean isFromCustomActionLabel() {
        return (this.labelFlags & 262144) != 0;
    }

    private final boolean isShiftedLetterActivated() {
        return ((this.labelFlags & 131072) == 0 || TextUtils.isEmpty(this.hintLabel)) ? false : true;
    }

    public final int getMoreKeysColumnNumber() {
        return this.moreKeysFlags & 255;
    }

    public final boolean isMoreKeysFixedColumn() {
        return (this.moreKeysFlags & 256) != 0;
    }

    public final boolean isMoreKeysFixedOrder() {
        return (this.moreKeysFlags & 512) != 0;
    }

    public final boolean hasLabelsInMoreKeys() {
        return (this.moreKeysFlags & MORE_KEYS_FLAGS_HAS_LABELS) != 0;
    }

    public final int getMoreKeyLabelFlags() {
        return (hasLabelsInMoreKeys() ? 192 : 128) | 16384;
    }

    public final boolean needsDividersInMoreKeys() {
        return (this.moreKeysFlags & MORE_KEYS_FLAGS_NEEDS_DIVIDERS) != 0;
    }

    public final boolean hasBackgroundTypeAction() {
        return (this.moreKeysFlags & MORE_KEYS_FLAGS_NO_PANEL_AUTO_MORE_KEY) != 0;
    }

    public int getMoreKeysFlags() {
        return this.moreKeysFlags;
    }

    public final String getKeySpecOutputText() {
        return this.keySpec != null ? this.keySpec.getOutputText() : null;
    }

    public final int getLongPressActionCode() {
        LongPressKeyData c0942b = this.longPressData;
        if (c0942b != null) {
            return c0942b.longPressCode;
        }
        return -21;
    }

    public int getIconId() {
        return this.keySpec != null ? this.keySpec.getIconId() : 0;
    }

    public int getLongPressIconId() {
        LongPressKeyData c0942b = this.longPressData;
        if (c0942b != null) {
            return c0942b.longPressKeySpec.getIconId();
        }
        return 0;
    }

    public Drawable getIcon(KeyboardIconSet c1024ag) {
        return c1024ag.getIconDrawable(getLongPressIconId());
    }

    public Drawable getIcon(KeyboardIconSet c1024ag, int i) {
        LongPressKeyData c0942b = this.longPressData;
        // Enabled: always the default icon — the active/inactive distinction is opacity
        // (see applyIconTint), never a different icon. Disabled: the disabled variant.
        // activeIconId is deliberately NOT read here; see the note on the constructor.
        int iconId = this.isActive ? getIconId() : (c0942b != null ? c0942b.disabledIconId : 0);
        Drawable drawableM7165b = c1024ag.getIconDrawable(iconId);
        if (drawableM7165b != null) {
            drawableM7165b.setAlpha(i);
        }
        return drawableM7165b;
    }

    public Drawable getBackgroundIcon(KeyboardIconSet c1024ag, int i, int i2) {
        LongPressKeyData c0942b = this.longPressData;
        if (c0942b != null && c0942b.backgroundIconIds[i2] != 0) {
            return c1024ag.getIconDrawable(c0942b.backgroundIconIds[i2]);
        }
        return getIcon(c1024ag, i);
    }

    public Drawable getPreviewIcon(KeyboardIconSet c1024ag) {
        return c1024ag.getIconDrawable(getIconId());
    }
    
    /**
     * Apply icon tint based on active/inactive state.
     * 
     * This method tints whichever icon is displayed (default or disabled).
     * Disabled icons are always shown at 60% opacity.
     * Active/inactive input board state is shown via opacity for enabled icons.
     * 
     * @param c1024ag KeyboardIconSet containing the icon
     * @param isActive true if this is the active tab (100% opacity), false for inactive (60% opacity)
     */
    public void applyIconTint(KeyboardIconSet c1024ag, boolean isActive) {
        if (!KeyboardColorManager.INSTANCE.isInitialized()) {
            return;
        }
        applyIconTintWithColor(c1024ag, isActive,
                KeyboardColorManager.INSTANCE.getIconColor(KeyboardColorManager.ALPHA_SECONDARY));
    }

    /**
     * Apply icon tint with pre-computed color values for better performance.
     * Phase 1 Optimization: Accepts cached color to avoid repeated KeyboardColorManager calls.
     * 
     * This method is identical to applyIconTint but accepts ClipboardItem pre-computed inactiveColor
     * to avoid calling KeyboardColorManager.getIconColor(ALPHA_SECONDARY) for every key.
     * 
     * @param c1024ag KeyboardIconSet containing the icon
     * @param isActive true if this is the active tab (100% opacity), false for inactive (60% opacity)
     * @param inactiveColor Pre-computed color for inactive state (from KeyboardColorManager)
     */
    public void applyIconTintWithColor(KeyboardIconSet c1024ag, boolean isActive, int inactiveColor) {
        if (!KeyboardColorManager.INSTANCE.isInitialized()) {
            return;
        }
        
        // Determine which icon is actually being displayed
        int iconId;
        LongPressKeyData c0942b = this.longPressData;
        
        if (!this.isActive && c0942b != null && c0942b.disabledIconId != 0) {
            // Key is disabled: tint the disabled icon
            iconId = c0942b.disabledIconId;
        } else {
            // Key is enabled: tint the default icon
            iconId = getIconId();
        }
        
        if (iconId == 0) {
            return;  // No icon to tint
        }
        
        Drawable icon = c1024ag.getIconDrawable(iconId);
        if (icon == null) {
            return;
        }
        
        // Apply color with appropriate opacity using pre-computed values
        // Note: Drawables are already mutated in KeyboardIconSet.loadIcons()
        // Disabled keys always get 60% opacity
        // Enabled keys get 100% if active, 60% if inactive
        if (!this.isActive || !isActive) {
            // Disabled OR inactive: use pre-computed inactive color
            KeyboardColorManager.INSTANCE.tint(icon, inactiveColor);
        } else {
            // Enabled AND active: 100% opacity (bright, selected)
            KeyboardColorManager.INSTANCE.tint(icon);
        }
    }

    /**
     * The physical-keyboard scan code this key is mapped to ({@code keyMapScanCode}), or -1.
     * NOT a more-keys column: the field was called {@code maxMoreKeysColumn} as decompilation
     * residue, while the real max-more-keys-column value is folded into {@link #moreKeysFlags}.
     */
    public final int getScanCode() {
        return this.scanCode;
    }

    public final boolean hasScanCode() {
        return getScanCode() != -1;
    }

    public int getWidth() {
        return this.width;
    }

    public int getHeight() {
        return this.height;
    }

    public int getX() {
        return this.centerX;
    }

    public int getY() {
        return this.centerY;
    }

    public final int getDrawX() {
        int iMo6216ab = getX();
        LongPressKeyData c0942b = this.longPressData;
        return c0942b == null ? iMo6216ab : iMo6216ab + c0942b.visualInsetsLeft;
    }

    public final int getDrawWidth() {
        LongPressKeyData c0942b = this.longPressData;
        return c0942b == null ? this.width : (this.width - c0942b.visualInsetsLeft) - c0942b.visualInsetsRight;
    }

    public void onPressed() {
        this.isPressed = true;
    }

    public void onReleased() {
        this.isPressed = false;
    }

    public final boolean isActive() {
        return this.isActive;
    }
    
    public final boolean isPressed() {
        return this.isPressed;
    }

    public void setActive(boolean z) {
        this.isActive = z;
    }

    public void setHighlighted(boolean z) {
        this.isHighlighted = z;
    }
    
    public boolean isHighlighted() {
        return this.isHighlighted;
    }

    public Rect getHitBox() {
        return this.hitBox;
    }

    public boolean isOnKey(int i, int i2) {
        return this.hitBox.contains(i, i2);
    }

    public int squaredDistanceToEdge(int i, int i2) {
        int iMo6216ab = getX();
        int i3 = this.width + iMo6216ab;
        int iMo6217ac = getY();
        int i4 = this.height + iMo6217ac;
        if (i >= iMo6216ab) {
            iMo6216ab = i > i3 ? i3 : i;
        }
        if (i2 >= iMo6217ac) {
            iMo6217ac = i2 > i4 ? i4 : i2;
        }
        int i5 = i - iMo6216ab;
        int i6 = i2 - iMo6217ac;
        return (i5 * i5) + (i6 * i6);
    }

    

    public static class SpacerKey extends Key {
        public SpacerKey(TypedArray typedArray, KeyStyle abstractC1067z, KeyboardParams c1025ah, KeyboardRow c1027aj) {
            super(null, null, KeyHintPosition.HIDDEN, typedArray, abstractC1067z, c1025ah, c1027aj);
            this.backgroundType = abstractC1067z.getInt(typedArray, R.styleable.Keyboard_Key_backgroundType, 0);
            this.isEnabled = this.backgroundType != 0;
        }

        protected SpacerKey(KeyboardParams c1025ah, int i, int i2, int i3, int i4) {
            super(MoreKeySpec.getEmpty(), MoreKeySpec.getEmpty(), KeyHintPosition.HIDDEN, null, 0, 0, i, i2, i3, i4, c1025ah.mHorizontalGap, c1025ah.mVerticalGap, null, null, null, 0, 2, -1);
        }
    }

    public String getMoreKeysSpecString() {
        if (this.moreKeys == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (true) {
            MoreKeySpec[] c1034aqArr = this.moreKeys;
            if (i < c1034aqArr.length) {
                sb.append(c1034aqArr[i].getLabel());
                i++;
            } else {
                return sb.toString();
            }
        }
    }

    private String toUpperCaseLabel(String str, boolean z, Locale locale, String str2) {
        String upperCase;
        if (str == null) {
            return null;
        }
        if (z) {
            upperCase = (locale.getLanguage().equals("el") && str2.equals("ς")) ? "ς" : str.toLowerCase(locale);
        } else {
            upperCase = str.toUpperCase(locale);
        }
        if (upperCase == null || upperCase.equals(str)) {
            return null;
        }
        return upperCase;
    }
}

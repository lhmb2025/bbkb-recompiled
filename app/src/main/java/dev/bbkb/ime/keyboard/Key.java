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

    /**
     * @param labelFlags the key's label flags
     * @param keyboardElementId the keyboard's element id
     */
    private static boolean shouldPreserveCase(int labelFlags, int keyboardElementId) {
        if ((labelFlags & 65536) != 0) {
            return false;
        }
        switch (keyboardElementId) {
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

        /**
         * @param longPressKeySpec long-press key spec
         * @param longPressKeyHintPosition hint position
         * @param longPressCode long-press code
         * @param activeIconId active icon id
         * @param disabledIconId disabled icon id
         * @param backgroundIconIds background icon ids
         * @param visualInsetsLeft left visual inset
         * @param visualInsetsRight right visual inset
         */
        private LongPressKeyData(MoreKeySpec longPressKeySpec, KeyHintPosition longPressKeyHintPosition, int longPressCode, int activeIconId, int disabledIconId, int[] backgroundIconIds, int visualInsetsLeft, int visualInsetsRight) {
            this.longPressKeySpec = longPressKeySpec;
            this.longPressKeyHintPosition = longPressKeyHintPosition;
            this.longPressCode = longPressCode;
            this.activeIconId = activeIconId;
            this.disabledIconId = disabledIconId;
            for (int i6 = 0; i6 < backgroundIconIds.length; i6++) {
                this.backgroundIconIds[i6] = backgroundIconIds[i6];
            }
            this.visualInsetsLeft = visualInsetsLeft;
            this.visualInsetsRight = visualInsetsRight;
        }

        /**
         * @param longPressKeySpec long-press key spec
         * @param longPressKeyHintPosition hint position
         * @param longPressCode long-press code
         * @param activeIconId active icon id
         * @param disabledIconId disabled icon id
         * @param backgroundIconIds background icon ids
         * @param visualInsetsLeft left visual inset
         * @param visualInsetsRight right visual inset
         */
        public static LongPressKeyData create(MoreKeySpec longPressKeySpec, KeyHintPosition longPressKeyHintPosition, int longPressCode, int activeIconId, int disabledIconId, int[] backgroundIconIds, int visualInsetsLeft, int visualInsetsRight) {
            if ((longPressKeySpec == null || longPressKeySpec.equals(MoreKeySpec.getEmpty())) && longPressKeyHintPosition == KeyHintPosition.HIDDEN) {
                if (longPressCode == -21 && activeIconId == 0 && visualInsetsLeft == 0 && visualInsetsRight == 0) {
                    boolean z = false;
                    for (int i6 : backgroundIconIds) {
                        if (i6 != 0) {
                            z = true;
                        }
                    }
                    if (!z) {
                        return null;
                    }
                }
            }
            return new LongPressKeyData(longPressKeySpec, longPressKeyHintPosition, longPressCode, activeIconId, disabledIconId, backgroundIconIds, visualInsetsLeft, visualInsetsRight);
        }
    }

    /**
     * @param keySpec key spec
     * @param c1065x2 long-press key spec
     * @param longPressKeyHintPosition hint position
     * @param hintLabel hint label
     * @param labelFlags label flags
     * @param backgroundType background type
     * @param x x
     * @param y y
     * @param width width including the horizontal gap
     * @param height height including the vertical gap
     * @param horizontalGap horizontal gap
     * @param verticalGap vertical gap
     * @param keyStyleName output text
     * @param c1034aqArr more keys
     * @param keyLabelSet key label set
     * @param moreKeysFlags more-keys flags
     * @param actionFlags action flags
     * @param scanCode scan code, -1 for none
     */
    public Key(MoreKeySpec keySpec, MoreKeySpec c1065x2, KeyHintPosition longPressKeyHintPosition, String hintLabel, int labelFlags, int backgroundType, int x, int y, int width, int height, int horizontalGap, int verticalGap, String keyStyleName, MoreKeySpec[] c1034aqArr, String[] keyLabelSet, int moreKeysFlags, int actionFlags, int scanCode) {
        int i12;
        this.hitBox = new Rect();
        this.isEnabled = true;
        this.isActive = true;
        this.isHighlighted = false;
        if (keySpec == null) {
            throw new IllegalArgumentException("Invalid null keySpec argument.");
        }
        this.keySpec = keySpec;
        this.height = height - verticalGap;
        this.width = width - horizontalGap;
        this.hintLabel = hintLabel;
        this.labelFlags = labelFlags;
        this.backgroundType = backgroundType;
        this.actionFlags = actionFlags;
        if (c1034aqArr != null) {
            this.moreKeys = new MoreKeySpec[c1034aqArr.length];
            System.arraycopy(c1034aqArr, 0, this.moreKeys, 0, c1034aqArr.length);
        } else {
            this.moreKeys = null;
        }
        if (keyLabelSet != null) {
            this.keyLabelSet = new String[keyLabelSet.length];
            System.arraycopy(keyLabelSet, 0, this.keyLabelSet, 0, keyLabelSet.length);
            i12 = moreKeysFlags;
        } else {
            this.keyLabelSet = null;
            i12 = moreKeysFlags;
        }
        this.moreKeysFlags = i12;
        this.longPressData = LongPressKeyData.create(c1065x2, longPressKeyHintPosition, -21, 0, 0, new int[10], 0, 0);
        this.isActive = this.keySpec.getCode() != -21;
        this.centerX = (horizontalGap / 2) + x;
        this.centerY = y;
        this.hitBox.set(x, y, x + width + 1, y + height);
        this.keyData = null;
        this.outputText = keyStyleName;
        this.scanCode = scanCode;
        this.hashCode = calculateHashCode(this);
    }

    /**
     * @param keySpecString key spec
     * @param longPressKeySpecString long-press key spec, or null
     * @param longPressKeyHintPosition hint position
     * @param keyStyle the key style
     * @param keyboardParams the keyboard params
     * @param keyboardRow the row
     */
    public Key(String keySpecString, String longPressKeySpecString, KeyHintPosition longPressKeyHintPosition, TypedArray typedArray, KeyStyle keyStyle, KeyboardParams keyboardParams, KeyboardRow keyboardRow) throws NumberFormatException {
        int iCodePointAt;
        String strM6198T;
        String strM6173a;
        MoreKeySpec[] c1034aqArr;
        MoreKeySpec c1065x;
        this.hitBox = new Rect();
        this.isEnabled = true;
        this.isActive = true;
        this.isHighlighted = false;
        float f = isSpacerKey() ? 0.0f : keyboardParams.mHorizontalGap;
        int iM7177a = keyboardRow.getRowHeight();
        float fM7180b = keyboardRow.getKeyX(typedArray);
        float fM7181b = isSpacerKey() ? keyboardRow.getSpacerWidth(typedArray, fM7180b) : keyboardRow.getKeyWidth(typedArray, fM7180b);
        int iM7188g = keyboardRow.getY();
        float f2 = fM7180b + fM7181b;
        if (Math.round(f2) > f2) {
            this.width = Math.round((fM7181b - f) + 0.5f);
        } else {
            this.width = Math.round(fM7181b - f);
        }
        this.centerX = Math.round((f / 2.0f) + fM7180b);
        this.centerY = iM7188g;
        int iRound = Math.round(typedArray.getFraction(R.styleable.Keyboard_Key_keyHeight, iM7177a, iM7177a, iM7177a));
        this.height = iRound - keyboardParams.mVerticalGap;
        this.hitBox.set(Math.round(fM7180b), iM7188g, Math.round(f2) + 1, iRound + iM7188g);
        keyboardRow.setXPos(f2);
        this.backgroundType = keyStyle.getInt(typedArray, R.styleable.Keyboard_Key_backgroundType, keyboardRow.getDefaultBackgroundType());
        int i = keyboardParams.mBaseWidth;
        int iRound2 = Math.round(typedArray.getFraction(R.styleable.Keyboard_Key_visualInsetsLeft, i, i, 0.0f));
        int iRound3 = Math.round(typedArray.getFraction(R.styleable.Keyboard_Key_visualInsetsRight, i, i, 0.0f));
        int[] iArrM7451a = KeySpecParser.getIconIds(keyStyle.parseStringArray(typedArray, R.styleable.Keyboard_Key_keyIconsBackgroundType));
        this.labelFlags = keyboardRow.getDefaultKeyLabelFlags() | keyStyle.getFlags(typedArray, R.styleable.Keyboard_Key_keyLabelFlags);
        boolean zM6177c = shouldPreserveCase(this.labelFlags, keyboardParams.mId.mElementId);
        Locale locale = keyboardParams.mId.mLocale;
        // Named locals: create() takes activeIconId before disabledIconId, and swapping them makes
        // disabled keys draw their active icon.
        final int disabledIconId = KeySpecParser.getIconId(keyStyle.getString(typedArray, R.styleable.Keyboard_Key_keyIconDisabled));
        final int activeIconId = KeySpecParser.getIconId(keyStyle.getString(typedArray, R.styleable.Keyboard_Key_keyIconActive));
        String str3 = (this.labelFlags & 262144) != 0 ? keyboardParams.mId.mCustomActionLabel : null;
        if ((this.labelFlags & LABEL_FLAGS_DISABLE_HINT_LABEL) != 0) {
            this.hintLabel = null;
        } else {
            String hintLabelValue = keyStyle.getString(typedArray, R.styleable.Keyboard_Key_keyHintLabel);
            this.hintLabel = (zM6177c && hintLabelValue != null) ? hintLabelValue.toUpperCase(locale) : hintLabelValue;
        }
        if (hasShiftedLetterHint() && isShiftedLetterActivated()) {
            String str4 = this.hintLabel;
            iCodePointAt = str4 != null ? str4.codePointAt(0) : -21;
        } else {
            iCodePointAt = -21;
        }
        this.keySpec = new MoreKeySpec(keySpecString, str3, iCodePointAt, zM6177c, locale);
        int iMo7101c = keyStyle.getFlags(typedArray, R.styleable.Keyboard_Key_keyActionFlags);
        String[] strArrMo7099a = keyStyle.getStringArray(typedArray, R.styleable.Keyboard_Key_moreKeys, true);
        int iMo7097a = keyStyle.getInt(typedArray, R.styleable.Keyboard_Key_maxMoreKeysColumn, keyboardParams.mMaxMoreKeysKeyboardColumn) | 0;
        int iM7278a = KeySpecParser.getIntValue(strArrMo7099a, "!autoColumnOrder!", -1);
        iMo7097a = iM7278a > 0 ? (iM7278a & 255) | 256 : iMo7097a;
        int iM7278a2 = KeySpecParser.getIntValue(strArrMo7099a, "!fixedColumnOrder!", -1);
        iMo7097a = iM7278a2 > 0 ? (iM7278a2 & 255) | 768 : iMo7097a;
        iMo7097a = KeySpecParser.getBooleanValue(strArrMo7099a, "!hasLabels!") ? iMo7097a | MORE_KEYS_FLAGS_HAS_LABELS : iMo7097a;
        iMo7097a = KeySpecParser.getBooleanValue(strArrMo7099a, "!needsDividers!") ? iMo7097a | MORE_KEYS_FLAGS_NEEDS_DIVIDERS : iMo7097a;
        this.moreKeysFlags = KeySpecParser.getBooleanValue(strArrMo7099a, "!noPanelAutoMoreKey!") ? iMo7097a | MORE_KEYS_FLAGS_NO_PANEL_AUTO_MORE_KEY : iMo7097a;
        String[] strArrM7283a = KeySpecParser.insertAdditionalMoreKeys(strArrMo7099a, (this.labelFlags & LABEL_FLAGS_DISABLE_ADDITIONAL_MORE_KEYS) != 0 ? null : keyStyle.parseStringArray(typedArray, R.styleable.Keyboard_Key_additionalMoreKeys));
        if (getCode() > 0) {
            String strM5463a = new String(Character.toChars(getCode()));
            String strM6173a2 = toUpperCaseLabel(strM5463a, zM6177c, locale, keySpecString);
            String str5 = zM6177c ? strM5463a : null;
            strM6173a = strM6173a2;
            strM6198T = str5;
        } else if (getCode() == -4) {
            strM6173a = toUpperCaseLabel(getKeySpecOutputText(), zM6177c, locale, keySpecString);
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
        this.keyLabelSet = keyStyle.getStringArray(typedArray, R.styleable.Keyboard_Key_multitapKeys, true);
        int iM5459a = toUpperCaseCodePoint(KeySpecParser.getCodeFromSpec(keyStyle.getString(typedArray, R.styleable.Keyboard_Key_altCode), -21), zM6177c, locale);
        int iMo7097a2 = keyStyle.getInt(typedArray, R.styleable.Keyboard_Key_keyMapScanCode, -1);
        if (longPressKeySpecString == null) {
            c1065x = MoreKeySpec.getEmpty();
        } else {
            iMo7101c |= 8;
            c1065x = new MoreKeySpec(longPressKeySpecString, zM6177c, locale);
        }
        this.longPressData = LongPressKeyData.create(c1065x, longPressKeyHintPosition, iM5459a, activeIconId, disabledIconId, iArrM7451a, iRound2, iRound3);
        this.keyData = KeyVisualAttributes.createIfHasStyleAttributes(typedArray);
        this.actionFlags = iMo7101c;
        this.isActive = this.keySpec.getCode() != -21;
        this.outputText = keyStyle.getString(typedArray, R.styleable.Keyboard_Key_keyStyle);
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
     * Clones {@code prototype} onto the geometry of {@code slot}. The UIM bar reorders keys this way
     * because only XML-parsed keys keep their keyIconActive/keyIconDisabled pair.
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

    /**
     * @param keySpec the new key spec
     * @param c1034aqArr the new more keys
     */
    public Key(Key key, MoreKeySpec keySpec, MoreKeySpec[] c1034aqArr) {
        this.hitBox = new Rect();
        this.isEnabled = true;
        this.isActive = true;
        this.isHighlighted = false;
        this.keySpec = keySpec;
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

    /** @param keyboardParams the keyboard params */
    public void setLeftEdge(KeyboardParams keyboardParams) {
        this.hitBox.left = keyboardParams.mLeftPadding;
    }

    /** @param keyboardParams the keyboard params */
    public void setRightEdge(KeyboardParams keyboardParams) {
        this.hitBox.right = keyboardParams.mOccupiedWidth - keyboardParams.mRightPadding;
    }

    /** @param keyboardParams the keyboard params */
    public void setTopEdge(KeyboardParams keyboardParams) {
        this.hitBox.top = keyboardParams.mTopPadding;
    }

    /** @param keyboardParams the keyboard params */
    public void setBottomEdge(KeyboardParams keyboardParams) {
        this.hitBox.bottom = keyboardParams.mOccupiedHeight + keyboardParams.mBottomPadding;
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

    /** @param keyDrawParams the draw params */
    public final Typeface getTypeface(KeyDrawParams keyDrawParams) {
        int i = this.labelFlags & 48;
        if (i == 16) {
            return Typeface.DEFAULT;
        }
        if (i == 32) {
            return Typeface.MONOSPACE;
        }
        return keyDrawParams.typeface;
    }

    /** @param keyDrawParams the draw params */
    public final int getLabelSize(KeyDrawParams keyDrawParams) {
        int i = this.labelFlags & 448;
        if (i == 64) {
            return keyDrawParams.largeLetterSizePixels;
        }
        if (i == 128) {
            return keyDrawParams.letterSizePixels;
        }
        if (i == 192) {
            return keyDrawParams.labelSizePixels;
        }
        if (i != 320) {
            return getLabel().codePointCount(0, getLabel().length()) == 1 ? keyDrawParams.letterSizePixels : keyDrawParams.labelSizePixels;
        }
        return keyDrawParams.hintLabelSizePixels;
    }

    /** @param keyDrawParams the draw params */
    public final int getLabelColor(KeyDrawParams keyDrawParams) {
        if ((this.labelFlags & 524288) != 0) {
            return keyDrawParams.getFunctionalTextColor();
        }
        return isShiftedLetterActivated() ? keyDrawParams.getTextInactivatedColor() : keyDrawParams.getTextColor();
    }

    /** @param keyDrawParams the draw params */
    public final int resolveHintLabelSize(KeyDrawParams keyDrawParams) {
        if (hasHintLabel()) {
            return keyDrawParams.hintLabelSizePixels;
        }
        if (hasShiftedLetterHint()) {
            return keyDrawParams.shiftedLetterHintSizePixels;
        }
        if (hasMultilineHintLabel()) {
            return keyDrawParams.multilineHintLabelSizePixels;
        }
        return keyDrawParams.hintLetterSizePixels;
    }

    /** @param keyDrawParams the draw params */
    public final int resolveHintLabelColor(KeyDrawParams keyDrawParams) {
        if (hasHintLabel()) {
            return keyDrawParams.getHintLabelColor();
        }
        if (hasShiftedLetterHint()) {
            return isShiftedLetterActivated() ? keyDrawParams.getShiftedLetterHintActivatedColor() : keyDrawParams.getShiftedLetterHintInactivatedColor();
        }
        return keyDrawParams.getHintLetterColor();
    }

    public final String getVisibleLabel() {
        return isShiftedLetterActivated() ? this.hintLabel : getLabel();
    }

    private boolean hasSingleLetterVisibleLabel() {
        return (this.labelFlags & 128) != 0 || getVisibleLabel().codePointCount(0, getVisibleLabel().length()) == 1;
    }

    /** @param keyDrawParams the draw params */
    public final int getLetterSize(KeyDrawParams keyDrawParams) {
        if (hasSingleLetterVisibleLabel()) {
            return keyDrawParams.previewTextSizePixels;
        }
        return keyDrawParams.letterSizePixels;
    }

    /** @param keyDrawParams the draw params */
    public Typeface getLetterTypeface(KeyDrawParams keyDrawParams) {
        if (hasSingleLetterVisibleLabel()) {
            return getTypeface(keyDrawParams);
        }
        return Typeface.DEFAULT_BOLD;
    }

    /** @param defaultFlags the keyboard's default label flags */
    public final boolean isAlignHintLabelToBottom(int defaultFlags) {
        return ((defaultFlags | this.labelFlags) & 2) != 0;
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

    /** @param keyboardIconSet the icon set */
    public Drawable getIcon(KeyboardIconSet keyboardIconSet) {
        return keyboardIconSet.getIconDrawable(getLongPressIconId());
    }

    /**
     * @param keyboardIconSet the icon set
     * @param alpha alpha to apply
     */
    public Drawable getIcon(KeyboardIconSet keyboardIconSet, int alpha) {
        LongPressKeyData c0942b = this.longPressData;
        // Enabled keys always draw the default icon (active/inactive is opacity, see applyIconTint);
        // activeIconId is intentionally not read.
        int iconId = this.isActive ? getIconId() : (c0942b != null ? c0942b.disabledIconId : 0);
        Drawable drawableM7165b = keyboardIconSet.getIconDrawable(iconId);
        if (drawableM7165b != null) {
            drawableM7165b.setAlpha(alpha);
        }
        return drawableM7165b;
    }

    /**
     * @param keyboardIconSet the icon set
     * @param alpha alpha for the fallback icon
     * @param backgroundIndex background type index
     */
    public Drawable getBackgroundIcon(KeyboardIconSet keyboardIconSet, int alpha, int backgroundIndex) {
        LongPressKeyData c0942b = this.longPressData;
        if (c0942b != null && c0942b.backgroundIconIds[backgroundIndex] != 0) {
            return keyboardIconSet.getIconDrawable(c0942b.backgroundIconIds[backgroundIndex]);
        }
        return getIcon(keyboardIconSet, alpha);
    }

    /** @param keyboardIconSet the icon set */
    public Drawable getPreviewIcon(KeyboardIconSet keyboardIconSet) {
        return keyboardIconSet.getIconDrawable(getIconId());
    }
    
    /**
     * Tints the displayed icon: full color when enabled and active, the 60% secondary color otherwise.
     * @param keyboardIconSet icon set holding the icon
     * @param isActive true if this is the active tab
     */
    public void applyIconTint(KeyboardIconSet keyboardIconSet, boolean isActive) {
        if (!KeyboardColorManager.INSTANCE.isInitialized()) {
            return;
        }
        applyIconTintWithColor(keyboardIconSet, isActive,
                KeyboardColorManager.INSTANCE.getIconColor(KeyboardColorManager.ALPHA_SECONDARY));
    }

    /**
     * {@link #applyIconTint} with a precomputed inactive color, so callers skip a lookup per key.
     * @param keyboardIconSet icon set holding the icon
     */
    public void applyIconTintWithColor(KeyboardIconSet keyboardIconSet, boolean isActive, int inactiveColor) {
        if (!KeyboardColorManager.INSTANCE.isInitialized()) {
            return;
        }

        int iconId;
        LongPressKeyData c0942b = this.longPressData;
        
        if (!this.isActive && c0942b != null && c0942b.disabledIconId != 0) {
            iconId = c0942b.disabledIconId;
        } else {
            iconId = getIconId();
        }
        
        if (iconId == 0) {
            return;
        }
        
        Drawable icon = keyboardIconSet.getIconDrawable(iconId);
        if (icon == null) {
            return;
        }

        // Drawables are already mutated in KeyboardIconSet.loadIcons().
        if (!this.isActive || !isActive) {
            KeyboardColorManager.INSTANCE.tint(icon, inactiveColor);
        } else {
            KeyboardColorManager.INSTANCE.tint(icon);
        }
    }

    /** The physical-keyboard scan code this key is mapped to ({@code keyMapScanCode}), or -1. */
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

    /** @param active true if the key is active (tappable) */
    public void setActive(boolean active) {
        this.isActive = active;
    }

    /** @param highlighted true to highlight */
    public void setHighlighted(boolean highlighted) {
        this.isHighlighted = highlighted;
    }
    
    public boolean isHighlighted() {
        return this.isHighlighted;
    }

    public Rect getHitBox() {
        return this.hitBox;
    }

    /**
     * @param x x
     * @param y y
     */
    public boolean isOnKey(int x, int y) {
        return this.hitBox.contains(x, y);
    }

    /**
     * @param x x
     * @param y y
     */
    public int squaredDistanceToEdge(int x, int y) {
        int iMo6216ab = getX();
        int i3 = this.width + iMo6216ab;
        int iMo6217ac = getY();
        int i4 = this.height + iMo6217ac;
        if (x >= iMo6216ab) {
            iMo6216ab = x > i3 ? i3 : x;
        }
        if (y >= iMo6217ac) {
            iMo6217ac = y > i4 ? i4 : y;
        }
        int i5 = x - iMo6216ab;
        int i6 = y - iMo6217ac;
        return (i5 * i5) + (i6 * i6);
    }

    

    public static class SpacerKey extends Key {
        /**
         * @param keyStyle the key style
         * @param keyboardParams the keyboard params
         * @param keyboardRow the row
         */
        public SpacerKey(TypedArray typedArray, KeyStyle keyStyle, KeyboardParams keyboardParams, KeyboardRow keyboardRow) {
            super(null, null, KeyHintPosition.HIDDEN, typedArray, keyStyle, keyboardParams, keyboardRow);
            this.backgroundType = keyStyle.getInt(typedArray, R.styleable.Keyboard_Key_backgroundType, 0);
            this.isEnabled = this.backgroundType != 0;
        }

        /**
         * @param keyboardParams the keyboard params
         * @param x x
         * @param y y
         * @param width width
         * @param height height
         */
        protected SpacerKey(KeyboardParams keyboardParams, int x, int y, int width, int height) {
            super(MoreKeySpec.getEmpty(), MoreKeySpec.getEmpty(), KeyHintPosition.HIDDEN, null, 0, 0, x, y, width, height, keyboardParams.mHorizontalGap, keyboardParams.mVerticalGap, null, null, null, 0, 2, -1);
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

    /**
     * @param label the label
     * @param needsToUpperCase true to lower-case instead
     * @param keySpecString the key spec (Greek final sigma stays)
     */
    private String toUpperCaseLabel(String label, boolean needsToUpperCase, Locale locale, String keySpecString) {
        String upperCase;
        if (label == null) {
            return null;
        }
        if (needsToUpperCase) {
            upperCase = (locale.getLanguage().equals("el") && keySpecString.equals("ς")) ? "ς" : label.toLowerCase(locale);
        } else {
            upperCase = label.toUpperCase(locale);
        }
        if (upperCase == null || upperCase.equals(label)) {
            return null;
        }
        return upperCase;
    }
}

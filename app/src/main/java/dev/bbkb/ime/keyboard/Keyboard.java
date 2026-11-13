package dev.bbkb.ime.keyboard;

import static dev.bbkb.ime.core.shared.StringHelper.*;

import android.util.SparseArray;
import android.view.KeyEvent;

import dev.bbkb.ime.core.keyevent.KeyCharacterInterpreter;
import dev.bbkb.ime.core.shared.CoordinateUtils;
import dev.bbkb.ime.core.device.profile.DeviceProfile;
import dev.bbkb.ime.keyboard.internal.KeyVisualAttributes;
import dev.bbkb.ime.keyboard.internal.MoreKeySpec;
import dev.bbkb.ime.keyboard.internal.KeyboardIconSet;
import dev.bbkb.ime.keyboard.internal.KeyboardId;
import dev.bbkb.ime.keyboard.internal.KeyboardParams;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;



public class Keyboard {

    private final KeyCharacterInterpreter.MetaMask mDefaultKeyInterpretation;

    private final List<Key> mSortedKeys;

    public final KeyboardId mId;

    public final int mOccupiedHeight;

    public final int mOccupiedWidth;

    public final int mBaseHeight;

    public final int mBaseWidth;

    public final int mTopPadding;

    public final int mLeftPadding;

    public final int mRightPadding;

    public final int mVerticalGap;

    public final int mHorizontalGap;

    public final KeyVisualAttributes mMoreKeySpec;

    public final int mMostCommonKeyHeight;

    public final int mMostCommonKeyWidth;

    public final int mMoreKeysTemplate;

    public final int mMaxMoreKeysKeyboardColumn;

    public final List<Key> mShiftKeys;

    public final List<Key> mAltCodeKeysWhileTyping;

    public final KeyboardIconSet mIconsSet;

    private final SparseArray<Key> mKeyCache;

    private final SparseArray<Key> mKeysByScanCode;

    private final ProximityGrid mProximityGrid;

    private final boolean mAllowRedundantMoreKeys;

    private final Locale mLocale;

    /** @param keyboardParams the parsed keyboard params */
    public Keyboard(KeyboardParams keyboardParams) {
        this.mKeyCache = new SparseArray<>();
        this.mId = keyboardParams.mId;
        this.mOccupiedHeight = keyboardParams.mOccupiedHeight;
        this.mOccupiedWidth = keyboardParams.mOccupiedWidth;
        this.mBaseHeight = keyboardParams.mBaseHeight;
        this.mBaseWidth = keyboardParams.mBaseWidth;
        this.mMostCommonKeyHeight = keyboardParams.mMostCommonKeyHeight;
        this.mMostCommonKeyWidth = keyboardParams.mMostCommonKeyWidth;
        this.mMoreKeysTemplate = keyboardParams.mMoreKeysTemplate;
        this.mMaxMoreKeysKeyboardColumn = keyboardParams.mMaxMoreKeysKeyboardColumn;
        this.mMoreKeySpec = keyboardParams.mMoreKeySpec;
        this.mTopPadding = keyboardParams.mTopPadding;
        this.mLeftPadding = keyboardParams.mLeftPadding;
        this.mRightPadding = keyboardParams.mRightPadding;
        this.mVerticalGap = keyboardParams.mVerticalGap;
        this.mHorizontalGap = keyboardParams.mHorizontalGap;
        this.mKeysByScanCode = keyboardParams.mKeysByScanCode;
        this.mLocale = keyboardParams.mId.mLocale;
        this.mSortedKeys = new ArrayList(keyboardParams.mSortedKeys);
        this.mShiftKeys = Collections.unmodifiableList(keyboardParams.mShiftKeys);
        this.mAltCodeKeysWhileTyping = Collections.unmodifiableList(keyboardParams.mAltCodeKeysWhileTyping);
        this.mIconsSet = keyboardParams.mIconsSet;
        this.mProximityGrid = new ProximityGrid(keyboardParams.mGridWidth, keyboardParams.mGridHeight, this.mOccupiedWidth, this.mOccupiedHeight, this.mMostCommonKeyWidth, this.mSortedKeys);
        this.mAllowRedundantMoreKeys = keyboardParams.mAllowRedundantMoreKeys;
        int iM6595g = computeDefaultMetaState();
        this.mDefaultKeyInterpretation = new KeyCharacterInterpreter.MetaMask(iM6595g, iM6595g);
    }

    /** @param keyboard the keyboard to copy */
    protected Keyboard(Keyboard keyboard) {
        this.mKeyCache = new SparseArray<>();
        this.mId = keyboard.mId;
        this.mOccupiedHeight = keyboard.mOccupiedHeight;
        this.mOccupiedWidth = keyboard.mOccupiedWidth;
        this.mBaseHeight = keyboard.mBaseHeight;
        this.mBaseWidth = keyboard.mBaseWidth;
        this.mMostCommonKeyHeight = keyboard.mMostCommonKeyHeight;
        this.mMostCommonKeyWidth = keyboard.mMostCommonKeyWidth;
        this.mMoreKeysTemplate = keyboard.mMoreKeysTemplate;
        this.mMaxMoreKeysKeyboardColumn = keyboard.mMaxMoreKeysKeyboardColumn;
        this.mMoreKeySpec = keyboard.mMoreKeySpec;
        this.mTopPadding = keyboard.mTopPadding;
        this.mLeftPadding = keyboard.mLeftPadding;
        this.mRightPadding = keyboard.mRightPadding;
        this.mVerticalGap = keyboard.mVerticalGap;
        this.mHorizontalGap = keyboard.mHorizontalGap;
        this.mKeysByScanCode = keyboard.mKeysByScanCode;
        this.mLocale = Locale.getDefault();
        this.mSortedKeys = keyboard.mSortedKeys;
        this.mShiftKeys = keyboard.mShiftKeys;
        this.mAltCodeKeysWhileTyping = keyboard.mAltCodeKeysWhileTyping;
        this.mIconsSet = keyboard.mIconsSet;
        this.mProximityGrid = keyboard.mProximityGrid;
        this.mAllowRedundantMoreKeys = keyboard.mAllowRedundantMoreKeys;
        this.mDefaultKeyInterpretation = keyboard.mDefaultKeyInterpretation;
    }

    /** @param code the key code */
    public boolean allowsGestureForCode(int code) {
        if (this.mAllowRedundantMoreKeys) {
            return (this.mId.mElementId == 0 || this.mId.mElementId == 2) || Character.isLetter(code);
        }
        return false;
    }

    public boolean isSlideboardEligible() {
        return (this.mId.passwordInput() || this.mId.isNumberLayout() || this.mId.isPhoneLayout() || this.mId.isDateTimeLayout()) ? false : true;
    }

    /** True if the keyboard's XML definition enables gesture input. */
    public boolean supportsGestureInput() {
        return this.mAllowRedundantMoreKeys;
    }

    public ProximityGrid getProximityGrid() {
        return this.mProximityGrid;
    }

    public List<Key> getKeys() {
        return this.mSortedKeys;
    }

    /** @param outputText the output text */
    public Key getKeyByOutputText(String outputText) {
        if (outputText == null) {
            return null;
        }
        for (Key key : getKeys()) {
            if (outputText.equals(key.getOutputText())) {
                return key;
            }
        }
        return null;
    }

    /** @param code the key code */
    public Key getKeyByCode(int code) {
        if (code == -21) {
            return null;
        }
        synchronized (this.mKeyCache) {
            int iIndexOfKey = this.mKeyCache.indexOfKey(code);
            if (iIndexOfKey >= 0) {
                return this.mKeyCache.valueAt(iIndexOfKey);
            }
            for (Key key : getKeys()) {
                if (key.getCode() == code) {
                    this.mKeyCache.put(code, key);
                    return key;
                }
            }
            this.mKeyCache.put(code, null);
            return null;
        }
    }

    /** @param codePoint the code point */
    public Key getActionKeyForCode(int codePoint) {
        String strM6239e;
        for (Key key : getKeys()) {
            if (key.isActionKey() && (strM6239e = key.getLabel()) != null && strM6239e.length() > 0) {
                if (containsCodePoint(strM6239e, codePoint)) {
                    return key;
                }
                MoreKeySpec[] c1034aqArrM6245i = key.getMoreKeys();
                if (c1034aqArrM6245i != null && c1034aqArrM6245i.length > 0) {
                    for (MoreKeySpec c1034aq : c1034aqArrM6245i) {
                        String strD = c1034aq.getLabel();
                        if (strD != null && strD.length() > 0 && containsCodePoint(strD, codePoint)) {
                            return key;
                        }
                    }
                }
            }
        }
        return null;
    }

    public boolean hasKey(Key key) {
        synchronized (this.mKeyCache) {
            if (this.mKeyCache.indexOfValue(key) >= 0) {
                return true;
            }
            for (Key key2 : getKeys()) {
                if (key2 == key) {
                    this.mKeyCache.put(key2.getCode(), key2);
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Swaps {@code key} for {@code newKey} in place. Does not rebuild the proximity grid (a keys x cells
     * scan; callers replace in batches): call {@link #rebuildProximityGrid()} once after a batch.
     */
    public boolean replaceKey(Key key, Key newKey) {
        int iIndexOf = this.mSortedKeys.indexOf(key);
        if (iIndexOf <= -1) {
            return false;
        }
        this.mSortedKeys.set(iIndexOf, newKey);
        int iIndexOfKey = this.mKeysByScanCode.indexOfKey(key.getScanCode());
        if (iIndexOfKey > -1) {
            this.mKeysByScanCode.setValueAt(iIndexOfKey, newKey);
        }
        synchronized (this.mKeyCache) {
            this.mKeyCache.put(newKey.getCode(), newKey);
        }
        return true;
    }

    /** Rebuild the proximity grid; call once after a batch of {@link #replaceKey} calls. */
    public void rebuildProximityGrid() {
        this.mProximityGrid.buildGrid();
    }

    public String toString() {
        return this.mId.toString();
    }

    /**
     * @param x x
     * @param y y
     */
    public List<Key> getNearestKeys(int x, int y) {
        return this.mProximityGrid.getKeysInColumns(Math.max(0, Math.min(x, this.mOccupiedWidth - 1)), Math.max(0, Math.min(y, this.mOccupiedHeight - 1)));
    }

    public int[] getCoordinatesForCodes(int[] codes) {
        int length = codes.length;
        int[] iArrM5688a = CoordinateUtils.newCoordinateArray(length);
        for (int i = 0; i < length; i++) {
            Key keyM6604b = getKeyByCode(codes[i]);
            if (keyM6604b != null) {
                CoordinateUtils.setXYInArray(iArrM5688a, i, keyM6604b.getX() + (keyM6604b.getWidth() / 2), keyM6604b.getY() + (keyM6604b.getHeight() / 2));
            } else {
                CoordinateUtils.setXYInArray(iArrM5688a, i, -1, -1);
            }
        }
        return iArrM5688a;
    }

    /** The key mapped to physical scan code {@code scanCode} ({@code keyMapScanCode}), or null. */
    public Key getKeyByScanCode(int scanCode) {
        return this.mKeysByScanCode.get(scanCode);
    }

    /** @param code the key code */
    public String[] getKeyLabelSetForCode(int code) {
        Key keyM6604b = getKeyByCode(code);
        if (keyM6604b == null || !keyM6604b.isActive()) {
            return null;
        }
        return keyM6604b.getKeyLabelSet();
    }

    public KeyCharacterInterpreter.MetaMask getDefaultKeyInterpretation() {
        return this.mDefaultKeyInterpretation;
    }

    private int computeDefaultMetaState() {
        int i = 0;
        int i2 = this.mId.mElementId;

        switch (i2) {
            case 5:
                i = 512;
                break;
            case 6:
            case 7:
            case 8:
                i = 1024;
                break;
            case 105:
                i = 1024;
                break;
            case 106:
            case 107:
            case 108:
                i = 512;
                break;
            default:
                i = 0;
                break;
        }

        if (!"unknown".equals(DeviceProfile.current().getEffectiveKeypadType())) {
            // mMode is the editor variation.
            switch (this.mId.mMode) {
                case 4:
                case 5:
                case 6:
                case 7:
                case 8:
                    i = 512;
                    break;
            }
        }
        
        return KeyEvent.normalizeMetaState(i);
    }

    public int getNumberForKeyEvent(KeyEvent keyEvent) {
        if (!"unknown".equals(DeviceProfile.current().getEffectiveKeypadType())) {
            return 0;
        }
        switch (this.mId.mMode) {
            case 4:
            case 5:
            case 6:
            case 7:
            case 8:
                return keyEvent.getNumber();
            default:
                return 0;
        }
    }

    public boolean isNumberOrDatetimeVariation() {
        switch (this.mId.mMode) {
            case 4:
            case 5:
            case 6:
            case 7:
            case 8:
                return true;
            default:
                return false;
        }
    }

    public boolean isTouchKeyboard() {
        KeyboardId c0977g = this.mId;
        return c0977g != null && c0977g.mElementId >= 100;
    }
}

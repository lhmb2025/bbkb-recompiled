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
import dev.bbkb.ime.keyboard.internal.PhysicalKeySpecTable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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

    private final PhysicalKeySpecTable mPhysicalKeyboardRow;

    private final Locale mLocale;

    public Keyboard(KeyboardParams c1025ah) {
        this.mKeyCache = new SparseArray<>();
        this.mId = c1025ah.mId;
        this.mOccupiedHeight = c1025ah.mOccupiedHeight;
        this.mOccupiedWidth = c1025ah.mOccupiedWidth;
        this.mBaseHeight = c1025ah.mBaseHeight;
        this.mBaseWidth = c1025ah.mBaseWidth;
        this.mMostCommonKeyHeight = c1025ah.mMostCommonKeyHeight;
        this.mMostCommonKeyWidth = c1025ah.mMostCommonKeyWidth;
        this.mMoreKeysTemplate = c1025ah.mMoreKeysTemplate;
        this.mMaxMoreKeysKeyboardColumn = c1025ah.mMaxMoreKeysKeyboardColumn;
        this.mMoreKeySpec = c1025ah.mMoreKeySpec;
        this.mTopPadding = c1025ah.mTopPadding;
        this.mLeftPadding = c1025ah.mLeftPadding;
        this.mRightPadding = c1025ah.mRightPadding;
        this.mVerticalGap = c1025ah.mVerticalGap;
        this.mHorizontalGap = c1025ah.mHorizontalGap;
        this.mKeysByScanCode = c1025ah.mKeysByScanCode;
        this.mLocale = c1025ah.mId.mLocale;
        this.mSortedKeys = new ArrayList(c1025ah.mSortedKeys);
        this.mShiftKeys = Collections.unmodifiableList(c1025ah.mShiftKeys);
        this.mAltCodeKeysWhileTyping = Collections.unmodifiableList(c1025ah.mAltCodeKeysWhileTyping);
        this.mIconsSet = c1025ah.mIconsSet;
        this.mProximityGrid = new ProximityGrid(c1025ah.mGridWidth, c1025ah.mGridHeight, this.mOccupiedWidth, this.mOccupiedHeight, this.mMostCommonKeyWidth, this.mSortedKeys);
        this.mAllowRedundantMoreKeys = c1025ah.mAllowRedundantMoreKeys;
        this.mPhysicalKeyboardRow = c1025ah.mPhysicalKeyboardRow;
        int iM6595g = computeDefaultMetaState();
        this.mDefaultKeyInterpretation = new KeyCharacterInterpreter.MetaMask(iM6595g, iM6595g);
    }

    protected Keyboard(Keyboard c0965e) {
        this.mKeyCache = new SparseArray<>();
        this.mId = c0965e.mId;
        this.mOccupiedHeight = c0965e.mOccupiedHeight;
        this.mOccupiedWidth = c0965e.mOccupiedWidth;
        this.mBaseHeight = c0965e.mBaseHeight;
        this.mBaseWidth = c0965e.mBaseWidth;
        this.mMostCommonKeyHeight = c0965e.mMostCommonKeyHeight;
        this.mMostCommonKeyWidth = c0965e.mMostCommonKeyWidth;
        this.mMoreKeysTemplate = c0965e.mMoreKeysTemplate;
        this.mMaxMoreKeysKeyboardColumn = c0965e.mMaxMoreKeysKeyboardColumn;
        this.mMoreKeySpec = c0965e.mMoreKeySpec;
        this.mTopPadding = c0965e.mTopPadding;
        this.mLeftPadding = c0965e.mLeftPadding;
        this.mRightPadding = c0965e.mRightPadding;
        this.mVerticalGap = c0965e.mVerticalGap;
        this.mHorizontalGap = c0965e.mHorizontalGap;
        this.mKeysByScanCode = c0965e.mKeysByScanCode;
        this.mLocale = Locale.getDefault();
        this.mSortedKeys = c0965e.mSortedKeys;
        this.mShiftKeys = c0965e.mShiftKeys;
        this.mAltCodeKeysWhileTyping = c0965e.mAltCodeKeysWhileTyping;
        this.mIconsSet = c0965e.mIconsSet;
        this.mProximityGrid = c0965e.mProximityGrid;
        this.mAllowRedundantMoreKeys = c0965e.mAllowRedundantMoreKeys;
        this.mPhysicalKeyboardRow = c0965e.mPhysicalKeyboardRow;
        this.mDefaultKeyInterpretation = c0965e.mDefaultKeyInterpretation;
    }

    public boolean allowsGestureForCode(int i) {
        if (this.mAllowRedundantMoreKeys) {
            return (this.mId.mElementId == 0 || this.mId.mElementId == 2) || Character.isLetter(i);
        }
        return false;
    }

    public boolean isSlideboardEligible() {
        return (this.mId.passwordInput() || this.mId.isNumberLayout() || this.mId.isPhoneLayout() || this.mId.isDateTimeLayout()) ? false : true;
    }

    /**
     * Returns true if the keyboard's XML definition explicitly enables gesture input support.
     * This is the ground-truth capability flag, replacing the hardcoded KeyboardId checks
     * that were introduced by decompilation errors.
     */
    public boolean supportsGestureInput() {
        return this.mAllowRedundantMoreKeys;
    }

    public ProximityGrid getProximityGrid() {
        return this.mProximityGrid;
    }

    public List<Key> getKeys() {
        return this.mSortedKeys;
    }

    public Key getKeyByOutputText(String str) {
        if (str == null) {
            return null;
        }
        for (Key key : getKeys()) {
            if (str.equals(key.getOutputText())) {
                return key;
            }
        }
        return null;
    }

    public Key getKeyByCode(int i) {
        if (i == -21) {
            return null;
        }
        synchronized (this.mKeyCache) {
            int iIndexOfKey = this.mKeyCache.indexOfKey(i);
            if (iIndexOfKey >= 0) {
                return this.mKeyCache.valueAt(iIndexOfKey);
            }
            for (Key key : getKeys()) {
                if (key.getCode() == i) {
                    this.mKeyCache.put(i, key);
                    return key;
                }
            }
            this.mKeyCache.put(i, null);
            return null;
        }
    }

    public Key getActionKeyForCode(int i) {
        String strM6239e;
        for (Key key : getKeys()) {
            if (key.isActionKey() && (strM6239e = key.getLabel()) != null && strM6239e.length() > 0) {
                if (containsCodePoint(strM6239e, i)) {
                    return key;
                }
                MoreKeySpec[] c1034aqArrM6245i = key.getMoreKeys();
                if (c1034aqArrM6245i != null && c1034aqArrM6245i.length > 0) {
                    for (MoreKeySpec c1034aq : c1034aqArrM6245i) {
                        String strD = c1034aq.getLabel();
                        if (strD != null && strD.length() > 0 && containsCodePoint(strD, i)) {
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
     * Swap {@code key} for {@code key2} in place.
     *
     * <p>KT-10(b): this deliberately does NOT rebuild the proximity grid — that is a nested scan
     * over all keys x all grid cells and callers replace keys in batches (up to ~30 custom symbol
     * keys per page open). Call {@link #rebuildProximityGrid()} once after a batch.
     */
    public boolean replaceKey(Key key, Key key2) {
        int iIndexOf = this.mSortedKeys.indexOf(key);
        if (iIndexOf <= -1) {
            return false;
        }
        this.mSortedKeys.set(iIndexOf, key2);
        int iIndexOfKey = this.mKeysByScanCode.indexOfKey(key.getScanCode());
        if (iIndexOfKey > -1) {
            this.mKeysByScanCode.setValueAt(iIndexOfKey, key2);
        }
        synchronized (this.mKeyCache) {
            this.mKeyCache.put(key2.getCode(), key2);
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

    public List<Key> getNearestKeys(int i, int i2) {
        return this.mProximityGrid.getKeysInColumns(Math.max(0, Math.min(i, this.mOccupiedWidth - 1)), Math.max(0, Math.min(i2, this.mOccupiedHeight - 1)));
    }

    public int[] getCoordinatesForCodes(int[] iArr) {
        int length = iArr.length;
        int[] iArrM5688a = CoordinateUtils.newCoordinateArray(length);
        for (int i = 0; i < length; i++) {
            Key keyM6604b = getKeyByCode(iArr[i]);
            if (keyM6604b != null) {
                CoordinateUtils.setXYInArray(iArrM5688a, i, keyM6604b.getX() + (keyM6604b.getWidth() / 2), keyM6604b.getY() + (keyM6604b.getHeight() / 2));
            } else {
                CoordinateUtils.setXYInArray(iArrM5688a, i, -1, -1);
            }
        }
        return iArrM5688a;
    }

    /** The key mapped to physical scan code {@code i} ({@code keyMapScanCode}), or null. */
    public Key getKeyByScanCode(int i) {
        return this.mKeysByScanCode.get(i);
    }

    public String[] getMultiTapAlternates(String str) {
        PhysicalKeySpecTable c1026ai = this.mPhysicalKeyboardRow;
        if (c1026ai != null) {
            return c1026ai.getMultiTapAlternates(str);
        }
        return null;
    }

    public String[] getMultiTapSequence(String str) {
        PhysicalKeySpecTable c1026ai = this.mPhysicalKeyboardRow;
        if (c1026ai != null) {
            return c1026ai.getMultiTapSequence(str);
        }
        return null;
    }

    public String[] getKeyLabelSetForCode(int i) {
        Key keyM6604b = getKeyByCode(i);
        if (keyM6604b == null || !keyM6604b.isActive()) {
            return null;
        }
        return keyM6604b.getKeyLabelSet();
    }


    public HashMap<String, String[]> getMultiTapHash() {
        PhysicalKeySpecTable c1026ai = this.mPhysicalKeyboardRow;
        if (c1026ai != null) {
            return c1026ai.getMultiTapHash();
        }
        return null;
    }

    public KeyCharacterInterpreter.MetaMask getDefaultKeyInterpretation() {
        return this.mDefaultKeyInterpretation;
    }

    private int computeDefaultMetaState() {
        int i = 0;
        int i2 = this.mId.mElementId;
        
        // First switch on mElementId (input type)
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
        
        // Check if device type is not "unknown" using DeviceProfile
        if (!"unknown".equals(DeviceProfile.current().getEffectiveKeypadType())) {
            // Second switch on mMode (variation type)
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

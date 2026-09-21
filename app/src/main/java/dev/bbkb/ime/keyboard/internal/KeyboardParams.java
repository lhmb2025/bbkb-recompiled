package dev.bbkb.ime.keyboard.internal;

import android.util.SparseArray;
import android.util.SparseIntArray;

import dev.bbkb.ime.keyboard.Key;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.SortedSet;
import java.util.TreeSet;



public class KeyboardParams {

    /**
     * Row/column order, i.e. position: y then x, exactly as AOSP orders it. mSortedKeys is a
     * TreeSet, so "comparator returns 0" silently DROPS the insert — and the decompiled
     * comparator ordered by height then width, which is a size histogram, not a row/column
     * order, and made two distinct keys of equal size collide. onAddKey registers a dropped key
     * in mShiftKeys / mAltCodeKeysWhileTyping regardless, so it stayed a shift key while being
     * absent from the draw list and the proximity grid.
     *
     * <p>The code / output-text / size tiebreaks from the decompiled version are kept after the
     * position, so the set of key pairs that can still compare equal is a strict subset of what
     * collided before: nothing that survives today can be dropped by this ordering.
     */
    private static final Comparator<Key> ROW_COLUMN_COMPARATOR = new Comparator<Key>() {
        @Override // java.util.Comparator
        public int compare(Key key, Key key2) {
            if (key.getY() != key2.getY()) {
                return key.getY() < key2.getY() ? -1 : 1;
            }
            if (key.getX() != key2.getX()) {
                return key.getX() < key2.getX() ? -1 : 1;
            }
            if (key.getCode() != key2.getCode()) {
                return key.getCode() < key2.getCode() ? -1 : 1;
            }
            String spec1 = key.getKeySpec() != null ? key.getKeySpec().getOutputText() : "";
            String spec2 = key2.getKeySpec() != null ? key2.getKeySpec().getOutputText() : "";
            if (spec1 == null) spec1 = "";
            if (spec2 == null) spec2 = "";
            int specDiff = spec1.compareTo(spec2);
            if (specDiff != 0) {
                return specDiff;
            }
            if (key.getHeight() != key2.getHeight()) {
                return key.getHeight() < key2.getHeight() ? -1 : 1;
            }
            if (key.getWidth() != key2.getWidth()) {
                return key.getWidth() < key2.getWidth() ? -1 : 1;
            }
            return 0;
        }
    };

    public int mMaxMoreKeysKeyboardColumn;

    public int mGridWidth;

    public int mGridHeight;

    public UniqueKeysCache mTextTable;

    public boolean mAllowRedundantMoreKeys;

    public KeyboardId mId;

    public int mOccupiedHeight;

    public int mOccupiedWidth;

    public int mBaseHeight;

    public int mBaseWidth;

    public int mTopPadding;

    public int mBottomPadding;

    public int mLeftPadding;

    public int mRightPadding;

    public KeyVisualAttributes mMoreKeySpec;

    public int mDefaultRowHeight;

    public int mDefaultKeyWidth;

    public int mDefaultSpacerWidth;

    public int mHorizontalGap;

    public int mVerticalGap;

    public int mMoreKeysTemplate;

    public final SortedSet<Key> mSortedKeys = new TreeSet(ROW_COLUMN_COMPARATOR);

    public final ArrayList<Key> mShiftKeys = new ArrayList<>();

    public final ArrayList<Key> mAltCodeKeysWhileTyping = new ArrayList<>();

    public final SparseArray<Key> mKeysByScanCode = new SparseArray<>();

    public final KeyboardIconSet mIconsSet = new KeyboardIconSet();

    public final KeyboardTextsSet mTextsSet = new KeyboardTextsSet();

    public final KeyStylesSet mKeyStyles = new KeyStylesSet(this.mTextsSet);

    public PhysicalKeySpecTable mPhysicalKeyboardRow = null;

    public int mMostCommonKeyHeight = 0;

    public int mMostCommonKeyWidth = 0;


    /** Highest bucket count seen in mHeightHistogram / mWidthHistogram. The decompiled
     *  names had these two crossed, next to the code that derives every gesture
     *  threshold in this package from mMostCommonKeyWidth / mMostCommonKeyHeight. */
    private int mMostCommonKeyHeightCount = 0;

    private int mMostCommonKeyWidthCount = 0;

    private final SparseIntArray mHeightHistogram = new SparseIntArray();

    private final SparseIntArray mWidthHistogram = new SparseIntArray();

    protected void clearKeys() {
        this.mSortedKeys.clear();
        this.mShiftKeys.clear();
        clearHistogram();
    }

    public void onAddKey(Key key) {
        UniqueKeysCache c1031an = this.mTextTable;
        if (c1031an != null) {
            key = c1031an.intern(key);
        }
        boolean zM6250n = key.isSpacerKey();
        if (zM6250n && key.getWidth() == 0) {
            return;
        }
        this.mSortedKeys.add(key);
        if (zM6250n) {
            return;
        }
        updateHistogram(key);
        if (key.getCode() == -1) {
            this.mShiftKeys.add(key);
        }
        if (key.isAltCodeWhileTyping()) {
            this.mAltCodeKeysWhileTyping.add(key);
        }
        if (key.hasScanCode()) {
            this.mKeysByScanCode.append(key.getScanCode(), key);
        }
    }

    private void clearHistogram() {
        this.mMostCommonKeyHeight = 0;
        this.mMostCommonKeyHeightCount = 0;
        this.mHeightHistogram.clear();
        this.mMostCommonKeyWidth = 0;
        this.mMostCommonKeyWidthCount = 0;
        this.mWidthHistogram.clear();
    }

    private static int updateHistogramCounter(SparseIntArray sparseIntArray, int i) {
        int i2 = (sparseIntArray.indexOfKey(i) >= 0 ? sparseIntArray.get(i) : 0) + 1;
        sparseIntArray.put(i, i2);
        return i2;
    }

    private void updateHistogram(Key key) {
        int iM6215aa = key.getHeight() + this.mVerticalGap;
        int iM7166a = updateHistogramCounter(this.mHeightHistogram, iM6215aa);
        if (iM7166a > this.mMostCommonKeyHeightCount) {
            this.mMostCommonKeyHeightCount = iM7166a;
            this.mMostCommonKeyHeight = iM6215aa;
        }
        int iM6204Z = key.getWidth() + this.mHorizontalGap;
        int iM7166a2 = updateHistogramCounter(this.mWidthHistogram, iM6204Z);
        if (iM7166a2 > this.mMostCommonKeyWidthCount) {
            this.mMostCommonKeyWidthCount = iM7166a2;
            this.mMostCommonKeyWidth = iM6204Z;
        }
    }
}

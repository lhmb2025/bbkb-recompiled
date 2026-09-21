package dev.bbkb.ime.keyboard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;



/**
 * The proximity grid behind {@code Keyboard.getNearestKeys} → {@code KeyDetector.detectHitKey}:
 * the keyboard is divided into a {@code mGridWidth} x {@code mGridHeight} grid of cells, and each
 * cell holds the keys within a proximity radius of it. Nothing here animates anything — the class
 * was called {@code KeyboardAnimator} and its grid build {@code cancelAllAnimations()} purely as
 * decompilation residue.
 */
public class ProximityGrid {

    private static final List<Key> EMPTY_KEY_LIST = Collections.emptyList();

    /** Number of grid columns. */
    private final int mGridWidth;

    /** Number of grid rows. */
    private final int mGridHeight;

    /** {@code mGridWidth * mGridHeight} — the number of cells in {@link #mGridNeighbors}. */
    private final int mGridCellCount;

    /** Width in pixels of one grid cell. */
    private final int mCellWidth;

    /** Height in pixels of one grid cell. */
    private final int mCellHeight;

    /** Occupied keyboard width in pixels. */
    private final int mKeyboardWidth;

    /** Occupied keyboard height in pixels. */
    private final int mKeyboardHeight;

    /** Width in pixels of the most common key — the proximity radius is derived from this. */
    private final int mMostCommonKeyWidth;

    private final List<Key> mKeys;

    private final List<Key>[] mGridNeighbors;

    ProximityGrid(int i, int i2, int i3, int i4, int i5, List<Key> list) {
        this.mGridWidth = i;
        this.mGridHeight = i2;
        int i7 = this.mGridWidth;
        int i8 = this.mGridHeight;
        this.mGridCellCount = i7 * i8;
        // Guard the DIVISORS (grid width/height) as well as the occupied dimensions: a
        // degenerate keyboard can arrive with a zero grid dimension (a moreKeys keyboard
        // built against a zero-height parent) and dividing by it throws before the grid
        // build is ever reached. <= not ==: occupied dimensions can also arrive NEGATIVE
        // (rows*0 - verticalGap), which makes the derived cell size 0.
        boolean degenerate = i7 <= 0 || i8 <= 0 || i3 <= 0 || i4 <= 0;
        this.mCellWidth = degenerate ? 0 : ((i3 + i7) - 1) / i7;
        this.mCellHeight = degenerate ? 0 : ((i4 + i8) - 1) / i8;
        this.mKeyboardWidth = i3;
        this.mKeyboardHeight = i4;
        this.mMostCommonKeyWidth = i5;
        this.mKeys = list;
        this.mGridNeighbors = new List[Math.max(0, this.mGridCellCount)];
        if (degenerate) {
            return;
        }
        buildGrid();
    }

    public void buildGrid() {
        int size = this.mKeys.size();
        int length = this.mGridNeighbors.length;
        int i = (int) (this.mMostCommonKeyWidth * 1.2f);
        int i2 = i * i;
        int i3 = (this.mGridWidth * this.mCellWidth) - 1;
        int i4 = (this.mGridHeight * this.mCellHeight) - 1;
        HashMap map = new HashMap();
        int[] iArr = new int[length];
        int i5 = this.mCellWidth / 2;
        int i6 = this.mCellHeight / 2;
        Iterator<Key> it = this.mKeys.iterator();
        while (it.hasNext()) {
            Key next = it.next();
            if (!next.isSpacerKey()) {
                int iMo6216ab = next.getX();
                int iMo6217ac = next.getY();
                int i7 = iMo6217ac - i;
                int i8 = this.mCellHeight;
                Iterator<Key> it2 = it;
                int i9 = i7 % i8;
                int i10 = (i7 - i9) + i6;
                if (i9 <= i6) {
                    i8 = 0;
                }
                int iMax = Math.max(i6, i10 + i8);
                int iMin = Math.min(i4, iMo6217ac + next.getHeight() + i);
                int i11 = iMo6216ab - i;
                int i12 = i4;
                int i13 = this.mCellWidth;
                int i14 = i6;
                int i15 = i11 % i13;
                int iMax2 = Math.max(i5, (i11 - i15) + i5 + (i15 <= i5 ? 0 : i13));
                int iMin2 = Math.min(i3, iMo6216ab + next.getWidth() + i);
                int i16 = ((iMax / this.mCellHeight) * this.mGridWidth) + (iMax2 / this.mCellWidth);
                while (iMax <= iMin) {
                    int i17 = iMax2;
                    int i18 = i16;
                    while (i17 <= iMin2) {
                        int i19 = i;
                        if (next.squaredDistanceToEdge(i17, iMax) < i2) {
                            map.put(Integer.valueOf((i18 * size) + iArr[i18]), next);
                            iArr[i18] = iArr[i18] + 1;
                        }
                        i18++;
                        i17 += this.mCellWidth;
                        i = i19;
                    }
                    i16 += this.mGridWidth;
                    iMax += this.mCellHeight;
                    i = i;
                }
                i4 = i12;
                it = it2;
                i6 = i14;
            }
        }
        for (int i20 = 0; i20 < length; i20++) {
            int i21 = i20 * size;
            int i22 = iArr[i20] + i21;
            ArrayList arrayList = new ArrayList(i22 - i21);
            while (i21 < i22) {
                arrayList.add(map.get(Integer.valueOf(i21)));
                i21++;
            }
            this.mGridNeighbors[i20] = Collections.unmodifiableList(arrayList);
        }
    }

    public List<Key> getKeysInColumns(int i, int i2) {
        int i3;
        List<Key>[] listArr = this.mGridNeighbors;
        if (listArr == null) {
            return EMPTY_KEY_LIST;
        }
        if (this.mCellWidth > 0 && this.mCellHeight > 0
                && i >= 0 && i < this.mKeyboardWidth && i2 >= 0 && i2 < this.mKeyboardHeight
                && (i3 = ((i2 / this.mCellHeight) * this.mGridWidth) + (i / this.mCellWidth)) < this.mGridCellCount) {
            return listArr[i3];
        }
        return EMPTY_KEY_LIST;
    }
}

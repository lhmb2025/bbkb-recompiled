package dev.bbkb.ime.keyboard.internal;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Paint;

import androidx.annotation.VisibleForTesting;

import dev.bbkb.ime.keyboard.Key;
import dev.bbkb.ime.keyboard.Keyboard;
import dev.bbkb.ime.R;
import dev.bbkb.ime.core.shared.TypefaceUtils;

import java.lang.ref.WeakReference;
import java.util.ArrayList;


public final class MoreKeysKeyboard extends Keyboard {

    private final int mDefaultCoordX;

    MoreKeysKeyboard(MoreKeysKeyboardParams moreKeysKeyboardParams) {
        super(moreKeysKeyboardParams);
        this.mDefaultCoordX = moreKeysKeyboardParams.getDefaultKeyCoordX() + (moreKeysKeyboardParams.mDefaultKeyWidth / 2);
    }

    public int getDefaultCoordX() {
        return this.mDefaultCoordX;
    }


    static class MoreKeysKeyboardParams extends KeyboardParams {

        public boolean mIsMoreKeysFixedOrder;

        int mTopRowAdjustment;

        public int mNumRows;

        public int mNumColumns;

        public int mTopKeys;

        public int mLeftKeys;

        public int mRightKeys;

        public int mDividerWidth;

        public int mColumnWidth;

        /**
         * @param numKeys number of more keys
         * @param numColumn maximum columns
         * @param keyWidth key width
         * @param rowHeight row height
         * @param coordXInParent x of the parent key's center
         * @param parentKeyboardWidth keyboard width
         * @param isMoreKeysFixedColumn true for a fixed column count
         * @param isMoreKeysFixedOrder true for fixed key order
         * @param dividerWidth divider width, 0 for none
         */
        public void setParameters(int numKeys, int numColumn, int keyWidth, int rowHeight, int coordXInParent, int parentKeyboardWidth, boolean isMoreKeysFixedColumn, boolean isMoreKeysFixedOrder, int dividerWidth) {
            int iM6344c;
            int iM6345d;
            this.mIsMoreKeysFixedOrder = isMoreKeysFixedOrder;
            if (parentKeyboardWidth / keyWidth < Math.min(numKeys, numColumn)) {
                throw new IllegalArgumentException("Keyboard is too small to hold more keys: " + parentKeyboardWidth + " " + keyWidth + " " + numKeys + " " + numColumn);
            }
            this.mDefaultKeyWidth = keyWidth;
            this.mDefaultRowHeight = rowHeight;
            this.mNumRows = ((numKeys + numColumn) - 1) / numColumn;
            if (isMoreKeysFixedColumn) {
                iM6344c = Math.min(numKeys, numColumn);
            } else {
                iM6344c = getOptimizedColumns(numKeys, numColumn);
            }
            this.mNumColumns = iM6344c;
            int i8 = numKeys % iM6344c;
            if (i8 == 0) {
                i8 = iM6344c;
            }
            this.mTopKeys = i8;
            int i9 = (iM6344c - 1) / 2;
            int i10 = iM6344c - i9;
            int i11 = coordXInParent / keyWidth;
            int i12 = (parentKeyboardWidth - coordXInParent) / keyWidth;
            if (i9 > i11) {
                i10 = iM6344c - i11;
                i9 = i11;
            } else {
                int i13 = i12 + 1;
                if (i10 > i13) {
                    i9 = iM6344c - i13;
                    i10 = i13;
                }
            }
            if (i11 == i9 && i9 > 0) {
                i9--;
                i10++;
            }
            if (i12 == i10 - 1 && i10 > 1) {
                i9++;
                i10--;
            }
            this.mLeftKeys = i9;
            this.mRightKeys = i10;
            if (isMoreKeysFixedOrder) {
                iM6345d = getFixedOrderTopRowAdjustment();
            } else {
                iM6345d = getAutoOrderTopRowAdjustment();
            }
            this.mTopRowAdjustment = iM6345d;
            this.mDividerWidth = dividerWidth;
            int i14 = this.mDefaultKeyWidth;
            int i15 = this.mDividerWidth;
            this.mColumnWidth = i14 + i15;
            int i16 = (this.mNumColumns * this.mColumnWidth) - i15;
            this.mOccupiedWidth = i16;
            this.mBaseWidth = i16;
            int i17 = ((this.mNumRows * this.mDefaultRowHeight) - this.mVerticalGap) + this.mTopPadding + this.mBottomPadding;
            this.mOccupiedHeight = i17;
            this.mBaseHeight = i17;
        }

        private int getFixedOrderTopRowAdjustment() {
            if (this.mNumRows == 1) {
                return 0;
            }
            int i = this.mTopKeys;
            return (i % 2 == 1 || i == this.mNumColumns || this.mLeftKeys == 0 || this.mRightKeys == 1) ? 0 : -1;
        }

        private int getAutoOrderTopRowAdjustment() {
            int i;
            return (this.mNumRows == 1 || (i = this.mTopKeys) == 1 || this.mNumColumns % 2 == i % 2 || this.mLeftKeys == 0 || this.mRightKeys == 1) ? 0 : -1;
        }

        /** @param n index of the more key */
        int getColumnPos(int n) {
            return this.mIsMoreKeysFixedOrder ? getFixedOrderColumnPos(n) : getAutoOrderColumnPos(n);
        }

        private int getFixedOrderColumnPos(int n) {
            int i2 = this.mNumColumns;
            int i3 = n % i2;
            if (!isTopRow(n / i2)) {
                return i3 - this.mLeftKeys;
            }
            int i4 = this.mTopKeys;
            int i5 = i4 / 2;
            int i6 = i4 - (i5 + 1);
            int i7 = i3 - i6;
            int i8 = this.mLeftKeys + this.mTopRowAdjustment;
            int i9 = this.mRightKeys - 1;
            return (i9 < i5 || i8 < i6) ? i9 < i5 ? i7 - (i5 - i9) : i7 + (i6 - i8) : i7;
        }

        private int getAutoOrderColumnPos(int n) {
            int i2 = this.mNumColumns;
            int i3 = n % i2;
            int i4 = n / i2;
            int i5 = this.mLeftKeys;
            if (isTopRow(i4)) {
                i5 += this.mTopRowAdjustment;
            }
            int i6 = 0;
            if (i3 == 0) {
                return 0;
            }
            int i7 = 1;
            int i8 = 0;
            int i9 = 0;
            do {
                if (i7 < this.mRightKeys) {
                    i6++;
                    i9 = i7;
                    i7++;
                }
                if (i6 >= i3) {
                    break;
                }
                if (i8 < i5) {
                    i8++;
                    i9 = -i8;
                    i6++;
                }
            } while (i6 < i3);
            return i9;
        }

        private static int getTopRowEmptySlots(int numKeys, int numColumns) {
            int i3 = numKeys % numColumns;
            if (i3 == 0) {
                return 0;
            }
            return numColumns - i3;
        }

        private int getOptimizedColumns(int numKeys, int maxColumns) {
            int iMin = Math.min(numKeys, maxColumns);
            while (getTopRowEmptySlots(numKeys, iMin) >= this.mNumRows) {
                iMin--;
            }
            return iMin;
        }

        public int getDefaultKeyCoordX() {
            return (this.mLeftKeys * this.mColumnWidth) + this.mLeftPadding;
        }

        /**
         * @param n index of the more key
         * @param row row, 0 at the bottom
         */
        public int getX(int n, int row) {
            int iM6349a = (getColumnPos(n) * this.mColumnWidth) + getDefaultKeyCoordX();
            return isTopRow(row) ? iM6349a + (this.mTopRowAdjustment * (this.mColumnWidth / 2)) : iM6349a;
        }

        /** @param row row, 0 at the bottom */
        public int getY(int row) {
            return (((this.mNumRows - 1) - row) * this.mDefaultRowHeight) + this.mTopPadding;
        }

        /** @param row row, 0 at the bottom */
        public void markAsEdgeKey(Key key, int row) {
            if (row == 0) {
                key.setTopEdge(this);
            }
            if (isTopRow(row)) {
                key.setBottomEdge(this);
            }
        }

        private boolean isTopRow(int row) {
            int i2 = this.mNumRows;
            return i2 > 1 && row == i2 - 1;
        }
    }

    /**
     * The more-keys template's parsed {@code <Keyboard>} attributes, which depend only on theme,
     * template and parent size. Parsed once per combination (a full parse is expensive) and copied
     * into each popup.
     */
    static final class TemplateAttributes {

        private static final int MAX_ENTRIES = 4;

        /** Newest first. */
        private static final ArrayList<TemplateAttributes> sCache = new ArrayList<>();

        final WeakReference<Resources.Theme> theme;

        final int templateId;

        final int width;

        final int height;

        final int topPadding;

        final int bottomPadding;

        final int leftPadding;

        final int rightPadding;

        final int baseWidth;

        final int baseHeight;

        final int defaultKeyWidth;

        final int defaultSpacerWidth;

        final int horizontalGap;

        final int verticalGap;

        final int defaultRowHeight;

        final int occupiedWidth;

        final int occupiedHeight;

        final KeyVisualAttributes moreKeySpec;

        final int moreKeysTemplate;

        final int maxMoreKeysKeyboardColumn;

        final int gridWidth;

        final int gridHeight;

        final KeyboardIconSet iconsSet;

        private TemplateAttributes(Resources.Theme theme, int templateId, KeyboardId id, KeyboardParams parsed) {
            this.theme = new WeakReference<>(theme);
            this.templateId = templateId;
            this.width = id.mWidth;
            this.height = id.mHeight;
            this.topPadding = parsed.mTopPadding;
            this.bottomPadding = parsed.mBottomPadding;
            this.leftPadding = parsed.mLeftPadding;
            this.rightPadding = parsed.mRightPadding;
            this.baseWidth = parsed.mBaseWidth;
            this.baseHeight = parsed.mBaseHeight;
            this.defaultKeyWidth = parsed.mDefaultKeyWidth;
            this.defaultSpacerWidth = parsed.mDefaultSpacerWidth;
            this.horizontalGap = parsed.mHorizontalGap;
            this.verticalGap = parsed.mVerticalGap;
            this.defaultRowHeight = parsed.mDefaultRowHeight;
            this.occupiedWidth = parsed.mOccupiedWidth;
            this.occupiedHeight = parsed.mOccupiedHeight;
            this.moreKeySpec = parsed.mMoreKeySpec;
            this.moreKeysTemplate = parsed.mMoreKeysTemplate;
            this.maxMoreKeysKeyboardColumn = parsed.mMaxMoreKeysKeyboardColumn;
            this.gridWidth = parsed.mGridWidth;
            this.gridHeight = parsed.mGridHeight;
            this.iconsSet = parsed.mIconsSet;
        }

        private boolean matches(Resources.Theme theme, int templateId, KeyboardId id) {
            return this.theme.get() == theme && this.templateId == templateId
                    && this.width == id.mWidth && this.height == id.mHeight;
        }

        static TemplateAttributes obtain(Context context, Keyboard parent) {
            Resources.Theme theme = context.getTheme();
            int templateId = parent.mMoreKeysTemplate;
            KeyboardId id = parent.mId;
            synchronized (sCache) {
                for (int i = 0; i < sCache.size(); i++) {
                    TemplateAttributes candidate = sCache.get(i);
                    if (candidate.matches(theme, templateId, id)) {
                        if (i != 0) {
                            sCache.remove(i);
                            sCache.add(0, candidate);
                        }
                        return candidate;
                    }
                }
            }
            MoreKeysKeyboardParams parsed = new MoreKeysKeyboardParams();
            new KeyboardXMLParser<>(context, parsed).load(templateId, id);
            TemplateAttributes created = new TemplateAttributes(theme, templateId, id, parsed);
            synchronized (sCache) {
                sCache.add(0, created);
                while (sCache.size() > MAX_ENTRIES) {
                    sCache.remove(sCache.size() - 1);
                }
            }
            return created;
        }

        /** What {@code load(template, parent.mId)} left in a fresh params object. */
        void applyTo(MoreKeysKeyboardParams params, KeyboardId id) {
            params.mId = id;
            params.mTopPadding = this.topPadding;
            params.mBottomPadding = this.bottomPadding;
            params.mLeftPadding = this.leftPadding;
            params.mRightPadding = this.rightPadding;
            params.mBaseWidth = this.baseWidth;
            params.mBaseHeight = this.baseHeight;
            params.mDefaultKeyWidth = this.defaultKeyWidth;
            params.mDefaultSpacerWidth = this.defaultSpacerWidth;
            params.mHorizontalGap = this.horizontalGap;
            params.mVerticalGap = this.verticalGap;
            params.mDefaultRowHeight = this.defaultRowHeight;
            params.mOccupiedWidth = this.occupiedWidth;
            params.mOccupiedHeight = this.occupiedHeight;
            params.mMoreKeySpec = this.moreKeySpec;
            params.mMoreKeysTemplate = this.moreKeysTemplate;
            params.mMaxMoreKeysKeyboardColumn = this.maxMoreKeysKeyboardColumn;
            params.mGridWidth = this.gridWidth;
            params.mGridHeight = this.gridHeight;
            params.mIconsSet.shareFrom(this.iconsSet);
        }

        @VisibleForTesting
        static void clearForTest() {
            synchronized (sCache) {
                sCache.clear();
            }
        }
    }

    public static class Builder {

        private final MoreKeysKeyboardParams mParams;

        private final Key mParentKey;

        /**
         * @param keyboard the parent keyboard
         * @param isSingleMoreKeyWithPreview true for a single more key shown in the key preview's place
         * @param keyPreviewVisibleWidth key preview visible width
         * @param keyPreviewVisibleHeight key preview visible height
         */
        public Builder(Context context, Key key, Keyboard keyboard, boolean isSingleMoreKeyWithPreview, int keyPreviewVisibleWidth, int keyPreviewVisibleHeight, Paint paint) {
            this.mParams = new MoreKeysKeyboardParams();
            int iM6354a;
            int i3;
            TemplateAttributes.obtain(context, keyboard).applyTo(this.mParams, keyboard.mId);
            this.mParams.mVerticalGap = keyboard.mVerticalGap / 2;
            this.mParentKey = key;
            if (isSingleMoreKeyWithPreview) {
                iM6354a = keyPreviewVisibleWidth;
                i3 = keyPreviewVisibleHeight + this.mParams.mVerticalGap;
            } else {
                iM6354a = getMaxKeyWidth(key, this.mParams.mDefaultKeyWidth, context.getResources().getDimension(R.dimen.config_more_keys_keyboard_key_horizontal_padding) + (key.hasLabelsInMoreKeys() ? this.mParams.mDefaultKeyWidth * 0.2f : 0.0f), paint);
                i3 = keyboard.mMostCommonKeyHeight;
            }
            this.mParams.setParameters(key.getMoreKeys().length, key.getMoreKeysColumnNumber(), iM6354a, i3, key.getX() + (key.getWidth() / 2), keyboard.mId.mWidth, key.isMoreKeysFixedColumn(), key.isMoreKeysFixedOrder(), key.needsDividersInMoreKeys() ? (int) (iM6354a * 0.2f) : 0);
        }

        private static int getMaxKeyWidth(Key key, int minKeyWidth, float padding, Paint paint) {
            for (MoreKeySpec c1034aq : key.getMoreKeys()) {
                String strD = c1034aq.getLabel();
                if (strD != null && (strD).codePointCount(0, (strD).length()) > 1) {
                    minKeyWidth = Math.max(minKeyWidth, (int) (TypefaceUtils.getStringWidth(strD, paint) + padding));
                }
            }
            return minKeyWidth;
        }

        public MoreKeysKeyboard build() {
            MoreKeysKeyboardParams moreKeysKeyboardParams = (MoreKeysKeyboardParams) this.mParams;
            int iM6194P = this.mParentKey.getMoreKeyLabelFlags();
            MoreKeySpec[] c1034aqArrM6245i = this.mParentKey.getMoreKeys();
            for (int i = 0; i < c1034aqArrM6245i.length; i++) {
                MoreKeySpec c1034aq = c1034aqArrM6245i[i];
                int i2 = i / moreKeysKeyboardParams.mNumColumns;
                int iM6350a = moreKeysKeyboardParams.getX(i, i2);
                int iM6353b = moreKeysKeyboardParams.getY(i2);
                Key keyM7284a = c1034aq.buildKey(iM6350a, iM6353b, iM6194P, moreKeysKeyboardParams);
                moreKeysKeyboardParams.markAsEdgeKey(keyM7284a, i2);
                moreKeysKeyboardParams.onAddKey(keyM7284a);
                int iM6349a = moreKeysKeyboardParams.getColumnPos(i);
                if (moreKeysKeyboardParams.mDividerWidth > 0 && iM6349a != 0) {
                    moreKeysKeyboardParams.onAddKey(new MoreKeysDivider(moreKeysKeyboardParams, iM6349a > 0 ? iM6350a - moreKeysKeyboardParams.mDividerWidth : iM6350a + moreKeysKeyboardParams.mDefaultKeyWidth, iM6353b, moreKeysKeyboardParams.mDividerWidth, moreKeysKeyboardParams.mDefaultRowHeight));
                }
            }
            return new MoreKeysKeyboard(moreKeysKeyboardParams);
        }
    }

    
    public static class MoreKeysDivider extends Key.SpacerKey {
        /**
         * @param keyboardParams the popup's params
         * @param x x
         * @param y y
         * @param width width
         * @param height height
         */
        public MoreKeysDivider(KeyboardParams keyboardParams, int x, int y, int width, int height) {
            super(keyboardParams, x, y, width, height);
        }
    }
}

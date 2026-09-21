package dev.bbkb.ime.keyboard.internal;

import android.content.Context;
import android.graphics.Paint;

import dev.bbkb.ime.keyboard.Key;
import dev.bbkb.ime.keyboard.Keyboard;
import dev.bbkb.ime.R;
import dev.bbkb.ime.core.shared.TypefaceUtils;


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

        public void setParameters(int i, int i2, int i3, int i4, int i5, int i6, boolean z, boolean z2, int i7) {
            int iM6344c;
            int iM6345d;
            this.mIsMoreKeysFixedOrder = z2;
            if (i6 / i3 < Math.min(i, i2)) {
                throw new IllegalArgumentException("Keyboard is too small to hold more keys: " + i6 + " " + i3 + " " + i + " " + i2);
            }
            this.mDefaultKeyWidth = i3;
            this.mDefaultRowHeight = i4;
            this.mNumRows = ((i + i2) - 1) / i2;
            if (z) {
                iM6344c = Math.min(i, i2);
            } else {
                iM6344c = getOptimizedColumns(i, i2);
            }
            this.mNumColumns = iM6344c;
            int i8 = i % iM6344c;
            if (i8 == 0) {
                i8 = iM6344c;
            }
            this.mTopKeys = i8;
            int i9 = (iM6344c - 1) / 2;
            int i10 = iM6344c - i9;
            int i11 = i5 / i3;
            int i12 = (i6 - i5) / i3;
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
            if (z2) {
                iM6345d = getFixedOrderTopRowAdjustment();
            } else {
                iM6345d = getAutoOrderTopRowAdjustment();
            }
            this.mTopRowAdjustment = iM6345d;
            this.mDividerWidth = i7;
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

        int getColumnPos(int i) {
            return this.mIsMoreKeysFixedOrder ? getFixedOrderColumnPos(i) : getAutoOrderColumnPos(i);
        }

        private int getFixedOrderColumnPos(int i) {
            int i2 = this.mNumColumns;
            int i3 = i % i2;
            if (!isTopRow(i / i2)) {
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

        private int getAutoOrderColumnPos(int i) {
            int i2 = this.mNumColumns;
            int i3 = i % i2;
            int i4 = i / i2;
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

        private static int getTopRowEmptySlots(int i, int i2) {
            int i3 = i % i2;
            if (i3 == 0) {
                return 0;
            }
            return i2 - i3;
        }

        private int getOptimizedColumns(int i, int i2) {
            int iMin = Math.min(i, i2);
            while (getTopRowEmptySlots(i, iMin) >= this.mNumRows) {
                iMin--;
            }
            return iMin;
        }

        public int getDefaultKeyCoordX() {
            return (this.mLeftKeys * this.mColumnWidth) + this.mLeftPadding;
        }

        public int getX(int i, int i2) {
            int iM6349a = (getColumnPos(i) * this.mColumnWidth) + getDefaultKeyCoordX();
            return isTopRow(i2) ? iM6349a + (this.mTopRowAdjustment * (this.mColumnWidth / 2)) : iM6349a;
        }

        public int getY(int i) {
            return (((this.mNumRows - 1) - i) * this.mDefaultRowHeight) + this.mTopPadding;
        }

        public void markAsEdgeKey(Key key, int i) {
            if (i == 0) {
                key.setTopEdge(this);
            }
            if (isTopRow(i)) {
                key.setBottomEdge(this);
            }
        }

        private boolean isTopRow(int i) {
            int i2 = this.mNumRows;
            return i2 > 1 && i == i2 - 1;
        }
    }

    
    public static class Builder extends KeyboardXMLParser<MoreKeysKeyboardParams> {

        private final Key mParentKey;

        public Builder(Context context, Key key, Keyboard c0965e, boolean z, int i, int i2, Paint paint) {
            super(context, new MoreKeysKeyboardParams());
            int iM6354a;
            int i3;
            load(c0965e.mMoreKeysTemplate, c0965e.mId);
            this.mParams.mVerticalGap = c0965e.mVerticalGap / 2;
            this.mParentKey = key;
            if (z) {
                iM6354a = i;
                i3 = i2 + this.mParams.mVerticalGap;
            } else {
                iM6354a = getMaxKeyWidth(key, this.mParams.mDefaultKeyWidth, context.getResources().getDimension(R.dimen.config_more_keys_keyboard_key_horizontal_padding) + (key.hasLabelsInMoreKeys() ? this.mParams.mDefaultKeyWidth * 0.2f : 0.0f), paint);
                i3 = c0965e.mMostCommonKeyHeight;
            }
            this.mParams.setParameters(key.getMoreKeys().length, key.getMoreKeysColumnNumber(), iM6354a, i3, key.getX() + (key.getWidth() / 2), c0965e.mId.mWidth, key.isMoreKeysFixedColumn(), key.isMoreKeysFixedOrder(), key.needsDividersInMoreKeys() ? (int) (iM6354a * 0.2f) : 0);
        }

        private static int getMaxKeyWidth(Key key, int i, float f, Paint paint) {
            for (MoreKeySpec c1034aq : key.getMoreKeys()) {
                String strD = c1034aq.getLabel();
                if (strD != null && (strD).codePointCount(0, (strD).length()) > 1) {
                    i = Math.max(i, (int) (TypefaceUtils.getStringWidth(strD, paint) + f));
                }
            }
            return i;
        }

        @Override // dev.bbkb.ime.keyboard.internal.KeyboardXMLParser
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
        public MoreKeysDivider(KeyboardParams c1025ah, int i, int i2, int i3, int i4) {
            super(c1025ah, i, i2, i3, i4);
        }
    }
}

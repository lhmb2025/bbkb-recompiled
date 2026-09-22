package dev.bbkb.ime.keyboard.auxbar.suggestions;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;

import dev.bbkb.ime.R;
import dev.bbkb.ime.core.suggestion.SuggestedWords;
import dev.bbkb.ime.core.shared.TypefaceUtils;
import dev.bbkb.ime.keyboard.Key;
import dev.bbkb.ime.keyboard.Keyboard;
import dev.bbkb.ime.keyboard.internal.KeyHintPosition;
import dev.bbkb.ime.keyboard.internal.KeyboardIconSet;
import dev.bbkb.ime.keyboard.internal.MoreKeySpec;
import dev.bbkb.ime.keyboard.internal.KeyboardParams;
import dev.bbkb.ime.keyboard.internal.KeyboardXMLParser;



public final class MoreSuggestionsKeyboard extends Keyboard {

    public final SuggestedWords mSuggestedWords;

    MoreSuggestionsKeyboard(MoreSuggestionsParam dVar, SuggestedWords c0666ac) {
        super(dVar);
        this.mSuggestedWords = c0666ac;
    }

    
    private static final class MoreSuggestionsParam extends KeyboardParams {

        private static final int[][] COLUMN_ORDER = {new int[]{0}, new int[]{1, 0}, new int[]{1, 0, 2}};

        public Drawable mDivider;

        public int mDividerWidth;

        /**
         * Column divider for the more-suggestions popup, themed. The sized fill()
         * overload is required: mDividerWidth reads getIntrinsicWidth() and feeds the
         * column layout maths.
         */
        private static Drawable makeDivider(Resources resources) {
            final int widthPx = Math.max(1,
                    Math.round(resources.getDisplayMetrics().density));
            return dev.bbkb.ime.keyboard.KeyboardColorManager.fill(
                    dev.bbkb.ime.keyboard.KeyboardColorManager.INSTANCE
                            .getIconColor(dev.bbkb.ime.keyboard.KeyboardColorManager.ALPHA_DISABLED),
                    widthPx, resources.getDisplayMetrics().heightPixels);
        }

        private final int[] mWidths = new int[SuggestedWords.getMaxSuggestionCount()];

        private final int[] mRowNumbers = new int[SuggestedWords.getMaxSuggestionCount()];

        private final int[] mColumnOrders = new int[SuggestedWords.getMaxSuggestionCount()];

        private final int[] mNumColumnsInRow = new int[SuggestedWords.getMaxSuggestionCount()];

        private int mNumRows;

        public int layout(SuggestedWords c0666ac, int i, int i2, int i3, int i4, Paint paint, Resources resources) throws Resources.NotFoundException {
            String strMo4286b;
            clearKeys();
            this.mDivider = makeDivider(resources);
            this.mDividerWidth = this.mDivider.getIntrinsicWidth();
            float dimension = resources.getDimension(R.dimen.config_more_suggestions_key_horizontal_padding);
            int iMin = Math.min(c0666ac.size(), 18);
            int i5 = i;
            int i6 = i5;
            int i7 = 0;
            while (i5 < iMin) {
                if (MoreSuggestionsKeyboard.isAutoCorrectionIndex(c0666ac, i5)) {
                    strMo4286b = c0666ac.getWordForDisplay(0);
                } else {
                    strMo4286b = c0666ac.getWordForDisplay(i5);
                }
                this.mWidths[i5] = (int) (TypefaceUtils.getStringWidth(strMo4286b, paint) + dimension);
                int i8 = i5 - i6;
                int i9 = i8 + 1;
                int i10 = (i2 - (this.mDividerWidth * (i9 - 1))) / i9;
                if (i9 > 3 || !fitInWidth(i6, i5 + 1, i10)) {
                    int i11 = i7 + 1;
                    if (i11 >= i4) {
                        break;
                    }
                    this.mNumColumnsInRow[i7] = i8;
                    i6 = i5;
                    i7 = i11;
                }
                this.mColumnOrders[i5] = i5 - i6;
                this.mRowNumbers[i5] = i7;
                i5++;
            }
            this.mNumColumnsInRow[i7] = i5 - i6;
            this.mNumRows = i7 + 1;
            int iMax = Math.max(i3, calculateMaxColumnWidth(i, i5));
            this.mOccupiedWidth = iMax;
            this.mBaseWidth = iMax;
            int i12 = (this.mNumRows * this.mDefaultRowHeight) + this.mVerticalGap;
            this.mOccupiedHeight = i12;
            this.mBaseHeight = i12;
            return i5 - i;
        }

        private boolean fitInWidth(int i, int i2, int i3) {
            while (i < i2) {
                if (this.mWidths[i] > i3) {
                    return false;
                }
                i++;
            }
            return true;
        }

        private int calculateMaxColumnWidth(int i, int i2) {
            int i3 = i;
            int iMax = 0;
            for (int i4 = 0; i4 < this.mNumRows; i4++) {
                int i5 = this.mNumColumnsInRow[i4];
                int iMax2 = 0;
                while (i3 < i2 && this.mRowNumbers[i3] == i4) {
                    iMax2 = Math.max(iMax2, this.mWidths[i3]);
                    i3++;
                }
                iMax = Math.max(iMax, (iMax2 * i5) + (this.mDividerWidth * (i5 - 1)));
            }
            return iMax;
        }

        public int getNumColumnInRow(int i) {
            return this.mNumColumnsInRow[this.mRowNumbers[i]];
        }

        public int getColumnNumber(int i) {
            return COLUMN_ORDER[getNumColumnInRow(i) - 1][this.mColumnOrders[i]];
        }

        public int getX(int i) {
            return getColumnNumber(i) * (getWidth(i) + this.mDividerWidth);
        }

        public int getY(int i) {
            return (((this.mNumRows - 1) - this.mRowNumbers[i]) * this.mDefaultRowHeight) + this.mTopPadding;
        }

        public int getWidth(int i) {
            int iM5342a = getNumColumnInRow(i);
            return (this.mOccupiedWidth - (this.mDividerWidth * (iM5342a - 1))) / iM5342a;
        }

        public void markAsEdgeKey(Key key, int i) {
            int i2 = this.mRowNumbers[i];
            if (i2 == 0) {
                key.setBottomEdge(this);
            }
            if (i2 == this.mNumRows - 1) {
                key.setTopEdge(this);
            }
            int i3 = this.mNumColumnsInRow[i2];
            int iM5345b = getColumnNumber(i);
            if (iM5345b == 0) {
                key.setLeftEdge(this);
            }
            if (iM5345b == i3 - 1) {
                key.setRightEdge(this);
            }
        }
    }

    static boolean isAutoCorrectionIndex(SuggestedWords c0666ac, int i) {
        return c0666ac.mWillAutoCorrect && i == 1;
    }

    
    public static final class Builder extends KeyboardXMLParser<MoreSuggestionsParam> {

        private final MoreSuggestionsView mPaneView;

        private SuggestedWords mSuggestedWords;

        private int mFromIndex;

        private int mToIndex;

        public Builder(Context context, MoreSuggestionsView moreSuggestionsView) {
            super(context, new MoreSuggestionsParam());
            this.mPaneView = moreSuggestionsView;
        }

        public Builder layout(SuggestedWords c0666ac, int i, int i2, int i3, int i4, Keyboard c0965e) throws Resources.NotFoundException {
            load(R.xml.kbd_suggestions_pane_template, c0965e.mId);
            MoreSuggestionsParam dVar = (MoreSuggestionsParam) this.mParams;
            MoreSuggestionsParam dVar2 = (MoreSuggestionsParam) this.mParams;
            int i5 = c0965e.mVerticalGap / 2;
            dVar2.mTopPadding = i5;
            dVar.mVerticalGap = i5;
            this.mPaneView.applyDefaultRowHeight(((MoreSuggestionsParam) this.mParams).mDefaultRowHeight);
            int iM5343a = ((MoreSuggestionsParam) this.mParams).layout(c0666ac, i, i2, i3, i4, this.mPaneView.createPaintForKey((Key) null), this.mResources);
            this.mFromIndex = i;
            this.mToIndex = i + iM5343a;
            this.mSuggestedWords = c0666ac;
            return this;
        }

        @Override // dev.bbkb.ime.keyboard.internal.KeyboardXMLParser
        public MoreSuggestionsKeyboard build() {
            String strMo4286b;
            MoreSuggestionsParam dVar = (MoreSuggestionsParam) this.mParams;
            for (int i = this.mFromIndex; i < this.mToIndex; i++) {
                int iM5346c = dVar.getX(i);
                int iM5347d = dVar.getY(i);
                int iM5348e = dVar.getWidth(i);
                // UT-18: the auto-correction index shows the word at 0, not at i. The second
                // MoreSuggestionKey argument used to be SuggestedWords.getAutoCorrectWord(),
                // which was a constant null on both branches; it is gone with the method.
                strMo4286b = MoreSuggestionsKeyboard.isAutoCorrectionIndex(this.mSuggestedWords, i)
                        ? this.mSuggestedWords.getWordForDisplay(0)
                        : this.mSuggestedWords.getWordForDisplay(i);
                MoreSuggestionKey cVar = new MoreSuggestionKey(strMo4286b, i, dVar);
                dVar.markAsEdgeKey(cVar, i);
                dVar.onAddKey(cVar);
                if (dVar.getColumnNumber(i) < dVar.getNumColumnInRow(i) - 1) {
                    dVar.onAddKey(new MoreSuggestionDivider(dVar, dVar.mDivider, iM5346c + iM5348e, iM5347d, dVar.mDividerWidth, dVar.mDefaultRowHeight));
                }
            }
            return new MoreSuggestionsKeyboard(dVar, this.mSuggestedWords);
        }
    }

    
    static final class MoreSuggestionKey extends Key {

        public final int mSuggestedWordIndex;

        public MoreSuggestionKey(String str, int i, MoreSuggestionsParam dVar) {
            super(new MoreKeySpec(str, 0, -4, str), MoreKeySpec.getEmpty(), KeyHintPosition.HIDDEN, null, 0, 1, dVar.getX(i), dVar.getY(i), dVar.getWidth(i), dVar.mDefaultRowHeight, dVar.mHorizontalGap, dVar.mVerticalGap, null, null, null, 0, 2, -1);
            this.mSuggestedWordIndex = i;
        }
    }

    
    private static final class MoreSuggestionDivider extends Key.SpacerKey {

        private final Drawable mDivider;

        public MoreSuggestionDivider(KeyboardParams c1025ah, Drawable drawable, int i, int i2, int i3, int i4) {
            super(c1025ah, i, i2, i3, i4);
            this.mDivider = drawable;
        }

        @Override // dev.bbkb.ime.keyboard.Key
        public Drawable getIcon(KeyboardIconSet c1024ag, int i) {
            this.mDivider.setAlpha(128);
            return this.mDivider;
        }
    }
}

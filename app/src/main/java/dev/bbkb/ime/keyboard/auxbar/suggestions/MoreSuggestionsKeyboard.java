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

    MoreSuggestionsKeyboard(MoreSuggestionsParam params, SuggestedWords suggestedWords) {
        super(params);
        this.mSuggestedWords = suggestedWords;
    }

    
    private static final class MoreSuggestionsParam extends KeyboardParams {

        private static final int[][] COLUMN_ORDER = {new int[]{0}, new int[]{1, 0}, new int[]{1, 0, 2}};

        public Drawable mDivider;

        public int mDividerWidth;

        /** The sized fill() overload is required: mDividerWidth reads getIntrinsicWidth() for the layout maths. */
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

        /**
         * @param suggestedWords the words
         * @param fromIndex the first word index
         * @param maxWidth the maximum width
         * @param minWidth the minimum width
         * @param maxRow the maximum number of rows
         * @return how many words were laid out
         */
        public int layout(SuggestedWords suggestedWords, int fromIndex, int maxWidth, int minWidth, int maxRow, Paint paint, Resources resources) throws Resources.NotFoundException {
            String strMo4286b;
            clearKeys();
            this.mDivider = makeDivider(resources);
            this.mDividerWidth = this.mDivider.getIntrinsicWidth();
            float dimension = resources.getDimension(R.dimen.config_more_suggestions_key_horizontal_padding);
            int iMin = Math.min(suggestedWords.size(), 18);
            int i5 = fromIndex;
            int i6 = i5;
            int i7 = 0;
            while (i5 < iMin) {
                if (MoreSuggestionsKeyboard.isAutoCorrectionIndex(suggestedWords, i5)) {
                    strMo4286b = suggestedWords.getWordForDisplay(0);
                } else {
                    strMo4286b = suggestedWords.getWordForDisplay(i5);
                }
                this.mWidths[i5] = (int) (TypefaceUtils.getStringWidth(strMo4286b, paint) + dimension);
                int i8 = i5 - i6;
                int i9 = i8 + 1;
                int i10 = (maxWidth - (this.mDividerWidth * (i9 - 1))) / i9;
                if (i9 > 3 || !fitInWidth(i6, i5 + 1, i10)) {
                    int i11 = i7 + 1;
                    if (i11 >= maxRow) {
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
            int iMax = Math.max(minWidth, calculateMaxColumnWidth(fromIndex, i5));
            this.mOccupiedWidth = iMax;
            this.mBaseWidth = iMax;
            int i12 = (this.mNumRows * this.mDefaultRowHeight) + this.mVerticalGap;
            this.mOccupiedHeight = i12;
            this.mBaseHeight = i12;
            return i5 - fromIndex;
        }

        /**
         * @param startIndex the first word index
         * @param endIndex the end word index (exclusive)
         * @param width the column width
         */
        private boolean fitInWidth(int startIndex, int endIndex, int width) {
            while (startIndex < endIndex) {
                if (this.mWidths[startIndex] > width) {
                    return false;
                }
                startIndex++;
            }
            return true;
        }

        /**
         * @param startIndex the first word index
         * @param endIndex the end word index (exclusive)
         */
        private int calculateMaxColumnWidth(int startIndex, int endIndex) {
            int i3 = startIndex;
            int iMax = 0;
            for (int i4 = 0; i4 < this.mNumRows; i4++) {
                int i5 = this.mNumColumnsInRow[i4];
                int iMax2 = 0;
                while (i3 < endIndex && this.mRowNumbers[i3] == i4) {
                    iMax2 = Math.max(iMax2, this.mWidths[i3]);
                    i3++;
                }
                iMax = Math.max(iMax, (iMax2 * i5) + (this.mDividerWidth * (i5 - 1)));
            }
            return iMax;
        }

        /** @param index the word index (also for the getters below) */
        public int getNumColumnInRow(int index) {
            return this.mNumColumnsInRow[this.mRowNumbers[index]];
        }

        public int getColumnNumber(int index) {
            return COLUMN_ORDER[getNumColumnInRow(index) - 1][this.mColumnOrders[index]];
        }

        public int getX(int index) {
            return getColumnNumber(index) * (getWidth(index) + this.mDividerWidth);
        }

        public int getY(int index) {
            return (((this.mNumRows - 1) - this.mRowNumbers[index]) * this.mDefaultRowHeight) + this.mTopPadding;
        }

        public int getWidth(int index) {
            int iM5342a = getNumColumnInRow(index);
            return (this.mOccupiedWidth - (this.mDividerWidth * (iM5342a - 1))) / iM5342a;
        }

        /** @param index the word index */
        public void markAsEdgeKey(Key key, int index) {
            int i2 = this.mRowNumbers[index];
            if (i2 == 0) {
                key.setBottomEdge(this);
            }
            if (i2 == this.mNumRows - 1) {
                key.setTopEdge(this);
            }
            int i3 = this.mNumColumnsInRow[i2];
            int iM5345b = getColumnNumber(index);
            if (iM5345b == 0) {
                key.setLeftEdge(this);
            }
            if (iM5345b == i3 - 1) {
                key.setRightEdge(this);
            }
        }
    }

    /**
     * @param suggestedWords the words
     * @param index the word index
     */
    static boolean isAutoCorrectionIndex(SuggestedWords suggestedWords, int index) {
        return suggestedWords.mWillAutoCorrect && index == 1;
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

        /**
         * @param suggestedWords the words
         * @param fromIndex the first word index
         * @param maxWidth the maximum width
         * @param minWidth the minimum width
         * @param maxRow the maximum number of rows
         * @param parentKeyboard the keyboard the pane opens over
         */
        public Builder layout(SuggestedWords suggestedWords, int fromIndex, int maxWidth, int minWidth, int maxRow, Keyboard parentKeyboard) throws Resources.NotFoundException {
            load(R.xml.kbd_suggestions_pane_template, parentKeyboard.mId);
            MoreSuggestionsParam dVar = (MoreSuggestionsParam) this.mParams;
            MoreSuggestionsParam dVar2 = (MoreSuggestionsParam) this.mParams;
            int i5 = parentKeyboard.mVerticalGap / 2;
            dVar2.mTopPadding = i5;
            dVar.mVerticalGap = i5;
            this.mPaneView.applyDefaultRowHeight(((MoreSuggestionsParam) this.mParams).mDefaultRowHeight);
            int iM5343a = ((MoreSuggestionsParam) this.mParams).layout(suggestedWords, fromIndex, maxWidth, minWidth, maxRow, this.mPaneView.createPaintForKey((Key) null), this.mResources);
            this.mFromIndex = fromIndex;
            this.mToIndex = fromIndex + iM5343a;
            this.mSuggestedWords = suggestedWords;
            return this;
        }

        @Override
        public MoreSuggestionsKeyboard build() {
            String strMo4286b;
            MoreSuggestionsParam dVar = (MoreSuggestionsParam) this.mParams;
            for (int i = this.mFromIndex; i < this.mToIndex; i++) {
                int iM5346c = dVar.getX(i);
                int iM5347d = dVar.getY(i);
                int iM5348e = dVar.getWidth(i);
                // The auto-correction index shows the word at 0, not at i.
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

        /**
         * @param word the word shown
         * @param index the word index
         * @param params the pane's layout params
         */
        public MoreSuggestionKey(String word, int index, MoreSuggestionsParam params) {
            super(new MoreKeySpec(word, 0, -4, word), MoreKeySpec.getEmpty(), KeyHintPosition.HIDDEN, null, 0, 1, params.getX(index), params.getY(index), params.getWidth(index), params.mDefaultRowHeight, params.mHorizontalGap, params.mVerticalGap, null, null, null, 0, 2, -1);
            this.mSuggestedWordIndex = index;
        }
    }

    
    private static final class MoreSuggestionDivider extends Key.SpacerKey {

        private final Drawable mDivider;

        /**
         * @param keyboardParams the pane's layout params
         * @param x x
         * @param y y
         * @param width width
         * @param height height
         */
        public MoreSuggestionDivider(KeyboardParams keyboardParams, Drawable drawable, int x, int y, int width, int height) {
            super(keyboardParams, x, y, width, height);
            this.mDivider = drawable;
        }

        /**
         * @param keyboardIconSet unused
         * @param alpha unused
         */
        @Override
        public Drawable getIcon(KeyboardIconSet keyboardIconSet, int alpha) {
            this.mDivider.setAlpha(128);
            return this.mDivider;
        }
    }
}

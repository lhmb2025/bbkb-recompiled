package dev.bbkb.ime.keyboard.auxbar.suggestions;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Rect;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import dev.bbkb.ime.R;
import dev.bbkb.ime.core.suggestion.SuggestedWords;
import dev.bbkb.ime.core.locale.LocaleUtils;
import dev.bbkb.ime.core.shared.TypefaceUtils;

import java.util.Locale;


public final class CJKSuggestionGridView extends RelativeLayout {

    private Listener mListener;

    private RecyclerView mRecyclerView;

    private final int mMinHeight;

    private int mVisibleHeight;

    
    public interface Listener {
        /** @param suggestedWordInfo the tapped candidate */
        void onCjkSuggestionSelected(SuggestedWords.SuggestedWordInfo suggestedWordInfo);
    }

    
    private class GridAdapter extends CJKSuggestionsAdapter {
        /** @param suggestedWords the initial words */
        GridAdapter(SuggestedWords suggestedWords) {
            super(suggestedWords);
        }

        // One listener for every word view (reads the position at click time), not one per bind.
        private final View.OnClickListener mWordClick = new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                SuggestedWords words = getSuggestedWords();
                Object tag = view.getTag();
                if (words == null || !(tag instanceof Integer)) {
                    return;
                }
                int index = (Integer) tag;
                if (index < 0 || index >= words.size()) {
                    return;
                }
                CJKSuggestionGridView.this.mListener.onCjkSuggestionSelected(words.getWordInfo(index));
                CJKSuggestionGridView.this.setVisible(false);
            }
        };

        /**
         * @param holder the holder to bind
         * @param position the word's position
         */
        @Override
        public void onBindViewHolder(CJKSuggestionsAdapter.ViewHolder holder, int position) {
            final SuggestedWords c0666acD = getSuggestedWords();
            TextView textView = holder.mWordView;
            View view = holder.itemView;
            // setTextLocale forces a relayout of the view even when the locale is the one it has.
            if (LocaleUtils.isCurrentSubtypeJapanese() && !Locale.JAPANESE.equals(textView.getTextLocale())) {
                textView.setTextLocale(Locale.JAPANESE);
            }
            textView.setTag(Integer.valueOf(position));
            textView.setOnClickListener(mWordClick);
            // Each view's own params, changed only when they differ.
            applySize(view, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            applySize(textView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            textView.setTextColor(CJKSuggestionGridView.this.getResources().getColor(R.color.suggested_word_dark));
            String strMo4284a = (c0666acD == null || c0666acD.isEmpty()) ? null : c0666acD.getWord(position);
            // Reset to the base size first, or the shrink below compounds on a recycled view.
            textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, holder.mBaseTextSizePx);
            if (strMo4284a != null && strMo4284a.length() > 18) {
                TextPaint paint = textView.getPaint();
                float scale = Math.min(1.0f, (CJKSuggestionGridView.this.mRecyclerView.getWidth() * 0.9f)
                        / TypefaceUtils.getStringWidth(strMo4284a, paint));
                textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, holder.mBaseTextSizePx * scale);
            }
            textView.setText(strMo4284a);
            textView.setContentDescription(strMo4284a);
        }

        private void applySize(View target, int width, int height) {
            ViewGroup.LayoutParams params = target.getLayoutParams();
            if (params == null || (params.width == width && params.height == height)) {
                return;
            }
            params.width = width;
            params.height = height;
            target.setLayoutParams(params);
        }

        /** @param position the word's position */
        public int getSpanSize(int position) {
            SuggestedWords c0666acD = getSuggestedWords();
            if (c0666acD.isEmpty()) {
                return 1;
            }
            String strMo4284a = c0666acD.getWord(position);
            int i2 = LocaleUtils.isCurrentSubtypeJapanese() ? 3 : 4;
            int length = strMo4284a.length();
            if (length <= i2) {
                return 1;
            }
            if (length <= i2 * 2) {
                return 2;
            }
            return length <= i2 * 3 ? 3 : 4;
        }
    }

    
    private class SpacingItemDecoration extends RecyclerView.ItemDecoration {

        private final int mSpacing;

        /** @param spacing the vertical spacing in px */
        public SpacingItemDecoration(int spacing) {
            this.mSpacing = spacing;
        }

        @Override
        public void getItemOffsets(Rect rect, View view, RecyclerView recyclerView, RecyclerView.State state) {
            int i = this.mSpacing;
            rect.top = i;
            rect.bottom = i;
        }
    }

    public CJKSuggestionGridView(Context context, AttributeSet attributeSet) {
        this(context, attributeSet, 0);
    }

    /** @param defStyleAttr the default style attribute */
    public CJKSuggestionGridView(Context context, AttributeSet attributeSet, int defStyleAttr) throws Resources.NotFoundException {
        super(context, attributeSet, defStyleAttr);
        this.mMinHeight = (int) getResources().getDimension(R.dimen.config_cjk_suggestions_grid_min_height);
        this.mVisibleHeight = this.mMinHeight;
        LayoutInflater.from(context).inflate(R.layout.cjk_suggestions_grid, this);
        this.mRecyclerView = (RecyclerView) findViewById(R.id.rvCJKGrid);
        final GridAdapter c0816a = new GridAdapter(SuggestedWords.EMPTY);
        this.mRecyclerView.addItemDecoration(new SpacingItemDecoration(getResources().getDimensionPixelSize(R.dimen.config_cjk_grid_vertical_margin)));
        this.mRecyclerView.setAdapter(c0816a);
        // A page binds 20 to 30 words; the default pool of 5 inflates per keystroke. No animator:
        // ranged updates would otherwise cross-fade.
        this.mRecyclerView.getRecycledViewPool().setMaxRecycledViews(0, 32);
        this.mRecyclerView.setItemAnimator(null);
        GridLayoutManager gridLayoutManager = new GridLayoutManager(context, 5);
        gridLayoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            /** @param position the word's position */
            public int getSpanSize(int position) {
                return c0816a.getSpanSize(position);
            }
        });
        this.mRecyclerView.setLayoutManager(gridLayoutManager);
        setVisible(false);
    }

    /** @param visible true to show the grid */
    public void setVisible(boolean visible) {
        setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    public boolean isVisible() {
        return getVisibility() == View.VISIBLE;
    }

    /** @param suggestedWords the words to show */
    public void setSuggestions(SuggestedWords suggestedWords) {
        GridAdapter c0816a = (GridAdapter) this.mRecyclerView.getAdapter();
        int oldCount = c0816a.getItemCount();
        c0816a.setSuggestedWords(suggestedWords);
        int newCount = c0816a.getItemCount();
        // Ranged notifications rebind in place; notifyDataSetChanged re-attaches every view.
        int common = Math.min(oldCount, newCount);
        if (common > 0) {
            c0816a.notifyItemRangeChanged(0, common);
        }
        if (newCount > oldCount) {
            c0816a.notifyItemRangeInserted(common, newCount - oldCount);
        } else if (oldCount > newCount) {
            c0816a.notifyItemRangeRemoved(common, oldCount - newCount);
        }
        this.mRecyclerView.scrollToPosition(0);
    }

    /** @param listener receives tapped candidates */
    public void setListener(Listener listener) {
        this.mListener = listener;
    }

    /** @param height the height in px (at least the minimum) */
    public void setVisibleHeight(int height) {
        this.mVisibleHeight = Math.max(height, this.mMinHeight);
    }

    /**
     * @param widthMeasureSpec the width measure spec
     * @param heightMeasureSpec the height measure spec (ignored)
     */
    @Override
    public void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(View.MeasureSpec.getSize(widthMeasureSpec), this.mVisibleHeight);
        super.onMeasure(View.MeasureSpec.makeMeasureSpec(getMeasuredWidth(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(getMeasuredHeight(), View.MeasureSpec.EXACTLY));
    }
}

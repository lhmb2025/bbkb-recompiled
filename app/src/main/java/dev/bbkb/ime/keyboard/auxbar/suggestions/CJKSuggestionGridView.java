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
        void onCjkSuggestionSelected(SuggestedWords.SuggestedWordInfo suggestedWordInfoVar);
    }

    
    private class GridAdapter extends CJKSuggestionsAdapter {
        GridAdapter(SuggestedWords c0666ac) {
            super(c0666ac);
        }

        @Override
        public void onBindViewHolder(CJKSuggestionsAdapter.ViewHolder bVar, int i) {
            final SuggestedWords c0666acD = getSuggestedWords();
            TextView textView = bVar.mWordView;
            View view = bVar.itemView;
            if (LocaleUtils.isCurrentSubtypeJapanese()) {
                textView.setTextLocale(Locale.JAPANESE);
            }
            textView.setTag(Integer.valueOf(i));
            textView.setOnClickListener(new View.OnClickListener() {
                @Override // android.view.View.OnClickListener
                public void onClick(View view2) {
                    CJKSuggestionGridView.this.mListener.onCjkSuggestionSelected(c0666acD.getWordInfo(((Integer) view2.getTag()).intValue()));
                    CJKSuggestionGridView.this.setVisible(false);
                }
            });
            // Sizes are applied to each view's OWN params (they used to share one freshly
            // allocated LinearLayout.LayoutParams instance, which also replaced the item's
            // RecyclerView.LayoutParams) and only when they actually differ.
            applySize(view, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            applySize(textView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            textView.setTextColor(CJKSuggestionGridView.this.getResources().getColor(R.color.suggested_word_dark));
            String strMo4284a = (c0666acD == null || c0666acD.isEmpty()) ? null : c0666acD.getWord(i);
            // Always reset to the holder's base size first: the shrink below used to multiply
            // into the RECYCLED view's current size, so one long word shrank the grid for good.
            textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, bVar.mBaseTextSizePx);
            if (strMo4284a != null && strMo4284a.length() > 18) {
                TextPaint paint = textView.getPaint();
                float scale = Math.min(1.0f, (CJKSuggestionGridView.this.mRecyclerView.getWidth() * 0.9f)
                        / TypefaceUtils.getStringWidth(strMo4284a, paint));
                textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, bVar.mBaseTextSizePx * scale);
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

        public int getSpanSize(int i) {
            SuggestedWords c0666acD = getSuggestedWords();
            if (c0666acD.isEmpty()) {
                return 1;
            }
            String strMo4284a = c0666acD.getWord(i);
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

        public SpacingItemDecoration(int i) {
            this.mSpacing = i;
        }

        @Override // androidx.recyclerview.widget.RecyclerView.ItemDecoration
        public void getItemOffsets(Rect rect, View view, RecyclerView recyclerView, RecyclerView.State state) {
            int i = this.mSpacing;
            rect.top = i;
            rect.bottom = i;
        }
    }

    public CJKSuggestionGridView(Context context, AttributeSet attributeSet) {
        this(context, attributeSet, 0);
    }

    public CJKSuggestionGridView(Context context, AttributeSet attributeSet, int i) throws Resources.NotFoundException {
        super(context, attributeSet, i);
        this.mMinHeight = (int) getResources().getDimension(R.dimen.config_cjk_suggestions_grid_min_height);
        this.mVisibleHeight = this.mMinHeight;
        LayoutInflater.from(context).inflate(R.layout.cjk_suggestions_grid, this);
        this.mRecyclerView = (RecyclerView) findViewById(R.id.rvCJKGrid);
        final GridAdapter c0816a = new GridAdapter(SuggestedWords.EMPTY);
        this.mRecyclerView.addItemDecoration(new SpacingItemDecoration(getResources().getDimensionPixelSize(R.dimen.config_cjk_grid_vertical_margin)));
        this.mRecyclerView.setAdapter(c0816a);
        GridLayoutManager gridLayoutManager = new GridLayoutManager(context, 5);
        gridLayoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override // androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup
            public int getSpanSize(int i2) {
                return c0816a.getSpanSize(i2);
            }
        });
        this.mRecyclerView.setLayoutManager(gridLayoutManager);
        setVisible(false);
    }

    public void setVisible(boolean z) {
        setVisibility(z ? View.VISIBLE : View.GONE);
    }

    public boolean isVisible() {
        return getVisibility() == View.VISIBLE;
    }

    public void setSuggestions(SuggestedWords c0666ac) {
        GridAdapter c0816a = (GridAdapter) this.mRecyclerView.getAdapter();
        c0816a.setSuggestedWords(c0666ac);
        c0816a.notifyDataSetChanged();
        this.mRecyclerView.scrollToPosition(0);
    }

    public void setListener(Listener interfaceC0817b) {
        this.mListener = interfaceC0817b;
    }

    public void setVisibleHeight(int i) {
        this.mVisibleHeight = Math.max(i, this.mMinHeight);
    }

    @Override // android.widget.RelativeLayout, android.view.View
    public void onMeasure(int i, int i2) {
        // getSize(i): `i` is the width MeasureSpec (a packed mode+size int), not a width.
        setMeasuredDimension(View.MeasureSpec.getSize(i), this.mVisibleHeight);
        super.onMeasure(View.MeasureSpec.makeMeasureSpec(getMeasuredWidth(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(getMeasuredHeight(), View.MeasureSpec.EXACTLY));
    }
}

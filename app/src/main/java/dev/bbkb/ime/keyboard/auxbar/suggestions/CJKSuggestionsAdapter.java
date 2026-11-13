package dev.bbkb.ime.keyboard.auxbar.suggestions;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import dev.bbkb.ime.R;
import dev.bbkb.ime.core.suggestion.SuggestedWords;



abstract class CJKSuggestionsAdapter extends RecyclerView.Adapter<CJKSuggestionsAdapter.ViewHolder> {

    private SuggestedWords mSuggestedWords;

    
    static class ViewHolder extends RecyclerView.ViewHolder {

        TextView mWordView;

        /**
         * The layout's text size in px. Shrink long candidates from this, never from the recycled
         * view's current size, or one long word shrinks every later word in the holder.
         */
        final float mBaseTextSizePx;

        ViewHolder(View view) {
            super(view);
            this.mWordView = (TextView) view.findViewById(R.id.suggested_word);
            this.mBaseTextSizePx = this.mWordView.getTextSize();
        }
    }

    /** @param suggestedWords the initial words */
    CJKSuggestionsAdapter(SuggestedWords suggestedWords) {
        this.mSuggestedWords = suggestedWords;
    }

    /** @param viewType the view type (only one) */
    @Override
    public ViewHolder onCreateViewHolder(ViewGroup viewGroup, int viewType) {
        return new ViewHolder(LayoutInflater.from(viewGroup.getContext()).inflate(R.layout.cjk_suggested_word, viewGroup, false));
    }

    @Override
    public int getItemCount() {
        return this.mSuggestedWords.size();
    }

    public SuggestedWords getSuggestedWords() {
        return this.mSuggestedWords;
    }

    /** @param suggestedWords the words to show */
    public void setSuggestedWords(SuggestedWords suggestedWords) {
        this.mSuggestedWords = suggestedWords;
    }
}

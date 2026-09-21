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
         * The layout's text size in px. Long candidates are shrunk to fit; the scale must be
         * applied to THIS, not multiplied into the recycled view's current size, or every long
         * word permanently shrinks whatever word later lands in the holder.
         */
        final float mBaseTextSizePx;

        ViewHolder(View view) {
            super(view);
            this.mWordView = (TextView) view.findViewById(R.id.suggested_word);
            this.mBaseTextSizePx = this.mWordView.getTextSize();
        }
    }

    CJKSuggestionsAdapter(SuggestedWords c0666ac) {
        this.mSuggestedWords = c0666ac;
    }

    @Override // androidx.recyclerview.widget.RecyclerView.Adapter
    public ViewHolder onCreateViewHolder(ViewGroup viewGroup, int i) {
        return new ViewHolder(LayoutInflater.from(viewGroup.getContext()).inflate(R.layout.cjk_suggested_word, viewGroup, false));
    }

    @Override // androidx.recyclerview.widget.RecyclerView.Adapter
    public int getItemCount() {
        return this.mSuggestedWords.size();
    }

    public SuggestedWords getSuggestedWords() {
        return this.mSuggestedWords;
    }

    public void setSuggestedWords(SuggestedWords c0666ac) {
        this.mSuggestedWords = c0666ac;
    }
}

package dev.bbkb.ime.keyboard.slideboard;

import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import dev.bbkb.ime.R;
import dev.bbkb.ime.keyboard.KeyboardColorManager;

import java.util.List;



public class QuickPhrasesAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private List<QuickPhrase> phrases;

    private Listener listener;

    
    interface Listener {
        void onPhraseClicked(QuickPhrase phrase);
    }

    
    public static class QuickPhrase {

        final String text;

        QuickPhrase(String text) {
            this.text = text;
        }

        public String toString() {
            return this.text;
        }
    }

    
    private static class ViewHolder extends RecyclerView.ViewHolder {

        final TextView textView;

        final View container;

        QuickPhrase phrase;

        ViewHolder(View view) {
            super(view);
            this.textView = (TextView) view.findViewById(R.id.quick_phrase_text);
            this.container = view.findViewById(R.id.quick_phrase_text_container);
        }
    }

    /** Row height in px (tile plus gap) that fills the view; 0 = the default height. */
    private int tileHeight;

    QuickPhrasesAdapter(List<QuickPhrase> list) {
        this.phrases = list;
    }

    void setPhrases(List<QuickPhrase> list) {
        this.phrases = list;
    }

    /** @return whether the height changed (the caller then rebinds). */
    boolean setTileHeight(int px) {
        if (px == this.tileHeight) {
            return false;
        }
        this.tileHeight = px;
        return true;
    }

    /** @param viewType the view type (only one) */
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup viewGroup, int viewType) {
        LayoutInflater layoutInflaterFrom = LayoutInflater.from(viewGroup.getContext());
        return new ViewHolder(layoutInflaterFrom.inflate(R.layout.quick_phrase_list_item, viewGroup, false));
    }

    /** @param position the phrase's position */
    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
        bindViewHolder((ViewHolder) holder, position);
    }

    @Override
    public int getItemCount() {
        return this.phrases.size();
    }

    /** @param position the phrase's position */
    private void bindViewHolder(ViewHolder holder, int position) {
        QuickPhrase phrase = this.phrases.get(position);
        holder.phrase = phrase;
        // The stored phrase carries a trailing space for committing; the tile shows it without.
        CharSequence label = phrase.toString().trim();
        holder.textView.setText(label);
        holder.container.setContentDescription(label);
        KeyboardColorManager colors = KeyboardColorManager.INSTANCE;
        holder.textView.setTextColor(colors.getSlideTextColor());
        StateListDrawable fill = new StateListDrawable();
        fill.addState(new int[]{android.R.attr.state_pressed}, KeyboardColorManager.fill(colors.getSlidePressedColor()));
        fill.addState(new int[0], KeyboardColorManager.fill(colors.getSlideKeyColor()));
        int gap = holder.container.getResources().getDimensionPixelSize(R.dimen.quick_phrase_tile_gap);
        holder.container.setBackground(new InsetDrawable(fill, 0, 0, 0, gap));
        ViewGroup.LayoutParams layoutParams = holder.container.getLayoutParams();
        int height = this.tileHeight > 0
                ? this.tileHeight
                : holder.container.getResources().getDimensionPixelSize(R.dimen.quick_phrase_tile_default_height);
        if (layoutParams.height != height) {
            layoutParams.height = height;
            holder.container.setLayoutParams(layoutParams);
        }
        setupClickListener(holder);
    }

    private void setupClickListener(final ViewHolder holder) {
        holder.container.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                QuickPhrasesAdapter.this.listener.onPhraseClicked(holder.phrase);
            }
        });
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }
}

package dev.bbkb.ime.keyboard.slideboard;

import android.content.res.Resources;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import dev.bbkb.ime.R;

import java.util.List;



public class QuickPhrasesAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private List<QuickPhrase> phrases;

    private Listener listener;

    
    interface Listener {
        void onPhraseClicked(QuickPhrase phrase);
    }

    
    public static class QuickPhrase {

        final String text;

        QuickPhrase(String str) {
            this.text = str;
        }

        public String toString() {
            return this.text;
        }
    }

    
    private static class ViewHolder extends RecyclerView.ViewHolder {

        final TextView textView;

        final RelativeLayout container;

        QuickPhrase phrase;

        ViewHolder(View view) {
            super(view);
            this.textView = (TextView) view.findViewById(R.id.quick_phrase_text);
            this.container = (RelativeLayout) view.findViewById(R.id.quick_phrase_text_container);
        }
    }

    QuickPhrasesAdapter(List<QuickPhrase> list) {
        this.phrases = list;
    }

    void setPhrases(List<QuickPhrase> list) {
        this.phrases = list;
    }

    @Override // androidx.recyclerview.widget.RecyclerView.Adapter
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup viewGroup, int i) {
        LayoutInflater layoutInflaterFrom = LayoutInflater.from(viewGroup.getContext());
        return new ViewHolder(layoutInflaterFrom.inflate(R.layout.quick_phrase_list_item, viewGroup, false));
    }

    @Override // androidx.recyclerview.widget.RecyclerView.Adapter
    public void onBindViewHolder(RecyclerView.ViewHolder holder, int i) throws Resources.NotFoundException {
        bindViewHolder((ViewHolder) holder, i);
    }

    @Override // androidx.recyclerview.widget.RecyclerView.Adapter
    public int getItemCount() {
        return this.phrases.size();
    }

    private void bindViewHolder(ViewHolder holder, int i) throws Resources.NotFoundException {
        QuickPhrase phrase = this.phrases.get(i);
        holder.phrase = phrase;
        holder.textView.setText(phrase.toString());
        ViewGroup.LayoutParams layoutParams = holder.container.getLayoutParams();
        if (i == 0) {
            layoutParams.height = holder.container.getResources().getDimensionPixelSize(R.dimen.quick_phrase_list_item_height_first_item);
            holder.textView.setPadding(0, holder.container.getResources().getDimensionPixelSize(R.dimen.quick_phrase_list_item_top_padding_first_item), 0, 0);
        } else {
            layoutParams.height = holder.container.getResources().getDimensionPixelSize(R.dimen.quick_phrase_list_item_height_normal_item);
            holder.textView.setPadding(0, holder.container.getResources().getDimensionPixelSize(R.dimen.quick_phrase_list_item_top_padding_normal_item), 0, 0);
        }
        holder.container.setLayoutParams(layoutParams);
        setupClickListener(holder);
    }

    private void setupClickListener(final ViewHolder holder) {
        holder.container.setOnClickListener(new View.OnClickListener() {
            @Override // android.view.View.OnClickListener
            public void onClick(View view) {
                QuickPhrasesAdapter.this.listener.onPhraseClicked(holder.phrase);
            }
        });
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }
}

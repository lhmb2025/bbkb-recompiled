package dev.bbkb.ime.keyboard.auxbar;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import dev.bbkb.ime.R;
import dev.bbkb.ime.core.suggestion.SuggestedWords;
import dev.bbkb.ime.keyboard.KeyboardColorManager;

import android.widget.ImageView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import dev.bbkb.ime.BuildConfig;

/**
 * Adapter for every suggestion mode (Latin, CJK, autofill, language chips).
 *
 * <p>Word updates ({@link #setSuggestions}) notify item ranges so bound holders rebind in place
 * on every keystroke; mode, palette and width changes rebind wholesale. The RecyclerView runs no
 * item animator, so ranged notifications do not animate.
 */
public class UnifiedSuggestionAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final String DIAG = "INLINE_AUTOFILL_DEBUG";
    static final int VIEW_TYPE_WORD = 0;
    private static final int VIEW_TYPE_AUTOFILL = 1;

    private static final int LATIN_MAX_SUGGESTIONS = 3;

    // Minimum text scale before ellipsizing.
    private static final float MIN_TEXT_SCALE = 0.7f;

    private int availableWidth = 0;

    /** Bumped by {@link #onPaletteChanged()}; holders rebuild palette drawables when stale. */
    private int paletteGeneration = 0;

    private final int minWidthMaterialPx;

    private SuggestionMode currentMode = SuggestionMode.LATIN;
    private SuggestedWords suggestedWords;
    private List<Object> autofillSuggestions = new ArrayList<>();
    private int autofillWidth = 100;
    private int autofillHeight = 40;
    
    private final Context context;
    private final OnSuggestionClickListener listener;
    private final Executor mainExecutor;

    public interface OnSuggestionClickListener {
        void onSuggestionClick(int position, SuggestedWords.SuggestedWordInfo wordInfo);
        void onSuggestionLongClick(int position, SuggestedWords.SuggestedWordInfo wordInfo);
        void onAutofillClick(int position);
        void onLanguageChipClick(LanguageChip chip);
    }

    private List<LanguageChip> languageChips = new ArrayList<>();

    /** The chips of {@link SuggestionMode#LANGUAGES}, in the order they are shown. */
    public void setLanguageChips(List<LanguageChip> chips) {
        this.languageChips = chips == null ? new ArrayList<>() : new ArrayList<>(chips);
        if (currentMode == SuggestionMode.LANGUAGES) {
            notifyDataSetChanged();
        }
    }

    /** Read off the holder, not its adapter position, which is NO_POSITION while an update is pending. */
    private LanguageChip boundChip(WordViewHolder holder) {
        return currentMode == SuggestionMode.LANGUAGES ? holder.chip : null;
    }

    public UnifiedSuggestionAdapter(Context context, OnSuggestionClickListener listener) {
        this.context = context;
        this.listener = listener;
        // ContextCompat: Context.getMainExecutor() itself is API 28 and this runs for every input view.
        this.mainExecutor = ContextCompat.getMainExecutor(context);
        this.minWidthMaterialPx = context.getResources()
                .getDimensionPixelSize(R.dimen.config_suggestion_min_width_material);
    }

    public void setMode(SuggestionMode mode) {
        if (this.currentMode != mode) {
            this.currentMode = mode;
            notifyDataSetChanged();
        }
    }

    public SuggestionMode getMode() {
        return currentMode;
    }

    public void setSuggestions(SuggestedWords words) {
        int oldCount = getItemCount();
        this.suggestedWords = words;
        if (currentMode == SuggestionMode.AUTOFILL) {
            return;
        }
        if (currentMode == SuggestionMode.LANGUAGES) {
            // The chips are on screen, not the words: nothing visible changed.
            return;
        }
        int newCount = getItemCount();
        int common = Math.min(oldCount, newCount);
        if (common > 0) {
            notifyItemRangeChanged(0, common);
        }
        if (newCount > oldCount) {
            notifyItemRangeInserted(oldCount, newCount - oldCount);
        } else if (oldCount > newCount) {
            notifyItemRangeRemoved(newCount, oldCount - newCount);
        }
    }

    /** For a result that draws exactly like the bound one: clicks report the newest words, no rebind. */
    void replaceSuggestionsWithoutRebind(SuggestedWords words) {
        this.suggestedWords = words;
    }

    /** Invalidates palette-derived per-holder state (the pressed highlight). */
    public void onPaletteChanged() {
        this.paletteGeneration++;
        notifyDataSetChanged();
    }

    @RequiresApi(api = Build.VERSION_CODES.R)
    public void setAutofillSuggestions(List<?> suggestions, int width, int height) {
        this.autofillSuggestions.clear();
        if (suggestions != null) {
            this.autofillSuggestions.addAll(suggestions);
        }
        this.autofillWidth = width;
        this.autofillHeight = height;
        if (BuildConfig.DEBUG) {
        android.util.Log.d(DIAG, "[ADAPTER] setAutofillSuggestions()"
                + " | count=" + this.autofillSuggestions.size()
                + " | width=" + width + " | height=" + height
                + " | currentMode=" + currentMode
                + " | willNotify=" + (currentMode == SuggestionMode.AUTOFILL));
        }
        
        if (currentMode == SuggestionMode.AUTOFILL) {
            notifyDataSetChanged();
        } else {
            if (BuildConfig.DEBUG) {
            android.util.Log.d(DIAG, "[ADAPTER] setAutofillSuggestions() WARNING: mode is " + currentMode
                    + " not AUTOFILL - notifyDataSetChanged NOT called - UI will not update!");
            }
        }
    }

    public void clear() {
        this.suggestedWords = null;
        this.autofillSuggestions.clear();
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return currentMode == SuggestionMode.AUTOFILL ? VIEW_TYPE_AUTOFILL : VIEW_TYPE_WORD;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // The parent's context carries the keyboard theme (?attr/suggestionWordStyle).
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        
        if (viewType == VIEW_TYPE_AUTOFILL) {
            View view = inflater.inflate(R.layout.autofill_suggestion_item, parent, false);
            final AutofillViewHolder holder = new AutofillViewHolder(view);
            // Position is read at click time.
            view.setOnClickListener(v -> {
                int position = holder.getBindingAdapterPosition();
                if (BuildConfig.DEBUG) {
                android.util.Log.d(DIAG, "[INTERACTION] autofill suggestion tapped at position " + position);
                }
                if (listener != null && position != RecyclerView.NO_POSITION) {
                    listener.onAutofillClick(position);
                }
            });
            return holder;
        }
        View view = inflater.inflate(R.layout.suggestion_word_item, parent, false);
        final WordViewHolder holder = new WordViewHolder(view);
        // Installed per holder, not per bind; the position is read at click time.
        view.setOnClickListener(v -> {
            if (listener == null) return;
            LanguageChip chip = boundChip(holder);
            if (chip != null) {
                listener.onLanguageChipClick(chip);
                return;
            }
            SuggestedWords.SuggestedWordInfo wordInfo = boundWordInfo(holder);
            if (wordInfo != null) {
                listener.onSuggestionClick(holder.getBindingAdapterPosition(), wordInfo);
            }
        });
        view.setOnLongClickListener(v -> {
            SuggestedWords.SuggestedWordInfo wordInfo = boundWordInfo(holder);
            if (wordInfo != null && listener != null) {
                listener.onSuggestionLongClick(holder.getBindingAdapterPosition(), wordInfo);
                return true;
            }
            return false;
        });
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof WordViewHolder && currentMode == SuggestionMode.LANGUAGES) {
            bindLanguageChip((WordViewHolder) holder, position);
        } else if (holder instanceof WordViewHolder) {
            bindWordViewHolder((WordViewHolder) holder, position);
        } else if (holder instanceof AutofillViewHolder) {
            bindAutofillViewHolder((AutofillViewHolder) holder, position);
        }
    }

    /** Current language bold in the text colour, others in the hint colour; sized to the name so the row scrolls. */
    private void bindLanguageChip(WordViewHolder holder, int position) {
        if (holder.highlightGeneration != this.paletteGeneration) {
            holder.itemView.setForeground(KeyboardColorManager.pressedHighlight());
            holder.highlightGeneration = this.paletteGeneration;
        }
        ViewGroup.LayoutParams params = holder.itemView.getLayoutParams();
        if (params != null && params.width != ViewGroup.LayoutParams.WRAP_CONTENT) {
            params.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            holder.itemView.setLayoutParams(params);
        }
        // No minimum width: it would pad short names to a word slot and make the gaps uneven.
        holder.itemView.setMinimumWidth(0);
        int chipPadding = context.getResources().getDimensionPixelSize(R.dimen.config_language_chip_horizontal_padding);
        holder.textView.setPadding(chipPadding, holder.textView.getPaddingTop(), chipPadding, holder.textView.getPaddingBottom());
        if (holder.moreSuggestionsIndicator != null) {
            holder.moreSuggestionsIndicator.setVisibility(View.GONE);
        }
        holder.textView.setTextScaleX(1.0f);
        if (position >= languageChips.size()) {
            holder.chip = null;
            holder.textView.setText("");
            return;
        }
        LanguageChip chip = languageChips.get(position);
        holder.chip = chip;
        holder.textView.setText(chip.displayName);
        holder.textView.setTypeface(chip.current ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        holder.textView.setTextColor(chip.current
                ? KeyboardColorManager.INSTANCE.getTextColor()
                : KeyboardColorManager.INSTANCE.getHintColor(KeyboardColorManager.ALPHA_FULL));
    }

    /** The word a holder is currently bound to, or null if its position is stale/out of range. */
    private SuggestedWords.SuggestedWordInfo boundWordInfo(WordViewHolder holder) {
        int position = holder.getBindingAdapterPosition();
        if (position == RecyclerView.NO_POSITION || suggestedWords == null
                || position >= suggestedWords.size()) {
            return null;
        }
        return suggestedWords.getWordInfo(position);
    }

    private void bindWordViewHolder(WordViewHolder holder, int position) {
        holder.chip = null;
        // A holder that last showed a language chip carries the chip padding; words get the strip's.
        int wordPadding = context.getResources().getDimensionPixelSize(R.dimen.config_suggestion_text_horizontal_padding);
        if (holder.textView.getPaddingStart() != wordPadding) {
            holder.textView.setPadding(wordPadding, holder.textView.getPaddingTop(), wordPadding, holder.textView.getPaddingBottom());
        }
        if (suggestedWords == null || position >= suggestedWords.size()) {
            holder.textView.setText("");
            holder.textView.setTextScaleX(1.0f);
            return;
        }

        // Modern boards: the word that commits on space is bold, the others use the hint color.
        // Index 1 is the auto-correction, index 0 the typed word; with neither flag set (e.g.
        // next-word predictions) all render uniformly.
        boolean modernBoards = KeyboardColorManager.styleSpec().getModernBoards();
        boolean isDefaultCandidate = suggestedWords.mWillAutoCorrect
                ? position == 1
                : (suggestedWords.mTypedWordValid && position == 0);
        boolean hasDefaultCandidate = suggestedWords.mWillAutoCorrect || suggestedWords.mTypedWordValid;
        int textColor = (modernBoards && hasDefaultCandidate && !isDefaultCandidate)
                ? KeyboardColorManager.INSTANCE.getHintColor(KeyboardColorManager.ALPHA_FULL)
                : KeyboardColorManager.INSTANCE.getTextColor();
        holder.textView.setTextColor(textColor);
        holder.textView.setTypeface(
                modernBoards && isDefaultCandidate ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);

        // 48dp minimum touch target on modern boards only.
        holder.itemView.setMinimumWidth(modernBoards ? this.minWidthMaterialPx : 0);

        // pressedHighlight() allocates, so build once per holder per palette; a StateListDrawable
        // must not be shared across views, so it cannot be cached on the adapter.
        if (holder.highlightGeneration != this.paletteGeneration) {
            holder.itemView.setForeground(KeyboardColorManager.pressedHighlight());
            holder.highlightGeneration = this.paletteGeneration;
        }

        if (currentMode == SuggestionMode.LATIN && availableWidth > 0) {
            int itemWidth = getLatinItemWidth();
            if (BuildConfig.DEBUG) {
            android.util.Log.d("SUGGESTION_STRIP_DEBUG", "bindWordViewHolder pos=" + position
                    + " availableWidth=" + availableWidth + " itemWidth=" + itemWidth);
            }
            ViewGroup.LayoutParams params = holder.itemView.getLayoutParams();
            if (params != null && params.width != itemWidth) {
                params.width = itemWidth;
                holder.itemView.setLayoutParams(params);
            }
        } else {
            if (BuildConfig.DEBUG) {
            android.util.Log.d("SUGGESTION_STRIP_DEBUG", "bindWordViewHolder pos=" + position
                    + " WRAP_CONTENT availableWidth=" + availableWidth + " mode=" + currentMode);
            }
            ViewGroup.LayoutParams params = holder.itemView.getLayoutParams();
            if (params != null && params.width != ViewGroup.LayoutParams.WRAP_CONTENT) {
                params.width = ViewGroup.LayoutParams.WRAP_CONTENT;
                holder.itemView.setLayoutParams(params);
            }
        }
        
        SuggestedWords.SuggestedWordInfo wordInfo = suggestedWords.getWordInfo(position);
        if (wordInfo != null) {
            CharSequence text = wordInfo.word;
            holder.textView.setText(text);

            if (currentMode == SuggestionMode.LATIN && availableWidth > 0) {
                int itemWidth = getLatinItemWidth();
                int paddingHorizontal = holder.textView.getPaddingStart() + holder.textView.getPaddingEnd();
                int textAvailableWidth = itemWidth - paddingHorizontal;

                applyTextAutoShrink(holder.textView, text, textAvailableWidth);
            } else {
                holder.textView.setTextScaleX(1.0f);
            }

            // The center slot shows the more-suggestions indicator when there are more than 3.
            if (holder.moreSuggestionsIndicator != null) {
                boolean showIndicator = currentMode == SuggestionMode.LATIN 
                        && position == 1 
                        && suggestedWords != null 
                        && suggestedWords.size() > LATIN_MAX_SUGGESTIONS;
                holder.moreSuggestionsIndicator.setVisibility(showIndicator ? View.VISIBLE : View.GONE);

                if (showIndicator) {
                    int iconColor = KeyboardColorManager.INSTANCE.getIconColor(KeyboardColorManager.ALPHA_FULL);
                    holder.moreSuggestionsIndicator.setColorFilter(iconColor);
                }
            }
        }
    }

    /** Scales text down to fit, then ellipsizes (via XML) below MIN_TEXT_SCALE. */
    private void applyTextAutoShrink(TextView textView, CharSequence text, int availableWidth) {
        if (TextUtils.isEmpty(text) || availableWidth <= 0) {
            textView.setTextScaleX(1.0f);
            return;
        }
        
        textView.setTextScaleX(1.0f);
        TextPaint paint = textView.getPaint();

        float textWidth = paint.measureText(text, 0, text.length());
        
        if (textWidth <= availableWidth) {
            textView.setTextScaleX(1.0f);
            return;
        }

        float scale = availableWidth / textWidth;
        
        if (scale >= MIN_TEXT_SCALE) {
            textView.setTextScaleX(scale);
        } else {
            textView.setTextScaleX(MIN_TEXT_SCALE);
            // Ellipsized by the layout (ellipsize="middle").
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.R)
    private void bindAutofillViewHolder(AutofillViewHolder holder, int position) {
        if (BuildConfig.DEBUG) {
        android.util.Log.d(DIAG, "[ADAPTER] bindAutofillViewHolder() pos=" + position
                + " | listSize=" + autofillSuggestions.size()
                + " | width=" + autofillWidth + " | height=" + autofillHeight);
        }
        if (position >= autofillSuggestions.size()) {
            if (BuildConfig.DEBUG) android.util.Log.d(DIAG, "[ADAPTER] bindAutofillViewHolder() SKIPPED: pos out of bounds");
            return;
        }
        
        Object suggestion = autofillSuggestions.get(position);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                android.view.inputmethod.InlineSuggestion inlineSuggestion = 
                    (android.view.inputmethod.InlineSuggestion) suggestion;
                
                holder.container.removeAllViews();
                
                Size size = new Size(autofillWidth, autofillHeight);
                if (BuildConfig.DEBUG) {
                android.util.Log.d(DIAG, "[ADAPTER] inflate() calling InlineSuggestion.inflate()"
                        + " | size=" + size + " | executor=" + (mainExecutor != null ? "SET" : "NULL"));
                }
                inlineSuggestion.inflate(context, size, mainExecutor, 
                    (Consumer<android.widget.inline.InlineContentView>) contentView -> {
                        if (contentView != null) {
                            holder.container.addView(contentView);
                            if (BuildConfig.DEBUG) android.util.Log.d(DIAG, "[ADAPTER] inflate() callback: InlineContentView added to container");
                        } else {
                            if (BuildConfig.DEBUG) android.util.Log.d(DIAG, "[ADAPTER] inflate() callback: contentView is NULL - inflation failed!");
                        }
                    });

            } catch (Exception e) {
                if (BuildConfig.DEBUG) android.util.Log.e("UnifiedSuggestionAdapter", "Failed to inflate autofill suggestion", e);
                if (BuildConfig.DEBUG) android.util.Log.e(DIAG, "[ADAPTER] EXCEPTION inflating autofill suggestion at pos=" + position + ": " + e.getMessage());
            }
        }
    }

    @Override
    public int getItemCount() {
        if (currentMode == SuggestionMode.LANGUAGES) {
            return languageChips.size();
        }
        if (currentMode == SuggestionMode.AUTOFILL) {
            return autofillSuggestions.size();
        } else if (currentMode == SuggestionMode.LATIN) {
            int count = suggestedWords != null ? suggestedWords.size() : 0;
            return Math.min(count, LATIN_MAX_SUGGESTIONS);
        } else {
            return suggestedWords != null ? suggestedWords.size() : 0;
        }
    }

    /** The RecyclerView's own width (already excludes the hamburger and margins), for Latin slots. */
    public void setAvailableWidth(int width) {
        int old = this.availableWidth;
        this.availableWidth = width;
        if (old != width) {
            if (BuildConfig.DEBUG) {
            android.util.Log.d("SUGGESTION_STRIP_DEBUG", "setAvailableWidth: " + old + " -> " + width
                    + " mode=" + currentMode + " (triggering rebind)");
            }
            notifyDataSetChanged();
        }
    }

    /** One third of the available width in Latin mode, else WRAP_CONTENT. */
    public int getLatinItemWidth() {
        if (availableWidth <= 0 || currentMode != SuggestionMode.LATIN) {
            return ViewGroup.LayoutParams.WRAP_CONTENT;
        }
        return availableWidth / LATIN_MAX_SUGGESTIONS;
    }

    static class WordViewHolder extends RecyclerView.ViewHolder {
        final TextView textView;
        final ImageView moreSuggestionsIndicator;
        /** Palette generation the current itemView foreground was built for. */
        int highlightGeneration = -1;
        /** The chip this holder shows in the language-chip mode, else null. */
        LanguageChip chip;

        WordViewHolder(@NonNull View itemView) {
            super(itemView);
            textView = itemView.findViewById(R.id.suggestion_word_text);
            moreSuggestionsIndicator = itemView.findViewById(R.id.more_suggestions_indicator);
        }
    }

    static class AutofillViewHolder extends RecyclerView.ViewHolder {
        final FrameLayout container;

        AutofillViewHolder(@NonNull View itemView) {
            super(itemView);
            container = itemView.findViewById(R.id.autofill_item_container);
        }
    }
}

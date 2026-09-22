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
 * Unified adapter for all suggestion types: Latin, CJK, and Autofill.
 *
 * <p>Updates go through {@code notifyDataSetChanged()}: the Latin strip is three slots and the
 * CJK grid is repopulated wholesale, so a diff would not pay for itself, and item-change
 * animations are not wanted on a strip that changes on every keystroke.
 */
public class UnifiedSuggestionAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final String DIAG = "INLINE_AUTOFILL_DEBUG";
    private static final int VIEW_TYPE_WORD = 0;
    private static final int VIEW_TYPE_AUTOFILL = 1;
    
    // Latin mode shows only 3 suggestions with equal width
    private static final int LATIN_MAX_SUGGESTIONS = 3;
    
    // Minimum text scale before ellipsizing (matches old SuggestionStripLayoutHelper)
    private static final float MIN_TEXT_SCALE = 0.7f;
    
    // Available width for calculating equal-width items in Latin mode
    private int availableWidth = 0;

    /**
     * Bumped by {@link #onPaletteChanged()}. Per-holder drawables built from the palette are
     * rebuilt on the next bind when their generation is stale, instead of a fresh
     * StateListDrawable + GradientDrawable + InsetDrawable per bound word per keystroke.
     */
    private int paletteGeneration = 0;

    /** R.dimen.config_suggestion_min_width_material, resolved once. */
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
    }

    public UnifiedSuggestionAdapter(Context context, OnSuggestionClickListener listener) {
        this.context = context;
        this.listener = listener;
        this.mainExecutor = context.getMainExecutor();
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
        this.suggestedWords = words;
        if (currentMode == SuggestionMode.AUTOFILL) {
            return;
        }
        notifyDataSetChanged();
    }

    /**
     * Invalidate palette-derived per-holder state (the pressed highlight). Called from
     * {@code UnifiedSuggestionView}'s KeyboardColorManager observer.
     */
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
        // Use parent's context which has the keyboard theme applied
        // This ensures theme attributes like ?attr/suggestionWordStyle are resolved correctly
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        
        if (viewType == VIEW_TYPE_AUTOFILL) {
            View view = inflater.inflate(R.layout.autofill_suggestion_item, parent, false);
            return new AutofillViewHolder(view);
        }
        View view = inflater.inflate(R.layout.suggestion_word_item, parent, false);
        final WordViewHolder holder = new WordViewHolder(view);
        // Installed here rather than per bind: onCreateViewHolder runs a handful of times, the
        // binds run on every keystroke. The position is read at click time instead of captured.
        view.setOnClickListener(v -> {
            SuggestedWords.SuggestedWordInfo wordInfo = boundWordInfo(holder);
            if (wordInfo != null && listener != null) {
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
        if (holder instanceof WordViewHolder) {
            bindWordViewHolder((WordViewHolder) holder, position);
        } else if (holder instanceof AutofillViewHolder) {
            bindAutofillViewHolder((AutofillViewHolder) holder, position);
        }
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
        if (suggestedWords == null || position >= suggestedWords.size()) {
            holder.textView.setText("");
            holder.textView.setTextScaleX(1.0f);
            return;
        }
        
        // Candidate hierarchy (modern boards only): the word that commits on space is
        // bold in the text color, the other candidates drop to the hint color. Index
        // semantics match MoreSuggestionsKeyboard: index 1 is the auto-correction,
        // index 0 the typed word. When neither flag is set (e.g. next-word predictions)
        // all candidates render uniformly. Legacy styles keep the uniform styling.
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

        // Modern boards get 48dp minimum touch-target width; legacy styles keep the
        // original unconstrained width (Latin slots are sized explicitly below anyway).
        holder.itemView.setMinimumWidth(modernBoards ? this.minWidthMaterialPx : 0);

        // Pressed highlight, themed rather than a fixed drawable. The layout used to
        // hardcode the dark-theme selector, so light themes got the wrong highlight.
        // pressedHighlight() is a FACTORY (three Drawable allocations plus a displayMetrics
        // read), so it runs once per holder rather than once per bound word per keystroke; a
        // StateListDrawable must not be shared across views, hence per-holder and not cached
        // on the adapter.
        if (holder.highlightGeneration != this.paletteGeneration) {
            holder.itemView.setForeground(KeyboardColorManager.pressedHighlight());
            holder.highlightGeneration = this.paletteGeneration;
        }

        // Set item width for Latin mode (equal 1/3 width for each suggestion)
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
            // CJK/Autofill mode: wrap content (scrollable)
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
            
            // Auto-shrink text for Latin mode to fit within the fixed width
            if (currentMode == SuggestionMode.LATIN && availableWidth > 0) {
                int itemWidth = getLatinItemWidth();
                // Account for padding
                int paddingHorizontal = holder.textView.getPaddingStart() + holder.textView.getPaddingEnd();
                int textAvailableWidth = itemWidth - paddingHorizontal;
                
                // Calculate text scale and apply ellipsize if needed
                applyTextAutoShrink(holder.textView, text, textAvailableWidth);
            } else {
                // Reset text scale for non-Latin modes
                holder.textView.setTextScaleX(1.0f);
            }
            
            // Show more suggestions indicator on center suggestion (position 1) 
            // when there are more than 3 suggestions available
            if (holder.moreSuggestionsIndicator != null) {
                boolean showIndicator = currentMode == SuggestionMode.LATIN 
                        && position == 1 
                        && suggestedWords != null 
                        && suggestedWords.size() > LATIN_MAX_SUGGESTIONS;
                holder.moreSuggestionsIndicator.setVisibility(showIndicator ? View.VISIBLE : View.GONE);
                
                // Apply icon color from KeyboardColorManager
                if (showIndicator) {
                    int iconColor = KeyboardColorManager.INSTANCE.getIconColor(KeyboardColorManager.ALPHA_FULL);
                    holder.moreSuggestionsIndicator.setColorFilter(iconColor);
                }
            }
        }
    }
    
    /**
     * Apply auto-shrink logic to text: scale text to fit, then ellipsize if still too long.
     * Based on SuggestionStripLayoutHelper approach.
     */
    private void applyTextAutoShrink(TextView textView, CharSequence text, int availableWidth) {
        if (TextUtils.isEmpty(text) || availableWidth <= 0) {
            textView.setTextScaleX(1.0f);
            return;
        }
        
        textView.setTextScaleX(1.0f);
        TextPaint paint = textView.getPaint();

        float textWidth = paint.measureText(text, 0, text.length());
        
        if (textWidth <= availableWidth) {
            // Text fits at full scale
            textView.setTextScaleX(1.0f);
            return;
        }
        
        // Calculate scale needed to fit
        float scale = availableWidth / textWidth;
        
        if (scale >= MIN_TEXT_SCALE) {
            // Scale is acceptable (70% or more), just apply it
            textView.setTextScaleX(scale);
        } else {
            // Scale would be too small, use minimum scale and ellipsize
            textView.setTextScaleX(MIN_TEXT_SCALE);
            // Ellipsize is handled by XML (ellipsize="middle")
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
        
        // Handle InlineSuggestion inflation
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
                
                holder.itemView.setOnClickListener(v -> {
                    if (BuildConfig.DEBUG) {
                    android.util.Log.d(DIAG, "[INTERACTION] autofill suggestion tapped at position " + position);
                    }
                    if (listener != null) {
                        listener.onAutofillClick(position);
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
        if (currentMode == SuggestionMode.AUTOFILL) {
            return autofillSuggestions.size();
        } else if (currentMode == SuggestionMode.LATIN) {
            // Latin mode: limit to 3 suggestions
            int count = suggestedWords != null ? suggestedWords.size() : 0;
            return Math.min(count, LATIN_MAX_SUGGESTIONS);
        } else {
            // CJK mode: show all suggestions (scrollable)
            return suggestedWords != null ? suggestedWords.size() : 0;
        }
    }
    
    /**
     * Set the available width for calculating equal-width items in Latin mode.
     * This should be the RecyclerView's actual width (already excludes hamburger and margins).
     */
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
    
    /**
     * Get the width for a single suggestion item in Latin mode.
     * Items should each take 1/3 of the available width.
     */
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

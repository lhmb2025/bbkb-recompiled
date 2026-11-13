package dev.bbkb.ime.keyboard.auxbar;

import android.content.Context;
import android.content.res.Resources;
import android.os.Build;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InlineSuggestion;
import android.widget.FrameLayout;
import android.widget.ImageButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import dev.bbkb.ime.R;
import dev.bbkb.ime.core.suggestion.SuggestedWords;
import dev.bbkb.ime.core.suggestion.EmojibaseStripSuggestion;
import dev.bbkb.ime.core.keyevent.InputSource;
import dev.bbkb.ime.keyboard.internal.MoreKeysPanel;
import dev.bbkb.ime.keyboard.Keyboard;
import dev.bbkb.ime.keyboard.KeyboardActionListenerInterface;
import dev.bbkb.ime.keyboard.KeyboardColorManager;
import dev.bbkb.ime.keyboard.auxbar.suggestions.FlickSuggestionAnimationView;
import dev.bbkb.ime.keyboard.auxbar.suggestions.MoreSuggestionsKeyboard;
import dev.bbkb.ime.keyboard.auxbar.suggestions.MoreSuggestionsView;
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiToneMemory;
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojibaseDataProvider;

import java.util.List;
import java.util.ArrayList;
import dev.bbkb.ime.BuildConfig;

/** The suggestion strip for every {@link SuggestionMode}, on a RecyclerView. */
public class UnifiedSuggestionView extends FrameLayout implements UnifiedSuggestionAdapter.OnSuggestionClickListener {

    private static final String TAG = "UnifiedSuggestionView";

    private RecyclerView recyclerView;
    private ImageButton hamburgerBtn;
    private ImageButton actionBtn;
    private FrameLayout overlayContainer;
    private View contentLayer;

    private UnifiedSuggestionAdapter adapter;
    private SuggestionMode currentMode = SuggestionMode.LATIN;
    private NonScrollableLayoutManager layoutManager;
    private GestureDetector gestureDetector;
    /** The words as the strip shows them: {@link #currentWords} without the emoji entry. */
    private SuggestedWords currentSuggestions;
    /** The words as given to {@link #setSuggestions}, emoji entry included. */
    private SuggestedWords currentWords;
    
    private Listener listener;
    private FlickSuggestionAnimationView suggestionAnimationView;

    /**
     * The emojibase hit for the typed word (kind 11, {@link EmojibaseStripSuggestion}) at the
     * right end, outside the word slots so they keep their widths. Long-press shows skin tones.
     */
    private android.widget.TextView emojiSlot;
    private SuggestedWords.SuggestedWordInfo emojiSuggestion;

    private final kotlin.jvm.functions.Function0<kotlin.Unit> colorObserver = () -> {
        updateColors();
        return kotlin.Unit.INSTANCE;
    };

    private View moreSuggestionsContainer;
    private MoreSuggestionsView moreSuggestionsView;
    private MoreSuggestionsKeyboard.Builder moreSuggestionsBuilder;
    private int moreSuggestionsTouchX;
    private int moreSuggestionsTouchY;
    private int moreSuggestionsOriginX;
    private int moreSuggestionsOriginY;
    private int moreSuggestionsModalTolerance;
    private int moreSuggestionsBottomGap;
    private static final int LATIN_MAX_SUGGESTIONS = 3;

    public interface Listener {
        void onSuggestionClicked(SuggestedWords.SuggestedWordInfo wordInfo, InputSource inputSource);
        void onSuggestionLongClicked(SuggestedWords.SuggestedWordInfo wordInfo);
        void onAutofillSuggestionClicked(int position);
        void onHamburgerClicked();
        void onExpandClicked();
        void onLanguageChipClicked(LanguageChip chip);
        /** The gear in the hamburger's place on the language-chip bar. */
        void onLanguageSettingsClicked();
        /** @return the keyboard to build the more-suggestions panel from, or null */
        Keyboard onMoreSuggestionsRequested();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        KeyboardColorManager.INSTANCE.addObserver(colorObserver);
    }

    @Override
    protected void onDetachedFromWindow() {
        KeyboardColorManager.INSTANCE.removeObserver(colorObserver);
        super.onDetachedFromWindow();
    }

    public UnifiedSuggestionView(@NonNull Context context) {
        this(context, null);
    }

    public UnifiedSuggestionView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public UnifiedSuggestionView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        LayoutInflater.from(context).inflate(R.layout.unified_suggestion_view, this, true);

        contentLayer = findViewById(R.id.suggestion_content_layer);
        recyclerView = findViewById(R.id.suggestion_recycler);
        hamburgerBtn = findViewById(R.id.hamburger_btn);
        actionBtn = findViewById(R.id.action_btn);
        overlayContainer = findViewById(R.id.overlay_container);
        emojiSlot = findViewById(R.id.emoji_suggestion_slot);
        emojiSlot.setOnClickListener(v -> {
            if (listener != null && emojiSuggestion != null) {
                listener.onSuggestionClicked(emojiSuggestion, InputSource.UNKNOWN);
            }
        });
        emojiSlot.setOnLongClickListener(v -> showEmojiVariants(emojiSuggestion));

        adapter = new UnifiedSuggestionAdapter(context, this);
        recyclerView.setAdapter(adapter);
        layoutManager = new NonScrollableLayoutManager(context);
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.setHasFixedSize(true);
        // Ranged adapter updates would otherwise cross-fade with a second holder per word.
        recyclerView.setItemAnimator(null);
        // CJK binds 20 to 30 candidates; the default pool of 5 would re-inflate the rest.
        recyclerView.getRecycledViewPool().setMaxRecycledViews(
                UnifiedSuggestionAdapter.VIEW_TYPE_WORD, CJK_RECYCLED_WORD_VIEWS);

        // The word slots' width follows the recycler's in onMeasure, not a layout listener.

        hamburgerBtn.setOnClickListener(v -> {
            if (listener == null) {
                return;
            }
            if (currentMode == SuggestionMode.LANGUAGES) {
                listener.onLanguageSettingsClicked();
            } else {
                listener.onHamburgerClicked();
            }
        });

        // CJK expand
        actionBtn.setOnClickListener(v -> {
            if (listener != null) {
                listener.onExpandClicked();
            }
        });

        updateColors();

        initMoreSuggestions(context);

        // Swipe up on a slot commits it.
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                if (currentMode != SuggestionMode.LATIN) {
                    return false;
                }
                float deltaY = e2.getY() - e1.getY();
                if (distanceY > 0 && deltaY < -getHeight() / 2.0f) {
                    return handleSwipeUp(e1.getX());
                }
                return false;
            }
        });
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * With the input menu off the left button is gone and suggestions take the full width; the
     * language-chip bar always keeps its gear, which opens the language settings.
     */
    public void setMenuButtonVisible(boolean visible) {
        boolean show = visible || currentMode == SuggestionMode.LANGUAGES;
        int visibility = show ? VISIBLE : GONE;
        if (hamburgerBtn.getVisibility() != visibility) {
            hamburgerBtn.setVisibility(visibility);
            updateAdapterDimensions();
        }
    }

    public void setMode(SuggestionMode mode) {
        if (this.currentMode != mode) {
            this.currentMode = mode;
            adapter.setMode(mode);

            switch (mode) {
                case LATIN:
                    actionBtn.setVisibility(GONE);
                    layoutManager.setScrollEnabled(false);
                    recyclerView.setNestedScrollingEnabled(false);
                    break;
                case CJK:
                    actionBtn.setVisibility(VISIBLE);
                    layoutManager.setScrollEnabled(true);
                    recyclerView.setNestedScrollingEnabled(true);
                    break;
                case AUTOFILL:
                    actionBtn.setVisibility(GONE);
                    layoutManager.setScrollEnabled(true);
                    recyclerView.setNestedScrollingEnabled(true);
                    break;
                case LANGUAGES:
                    actionBtn.setVisibility(GONE);
                    layoutManager.setScrollEnabled(true);
                    recyclerView.setNestedScrollingEnabled(true);
                    break;
            }
            hamburgerBtn.setImageResource(mode == SuggestionMode.LANGUAGES
                    ? R.drawable.ic_auxbar_language_settings : R.drawable.ic_auxbar_hamburger);

            updateAdapterDimensions();
            updateEmojiSlot();
        }
    }
    
    private void updateAdapterDimensions() {
        // Posted so the RecyclerView has been laid out.
        if (recyclerView != null) {
            recyclerView.post(() -> {
                int recyclerWidth = recyclerView.getWidth();
                if (recyclerWidth > 0) {
                    adapter.setAvailableWidth(recyclerWidth);
                }
            });
        }
    }
    
    /**
     * Resizes the word slots in the same pass that sizes the emoji slot. Not from an
     * OnLayoutChangeListener: a relayout requested inside layout is dropped, leaving the slots
     * at the old width. No-op when the width is unchanged.
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int width = recyclerView.getMeasuredWidth();
        if (width > 0) {
            adapter.setAvailableWidth(width);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateAdapterDimensions();
    }

    public SuggestionMode getMode() {
        return currentMode;
    }

    /** The words last given to {@link #setSuggestions}, emoji entry included, or null. */
    public SuggestedWords getCurrentSuggestions() {
        return currentWords;
    }

    public void flashCommittedSlot(int slot) {
        RecyclerView.ViewHolder holder = recyclerView.findViewHolderForAdapterPosition(slot);
        if (!(holder instanceof UnifiedSuggestionAdapter.WordViewHolder)) return;
        android.widget.TextView tv = ((UnifiedSuggestionAdapter.WordViewHolder) holder).textView;
        if (suggestionAnimationView == null) {
            suggestionAnimationView = new FlickSuggestionAnimationView(getContext(), null);
        }
        // Host in the root view: the animation positions itself in window coordinates, needs
        // room above the strip, and a child in the strip's LinearLayout would steal its width.
        ViewGroup host = (ViewGroup) getRootView();
        suggestionAnimationView.animateSelection(tv, tv.getCurrentTextColor(), host);
    }

    public void setSuggestions(SuggestedWords words) {
        hideOverlays();
        // Most deliveries repeat what is on screen: skip the rebind, but keep the new objects so
        // clicks and getCurrentSuggestions() see the latest edit count and word metadata.
        boolean unchanged = currentWords != null && rendersSame(currentWords, words);
        currentWords = words;
        // The emoji (kind 11) goes to its own slot, not a word slot.
        SuggestedWords.SuggestedWordInfo emoji = null;
        ArrayList<SuggestedWords.SuggestedWordInfo> wordsOnly = null;
        if (words != null) {
            for (int i = 0; i < words.size(); i++) {
                SuggestedWords.SuggestedWordInfo info = words.getWordInfo(i);
                if (info != null && info.isKind(EmojibaseStripSuggestion.KIND_EMOJI)) {
                    if (emoji == null) {
                        emoji = info;
                    }
                    if (wordsOnly == null) {
                        wordsOnly = new ArrayList<>(words.size());
                        for (int j = 0; j < i; j++) {
                            wordsOnly.add(words.getWordInfo(j));
                        }
                    }
                } else if (wordsOnly != null) {
                    wordsOnly.add(info);
                }
            }
        }
        if (wordsOnly == null) {
            currentSuggestions = words;
        } else {
            SuggestedWords filtered = new SuggestedWords(wordsOnly, words.mAutoCorrectWord,
                    words.mTypedWordValid, words.mWillAutoCorrect, words.mInputStyle);
            filtered.mRequestEditCount = words.mRequestEditCount;
            currentSuggestions = filtered;
        }
        emojiSuggestion = emoji;
        if (unchanged) {
            adapter.replaceSuggestionsWithoutRebind(currentSuggestions);
            return;
        }
        adapter.setSuggestions(currentSuggestions);
        updateEmojiSlot();
    }

    /** Pool size for word holders: covers the CJK row's candidates without re-inflating. */
    private static final int CJK_RECYCLED_WORD_VIEWS = 32;

    /**
     * Same words, order, kinds and auto-correct / typed-word flags: the only fields the strip
     * and the flick overlay render.
     */
    public static boolean rendersSame(SuggestedWords a, SuggestedWords b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (a.size() != b.size() || a.mWillAutoCorrect != b.mWillAutoCorrect
                || a.mTypedWordValid != b.mTypedWordValid
                || (a.mAutoCorrectWord == null) != (b.mAutoCorrectWord == null)) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            SuggestedWords.SuggestedWordInfo x = a.getWordInfo(i);
            SuggestedWords.SuggestedWordInfo y = b.getWordInfo(i);
            if (x == y) {
                continue;
            }
            if (x == null || y == null || x.getKind() != y.getKind()
                    || !android.text.TextUtils.equals(x.word, y.word)) {
                return false;
            }
        }
        return true;
    }

    /** Shows the emoji slot while there is an emoji and the strip is in its Latin mode. */
    private void updateEmojiSlot() {
        if (emojiSlot == null) {
            return;
        }
        boolean show = emojiSuggestion != null && currentMode == SuggestionMode.LATIN;
        emojiSlot.setText(show ? emojiSuggestion.word : "");
        int visibility = show ? VISIBLE : GONE;
        if (emojiSlot.getVisibility() != visibility) {
            emojiSlot.setVisibility(visibility);
            updateAdapterDimensions();
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.R)
    public void setAutofillSuggestions(List<InlineSuggestion> suggestions, int width, int height) {
        hideOverlays();
        adapter.setAutofillSuggestions(suggestions, width, height);
    }

    public void clear() {
        currentSuggestions = null;
        currentWords = null;
        adapter.clear();
        emojiSuggestion = null;
        updateEmojiSlot();
        hideOverlays();
    }

    /** The language-chip bar's chips; shown when the mode is {@link SuggestionMode#LANGUAGES}. */
    public void setLanguageChips(List<LanguageChip> chips) {
        hideOverlays();
        adapter.setLanguageChips(chips);
        if (recyclerView != null) {
            recyclerView.scrollToPosition(0);
        }
    }

    @Override
    public void onLanguageChipClick(LanguageChip chip) {
        if (listener != null) {
            listener.onLanguageChipClicked(chip);
        }
    }

    public void hideOverlays() {
        overlayContainer.setVisibility(GONE);
        contentLayer.setVisibility(VISIBLE);
    }

    public void updateColors() {
        KeyboardColorManager colorManager = KeyboardColorManager.INSTANCE;
        int iconColor = colorManager.getIconColor(KeyboardColorManager.ALPHA_FULL);
        
        hamburgerBtn.setColorFilter(iconColor);
        actionBtn.setColorFilter(iconColor);

        // Modern boards: press feedback and 48dp width; legacy: none and 39dp. Separate drawable
        // instances, since a StateListDrawable must not be shared across views.
        boolean modernBoards = KeyboardColorManager.styleSpec().getModernBoards();
        hamburgerBtn.setForeground(modernBoards ? KeyboardColorManager.pressedHighlight() : null);
        actionBtn.setForeground(modernBoards ? KeyboardColorManager.pressedHighlight() : null);
        int buttonWidth = getResources().getDimensionPixelSize(modernBoards
                ? R.dimen.config_suggestion_min_width_material
                : R.dimen.config_suggestion_half_hamburger_icon_width);
        applyButtonWidth(hamburgerBtn, buttonWidth);
        applyButtonWidth(actionBtn, buttonWidth);
        if (emojiSlot != null) {
            emojiSlot.setForeground(modernBoards ? KeyboardColorManager.pressedHighlight() : null);
            emojiSlot.setTextColor(colorManager.getTextColor());
            applyButtonWidth(emojiSlot, buttonWidth);
        }

        int bgColor = colorManager.getBackgroundColor();
        setBackgroundColor(bgColor);

        if (adapter != null) {
            adapter.onPaletteChanged();
        }
    }

    private static void applyButtonWidth(View button, int width) {
        ViewGroup.LayoutParams params = button.getLayoutParams();
        if (params != null && params.width != width) {
            params.width = width;
            button.setLayoutParams(params);
        }
    }

    private void initMoreSuggestions(Context context) {
        LayoutInflater inflater = LayoutInflater.from(context);
        moreSuggestionsContainer = inflater.inflate(R.layout.more_suggestions, null);
        moreSuggestionsView = moreSuggestionsContainer.findViewById(R.id.more_suggestions_view);
        moreSuggestionsBuilder = new MoreSuggestionsKeyboard.Builder(context, moreSuggestionsView);

        moreSuggestionsModalTolerance = context.getResources().getDimensionPixelOffset(
                R.dimen.config_more_suggestions_modal_tolerance);

        // 2dp gap between the panel and the strip.
        moreSuggestionsBottomGap = (int) (2 * context.getResources().getDisplayMetrics().density);
    }

    public boolean isMoreSuggestionsShowing() {
        return moreSuggestionsView != null && moreSuggestionsView.isShowingInParent();
    }

    public void dismissMoreSuggestions() {
        if (moreSuggestionsView != null) {
            moreSuggestionsView.dismissMoreKeysPanel();
        }
    }

    /** Shows the words past the first three, on a long-press of the center slot. */
    public boolean showMoreSuggestions(Keyboard keyboard, KeyboardActionListenerInterface actionListener) {
        if (BuildConfig.DEBUG) {
        android.util.Log.d(TAG, "showMoreSuggestions called - suggestions: " + 
            (currentSuggestions != null ? currentSuggestions.size() : "null") +
            ", keyboard: " + (keyboard != null) + ", mode: " + currentMode);
        }
        
        if (currentSuggestions == null || keyboard == null) {
            if (BuildConfig.DEBUG) android.util.Log.w(TAG, "Cannot show MoreSuggestions - missing data");
            return false;
        }

        if (currentSuggestions.size() <= LATIN_MAX_SUGGESTIONS) {
            if (BuildConfig.DEBUG) android.util.Log.d(TAG, "Not enough suggestions to show MoreSuggestions panel");
            return false;
        }
        return showSuggestionsPanel(currentSuggestions, LATIN_MAX_SUGGESTIONS, keyboard);
    }

    /**
     * Shows the base emoji and its skin tones in the more-suggestions panel; the pick is remembered
     * ({@link EmojiToneMemory}). False without tones or before emojibase is loaded (never parses it).
     */
    private boolean showEmojiVariants(SuggestedWords.SuggestedWordInfo emoji) {
        if (emoji == null || listener == null) {
            return false;
        }
        EmojibaseDataProvider provider = EmojibaseDataProvider.peekShared();
        if (provider == null) {
            return false;
        }
        String base = EmojiToneMemory.hasSkinTone(emoji.word) ? EmojiToneMemory.normalize(emoji.word) : emoji.word;
        List<String> tones = provider.getSkinToneVariants(base);
        if (tones.isEmpty()) {
            return false;
        }
        Keyboard keyboard = listener.onMoreSuggestionsRequested();
        if (keyboard == null) {
            return false;
        }
        ArrayList<SuggestedWords.SuggestedWordInfo> variants = new ArrayList<>(tones.size() + 1);
        variants.add(EmojibaseStripSuggestion.emojiEntry(base));
        for (String tone : tones) {
            variants.add(EmojibaseStripSuggestion.emojiEntry(tone));
        }
        int inputStyle = currentSuggestions != null ? currentSuggestions.mInputStyle : 0;
        return showSuggestionsPanel(new SuggestedWords(variants, false, false, inputStyle), 0, keyboard);
    }

    /** Lays out {@code words} from {@code fromIndex} in the panel above the strip and shows it. */
    private boolean showSuggestionsPanel(SuggestedWords words, int fromIndex, Keyboard keyboard) {
        try {
            int width = getWidth();
            int paddingLeft = recyclerView.getPaddingLeft();
            int paddingRight = recyclerView.getPaddingRight();
            int availableWidth = width - paddingLeft - paddingRight;

            int maxHeight = (int) (availableWidth * 0.5f);

            float suggestionTextSize = getResources().getDimension(R.dimen.config_suggestion_text_size);

            moreSuggestionsBuilder.layout(
                    words, 
                    fromIndex,
                    availableWidth, 
                    maxHeight, 
                    (int) suggestionTextSize, 
                    keyboard);
            
            moreSuggestionsView.setKeyboard(moreSuggestionsBuilder.build());
            moreSuggestionsContainer.measure(
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));

            final MoreSuggestionsView.MoreSuggestionsListener moreSuggestionsListener = 
                    new MoreSuggestionsView.MoreSuggestionsListener() {
                @Override
                public void onSuggestionSelected(SuggestedWords.SuggestedWordInfo wordInfo) {
                    if (BuildConfig.DEBUG) android.util.Log.d(TAG, "MoreSuggestions word selected: " + wordInfo.word);
                    if (listener != null) {
                        listener.onSuggestionClicked(wordInfo, InputSource.UNKNOWN);
                    }
                    dismissMoreSuggestions();
                }

                @Override
                public void onCancelInput() {
                    if (BuildConfig.DEBUG) android.util.Log.d(TAG, "MoreSuggestions cancelled");
                    dismissMoreSuggestions();
                }
            };

            MoreKeysPanel.Controller drawingProxyCallback = new MoreKeysPanel.Controller() {
                @Override
                public void onCancelMoreKeysPanel() {
                    moreSuggestionsView.removeFromParent();
                }

                @Override
                public void onShowMoreKeysPanel(MoreKeysPanel drawingProxy) {
                    ViewGroup rootView = (ViewGroup) getRootView().findViewById(android.R.id.content);
                    if (rootView != null) {
                        moreSuggestionsView.showInParent(rootView);
                    }
                }

                @Override
                public void onDismissMoreKeysPanel() {
                    dismissMoreSuggestions();
                }
            };

            // showMoreKeysPanel puts the top at anchorY + panelY - containerHeight + padding, so
            // -bottomGap leaves the panel's bottom that far above the strip.
            int centerX = width / 2;
            int panelY = -moreSuggestionsBottomGap;
            
            if (BuildConfig.DEBUG) {
            android.util.Log.d(TAG, "Showing MoreSuggestions - centerX: " + centerX + ", panelY: " + panelY + 
                ", containerHeight: " + moreSuggestionsContainer.getMeasuredHeight() + 
                ", bottomGap: " + moreSuggestionsBottomGap);
            }
            
            moreSuggestionsView.showMoreKeysPanel(this, drawingProxyCallback, centerX, panelY, moreSuggestionsListener);

            moreSuggestionsOriginX = moreSuggestionsTouchX;
            moreSuggestionsOriginY = moreSuggestionsTouchY;
            
            return true;
        } catch (Resources.NotFoundException e) {
            if (BuildConfig.DEBUG) android.util.Log.e(TAG, "Failed to show MoreSuggestions", e);
            return false;
        }
    }

    @Override
    public void onSuggestionClick(int position, SuggestedWords.SuggestedWordInfo wordInfo) {
        if (listener != null && wordInfo != null) {
            listener.onSuggestionClicked(wordInfo, InputSource.UNKNOWN);
        }
    }

    @Override
    public void onSuggestionLongClick(int position, SuggestedWords.SuggestedWordInfo wordInfo) {
        // The center slot opens the more-suggestions panel when there are more words.
        if (position == 1 && currentMode == SuggestionMode.LATIN 
                && currentSuggestions != null 
                && currentSuggestions.size() > LATIN_MAX_SUGGESTIONS
                && listener != null) {
            
            Keyboard keyboard = listener.onMoreSuggestionsRequested();
            if (keyboard != null && showMoreSuggestions(keyboard, listener instanceof KeyboardActionListenerInterface ? (KeyboardActionListenerInterface) listener : null)) {
                return;
            }
        }

        if (listener != null && wordInfo != null) {
            listener.onSuggestionLongClicked(wordInfo);
        }
    }

    @Override
    public void onAutofillClick(int position) {
        if (listener != null) {
            listener.onAutofillSuggestionClicked(position);
        }
    }
    
    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            moreSuggestionsTouchX = (int) ev.getX();
            moreSuggestionsTouchY = (int) ev.getY();
        }

        if (isMoreSuggestionsShowing()) {
            // Masked: the raw action packs the pointer index into bits 8-15.
            int action = ev.getActionMasked();
            int actionIndex = ev.getActionIndex();
            int x = (int) ev.getX(actionIndex);
            int y = (int) ev.getY(actionIndex);

            int deltaX = Math.abs(x - moreSuggestionsOriginX);
            int deltaY = moreSuggestionsOriginY - y;
            
            if (deltaX >= moreSuggestionsModalTolerance || deltaY >= moreSuggestionsModalTolerance) {
                // Dragged far enough: modal, the panel handles it directly.
                return true;
            }

            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
                dismissMoreSuggestions();
            }
            return false;
        }

        if (currentMode == SuggestionMode.LATIN && gestureDetector != null) {
            gestureDetector.onTouchEvent(ev);
        }
        return super.onInterceptTouchEvent(ev);
    }
    
    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (isMoreSuggestionsShowing()) {
            int actionIndex = ev.getActionIndex();
            int translatedX = moreSuggestionsView.translateX((int) ev.getX(actionIndex));
            int translatedY = moreSuggestionsView.translateY((int) ev.getY(actionIndex));
            ev.setLocation(translatedX, translatedY);
            
            
            moreSuggestionsView.onTouchEvent(ev);
            return true;
        }

        if (currentMode == SuggestionMode.LATIN && gestureDetector != null) {
            if (gestureDetector.onTouchEvent(ev)) {
                return true;
            }
        }
        return super.onTouchEvent(ev);
    }

    /** Commits the slot under the swipe's start {@code x}; true if one was committed. */
    private boolean handleSwipeUp(float x) {
        if (currentSuggestions == null || currentSuggestions.size() == 0) {
            return false;
        }

        int recyclerWidth = recyclerView.getWidth();
        if (recyclerWidth <= 0) {
            return false;
        }

        float recyclerX = x - hamburgerBtn.getWidth();
        if (recyclerX < 0) {
            return false; // on the hamburger
        }

        int slotWidth = recyclerWidth / 3;
        int slotIndex = (int) (recyclerX / slotWidth);
        slotIndex = Math.max(0, Math.min(slotIndex, 2));

        if (slotIndex >= currentSuggestions.size()) {
            return false;
        }

        SuggestedWords.SuggestedWordInfo wordInfo = currentSuggestions.getWordInfo(slotIndex);
        if (wordInfo != null && listener != null) {
            listener.onSuggestionClicked(wordInfo, InputSource.SOFTWARE);
            return true;
        }
        
        return false;
    }

    /** Horizontal layout whose scrolling is off in Latin mode. */
    private static class NonScrollableLayoutManager extends LinearLayoutManager {
        private boolean scrollEnabled = false;
        
        public NonScrollableLayoutManager(Context context) {
            super(context, LinearLayoutManager.HORIZONTAL, false);
        }
        
        public void setScrollEnabled(boolean enabled) {
            this.scrollEnabled = enabled;
        }
        
        @Override
        public boolean canScrollHorizontally() {
            return scrollEnabled && super.canScrollHorizontally();
        }
        
        @Override
        public boolean canScrollVertically() {
            return false;
        }
    }
}

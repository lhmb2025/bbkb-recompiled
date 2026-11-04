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

/**
 * Unified suggestion view that handles Latin, CJK, and Autofill suggestions.
 * Uses RecyclerView for efficient recycling and consistent performance.
 */
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
    /** The words as given to {@link #setSuggestions}, emoji entry included, for a round trip through {@link #getCurrentSuggestions}. */
    private SuggestedWords currentWords;
    
    private Listener listener;
    private FlickSuggestionAnimationView suggestionAnimationView;

    /**
     * The emoji slot at the strip's right end: the emojibase hit for the word being typed
     * (kind 11, {@link EmojibaseStripSuggestion}), or hidden. It rides outside the word slots so
     * the three words keep their positions and widths. A tap inserts it like any suggestion; a
     * long-press shows its skin tones ({@link #showEmojiVariants}).
     */
    private android.widget.TextView emojiSlot;
    private SuggestedWords.SuggestedWordInfo emojiSuggestion;

    private final kotlin.jvm.functions.Function0<kotlin.Unit> colorObserver = () -> {
        updateColors();
        return kotlin.Unit.INSTANCE;
    };
    
    // More suggestions components
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
        /** A tap on a chip of the language-chip bar. */
        void onLanguageChipClicked(LanguageChip chip);
        /** The gear that stands in the hamburger's place on the language-chip bar. */
        void onLanguageSettingsClicked();
        /**
         * Called when user long-presses the center suggestion to show more suggestions.
         * @return The current Keyboard to use for building the MoreSuggestions panel, or null if unavailable.
         */
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

        // Setup RecyclerView with custom non-scrollable layout manager for Latin mode
        adapter = new UnifiedSuggestionAdapter(context, this);
        recyclerView.setAdapter(adapter);
        layoutManager = new NonScrollableLayoutManager(context);
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.setHasFixedSize(true);
        // The adapter now notifies item ranges rather than the whole data set. Nothing here ever
        // animated (notifyDataSetChanged runs no item animations), and ranged changes would start
        // the default cross-fade with a second holder per word, so keep the strip unanimated.
        recyclerView.setItemAnimator(null);
        // CJK mode binds every candidate (20-30 in a row); with the default pool of 5 per view
        // type, holders beyond that were discarded and re-inflated on the next update.
        recyclerView.getRecycledViewPool().setMaxRecycledViews(
                UnifiedSuggestionAdapter.VIEW_TYPE_WORD, CJK_RECYCLED_WORD_VIEWS);
        
        // Update adapter dimensions when RecyclerView layout changes
        recyclerView.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int newWidth = right - left;
            if (newWidth > 0) {
                adapter.setAvailableWidth(newWidth);
            }
        });

        // Setup hamburger button
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

        // Setup action button (CJK expand)
        actionBtn.setOnClickListener(v -> {
            if (listener != null) {
                listener.onExpandClicked();
            }
        });

        // Apply theme colors
        updateColors();
        
        // Initialize MoreSuggestions components
        initMoreSuggestions(context);
        
        // Setup gesture detector for swipe-up to commit suggestions
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                if (currentMode != SuggestionMode.LATIN) {
                    return false;
                }
                // Detect upward swipe (negative Y movement)
                float deltaY = e2.getY() - e1.getY();
                if (distanceY > 0 && deltaY < -getHeight() / 2.0f) {
                    // Swipe up detected - find which suggestion was swiped
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
     * Whether the menu button at the left edge is drawn. With the unified input menu off it is
     * gone and the suggestions take the whole width (2026-10-19); the language-chip bar keeps
     * its gear whatever the menu setting, since that button opens the language settings.
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
            
            // Update UI based on mode
            switch (mode) {
                case LATIN:
                    actionBtn.setVisibility(GONE);
                    // Disable scrolling for Latin mode (fixed 3 items)
                    layoutManager.setScrollEnabled(false);
                    recyclerView.setNestedScrollingEnabled(false);
                    break;
                case CJK:
                    actionBtn.setVisibility(VISIBLE);
                    // Enable scrolling for CJK mode
                    layoutManager.setScrollEnabled(true);
                    recyclerView.setNestedScrollingEnabled(true);
                    break;
                case AUTOFILL:
                    actionBtn.setVisibility(GONE);
                    // Enable scrolling for Autofill mode
                    layoutManager.setScrollEnabled(true);
                    recyclerView.setNestedScrollingEnabled(true);
                    break;
                case LANGUAGES:
                    actionBtn.setVisibility(GONE);
                    layoutManager.setScrollEnabled(true);
                    recyclerView.setNestedScrollingEnabled(true);
                    break;
            }
            // The language-chip bar's left button is the language settings gear; every other
            // mode's is the hamburger.
            hamburgerBtn.setImageResource(mode == SuggestionMode.LANGUAGES
                    ? R.drawable.ic_auxbar_language_settings : R.drawable.ic_auxbar_hamburger);
            
            // Update adapter with current dimensions
            updateAdapterDimensions();
            updateEmojiSlot();
        }
    }
    
    private void updateAdapterDimensions() {
        // Post to ensure RecyclerView has been laid out
        if (recyclerView != null) {
            recyclerView.post(() -> {
                int recyclerWidth = recyclerView.getWidth();
                if (recyclerWidth > 0) {
                    adapter.setAvailableWidth(recyclerWidth);
                }
            });
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
        // Host the flick animation in the window's root view (DecorView), NOT in the
        // suggestion strip or its content LinearLayout. Two reasons:
        //  1) FlickSuggestionAnimationView positions itself in absolute window coordinates
        //     (setX/translationY from getLocationInWindow). That only renders correctly when
        //     the host's top-left is at window (0,0). The strip sits near the bottom of the
        //     window, so hosting there double-offsets the view off-screen (the animation
        //     never displayed). The root view is at window (0,0) and spans the full window
        //     height, so the word also has room to float up out of the strip without clipping.
        //  2) The strip's content layer is a horizontal LinearLayout; adding a fixed-width
        //     child there steals width from the weight=1 RecyclerView (the ~2/3 shrink bug).
        //     The root view is not part of the strip's layout, so widths are unaffected.
        ViewGroup host = (ViewGroup) getRootView();
        suggestionAnimationView.animateSelection(tv, tv.getCurrentTextColor(), host);
    }

    public void setSuggestions(SuggestedWords words) {
        hideOverlays();
        // Most deliveries repeat what is already on screen (shift, cursor and modifier
        // re-requests, a re-delivered result for unchanged text). Skip the rebind then, but still
        // take the new objects: clicks, the emoji slot and getCurrentSuggestions() must see the
        // latest result (its request edit count, its word metadata).
        boolean unchanged = currentWords != null && rendersSame(currentWords, words);
        currentWords = words;
        // The strip's emoji (kind 11) goes to its own slot at the right end, not into a word slot.
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
     * Whether two results draw the same: the same words in the same order, each of the same
     * kind (the emoji entry and its slot), and the same auto-correct / typed-word flags (the
     * candidate emphasis on the strip, the spacebar word on the on-screen keyboard). These are
     * the only fields the strip and the flick overlay render.
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

        // Modern boards: rounded state-layer press feedback (matching the suggestion
        // words) and 48dp touch-target width. Legacy styles keep the original
        // no-feedback, 39dp-wide buttons. Separate drawable instances — a
        // StateListDrawable must not be shared across views.
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

        // Update background
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
    
    /**
     * Initialize the MoreSuggestions components for showing additional suggestions popup.
     */
    private void initMoreSuggestions(Context context) {
        // Inflate the more suggestions container
        LayoutInflater inflater = LayoutInflater.from(context);
        moreSuggestionsContainer = inflater.inflate(R.layout.more_suggestions, null);
        moreSuggestionsView = moreSuggestionsContainer.findViewById(R.id.more_suggestions_view);
        moreSuggestionsBuilder = new MoreSuggestionsKeyboard.Builder(context, moreSuggestionsView);
        
        // Get the modal tolerance for determining when to switch to modal mode
        moreSuggestionsModalTolerance = context.getResources().getDimensionPixelOffset(
                R.dimen.config_more_suggestions_modal_tolerance);
        
        // Use 2dp gap between panel bottom and suggestion strip top
        moreSuggestionsBottomGap = (int) (2 * context.getResources().getDisplayMetrics().density);
    }
    
    /**
     * Check if MoreSuggestions panel is currently showing.
     */
    public boolean isMoreSuggestionsShowing() {
        return moreSuggestionsView != null && moreSuggestionsView.isShowingInParent();
    }
    
    /**
     * Dismiss the MoreSuggestions panel if showing.
     */
    public void dismissMoreSuggestions() {
        if (moreSuggestionsView != null) {
            moreSuggestionsView.dismissMoreKeysPanel();
        }
    }
    
    /**
     * Show MoreSuggestions panel with additional word suggestions.
     * Called when user long-presses the center suggestion.
     * 
     * @param keyboard The current keyboard for layout parameters
     * @param actionListener The keyboard action listener for handling suggestion selection
     * @return true if MoreSuggestions was shown successfully
     */
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
        
        // Check if there are more suggestions than what's shown
        if (currentSuggestions.size() <= LATIN_MAX_SUGGESTIONS) {
            if (BuildConfig.DEBUG) android.util.Log.d(TAG, "Not enough suggestions to show MoreSuggestions panel");
            return false;
        }
        return showSuggestionsPanel(currentSuggestions, LATIN_MAX_SUGGESTIONS, keyboard);
    }

    /**
     * The skin-tone popup for the emoji slot (owner decision 2026-11-03): a long-press on an emoji
     * that has tones shows the base emoji and every tone in the more-suggestions panel. Picking one
     * replaces the typed word as a tap does, and the IME remembers the tone ({@link EmojiToneMemory}).
     * An emoji without tones returns false and keeps today's long-press behaviour. Never pays for
     * the emojibase parse: a provider not loaded yet also returns false.
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
            
            // Calculate max height as a fraction of available width
            int maxHeight = (int) (availableWidth * 0.5f);
            
            // Get suggestion text size from resources
            float suggestionTextSize = getResources().getDimension(R.dimen.config_suggestion_text_size);
            
            // Build the MoreSuggestions keyboard
            moreSuggestionsBuilder.layout(
                    words, 
                    fromIndex,  // Start from after visible suggestions
                    availableWidth, 
                    maxHeight, 
                    (int) suggestionTextSize, 
                    keyboard);
            
            moreSuggestionsView.setKeyboard(moreSuggestionsBuilder.build());
            moreSuggestionsContainer.measure(
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            
            // Create listener for handling suggestion selection from MoreSuggestions
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
            
            // Create drawing proxy callback for panel management
            MoreKeysPanel.Controller drawingProxyCallback = new MoreKeysPanel.Controller() {
                @Override
                public void onCancelMoreKeysPanel() {
                    // Panel dismissed - remove from view hierarchy
                    moreSuggestionsView.removeFromParent();
                }

                @Override
                public void onShowMoreKeysPanel(MoreKeysPanel drawingProxy) {
                    // Panel shown - add to view hierarchy
                    ViewGroup rootView = (ViewGroup) getRootView().findViewById(android.R.id.content);
                    if (rootView != null) {
                        moreSuggestionsView.showInParent(rootView);
                    }
                }

                @Override
                public void onDismissMoreKeysPanel() {
                    // Cancel - dismiss panel
                    dismissMoreSuggestions();
                }
            };
            
            // Position the MoreSuggestions panel so its bottom edge is above the top of UnifiedSuggestionView
            // The showMoreKeysPanel method calculates final Y as: anchor_Y + (panelY - containerHeight) + padding
            // So passing -bottomGap results in bottom edge being bottomGap pixels above the anchor view
            int centerX = width / 2;
            int panelY = -moreSuggestionsBottomGap;
            
            if (BuildConfig.DEBUG) {
            android.util.Log.d(TAG, "Showing MoreSuggestions - centerX: " + centerX + ", panelY: " + panelY + 
                ", containerHeight: " + moreSuggestionsContainer.getMeasuredHeight() + 
                ", bottomGap: " + moreSuggestionsBottomGap);
            }
            
            moreSuggestionsView.showMoreKeysPanel(this, drawingProxyCallback, centerX, panelY, moreSuggestionsListener);
            
            // Store origin for touch tracking
            moreSuggestionsOriginX = moreSuggestionsTouchX;
            moreSuggestionsOriginY = moreSuggestionsTouchY;
            
            return true;
        } catch (Resources.NotFoundException e) {
            if (BuildConfig.DEBUG) android.util.Log.e(TAG, "Failed to show MoreSuggestions", e);
            return false;
        }
    }

    // UnifiedSuggestionAdapter.OnSuggestionClickListener implementation

    @Override
    public void onSuggestionClick(int position, SuggestedWords.SuggestedWordInfo wordInfo) {
        if (listener != null && wordInfo != null) {
            listener.onSuggestionClicked(wordInfo, InputSource.UNKNOWN);
        }
    }

    @Override
    public void onSuggestionLongClick(int position, SuggestedWords.SuggestedWordInfo wordInfo) {
        // If long-pressing center suggestion (position 1) and we have more suggestions, 
        // show the MoreSuggestions panel
        if (position == 1 && currentMode == SuggestionMode.LATIN 
                && currentSuggestions != null 
                && currentSuggestions.size() > LATIN_MAX_SUGGESTIONS
                && listener != null) {
            
            Keyboard keyboard = listener.onMoreSuggestionsRequested();
            if (keyboard != null && showMoreSuggestions(keyboard, listener instanceof KeyboardActionListenerInterface ? (KeyboardActionListenerInterface) listener : null)) {
                // MoreSuggestions panel shown successfully
                return;
            }
        }
        
        // Fall back to regular long-click behavior
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
        // Track touch position for MoreSuggestions
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            moreSuggestionsTouchX = (int) ev.getX();
            moreSuggestionsTouchY = (int) ev.getY();
        }
        
        // If MoreSuggestions is showing, intercept touches to handle them
        if (isMoreSuggestionsShowing()) {
            // Masked: the raw action packs the pointer index into bits 8-15, so lifting a
            // second finger produced 0x0106 and never matched ACTION_POINTER_UP.
            int action = ev.getActionMasked();
            int actionIndex = ev.getActionIndex();
            int x = (int) ev.getX(actionIndex);
            int y = (int) ev.getY(actionIndex);
            
            // Check if we should switch to modal mode (drag far enough from origin)
            int deltaX = Math.abs(x - moreSuggestionsOriginX);
            int deltaY = moreSuggestionsOriginY - y;
            
            if (deltaX >= moreSuggestionsModalTolerance || deltaY >= moreSuggestionsModalTolerance) {
                // Dragged far enough: swallow the event so MoreSuggestions handles it directly.
                return true;
            }
            
            // Dismiss on touch up if not dragged
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
                dismissMoreSuggestions();
            }
            return false;
        }
        
        // Let gesture detector process events for swipe-up detection
        if (currentMode == SuggestionMode.LATIN && gestureDetector != null) {
            gestureDetector.onTouchEvent(ev);
        }
        return super.onInterceptTouchEvent(ev);
    }
    
    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        // If MoreSuggestions is showing, forward touch events to it
        if (isMoreSuggestionsShowing()) {
            int actionIndex = ev.getActionIndex();
            int translatedX = moreSuggestionsView.translateX((int) ev.getX(actionIndex));
            int translatedY = moreSuggestionsView.translateY((int) ev.getY(actionIndex));
            ev.setLocation(translatedX, translatedY);
            
            
            moreSuggestionsView.onTouchEvent(ev);
            return true;
        }
        
        // Let gesture detector process events for swipe-up detection
        if (currentMode == SuggestionMode.LATIN && gestureDetector != null) {
            if (gestureDetector.onTouchEvent(ev)) {
                return true;
            }
        }
        return super.onTouchEvent(ev);
    }
    
    /**
     * Handle swipe-up gesture to commit the suggestion at the given X position.
     * @param x The X coordinate of the swipe start
     * @return true if a suggestion was committed
     */
    private boolean handleSwipeUp(float x) {
        if (currentSuggestions == null || currentSuggestions.size() == 0) {
            return false;
        }
        
        // Calculate which suggestion slot was swiped based on X position
        int recyclerWidth = recyclerView.getWidth();
        if (recyclerWidth <= 0) {
            return false;
        }
        
        // Account for hamburger button offset
        float recyclerX = x - hamburgerBtn.getWidth();
        if (recyclerX < 0) {
            return false; // Swipe was on hamburger button
        }
        
        // Calculate slot width and determine which slot was swiped
        int slotWidth = recyclerWidth / 3;
        int slotIndex = (int) (recyclerX / slotWidth);
        slotIndex = Math.max(0, Math.min(slotIndex, 2)); // Clamp to 0-2
        
        // Check if we have a suggestion at this index
        if (slotIndex >= currentSuggestions.size()) {
            return false;
        }
        
        // Commit the suggestion
        SuggestedWords.SuggestedWordInfo wordInfo = currentSuggestions.getWordInfo(slotIndex);
        if (wordInfo != null && listener != null) {
            listener.onSuggestionClicked(wordInfo, InputSource.SOFTWARE);
            return true;
        }
        
        return false;
    }
    
    /**
     * Custom LinearLayoutManager that can disable scrolling for Latin mode.
     */
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

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
import dev.bbkb.ime.core.keyevent.InputSource;
import dev.bbkb.ime.keyboard.internal.MoreKeysPanel;
import dev.bbkb.ime.keyboard.Keyboard;
import dev.bbkb.ime.keyboard.KeyboardActionListenerInterface;
import dev.bbkb.ime.keyboard.KeyboardColorManager;
import dev.bbkb.ime.keyboard.auxbar.suggestions.FlickSuggestionAnimationView;
import dev.bbkb.ime.keyboard.auxbar.suggestions.MoreSuggestionsKeyboard;
import dev.bbkb.ime.keyboard.auxbar.suggestions.MoreSuggestionsView;

import java.util.List;
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
    private SuggestedWords currentSuggestions;
    
    private Listener listener;
    private FlickSuggestionAnimationView suggestionAnimationView;

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

        // Setup RecyclerView with custom non-scrollable layout manager for Latin mode
        adapter = new UnifiedSuggestionAdapter(context, this);
        recyclerView.setAdapter(adapter);
        layoutManager = new NonScrollableLayoutManager(context);
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.setHasFixedSize(true);
        
        // Update adapter dimensions when RecyclerView layout changes
        recyclerView.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int newWidth = right - left;
            if (newWidth > 0) {
                adapter.setAvailableWidth(newWidth);
            }
        });

        // Setup hamburger button
        hamburgerBtn.setOnClickListener(v -> {
            if (listener != null) {
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
            }
            
            // Update adapter with current dimensions
            updateAdapterDimensions();
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
        currentSuggestions = words;
        adapter.setSuggestions(words);
    }

    @RequiresApi(api = Build.VERSION_CODES.R)
    public void setAutofillSuggestions(List<InlineSuggestion> suggestions, int width, int height) {
        hideOverlays();
        adapter.setAutofillSuggestions(suggestions, width, height);
    }

    public void clear() {
        currentSuggestions = null;
        adapter.clear();
        hideOverlays();
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
                    currentSuggestions, 
                    LATIN_MAX_SUGGESTIONS,  // Start from after visible suggestions
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

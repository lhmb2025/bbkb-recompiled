package dev.bbkb.ime.keyboard.auxbar;

import android.content.Context;
import android.os.Build;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.inputmethod.InlineSuggestion;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import dev.bbkb.ime.R;
import dev.bbkb.ime.core.suggestion.SuggestedWords;
import dev.bbkb.ime.keyboard.Keyboard;
import dev.bbkb.ime.keyboard.SimplifiedKeyboardView;
import dev.bbkb.ime.keyboard.KeyboardColorManager;

import android.util.Log;

import java.util.List;
import dev.bbkb.ime.BuildConfig;

/**
 * Container view for all auxiliary bars.
 * Contains two child views:
 * - UnifiedSuggestionView: For dynamic content (Latin, CJK, Autofill suggestions)
 * - SimplifiedKeyboardView: For static keys (UIM, Arrows, Accents, Diacritics)
 * 
 * Only one child is visible at a time, managed by AuxBarState.
 */
public class AuxBarView extends FrameLayout {

    private static final String TAG = "AuxBarView";
    private static final String DIAG = "INLINE_AUTOFILL_DEBUG";

    private UnifiedSuggestionView suggestionView;
    private SimplifiedKeyboardView sharedKeyView;
    private AuxBarState currentState = AuxBarState.NONE;

    private StateChangeListener stateChangeListener;

    private final kotlin.jvm.functions.Function0<kotlin.Unit> colorObserver = () -> {
        updateColors();
        return kotlin.Unit.INSTANCE;
    };

    public interface StateChangeListener {
        void onAuxBarStateChanged(AuxBarState oldState, AuxBarState newState);
    }

    public AuxBarView(@NonNull Context context) {
        this(context, null);
    }

    public AuxBarView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AuxBarView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        LayoutInflater.from(context).inflate(R.layout.aux_bar_view, this, true);

        suggestionView = findViewById(R.id.unified_suggestion_view);
        sharedKeyView = findViewById(R.id.shared_aux_key_view);

        // Start with suggestionView visible (default state), sharedKeyView invisible (not GONE)
        // Using INVISIBLE instead of GONE ensures sharedKeyView participates in layout
        // and has proper dimensions when we need to show it
        suggestionView.setVisibility(VISIBLE);
        sharedKeyView.setVisibility(INVISIBLE);
        
        // Ensure touch events are consumed and don't pass through to the app behind
        setClickable(true);
    }

    public void setStateChangeListener(StateChangeListener listener) {
        this.stateChangeListener = listener;
    }

    public AuxBarState getCurrentState() {
        return currentState;
    }

    public UnifiedSuggestionView getSuggestionView() {
        return suggestionView;
    }

    public SimplifiedKeyboardView getSharedKeyView() {
        return sharedKeyView;
    }

    /**
     * Show Latin or CJK suggestions.
     */
    public void showSuggestions(SuggestedWords words, boolean isCJK) {
        AuxBarState oldState = currentState;
        if (BuildConfig.DEBUG) {
        Log.d(TAG, "showSuggestions: oldState=" + oldState + " isCJK=" + isCJK
                + " suggestionViewVis=" + suggestionView.getVisibility()
                + " recyclerWidth=" + suggestionView.getWidth());
        }

        showSuggestionChild();
        suggestionView.setMode(isCJK ? SuggestionMode.CJK : SuggestionMode.LATIN);
        suggestionView.setSuggestions(words);

        enter(oldState, isCJK ? AuxBarState.CJK_SUGGESTIONS : AuxBarState.LATIN_SUGGESTIONS);
    }

    /**
     * Show autofill suggestions (Android 11+).
     */
    @RequiresApi(api = Build.VERSION_CODES.R)
    public void showAutofill(List<InlineSuggestion> suggestions, int width, int height) {
        AuxBarState oldState = currentState;
        if (BuildConfig.DEBUG) {
        Log.d(DIAG, "[AUXBARVIEW] showAutofill() called"
                + " | suggestionCount=" + (suggestions != null ? suggestions.size() : "null")
                + " | width=" + width + " | height=" + height
                + " | oldState=" + oldState
                + " | suggestionView=" + (suggestionView != null ? "SET" : "NULL")
                + " | containerVis=" + getVisibility());
        }
        
        showSuggestionChild();
        suggestionView.setMode(SuggestionMode.AUTOFILL);
        suggestionView.setAutofillSuggestions(suggestions, width, height);

        enter(oldState, AuxBarState.AUTOFILL);
        if (BuildConfig.DEBUG) Log.d(DIAG, "[AUXBARVIEW] showAutofill() complete | newState=AUTOFILL");
    }

    /**
     * Show a keyboard-based auxiliary bar (UIM, Arrows, Accents, Diacritics).
     */
    public void showKeys(Keyboard keyboard, AuxBarState state) {
        if (keyboard == null) {
            if (BuildConfig.DEBUG) Log.e(TAG, "DEBUG: showKeys() - FAILED: keyboard is null!");
            return;
        }
        
        AuxBarState oldState = currentState;
        
        // Ensure the container is visible
        setVisibility(VISIBLE);
        
        suggestionView.setVisibility(GONE);
        sharedKeyView.setVisibility(VISIBLE);
        sharedKeyView.setKeyboard(keyboard);
        
        // Force remeasure after setting keyboard
        sharedKeyView.requestLayout();
        enter(oldState, state);
    }

    /**
     * Dismiss the key-based view (accent bar, etc.) and restore the suggestion view
     * without clearing its content. Used by hold-to-auto-commit to restore the
     * suggestion strip after the accent bar is dismissed.
     */
    public void dismissKeyViewAndRestoreSuggestions() {
        AuxBarState oldState = currentState;
        showSuggestionChild();

        // Restore to the suggestion state that was active before the key view was shown.
        // Derived from the suggestion view's own mode rather than hardcoded to LATIN:
        // showKeys() never touched the mode, so it still reflects what was on screen, and
        // a CJK user dismissing a key view must not be reported as LATIN_SUGGESTIONS.
        // AUTOFILL is carried back too: the chips are what the restored child still shows,
        // and hideAutofillBar() only acts from AUTOFILL, so reporting LATIN left them stuck.
        enter(oldState, stateForSuggestionMode(suggestionView.getMode()));
    }

    private static AuxBarState stateForSuggestionMode(SuggestionMode mode) {
        switch (mode) {
            case CJK:
                return AuxBarState.CJK_SUGGESTIONS;
            case AUTOFILL:
                return AuxBarState.AUTOFILL;
            default:
                return AuxBarState.LATIN_SUGGESTIONS;
        }
    }

    /**
     * Hide all auxiliary bars.
     */
    public void hide() {
        AuxBarState oldState = currentState;
        if (oldState == AuxBarState.AUTOFILL) {
            if (BuildConfig.DEBUG) Log.d(DIAG, "[AUXBARVIEW] hide() clearing AUTOFILL state");
        }
        
        suggestionView.setVisibility(GONE);
        suggestionView.clear();
        sharedKeyView.setVisibility(GONE);
        // Hide the container itself — prevents phantom space in layout and
        // touch-event consumption when the bar is logically hidden.
        setVisibility(GONE);

        enter(oldState, AuxBarState.NONE);
    }

    /**
     * Check if any auxiliary bar is visible.
     */
    public boolean isShowing() {
        return currentState != AuxBarState.NONE;
    }

    /**
     * Update colors based on current theme.
     */
    public void updateColors() {
        KeyboardColorManager colorManager = KeyboardColorManager.INSTANCE;
        int bgColor = colorManager.getBackgroundColor();
        setBackgroundColor(bgColor);
        
        if (suggestionView != null) {
            suggestionView.updateColors();
        }
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

    /**
     * Container VISIBLE, key view GONE, suggestion view VISIBLE — in that order, which is the
     * order a held key on the key view is released in (it is routed by the state being left).
     */
    private void showSuggestionChild() {
        setVisibility(VISIBLE);
        sharedKeyView.setVisibility(GONE);
        suggestionView.setVisibility(VISIBLE);
    }

    /**
     * The tail of every transition: record the new state, then notify on a real change. Runs
     * after the children and content are in place. {@code oldState} is captured by the caller
     * at the top of the transition, before any visibility callback runs.
     */
    private void enter(AuxBarState oldState, AuxBarState newState) {
        currentState = newState;
        if (stateChangeListener != null && oldState != newState) {
            stateChangeListener.onAuxBarStateChanged(oldState, newState);
        }
    }
}

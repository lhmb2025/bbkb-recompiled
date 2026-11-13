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
import dev.bbkb.ime.core.settings.util.SettingsManager;
import dev.bbkb.ime.core.settings.util.SettingsValues;
import dev.bbkb.ime.core.suggestion.SuggestedWords;
import dev.bbkb.ime.keyboard.Keyboard;
import dev.bbkb.ime.keyboard.SimplifiedKeyboardView;
import dev.bbkb.ime.keyboard.KeyboardColorManager;

import android.util.Log;

import java.util.List;
import dev.bbkb.ime.BuildConfig;

/**
 * Container for the auxiliary bars: UnifiedSuggestionView (suggestions, autofill, language chips)
 * or SimplifiedKeyboardView (UIM, arrows, accents). One child is visible at a time, per AuxBarState.
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

        // INVISIBLE, not GONE, so sharedKeyView is measured and sized before it is first shown.
        suggestionView.setVisibility(VISIBLE);
        sharedKeyView.setVisibility(INVISIBLE);

        // Consume touches so they do not reach the app behind.
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

    public void showSuggestions(SuggestedWords words, boolean isCJK) {
        AuxBarState oldState = currentState;
        if (BuildConfig.DEBUG) {
        Log.d(TAG, "showSuggestions: oldState=" + oldState + " isCJK=" + isCJK
                + " suggestionViewVis=" + suggestionView.getVisibility()
                + " recyclerWidth=" + suggestionView.getWidth());
        }

        showSuggestionChild();
        suggestionView.setMode(isCJK ? SuggestionMode.CJK : SuggestionMode.LATIN);
        suggestionView.setMenuButtonVisible(menuEnabled());
        suggestionView.setSuggestions(words);

        enter(oldState, isCJK ? AuxBarState.CJK_SUGGESTIONS : AuxBarState.LATIN_SUGGESTIONS);
    }

    /**
     * Updates the words under a covering key bar without showing them, so uncovering the strip
     * shows current words. A no-op unless a key bar covers a word strip (autofill and language
     * chips underneath are kept).
     */
    public void setSuggestionsBehindKeys(SuggestedWords words, boolean isCJK) {
        if (currentState != AuxBarState.UNIFIED_INPUT_MENU && currentState != AuxBarState.ARROW_BAR
                && currentState != AuxBarState.ACCENT_BAR) {
            return;
        }
        SuggestionMode mode = suggestionView.getMode();
        if (mode != SuggestionMode.LATIN && mode != SuggestionMode.CJK) {
            return;
        }
        suggestionView.setMode(isCJK ? SuggestionMode.CJK : SuggestionMode.LATIN);
        suggestionView.setMenuButtonVisible(menuEnabled());
        suggestionView.setSuggestions(words);
    }

    /** "Enable unified input menu" (or the key's request); on when no settings are loaded yet. */
    private static boolean menuEnabled() {
        SettingsManager manager = SettingsManager.getInstance();
        SettingsValues values = manager == null ? null : manager.getSettingsValues();
        return values == null || values.isUimEnabled();
    }

    /** What the suggestion view showed before the language-chip bar took it over. */
    private SuggestionMode modeBeforeLanguageChips = SuggestionMode.LATIN;
    private SuggestedWords wordsBeforeLanguageChips;

    /** Show the language-chip bar: this keyboard's languages, the current one first and bold. */
    public void showLanguageChips(List<LanguageChip> chips) {
        AuxBarState oldState = currentState;
        if (oldState != AuxBarState.LANGUAGE_CHIPS) {
            modeBeforeLanguageChips = suggestionView.getMode();
            wordsBeforeLanguageChips = suggestionView.getCurrentSuggestions();
        }
        showSuggestionChild();
        suggestionView.setMode(SuggestionMode.LANGUAGES);
        suggestionView.setMenuButtonVisible(true);
        suggestionView.setLanguageChips(chips);
        enter(oldState, AuxBarState.LANGUAGE_CHIPS);
    }

    /** Puts back the mode and words the chips replaced (they share one view, unlike a key bar). */
    public void restoreSuggestionsAfterLanguageChips() {
        AuxBarState oldState = currentState;
        showSuggestionChild();
        suggestionView.setMode(modeBeforeLanguageChips);
        if (modeBeforeLanguageChips != SuggestionMode.AUTOFILL) {
            if (wordsBeforeLanguageChips != null) {
                suggestionView.setSuggestions(wordsBeforeLanguageChips);
            } else {
                suggestionView.clear();
            }
        }
        wordsBeforeLanguageChips = null;
        enter(oldState, stateForSuggestionMode(modeBeforeLanguageChips));
    }

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

    public void showKeys(Keyboard keyboard, AuxBarState state) {
        if (keyboard == null) {
            if (BuildConfig.DEBUG) Log.e(TAG, "DEBUG: showKeys() - FAILED: keyboard is null!");
            return;
        }
        
        AuxBarState oldState = currentState;

        setVisibility(VISIBLE);
        
        suggestionView.setVisibility(GONE);
        sharedKeyView.setVisibility(VISIBLE);
        // The accent bar draws its letters in the strip's text style, not a key's.
        sharedKeyView.setStripTextStyle(state == AuxBarState.ACCENT_BAR);
        sharedKeyView.setKeyboard(keyboard);

        sharedKeyView.requestLayout();
        enter(oldState, state);
    }

    /** Dismisses the key bar and uncovers the suggestion view with its content intact. */
    public void dismissKeyViewAndRestoreSuggestions() {
        AuxBarState oldState = currentState;
        showSuggestionChild();

        // Derive the state from the view's mode (showKeys never changes it), so CJK and AUTOFILL
        // come back as themselves; hideAutofillBar() only acts from AUTOFILL.
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

    public void hide() {
        AuxBarState oldState = currentState;
        if (oldState == AuxBarState.AUTOFILL) {
            if (BuildConfig.DEBUG) Log.d(DIAG, "[AUXBARVIEW] hide() clearing AUTOFILL state");
        }
        
        suggestionView.setVisibility(GONE);
        suggestionView.clear();
        sharedKeyView.setVisibility(GONE);
        // GONE container: no phantom layout space and no touch consumption while hidden.
        setVisibility(GONE);

        enter(oldState, AuxBarState.NONE);
    }

    public boolean isShowing() {
        return currentState != AuxBarState.NONE;
    }

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

    /** Order matters: a held key on the key view is released here, routed by the state being left. */
    private void showSuggestionChild() {
        setVisibility(VISIBLE);
        sharedKeyView.setVisibility(GONE);
        suggestionView.setVisibility(VISIBLE);
    }

    /**
     * Ends every transition, after children and content are in place. {@code oldState} must be
     * captured before any visibility callback runs.
     */
    private void enter(AuxBarState oldState, AuxBarState newState) {
        currentState = newState;
        if (stateChangeListener != null && oldState != newState) {
            stateChangeListener.onAuxBarStateChanged(oldState, newState);
        }
    }
}

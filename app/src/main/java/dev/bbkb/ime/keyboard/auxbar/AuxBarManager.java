package dev.bbkb.ime.keyboard.auxbar;

import android.content.Context;
import android.os.Build;
import dev.bbkb.ime.core.settings.PrefsManager;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.inputmethod.InlineSuggestion;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import dev.bbkb.ime.core.suggestion.SuggestedWords;
import dev.bbkb.ime.core.device.profile.DeviceProfile;
import dev.bbkb.ime.core.settings.util.SettingsManager;
import dev.bbkb.ime.core.settings.util.SettingsValues;
import dev.bbkb.ime.core.layout.PhysicalKeyResolver;
import dev.bbkb.ime.core.keyevent.InputSource;
import dev.bbkb.ime.core.locale.LocaleUtils;
import dev.bbkb.ime.keyboard.Keyboard;
import dev.bbkb.ime.keyboard.KeyboardBuilder;
import dev.bbkb.ime.keyboard.SimplifiedKeyboardView;
import dev.bbkb.ime.keyboard.KeyboardSwitcher;
import dev.bbkb.ime.keyboard.inputboard.UnifiedInputBoardManager;
import dev.bbkb.ime.core.subtypeswitcher.SubtypeFactory;
import dev.bbkb.ime.core.device.ResourceConfigManager;
import dev.bbkb.ime.R;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.bbkb.ime.core.keyevent.KeyHoldHandler;
import dev.bbkb.ime.keyboard.internal.MoreKeysProvider;
import dev.bbkb.ime.core.keyevent.ModifierState;
import dev.bbkb.ime.BuildConfig;

/**
 * Drives AuxBarView: state transitions, aux-bar keyboards, and the physical-key hold that raises
 * the accent bar (via KeyHoldHandler and MoreKeysProvider).
 */
public class AuxBarManager implements AuxBarView.StateChangeListener, UnifiedSuggestionView.Listener,
        KeyHoldHandler.KeyHoldCallback {

    private static final String TAG = "AuxBarManager";
    private static final String DIAG = "INLINE_AUTOFILL_DEBUG";

    private final Context context;
    private final Context themedContext;
    private KeyboardBuilder keyboardBuilder;
    private AuxBarView auxBarView;
    private Locale currentLocale;

    private Keyboard unifiedInputKeyboard;
    private Keyboard arrowKeyboard;

    private AuxBarEventListener eventListener;

    private ArrowBarController arrowBarController;

    private MoreKeysProvider moreKeysProvider;
    private boolean keyHoldCallbackRegistered = false;
    private int currentAccentBarKeyCode = -1; // the key that raised the accent bar
    /** What the accent bar covered (suggestions or the input menu), put back when it goes. */
    private AuxBarState stateBeforeAccentBar = AuxBarState.NONE;

    /** What the language-chip bar covered, put back when it goes. */
    private AuxBarState stateBeforeLanguageChips = AuxBarState.NONE;

    // Key hold detection state.
    private int lastKeyCode = -1;
    private long lastDownTime = 0;
    private int lastSymbolPageOrder = -1;
    private boolean accentBarTriggered = false; // accent bar already shown for this hold

    /** "off" / "uppercase" / "symbol", refreshed once per press rather than per auto-repeat. */
    private String holdActionMode = HOLD_ACTION_OFF;

    /** Repeats before auto-commit, about 300 ms after the accent bar appears. */
    private static final int AUTO_COMMIT_REPEAT_THRESHOLD = 6;

    private enum HoldState {
        IDLE,
        KEY_PRESSED,
        HOLD_DETECTED,
        HOLD_WAITING,
        AUTO_COMMITTED
    }

    /** pref_pkb_hold_auto_commit values. */
    public static final String HOLD_ACTION_OFF = "off";
    public static final String HOLD_ACTION_UPPERCASE = "uppercase";
    public static final String HOLD_ACTION_SYMBOL = "symbol";
    
    private HoldState holdState = HoldState.IDLE;
    private int holdRepeatCount = 0;
    private String holdBaseChar = null;       // committed on key down
    private String holdShiftChar = null;      // its Shift letter on a script layout, or null
    private boolean holdOnScriptLayout = false; // the held key types the active keyboard's alphabet
    private boolean autoCommitPerformed = false; // once per hold
    private boolean holdSuppressingRepeats = false; // for keys without accents
    private boolean holdHasAccents = false;   // accent bar shown for this hold

    public interface AuxBarEventListener {
        void onSuggestionSelected(SuggestedWords.SuggestedWordInfo wordInfo, InputSource inputSource);
        void onSuggestionLongPressed(SuggestedWords.SuggestedWordInfo wordInfo);
        void onAutofillSelected(int position);
        void onHamburgerMenuClicked();
        void onExpandSuggestionsClicked();
        void onAuxBarStateChanged(AuxBarState oldState, AuxBarState newState);
        /** The typing keyboard the more-suggestions panel is laid out from. */
        dev.bbkb.ime.keyboard.Keyboard getMainKeyboard();
        void onAccentSelected(String accentChar);
        void onLanguageChosen(android.view.inputmethod.InputMethodSubtype subtype);
        /** The gear on the language-chip bar. */
        void onLanguageSettingsClicked();
        /** The letter an accent pick would replace ("" if none); the bar uses its case. */
        String letterAnAccentWouldReplace();
        /** Whether the unified input menu is what this editor shows when the strip is not. */
        boolean shouldShowUim();
        boolean isInSymbolMode();
        String getMoreKeysForScanCode(int scanCode);
        /** Alt character lookups for the physical-key hold. */
        dev.bbkb.ime.core.keyevent.AuxCharacterResolver getAuxCharacterResolver();
    }

    public AuxBarManager(@NonNull Context context) {
        this.context = context;
        this.themedContext = new ContextThemeWrapper(context, R.style.KeyboardTheme_LXX);
        this.keyboardBuilder = createAuxBarKeyboardBuilder();
    }

    /** A builder sized to the aux bar strip. */
    private KeyboardBuilder createAuxBarKeyboardBuilder() {
        KeyboardBuilder.Builder aVar = new KeyboardBuilder.Builder(themedContext, null);
        aVar.setSubtype(SubtypeFactory.createSubtype(Locale.ENGLISH.toString(), "unified_input_menu"));
        android.content.res.Resources resources = themedContext.getResources();
        aVar.setKeyboardGeometry(ResourceConfigManager.getScreenWidthPixels(resources), 
                    ResourceConfigManager.getSuggestionsStripHeight(resources));
        return aVar.build();
    }

    public void setAuxBarView(@NonNull AuxBarView view) {
        this.auxBarView = view;
        this.auxBarView.setStateChangeListener(this);
        
        UnifiedSuggestionView suggestionView = auxBarView.getSuggestionView();
        if (suggestionView != null) {
            suggestionView.setListener(this);
        }

        // The shared key view routes by the current state.
        SimplifiedKeyboardView sharedKeyView = auxBarView.getSharedKeyView();
        if (sharedKeyView != null) {
            sharedKeyView.setOnKeyEventListener(new SimplifiedKeyboardView.onKeyEventListener() {
                @Override
                public void onKeyDown(dev.bbkb.ime.keyboard.Key key) {
                    if (auxBarView.getCurrentState() == AuxBarState.UNIFIED_INPUT_MENU) {
                        UnifiedInputBoardManager uibm = KeyboardSwitcher.getInstance().getUnifiedInputBoardManager();
                        if (uibm != null) {
                            uibm.onKeyDown(key);
                        }
                    } else if (auxBarView.getCurrentState() == AuxBarState.ARROW_BAR) {
                        if (arrowBarController != null) {
                            arrowBarController.onKeyDown(key);
                        }
                    }
                }

                @Override
                public void onKeyUp(dev.bbkb.ime.keyboard.Key key, boolean released) {
                    if (key == null) return;
                    
                    AuxBarState state = auxBarView.getCurrentState();
                    if (state == AuxBarState.ACCENT_BAR) {
                        if (released) {
                            onAccentKeySelected(key);
                        }
                    } else if (state == AuxBarState.UNIFIED_INPUT_MENU) {
                        UnifiedInputBoardManager uibm = KeyboardSwitcher.getInstance().getUnifiedInputBoardManager();
                        if (uibm != null) {
                            uibm.onKeyUp(key, released);
                        }
                    } else if (state == AuxBarState.ARROW_BAR) {
                        if (arrowBarController != null) {
                            arrowBarController.onKeyUp(key, released);
                        }
                    }
                }

                @Override
                public void onKeyLongPress(dev.bbkb.ime.keyboard.Key key) {
                    if (auxBarView.getCurrentState() == AuxBarState.UNIFIED_INPUT_MENU) {
                        UnifiedInputBoardManager uibm = KeyboardSwitcher.getInstance().getUnifiedInputBoardManager();
                        if (uibm != null) {
                            uibm.onKeyLongPress(key);
                        }
                    }
                }
            });
        }
    }

    private void onAccentKeySelected(dev.bbkb.ime.keyboard.Key key) {
        int code = key.getCode();
        if (eventListener == null || code <= 0) {
            return;
        }
        String accentChar = new String(Character.toChars(code));
        // A manual pick cancels any pending auto-commit.
        resetHoldState();
        eventListener.onAccentSelected(accentChar);
        hideAccentBar();
    }

    public void setEventListener(@Nullable AuxBarEventListener listener) {
        this.eventListener = listener;
    }

    public void setArrowBarController(ArrowBarController controller) {
        this.arrowBarController = controller;
    }

    public void setCurrentLocale(Locale locale) {
        this.currentLocale = locale;
    }

    @Nullable
    public AuxBarView getAuxBarView() {
        return auxBarView;
    }

    public AuxBarState getCurrentState() {
        return auxBarView != null ? auxBarView.getCurrentState() : AuxBarState.NONE;
    }

    public boolean isShowing() {
        return auxBarView != null && auxBarView.isShowing();
    }

    /**
     * Shows the strip, CJK or not by the current locale. Never while the on-screen keyboard is
     * visible (it uses FlickSuggestionView) or in fields that decline suggestions.
     */
    public void showSuggestionStrip(@NonNull SuggestedWords words) {
        if (auxBarView == null || fieldForbidsSuggestions()) {
            return;
        }

        if (DeviceProfile.isOnScreenKeyboardVisible()) {
            return;
        }

        if (isLatinStripHidden()) {
            hideSuggestionBar();
            return;
        }
        
        boolean isCJK = isChineseOrJapaneseLocale();
        auxBarView.showSuggestions(words, isCJK);
    }

    /** Shows the strip on physical-keyboard devices only, never in fields that decline suggestions. */
    public void showSuggestionStrip(@NonNull SuggestedWords words, boolean isCJK) {
        if (auxBarView == null || fieldForbidsSuggestions()) {
            return;
        }

        // On-screen keyboard devices use FlickSuggestionView.
        if (!DeviceProfile.current().isPkbDevice()) {
            return;
        }

        if (!isCJK && isLatinStripHidden()) {
            hideSuggestionBar();
            return;
        }
        
        auxBarView.showSuggestions(words, isCJK);
    }

    /**
     * Takes down what "Show the suggestion bar" hides: a Latin strip, the input menu (unless its
     * key raised it), or the empty initial container (VISIBLE in state NONE). Autofill, the key
     * bars and a CJK strip stay; an accent or chip bar over a hidden bar closes onto nothing.
     */
    public void hideSuggestionBar() {
        if (auxBarView == null) {
            return;
        }
        AuxBarState state = auxBarView.getCurrentState();
        if (state == AuxBarState.ACCENT_BAR && isHiddenWithTheBar(stateBeforeAccentBar)) {
            stateBeforeAccentBar = AuxBarState.NONE;
        }
        if (state == AuxBarState.LANGUAGE_CHIPS && isHiddenWithTheBar(stateBeforeLanguageChips)) {
            stateBeforeLanguageChips = AuxBarState.NONE;
        }
        boolean emptyContainer = state == AuxBarState.NONE && auxBarView.getVisibility() == View.VISIBLE;
        if (emptyContainer || isHiddenWithTheBar(state)) {
            auxBarView.hide();
        }
    }

    /** The input menu unless its key asked for it, or a Latin strip on a non-CJK keyboard. */
    private boolean isHiddenWithTheBar(AuxBarState state) {
        return (state == AuxBarState.UNIFIED_INPUT_MENU && !SettingsValues.isInputMenuRequestedByKey())
                || (state == AuxBarState.LATIN_SUGGESTIONS && !isChineseOrJapaneseLocale());
    }

    /** "Show the suggestion bar" is off and the keyboard is not CJK (whose strip always stays). */
    private boolean isLatinStripHidden() {
        SettingsValues settings = SettingsManager.getInstance().getSettingsValues();
        return settings != null && settings.isPkbSuggestionBarHidden() && !isChineseOrJapaneseLocale();
    }

    private boolean isChineseOrJapaneseLocale() {
        return currentLocale != null && LocaleUtils.isChineseOrJapanese(currentLocale);
    }

    /**
     * True when the field declines suggestions (password, URI, NO_SUGGESTIONS...). Hides a
     * Latin/CJK strip already up; autofill and the key bars are left alone.
     */
    private boolean fieldForbidsSuggestions() {
        SettingsValues settings = SettingsManager.getInstance().getSettingsValues();
        if (settings == null || settings.editorCapabilities.shouldShowSuggestions) {
            return false;
        }
        AuxBarState state = auxBarView.getCurrentState();
        if (state == AuxBarState.LATIN_SUGGESTIONS || state == AuxBarState.CJK_SUGGESTIONS) {
            auxBarView.hide();
        }
        return true;
    }

    @RequiresApi(api = Build.VERSION_CODES.R)
    public void showAutofillBar(@NonNull List<InlineSuggestion> suggestions, int width, int height) {
        if (BuildConfig.DEBUG) {
        Log.d(DIAG, "[AUXBAR] showAutofillBar() called"
                + " | suggestionCount=" + suggestions.size()
                + " | width=" + width
                + " | height=" + height
                + " | auxBarView=" + (auxBarView != null ? "SET" : "NULL"));
        }
        if (auxBarView == null) {
            if (BuildConfig.DEBUG) Log.d(DIAG, "[AUXBAR] showAutofillBar() ABORTED: auxBarView is null");
            return;
        }
        
        auxBarView.showAutofill(suggestions, width, height);
    }

    /** Hides autofill chips; the next suggestion update refills the strip. */
    public void hideAutofillBar() {
        if (BuildConfig.DEBUG) {
        Log.d(DIAG, "[AUXBAR] hideAutofillBar() called"
                + " | currentState=" + (auxBarView != null ? auxBarView.getCurrentState() : "auxBarView=NULL"));
        }
        if (auxBarView == null) {
            return;
        }
        
        if (auxBarView.getCurrentState() == AuxBarState.AUTOFILL) {
            auxBarView.hide();
            // A menu the user chose comes back; otherwise the next suggestion update fills the strip.
            if (eventListener != null && eventListener.shouldShowUim()) {
                showUnifiedInputMenu();
            }
            if (BuildConfig.DEBUG) Log.d(DIAG, "[AUXBAR] hideAutofillBar() executed - was in AUTOFILL state");
        } else {
            if (BuildConfig.DEBUG) Log.d(DIAG, "[AUXBAR] hideAutofillBar() no-op - not in AUTOFILL state");
        }
    }

    /** Shows the menu with UnifiedInputBoardManager's keyboard. */
    public void showUnifiedInputMenu(@NonNull Keyboard keyboard) {
        
        if (auxBarView == null) {
            return;
        }
        
        this.unifiedInputKeyboard = keyboard;
        auxBarView.showKeys(keyboard, AuxBarState.UNIFIED_INPUT_MENU);
    }

    public void showUnifiedInputMenu() {
        if (auxBarView == null) {
            return;
        }
        
        if (unifiedInputKeyboard == null) {
            // Built by UnifiedInputBoardManager so the user's menu order (pref_uim_menu_order) applies.
            dev.bbkb.ime.keyboard.inputboard.UnifiedInputBoardManager uibm =
                    dev.bbkb.ime.keyboard.KeyboardSwitcher.getInstance().getUnifiedInputBoardManager();
            unifiedInputKeyboard = uibm != null ? uibm.getOrBuildKeyboard() : buildKeyboard(138);
        }
        
        if (unifiedInputKeyboard != null) {
            auxBarView.showKeys(unifiedInputKeyboard, AuxBarState.UNIFIED_INPUT_MENU);
        } else {
            if (BuildConfig.DEBUG) Log.e(TAG, "DEBUG: showUnifiedInputMenu() - FAILED: unifiedInputKeyboard is null!");
        }
    }

    /**
     * Updates the words under a covering key bar without showing them. Same gates as
     * {@link #showSuggestionStrip(SuggestedWords, boolean)}, without their side effects.
     */
    public void updateHiddenSuggestionStrip(@NonNull SuggestedWords words, boolean isCJK) {
        if (auxBarView == null || !DeviceProfile.current().isPkbDevice()) {
            return;
        }
        SettingsValues settings = SettingsManager.getInstance().getSettingsValues();
        if (settings != null && !settings.editorCapabilities.shouldShowSuggestions) {
            return;
        }
        if (!isCJK && isLatinStripHidden()) {
            return;
        }
        auxBarView.setSuggestionsBehindKeys(words, isCJK);
    }

    /**
     * Uncovers the strip under the menu with its words intact (unlike {@link #hideUnifiedInputMenu()},
     * which empties it). With no strip to return to, the bar goes away instead.
     */
    public void restoreStripFromMenu() {
        if (auxBarView == null || auxBarView.getCurrentState() != AuxBarState.UNIFIED_INPUT_MENU) {
            return;
        }
        SettingsValues settings = SettingsManager.getInstance().getSettingsValues();
        boolean fieldTakesSuggestions = settings == null || settings.editorCapabilities.shouldShowSuggestions;
        boolean latin = auxBarView.getSuggestionView().getMode() == SuggestionMode.LATIN;
        if (!fieldTakesSuggestions || (latin && isLatinStripHidden())) {
            auxBarView.hide();
        } else {
            auxBarView.dismissKeyViewAndRestoreSuggestions();
        }
    }

    public void hideUnifiedInputMenu() {
        if (auxBarView != null && auxBarView.getCurrentState() == AuxBarState.UNIFIED_INPUT_MENU) {
            auxBarView.hide();
        }
    }

    /** The shared key view, which UnifiedInputBoardManager tints. */
    public SimplifiedKeyboardView getDisplayedKeyView() {
        if (auxBarView != null) {
            return auxBarView.getSharedKeyView();
        }
        return null;
    }

    public void showArrowBar() {
        if (BuildConfig.DEBUG) Log.d(TAG, "ARROW_BAR_DIAG showArrowBar: auxBarView=" + (auxBarView != null ? "non-null" : "NULL"));
        if (auxBarView == null) {
            if (BuildConfig.DEBUG) Log.e(TAG, "ARROW_BAR_DIAG showArrowBar: ERROR — auxBarView is null");
            return;
        }
        
        if (arrowKeyboard == null) {
            if (BuildConfig.DEBUG) Log.d(TAG, "ARROW_BAR_DIAG showArrowBar: building arrowKeyboard via ArrowBarController");
            if (arrowBarController != null) {
                arrowKeyboard = arrowBarController.buildArrowKeyboard();
            }
            if (BuildConfig.DEBUG) Log.d(TAG, "ARROW_BAR_DIAG showArrowBar: arrowKeyboard build result=" + (arrowKeyboard != null ? "OK" : "FAILED (arrowBarController=" + arrowBarController + ")"));
        }
        
        if (arrowKeyboard != null) {
            if (BuildConfig.DEBUG) Log.d(TAG, "ARROW_BAR_DIAG showArrowBar: calling auxBarView.showKeys(ARROW_BAR)");
            auxBarView.showKeys(arrowKeyboard, AuxBarState.ARROW_BAR);
        } else {
            if (BuildConfig.DEBUG) Log.e(TAG, "ARROW_BAR_DIAG showArrowBar: ERROR — arrowKeyboard is null, cannot show");
        }
    }

    public void hideArrowBar() {
        // Not hide(): that blinks the container GONE and clears suggestions the bar only covered.
        if (auxBarView != null && auxBarView.getCurrentState() == AuxBarState.ARROW_BAR) {
            // With "Show the suggestion bar" off there is no Latin strip underneath to restore.
            if (auxBarView.getSuggestionView().getMode() == SuggestionMode.LATIN && isLatinStripHidden()) {
                auxBarView.hide();
            } else {
                auxBarView.dismissKeyViewAndRestoreSuggestions();
            }
        }
    }

    public void showAccentBar(@NonNull List<String> accents) {
        if (auxBarView == null || accents.isEmpty()) {
            return;
        }
        
        Keyboard accentKeyboard = buildAccentKeyboard(accents);
        if (accentKeyboard != null) {
            if (auxBarView.getCurrentState() != AuxBarState.ACCENT_BAR) {
                stateBeforeAccentBar = auxBarView.getCurrentState();
            }
            auxBarView.showKeys(accentKeyboard, AuxBarState.ACCENT_BAR);
        } else {
            if (BuildConfig.DEBUG) Log.e(TAG, "showAccentBar: failed to build keyboard from accents: " + accents);
        }
    }

    /**
     * Puts back what the accent bar covered, not a plain hide() that would empty the slot. With
     * nothing underneath, shows the menu if this editor uses it.
     */
    public void hideAccentBar() {
        currentAccentBarKeyCode = -1;
        if (auxBarView == null || auxBarView.getCurrentState() != AuxBarState.ACCENT_BAR) {
            return;
        }
        AuxBarState covered = stateBeforeAccentBar;
        stateBeforeAccentBar = AuxBarState.NONE;
        restoreCovered(covered);
    }

    /**
     * Shows the chip bar over whatever is up. A chip tap, the next key or
     * {@link #hideLanguageChips()} puts back what it covered.
     */
    public void showLanguageChips(@NonNull List<LanguageChip> chips) {
        if (auxBarView == null || chips.isEmpty()) {
            return;
        }
        if (auxBarView.getCurrentState() == AuxBarState.ACCENT_BAR) {
            hideAccentBar();
        }
        if (auxBarView.getCurrentState() != AuxBarState.LANGUAGE_CHIPS) {
            stateBeforeLanguageChips = auxBarView.getCurrentState();
        }
        auxBarView.showLanguageChips(chips);
    }

    public boolean isShowingLanguageChips() {
        return auxBarView != null && auxBarView.getCurrentState() == AuxBarState.LANGUAGE_CHIPS;
    }

    public void hideLanguageChips() {
        if (!isShowingLanguageChips()) {
            return;
        }
        AuxBarState covered = stateBeforeLanguageChips;
        stateBeforeLanguageChips = AuxBarState.NONE;
        switch (covered) {
            case LATIN_SUGGESTIONS:
            case CJK_SUGGESTIONS:
            case AUTOFILL:
                // The chips took the suggestion view itself: put its words back.
                auxBarView.restoreSuggestionsAfterLanguageChips();
                break;
            default:
                restoreCovered(covered);
                break;
        }
    }

    /** Puts back the state a key bar covered. */
    private void restoreCovered(AuxBarState covered) {
        switch (covered) {
            case LATIN_SUGGESTIONS:
            case CJK_SUGGESTIONS:
            case AUTOFILL:
                auxBarView.dismissKeyViewAndRestoreSuggestions();
                break;
            case UNIFIED_INPUT_MENU:
                showUnifiedInputMenu();
                break;
            default:
                if (eventListener != null && eventListener.shouldShowUim()) {
                    showUnifiedInputMenu();
                } else {
                    auxBarView.hide();
                }
                break;
        }
    }

    public void hide() {
        if (auxBarView != null) {
            auxBarView.hide();
        }
    }

    public void updateTheme() {
        if (auxBarView != null) {
            auxBarView.updateColors();
        }
        // Rebuild keyboards with the new theme.
        clearKeyboardCache();
    }

    public void clearKeyboardCache() {
        unifiedInputKeyboard = null;
        arrowKeyboard = null;
    }

    @Nullable
    private Keyboard buildKeyboard(int layoutId) {
        
        if (keyboardBuilder == null) {
            if (BuildConfig.DEBUG) Log.e(TAG, "DEBUG: buildKeyboard() - FAILED: KeyboardBuilder not initialized!");
            return null;
        }
        
        try {
            Keyboard result = keyboardBuilder.getKeyboardForShift(layoutId, true);
            return result;
        } catch (Exception e) {
            if (BuildConfig.DEBUG) Log.e(TAG, "DEBUG: buildKeyboard(" + layoutId + ") EXCEPTION: " + e.getMessage(), e);
            return null;
        }
    }

    @Nullable
    private Keyboard buildAccentKeyboard(@NonNull List<String> accents) {
        try {
            List<CharSequence> labels = new ArrayList<>(accents);

            int keyHeight = ResourceConfigManager.getSuggestionsStripHeight(context.getResources());
            return KeyboardBuilder.createFromLabels(context, labels, keyHeight);
        } catch (Exception e) {
            if (BuildConfig.DEBUG) android.util.Log.e(TAG, "Failed to build accent keyboard", e);
            return null;
        }
    }

    @Override
    public void onAuxBarStateChanged(AuxBarState oldState, AuxBarState newState) {
        if (eventListener != null) {
            eventListener.onAuxBarStateChanged(oldState, newState);
        }
    }

    @Override
    public void onSuggestionClicked(SuggestedWords.SuggestedWordInfo wordInfo, InputSource inputSource) {
        if (eventListener != null) {
            eventListener.onSuggestionSelected(wordInfo, inputSource);
        }
    }

    @Override
    public void onSuggestionLongClicked(SuggestedWords.SuggestedWordInfo wordInfo) {
        if (eventListener != null) {
            eventListener.onSuggestionLongPressed(wordInfo);
        }
    }

    @Override
    public void onAutofillSuggestionClicked(int position) {
        if (eventListener != null) {
            eventListener.onAutofillSelected(position);
        }
    }

    @Override
    public void onHamburgerClicked() {
        if (eventListener != null) {
            eventListener.onHamburgerMenuClicked();
        }
    }

    @Override
    public void onLanguageChipClicked(LanguageChip chip) {
        hideLanguageChips();
        if (eventListener != null && !chip.current) {
            eventListener.onLanguageChosen(chip.subtype);
        }
    }

    @Override
    public void onLanguageSettingsClicked() {
        hideLanguageChips();
        if (eventListener != null) {
            eventListener.onLanguageSettingsClicked();
        }
    }

    @Override
    public void onExpandClicked() {
        if (eventListener != null) {
            eventListener.onExpandSuggestionsClicked();
        }
    }

    @Override
    public Keyboard onMoreSuggestionsRequested() {
        if (eventListener != null) {
            return eventListener.getMainKeyboard();
        }
        return null;
    }

    /** Normally a CompositeMoreKeysProvider, set during initialization. */
    public void setMoreKeysProvider(MoreKeysProvider provider) {
        this.moreKeysProvider = provider;
    }
    
    /**
     * Accent bar and hold-to-auto-commit for a physical key down; true to consume the event.
     * Hold states: IDLE, KEY_PRESSED, HOLD_DETECTED, HOLD_WAITING, AUTO_COMMITTED.
     * @param symbolPageOrder current symbol page order from PhysicalKeyboardStateTracker
     */
    public boolean processKeyDown(android.view.KeyEvent event, int symbolPageOrder) {
        if (auxBarView == null) {
            return false;
        }
        
        int keyCode = event.getKeyCode();
        int repeatCount = event.getRepeatCount();
        long downTime = event.getDownTime();

        // New press: reset.
        if (repeatCount == 0) {
            if (auxBarView.getCurrentState() == AuxBarState.ACCENT_BAR) {
                hideAccentBar();
                accentBarTriggered = false;
            }
            // Typing on dismisses the language-chip bar.
            hideLanguageChips();

            lastKeyCode = keyCode;
            lastDownTime = downTime;
            lastSymbolPageOrder = symbolPageOrder;
            accentBarTriggered = false;

            resetHoldState();
            holdState = HoldState.KEY_PRESSED;
            // Read once per hold, not per repeat.
            this.holdActionMode = readHoldActionMode();

            holdBaseChar = getBaseCharacterForEvent(event);
            // On a keyboard with its own alphabet the held key's "capital" is its Shift letter
            // (Й, or ض on the ص key), not the upper case of the reported Latin letter.
            holdOnScriptLayout = PhysicalKeyResolver.letter(event, 0) != 0;
            int heldLetter = holdOnScriptLayout ? PhysicalKeyResolver.heldLetter(event) : 0;
            holdShiftChar = heldLetter != 0 ? new String(Character.toChars(heldLetter)) : null;

            return false; // the normal IME flow commits the base char
        }

        // Already auto-committed: consume all further repeats.
        if (holdState == HoldState.AUTO_COMMITTED) {
            return true;
        }

        // Different key or press.
        if (keyCode != lastKeyCode || downTime != lastDownTime) {
            resetHoldState();
            return false;
        }
        
        String holdAction = this.holdActionMode;
        boolean holdFeatureEnabled = !HOLD_ACTION_OFF.equals(holdAction);
        // The key past M carries a letter on Arabic and Cyrillic keycaps, so it counts there.
        boolean isEligibleKey = isLetterKey(keyCode) || holdOnScriptLayout;
        // From the repeating event itself, not the tracker's sticky/locked state.
        ModifierState eventModifiers = ModifierState.ofEvent(event);
        boolean shiftActive = eventModifiers.isShiftHeld();
        boolean altActive = eventModifiers.isAltHeld();

        // Shift already gives uppercase and Alt the symbol.
        boolean skipAutoCommit = !holdFeatureEnabled || !isEligibleKey
                || (HOLD_ACTION_UPPERCASE.equals(holdAction) && shiftActive)
                || (HOLD_ACTION_SYMBOL.equals(holdAction) && altActive);

        // First repeat: try the accent bar, enter HOLD_DETECTED.
        if (repeatCount == 1) {
            boolean accentBarShown = false;
            if (!accentBarTriggered) {
                accentBarShown = tryShowAccentBar(event, symbolPageOrder);
                if (accentBarShown) {
                    accentBarTriggered = true;
                }
            }
            
            if (!skipAutoCommit) {
                holdState = HoldState.HOLD_DETECTED;
                holdRepeatCount = 1;
                holdHasAccents = accentBarShown;
                holdSuppressingRepeats = !accentBarShown;
            }

            if (accentBarShown) return true;
            if (!skipAutoCommit && holdSuppressingRepeats) return true;
            return false;
        }

        // Later repeats: consume and count toward the threshold.
        boolean accentBarActive = auxBarView.getCurrentState() == AuxBarState.ACCENT_BAR 
                && currentAccentBarKeyCode == keyCode;
        if (accentBarActive || (accentBarTriggered && !skipAutoCommit)) {
            holdRepeatCount = repeatCount;
            
            if (!skipAutoCommit && holdState == HoldState.HOLD_DETECTED) {
                holdState = HoldState.HOLD_WAITING;
            }

            // Three times longer when there are accents to choose from.
            int effectiveThreshold = holdHasAccents 
                    ? AUTO_COMMIT_REPEAT_THRESHOLD * 3 
                    : AUTO_COMMIT_REPEAT_THRESHOLD;
            if (!skipAutoCommit && holdState == HoldState.HOLD_WAITING 
                    && repeatCount >= effectiveThreshold && !autoCommitPerformed) {
                performAutoCommit(keyCode, holdAction);
            }
            
            return true;
        }

        // Keys without accents: suppress repeats.
        if (!skipAutoCommit && holdSuppressingRepeats) {
            holdRepeatCount = repeatCount;
            
            if (holdState == HoldState.HOLD_DETECTED) {
                holdState = HoldState.HOLD_WAITING;
            }

            if (holdState == HoldState.HOLD_WAITING 
                    && repeatCount >= AUTO_COMMIT_REPEAT_THRESHOLD && !autoCommitPerformed) {
                performAutoCommit(keyCode, holdAction);
            }
            
            return true;
        }

        // Normal repeat.
        return false;
    }

    public void processKeyUp(android.view.KeyEvent event) {
        if (holdState != HoldState.IDLE) {
            resetHoldState();
        }
    }

    private void resetHoldState() {
        holdState = HoldState.IDLE;
        holdRepeatCount = 0;
        holdBaseChar = null;
        holdShiftChar = null;
        holdOnScriptLayout = false;
        autoCommitPerformed = false;
        holdSuppressingRepeats = false;
        holdHasAccents = false;
    }

    /** "off", "uppercase" or "symbol"; from the settings snapshot, the preference only before one exists. */
    private String readHoldActionMode() {
        SettingsManager manager = SettingsManager.getInstance();
        SettingsValues settings = manager != null ? manager.getSettingsValues() : null;
        String mode = settings != null
                ? settings.pkbHoldAction
                : PrefsManager.INSTANCE.getPrefs(this.context)
                        .getString("pref_pkb_hold_auto_commit", HOLD_ACTION_OFF);
        return mode == null ? HOLD_ACTION_OFF : mode;
    }

    private static boolean isLetterKey(int keyCode) {
        return keyCode >= android.view.KeyEvent.KEYCODE_A 
            && keyCode <= android.view.KeyEvent.KEYCODE_Z;
    }

    private void performAutoCommit(int keyCode, String holdAction) {
        if (autoCommitPerformed || eventListener == null) return;
        
        String resolved = resolveAutoCommitChar(keyCode, holdAction);
        if (resolved == null || resolved.isEmpty()) {
            return;
        }
        
        autoCommitPerformed = true;
        holdState = HoldState.AUTO_COMMITTED;

        // Same path as an accent pick: replaces the last char.
        eventListener.onAccentSelected(resolved);

        hideAccentBar();
    }

    /** Uppercase: the Shift letter or locale upper case; symbol: the AuxCharacterResolver lookup. */
    @Nullable
    private String resolveAutoCommitChar(int keyCode, String holdAction) {
        if (HOLD_ACTION_UPPERCASE.equals(holdAction)) {
            if (holdOnScriptLayout) {
                // Null on a key with a single letter: there is nothing to switch to.
                return holdShiftChar;
            }
            if (holdBaseChar != null && !holdBaseChar.isEmpty()) {
                Locale locale = currentLocale != null ? currentLocale : Locale.getDefault();
                return holdBaseChar.toUpperCase(locale);
            }
            return null;
        } else if (HOLD_ACTION_SYMBOL.equals(holdAction)) {
            if (eventListener != null) {
                dev.bbkb.ime.core.keyevent.AuxCharacterResolver resolver = 
                    eventListener.getAuxCharacterResolver();
                if (resolver != null) {
                    dev.bbkb.ime.core.keyevent.AuxCharacterResolver.Result result = 
                        resolver.resolve(keyCode);
                    if (result.hasCharacter()) {
                        return result.text();
                    }
                }
            }
            return null;
        }
        return null;
    }
    
    /**
     * True if shown. Looks up the layout key's moreKeys by scan code first (symbol keys), then
     * MoreKeysProvider (accents, style more keys).
     */
    private boolean tryShowAccentBar(android.view.KeyEvent event, int symbolPageOrder) {
        int keyCode = event.getKeyCode();
        int scanCode = event.getScanCode();
        List<String> moreKeys = null;
        boolean inSymbolMode = symbolPageOrder == 1 || symbolPageOrder == 3;

        if (eventListener != null) {
            String moreKeysSpec = eventListener.getMoreKeysForScanCode(scanCode);
            if (moreKeysSpec != null && !moreKeysSpec.isEmpty()) {
                moreKeys = parseMoreKeysSpec(moreKeysSpec);
            }
        }

        if ((moreKeys == null || moreKeys.isEmpty()) && moreKeysProvider != null) {
            String baseChar = getBaseCharacterForEvent(event);
            if (baseChar != null && !baseChar.isEmpty()) {
                MoreKeysProvider.MoreKeysContext context = new MoreKeysProvider.MoreKeysContext(
                        baseChar, ModifierState.ofEvent(event), true, inSymbolMode);
                
                moreKeys = moreKeysProvider.getMoreKeys(context);
            }
        }
        
        if (moreKeys != null && !moreKeys.isEmpty()) {
            currentAccentBarKeyCode = keyCode;
            showAccentBar(inCaseOfTheTypedLetter(moreKeys));
            return true;
        }
        
        return false;
    }

    /** One key per code point ("–—-" is three keys), not comma-separated. */
    private List<String> parseMoreKeysSpec(String moreKeysSpec) {
        List<String> result = new ArrayList<>();
        if (moreKeysSpec == null || moreKeysSpec.isEmpty()) {
            return result;
        }

        int i = 0;
        while (i < moreKeysSpec.length()) {
            int codePoint = moreKeysSpec.codePointAt(i);
            String character = new String(Character.toChars(codePoint));
            result.add(character);
            i += Character.charCount(codePoint);
        }
        
        return result;
    }

    /** Call once the manager is fully initialized. */
    public void registerKeyHoldCallback() {
        if (!keyHoldCallbackRegistered) {
            KeyHoldHandler.getInstance().addCallback(this);
            keyHoldCallbackRegistered = true;
        }
    }

    @Override
    public void onKeyHoldStart(android.view.KeyEvent event, int repeatCount) {
        if (moreKeysProvider == null || auxBarView == null) {
            return;
        }
        
        int keyCode = event.getKeyCode();

        if (auxBarView.getCurrentState() == AuxBarState.ACCENT_BAR && 
            currentAccentBarKeyCode != -1 && currentAccentBarKeyCode != keyCode) {
            hideAccentBar();
        }

        String baseChar = getBaseCharacterForEvent(event);
        if (baseChar == null || baseChar.isEmpty()) {
            return;
        }
        
        MoreKeysProvider.MoreKeysContext context = new MoreKeysProvider.MoreKeysContext(
                baseChar, ModifierState.ofEvent(event), true,
                eventListener != null && eventListener.isInSymbolMode());

        List<String> moreKeys = moreKeysProvider.getMoreKeys(context);
        
        if (moreKeys != null && !moreKeys.isEmpty()) {
            currentAccentBarKeyCode = keyCode;
            showAccentBar(inCaseOfTheTypedLetter(moreKeys));
        }
    }

    /** In the case of the letter it would replace, as {@code InputLogic.replaceLastCharWithAccent} commits it. */
    private List<String> inCaseOfTheTypedLetter(List<String> moreKeys) {
        if (eventListener == null) return moreKeys;
        Locale locale = currentLocale != null ? currentLocale : Locale.getDefault();
        return dev.bbkb.ime.core.textinput.InputLogic.matchCase(eventListener.letterAnAccentWouldReplace(), moreKeys, locale);
    }
    
    @Override
    public void onKeyHoldContinue(android.view.KeyEvent event, int repeatCount) {
        // The accent bar is already up.
    }
    
    @Override
    public void onKeyHoldEnd(android.view.KeyEvent event) {
        // The bar stays up for a pick; a pick, another key, Escape or Back dismisses it.
    }

    /** The character more keys are looked up by; Alt is stripped so StyleMoreKeysProvider finds the base key. */
    private String getBaseCharacterForEvent(android.view.KeyEvent event) {
        int metaState = event.getMetaState();

        if ((metaState & android.view.KeyEvent.META_ALT_MASK) != 0) {
            int baseMetaState = metaState & ~android.view.KeyEvent.META_ALT_MASK;
            int unicodeChar = event.getUnicodeChar(baseMetaState);
            if (unicodeChar > 0 && Character.isValidCodePoint(unicodeChar)) {
                return new String(Character.toChars(unicodeChar));
            }
        }

        // Script layouts first: Arabic, Cyrillic, Hebrew, Greek and Korean lists are keyed by that
        // letter, while the system key map says "q".
        int scriptLetter = PhysicalKeyResolver.letter(event, metaState);
        if (scriptLetter != 0) {
            return new String(Character.toChars(scriptLetter));
        }

        int unicodeChar = event.getUnicodeChar(metaState);
        if (unicodeChar > 0 && Character.isValidCodePoint(unicodeChar)) {
            return new String(Character.toChars(unicodeChar));
        }
        return null;
    }
}

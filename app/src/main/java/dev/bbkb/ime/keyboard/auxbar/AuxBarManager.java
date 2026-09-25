package dev.bbkb.ime.keyboard.auxbar;

import android.content.Context;
import android.os.Build;
import dev.bbkb.ime.core.settings.PrefsManager;
import android.view.ContextThemeWrapper;
import android.view.inputmethod.InlineSuggestion;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import dev.bbkb.ime.core.suggestion.SuggestedWords;
import dev.bbkb.ime.core.device.profile.DeviceProfile;
import dev.bbkb.ime.core.settings.util.SettingsManager;
import dev.bbkb.ime.core.settings.util.SettingsValues;
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
 * Central manager for the AuxBarView.
 * Handles state transitions, keyboard building, and coordinates with InputViewCoordinator.
 * 
 * Integrates with:
 * - KeyHoldHandler: For unified key hold detection
 * - MoreKeysProvider: For unified more keys data source
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
    
    // Cached keyboards for quick switching
    private Keyboard unifiedInputKeyboard;
    private Keyboard arrowKeyboard;
    
    // Listener for external events
    private AuxBarEventListener eventListener;

    // Arrow bar cursor movement controller
    private ArrowBarController arrowBarController;
    
    // Key hold integration
    private MoreKeysProvider moreKeysProvider;
    private boolean keyHoldCallbackRegistered = false;
    private int currentAccentBarKeyCode = -1; // Track which key triggered the accent bar
    /**
     * What the accent bar covered when it came up, so dismissing it puts that back instead of
     * emptying the strip: the suggestions the user was reading, or the unified input menu.
     */
    private AuxBarState stateBeforeAccentBar = AuxBarState.NONE;
    
    // State tracking for key hold detection (mirrors original AccentBarController)
    private int lastKeyCode = -1;
    private long lastDownTime = 0;
    private int lastSymbolPageOrder = -1;
    private boolean accentBarTriggered = false; // True once accent bar has been shown for current key hold

    /**
     * "off" / "uppercase" / "symbol", refreshed once per physical key press. This used to be a
     * PreferenceManager.getDefaultSharedPreferences() lookup plus a getString() on EVERY
     * hardware KEY_DOWN including every ~50 ms auto-repeat of a held key.
     */
    private String holdActionMode = HOLD_ACTION_OFF;

    // ========== Hold-to-Auto-Commit State ==========
    
    /** Repeat count threshold for auto-commit. ~300ms after accent bar appears. */
    private static final int AUTO_COMMIT_REPEAT_THRESHOLD = 6;
    
    /** Hold-to-auto-commit state machine states */
    private enum HoldState {
        IDLE,
        KEY_PRESSED,
        HOLD_DETECTED,
        HOLD_WAITING,
        AUTO_COMMITTED
    }
    
    /** Hold action modes matching pref_pkb_hold_auto_commit values */
    public static final String HOLD_ACTION_OFF = "off";
    public static final String HOLD_ACTION_UPPERCASE = "uppercase";
    public static final String HOLD_ACTION_SYMBOL = "symbol";
    
    private HoldState holdState = HoldState.IDLE;
    private int holdRepeatCount = 0;
    private String holdBaseChar = null;       // The base character that was committed on keyDown
    private boolean autoCommitPerformed = false; // Guard: only auto-commit once per hold session
    private boolean holdSuppressingRepeats = false; // True when suppressing repeats for no-accent keys
    private boolean holdHasAccents = false;   // True if accent bar was shown for this hold session

    public interface AuxBarEventListener {
        void onSuggestionSelected(SuggestedWords.SuggestedWordInfo wordInfo, InputSource inputSource);
        void onSuggestionLongPressed(SuggestedWords.SuggestedWordInfo wordInfo);
        void onAutofillSelected(int position);
        void onHamburgerMenuClicked();
        void onExpandSuggestionsClicked();
        void onAuxBarStateChanged(AuxBarState oldState, AuxBarState newState);
        /** Get the main typing keyboard for MoreSuggestions panel layout */
        dev.bbkb.ime.keyboard.Keyboard getMainKeyboard();
        /** Called when an accent character is selected from the accent bar */
        void onAccentSelected(String accentChar);
        /** Whether the unified input menu is what this editor shows when the strip is not. */
        boolean shouldShowUim();
        /** Check if currently in symbol mode (symbol keyboard active) */
        boolean isInSymbolMode();
        /** Get moreKeys for a physical key by its scan code from the keyboard layout */
        String getMoreKeysForScanCode(int scanCode);
        /** Get the AuxCharacterResolver for alt character lookups (PKB hold feature) */
        dev.bbkb.ime.core.keyevent.AuxCharacterResolver getAuxCharacterResolver();
    }

    public AuxBarManager(@NonNull Context context) {
        this.context = context;
        // Create themed context for keyboard building
        this.themedContext = new ContextThemeWrapper(context, R.style.KeyboardTheme_LXX);
        // Create our own KeyboardBuilder with correct aux bar dimensions
        this.keyboardBuilder = createAuxBarKeyboardBuilder();
    }
    
    /**
     * Creates a KeyboardBuilder configured specifically for aux bar keyboards.
     * Sets the correct width and height for the aux bar strip.
     */
    private KeyboardBuilder createAuxBarKeyboardBuilder() {
        KeyboardBuilder.Builder aVar = new KeyboardBuilder.Builder(themedContext, null);
        aVar.setSubtype(SubtypeFactory.createSubtype(Locale.ENGLISH.toString(), "unified_input_menu"));
        android.content.res.Resources resources = themedContext.getResources();
        // Set keyboard dimensions to match aux bar size
        aVar.setKeyboardGeometry(ResourceConfigManager.getScreenWidthPixels(resources), 
                    ResourceConfigManager.getSuggestionsStripHeight(resources));
        return aVar.build();
    }

    /**
     * Initialize with the AuxBarView from the layout.
     */
    public void setAuxBarView(@NonNull AuxBarView view) {
        this.auxBarView = view;
        this.auxBarView.setStateChangeListener(this);
        
        UnifiedSuggestionView suggestionView = auxBarView.getSuggestionView();
        if (suggestionView != null) {
            suggestionView.setListener(this);
        }
        
        // Set up key event listener for accent bar key selection
        SimplifiedKeyboardView sharedKeyView = auxBarView.getSharedKeyView();
        if (sharedKeyView != null) {
            sharedKeyView.setOnKeyEventListener(new SimplifiedKeyboardView.onKeyEventListener() {
                @Override
                public void onKeyDown(dev.bbkb.ime.keyboard.Key key) {
                    // Key down - delegate to UnifiedInputBoardManager for UIM state
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
                        // Handle accent selection
                        if (released) {
                            onAccentKeySelected(key);
                        }
                    } else if (state == AuxBarState.UNIFIED_INPUT_MENU) {
                        // Delegate to UnifiedInputBoardManager for UIM key handling
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
                    // Long press - delegate to UnifiedInputBoardManager for UIM state
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
    
    /**
     * Handle accent key selection from the accent bar.
     */
    private void onAccentKeySelected(dev.bbkb.ime.keyboard.Key key) {
        int code = key.getCode();
        if (eventListener == null || code <= 0) {
            return;
        }
        String accentChar = new String(Character.toChars(code));
        // Cancel any pending auto-commit — user made a manual selection
        resetHoldState();
        // BlackBerryIME (the AuxBarEventListener) commits the character
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

    // ========== Suggestion Strip Methods ==========

    /**
     * Show the suggestion strip with the given words.
     * Automatically determines CJK mode based on current locale.
     * 
     * Note: LATIN/CJK suggestion modes are only available on PKB devices.
     * VKB devices use FlickSuggestionView for suggestions instead.
     * 
     * The suggestion strip will NOT be shown for fields that don't support suggestions
     * (password, URI, email, fields with TYPE_TEXT_FLAG_NO_SUGGESTIONS, etc.)
     */
    public void showSuggestionStrip(@NonNull SuggestedWords words) {
        if (auxBarView == null || fieldForbidsSuggestions()) {
            return;
        }

        // Show LATIN/CJK suggestions only when on-screen keyboard is NOT visible
        // When on-screen keyboard IS visible (VKB device OR PKB with "use on-screen keyboard" enabled),
        // FlickSuggestionView is used instead
        if (DeviceProfile.isOnScreenKeyboardVisible()) {
            return;
        }
        
        boolean isCJK = currentLocale != null && LocaleUtils.isChineseOrJapanese(currentLocale);
        auxBarView.showSuggestions(words, isCJK);
    }

    /**
     * Show the suggestion strip with explicit CJK mode.
     * 
     * Note: LATIN/CJK suggestion modes are only available on PKB devices.
     * VKB devices use FlickSuggestionView for suggestions instead.
     * 
     * The suggestion strip will NOT be shown for fields that don't support suggestions.
     */
    public void showSuggestionStrip(@NonNull SuggestedWords words, boolean isCJK) {
        if (auxBarView == null || fieldForbidsSuggestions()) {
            return;
        }

        // Only PKB devices should show LATIN/CJK suggestions in unified_suggestion_view
        // VKB devices use FlickSuggestionView for suggestions
        if (!DeviceProfile.current().isPkbDevice()) {
            return;
        }
        
        auxBarView.showSuggestions(words, isCJK);
    }

    /**
     * The field-capability gate shared by both showSuggestionStrip overloads, checked before
     * either overload's device gate. True when the focused field declines suggestions (password,
     * URI, email, TYPE_TEXT_FLAG_NO_SUGGESTIONS, ...); a Latin/CJK strip already up is hidden,
     * while autofill and the key bars are left alone.
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

    // ========== Autofill Methods ==========

    /**
     * Show autofill suggestions (Android 11+).
     */
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

    /**
     * Hide autofill bar and optionally restore previous state.
     */
    public void hideAutofillBar(boolean restoreSuggestionStrip) {
        if (BuildConfig.DEBUG) {
        Log.d(DIAG, "[AUXBAR] hideAutofillBar() called"
                + " | restoreSuggestions=" + restoreSuggestionStrip
                + " | currentState=" + (auxBarView != null ? auxBarView.getCurrentState() : "auxBarView=NULL"));
        }
        if (auxBarView == null) {
            return;
        }
        
        if (auxBarView.getCurrentState() == AuxBarState.AUTOFILL) {
            // Both paths currently just hide: there is no retained prior SuggestedWords to
            // re-show here, so "restore" is handled by the normal suggestion-update cycle that
            // follows. Collapsed the previously-identical if/else (FABLE_VIEW_REPORT §10) so the
            // dead branch can't be mistaken for differentiated behavior.
            auxBarView.hide();
            if (BuildConfig.DEBUG) Log.d(DIAG, "[AUXBAR] hideAutofillBar() executed - was in AUTOFILL state (restoreSuggestionStrip=" + restoreSuggestionStrip + ")");
        } else {
            if (BuildConfig.DEBUG) Log.d(DIAG, "[AUXBAR] hideAutofillBar() no-op - not in AUTOFILL state");
        }
    }

    // ========== Unified Input Menu Methods ==========

    /**
     * Show the Unified Input Menu bar with a specific keyboard.
     * This allows UnifiedInputBoardManager to provide its managed keyboard.
     */
    public void showUnifiedInputMenu(@NonNull Keyboard keyboard) {
        
        if (auxBarView == null) {
            return;
        }
        
        this.unifiedInputKeyboard = keyboard;
        auxBarView.showKeys(keyboard, AuxBarState.UNIFIED_INPUT_MENU);
    }

    /**
     * Show the Unified Input Menu bar.
     */
    public void showUnifiedInputMenu() {
        if (auxBarView == null) {
            return;
        }
        
        if (unifiedInputKeyboard == null) {
            // Route through UnifiedInputBoardManager so the user's menu order
            // (pref_uim_menu_order) is applied — a bar built here directly would
            // always show the default XML arrangement.
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
     * Hide the Unified Input Menu bar.
     */
    public void hideUnifiedInputMenu() {
        if (auxBarView != null && auxBarView.getCurrentState() == AuxBarState.UNIFIED_INPUT_MENU) {
            auxBarView.hide();
        }
    }
    
    /**
     * Get the SimplifiedKeyboardView that's currently displaying keys.
     * This is the sharedKeyView from AuxBarView.
     * Used by UnifiedInputBoardManager to tint the correct view.
     */
    public SimplifiedKeyboardView getDisplayedKeyView() {
        if (auxBarView != null) {
            return auxBarView.getSharedKeyView();
        }
        return null;
    }

    // ========== Arrow Bar Methods ==========

    /**
     * Show the arrow/cursor keys bar.
     */
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

    /**
     * Hide the arrow bar.
     */
    public void hideArrowBar() {
        // Dismiss the key view and restore the suggestion strip in one transition, the
        // same way the accent bar does. hide() is wrong here on two counts: it sets the
        // whole AuxBarView container GONE (the bar visibly blinks out before anything
        // brings it back), and it calls suggestionView.clear(), destroying suggestions
        // that are still valid — the arrow bar only ever covered them.
        if (auxBarView != null && auxBarView.getCurrentState() == AuxBarState.ARROW_BAR) {
            auxBarView.dismissKeyViewAndRestoreSuggestions();
        }
    }

    // ========== Accent Bar Methods ==========

    /**
     * Show the accent selection bar with the given accented characters.
     */
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
     * Dismiss the accent bar and put back what it covered. A plain {@code hide()} left the slot
     * empty (container GONE, suggestions cleared) after every accent pick, so the strip or the
     * unified input menu vanished until something else redrew it. The bar only ever covered
     * them: restore the suggestions it lay over, re-show the menu it replaced, and when nothing
     * was there (the window came up for this very key) show the menu if that is what this
     * editor uses, otherwise leave the slot for the next suggestion update to fill.
     */
    public void hideAccentBar() {
        currentAccentBarKeyCode = -1;
        if (auxBarView == null || auxBarView.getCurrentState() != AuxBarState.ACCENT_BAR) {
            return;
        }
        AuxBarState covered = stateBeforeAccentBar;
        stateBeforeAccentBar = AuxBarState.NONE;
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

    // ========== General Methods ==========

    /**
     * Hide all auxiliary bars.
     */
    public void hide() {
        if (auxBarView != null) {
            auxBarView.hide();
        }
    }

    /**
     * Update theme colors.
     */
    public void updateTheme() {
        if (auxBarView != null) {
            auxBarView.updateColors();
        }
        // Clear cached keyboards so they rebuild with new theme
        clearKeyboardCache();
    }

    /**
     * Clear cached keyboards.
     */
    public void clearKeyboardCache() {
        unifiedInputKeyboard = null;
        arrowKeyboard = null;
    }

    // ========== Keyboard Building ==========

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
            // Convert List<String> to List<CharSequence>
            List<CharSequence> labels = new ArrayList<>(accents);
            
            // Use KeyboardBuilder's static method for creating from labels
            int keyHeight = ResourceConfigManager.getSuggestionsStripHeight(context.getResources());
            return KeyboardBuilder.createFromLabels(context, labels, keyHeight);
        } catch (Exception e) {
            if (BuildConfig.DEBUG) android.util.Log.e(TAG, "Failed to build accent keyboard", e);
            return null;
        }
    }

    // ========== AuxBarView.StateChangeListener ==========

    @Override
    public void onAuxBarStateChanged(AuxBarState oldState, AuxBarState newState) {
        if (eventListener != null) {
            eventListener.onAuxBarStateChanged(oldState, newState);
        }
    }

    // ========== UnifiedSuggestionView.Listener ==========

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
    public void onExpandClicked() {
        if (eventListener != null) {
            eventListener.onExpandSuggestionsClicked();
        }
    }

    @Override
    public Keyboard onMoreSuggestionsRequested() {
        // Return the main typing keyboard for building MoreSuggestions panel
        if (eventListener != null) {
            return eventListener.getMainKeyboard();
        }
        return null;
    }
    
    // ========== MoreKeysProvider Integration ==========
    
    /**
     * Set the MoreKeysProvider for unified more keys data source.
     * This should be set during initialization with a CompositeMoreKeysProvider.
     */
    public void setMoreKeysProvider(MoreKeysProvider provider) {
        this.moreKeysProvider = provider;
    }
    
    /**
     * Process a key down event for accent bar handling and hold-to-auto-commit.
     * Returns true if the event should be consumed (no further processing).
     * 
     * Integrates the hold-to-auto-commit state machine:
     * IDLE → KEY_PRESSED → HOLD_DETECTED → HOLD_WAITING → AUTO_COMMITTED
     * 
     * @param event The key event
     * @param symbolPageOrder Current symbol page order from PhysicalKeyboardStateTracker
     * @return true if the event should be consumed, false otherwise
     */
    public boolean processKeyDown(android.view.KeyEvent event, int symbolPageOrder) {
        if (auxBarView == null) {
            return false;
        }
        
        int keyCode = event.getKeyCode();
        int repeatCount = event.getRepeatCount();
        long downTime = event.getDownTime();
        
        // === repeatCount == 0: New key press → reset state ===
        if (repeatCount == 0) {
            // Hide accent bar if a different key is pressed
            if (auxBarView.getCurrentState() == AuxBarState.ACCENT_BAR) {
                hideAccentBar();
                accentBarTriggered = false;
            }
            
            // Store state for this new key press
            lastKeyCode = keyCode;
            lastDownTime = downTime;
            lastSymbolPageOrder = symbolPageOrder;
            accentBarTriggered = false;
            
            // Reset hold-to-auto-commit state
            resetHoldState();
            holdState = HoldState.KEY_PRESSED;
            // The setting cannot meaningfully change during a single hold, so read it here
            // rather than on each repeat.
            this.holdActionMode = readHoldActionMode();
            
            // Capture the base character for potential auto-commit
            holdBaseChar = getBaseCharacterForEvent(event);
            
            return false; // Don't consume first press — base char committed by normal IME flow
        }
        
        // === AUTO_COMMITTED: Already auto-committed, consume all further repeats ===
        if (holdState == HoldState.AUTO_COMMITTED) {
            return true;
        }
        
        // === Different key or session mismatch — don't consume ===
        if (keyCode != lastKeyCode || downTime != lastDownTime) {
            resetHoldState();
            return false;
        }
        
        String holdAction = this.holdActionMode;
        boolean holdFeatureEnabled = !HOLD_ACTION_OFF.equals(holdAction);
        boolean isEligibleKey = isLetterKey(keyCode);
        // The one query API rather than two raw KeyEvent predicates. ModifierState.ofEvent carries
        // only what the event says, which is exactly what these two read — the hold action is
        // decided from the event that is repeating, not from the tracker's sticky/locked state.
        ModifierState eventModifiers = ModifierState.ofEvent(event);
        boolean shiftActive = eventModifiers.isShiftHeld();
        boolean altActive = eventModifiers.isAltHeld();
        
        // Skip auto-commit when shift (already uppercase) or alt (already symbol) is active
        boolean skipAutoCommit = !holdFeatureEnabled || !isEligibleKey
                || (HOLD_ACTION_UPPERCASE.equals(holdAction) && shiftActive)
                || (HOLD_ACTION_SYMBOL.equals(holdAction) && altActive);
        
        // === repeatCount == 1: Try accent bar, enter HOLD_DETECTED ===
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
                holdSuppressingRepeats = !accentBarShown; // Suppress repeats for no-accent keys
            }
            
            // Consume if accent bar shown OR if suppressing repeats for hold feature
            if (accentBarShown) return true;
            if (!skipAutoCommit && holdSuppressingRepeats) return true;
            return false;
        }
        
        // === repeatCount 2..N: Consume and track toward threshold ===
        
        // If accent bar is showing for this key, always consume
        boolean accentBarActive = auxBarView.getCurrentState() == AuxBarState.ACCENT_BAR 
                && currentAccentBarKeyCode == keyCode;
        if (accentBarActive || (accentBarTriggered && !skipAutoCommit)) {
            holdRepeatCount = repeatCount;
            
            if (!skipAutoCommit && holdState == HoldState.HOLD_DETECTED) {
                holdState = HoldState.HOLD_WAITING;
            }
            
            // Check if threshold reached (3x threshold for keys with accents)
            int effectiveThreshold = holdHasAccents 
                    ? AUTO_COMMIT_REPEAT_THRESHOLD * 3 
                    : AUTO_COMMIT_REPEAT_THRESHOLD;
            if (!skipAutoCommit && holdState == HoldState.HOLD_WAITING 
                    && repeatCount >= effectiveThreshold && !autoCommitPerformed) {
                performAutoCommit(keyCode, holdAction);
            }
            
            return true;
        }
        
        // Hold feature: suppress repeats for no-accent keys
        if (!skipAutoCommit && holdSuppressingRepeats) {
            holdRepeatCount = repeatCount;
            
            if (holdState == HoldState.HOLD_DETECTED) {
                holdState = HoldState.HOLD_WAITING;
            }
            
            // Check if threshold reached (no accents → use base threshold)
            if (holdState == HoldState.HOLD_WAITING 
                    && repeatCount >= AUTO_COMMIT_REPEAT_THRESHOLD && !autoCommitPerformed) {
                performAutoCommit(keyCode, holdAction);
            }
            
            return true;
        }
        
        // No accent bar, no hold feature — let normal repeat through
        return false;
    }
    
    /**
     * Notify that the key was released. Resets hold-to-auto-commit state.
     * Call this from the key up handler in the IME.
     */
    public void processKeyUp(android.view.KeyEvent event) {
        if (holdState != HoldState.IDLE) {
            resetHoldState();
        }
    }
    
    /**
     * Reset all hold-to-auto-commit state.
     */
    private void resetHoldState() {
        holdState = HoldState.IDLE;
        holdRepeatCount = 0;
        holdBaseChar = null;
        autoCommitPerformed = false;
        holdSuppressingRepeats = false;
        holdHasAccents = false;
    }
    
    /**
     * Read the hold action mode from preferences.
     * @return "off", "uppercase", or "symbol"
     */
    private String readHoldActionMode() {
        String mode = PrefsManager.INSTANCE.getPrefs(this.context)
                .getString("pref_pkb_hold_auto_commit", HOLD_ACTION_OFF);
        return mode == null ? HOLD_ACTION_OFF : mode;
    }
    
    /**
     * Check if a keyCode corresponds to a letter key (A-Z, keyCodes 29-54).
     */
    private static boolean isLetterKey(int keyCode) {
        return keyCode >= android.view.KeyEvent.KEYCODE_A 
            && keyCode <= android.view.KeyEvent.KEYCODE_Z;
    }
    
    /**
     * Perform the auto-commit: resolve the target character and commit it.
     */
    private void performAutoCommit(int keyCode, String holdAction) {
        if (autoCommitPerformed || eventListener == null) return;
        
        String resolved = resolveAutoCommitChar(keyCode, holdAction);
        if (resolved == null || resolved.isEmpty()) {
            return;
        }
        
        autoCommitPerformed = true;
        holdState = HoldState.AUTO_COMMITTED;
        
        // Commit via the same path as accent selection (replaces last char)
        eventListener.onAccentSelected(resolved);
        
        // Same dismissal as a manual pick: restore whatever the bar covered.
        hideAccentBar();
    }
    
    /**
     * Resolve the character to auto-commit based on mode.
     * - Uppercase: locale-aware toUpperCase of the base character
     * - Symbol: AuxCharacterResolver lookup
     */
    @Nullable
    private String resolveAutoCommitChar(int keyCode, String holdAction) {
        if (HOLD_ACTION_UPPERCASE.equals(holdAction)) {
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
                        return String.valueOf(result.character);
                    }
                }
            }
            return null;
        }
        return null;
    }
    
    /**
     * Try to show the accent bar for the given key event.
     * Returns true if accent bar was shown.
     * 
     * Priority order (mirroring original smali logic):
     * 1. ScanCode-based Key lookup from keyboard layout (for symbol mode keys)
     * 2. Fall back to MoreKeysProvider (character-based accents, style more keys)
     */
    private boolean tryShowAccentBar(android.view.KeyEvent event, int symbolPageOrder) {
        int keyCode = event.getKeyCode();
        int scanCode = event.getScanCode();
        List<String> moreKeys = null;
        boolean inSymbolMode = symbolPageOrder == 1 || symbolPageOrder == 3;
        
        // Priority 1: Try scanCode-based lookup from keyboard layout
        // This works for symbol keyboard keys that have moreKeys defined in XML
        if (eventListener != null) {
            String moreKeysSpec = eventListener.getMoreKeysForScanCode(scanCode);
            if (moreKeysSpec != null && !moreKeysSpec.isEmpty()) {
                moreKeys = parseMoreKeysSpec(moreKeysSpec);
            }
        }
        
        // Priority 2: Fall back to MoreKeysProvider (for accents, style more keys)
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
            showAccentBar(moreKeys);
            return true;
        }
        
        return false;
    }
    
    /**
     * Parse a moreKeys spec string into a list of individual keys.
     * The moreKeys format is character-by-character (e.g., "–—-" = 3 keys: –, —, -)
     * NOT comma-separated like some other keyboard formats.
     */
    private List<String> parseMoreKeysSpec(String moreKeysSpec) {
        List<String> result = new ArrayList<>();
        if (moreKeysSpec == null || moreKeysSpec.isEmpty()) {
            return result;
        }
        
        // Parse character by character, handling surrogate pairs for emoji/special chars
        int i = 0;
        while (i < moreKeysSpec.length()) {
            int codePoint = moreKeysSpec.codePointAt(i);
            String character = new String(Character.toChars(codePoint));
            result.add(character);
            i += Character.charCount(codePoint);
        }
        
        return result;
    }
    
    /**
     * Register this manager as a KeyHoldHandler callback.
     * Call this after the manager is fully initialized.
     */
    public void registerKeyHoldCallback() {
        if (!keyHoldCallbackRegistered) {
            KeyHoldHandler.getInstance().addCallback(this);
            keyHoldCallbackRegistered = true;
        }
    }
    
    // ========== KeyHoldHandler.KeyHoldCallback Implementation ==========
    
    @Override
    public void onKeyHoldStart(android.view.KeyEvent event, int repeatCount) {
        if (moreKeysProvider == null || auxBarView == null) {
            return;
        }
        
        int keyCode = event.getKeyCode();
        
        // If accent bar is showing for a different key, hide it first
        if (auxBarView.getCurrentState() == AuxBarState.ACCENT_BAR && 
            currentAccentBarKeyCode != -1 && currentAccentBarKeyCode != keyCode) {
            hideAccentBar();
        }
        
        // Build context for more keys lookup
        String baseChar = getBaseCharacterForEvent(event);
        if (baseChar == null || baseChar.isEmpty()) {
            return;
        }
        
        MoreKeysProvider.MoreKeysContext context = new MoreKeysProvider.MoreKeysContext(
                baseChar, ModifierState.ofEvent(event), true,
                eventListener != null && eventListener.isInSymbolMode());
        
        // Get more keys from unified provider
        List<String> moreKeys = moreKeysProvider.getMoreKeys(context);
        
        if (moreKeys != null && !moreKeys.isEmpty()) {
            currentAccentBarKeyCode = keyCode;
            showAccentBar(moreKeys);
        }
    }
    
    @Override
    public void onKeyHoldContinue(android.view.KeyEvent event, int repeatCount) {
        // Accent bar is already showing, nothing to do
    }
    
    @Override
    public void onKeyHoldEnd(android.view.KeyEvent event) {
        // Don't immediately hide accent bar on key release - user needs time to select an accent
        // The accent bar will be hidden when:
        // 1. User selects an accent (via touch or keyboard navigation)
        // 2. User presses a different key
        // 3. User presses Escape or Back
        // This matches the original AccentBarController behavior
    }
    
    /**
     * Get the base character for a key event.
     * Used to look up more keys for the character.
     * 
     * For Alt+key combinations, we need the base character (without Alt modifier)
     * so StyleMoreKeysProvider can look up the correct more keys.
     */
    private String getBaseCharacterForEvent(android.view.KeyEvent event) {
        int metaState = event.getMetaState();
        
        // For Alt+key combinations, strip Alt from meta state to get the base character
        // This allows StyleMoreKeysProvider to look up more keys for the base key
        if ((metaState & android.view.KeyEvent.META_ALT_MASK) != 0) {
            int baseMetaState = metaState & ~android.view.KeyEvent.META_ALT_MASK;
            int unicodeChar = event.getUnicodeChar(baseMetaState);
            if (unicodeChar > 0 && Character.isValidCodePoint(unicodeChar)) {
                return new String(Character.toChars(unicodeChar));
            }
        }
        
        // Normal case - use full meta state
        int unicodeChar = event.getUnicodeChar(metaState);
        if (unicodeChar > 0 && Character.isValidCodePoint(unicodeChar)) {
            return new String(Character.toChars(unicodeChar));
        }
        return null;
    }
}

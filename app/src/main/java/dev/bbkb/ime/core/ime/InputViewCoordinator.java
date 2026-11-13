package dev.bbkb.ime.core.ime;

import android.content.res.Resources;
import android.view.View;
import android.view.inputmethod.InputMethodSubtype;

import dev.bbkb.ime.core.settings.util.SettingsManager;
import dev.bbkb.ime.core.settings.util.SettingsValues;
import dev.bbkb.ime.core.suggestion.SuggestedWords;
import dev.bbkb.ime.keyboard.auxbar.AuxBarManager;
import dev.bbkb.ime.keyboard.auxbar.suggestions.CJKSuggestionGridView;
import dev.bbkb.ime.keyboard.auxbar.suggestions.FlickSuggestionView;
import dev.bbkb.ime.core.textinput.InputMethodHelper;
import dev.bbkb.ime.core.device.profile.DeviceProfile;
import dev.bbkb.ime.core.locale.LocaleUtils;
import dev.bbkb.ime.core.locale.ResourceLocaleUtils;
import dev.bbkb.ime.keyboard.KeyboardSwitcher;
import dev.bbkb.ime.keyboard.auxbar.ArrowBarController;
import dev.bbkb.ime.keyboard.inputboard.clipboard.ClipboardController;
import dev.bbkb.ime.keyboard.inputboard.fcc.FccController;
import dev.bbkb.ime.keyboard.inputboard.numberpad.NumberPadController;
import dev.bbkb.ime.keyboard.inputboard.UnifiedInputBoardManager;
import dev.bbkb.ime.keyboard.inputboard.voice.VoiceInputController;
import dev.bbkb.ime.core.locale.SubtypeManager;
import dev.bbkb.ime.core.BlackBerryIME;
import dev.bbkb.ime.R;
import dev.bbkb.ime.keyboard.internal.MoreKeysProvider;
import android.util.Log;
import dev.bbkb.ime.keyboard.MainKeyboardView;
import dev.bbkb.ime.keyboard.internal.TextDecoratorUi;
import dev.bbkb.ime.keyboard.auxbar.AuxBarView;
import dev.bbkb.ime.keyboard.auxbar.autofill.InlineAutofillManager;
import dev.bbkb.ime.keyboard.slideboard.SlideboardManager;
import dev.bbkb.ime.keyboard.state.AuxBarChoice;
import dev.bbkb.ime.BuildConfig;



/**
 * Builds the input view ({@link #setupInputView(View)}) and implements the callbacks those views
 * fire, including the aux bar's menu/strip choice ({@link AuxBarChoice}).
 */
public class InputViewCoordinator implements KeyboardSwitcher.SwitcherCallbacks, ClipboardController.Listener, FccController.Listener, VoiceInputController.Listener, NumberPadController.Listener {

    private static final String TAG = "UICoordinator";

    /** Separate tag for setup and ARROW_BAR_DIAG log lines. */
    private static final String SETUP_TAG = "UISetupCoord";

    private BlackBerryIME imeService;

    private SettingsManager settingsManager;

    private KeyboardSwitcher keyboardSwitcher;

    private SubtypeManager subtypeManager;

    private CJKSuggestionGridView cjkSuggestionGridView;
    private FlickSuggestionView flickSuggestionView;

    /** Whether the user raised the menu (hamburger or show-menu key) and has not put it away: what a board close returns to. */
    private final AuxBarChoice barChoice = new AuxBarChoice();


    private ArrowBarController arrowBarController;

    private boolean isDisengaging = false;
    
    private AuxBarManager auxBarManager;

    public InputViewCoordinator(BlackBerryIME blackBerryIME) {
        this.imeService = blackBerryIME;
    }

    public void initialize() {
        this.settingsManager = SettingsManager.getInstance();
        this.keyboardSwitcher = this.imeService.getKeyboardSwitcher();
        this.subtypeManager = SubtypeManager.getInstance();
        this.cjkSuggestionGridView = this.imeService.getCjkSuggestionGridView();
        this.flickSuggestionView = this.imeService.getFlickSuggestionView();
        this.arrowBarController = this.imeService.getArrowBarController();
    }

    private CJKSuggestionGridView getCjkSuggestionGrid() {
        if (this.cjkSuggestionGridView == null) {
            this.cjkSuggestionGridView = this.imeService.getCjkSuggestionGridView();
        }
        return this.cjkSuggestionGridView;
    }

    private FlickSuggestionView getFlickSuggestionView() {
        if (this.flickSuggestionView == null) {
            this.flickSuggestionView = this.imeService.getFlickSuggestionView();
        }
        return this.flickSuggestionView;
    }

    public void onStartInputView() {
        this.isDisengaging = false;
        
        UnifiedInputBoardManager uibm = this.keyboardSwitcher.getUnifiedInputBoardManager();
        if (uibm != null) {
            if (InputMethodHelper.isDeviceLocked()) {
                uibm.hide();
            } else if (shouldShowUim()) {
                uibm.show(false);
            } else if (uibm.isShowing() && !uibm.isPanelBoardShowing()) {
                // A menu left from the last field: suggestions do not take it down, so the field
                // start does. An open board keeps it.
                uibm.hide();
            }
        }
        
        if (DeviceProfile.current().isPkbDevice()) {
            preShowSuggestionStripForPkb();
        }
    }
    
    /**
     * Shows an empty suggestion strip early on physical-keyboard devices, when predictions are on,
     * the field takes suggestions, the bar is not hidden (CJK strips still show) and the show-menu
     * key has not raised the menu (the window coming up for that key press starts after it).
     */
    private void preShowSuggestionStripForPkb() {
        if (SettingsValues.isInputMenuRequestedByKey()) {
            return;
        }
        SettingsValues settings = SettingsManager.getInstance().getSettingsValues();
        if (settings.isPkbSuggestionBarHidden()) {
            // AuxBarView starts VISIBLE and empty, and nothing else hides it when no strip or menu is coming.
            if (auxBarManager != null) {
                auxBarManager.hideSuggestionBar();
            }
            if (!isChineseOrJapaneseSubtype()) {
                return;
            }
        }
        // A Chinese or Japanese keyboard's candidate strip does not answer to "Show suggestions".
        if ((settings.isPredictionsEnabled || isChineseOrJapaneseSubtype())
                && settings.editorCapabilities.shouldShowSuggestions) {
            if (auxBarManager != null) {
                auxBarManager.showSuggestionStrip(SuggestedWords.EMPTY);
            }
        }
    }

    public void hideAllInputUi() {
        this.isDisengaging = true;
        
        if (this.keyboardSwitcher == null) {
            return;
        }

        UnifiedInputBoardManager uibm = this.keyboardSwitcher.getUnifiedInputBoardManager();
        if (uibm != null) {
            uibm.hide();
        }

        if (auxBarManager != null) {
            auxBarManager.hide();
        }
        
        if (this.flickSuggestionView != null) {
            this.flickSuggestionView.setVisibility(View.GONE);
        }
    }

    public void hideUnifiedInputBoard() {
        UnifiedInputBoardManager uibm = this.keyboardSwitcher.getUnifiedInputBoardManager();
        if (uibm != null) {
            uibm.hide();
        }
    }

    public void showUnifiedInputMenuFromSuggestionStrip() {
        UnifiedInputBoardManager uibm = this.keyboardSwitcher.getUnifiedInputBoardManager();
        if (uibm != null) {
            uibm.show(true);
            if (uibm.isShowing()) {
                this.barChoice.chooseMenu();
            }
        }
    }

    public AuxBarChoice getBarChoice() {
        return this.barChoice;
    }

    /** Any {@code UnifiedInputBoardManager.hide()} or a new field: back to the field's own bar. */
    public void onMenuTakenDown() {
        this.barChoice.chooseFieldBar();
    }

    /**
     * The field's default bar: the menu when there is no strip and the menu is enabled; the strip
     * when there is one and the device has a physical keyboard ({@code AuxBarManager} draws strips
     * only there); otherwise nothing.
     */
    public AuxBarChoice.FieldBar fieldBar() {
        SettingsValues settings = SettingsManager.getInstance().getSettingsValues();
        boolean strip = shouldShowSuggestionStrip(settings, this.imeService.isOnScreenKeyboardVisible(),
                this.subtypeManager.getCurrentSubtype());
        if (!strip) {
            return settings.isUimEnabledBySetting() ? AuxBarChoice.FieldBar.MENU : AuxBarChoice.FieldBar.NONE;
        }
        return DeviceProfile.current().isPkbDevice() ? AuxBarChoice.FieldBar.STRIP : AuxBarChoice.FieldBar.NONE;
    }

    /**
     * Posted by {@code UnifiedInputBoardManager} after the last board closes: returns the bar to the
     * user's choice. Re-checks nothing opened since (board to board switch); a chosen menu stays.
     */
    public void uncoverAfterBoardsClosed() {
        UnifiedInputBoardManager uibm = this.keyboardSwitcher.getUnifiedInputBoardManager();
        if (uibm == null || !uibm.isShowing() || uibm.isPanelBoardShowing()
                || uibm.getBoardCoordinator().activeBoard() != dev.bbkb.ime.keyboard.inputboard.UnifiedBoardCoordinator.NO_BOARD) {
            return;
        }
        if (this.barChoice.isMenuChosen()) {
            return;
        }
        showFieldBarInPlaceOfMenu(uibm);
    }

    /** Replaces an unheld menu with the field's own bar; ends the show-menu key's override. */
    private void showFieldBarInPlaceOfMenu(UnifiedInputBoardManager uibm) {
        SettingsValues.setInputMenuRequestedByKey(false);
        switch (fieldBar()) {
            case STRIP:
                uibm.lowerMenuOntoStrip();
                break;
            case NONE:
                uibm.hide();
                break;
            case MENU:
            default:
                break;
        }
    }

    /**
     * Typing (and only typing) puts a raised menu away and brings back the field's bar, whose words
     * stayed current. Runs per edit, so the nothing-chosen case returns at once. On-screen input
     * while a board is open comes from the board and is not typing; physical typing then defers the
     * switch to the board's close.
     *
     * @param physical true for a physical key or keypad swipe
     */
    public void onUserTyped(boolean physical) {
        if (!this.barChoice.isMenuChosen() && !SettingsValues.isInputMenuRequestedByKey()) {
            return;
        }
        UnifiedInputBoardManager uibm = this.keyboardSwitcher.getUnifiedInputBoardManager();
        if (uibm == null) {
            return;
        }
        boolean boardOpen = uibm.isPanelBoardShowing();
        if (boardOpen && !physical) {
            return;
        }
        this.barChoice.chooseFieldBar();
        if (boardOpen || !uibm.isShowing()) {
            return;
        }
        showFieldBarInPlaceOfMenu(uibm);
    }

    /**
     * The menu's keyboard key: back to the field's own bar. A strip returns with its words as they
     * are (they stayed current); do not re-request them, the request often comes back empty and
     * blanks the strip. Otherwise {@code restoreSuggestionStrip} refreshes the on-screen words.
     */
    public void putMenuAway() {
        this.barChoice.chooseFieldBar();
        SettingsValues.setInputMenuRequestedByKey(false);
        UnifiedInputBoardManager uibm = this.keyboardSwitcher.getUnifiedInputBoardManager();
        if (uibm != null && uibm.isShowing() && fieldBar() == AuxBarChoice.FieldBar.STRIP) {
            uibm.lowerMenuOntoStrip();
            showCjkSuggestionStripIfNeeded();
            return;
        }
        this.imeService.restoreSuggestionStrip(true);
    }

    public void onAuxBarHeightChanged() {
        // AuxBarView positions itself.
    }

    public void hideAllAuxBars() {
        UnifiedInputBoardManager uibm = this.keyboardSwitcher.getUnifiedInputBoardManager();
        if (uibm != null) {
            uibm.hide();
            uibm.setTranslation(0, 0);
        }
        if (auxBarManager != null) {
            auxBarManager.hide();
        }
    }

    public AuxBarManager getAuxBarManager() {
        return auxBarManager;
    }

    @Override
    public void onFccPanelShown() {
        // The UIM stays visible above the FCC panel; only the flick view would overlap it.

        if (this.flickSuggestionView != null) {
            this.flickSuggestionView.setVisibility(View.GONE);
        }
        
        this.imeService.applyCursorModeState(true, true, true);
        this.imeService.getInputLogic().cancelComposingAndTouchEvent();
    }

    @Override
    public void onFccPanelHidden() {
        closeUimComponent();
        setFlickSuggestionVisibility(0);
    }

    @Override
    public void onVoicePanelShown() {
        // Always run the keyboard state chain: it initializes keyboard state and expands
        // keyboard_frame. VoiceInputView.show() brings itself to front over mainKeyboardView.
        this.keyboardSwitcher.onUnifiedInputBoardAction(0);
        setFlickSuggestionVisibility(4);
    }

    @Override
    public void onVoicePanelHidden() {
        this.keyboardSwitcher.onUnifiedInputBoardAction(8);
        setFlickSuggestionVisibility(0);
    }

    @Override
    public void onClipboardShown() {
        this.keyboardSwitcher.requestShiftOff();
        setFlickSuggestionVisibility(4);
    }

    @Override
    public void onClipboardHidden() {
        closeUimComponent();
        setFlickSuggestionVisibility(0);
    }

    @Override // dev.bbkb.ime.keyboard.inputboard.numberpad.NumberPadController.Listener
    public void onNumberPadShown() {
        this.keyboardSwitcher.requestShiftOff();
        setFlickSuggestionVisibility(4);
    }

    @Override // dev.bbkb.ime.keyboard.inputboard.numberpad.NumberPadController.Listener
    public void onNumberPadHidden() {
        closeUimComponent();
        setFlickSuggestionVisibility(0);
    }

    @Override
    public void showKeyboardMenu() throws Resources.NotFoundException {
        if (this.isDisengaging) {
            return;
        }
        
        UnifiedInputBoardManager uimManager = this.keyboardSwitcher.getUnifiedInputBoardManager();

        // uimManager is null until KeyboardSwitcher builds it, and the -23 key or hamburger tap
        // can fire before input-view recreation; an NPE here kills the IME.
        if (auxBarManager != null && uimManager != null) {
            boolean auxBarShowing = auxBarManager.isShowing();
            if (auxBarShowing && this.imeService.isUimEnabled()) {
                // Show over the bar without taking it down first: the strip keeps its words.
                uimManager.show(true);
                if (uimManager.isShowing()) {
                    this.barChoice.chooseMenu();
                }
                return;
            }
            if (uimManager.isShowing()) {
                showSuggestionStripOrUim();
                if (auxBarShowing) {
                    return;
                }
                uimManager.hideView();
                return;
            }
            if (this.imeService.isUimEnabled()) {
                uimManager.showView();
            }
        }
    }

    @Override
    public void onKeyboardLayoutChanged() {
        if (!shouldShowUim() && this.imeService.hasFccController() && !this.imeService.getFccController().isShowing()) {
            this.imeService.getUiCoordinator().hideUnifiedInputBoard();
        }
        this.imeService.refreshSuggestionStripVisibility();
    }

    /** Closes the UIM's board; the UIM is null until {@code KeyboardSwitcher} builds it. */
    private void closeUimComponent() {
        UnifiedInputBoardManager uim = this.keyboardSwitcher.getUnifiedInputBoardManager();
        if (uim != null) {
            uim.closeActiveComponent();
        }
    }

    /** @param visibility the {@link View} visibility to set */
    private void setFlickSuggestionVisibility(int visibility) {
        FlickSuggestionView view = getFlickSuggestionView();
        if (view != null) {
            view.setVisibility(visibility);
        }
    }

    public boolean isUimEnabled() {
        return this.settingsManager.getSettingsValues().isUimEnabled();
    }

    public boolean shouldShowUim() {
        // The show-menu key or the hamburger raised the menu: it stays the bar until put away
        // (the arrow bar, accent bar and language chips hand back to it).
        if (SettingsValues.isInputMenuRequestedByKey()) {
            return true;
        }
        if (this.barChoice.isMenuChosen()) {
            return true;
        }
        SettingsValues c0804dM5050c = SettingsManager.getInstance().getSettingsValues();
        return c0804dM5050c.isUimEnabled() && !shouldShowSuggestionStrip(c0804dM5050c, this.imeService.isOnScreenKeyboardVisible(), this.subtypeManager.getCurrentSubtype());
    }

    /**
     * "Show or hide the input menu" (multifunction key, Alt+Sym). Showing works even when settings
     * keep the menu off, as a session override ({@link SettingsValues#setInputMenuRequestedByKey})
     * under which the bar behaves as a normal menu bar. Hiding closes any board under it and
     * restores the field's bar (strip or nothing); a settings-default menu also goes until the next
     * field or press.
     */
    public void toggleInputMenu() {
        UnifiedInputBoardManager uibm = this.keyboardSwitcher.getUnifiedInputBoardManager();
        if (uibm == null) {
            return;
        }
        if (uibm.isShowing()) {
            SettingsValues.setInputMenuRequestedByKey(false);
            this.barChoice.chooseFieldBar();
            if (fieldBar() == AuxBarChoice.FieldBar.STRIP) {
                // Uncover the strip, whose words stayed current behind the menu: nothing to re-request.
                uibm.hideAllComponents();
                uibm.lowerMenuOntoStrip();
                return;
            }
            uibm.hide();
            if (DeviceProfile.current().isPkbDevice()) {
                preShowSuggestionStripForPkb();
            }
            // Refill an empty strip with words for the cursor position, as when cursor mode ends.
            if (this.imeService.isInputActive() && auxBarManager != null && auxBarManager.isShowing()) {
                this.imeService.getUiUpdateHandler().postUpdateShiftState(true, true);
            }
            return;
        }
        SettingsValues.setInputMenuRequestedByKey(true);
        this.barChoice.chooseMenu();
        if (!uibm.showMenuForKey()) {
            // No bar view yet: the settings keep their say.
            SettingsValues.setInputMenuRequestedByKey(false);
            this.barChoice.chooseFieldBar();
        }
    }

    /** @param settingsValues the current settings */
    private boolean shouldShowPredictionsInStrip(SettingsValues settingsValues) {
        return settingsValues.shouldShowPredictionsInCandidateStrip();
    }

    /**
     * The main strip: never for Chinese or Japanese.
     *
     * @param settingsValues the current settings
     * @param isOnScreenKeyboardVisible whether the on-screen keyboard is visible
     */
    public boolean shouldShowLatinSuggestionStrip(SettingsValues settingsValues, boolean isOnScreenKeyboardVisible, InputMethodSubtype inputMethodSubtype) {
        if (LocaleUtils.isCurrentSubtypeChinese() || LocaleUtils.isCurrentSubtypeJapanese()) {
            return false;
        }
        return shouldShowSuggestionStrip(settingsValues, isOnScreenKeyboardVisible, inputMethodSubtype);
    }

    /**
     * The CJK candidate strip: only for Chinese or Japanese.
     *
     * @param settingsValues the current settings
     * @param isOnScreenKeyboardVisible whether the on-screen keyboard is visible
     */
    public boolean shouldShowCjkSuggestionStrip(SettingsValues settingsValues, boolean isOnScreenKeyboardVisible, InputMethodSubtype inputMethodSubtype) {
        if (LocaleUtils.isCurrentSubtypeChinese() || LocaleUtils.isCurrentSubtypeJapanese()) {
            return shouldShowSuggestionStrip(settingsValues, isOnScreenKeyboardVisible, inputMethodSubtype);
        }
        return false;
    }

    /**
     * Hot path (every start input view and suggestion delivery): keep the checks short-circuiting.
     *
     * @param settingsValues the current settings
     * @param isOnScreenKeyboardVisible whether the on-screen keyboard is visible
     */
    public boolean shouldShowSuggestionStrip(SettingsValues settingsValues, boolean isOnScreenKeyboardVisible, InputMethodSubtype inputMethodSubtype) {
        if (settingsValues.editorCapabilities.isPassword) {
            return false;
        }
        // "Show the suggestion bar" off: CJK keyboards still need their candidate strip.
        if (settingsValues.isPkbSuggestionBarHidden() && !isChineseOrJapaneseSubtype()) {
            return false;
        }
        if (!shouldShowPredictionsInStrip(settingsValues) && !settingsValues.shouldShowMoreKeys()) {
            return false;
        }
        if (!hasAuxBarManager()) {
            return false;
        }
        ArrowBarController arrowBar = this.arrowBarController;
        if (arrowBar != null && arrowBar.isShowing()) {
            return false;
        }
        // The flick-suggestion view owns predictions instead of the strip when all of these hold.
        boolean flickViewOwnsPredictions = hasFlickSuggestionView()
                && ResourceLocaleUtils.isNonCjkLanguage(inputMethodSubtype)
                && settingsValues.isPredictionsEnabled
                && settingsValues.isOnKeyPredictionsEnabled
                && isOnScreenKeyboardVisible
                && !settingsValues.isVkbGestureInputEnabledForLocale();
        return !flickViewOwnsPredictions;
    }

    public void updateSuggestionStripVisibility() {
        // Never set the aux-bar container visible from here: AuxBarView owns that together with
        // its children and state; a bare VISIBLE container is an empty band that eats taps.
        if (hasCjkSuggestionGrid()) {
            this.cjkSuggestionGridView.setVisible(false);
        }
    }

    public void hideSuggestionViews() {
        if (auxBarManager != null) {
            auxBarManager.hide();
        }
        if (hasFlickSuggestionView()) {
            this.flickSuggestionView.clear();
        }
        if (hasCjkSuggestionGrid()) {
            this.cjkSuggestionGridView.setVisible(false);
        }
    }

    /** Only hides the CJK grid; AuxBarManager owns the strips. */
    public void showCjkSuggestionStripIfNeeded() throws Resources.NotFoundException {
        if (hasCjkSuggestionGrid()) {
            this.cjkSuggestionGridView.setVisible(false);
        }
    }

    public void showSuggestionStripOrUim() throws Resources.NotFoundException {
        UnifiedInputBoardManager uibm = this.keyboardSwitcher.getUnifiedInputBoardManager();
        if (shouldShowUim() && uibm != null) {
            uibm.show(false);
            return;
        }
        showCjkSuggestionStripIfNeeded();
    }

    private static boolean isChineseOrJapaneseSubtype() {
        return LocaleUtils.isCurrentSubtypeChinese() || LocaleUtils.isCurrentSubtypeJapanese();
    }

    private boolean hasFlickSuggestionView() {
        return this.flickSuggestionView != null;
    }

    private boolean hasCjkSuggestionGrid() {
        return this.cjkSuggestionGridView != null;
    }

    private boolean hasAuxBarManager() {
        return this.auxBarManager != null;
    }

    public void setAuxBarManager(dev.bbkb.ime.keyboard.auxbar.AuxBarManager manager) {
        this.auxBarManager = manager;
    }

    /** Wires every UI component of a new input view; called from {@code BlackBerryIME.setInputView()}. */
    public void setupInputView(View view) {
        this.imeService.setRootInputView(view);
        SwipeToDeleteAnimatorView deleteAnimationView =
                (SwipeToDeleteAnimatorView) view.findViewById(R.id.delete_animation_view);
        // The overlay's white icon is not tinted by KeyboardView.applyIconTint; tint it here
        // (a theme change recreates the input view, so this stays current).
        dev.bbkb.ime.keyboard.KeyboardColorManager.INSTANCE.tint(deleteAnimationView);
        this.imeService.setSwipeToDeleteAnimatorView(deleteAnimationView);

        initializeAuxBar(view);
        initializeCjkViews(view);
        initializeBoardControllers(view);
        initializeFlickSuggestions(view);


        this.imeService.getInputLogic().setTimerProxy(new TextDecoratorUi(this.imeService, view));
        ArrowBarController arrowBarController = new ArrowBarController(
                new ImeResourcesContext(this.imeService), this.imeService, view);
        AuxBarManager abmForArrowBar = this.imeService.getAuxBarManager();
        if (BuildConfig.DEBUG) Log.d(SETUP_TAG, "ARROW_BAR_DIAG setupInputView: abmForArrowBar=" + (abmForArrowBar != null ? "NON-NULL" : "NULL"));
        if (abmForArrowBar != null) {
            arrowBarController.setAuxBarManager(abmForArrowBar);
            abmForArrowBar.setArrowBarController(arrowBarController);
            if (BuildConfig.DEBUG) Log.d(SETUP_TAG, "ARROW_BAR_DIAG wiring complete: setAuxBarManager+setArrowBarController called");
        } else {
            if (BuildConfig.DEBUG) Log.e(SETUP_TAG, "ARROW_BAR_DIAG ERROR: AuxBarManager is null — ArrowBarController not wired!");
        }
        this.imeService.setArrowBarController(arrowBarController);
        initialize();

        initializeMoreKeysProvider();
        initializeInlineAutofill();
    }

    private void initializeAuxBar(View view) {
        AuxBarView auxBarView = view.findViewById(R.id.aux_bar_view);
        if (auxBarView != null) {
            // The layout uses the unscaled 40dp base; apply the keyboard-height setting here
            // (a height-mode change recreates the input view, so this re-runs).
            android.view.ViewGroup.LayoutParams lp = auxBarView.getLayoutParams();
            if (lp != null) {
                lp.height = dev.bbkb.ime.core.device.ResourceConfigManager
                        .getSuggestionsStripHeight(auxBarView.getResources());
                auxBarView.setLayoutParams(lp);
            }
            AuxBarManager abm = this.imeService.getAuxBarManager();
            if (abm == null) {
                abm = new AuxBarManager(this.imeService);
                this.imeService.setAuxBarManager(abm);
            }
            // The manager outlives input-view recreation, so its cached keyboards carry the old
            // strip height and width (rotation, display size): clear them on every bind.
            abm.clearKeyboardCache();
            abm.setAuxBarView(auxBarView);
            abm.setEventListener(this.imeService);
        }
    }

    private void initializeCjkViews(View view) {
        KeyboardSwitcher ks = this.imeService.getKeyboardSwitcher();
        MainKeyboardView mainKeyboardView = ks.getMainKeyboardView();
        SlideboardManager slideboardManager = ks.getSlideboardManager();
        if (mainKeyboardView != null) {
            mainKeyboardView.setSlideBoardViewManager(slideboardManager);
        }
        this.imeService.setCjkSuggestionGridView(
                (CJKSuggestionGridView) view.findViewById(R.id.cjk_suggestions_grid_view));
        CJKSuggestionGridView cjkView = this.imeService.getCjkSuggestionGridView();
        if (cjkView != null) {
            cjkView.setListener(this.imeService);
        }
    }

    private void initializeBoardControllers(View view) {
        if (this.imeService.hasFccController()) {
            this.imeService.getFccController().bindView(view);
        }
        if (this.imeService.hasClipboardController()) {
            this.imeService.getClipboardController().bindView(view);
        }
        if (this.imeService.hasVoiceInputController()) {
            this.imeService.getVoiceInputController().onInputViewCreated(view);
        }
    }

    private void initializeFlickSuggestions(View view) {
        FlickSuggestionView current = (FlickSuggestionView) view.findViewById(R.id.flick_suggestion_view);
        // Release a flick view from a replaced (detached) input view, or it leaks the old tree and
        // keeps receiving suggestions. The same input view wired again keeps its flick view.
        FlickSuggestionView previous = this.imeService.getFlickSuggestionView();
        if (previous != null && previous != current) {
            previous.init(null, null);
            this.imeService.setFlickSuggestionView(null);
            this.flickSuggestionView = null;
        }
        this.imeService.refreshOnScreenKeyboardShowing();
        if (DeviceProfile.current().isPkbWithoutAlphabeticKeyboard()
                || DeviceProfile.current().isVkbDevice()
                || DeviceProfile.isOnScreenKeyboardVisible()
                || DeviceProfile.isVkbForcedForPackage(this.imeService.getCurrentPackageName())
                || DeviceProfile.isForceVkbMode()
                || this.imeService.isLandscapeOrientation()) {
            this.imeService.setFlickSuggestionView(current);
            if (current != null) {
                current.init(this.imeService, this.imeService.getKeyboardSwitcher().getMainKeyboardView());
            }
        }
    }

    private void initializeMoreKeysProvider() {
        AuxBarManager abm = this.imeService.getAuxBarManager();
        if (abm != null) {
            setAuxBarManager(abm);

            abm.setMoreKeysProvider(new MoreKeysProvider(this.imeService.getKeyboardSwitcher()));
            abm.registerKeyHoldCallback();
        }
    }

    private static final String DIAG = "INLINE_AUTOFILL_DEBUG";

    private void initializeInlineAutofill() {
        if (BuildConfig.DEBUG) {
        Log.d(DIAG, "[SETUP] InputViewCoordinator.initializeInlineAutofill()"
                + " | isSupported=" + InlineAutofillManager.isSupported()
                + " | auxBarManager=" + (this.imeService.getAuxBarManager() != null ? "SET" : "NULL"));
        }
        if (InlineAutofillManager.isSupported()) {
            AuxBarManager abm = this.imeService.getAuxBarManager();
            if (abm != null) {
                InlineAutofillManager autofillManager = InlineAutofillManager.getInstance(this.imeService);
                autofillManager.setAuxBarManager(abm);
                if (BuildConfig.DEBUG) Log.d(DIAG, "[SETUP] setAuxBarManager() called - OK");
            } else {
                if (BuildConfig.DEBUG) Log.d(DIAG, "[SETUP] SKIPPED: auxBarManager is NULL - setAuxBarManager not called");
            }
        } else {
            if (BuildConfig.DEBUG) Log.d(DIAG, "[SETUP] SKIPPED: InlineAutofillManager.isSupported()=false");
        }
    }
}

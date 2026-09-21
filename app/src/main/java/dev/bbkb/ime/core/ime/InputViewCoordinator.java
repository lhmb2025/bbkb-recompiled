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
import dev.bbkb.ime.BuildConfig;



/**
 * The construction half and the callback half of the input view, in one object.
 *
 * <p>{@link #setupInputView(View)} wires the views (it was {@code UISetupCoordinator}); the
 * listener implementations below are what those same views call back on (it was
 * {@code UICoordinator}). They were two files with two separate {@code BlackBerryIME}
 * back-references, and the setup half reached the callback half through
 * {@code ime.getUiCoordinator()}; merged 2026-09 per the package-rearchitecture plan §5.2.
 *
 * <p>The {@code BlackBerryIME} setters that exist only to serve the setup half
 * ({@code setRootInputView}, {@code setSwipeToDeleteAnimatorView}, {@code setAuxBarManager},
 * {@code setCjkSuggestionGridView}, {@code setFlickSuggestionView},
 * {@code setArrowBarController}) are deliberately left in place; removing them is separate work.
 */
public class InputViewCoordinator implements KeyboardSwitcher.SwitcherCallbacks, ClipboardController.Listener, FccController.Listener, VoiceInputController.Listener, NumberPadController.Listener {

    private static final String TAG = "UICoordinator";

    /** Kept distinct so the ARROW_BAR_DIAG / setup log lines read as they did before the merge. */
    private static final String SETUP_TAG = "UISetupCoord";

    private BlackBerryIME imeService;

    private SettingsManager settingsManager;

    private KeyboardSwitcher keyboardSwitcher;

    private SubtypeManager subtypeManager;

    // Active views (not deprecated)
    private CJKSuggestionGridView cjkSuggestionGridView;
    private FlickSuggestionView flickSuggestionView;


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
        // Active views only
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
            }
        }
        
        if (DeviceProfile.current().isPkbDevice()) {
            preShowSuggestionStripForPkb();
        }
    }
    
    /**
     * PKB Optimization: Pre-show the suggestion strip immediately for faster perceived loading.
     * Only shows if:
     * - Predictions are enabled in settings
     * - The current field supports suggestions (not password, URI, email, etc.)
     */
    private void preShowSuggestionStripForPkb() {
        SettingsValues settings = SettingsManager.getInstance().getSettingsValues();
        if (settings.isPredictionsEnabled && settings.editorCapabilities.shouldShowSuggestions) {
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
        
        // UIBM.hide() handles: hiding all components, hiding AuxBarManager UIM view, clearing activeComponent
        UnifiedInputBoardManager uibm = this.keyboardSwitcher.getUnifiedInputBoardManager();
        if (uibm != null) {
            uibm.hide();
        }
        
        // Hide AuxBarView for non-UIM states (suggestions, autofill, etc.)
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
        }
    }

    public void onAuxBarHeightChanged() {
        // AuxBarView handles its own positioning
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

    public void onCjkGridVisibilityChanged(int i) {
        if (i == 0) {
            getCjkSuggestionGrid().setVisible(false);
        } else {
            if (i != 8) {
                return;
            }
            getCjkSuggestionGrid().setVisible(true);
        }
    }

    @Override
    public void onFccPanelShown() {
        // REMOVED: setFlickSuggestionVisibility(4) - Don't hide UIM when FCC opens from it
        // REMOVED: hideSuggestionViews() - Don't hide aux bar (UIM) when FCC opens
        // The UIM should stay visible above the FCC panel
        
        // Hide FlickSuggestionView when FCC opens to prevent overlay
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
        // Always run the keyboard state chain — needed to properly initialize keyboard
        // state and expand keyboard_frame. VoiceInputView.show() uses bringToFront()
        // to guarantee it renders on top of mainKeyboardView regardless of VKB state.
        // This matches clipboard's onClipboardShown() pattern which also always runs the chain.
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

        // Audit CT-21: uimManager was dereferenced four times below with no null check, while
        // every OTHER access to the same getter in this file is guarded — and it genuinely
        // returns null before KeyboardSwitcher builds it. This is the handler for the -23
        // "show input menu" key and for the hamburger tap, which can fire before or after
        // input-view recreation; an NPE here kills the IME.
        if (auxBarManager != null && uimManager != null) {
            boolean auxBarShowing = auxBarManager.isShowing();
            if (auxBarShowing && this.imeService.isUimEnabled()) {
                auxBarManager.hide();
                uimManager.show(true);
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

    /**
     * Close whatever board the UIM currently owns, if the UIM exists yet.
     *
     * <p>Audit CT-21: the three board-hidden callbacks each called
     * {@code getUnifiedInputBoardManager().closeActiveComponent()} unguarded, though the getter
     * genuinely returns null before {@code KeyboardSwitcher} builds the manager.
     */
    private void closeUimComponent() {
        UnifiedInputBoardManager uim = this.keyboardSwitcher.getUnifiedInputBoardManager();
        if (uim != null) {
            uim.closeActiveComponent();
        }
    }

    private void setFlickSuggestionVisibility(int i) {
        FlickSuggestionView view = getFlickSuggestionView();
        if (view != null) {
            view.setVisibility(i);
        }
    }

    public boolean isUimEnabled() {
        return this.settingsManager.getSettingsValues().isUimEnabled;
    }

    public boolean shouldShowUim() {
        SettingsValues c0804dM5050c = SettingsManager.getInstance().getSettingsValues();
        return c0804dM5050c.isUimEnabled && !shouldShowSuggestionStrip(c0804dM5050c, this.imeService.isOnScreenKeyboardVisible(), this.subtypeManager.getCurrentSubtype());
    }

    private boolean shouldShowPredictionsInStrip(SettingsValues c0804d) {
        return c0804d.shouldShowPredictionsInCandidateStrip();
    }

    public boolean shouldShowLatinSuggestionStrip(SettingsValues c0804d, boolean z, InputMethodSubtype inputMethodSubtype) {
        // FIXED: Real methods found in p.smali, not n.smali - decompiler incorrectly placed them here
        // CORRECTED: Implementing original p.smali logic exactly - main strip for non-CJK, disabled for CJK
        
        // ORIGINAL SMALI LOGIC: Return false for Chinese/Japanese, true for others (enables main strip for English)
        // This matches p.smali structure: locale checks, then calls DownloadCallback() method for non-CJK
        if (LocaleUtils.isCurrentSubtypeChinese() || LocaleUtils.isCurrentSubtypeJapanese()) {
            return false;  // Disable main strip for CJK locales
        }
        return shouldShowSuggestionStrip(c0804d, z, inputMethodSubtype);  // Enable main strip for non-CJK locales
    }

    public boolean shouldShowCjkSuggestionStrip(SettingsValues c0804d, boolean z, InputMethodSubtype inputMethodSubtype) {
        // FIXED: Real methods found in p.smali, not n.smali - decompiler incorrectly placed them here
        // CORRECTED: Implementing original p.smali logic exactly - CJK strip only for CJK locales
        
        // ORIGINAL SMALI LOGIC: Return true for Chinese/Japanese, false for others (enables CJK strip for CJK only)
        // This matches p.smali structure: locale checks, then calls DownloadCallback() method for CJK
        if (LocaleUtils.isCurrentSubtypeChinese() || LocaleUtils.isCurrentSubtypeJapanese()) {
            return shouldShowSuggestionStrip(c0804d, z, inputMethodSubtype);  // Enable CJK strip for CJK locales
        }
        return false;  // Disable CJK strip for non-CJK locales
    }

    /**
     * Audit CT-25: every predicate used to be evaluated eagerly into a local before any of them
     * was tested, defeating short-circuiting — on a method reached from {@code shouldShowUim()} on
     * every {@code onStartInputView}, from {@code SuggestionStripPresenter} on every suggestion
     * delivery, and from {@code refreshSuggestionStripVisibility}.
     *
     * <p>The most expensive of them, {@code ImportantNoticeUtils.shouldShowImportantNotice}
     * ({@code getSharedPreferences} plus a {@code Resources.getInteger}), is gone entirely:
     * {@code config_important_notice_version} is {@code 0} in every values folder, so
     * {@code hasNewImportantNotice()} is {@code 0 > 0} — permanently false. The whole notice
     * subsystem was dead weight this predicate paid for on every call.
     */
    public boolean shouldShowSuggestionStrip(SettingsValues c0804d, boolean z, InputMethodSubtype inputMethodSubtype) {
        if (c0804d.editorCapabilities.isPassword) {
            return false;
        }
        if (!shouldShowPredictionsInStrip(c0804d) && !c0804d.shouldShowMoreKeys()) {
            return false;
        }
        if (!hasAuxBarManager()) {
            return false;
        }
        ArrowBarController arrowBar = this.arrowBarController;
        if (arrowBar != null && arrowBar.isShowing()) {
            return false;
        }
        // The flick-suggestion view owns predictions instead of the strip when it is present and
        // every one of its preconditions holds.
        boolean flickViewOwnsPredictions = hasFlickSuggestionView()
                && ResourceLocaleUtils.isNonCjkLanguage(inputMethodSubtype)
                && c0804d.isPredictionsEnabled
                && c0804d.isOnKeyPredictionsEnabled
                && z
                && !c0804d.isVkbGestureInputEnabledForLocale();
        return !flickViewOwnsPredictions;
    }

    /**
     * CT-34: this took (isOnScreenKeyboardVisible, isFullscreenMode, currentSubtype) and read none
     * of them, so three call sites computed arguments for nothing. All that survives AB-1 is the
     * CJK-grid hide below, so the parameters are gone.
     */
    public void updateSuggestionStripVisibility() {
        // Audit AB-1: this used to force the aux-bar CONTAINER visible directly. Container
        // visibility belongs to AuxBarView, which owns it together with its children and its
        // AuxBarState as one coupled fact — it sets VISIBLE in showSuggestions/showAutofill/
        // showKeys and GONE in hide(), where the comment records why: "prevents phantom space
        // in layout and touch-event consumption when the bar is logically hidden".
        //
        // Forcing it visible from out here set the container without showing either child or
        // updating the state, producing exactly what hide() guards against: a VISIBLE,
        // empty, fixed-height container that is setClickable(true) (AuxBarView:79, so taps do
        // not fall through to the app) and is unioned into the touchable region by
        // onComputeInsets. In a field that declines suggestions that is a blank strip-height
        // band which shifts app content up and silently eats every tap in it — reaching
        // neither the keyboard nor the app.
        //
        // The predicate here (shouldShowLatin/CjkSuggestionStrip) was NOT the same predicate
        // AuxBarManager.showSuggestionStrip applies, which is how the two came to disagree. The
        // computation of both predicates went with the call: nothing read them.
        if (hasCjkSuggestionGrid()) {
            this.cjkSuggestionGridView.setVisible(false);
        }
    }

    public void hideSuggestionViews() {
        // Hide all suggestion views
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

    /**
     * CT-34: {@code showLatinSuggestionStripIfNeeded(boolean)} used to sit beside this and had an
     * empty body (AuxBarManager owns the Latin strip since AB-1); it and its call below are gone.
     * This one ignored its {@code boolean} too, so the parameter went with it.
     */
    public void showCjkSuggestionStripIfNeeded() throws Resources.NotFoundException {
        // Now handled by AuxBarManager — audit AB-1, as above.
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

    private boolean hasFlickSuggestionView() {
        return this.flickSuggestionView != null;
    }

    private boolean hasCjkSuggestionGrid() {
        return this.cjkSuggestionGridView != null;
    }

    private boolean hasAuxBarManager() {
        return this.auxBarManager != null;
    }
    
    /**
     * Set the AuxBarManager for unified aux bar control.
     * This will eventually replace direct manipulation of individual aux bars.
     */
    public void setAuxBarManager(dev.bbkb.ime.keyboard.auxbar.AuxBarManager manager) {
        this.auxBarManager = manager;
    }

    // ==================== view setup (was UISetupCoordinator) ====================
    /**
     * Main entry point: wire up all UI components from the input view.
     * Called from BlackBerryIME.setInputView().
     */
    public void setupInputView(View view) {
        this.imeService.setRootInputView(view);
        this.imeService.setSwipeToDeleteAnimatorView(
                (SwipeToDeleteAnimatorView) view.findViewById(R.id.delete_animation_view));

        initializeAuxBar(view);
        initializeCjkViews(view);
        initializeBoardControllers(view);
        initializeFlickSuggestions(view);


        this.imeService.getInputLogic().setTimerProxy(new TextDecoratorUi(this.imeService, view));
        ArrowBarController arrowBarController = new ArrowBarController(
                this.imeService.getApplicationContext(), this.imeService, view);
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
            // main_keyboard_frame.xml binds the container to the unscaled 40dp base. Apply the
            // keyboard-height setting here; a height-mode change recreates the input view, so
            // this re-runs and the container tracks the bars inside it.
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
            // Audit CT-36 (completing AB-3): the AuxBarManager is deliberately reused across
            // input-view recreations, so its cached arrow/diacritics/UIM keyboards — built at the
            // PREVIOUS strip height and screen width — come with it. clearKeyboardCache() was
            // wired into the two preference paths only; an orientation or display-size change
            // recreates the input view without touching any preference, so the stale-geometry
            // keyboards survived. Idempotent, and the strip height is re-derived just above.
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
        this.imeService.refreshOnScreenKeyboardShowing();
        if (DeviceProfile.current().isPkbWithoutAlphabeticKeyboard()
                || DeviceProfile.current().isVkbDevice()
                || DeviceProfile.isOnScreenKeyboardVisible()
                || DeviceProfile.isVkbForcedForPackage(this.imeService.getCurrentPackageName())
                || DeviceProfile.isForceVkbMode()
                || this.imeService.isLandscapeOrientation()) {
            FlickSuggestionView flickView =
                    (FlickSuggestionView) view.findViewById(R.id.flick_suggestion_view);
            this.imeService.setFlickSuggestionView(flickView);
            if (flickView != null) {
                flickView.init(this.imeService, this.imeService.getKeyboardSwitcher().getMainKeyboardView());
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

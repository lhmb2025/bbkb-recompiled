package dev.bbkb.ime.keyboard.inputboard;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.content.res.Resources;
import dev.bbkb.ime.core.settings.PrefsManager;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import dev.bbkb.ime.R;
import dev.bbkb.ime.core.BlackBerryIME;
import dev.bbkb.ime.core.settings.util.SettingsManager;
import dev.bbkb.ime.keyboard.inputboard.clipboard.ClipboardController;
import dev.bbkb.ime.keyboard.inputboard.fcc.FccController;
import dev.bbkb.ime.core.textinput.InputLogic;
import dev.bbkb.ime.core.subtypeswitcher.SubtypeFactory;
import dev.bbkb.ime.core.shared.Logger;
import dev.bbkb.ime.core.device.ResourceConfigManager;
import dev.bbkb.ime.keyboard.Key;
import dev.bbkb.ime.keyboard.Keyboard;
import dev.bbkb.ime.keyboard.KeyboardBuilder;
import dev.bbkb.ime.keyboard.KeyboardSwitcher;
import dev.bbkb.ime.keyboard.SimplifiedKeyboardView;
import dev.bbkb.ime.keyboard.auxbar.autofill.InlineAutofillManager;
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiBoardController;
import dev.bbkb.ime.keyboard.inputboard.numberpad.NumberPadController;
import dev.bbkb.ime.keyboard.internal.KeyboardIconSet;
import dev.bbkb.ime.keyboard.internal.MoreKeySpec;
import dev.bbkb.ime.keyboard.inputboard.voice.VoiceInputController;

import dev.bbkb.ime.keyboard.auxbar.AuxBarManager;
import dev.bbkb.ime.keyboard.auxbar.AuxBarState;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;



public class UnifiedInputBoardManager implements SimplifiedKeyboardView.onKeyEventListener, UnifiedBoardCoordinator.BoardHost {

    private static final String TAG = "UnifiedInputBoardManager";

    private final Context context;

    private final SimplifiedKeyboardView keyboardView;

    // REMOVED: the separate emoji-bar view - View hierarchy simplified, single unified bar now used

    private final KeyboardBuilder keyboardBuilder;

    private final BlackBerryIME imeService;

    private final KeyboardSwitcher keyboardSwwitcher;

    private boolean isAnimating;
    
    // UIM-05 fix: Safety timeout to prevent isAnimating from getting permanently stuck
    private static final long ANIMATION_TIMEOUT_MS = 1000;

    /** Element id of kbd_unified_input_menu_pool.xml (attrs.xml elementName unifiedInputMenuPool). */
    private static final int UIM_POOL_ELEMENT_ID = 43;
    private final Runnable animationSafetyReset = new Runnable() {
        @Override
        public void run() {
            if (UnifiedInputBoardManager.this.isAnimating) {
                Logger.warn(TAG, "Animation safety timeout — resetting isAnimating");
                UnifiedInputBoardManager.this.isAnimating = false;
            }
        }
    };

    private Map<Integer, UnifiedInputBoardComponent> componentMap;

    private final UnifiedInputBoardHandler uimHandler;

    /**
     * The typing-delay handler's callback. The handler references it only weakly (so a queued
     * message cannot pin this manager), which makes this field its ONLY strong holder: without it
     * the callback is collectable at once and the disable/restore messages silently stop, leaving
     * the bar's keys disabled.
     */
    private final UnifiedInputBoardHandler.Callback uimHandlerCallback;

    private final List<Boolean> enabledStates;

    private Key cachedAlphabetKey;
    
    /**
     * Single owner of board open/close decisions AND of "which board is open".
     * The UIM keeps no parallel activeComponent field — {@link #getActiveComponent()}
     * derives it from the coordinator's activeBoard, and every open/close path
     * reports through {@link #setActiveComponent} to mutate it.
     */
    private final UnifiedBoardCoordinator boardCoordinator = new UnifiedBoardCoordinator(this);
    
    /* Phase 4.1: Queue a single tap received during animation for replay after animation ends */
    private Key pendingAnimationKey = null;
    private boolean pendingAnimationLongPress = false;
    
    /* Phase 1 Optimization: Cache for batching invalidations */
    private boolean pendingInvalidation = false;
    private final android.os.Handler invalidationHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    
    /* Phase 2 Optimization: Lazy initialization flag */
    private boolean componentsRegistered = false;
    
    /* Phase 3 Optimization: Cache keyboard to avoid rebuilding */
    private Keyboard cachedKeyboard = null;
    
    /* Single source of truth: The currently displayed UIM keyboard */
    private Keyboard displayedKeyboard = null;

    @Override // dev.bbkb.ime.keyboard.SimplifiedKeyboardView.onKeyEventListener
    public void onKeyDown(Key key) {
    }

    /**
     * The one place that knows how the bar reaches the screen. Null means the AuxBar is not
     * installed yet, which selects the legacy direct-visibility fallback in {@link #showBar},
     * {@link #hideBar} and {@link #isShowing()}.
     */
    private AuxBarManager bar() {
        return this.imeService.getUiCoordinator().getAuxBarManager();
    }

    /** Raise the bar with {@code keyboard} on it, through the AuxBar or the legacy fallback. */
    private void showBar(Keyboard keyboard) {
        AuxBarManager auxBarManager = bar();
        if (auxBarManager != null) {
            auxBarManager.showUnifiedInputMenu(keyboard);
        } else {
            this.keyboardView.setKeyboard(keyboard);
            this.keyboardView.setVisibility(View.VISIBLE);
        }
    }

    /** Take the bar down, through the AuxBar or the legacy fallback. */
    private void hideBar() {
        AuxBarManager auxBarManager = bar();
        if (auxBarManager != null) {
            auxBarManager.hideUnifiedInputMenu();
        } else {
            this.keyboardView.setVisibility(View.GONE);
        }
    }

    /**
     * Paint the key states, publish {@code keyboard} as the displayed one, raise the bar, and
     * paint again.
     *
     * <p>FABLE_VIEW_REPORT §7: the first paint runs while the view is still GONE, so its
     * per-component key active/highlight branch is skipped and a cached keyboard can carry stale
     * states (keys dead on first tap). The second paint runs with the view VISIBLE so the
     * component keys reflect the current enabled state. Both show paths need exactly this
     * sequence; it is shared rather than written twice.
     */
    private void publishBar(Keyboard keyboard) {
        updateKeyHighlightedStates(keyboard);
        this.displayedKeyboard = keyboard;
        showBar(keyboard);
        updateKeyHighlightedStates(keyboard);
    }

    /**
     * Centralized UIM state dump for debugging state transitions.
     * Prints activeComponent, showing components, isAnimating, AuxBar state,
     * and keyboardView visibility. Gated behind Logger.debug (silenced on release).
     */
    /**
     * Audit IB-22: the callers used to build "caller(" + keyCode + ")" before the
     * call, so the StringBuilder and Integer.toString ran on release builds too - on
     * the board key-press path, and in setActiveComponent, the funnel every open and
     * close routes through. This overload composes the label only after the
     * isLoggable check.
     */
    private void dumpUimState(String caller, int arg) {
        if (!Logger.isLoggable(TAG, android.util.Log.DEBUG)) return;
        dumpUimState(caller + "(" + arg + ")");
    }

    private void dumpUimState(String caller) {
        if (!Logger.isLoggable(TAG, android.util.Log.DEBUG)) return;
        int activeKc = this.boardCoordinator.activeBoard();
        StringBuilder showing = new StringBuilder();
        if (this.componentMap != null) {
            for (Map.Entry<Integer, UnifiedInputBoardComponent> e : this.componentMap.entrySet()) {
                if (e.getValue() != null && e.getValue().isShowing()) {
                    if (showing.length() > 0) showing.append(",");
                    showing.append(e.getKey());
                }
            }
        }
        AuxBarState auxState = AuxBarState.NONE;
        AuxBarManager abm = bar();
        if (abm != null) auxState = abm.getCurrentState();
        int kbVis = this.keyboardView != null ? this.keyboardView.getVisibility() : -1;
        Logger.debug(TAG, "[" + caller + "] active=" + activeKc
            + " showing=[" + showing + "]"
            + " isAnimating=" + this.isAnimating
            + " auxBar=" + auxState
            + " kbVis=" + (kbVis == View.VISIBLE ? "VISIBLE" : kbVis == View.GONE ? "GONE" : String.valueOf(kbVis)));
    }

    /**
     * UIM-02 assertion: verify UIBM isShowing() agrees with AuxBarManager state.
     * Logs WARN if they diverge. Only runs on debug builds.
     */
    private void assertVisibilityConsistency(String caller) {
        if (!Logger.isLoggable(TAG, android.util.Log.DEBUG)) return;
        AuxBarManager abm = bar();
        if (abm == null) return;
        boolean uibmShowing = isShowing();
        boolean viewVisible = this.keyboardView != null
            && this.keyboardView.getVisibility() == View.VISIBLE;
        AuxBarState auxState = abm.getCurrentState();
        boolean auxSaysUim = auxState == AuxBarState.UNIFIED_INPUT_MENU;
        if (uibmShowing != auxSaysUim || (uibmShowing && !viewVisible)) {
            Logger.warn(TAG, "[" + caller + "] UIM-02 DIVERGENCE: isShowing()=" + uibmShowing
                + " auxState=" + auxState + " viewVisible=" + viewVisible);
        }
    }

    public UnifiedInputBoardManager(Context context, View view, BlackBerryIME blackBerryIME) {
        // Legacy unified_input_menu_bar_view removed - now using AuxBarManager
        // Get shared key view from AuxBarView if available
        dev.bbkb.ime.keyboard.auxbar.AuxBarView auxBarView = view.findViewById(dev.bbkb.ime.R.id.aux_bar_view);
        if (auxBarView != null) {
            this.keyboardView = auxBarView.getSharedKeyView();
        } else {
            this.keyboardView = null;
        }
        
        if (this.keyboardView != null) {
            this.keyboardView.setVisibility(View.GONE);
            this.keyboardView.setOnKeyEventListener(this);
            this.keyboardView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }
        // One style tree; colors come from KeyboardColorManager at draw time (C7).
        this.context = BoardKeyboardFactory.themedContext(context);
        // KeyboardBuilder is lightweight to create - actual keyboard building is deferred
        this.keyboardBuilder = createKeyboardBuilder();
        this.imeService = blackBerryIME;
        this.keyboardSwwitcher = KeyboardSwitcher.getInstance();
        setAnimating(false);
        // UIM-08 fix: Use ConcurrentHashMap for thread safety
        this.componentMap = new ConcurrentHashMap();
        // Phase 2: Lazy component registration - defer until first show
        // Initialization deferred - happens lazily on first show
        this.enabledStates = new ArrayList();
        this.uimHandlerCallback = new UnifiedInputBoardHandler.Callback() {
            @Override
            public void disableAllKeys() {
                UnifiedInputBoardManager.this.enabledStates.clear();
                Keyboard keyboard = UnifiedInputBoardManager.this.keyboardView.getKeyboard();
                if (keyboard != null) {
                    for (Key key : keyboard.getKeys()) {
                        UnifiedInputBoardManager.this.enabledStates.add(Boolean.valueOf(key.isActive()));
                        key.setActive(false);
                    }
                }
            }

            @Override
            public void restoreKeyStates() {
                Keyboard keyboard = UnifiedInputBoardManager.this.keyboardView.getKeyboard();
                if (keyboard != null) {
                    List<Key> listMo6608c = keyboard.getKeys();
                    if (UnifiedInputBoardManager.this.enabledStates.size() == listMo6608c.size()) {
                        for (int i = 0; i < listMo6608c.size(); i++) {
                            listMo6608c.get(i).setActive(((Boolean) UnifiedInputBoardManager.this.enabledStates.get(i)).booleanValue());
                        }
                    }
                }
                // UIM-03: A board may have opened (or already been open, e.g. the emoji board)
                // between the save and this restore, in which case the saved states are stale.
                // We must NOT simply skip the restore — doing so leaves every key disabled
                // (disableAllKeys set setActive(false) on all keys), making the UIM bar untappable.
                // Instead, re-derive the correct board-specific enabled/highlight states from
                // the single source of truth so the active board's keys stay responsive.
                UnifiedInputBoardComponent activeForRestore =
                        UnifiedInputBoardManager.this.getActiveComponent();
                if (activeForRestore != null) {
                    Logger.debug(TAG, "restoreKeyStates: activeComponent="
                        + activeForRestore.getKeyCode()
                        + " — re-deriving key states from current board state");
                    UnifiedInputBoardManager.this.updateKeyHighlightedStates(keyboard);
                }
                UnifiedInputBoardManager.this.scheduleInvalidation();
                UnifiedInputBoardManager.this.dumpUimState("restoreKeyStates");
            }
        };
        this.uimHandler = new UnifiedInputBoardHandler(this.uimHandlerCallback,
                this.keyboardView.getResources().getInteger(R.integer.config_typing_delay_inputboard_bar));
        this.imeService.attachInputBoardHandler(this.uimHandler);
    }

    public void setTranslation(int i, int i2) {
        this.keyboardView.setTranslationY(i);
        this.keyboardView.setTranslationZ(i2);
    }

    public void destroy() {
        // Cancel all pending handlers/timers to prevent stale callbacks
        this.uimHandler.cancelPendingMessages();
        this.invalidationHandler.removeCallbacks(animationSafetyReset);
        this.invalidationHandler.removeCallbacksAndMessages(null);
        this.keyboardView.animate().cancel();
        setAnimating(false);
        this.keyboardView.setOnKeyEventListener(null);
        this.componentMap = null;
        this.boardCoordinator.notifyBoardClosed();
        this.displayedKeyboard = null;
        this.cachedKeyboard = null;
    }

    /**
     * The boards that survive a keyboard-state change, in the order they are probed.
     *
     * <p>{@link #hideKeyboardOnKeyboardStateChange()} runs from {@code KeyboardSwitcher}'s shift /
     * symbol-page entry points, i.e. on the rebuild that follows <em>every committed character</em>
     * ({@code InputLogic} commit → {@code applyPostEventUpdates} → {@code resetKeyboardState} →
     * {@code setAlphabetKeyboard} → {@code requestShiftOff}/{@code requestAutomaticShift}). A board
     * that stays open across a commit therefore has to be named here, or the user's own text closes
     * it. FCC and the number pad are boards the user TYPES from; voice is the board the user
     * DICTATES from, and it reaches this sweep by exactly the same route — the commit of the
     * dictation result. Everything else (emoji, clipboard, …) is closed by a keyboard-state change
     * as before.
     */
    private static final int[] BOARDS_EXEMPT_FROM_KEYBOARD_STATE_CHANGE = {
            FccController.KEY_CODE,
            NumberPadController.KEY_CODE,
            VoiceInputController.KEY_CODE,
    };

    /**
     * The first board from {@link #BOARDS_EXEMPT_FROM_KEYBOARD_STATE_CHANGE} whose view is up, or
     * null. Each exemption is an early return over the WHOLE sweep, not a per-component skip —
     * that is the long-standing FCC semantics, kept deliberately.
     */
    private UnifiedInputBoardComponent findShowingExemptBoard() {
        if (this.componentMap == null) {
            return null;
        }
        for (int keyCode : BOARDS_EXEMPT_FROM_KEYBOARD_STATE_CHANGE) {
            UnifiedInputBoardComponent component = this.componentMap.get(Integer.valueOf(keyCode));
            if (component != null && component.isShowing()) {
                return component;
            }
        }
        return null;
    }

    public void hideKeyboardOnKeyboardStateChange() {
        if (isShowing() && isAnyBoardShowing()) {
            UnifiedInputBoardComponent exempt = findShowingExemptBoard();
            if (exempt != null) {
                Logger.debug(TAG, "hideKeyboardOnKeyboardStateChange: board "
                        + exempt.getKeyCode() + " is exempt; not sweeping");
                return;
            }
            hideOtherComponents(-37);
            // hideOtherComponents now reports the close itself when the sweep takes down the
            // active board (defect 8), so this is no longer that reconcile. It still covers the
            // case the sweep cannot see: an active board whose view was ALREADY down before the
            // sweep ran, which the sweep skips and therefore never reports.
            UnifiedInputBoardComponent active = getActiveComponent();
            if (active != null && !active.isShowing()) {
                setActiveComponent(null);
            }
            refresh();
        }
    }

    public void show(boolean force) {
        // Check if UIM is disabled in settings
        if (!SettingsManager.isUimEnabled(this.context)) {
            return;
        }
        if (!isAnimating() && (!this.keyboardView.isShown() || force)) {
            Logger.debug(TAG, "Showing Input Board Bar");
            // Phase 2 Optimization: Ensure lazy initialization before first display
            ensureComponentsRegistered();
            
            setAnimating(false);
            // Phase 3 Optimization: Use cached keyboard
            Keyboard c0965eM6734a = getOrBuildKeyboard();
            this.cachedAlphabetKey = c0965eM6734a.getKeyByCode(-3);
            
            // Apply color tints BEFORE showing
            c0965eM6734a.mIconsSet.applyColorTint();

            publishBar(c0965eM6734a);

            this.imeService.getUiUpdateHandler().cancelPendingSuggestionUpdates();
            // Phase 1 Optimization: Use batched invalidation
            scheduleInvalidation();
            dumpUimState("show.EXIT");
            assertVisibilityConsistency("show");
            return;
        }
        refresh();
    }

    public boolean isShowing() {
        // UIM-02 fix: AuxBarManager is the single source of truth for UIM visibility.
        // This eliminates dual-ownership between UIBM view check and AuxBarView.currentState.
        AuxBarManager auxBarManager = bar();
        if (auxBarManager != null) {
            return auxBarManager.getCurrentState() == AuxBarState.UNIFIED_INPUT_MENU;
        }
        // Legacy fallback
        return this.keyboardView.getVisibility() == View.VISIBLE;
    }

    public void showWithAnimation(final View suggestionStripView) {
        // Check if UIM is disabled in settings
        if (!SettingsManager.isUimEnabled(this.context)) {
            return;
        }
        if (isAnimating() || this.keyboardView.isShown()) {
            return;
        }
        // Phase 2 Optimization: Ensure lazy initialization before display
        ensureComponentsRegistered();
        
        Resources resources = this.context.getResources();
        // Phase 3 Optimization: Use cached keyboard
        Keyboard c0965eM6734a = getOrBuildKeyboard();
        this.imeService.uiUpdateHandler.cancelPendingSuggestionUpdates();

        publishBar(c0965eM6734a);

        // Apply color tint when keyboard is set
        c0965eM6734a.mIconsSet.applyColorTint();
        this.keyboardView.setAlpha(0.0f);
        float fM5585a = ResourceConfigManager.getScreenWidthPixels(this.context.getResources());
        this.keyboardView.setTranslationX(fM5585a);
        this.keyboardView.setTranslationY(suggestionStripView != null && suggestionStripView.isShown() ? suggestionStripView.getHeight() : 0);
        this.keyboardView.animate().translationXBy(-fM5585a).setDuration(resources.getInteger(R.integer.config_input_board_bar_slide_in_animation)).setInterpolator(new AccelerateDecelerateInterpolator()).alpha(1.0f).setListener(new AnimatorListenerAdapter() {
            @Override // android.animation.AnimatorListenerAdapter, android.animation.Animator.AnimatorListener
            public void onAnimationStart(Animator animator) {
                UnifiedInputBoardManager.this.setAnimating(true);
                // UIM-05: Start safety timeout
                UnifiedInputBoardManager.this.invalidationHandler.removeCallbacks(animationSafetyReset);
                UnifiedInputBoardManager.this.invalidationHandler.postDelayed(animationSafetyReset, ANIMATION_TIMEOUT_MS);
            }

            @Override // android.animation.AnimatorListenerAdapter, android.animation.Animator.AnimatorListener
            public void onAnimationEnd(Animator animator) {
                UnifiedInputBoardManager.this.invalidationHandler.removeCallbacks(animationSafetyReset);
                UnifiedInputBoardManager.this.setAnimating(false);
                UnifiedInputBoardManager.this.keyboardView.setTranslationY(0.0f);
                if (suggestionStripView != null) {
                    suggestionStripView.setVisibility(View.GONE);
                }
            }
            
            @Override // android.animation.AnimatorListenerAdapter, android.animation.Animator.AnimatorListener
            public void onAnimationCancel(Animator animator) {
                // UIM-05: Ensure flag resets even if animation is cancelled
                UnifiedInputBoardManager.this.invalidationHandler.removeCallbacks(animationSafetyReset);
                UnifiedInputBoardManager.this.setAnimating(false);
            }
        });
    }

    public void hide() {
        Logger.debug(TAG, "Hiding Input Board Bar via AuxBarManager");
        // UIM-05: Cancel any running animation and its safety timeout on hide
        this.keyboardView.animate().cancel();
        this.invalidationHandler.removeCallbacks(animationSafetyReset);
        setAnimating(false);

        hideBar();

        // Clear displayed keyboard when hiding - will be set again on next show
        this.displayedKeyboard = null;
        
        hideAllComponents();  // This will clear active component via setActiveComponent(null)
        dumpUimState("hide.EXIT");
        assertVisibilityConsistency("hide");
    }

    public void hideView() {
        Logger.debug(TAG, "Hiding Input Board View via AuxBarManager");
        hideBar();
    }

    public void showView() {
        Logger.debug(TAG, "Showing Input Board View via AuxBarManager");
        // NOT showBar(): the legacy fallback here only raises the view, deliberately leaving
        // whatever keyboard it already carries alone.
        AuxBarManager auxBarManager = bar();
        if (auxBarManager != null) {
            // Re-show current keyboard if available, or build default
            Keyboard k = this.keyboardView.getKeyboard();
            if (k == null) {
                k = getOrBuildKeyboard();
            }
            auxBarManager.showUnifiedInputMenu(k);
        } else {
            this.keyboardView.setVisibility(View.VISIBLE);
        }
    }

    public void refresh() {
        updateKeyHighlightedStates(this.keyboardView.getKeyboard());
        // Phase 1 Optimization: Batch invalidations to reduce layout passes
        scheduleInvalidation();
        for (UnifiedInputBoardComponent component : this.componentMap.values()) {
            component.onRefresh();
        }
    }

    /**
     * Entry bookkeeping every board transition shares, whichever half of the toggle decided it.
     * A PKB in symbol mode is reset to alphabet so {@code mainKeyboardView} has the alphabet
     * layout loaded when the panel is dismissed, and any pending typing-delay key-state restore
     * is cancelled so it cannot land on the board we are about to change (UIM-03).
     */
    private void beginBoardTransition() {
        KeyboardSwitcher ks = KeyboardSwitcher.getInstance();
        if (ks != null) {
            ks.clearPkbSymbolMode();
        }
        this.uimHandler.cancelPendingMessages();
    }

    /**
     * Bar-action dispatch: the OPEN half of the toggle, plus the bar keys that are not boards at
     * all (-3 alphabet, -26/-45 settings, anything absent from the component map).
     *
     * <p>It is reached from {@link #openBoard(int)} — i.e. from the coordinator having already
     * decided "open" — and from {@link #handleKeyEvent} for those non-board codes. Closes go to
     * {@link #closeBoard(int)}, its matched other half over the same
     * {@link #beginBoardTransition()} prologue.
     *
     * <p>Defect 8 (the board "dead press"): this method used to re-decide open-versus-close from
     * {@code component.isShowing()} — the clobberable view state the coordinator was introduced to
     * stop deciding from. When the two disagreed the press did the opposite of what it asked for:
     * the coordinator said "closed", so {@code requestBoard} took the OPEN path, and the
     * {@code isShowing()} re-check here saw the view up and CLOSED the board. The user's press to
     * open closed it; only the second press opened it. (Production reaches that desync easily:
     * {@link #showEmojiBoard()} shows a board straight through the component and reports nothing,
     * and {@code KeyboardSwitcher.setPkbSymbolsKeyboard} calls it without the follow-up
     * {@code setActiveComponentByKeyCode} that {@code showEmojiKeyboard} does.)
     *
     * <p>Where the view and the coordinator disagree, <b>the coordinator wins and the view is
     * repaired to match</b> rather than the disagreement merely being ignored — ignoring it would
     * leave a visibly-open board the coordinator believes is closed, which just moves the bug one
     * press later. Repair costs nothing extra: {@link UnifiedInputBoardComponent#show()} is guarded
     * on the component's own {@code isShowing()}, so a board whose view is already up is simply
     * left up, and the {@code setActiveComponent} that follows brings the coordinator's copy into
     * line. Both halves end the press agreeing the board is open, which is what the press asked
     * for. The audit notes this changes one edge case, and that change IS the fix.
     */
    private void dispatchBoardAction(int iM6232c) {
        dumpUimState("dispatchBoardAction.ENTRY", iM6232c);
        beginBoardTransition();
        // ===== EXCLUSIVE-OPEN INVARIANT (UIM-01/UIM-04/UIM-10) =====
        UnifiedInputBoardComponent previousActive = getActiveComponent();
        if (previousActive != null && previousActive.getKeyCode() != iM6232c) {
            // Defect 8: this guard used to be gated on previousActive.isShowing() as well, so
            // switching away from a board whose view had been clobbered down skipped the guard
            // entirely — nothing closed AND the coordinator was left pointing at the old board,
            // which then made the target's own isShowing() re-check below close it. Result: the
            // user asked to switch boards and ended up with nothing open. hide() carries its own
            // isShowing() guard, so dropping the gate is a no-op when the view really is down,
            // and the setActiveComponent(null) repairs the coordinator either way.
            Logger.debug(TAG, "Exclusive-open: closing active board " + previousActive.getKeyCode() + " before opening " + iM6232c);
            previousActive.hide();
            setActiveComponent(null);
        }

        // Hide any other stray showing components (defense-in-depth)
        hideOtherComponents(iM6232c);
        
        if (iM6232c == -3) {
            setActiveComponent(null);
            closeActiveComponent();
            if (isSlideboardShowing()) {
                showAndRefreshForSlideboard();
            } else {
                this.imeService.restoreSuggestionStrip(true, true);
            }
        } else if (iM6232c == -26) {
            this.imeService.openSlideboardSettings();
        } else if (iM6232c == -45) {
            this.imeService.openSettings();
        } else {
            if (iM6232c == -27) {
                // Voice input
                UnifiedInputBoardComponent voiceComponent = this.componentMap.get(Integer.valueOf(iM6232c));
                if (voiceComponent instanceof dev.bbkb.ime.keyboard.inputboard.voice.VoiceInputController) {
                    dev.bbkb.ime.keyboard.inputboard.voice.VoiceInputController voiceController =
                        (dev.bbkb.ime.keyboard.inputboard.voice.VoiceInputController) voiceComponent;
                    // Defect 8, voice flavour: this used to call toggleVoiceInput()
                    // unconditionally and then report null-or-open from the PREVIOUS mode flag —
                    // the same view-derived toggle as the branch below, so an open request that
                    // arrived while a recognizer session was already running stopped it. On the
                    // open path we start voice only when it is not already running, and report the
                    // open either way so the coordinator matches what the microphone is doing.
                    if (!voiceController.isInVoiceMode()) {
                        voiceController.toggleVoiceInput();
                    }
                    setActiveComponent(voiceComponent);
                }
            } else {
                // Every other board, emoji included: EmojiBoardController answers isShowing()
                // from the KeyboardState machine and drives show/hide through it, so emoji no
                // longer needs a branch of its own here (§5.6 item 4).
                // FCC, Clipboard, Number pad, Emoji, Autofill.
                UnifiedInputBoardComponent interfaceC1012j = this.componentMap.get(Integer.valueOf(iM6232c));
                if (interfaceC1012j != null) {
                    // Unconditional open — see the method comment. show() is guarded on the
                    // component's own isShowing(), so a board whose view is already up is left up
                    // rather than closed, and the report below repairs the coordinator's copy.
                    // The close half of the old toggle (and its -42 suggestion-strip restore)
                    // lives in closeBoard(int), which is where the coordinator sends closes.
                    interfaceC1012j.show();
                    // ...but report the RESULT, not the intention. The BoardHost contract says so
                    // in as many words ("a failed open leaves the coordinator truthful instead of
                    // assuming success") and this line was the one place that broke it. A board
                    // can refuse: FccController.showFcc() carries its own precondition (no
                    // physical key held) and any board whose ViewStub has not been inflated is a
                    // silent no-op. Reporting the open anyway left activeBoard pointing at a board
                    // that never appeared, so the user's NEXT tap on that icon took requestBoard's
                    // CLOSE branch and did nothing — one refusal cost two taps, not one, which is
                    // what turns a transient refusal into "the icon sometimes does nothing".
                    if (interfaceC1012j.isShowing()) {
                        setActiveComponent(interfaceC1012j);
                        if (iM6232c == -37) {
                            hideView();
                        }
                    } else {
                        Logger.warn(TAG, "openBoard(" + iM6232c + "): the board refused to open; "
                                + "reporting it CLOSED so the next press opens rather than toggles");
                        setActiveComponent(null);
                    }
                }
            }
        }
        updateAlphabetKeyForSlideboard();
        refresh();
        dumpUimState("dispatchBoardAction.EXIT", iM6232c);
        assertVisibilityConsistency("dispatchBoardAction.EXIT");
    }

    private void handleKeyEvent(Key key, boolean isLongPress) {
        // Ensure components are registered before handling key events
        ensureComponentsRegistered();

        int iM6232c = key.getCode();
        if (isLongPress && key.hasLongPressKey()) {
            iM6232c = key.getLongPressCode();
        }

        // Board keys route through the coordinator so touch and physical keys share
        // ONE toggle decision point (and the coordinator's active-board state stays
        // truthful for both). Non-board codes (-3 alphabet, -26/-45 settings) keep
        // the legacy dispatch.
        if (this.componentMap != null && this.componentMap.containsKey(Integer.valueOf(iM6232c))) {
            this.boardCoordinator.requestBoard(iM6232c);
            return;
        }
        dispatchBoardAction(iM6232c);
    }

    @Override // dev.bbkb.ime.keyboard.SimplifiedKeyboardView.onKeyEventListener
    public void onKeyUp(Key key, boolean z) {
        if (!z) {
            return;
        }
        if (isAnimating()) {
            pendingAnimationKey = key;
            pendingAnimationLongPress = false;
            return;
        }
        handleKeyEvent(key, false);
    }

    @Override // dev.bbkb.ime.keyboard.SimplifiedKeyboardView.onKeyEventListener
    public void onKeyLongPress(Key key) {
        if (isAnimating()) {
            pendingAnimationKey = key;
            pendingAnimationLongPress = true;
            return;
        }
        handleKeyEvent(key, true);
    }

    private KeyboardBuilder createKeyboardBuilder() {
        // The UIM bar is the one board keyboard that is NOT keyboard-height: it is one
        // suggestion-strip row tall, so it passes its own geometry rather than using
        // BoardKeyboardFactory's standard board size.
        Resources resources = this.context.getResources();
        return BoardKeyboardFactory.builder(
                this.context,
                SubtypeFactory.createSubtype(Locale.ENGLISH.toString(), "unified_input_menu"),
                ResourceConfigManager.getScreenWidthPixels(resources),
                ResourceConfigManager.getSuggestionsStripHeight(resources));
    }
    
    /**
     * Phase 2 Optimization: Lazy registration of input board components.
     * Registers components only when needed, not during construction.
     */
    private void ensureComponentsRegistered() {
        if (!componentsRegistered) {
            Logger.debug("UIMKeyHandler", "Registering components...");
            registerComponents();
            componentsRegistered = true;
            Logger.debug("UIMKeyHandler", "Components registered. Map size: " + this.componentMap.size());
        }
    }
    
    /**
     * Phase 3 Optimization: Get or build keyboard with caching.
     * Reuses cached keyboard when possible to avoid redundant XML parsing.
     * The actual keyboard building (XML parsing) is expensive and done lazily here.
     * Public because AuxBarManager's no-argument showUnifiedInputMenu() fallback must
     * come through here too — a bar built anywhere else would skip the user's menu order.
     */
    public Keyboard getOrBuildKeyboard() {
        if (cachedKeyboard == null) {
            Keyboard keyboard = this.keyboardBuilder.getKeyboardForShift(138, true);
            applyMenuOrder(keyboard);
            cachedKeyboard = keyboard;
        }
        return cachedKeyboard;
    }

    /**
     * Rebuilds the four toggle slots of the freshly parsed UIM bar to match
     * pref_uim_menu_order. The slots are the bar's keys in x order minus the fixed
     * center alphabet key. Prototypes for every toggle — including ones absent from
     * the default bar XML — are parsed from kbd_unified_input_menu_pool.xml and cloned
     * onto slot geometry with Key(prototype, slot). No-op when the stored order already
     * matches the parsed XML.
     */
    private void applyMenuOrder(Keyboard keyboard) {
        List<Integer> desired = UimMenuOrder.getShownKeyCodes(PrefsManager.INSTANCE.getPrefs(this.context));
        List<Key> slots = new ArrayList<>(keyboard.getKeys());
        java.util.Collections.sort(slots, (a, b) -> Integer.compare(a.getX(), b.getX()));
        for (Iterator<Key> it = slots.iterator(); it.hasNext(); ) {
            if (it.next().getCode() == -3) {
                it.remove();
            }
        }
        if (slots.size() != desired.size()) {
            Logger.warn(TAG, "UIM bar has " + slots.size() + " toggle slots, expected "
                    + desired.size() + " — keeping the XML order");
            return;
        }
        boolean matchesXml = true;
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i).getCode() != desired.get(i)) {
                matchesXml = false;
                break;
            }
        }
        if (matchesXml) {
            return;
        }
        Keyboard pool = this.keyboardBuilder.getKeyboardForShift(UIM_POOL_ELEMENT_ID, false);
        boolean replacedAny = false;
        for (int i = 0; i < slots.size(); i++) {
            Key slotKey = slots.get(i);
            int code = desired.get(i);
            if (slotKey.getCode() == code) {
                continue;
            }
            Key prototype = pool.getKeyByCode(code);
            if (prototype == null) {
                Logger.warn(TAG, "No UIM pool prototype for keycode " + code);
                continue;
            }
            replacedAny |= keyboard.replaceKey(slotKey, new Key(prototype, slotKey));
        }
        if (replacedAny) {
            keyboard.rebuildProximityGrid();
        }
    }

    /**
     * pref_uim_menu_order changed: rebuild the bar (and re-show it if visible).
     * updateTheme() already does exactly that invalidate-rebuild-reapply cycle.
     */
    public void onMenuOrderChanged() {
        updateTheme(this.context);
    }

        void setAnimating(boolean isAnimating) {
        this.isAnimating = isAnimating;
        if (!isAnimating && pendingAnimationKey != null) {
            Key key = pendingAnimationKey;
            boolean longPress = pendingAnimationLongPress;
            pendingAnimationKey = null;
            pendingAnimationLongPress = false;
            handleKeyEvent(key, longPress);
        }
    }

    private boolean isAnimating() {
        return this.isAnimating;
    }

    public void registerComponent(UnifiedInputBoardComponent component) {
        if (component != null) {
            this.componentMap.put(Integer.valueOf(component.getKeyCode()), component);
        }
    }

    public void hideOtherComponents(int exceptKeyCode) {
        // Hide all showing components EXCEPT the one identified.
        // The activeComponent exclusion was removed (UIM-01 fix): the exclusive-open
        // invariant in dispatchBoardAction() now handles closing the active board before we get here.
        final int activeKeyCode = this.boardCoordinator.activeBoard();
        boolean sweptTheActiveBoard = false;
        for (Map.Entry<Integer, UnifiedInputBoardComponent> entry : this.componentMap.entrySet()) {
            UnifiedInputBoardComponent value = entry.getValue();
            if (value != null && entry.getKey().intValue() != exceptKeyCode && value.isShowing()) {
                Logger.debug(TAG, "hideOtherComponents(" + exceptKeyCode + "): hiding keycode=" + entry.getKey());
                value.hide();
                sweptTheActiveBoard |= entry.getKey().intValue() == activeKeyCode;
            }
        }
        // Defect 8, third finding: this sweep reaches straight into each component's hide(),
        // bypassing the normal close paths, and used to report nothing — so afterwards the
        // coordinator still claimed a board it had just taken down and getActiveComponent()
        // handed back a board whose view was gone. That is precisely the coordinator/view desync
        // the toggle above has to survive, manufactured by the UIM itself, and only the explicit
        // reconcile in hideKeyboardOnKeyboardStateChange repaired it. Report the close here so the
        // sweep stops making the state it is supposed to be defending.
        //
        // Recursion is bounded: setActiveComponent -> updateKeyHighlightedStates can call
        // hideOtherComponents(-37) again, but activeBoard is already NO_BOARD by then (the notify
        // runs before the highlight pass) and NO_BOARD matches no registered keycode, so the
        // nested call can never report again.
        if (sweptTheActiveBoard) {
            setActiveComponent(null);
        }
    }

    public void hideAllComponents() {
        for (UnifiedInputBoardComponent interfaceC1012j : this.componentMap.values()) {
            if (interfaceC1012j != null) {
                interfaceC1012j.hide();
            }
        }
        // All components hidden, clear active state
        setActiveComponent(null);
    }

    public boolean isSlideboardShowing() {
        return KeyboardSwitcher.getInstance().getSlideboardManager().getTranslationX() != 0.0f;
    }

    public void showAndRefreshForSlideboard() {
        show(true);
        updateAlphabetKeyForSlideboard();
    }

    private boolean isSlideboardActiveAndNoBoardOpen() {
        return isSlideboardShowing() && !isAnyBoardShowing();
    }

    public void updateAlphabetKeyForSlideboard() {
        Keyboard keyboard = this.keyboardView.getKeyboard();
        if (keyboard == null) {
            return;
        }
        if (isSlideboardActiveAndNoBoardOpen()) {
            Key alphabetKey = keyboard.getKeyByCode(-3);
            if (alphabetKey != null) {
                Key settingsKey = createSlideboardSettingsKey(alphabetKey, keyboard);
                settingsKey.setActive(true);  // Set key's internal isActive to true for full brightness
                swapCentreKey(keyboard, alphabetKey, settingsKey);
            }
        } else {
            swapCentreKey(keyboard, keyboard.getKeyByCode(-26), this.cachedAlphabetKey);
        }
        updateKeyHighlightedStates(this.keyboardView.getKeyboard());
        // Phase 1 Optimization: Batch invalidations
        scheduleInvalidation();
    }

    /** Swap the bar's centre key, rebuilding the hit grid only if the swap actually happened. */
    private static void swapCentreKey(Keyboard keyboard, Key from, Key to) {
        if (from != null && keyboard.replaceKey(from, to)) {
            keyboard.rebuildProximityGrid();
        }
    }

    private Key createSlideboardSettingsKey(Key templateKey, Keyboard parent) {
        return new Key(new MoreKeySpec(templateKey.getLabel(), KeyboardIconSet.getIconId("slideboard_settings_key"), -26, templateKey.getKeySpecOutputText()), MoreKeySpec.getEmpty(), templateKey.getLongPressKeyHintPosition(), templateKey.getHintLabel(), templateKey.getLabelFlags(), templateKey.getBackgroundType(), templateKey.getX(), templateKey.getY(), templateKey.getWidth() + parent.mHorizontalGap, templateKey.getHeight() + parent.mVerticalGap, parent.mHorizontalGap, parent.mVerticalGap, templateKey.getOutputText(), templateKey.getMoreKeys(), templateKey.getKeyLabelSet(), templateKey.getMoreKeysFlags(), templateKey.getActionFlags(), templateKey.getScanCode());
    }

    public boolean isAnyBoardShowing() {
        for (UnifiedInputBoardComponent interfaceC1012j : this.componentMap.values()) {
            if (interfaceC1012j != null && interfaceC1012j.isShowing()) {
                return true;
            }
        }
        return false;
    }

    public void showEmojiBoard() {
        hideOtherComponents(-11);
        UnifiedInputBoardComponent interfaceC1012j = this.componentMap.get(-11);
        if (interfaceC1012j != null) {
            interfaceC1012j.show();
        }
        refresh();
    }

    /**
     * Hand -14 ("board closed") to the keyboard switcher so the main keyboard comes back with the
     * right shift state. Not a component call at all despite the name — the boards themselves are
     * taken down by {@link #closeBoard(int)} / {@link #hideAllComponents()}.
     */
    public void closeActiveComponent() {
        InputLogic inputLogic = this.imeService.getInputLogic();
        this.keyboardSwwitcher.onInputCodeChanged(-14,
                inputLogic.getCapsMode(SettingsManager.getInstance().getSettingsValues()),
                inputLogic.getConfigParserResult());
    }

    public void onMainKeyboardViewVisibilityChanged(int visibility) {
        if (visibility == 8) {
            closeActiveComponent();
        }
    }

    public void updateKeyHighlightedStates(Keyboard c0965e) {
        UnifiedInputBoardComponent value;
        if (c0965e == null) {
            Logger.error(TAG, "invoked with null keyboard");
            return;
        }
        
        boolean keyboardIconHighlighted = true; // true when no board is open
        boolean foundActiveBoard = false;

        // The per-component pass — painting AND the two force-closes it performs — runs only
        // while the bar is up. When keyboardView is GONE the loop is skipped, which is what
        // leaves keyboardIconHighlighted true and suppresses the hideOtherComponents(-37) below:
        // in that state it would hide ALL showing components, including a board that was just
        // opened before the UIM bar became visible.
        final boolean barIsUp = this.keyboardView.getVisibility() != View.GONE;
        if (barIsUp) {
            for (Map.Entry<Integer, UnifiedInputBoardComponent> entry : this.componentMap.entrySet()) {
                int iIntValue = entry.getKey().intValue();
                value = entry.getValue();
                if (value == null || iIntValue == InlineAutofillManager.KEY_CODE_AUTOFILL) {
                    // The inline-autofill strip is not a bar toggle: it has no key here, it is the
                    // one component the sweep below spares by name, and it has never counted as
                    // an open board. Skipping it keeps all three of those true.
                    continue;
                }
                // Whether a board is OPEN is decided from the component, never from whether its
                // toggle happens to be on the bar. pref_uim_menu_order lets the user move a toggle
                // off the four bar slots (the number pad in voice's slot, say), but the board itself
                // stays reachable: the physical mic and multifunction keys open it through the
                // coordinator, and every open path reports through setActiveComponent, which ends
                // here. Deciding from the key used to skip such a board entirely, so the pass
                // concluded "nothing is open" and the hideOtherComponents(-37) sweep below closed
                // the board the press had just opened — for voice, recognizer cancelled and panel
                // gone on every press. Only the PAINTING needs the key; a toggle that is not on the
                // bar simply has nothing to paint.
                Key keyM6604b = c0965e.getKeyByCode(iIntValue);
                boolean shouldHighlight = false;
                boolean isShowing = value.isShowing();
                boolean isEnabled = value.isEnabled();
                Logger.debug(TAG, "updateKeyHighlightedStates: keycode=" + iIntValue
                        + " isShowing=" + isShowing + " isEnabled=" + isEnabled
                        + " onBar=" + (keyM6604b != null) + " -> setActive=" + isEnabled);

                if (isShowing && isEnabled) {
                    if (foundActiveBoard) {
                        // UIM-10: Exclusive-open violation detected — auto-heal
                        Logger.warn(TAG, "Multiple boards showing! Force-closing extra: keycode=" + iIntValue);
                        value.hide();
                    } else {
                        shouldHighlight = true;
                        keyboardIconHighlighted = false;
                        foundActiveBoard = true;
                    }
                } else if (isShowing && !isEnabled) {
                    value.hide();
                }

                if (keyM6604b != null) {
                    keyM6604b.setHighlighted(shouldHighlight);
                    keyM6604b.setActive(isEnabled);
                } else {
                    Logger.debug(TAG, String.format(Locale.getDefault(), "Key with code %d is not on the UIM bar (menu order); nothing to paint", Integer.valueOf(iIntValue)));
                }
            }
        }
        // The centre alphabet key is painted either way — while the bar is GONE the states are
        // set on the Keyboard object for when it becomes visible. Only the sweep is gated.
        Key keyM6604b2 = c0965e.getKeyByCode(-3);
        if (keyM6604b2 != null) {
            keyM6604b2.setActive(true);
            keyM6604b2.setHighlighted(keyboardIconHighlighted);
            if (barIsUp && keyboardIconHighlighted) {
                hideOtherComponents(-37);
            }
        } else if (barIsUp) {
            Logger.warn(TAG, "The alphabet key was not found in the keyboard layout");
        }
    }

    private void registerComponents() {
        FccController c1008fM4107av = this.imeService.getFccController();
        if (c1008fM4107av != null) {
            registerComponent(c1008fM4107av);
        }
        VoiceInputController c1122bM4080aH = this.imeService.getVoiceInputController();
        if (c1122bM4080aH != null) {
            registerComponent(c1122bM4080aH);
        }
        ClipboardController c1005cM4108aw = this.imeService.getClipboardController();
        if (c1005cM4108aw != null) {
            registerComponent(c1005cM4108aw);
        }
        NumberPadController numberPadController = this.imeService.getNumberPadController();
        if (numberPadController != null) {
            registerComponent(numberPadController);
        }
        // Emoji: the controller holds no view, so unlike the old arrangement — where
        // EmojiPalettesView WAS the component and had to be peeked at here, then registered from
        // the lazy getter at first inflation — it can just be registered with the rest. The
        // ViewStub deferral (audit IB-1) is untouched; nothing here inflates anything.
        registerComponent(new EmojiBoardController(this.imeService));
        
        // Register inline autofill manager for Android 11+ autofill suggestions
        if (InlineAutofillManager.isSupported()) {
            registerComponent(InlineAutofillManager.getInstance(this.context));
        }
    }
    
    /**
     * Refreshes the unified input bar to reflect the current theme.
     * This should be called when the system theme changes to ensure the unified input bar
     * uses the correct theme colors.
     * 
     * The themed context is constant (one style tree); rebuilding the keyboard makes
     * every key re-pull its colors from KeyboardColorManager's current palette.
     */
    public void updateTheme(Context context) {
        // Phase 3 Optimization: Invalidate keyboard cache on theme change
        // This forces rebuild with correct theme colors
        cachedKeyboard = null;
        
        // Refresh the keyboard layout to apply new theme
        // The context already uses auto-detection, so this will pick up the new theme
        if (this.keyboardView != null && this.keyboardView.getKeyboard() != null) {
            // Rebuild keyboard with new theme
            Keyboard newKeyboard = getOrBuildKeyboard();
            this.keyboardView.setKeyboard(newKeyboard);
            updateKeyHighlightedStates(newKeyboard);
            // Apply color tint to icon set
            newKeyboard.mIconsSet.applyColorTint();
        }
        
        // Phase 1 Optimization: Single batched invalidation at end
        scheduleInvalidation();
    }
    
    /**
     * Set the currently active input board component and refresh icon colors.
     * 
     * @param component The active component, or null if no component is active
     */
    public void setActiveComponent(UnifiedInputBoardComponent component) {
        // Single bookkeeping funnel for "which board is open": every open/close path
        // ends here (dispatch branches, the KeyboardState emoji machine, the voice
        // paths), and the coordinator's activeBoard — the ONLY copy of that state —
        // is mutated exclusively from these reports. Syncing nulls became safe once
        // board keys were exempted from the text-key dismissal (phase 2): nothing
        // clears board state mid-press anymore.
        if (component != null) {
            this.boardCoordinator.notifyBoardOpened(component.getKeyCode());
        } else {
            this.boardCoordinator.notifyBoardClosed();
        }
        dumpUimState("setActiveComponent", component != null ? component.getKeyCode() : 0);
        
        // Update key.isHighlighted state - this is what KeyboardView.drawKeyContent() uses
        // to determine icon tint during rendering
        SimplifiedKeyboardView displayedView = getDisplayedView();
        Keyboard keyboard = displayedView != null ? displayedView.getKeyboard() : null;
        if (keyboard != null) {
            updateKeyHighlightedStates(keyboard);
        }
        
        // Trigger redraw
        scheduleInvalidation();
    }
    
    /**
     * Get the currently active input board component.
     * 
     * @return The active component, or null if no component is active
     */
    /**
     * Report an open by keycode, for callers that know which board opened but do not hold the
     * component — the KeyboardState emoji machine, which drives the board from the other side.
     * A no-op if nothing is registered under that keycode.
     */
    public void setActiveComponentByKeyCode(int keyCode) {
        UnifiedInputBoardComponent component =
                this.componentMap != null ? this.componentMap.get(Integer.valueOf(keyCode)) : null;
        if (component != null) {
            setActiveComponent(component);
        }
    }

    /**
     * Report that <em>{@code keyCode}'s</em> board has closed — the SCOPED counterpart of
     * {@code setActiveComponent(null)}, for a close path that knows which board it is taking down.
     *
     * <p>{@code setActiveComponent(null)} says "nothing is open at all", and the coordinator's
     * {@code activeBoard} is the only copy of that state, so a close path that calls it while a
     * DIFFERENT board is open makes the coordinator forget a board that is still on screen. The
     * next press of that board's key then finds {@code NO_BOARD} and takes the OPEN path — the
     * press that should have closed the board does nothing (or re-opens it), which is defect 8's
     * pathology arriving from the other side.
     *
     * <p>That is exactly what the physical mic key hit on the Key2. {@code KeyboardSwitcher
     * .setKeyboard} calls {@code hideEmojiKeyboard()} on EVERY keyboard rebuild, and that method
     * ended with an unscoped {@code setActiveComponent(null)}. Voice is the one board the user
     * keeps open while text is committed (dictation results go through
     * {@code BlackBerryIME.onTextInput}, which ends in {@code onInputCodeChanged} → the shift
     * chain → {@code setAlphabetKeyboard} → {@code setKeyboard}), so the first dictated word made
     * the coordinator forget the open voice board while the panel — which
     * {@code hideEmojiKeyboard} does not touch — stayed up. The number pad, the other board typed
     * from, was one rebuild away from the same fate.
     *
     * <p>The painting half runs either way, so a rebuild repaints the bar exactly as it did when
     * this was an unconditional {@code setActiveComponent(null)}; only the STATE mutation is
     * scoped to the board the caller is actually closing.
     */
    public void reportBoardClosed(int keyCode) {
        if (this.boardCoordinator.activeBoard() == keyCode) {
            setActiveComponent(null);
            return;
        }
        SimplifiedKeyboardView displayedView = getDisplayedView();
        Keyboard keyboard = displayedView != null ? displayedView.getKeyboard() : null;
        if (keyboard != null) {
            updateKeyHighlightedStates(keyboard);
        }
        scheduleInvalidation();
    }

    public UnifiedInputBoardComponent getActiveComponent() {
        int kc = this.boardCoordinator.activeBoard();
        return (kc != UnifiedBoardCoordinator.NO_BOARD && this.componentMap != null)
                ? this.componentMap.get(Integer.valueOf(kc))
                : null;
    }

    // ===== UnifiedBoardCoordinator.BoardHost =====
    // The coordinator owns the single toggle decision for external/physical board
    // keys, deciding from the authoritative active-board state below — never from a
    // component's isShowing(), which a key-down side effect can clobber.

    /**
     * The OPEN half. Reached from the coordinator (bar taps, physical board keys, gestures,
     * accessibility) once it has decided this board should be open.
     *
     * <p>Ensures components are registered (lazy init), ensures the UIM bar itself is visible —
     * transitioning the AuxBar out of suggestions mode — and then dispatches. Raising the bar
     * first is what makes the dispatch's key painting see the newly-opened board: with the
     * {@code hideOtherComponents(-37)} guard in {@code updateKeyHighlightedStates}, no component
     * is force-hidden by the GONE→VISIBLE transition.
     */
    @Override
    public void openBoard(int keyCode) {
        dumpUimState("openBoard", keyCode);
        ensureComponentsRegistered();
        if (!isShowing()) {
            show(false);
        }
        dispatchBoardAction(keyCode);
    }

    /** The CLOSE half, matching {@link #openBoard(int)} over the same prologue. */
    @Override
    public void closeBoard(int keyCode) {
        beginBoardTransition();
        // Close by keycode with per-board semantics, not via activeComponent — a
        // key-down side effect may have already hidden the view and cleared both
        // activeComponent and the board's own mode flag (isInVoiceMode /
        // isInEmojiMode), in which case each branch below is a safe no-op.
        UnifiedInputBoardComponent c =
                this.componentMap != null ? this.componentMap.get(Integer.valueOf(keyCode)) : null;
        // The -42 strip restore below undoes what OPENING the cursor board did, so it is only
        // correct when this board was actually open; the original APK ran it inside its
        // "board is showing" branch. Captured before the teardown below erases both signals.
        // Open = the coordinator's active board (how every production close reaches here, and
        // still true when a side effect clobbered the view down) OR the component's own view
        // (a board shown without being reported to the coordinator).
        boolean wasOpen = this.boardCoordinator.activeBoard() == keyCode
                || (c != null && c.isShowing());
        if (keyCode == -27) {
            // Voice: terminate recognition unconditionally, not gated on the mode
            // flag — a key-down side effect may have hidden the view and cleared
            // isInVoiceMode while a recognizer session is still winding down.
            // cancelVoiceInput() is idempotent (cancels the recognizer, resets the
            // mode, hides the view), guaranteeing panel-closed ⇒ mic released.
            if (c instanceof VoiceInputController) {
                ((VoiceInputController) c).cancelVoiceInput();
            } else if (c != null && c.isShowing()) {
                c.hide();
            }
        } else if (c != null) {
            // Emoji included: EmojiBoardController.hide() carries the state-machine exit and the
            // "mode flag already cleared but the palettes are still up" fallback that used to be
            // a branch here, and it is unconditional for the same reason (§5.6 item 4). Every
            // other board's hide() is guarded on its own isShowing(), so this stays a safe no-op.
            c.hide();
        }
        // Report the close through the bookkeeping funnel (idempotent — the per-board
        // branches above may already have cascaded here via their own close paths).
        setActiveComponent(null);
        if (keyCode == -42 && wasOpen && this.imeService.isOnScreenKeyboardVisible()) {
            this.imeService.restoreSuggestionStrip(true, true);
        }
        refresh();
    }

    /**
     * External/physical board-key entry point: toggle {@code keyCode}'s board through
     * the coordinator so a second press closes it (fixes the reopen bug where a
     * key-down side effect hid the view before key-up re-derived visibility).
     */
    public void requestBoard(int keyCode) {
        this.boardCoordinator.requestBoard(keyCode);
    }

    public UnifiedBoardCoordinator getBoardCoordinator() {
        return this.boardCoordinator;
    }
    
    /**
     * Get the SimplifiedKeyboardView that's actually being displayed.
     * When AuxBarManager is used, it's the sharedKeyView from AuxBarView.
     * Otherwise, fallback to keyboardView.
     */
    private SimplifiedKeyboardView getDisplayedView() {
        AuxBarManager auxBarManager = bar();
        if (auxBarManager != null) {
            SimplifiedKeyboardView sharedView = auxBarManager.getDisplayedKeyView();
            if (sharedView != null) {
                return sharedView;
            }
        }
        return this.keyboardView;
    }
    
    /**
     * Phase 1 Optimization: Schedule batched invalidation to reduce layout passes.
     * Multiple calls within ClipboardItem short time window will be coalesced into ClipboardItem single invalidate().
     */
    private void scheduleInvalidation() {
        if (!pendingInvalidation) {
            pendingInvalidation = true;
            invalidationHandler.post(new Runnable() {
                @Override
                public void run() {
                    pendingInvalidation = false;
                    // Invalidate the ACTUAL displayed view, not keyboardView
                    SimplifiedKeyboardView displayedView = getDisplayedView();
                    if (displayedView != null) {
                        displayedView.invalidate();
                    }
                }
            });
        }
    }
}

package dev.bbkb.ime.keyboard.inputboard;

import dev.bbkb.ime.BuildConfig;
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
import dev.bbkb.ime.core.settings.util.SettingsValues;
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
import dev.bbkb.ime.keyboard.state.CrossAxisRules;
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

    private final KeyboardBuilder keyboardBuilder;

    private final BlackBerryIME imeService;

    private final KeyboardSwitcher keyboardSwwitcher;

    private boolean isAnimating;

    // Safety timeout so isAnimating cannot get stuck.
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
     * The only strong reference to the handler's callback (the handler holds it weakly). Without it
     * the callback is collected and the bar's keys stay disabled.
     */
    private final UnifiedInputBoardHandler.Callback uimHandlerCallback;

    private final List<Boolean> enabledStates;

    private Key cachedAlphabetKey;
    
    /**
     * Owns which board is open; {@link #getActiveComponent()} derives from it and every open or
     * close path reports through {@link #setActiveComponent}.
     */
    private final UnifiedBoardCoordinator boardCoordinator = new UnifiedBoardCoordinator(this);

    /* A single tap received during animation, replayed when it ends. */
    private Key pendingAnimationKey = null;
    private boolean pendingAnimationLongPress = false;

    private boolean pendingInvalidation = false;
    private final android.os.Handler invalidationHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    private boolean componentsRegistered = false;

    private Keyboard cachedKeyboard = null;

    /* The keyboard currently on the bar. */
    private Keyboard displayedKeyboard = null;

    @Override
    public void onKeyDown(Key key) {
        // Bar keys act on key-up.
    }

    /** The AuxBar, or null before it is installed (then the direct-visibility fallback is used). */
    private AuxBarManager bar() {
        return this.imeService.getUiCoordinator().getAuxBarManager();
    }

    /** Raise the bar with {@code keyboard} on it, through the AuxBar or the fallback. */
    private void showBar(Keyboard keyboard) {
        AuxBarManager auxBarManager = bar();
        if (auxBarManager != null) {
            auxBarManager.showUnifiedInputMenu(keyboard);
        } else {
            this.keyboardView.setKeyboard(keyboard);
            this.keyboardView.setVisibility(View.VISIBLE);
        }
    }

    /** Take the bar down, through the AuxBar or the fallback. */
    private void hideBar() {
        AuxBarManager auxBarManager = bar();
        if (auxBarManager != null) {
            auxBarManager.hideUnifiedInputMenu();
        } else {
            this.keyboardView.setVisibility(View.GONE);
        }
    }

    /**
     * Paint key states, publish {@code keyboard}, raise the bar, and paint again. The first paint
     * runs while the view is GONE and skips the per-component branch, so a cached keyboard could
     * keep stale states (dead keys on first tap); the second runs with the view VISIBLE.
     */
    private void publishBar(Keyboard keyboard) {
        updateKeyHighlightedStates(keyboard);
        this.displayedKeyboard = keyboard;
        showBar(keyboard);
        updateKeyHighlightedStates(keyboard);
    }

    /**
     * Debug dump of UIM state. This overload builds the label only after the isLoggable check,
     * so the hot open/close path pays nothing on release builds.
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

    /** Debug check: warns when {@link #isShowing()} disagrees with the AuxBar state. */
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
        // Colors come from KeyboardColorManager at draw time.
        this.context = BoardKeyboardFactory.themedContext(context);
        this.keyboardBuilder = createKeyboardBuilder();
        this.imeService = blackBerryIME;
        this.keyboardSwwitcher = KeyboardSwitcher.getInstance();
        setAnimating(false);
        this.componentMap = new ConcurrentHashMap();
        // Components register lazily on first show.
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
                // A board may have opened since the save, making the saved states stale. Do not skip
                // the restore (every key would stay disabled); re-derive the states from the board.
                UnifiedInputBoardComponent activeForRestore =
                        UnifiedInputBoardManager.this.getActiveComponent();
                if (activeForRestore != null) {
                    if (BuildConfig.DEBUG) {
                        Logger.debug(TAG, "restoreKeyStates: activeComponent="
                            + activeForRestore.getKeyCode()
                            + " — re-deriving key states from current board state");
                    }
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

    /**
     * @param i translation Y in pixels
     * @param i2 translation Z in pixels
     */
    public void setTranslation(int i, int i2) {
        this.keyboardView.setTranslationY(i);
        this.keyboardView.setTranslationZ(i2);
    }

    public void destroy() {
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
     * Whether {@code keyCode}'s board has its view up; the predicate {@code CrossAxisRules} uses
     * for layout-change exemptions. Not filtered to panel boards.
     */
    public boolean isBoardViewShowing(int keyCode) {
        if (this.componentMap == null) {
            return false;
        }
        UnifiedInputBoardComponent component = this.componentMap.get(Integer.valueOf(keyCode));
        return component != null && component.isShowing();
    }

    /**
     * The first up board that survives a layout change (list in
     * {@link CrossAxisRules#boardsExemptFromCommitRebuild()}), or {@link UnifiedBoardCoordinator#NO_BOARD}.
     * An exemption skips the whole sweep, not just that board.
     */
    private int findShowingExemptBoard() {
        return CrossAxisRules.firstExemptBoardUp(this::isBoardViewShowing);
    }

    /**
     * On a layout switch, sweep the boards closed unless an exempt board is up. The gate is
     * repeated here for direct callers such as {@code BlackBerryIME.hideUnifiedInputBoard}.
     */
    public void hideKeyboardOnKeyboardStateChange() {
        // Must ask about panel boards only: a typing board is always up, and every committed
        // character changes layout.
        if (isShowing() && isPanelBoardShowing()) {
            int exempt = findShowingExemptBoard();
            if (exempt != UnifiedBoardCoordinator.NO_BOARD) {
                Logger.debug(TAG, "hideKeyboardOnKeyboardStateChange: board "
                        + exempt + " is exempt; not sweeping");
                return;
            }
            hideOtherComponents(-37);
            // The sweep skips (and never reports) an active board whose view was already down.
            UnifiedInputBoardComponent active = getActiveComponent();
            if (active != null && !active.isShowing()) {
                setActiveComponent(null);
            }
            refresh();
        }
    }

    public void show(boolean force) {
        if (!SettingsManager.isUimEnabled(this.context)) {
            return;
        }
        if (!isAnimating() && (!this.keyboardView.isShown() || force)) {
            Logger.debug(TAG, "Showing Input Board Bar");
            ensureComponentsRegistered();
            
            setAnimating(false);

            Keyboard c0965eM6734a = getOrBuildKeyboard();
            this.cachedAlphabetKey = c0965eM6734a.getKeyByCode(-3);

            // Tint before showing.
            c0965eM6734a.mIconsSet.applyColorTint();

            publishBar(c0965eM6734a);

            this.imeService.getUiUpdateHandler().cancelPendingSuggestionUpdates();

            scheduleInvalidation();
            dumpUimState("show.EXIT");
            assertVisibilityConsistency("show");
            return;
        }
        refresh();
    }

    /**
     * Show half of the "Show or hide the input menu" key (the caller already set
     * {@link SettingsValues#setInputMenuRequestedByKey}). A panel board opened while the menu was off
     * is adopted by the coordinator, so its next press closes it instead of being a dead press.
     *
     * @return whether the menu bar is up afterwards
     */
    public boolean showMenuForKey() {
        show(true);
        if (!isShowing()) {
            return false;
        }
        if (getActiveComponent() == null && this.componentMap != null) {
            for (Map.Entry<Integer, UnifiedInputBoardComponent> entry : this.componentMap.entrySet()) {
                int keyCode = entry.getKey().intValue();
                UnifiedInputBoardComponent component = entry.getValue();
                // The autofill strip is not a board the bar toggles; see updateKeyHighlightedStates.
                if (component != null && keyCode != InlineAutofillManager.KEY_CODE_AUTOFILL
                        && CrossAxisRules.isPanelBoard(keyCode) && component.isShowing()) {
                    setActiveComponent(component);
                    break;
                }
            }
        }
        dumpUimState("showMenuForKey.EXIT");
        return true;
    }

    public boolean isShowing() {
        // The AuxBar state is the source of truth for bar visibility.
        AuxBarManager auxBarManager = bar();
        if (auxBarManager != null) {
            return auxBarManager.getCurrentState() == AuxBarState.UNIFIED_INPUT_MENU;
        }
        return this.keyboardView.getVisibility() == View.VISIBLE;
    }

    public void showWithAnimation(final View suggestionStripView) {
        if (!SettingsManager.isUimEnabled(this.context)) {
            return;
        }
        if (isAnimating() || this.keyboardView.isShown()) {
            return;
        }
        ensureComponentsRegistered();
        
        Resources resources = this.context.getResources();

        Keyboard c0965eM6734a = getOrBuildKeyboard();
        this.imeService.uiUpdateHandler.cancelPendingSuggestionUpdates();

        publishBar(c0965eM6734a);

        c0965eM6734a.mIconsSet.applyColorTint();
        this.keyboardView.setAlpha(0.0f);
        float fM5585a = ResourceConfigManager.getScreenWidthPixels(this.context.getResources());
        this.keyboardView.setTranslationX(fM5585a);
        this.keyboardView.setTranslationY(suggestionStripView != null && suggestionStripView.isShown() ? suggestionStripView.getHeight() : 0);
        this.keyboardView.animate().translationXBy(-fM5585a).setDuration(resources.getInteger(R.integer.config_input_board_bar_slide_in_animation)).setInterpolator(new AccelerateDecelerateInterpolator()).alpha(1.0f).setListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animator) {
                UnifiedInputBoardManager.this.setAnimating(true);
                UnifiedInputBoardManager.this.invalidationHandler.removeCallbacks(animationSafetyReset);
                UnifiedInputBoardManager.this.invalidationHandler.postDelayed(animationSafetyReset, ANIMATION_TIMEOUT_MS);
            }

            @Override
            public void onAnimationEnd(Animator animator) {
                UnifiedInputBoardManager.this.invalidationHandler.removeCallbacks(animationSafetyReset);
                UnifiedInputBoardManager.this.setAnimating(false);
                UnifiedInputBoardManager.this.keyboardView.setTranslationY(0.0f);
                if (suggestionStripView != null) {
                    suggestionStripView.setVisibility(View.GONE);
                }
            }

            @Override
            public void onAnimationCancel(Animator animator) {
                UnifiedInputBoardManager.this.invalidationHandler.removeCallbacks(animationSafetyReset);
                UnifiedInputBoardManager.this.setAnimating(false);
            }
        });
    }

    public void hide() {
        Logger.debug(TAG, "Hiding Input Board Bar via AuxBarManager");
        // Any hide ends a menu the "Show or hide the input menu" key or the hamburger raised.
        SettingsValues.setInputMenuRequestedByKey(false);
        dev.bbkb.ime.core.ime.InputViewCoordinator ui = this.imeService.getUiCoordinator();
        if (ui != null) {
            ui.onMenuTakenDown();
        }
        // Called once or twice per strip update; skip the full pass when nothing is up.
        if (isFullyHidden()) {
            return;
        }
        this.keyboardView.animate().cancel();
        this.invalidationHandler.removeCallbacks(animationSafetyReset);
        setAnimating(false);

        hideBar();

        this.displayedKeyboard = null;

        hideAllComponents();
        dumpUimState("hide.EXIT");
        assertVisibilityConsistency("hide");
    }

    /** No bar, no animation, no displayed keyboard and no panel board open: hide() is a no-op. */
    private boolean isFullyHidden() {
        if (isAnimating() || isShowing() || this.displayedKeyboard != null
                || this.boardCoordinator.activeBoard() != UnifiedBoardCoordinator.NO_BOARD) {
            return false;
        }
        return !isPanelBoardShowing();
    }

    public void hideView() {
        Logger.debug(TAG, "Hiding Input Board View via AuxBarManager");
        hideBar();
    }

    public void showView() {
        Logger.debug(TAG, "Showing Input Board View via AuxBarManager");
        // Not showBar(): the fallback must only raise the view and keep its current keyboard.
        AuxBarManager auxBarManager = bar();
        if (auxBarManager != null) {
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
        scheduleInvalidation();
        for (UnifiedInputBoardComponent component : this.componentMap.values()) {
            component.onRefresh();
        }
    }

    /**
     * Shared prologue of open and close. Resets a PKB in symbol mode to alphabet so the alphabet is
     * loaded when the panel is dismissed (it must run before the open or close, since it re-enters
     * the keyboard loader), and cancels a pending key-state restore so it cannot hit the new board.
     */
    private void beginBoardTransition() {
        KeyboardSwitcher ks = KeyboardSwitcher.getInstance();
        if (ks != null) {
            ks.clearPkbSymbolMode();
        }
        this.uimHandler.cancelPendingMessages();
    }

    /**
     * The open half of the toggle (from {@link #openBoard(int)}), plus bar keys that are not
     * boards (-3 alphabet, -26/-45 settings, unregistered codes). It never re-decides open versus
     * close from view state: the board is opened unconditionally (show() is guarded) and the
     * coordinator is set from the result.
     *
     * @param iM6232c the bar key's code
     */
    private void dispatchBoardAction(int iM6232c) {
        dumpUimState("dispatchBoardAction.ENTRY", iM6232c);
        beginBoardTransition();
        // Exclusive open. Only panel boards can be active, so this never hides the keyboard itself.
        UnifiedInputBoardComponent previousActive = getActiveComponent();
        if (previousActive != null && previousActive.getKeyCode() != iM6232c) {
            // Not gated on isShowing(): a board whose view was clobbered down must still be
            // cleared from the coordinator (hide() has its own guard).
            Logger.debug(TAG, "Exclusive-open: closing active board " + previousActive.getKeyCode() + " before opening " + iM6232c);
            previousActive.hide();
            setActiveComponent(null);
        }

        hideOtherComponents(iM6232c);
        
        if (iM6232c == -3) {
            setActiveComponent(null);
            closeActiveComponent();
            if (isSlideboardShowing()) {
                showAndRefreshForSlideboard();
            } else {
                // Back to the field's own bar, keeping the strip's words.
                dev.bbkb.ime.core.ime.InputViewCoordinator ui = this.imeService.getUiCoordinator();
                if (ui != null) {
                    ui.putMenuAway();
                }
            }
        } else if (iM6232c == -26) {
            this.imeService.openSlideboardSettings();
        } else if (iM6232c == -45) {
            this.imeService.openSettings();
        } else {
            if (iM6232c == -27) {
                UnifiedInputBoardComponent voiceComponent = panelComponent(iM6232c);
                if (voiceComponent instanceof dev.bbkb.ime.keyboard.inputboard.voice.VoiceInputController) {
                    dev.bbkb.ime.keyboard.inputboard.voice.VoiceInputController voiceController =
                        (dev.bbkb.ime.keyboard.inputboard.voice.VoiceInputController) voiceComponent;
                    // Open never stops a running session.
                    if (!voiceController.isInVoiceMode()) {
                        voiceController.toggleVoiceInput();
                    }
                    // Report the result: with no recognition service voice mode stays off.
                    setActiveComponent(voiceController.isInVoiceMode() ? voiceComponent : null);
                }
            } else {
                // FCC, clipboard, number pad, emoji (driven through KeyboardState), autofill.
                UnifiedInputBoardComponent interfaceC1012j = panelComponent(iM6232c);
                if (interfaceC1012j != null) {
                    interfaceC1012j.show();
                    // Report the result, not the intention: a board can refuse (FCC with a physical
                    // key held, an uninflated ViewStub), and a false "open" makes the next tap dead.
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
        ensureComponentsRegistered();

        int iM6232c = key.getCode();
        if (isLongPress && key.hasLongPressKey()) {
            iM6232c = key.getLongPressCode();
        }

        // Panel board keys go through the coordinator, as physical keys do. The centre -3 key is
        // registered too but must take dispatchBoardAction's branch (close, restore the strip).
        if (CrossAxisRules.isPanelBoard(iM6232c) && this.componentMap != null
                && this.componentMap.containsKey(Integer.valueOf(iM6232c))) {
            this.boardCoordinator.requestBoard(iM6232c);
            return;
        }
        dispatchBoardAction(iM6232c);
    }

    /** @param z true if released over the key that was pressed (a real tap) */
    @Override
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

    @Override
    public void onKeyLongPress(Key key) {
        if (isAnimating()) {
            pendingAnimationKey = key;
            pendingAnimationLongPress = true;
            return;
        }
        handleKeyEvent(key, true);
    }

    private KeyboardBuilder createKeyboardBuilder() {
        // The bar is one suggestion-strip row tall, not keyboard height.
        Resources resources = this.context.getResources();
        return BoardKeyboardFactory.builder(
                this.context,
                SubtypeFactory.createSubtype(Locale.ENGLISH.toString(), "unified_input_menu"),
                ResourceConfigManager.getScreenWidthPixels(resources),
                ResourceConfigManager.getSuggestionsStripHeight(resources));
    }

    private void ensureComponentsRegistered() {
        if (!componentsRegistered) {
            Logger.debug("UIMKeyHandler", "Registering components...");
            registerComponents();
            componentsRegistered = true;
            Logger.debug("UIMKeyHandler", "Components registered. Map size: " + this.componentMap.size());
        }
    }
    
    /**
     * The bar keyboard, parsed once and cached. AuxBarManager's fallback must build through here
     * too, or the bar would skip the user's menu order.
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
     * Rebuild the four toggle slots (bar keys in x order, minus the centre -3) to match
     * pref_uim_menu_order, cloning prototypes from kbd_unified_input_menu_pool.xml onto the slot
     * geometry. No-op when the order already matches the XML.
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

    /** Rebuild (and re-show) the bar; updateTheme() does exactly that cycle. */
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

    /**
     * The registered panel board for {@code keyCode}, or null. Every open and close path uses it,
     * so typing boards can never be opened or closed by the board framework.
     */
    private UnifiedInputBoardComponent panelComponent(int keyCode) {
        return (this.componentMap != null && CrossAxisRules.isPanelBoard(keyCode))
                ? this.componentMap.get(Integer.valueOf(keyCode))
                : null;
    }

    public void hideOtherComponents(int exceptKeyCode) {
        hideComponentsExcept(new int[] {exceptKeyCode});
    }

    /**
     * Hide every showing panel board except those named (e.g.
     * {@link CrossAxisRules#boardsExemptFromTextKey()}). The active board is not spared.
     */
    public void hideComponentsExcept(int[] exceptKeyCodes) {
        final int activeKeyCode = this.boardCoordinator.activeBoard();
        boolean sweptTheActiveBoard = false;
        for (Map.Entry<Integer, UnifiedInputBoardComponent> entry : this.componentMap.entrySet()) {
            UnifiedInputBoardComponent value = entry.getValue();
            // Panel boards only: a typing board is the main keyboard view.
            if (value != null && CrossAxisRules.isPanelBoard(entry.getKey().intValue())
                    && !isExempt(exceptKeyCodes, entry.getKey().intValue())
                    && value.isShowing()) {
                // Guarded here: Logger gates inside the callee, after the string is built.
                if (BuildConfig.DEBUG) {
                    Logger.debug(TAG, "hideComponentsExcept(" + java.util.Arrays.toString(exceptKeyCodes)
                            + "): hiding keycode=" + entry.getKey());
                }
                value.hide();
                sweptTheActiveBoard |= entry.getKey().intValue() == activeKeyCode;
            }
        }
        // The sweep bypasses the close paths, so it must report the close or the coordinator would
        // keep a board that is gone. Recursion via updateKeyHighlightedStates is bounded:
        // activeBoard is already NO_BOARD when it re-enters.
        if (sweptTheActiveBoard) {
            setActiveComponent(null);
        }
    }

    private static boolean isExempt(int[] exceptKeyCodes, int keyCode) {
        for (int exempt : exceptKeyCodes) {
            if (exempt == keyCode) {
                return true;
            }
        }
        return false;
    }

    public void hideAllComponents() {
        for (Map.Entry<Integer, UnifiedInputBoardComponent> entry : this.componentMap.entrySet()) {
            UnifiedInputBoardComponent component = entry.getValue();
            if (component != null && CrossAxisRules.isPanelBoard(entry.getKey().intValue())) {
                component.hide();
            }
        }
        setActiveComponent(null);
    }

    public boolean isSlideboardShowing() {
        return KeyboardSwitcher.getInstance().getSlideboardManager().getTranslationX() != 0.0f;
    }

    public void showAndRefreshForSlideboard() {
        show(true);
        updateAlphabetKeyForSlideboard();
    }

    /** The slideboard's centre key is the settings key while no panel board is open. */
    private boolean isSlideboardActiveAndNoBoardOpen() {
        return isSlideboardShowing() && !isPanelBoardShowing();
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
                settingsKey.setActive(true);  // Full brightness.
                swapCentreKey(keyboard, alphabetKey, settingsKey);
            }
        } else {
            swapCentreKey(keyboard, keyboard.getKeyByCode(-26), this.cachedAlphabetKey);
        }
        updateKeyHighlightedStates(this.keyboardView.getKeyboard());
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

    /**
     * Whether a panel board is up (which board is up is {@code KeyboardSwitcher.activeBoard()}).
     * Answered from each component's own view, not the coordinator. The autofill strip (-37)
     * counts here, though the painting pass and sweeps skip it.
     */
    public boolean isPanelBoardShowing() {
        if (this.componentMap == null) {
            return false;
        }
        for (Map.Entry<Integer, UnifiedInputBoardComponent> entry : this.componentMap.entrySet()) {
            UnifiedInputBoardComponent component = entry.getValue();
            if (component != null && CrossAxisRules.isPanelBoard(entry.getKey().intValue())
                    && component.isShowing()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Alias of {@link #isPanelBoardShowing()} kept because
     * {@code KeyboardSwitcherLayoutTransitionTest} stubs this name on a mock. Prefer the other.
     */
    public boolean isAnyBoardShowing() {
        return isPanelBoardShowing();
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
     * Send -14 ("board closed") to the keyboard switcher so the main keyboard returns with the
     * right shift state. Does not hide any board.
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

    /** @param c0965e the bar keyboard to paint */
    public void updateKeyHighlightedStates(Keyboard c0965e) {
        UnifiedInputBoardComponent value;
        if (c0965e == null) {
            Logger.error(TAG, "invoked with null keyboard");
            return;
        }
        
        boolean keyboardIconHighlighted = true; // true when no board is open
        boolean foundActiveBoard = false;

        // The per-component pass (painting and force-closes) and the sweep below run only while
        // the bar is up; otherwise the sweep would close a board opened before the bar appeared.
        final boolean barIsUp = this.keyboardView.getVisibility() != View.GONE;
        if (barIsUp) {
            for (Map.Entry<Integer, UnifiedInputBoardComponent> entry : this.componentMap.entrySet()) {
                int iIntValue = entry.getKey().intValue();
                value = entry.getValue();
                if (value == null || iIntValue == InlineAutofillManager.KEY_CODE_AUTOFILL) {
                    // The autofill strip is not a bar toggle and never counts as an open board.
                    continue;
                }
                if (!CrossAxisRules.isPanelBoard(iIntValue)) {
                    // Typing boards are always up: they would un-highlight the centre key for good
                    // and suppress the sweep. The centre -3 key is painted after the loop.
                    continue;
                }
                // Openness comes from the component, not from its key being on the bar: a toggle
                // moved off the bar is still opened by physical keys, and the sweep must not close it.
                Key keyM6604b = c0965e.getKeyByCode(iIntValue);
                boolean shouldHighlight = false;
                boolean isShowing = value.isShowing();
                boolean isEnabled = value.isEnabled();
                if (BuildConfig.DEBUG) {
                    Logger.debug(TAG, "updateKeyHighlightedStates: keycode=" + iIntValue
                            + " isShowing=" + isShowing + " isEnabled=" + isEnabled
                            + " onBar=" + (keyM6604b != null) + " -> setActive=" + isEnabled);
                }

                if (isShowing && isEnabled) {
                    if (foundActiveBoard) {
                        // Exclusive-open violation: close the extra board.
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
                    if (BuildConfig.DEBUG) {
                        Logger.debug(TAG, String.format(Locale.getDefault(), "Key with code %d is not on the UIM bar (menu order); nothing to paint", Integer.valueOf(iIntValue)));
                    }
                }
            }
        }
        // The centre key is painted even while the bar is GONE, for when it becomes visible.
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
        // The typing boards are registered too, so the map names every board; they are not panel
        // boards, which keeps sweeps, exclusive open, painting and bar-tap routing off them.
        registerComponent(new AlphabetBoardController());
        registerComponent(new SymbolBoardController());

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
        // The emoji controller holds no view, so registering it inflates nothing.
        registerComponent(new EmojiBoardController(this.imeService));

        if (InlineAutofillManager.isSupported()) {
            registerComponent(InlineAutofillManager.getInstance(this.context));
        }
    }
    
    /**
     * Rebuild the bar after a theme change; the rebuild makes every key re-pull its colors from
     * KeyboardColorManager (the themed context itself never changes).
     */
    public void updateTheme(Context context) {
        cachedKeyboard = null;

        if (this.keyboardView != null && this.keyboardView.getKeyboard() != null) {
            Keyboard newKeyboard = getOrBuildKeyboard();
            this.keyboardView.setKeyboard(newKeyboard);
            updateKeyHighlightedStates(newKeyboard);
            newKeyboard.mIconsSet.applyColorTint();
        }

        scheduleInvalidation();
    }
    
    /**
     * Once no board is open, return the bar to the user's choice ({@code AuxBarChoice}). Deferred
     * because a board switch closes before it opens, and re-checked when it runs. A frame callback,
     * not a handler post: the hide's layout request holds messages back, which drew one stale frame.
     */
    private void scheduleUncoverAfterBoardsClosed() {
        // Lazy, not a field initializer: JVM harnesses build this class without its constructor.
        if (this.uncoverAfterBoardsClosed == null) {
            this.uncoverAfterBoardsClosed = frameTimeNanos -> uncoverAfterBoardsClosedNow();
        }
        android.view.Choreographer choreographer = android.view.Choreographer.getInstance();
        choreographer.removeFrameCallback(this.uncoverAfterBoardsClosed);
        choreographer.postFrameCallback(this.uncoverAfterBoardsClosed);
    }

    private android.view.Choreographer.FrameCallback uncoverAfterBoardsClosed;

    private void uncoverAfterBoardsClosedNow() {
        dev.bbkb.ime.core.ime.InputViewCoordinator ui = this.imeService.getUiCoordinator();
        if (ui != null) {
            ui.uncoverAfterBoardsClosed();
        }
    }

    /**
     * Lower the menu onto the strip it covers, keeping the strip's words and closing no board.
     * {@link #hide()} instead empties the strip and closes every board.
     */
    public void lowerMenuOntoStrip() {
        SettingsValues.setInputMenuRequestedByKey(false);
        this.keyboardView.animate().cancel();
        this.invalidationHandler.removeCallbacks(animationSafetyReset);
        setAnimating(false);
        AuxBarManager auxBarManager = bar();
        if (auxBarManager != null) {
            auxBarManager.restoreStripFromMenu();
        } else {
            this.keyboardView.setVisibility(View.GONE);
        }
        this.displayedKeyboard = null;
        dumpUimState("lowerMenuOntoStrip.EXIT");
    }

    /**
     * The funnel every open and close path reports through; the only writer of the coordinator's
     * state. Also repaints the bar.
     *
     * @param component the open board, or null if none is open
     */
    public void setActiveComponent(UnifiedInputBoardComponent component) {
        if (component != null) {
            this.boardCoordinator.notifyBoardOpened(component.getKeyCode());
        } else {
            boolean aBoardWasOpen = this.boardCoordinator.activeBoard() != UnifiedBoardCoordinator.NO_BOARD;
            this.boardCoordinator.notifyBoardClosed();
            if (aBoardWasOpen) {
                scheduleUncoverAfterBoardsClosed();
            }
        }
        dumpUimState("setActiveComponent", component != null ? component.getKeyCode() : 0);

        // drawKeyContent() tints icons from key.isHighlighted.
        SimplifiedKeyboardView displayedView = getDisplayedView();
        Keyboard keyboard = displayedView != null ? displayedView.getKeyboard() : null;
        if (keyboard != null) {
            updateKeyHighlightedStates(keyboard);
        }

        scheduleInvalidation();
    }

    /** Report an open by keycode (the KeyboardState emoji machine); no-op if unregistered. */
    public void setActiveComponentByKeyCode(int keyCode) {
        UnifiedInputBoardComponent component = panelComponent(keyCode);
        if (component != null) {
            setActiveComponent(component);
        }
    }

    /**
     * Scoped close: clears the coordinator only if {@code keyCode} is the active board, but always
     * repaints. Use it instead of {@code setActiveComponent(null)} when another board may be open;
     * e.g. {@code hideEmojiKeyboard()} runs on every keyboard rebuild, including while voice or the
     * number pad is typed from.
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
        return kc != UnifiedBoardCoordinator.NO_BOARD ? panelComponent(kc) : null;
    }

    /**
     * Open half, called once the coordinator decided "open". Raises the bar before dispatching so
     * the painting pass sees the new board instead of sweeping it.
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

    /** Close half, over the same prologue as {@link #openBoard(int)}. */
    @Override
    public void closeBoard(int keyCode) {
        beginBoardTransition();
        // By keycode, not activeComponent: the view and mode flag may already be cleared, in
        // which case each branch is a safe no-op.
        UnifiedInputBoardComponent c = panelComponent(keyCode);
        if (keyCode == -27) {
            // Unconditional, so a winding-down recognizer always releases the mic (idempotent).
            if (c instanceof VoiceInputController) {
                ((VoiceInputController) c).cancelVoiceInput();
            } else if (c != null && c.isShowing()) {
                c.hide();
            }
        } else if (c != null) {
            // Emoji's hide() is unconditional; every other hide() is guarded.
            c.hide();
        }
        // Idempotent; setActiveComponent also schedules what the bar returns to.
        setActiveComponent(null);
        refresh();
    }

    /** Entry point for physical board keys: toggle through the coordinator. */
    public void requestBoard(int keyCode) {
        this.boardCoordinator.requestBoard(keyCode);
    }

    public UnifiedBoardCoordinator getBoardCoordinator() {
        return this.boardCoordinator;
    }

    /** The key view actually on screen: the AuxBar's shared view, else keyboardView. */
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

    /** Coalesce invalidations into one posted invalidate(). */
    private void scheduleInvalidation() {
        if (!pendingInvalidation) {
            pendingInvalidation = true;
            invalidationHandler.post(new Runnable() {
                @Override
                public void run() {
                    pendingInvalidation = false;
                    SimplifiedKeyboardView displayedView = getDisplayedView();
                    if (displayedView != null) {
                        displayedView.invalidate();
                    }
                }
            });
        }
    }
}

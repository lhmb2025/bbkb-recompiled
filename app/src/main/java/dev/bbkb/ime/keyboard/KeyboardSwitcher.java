package dev.bbkb.ime.keyboard;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import dev.bbkb.ime.core.settings.PrefsManager;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodSubtype;

import androidx.annotation.Nullable;

import dev.bbkb.ime.R;
import dev.bbkb.ime.compat.InputMethodSubtypeCompat;
import dev.bbkb.ime.core.BlackBerryIME;
import dev.bbkb.ime.core.SymbolPageProvider;
import dev.bbkb.ime.core.locale.RichInputMethodManager;
import dev.bbkb.ime.core.ime.InputView;
import dev.bbkb.ime.core.KeyboardLayoutCallback;
import dev.bbkb.ime.core.engine.NuanceSDKManager;
import dev.bbkb.ime.core.locale.SubtypeManager;
import dev.bbkb.ime.core.keyevent.KeyCharacterInterpreter;
import dev.bbkb.ime.core.keyevent.KeyCharacterResult;
import dev.bbkb.ime.core.keyevent.ModifierResetReason;
import dev.bbkb.ime.core.settings.util.SettingsManager;
import dev.bbkb.ime.keyboard.internal.KeyHintPosition;
import dev.bbkb.ime.core.settings.util.SettingsValues;
import dev.bbkb.ime.core.textinput.InputMethodHelper;
import dev.bbkb.ime.core.device.profile.DeviceProfile;
import dev.bbkb.ime.core.locale.LocaleUtils;
import dev.bbkb.ime.core.shared.Logger;
import dev.bbkb.ime.core.shared.SystemProps;
import dev.bbkb.ime.core.locale.ResourceLocaleUtils;
import dev.bbkb.ime.core.device.ResourceConfigManager;
import dev.bbkb.ime.core.textinput.InputLogic;
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiPalettesView;
import dev.bbkb.ime.keyboard.inputboard.fcc.FccView;
import dev.bbkb.ime.keyboard.inputboard.numberpad.NumberPadView;
import dev.bbkb.ime.keyboard.inputboard.voice.VoiceInputView;
import dev.bbkb.ime.keyboard.inputboard.UnifiedBoardCoordinator;
import dev.bbkb.ime.keyboard.inputboard.UnifiedInputBoardManager;
import dev.bbkb.ime.keyboard.internal.KeyRepeatHandler;
import dev.bbkb.ime.keyboard.internal.KeyboardIconSet;
import dev.bbkb.ime.keyboard.internal.KeyboardTextsSet;
import dev.bbkb.ime.keyboard.internal.MoreKeySpec;
import dev.bbkb.ime.keyboard.internal.KeyboardState;
import dev.bbkb.ime.keyboard.state.CrossAxisRules;
import dev.bbkb.ime.keyboard.state.KeyboardStateCoordinator;
import dev.bbkb.ime.keyboard.state.KeyboardTransition;
import dev.bbkb.ime.keyboard.slideboard.NumericSubpanelKeyboardView;
import dev.bbkb.ime.keyboard.slideboard.QuickPhrasesView;
import dev.bbkb.ime.keyboard.slideboard.SlideboardComponent;
import dev.bbkb.ime.keyboard.slideboard.SlideboardManager;

import android.view.ViewStub;

import java.util.Locale;
import dev.bbkb.ime.BuildConfig;
import dev.bbkb.ime.core.shared.InputPathDebug;



public final class KeyboardSwitcher implements SymbolPageProvider, KeyboardLayoutCallback, KeyCharacterInterpreter, KeyboardState.SwitcherCallbacks {

    private static final String TAG = "KeyboardSwitcher";

    // Process-lifetime singleton that DOES hold Views (inputView, mainKeyboardFrame,
    // mainKeyboardView, emojiPalettesView, ...). destroy() releases every one of them, so the
    // retention lint would otherwise flag is bounded by the input view's own lifecycle.
    @SuppressLint({"StaticFieldLeak"})
    private static final KeyboardSwitcher INSTANCE = new KeyboardSwitcher();

    private SubtypeManager subtypeManager;

    private SharedPreferences sharedPreferences;

    private InputView inputView;

    private View mainKeyboardFrame;

    private MainKeyboardView mainKeyboardView;

    private EmojiPalettesView emojiPalettesView;

    // Performance: ViewStub for lazy inflation of the emoji board (audit IB-1)
    private ViewStub emojiPalettesViewStub;

    private FccView fccView;
    
    // Performance: ViewStub for lazy inflation of FCC view
    private ViewStub fccViewStub;
    
    private VoiceInputView voiceInputView;
    
    // Performance: ViewStub for lazy inflation of voice input view
    private ViewStub voiceInputViewStub;

    private NumberPadView numberPadView;

    // Performance: ViewStub for lazy inflation of the number pad board
    private ViewStub numberPadViewStub;

    private UnifiedInputBoardManager unifiedInputBoardManager;

    private SlideboardManager slideboardManager;

    private BlackBerryIME blackberryIme;

    /**
     * The one funnel every cross-axis change goes through. Built lazily so it also exists on
     * instances whose constructor never ran (the JVM tests build the switcher that way).
     *
     * @see #transitions()
     */
    private KeyboardStateCoordinator stateTransitions;

    /**
     * The single entry point for "switch layout / open board / close board / enter or exit cursor
     * mode / show or hide bar". What each of those does to the other three axes is stated once, in
     * {@link CrossAxisRules}; nothing here decides it.
     */
    public KeyboardStateCoordinator transitions() {
        if (this.stateTransitions == null) {
            this.stateTransitions = new KeyboardStateCoordinator(new SwitcherAxes());
        }
        return this.stateTransitions;
    }

    /**
     * The production {@link KeyboardStateCoordinator.Axes}: every method is one of the mechanism
     * calls the scattered sweeps used to make, under a name that says which axis it belongs to.
     * No decisions live here — they are all in the table.
     */
    private final class SwitcherAxes implements KeyboardStateCoordinator.Axes {

        // ── layout ────────────────────────────────────────────────────────

        @Override
        public void leaveEmojiMode() {
            leaveEmojiModeInternal();
        }

        @Override
        public void clearPkbSymbolMode() {
            KeyboardSwitcher.this.clearPkbSymbolMode();
        }

        // ── board ─────────────────────────────────────────────────────────

        @Override
        public boolean isBoardUp(int keyCode) {
            UnifiedInputBoardManager uim = KeyboardSwitcher.this.unifiedInputBoardManager;
            return uim != null && uim.isBoardViewShowing(keyCode);
        }

        /**
         * SITE 1 of the Phase 1f split, on the funnel's side of it: "a board is open" here has
         * always meant a PANEL board, which is what {@code isAnyBoardShowing()} still answers (it
         * delegates to {@code isPanelBoardShowing()}). The old name is kept at this one boundary
         * because {@code KeyboardSwitcherLayoutTransitionTest} characterises the sweep against a
         * mock UIM by stubbing it.
         */
        @Override
        public boolean anyBoardIsOpen() {
            UnifiedInputBoardManager uim = KeyboardSwitcher.this.unifiedInputBoardManager;
            return uim != null && uim.isShowing() && uim.isAnyBoardShowing();
        }

        @Override
        public void sweepBoardsClosed() {
            UnifiedInputBoardManager uim = KeyboardSwitcher.this.unifiedInputBoardManager;
            if (uim != null) {
                uim.hideKeyboardOnKeyboardStateChange();
            }
        }

        @Override
        public void sweepAllBoardsClosed() {
            BlackBerryIME ime = KeyboardSwitcher.this.blackberryIme;
            if (ime != null) {
                ime.sweepAllBoardsClosed();
            }
        }

        @Override
        public void openBoard(int keyCode) {
            UnifiedInputBoardManager uim = KeyboardSwitcher.this.unifiedInputBoardManager;
            if (uim != null) {
                uim.openBoard(keyCode);
            }
        }

        @Override
        public void closeBoard(int keyCode) {
            UnifiedInputBoardManager uim = KeyboardSwitcher.this.unifiedInputBoardManager;
            if (uim != null) {
                uim.closeBoard(keyCode);
            }
        }

        @Override
        public void reportBoardOpened(int keyCode) {
            UnifiedInputBoardManager uim = KeyboardSwitcher.this.unifiedInputBoardManager;
            if (uim != null) {
                uim.setActiveComponentByKeyCode(keyCode);
            }
        }

        @Override
        public void reportBoardClosed(int keyCode) {
            UnifiedInputBoardManager uim = KeyboardSwitcher.this.unifiedInputBoardManager;
            if (uim != null) {
                uim.reportBoardClosed(keyCode);
            }
        }

        // ── bar ───────────────────────────────────────────────────────────

        @Override
        public boolean isUimEnabled() {
            BlackBerryIME ime = KeyboardSwitcher.this.blackberryIme;
            return ime != null && ime.isUimEnabled();
        }

        @Override
        public boolean isBarShowing() {
            UnifiedInputBoardManager uim = KeyboardSwitcher.this.unifiedInputBoardManager;
            return uim != null && uim.isShowing();
        }

        @Override
        public void showUimBar() {
            UnifiedInputBoardManager uim = KeyboardSwitcher.this.unifiedInputBoardManager;
            if (uim != null) {
                uim.show(false);
                uim.showEmojiBoard();
            }
        }

        @Override
        public void hideUimBar() {
            UnifiedInputBoardManager uim = KeyboardSwitcher.this.unifiedInputBoardManager;
            if (uim != null) {
                uim.hide();
            }
        }

        @Override
        public void restoreStripOrUimBar() {
            BlackBerryIME ime = KeyboardSwitcher.this.blackberryIme;
            if (ime != null) {
                ime.restoreStripOrUimBar();
            }
        }

        // ── cursor ────────────────────────────────────────────────────────

        @Override
        public boolean canRaiseArrowBar() {
            BlackBerryIME ime = KeyboardSwitcher.this.blackberryIme;
            return ime != null && ime.canRaiseArrowBar();
        }

        @Override
        public void prepareForArrowBar() {
            BlackBerryIME ime = KeyboardSwitcher.this.blackberryIme;
            if (ime != null) {
                ime.prepareForArrowBar();
            }
        }

        @Override
        public void raiseArrowBar() {
            BlackBerryIME ime = KeyboardSwitcher.this.blackberryIme;
            if (ime != null) {
                ime.raiseArrowBar();
            }
        }

        @Override
        public void lowerArrowBarRestoringStrip(boolean requestShiftUpdate) {
            BlackBerryIME ime = KeyboardSwitcher.this.blackberryIme;
            if (ime != null) {
                ime.lowerArrowBarRestoringStrip(requestShiftUpdate);
            }
        }

        @Override
        public void cursorModeOn(boolean forced) {
            BlackBerryIME ime = KeyboardSwitcher.this.blackberryIme;
            if (ime != null) {
                ime.cursorModeOn(forced);
            }
        }

        @Override
        public void cursorModeOff() {
            BlackBerryIME ime = KeyboardSwitcher.this.blackberryIme;
            if (ime != null) {
                ime.cursorModeOff();
            }
        }
    }

    private SwitcherCallbacks switcherCallbacks;

    private boolean isHardwareAccelerated;

    private KeyboardState keyboardState;

    private KeyboardBuilder keyboardBuilder;

    private Context themeContext;

    private final KeyboardTextsSet keyboardLayoutSet = new KeyboardTextsSet();


    
    public interface SwitcherCallbacks {
        void showKeyboardMenu();

        void onKeyboardLayoutChanged();
    }

    public KeyboardLayoutCallback getKeyboardLayoutCallback() {
        return this;
    }

    public KeyCharacterInterpreter getKeyCharacterInterpreter() {
        return this;
    }

    public static KeyboardSwitcher getInstance() {
        return INSTANCE;
    }

    /**
     * Lazy getter for EmojiPalettesView: inflates from its ViewStub on first access.
     *
     * <p>Audit IB-1: the emoji board used to be an {@code <include>}, so its whole view tree
     * (TabHost, ViewPager, pager adapter) was built on every input-view creation whether or not
     * the user ever opened emoji. Wiring that used to happen in {@link #createInputView} now
     * happens here, once, at first inflation.
     *
     * <p>Callers that only need to answer "is it showing" or hide it must use
     * {@link #peekEmojiPalettesView()} instead -- a board that was never opened must not cost
     * an inflation just to report that it is not showing.
     */
    public EmojiPalettesView getEmojiPalettesView() {
        if (this.emojiPalettesView == null && this.emojiPalettesViewStub != null) {
            this.emojiPalettesView = (EmojiPalettesView) this.emojiPalettesViewStub.inflate();
            // The <include> version was inflated VISIBLE and immediately hidden by the
            // setKeyboard() -> hideEmojiKeyboard() that follows createInputView. Start hidden
            // so an inflation triggered by anything other than showEmojiKeyboardInternal()
            // cannot briefly cover the main keyboard.
            this.emojiPalettesView.setVisibility(View.GONE);
            this.emojiPalettesView.setHardwareAcceleratedDrawingEnabled(this.isHardwareAccelerated);
            this.emojiPalettesView.setKeyboardActionListener(this.blackberryIme);
            // No component registration here any more: the -11 component is EmojiBoardController,
            // which holds no view and is registered with the rest (§5.6 item 4). This getter's
            // only job is the IB-1 ViewStub deferral.
        }
        return this.emojiPalettesView;
    }

    /** Non-inflating variant for state queries and hide paths. Null until first open. */
    public EmojiPalettesView peekEmojiPalettesView() {
        return this.emojiPalettesView;
    }

    /**
     * Get the current composing (uncommitted) text from InputLogic.
     * Used by dynamic emoji search to drive search from what the user has typed.
     */
    public String getComposingText() {
        if (this.blackberryIme == null) return "";
        InputLogic logic = this.blackberryIme.getInputLogic();
        if (logic == null) return "";
        return logic.mComposingTracker.isComposing() ? logic.mComposingTracker.getComposingText() : "";
    }

    /**
     * Clear (discard) composing text without committing it.
     * Uses the codebase's own discard pattern to fully reset all internal state.
     * Called in emoji replace mode to delete the typed word before inserting emoji.
     */
    public void clearComposingText() {
        if (this.blackberryIme == null) return;
        InputLogic logic = this.blackberryIme.getInputLogic();
        if (logic == null) return;

        // Get composing text length before clearing
        int composingLength = logic.mComposingTracker.isComposing() ? logic.mComposingTracker.getComposingText().length() : 0;

        // Follow codebase pattern: finish composing, clear all state
        if (logic.mComposingTracker.isComposing() || logic.mTouchHighlightTracker.isHighlightActive()) {
            logic.mRichInputConnection.finishComposingText();   // finishComposingText
            logic.mTouchHighlightTracker.clear();   // clear TouchHighlightTracker
            logic.mComposingTracker.clearAll();   // reset ComposingTextTracker
        }

        // Reset event dispatcher
        logic.clearComposingText(true);

        // Delete the now-committed text from editor
        if (composingLength > 0) {
            logic.mRichInputConnection.deleteSurroundingText(composingLength, 0);  // deleteSurroundingText
        }
    }

    /**
     * Commit composing text (finalize it in the editor).
     * Called in emoji append mode to commit typed word before inserting emoji.
     */
    public void commitComposingText() {
        if (this.blackberryIme == null) return;
        InputLogic logic = this.blackberryIme.getInputLogic();
        if (logic == null) return;
        logic.commitTouchEventText();
    }

    private KeyboardSwitcher() {
    }

    public static void initialize(BlackBerryIME blackBerryIME, SwitcherCallbacks aVar) {
        INSTANCE.initializeInternal(blackBerryIME, aVar, PrefsManager.INSTANCE.getPrefs());
    }

    private void initializeInternal(BlackBerryIME blackBerryIME, SwitcherCallbacks aVar, SharedPreferences sharedPreferences) {
        this.blackberryIme = blackBerryIME;
        this.switcherCallbacks = aVar;
        this.sharedPreferences = sharedPreferences;
        this.subtypeManager = SubtypeManager.getInstance();
        this.keyboardState = new KeyboardState(this);
        // ISSUE 1 FIX: Drop reflective enableHardwareAcceleration probe. The
        // reflective lookup always returned null on every Android version
        // (Class.getMethod() does not see @hide methods), so the runtime value
        // was always false. Hardcode false to preserve behavior without paying
        // the reflection cost. See BlackBerryIME.isHardwareAccelerated.
        this.isHardwareAccelerated = false;
    }

    public boolean isInitialized() {
        return (this.blackberryIme == null || this.switcherCallbacks == null || this.sharedPreferences == null || this.subtypeManager == null || this.keyboardState == null || this.keyboardBuilder == null) ? false : true;
    }

    public void forceRecreateInputView() {
        if (this.mainKeyboardView == null) {
            return;
        }
        this.blackberryIme.setInputView(createInputView(this.isHardwareAccelerated));
    }

    /**
     * There is one keyboard style tree; light/dark/classic/dynamic are palettes in
     * KeyboardColorManager, applied at draw time. The themed context therefore never
     * changes after creation, and theme switches no longer recreate the input view.
     */
    private void ensureThemeContext(Context context) {
        if (this.themeContext == null) {
            this.themeContext = new ContextThemeWrapper(context.getApplicationContext(), R.style.KeyboardTheme_LXX);
            KeyboardBuilder.onSystemLocaleChanged();
        }
    }

    private void updateSecondaryKeySpec(int i, String str) {
        Key keyM6604b;
        MoreKeySpec c1065xM7444a;
        Keyboard c0965eM6834l = getCurrentKeyboard();
        if (c0965eM6834l == null) {
            return;
        }
        if (i == -23) {
            keyM6604b = c0965eM6834l.getKeyByOutputText("inputBoardToggleKeyStyle");
            if (keyM6604b == null) {
                keyM6604b = c0965eM6834l.getKeyByOutputText("numInputBoardToggleKeyStyle");
            }
        } else {
            keyM6604b = i == -10 ? c0965eM6834l.getKeyByCode(32) : null;
        }
        if (keyM6604b == null) {
            return;
        }
        boolean zM6181C = keyM6604b.hasLongPressKey();
        boolean zM6769d = shouldShowSecondaryIcon(i);
        if (zM6181C == zM6769d) {
            return;
        }
        if (zM6769d) {
            c1065xM7444a = new MoreKeySpec(null, KeyboardIconSet.getIconId(str), i, null);
        } else {
            c1065xM7444a = MoreKeySpec.getEmpty();
        }
        if (c0965eM6834l.replaceKey(keyM6604b, new Key(keyM6604b.getKeySpec(), c1065xM7444a, KeyHintPosition.TOP_RIGHT_CORNER, keyM6604b.getHintLabel(), keyM6604b.getLabelFlags(), keyM6604b.getBackgroundType(), keyM6604b.getX(), keyM6604b.getY(), keyM6604b.getWidth() + c0965eM6834l.mHorizontalGap, keyM6604b.getHeight() + c0965eM6834l.mVerticalGap, c0965eM6834l.mHorizontalGap, c0965eM6834l.mVerticalGap, keyM6604b.getOutputText(), keyM6604b.getMoreKeys(), keyM6604b.getKeyLabelSet(), keyM6604b.getMoreKeysFlags(), keyM6604b.getActionFlags(), keyM6604b.getScanCode()))) {
            c0965eM6834l.rebuildProximityGrid();
            return;
        }
        if (BuildConfig.DEBUG) Log.e(TAG, "Could not replace the secondary key spec of the key");
    }

    private boolean shouldShowSecondaryIcon(int i) {
        if (i == -23) {
            return SettingsManager.getInstance().getSettingsValues().isUimEnabled;
        }
        if (i != -10) {
            return false;
        }
        return RichInputMethodManager.getInstance().hasMultipleEnabledSubtypesInThisIme(false) && SettingsManager.getInstance().getSettingsValues().isSpacebarLanguageSwitchingEnabled;
    }

    public void startInput(EditorInfo editorInfo, int i, int i2) throws Resources.NotFoundException {
        if (this.blackberryIme == null) {
            return;
        }
        KeyboardBuilder.Builder aVar = new KeyboardBuilder.Builder(this.themeContext, editorInfo);
        Resources resources = this.themeContext.getResources();
        aVar.setKeyboardGeometry(ResourceConfigManager.getScreenWidthPixels(resources), ResourceConfigManager.getKeyboardHeight(resources));
        aVar.setSubtype(getSubtypeForEditorInfo(editorInfo, this.subtypeManager.getCurrentSubtype()));
        aVar.setVoiceInputKeyEnabled(InputMethodHelper.shouldShowVoiceInputKey());
        aVar.setLanguageSwitchKeyEnabled(this.blackberryIme.shouldShowLanguageSwitchKey());
        aVar.setLanguageQuickSwitchKeyEnabled(SettingsManager.isLanguageQuickSwitchEnabled());
        aVar.setInputBoardBarEnabled(this.blackberryIme.isUimEnabled());
        this.keyboardBuilder = aVar.build();
        try {
            this.keyboardState.onStartInput(i, i2, this.subtypeManager.getCurrentSubtypeLocale(), editorInfo.inputType, editorInfo.packageName);
            this.keyboardLayoutSet.setLocale(this.subtypeManager.getCurrentSubtypeLocale(), this.themeContext);
            SlideboardManager c1083e = this.slideboardManager;
            if (c1083e != null) {
                c1083e.setKeyboard(this.mainKeyboardView.getKeyboard());
            } else {
                if (BuildConfig.DEBUG) Log.e(TAG, "mSlideboardViewManager is not initialized.");
            }
            this.switcherCallbacks.onKeyboardLayoutChanged();
        } catch (KeyboardBuilder.KeyboardLayoutSetException e) {
            if (BuildConfig.DEBUG) Log.w(TAG, "loading keyboard failed: " + e.mKeyboardId, e.getCause());
        }
    }

    InputMethodSubtype getSubtypeForEditorInfo(EditorInfo editorInfo, InputMethodSubtype inputMethodSubtype) {
        Locale localeM5625c = ResourceLocaleUtils.getSubtypeLocale(inputMethodSubtype);
        int i = editorInfo.inputType & 4080;
        int i2 = editorInfo.inputType & 15;
        boolean z = true;
        if ((i2 != 1 || (i != 128 && i != 144 && i != 224)) && (i2 != 2 || i != 16)) {
            z = false;
        }
        return (z && LocaleUtils.isChinesePinyin(localeM5625c)) ? InputMethodSubtypeCompat.newInputMethodSubtype(R.string.subtype_no_language_qwerty, R.drawable.ic_ime_switcher, Locale.ENGLISH.toString(), "keyboard", "KeyboardLayoutSet=qwerty,AsciiCapable,EnabledWhenDefaultIsNotAsciiCapable,EmojiCapable", false, false, 0) : inputMethodSubtype;
    }

    public void saveKeyboardState(int i) {
        if (getCurrentKeyboard() != null || isEmojiKeyboardShowing()) {
            this.keyboardState.saveKeyboardState(i);
        }
    }


    public KeyboardState getKeyboardState() {
        return this.keyboardState;
    }

    public void cancelKeyTimers() {
        MainKeyboardView mainKeyboardView = this.mainKeyboardView;
        if (mainKeyboardView != null) {
            mainKeyboardView.onHideWindow();
        }
    }

    private void setKeyboard(Keyboard c0965e) {
        SettingsValues c0804dM5050c = SettingsManager.getInstance().getSettingsValues();
        // A new layout going into the main keyboard view means the emoji board is not up any more.
        // Phase 1a wrote that as a LAYOUT_LOADED row, because emoji was a layout mode AND a board
        // and the two identities disagreed. Phase 1d gave it one identity, so this is what it
        // always was: an ordinary board switch. The report stays scoped to -11 — an unscoped
        // "nothing is open" was what made the coordinator forget the open voice board on the KEY2
        // every time a dictated word committed text.
        transitions().apply(KeyboardTransition.boardLowered(CrossAxisRules.EMOJI_KEY_CODE));
        MainKeyboardView mainKeyboardView = this.mainKeyboardView;
        if (mainKeyboardView != null) {
            mainKeyboardView.setKeyboard(c0965e);
            // Apply color tint to keyboard icons (shift, delete, etc.)
            if (c0965e != null && c0965e.mIconsSet != null) {
                c0965e.mIconsSet.applyColorTint();
            }
            mainKeyboardView.setKeyPreviewPopupEnabled(c0804dM5050c.isKeyPreviewPopupEnabled, c0804dM5050c.keyPreviewPopupDismissDelay);
            mainKeyboardView.setKeyPreviewAnimationParams(c0804dM5050c.hasCustomKeyPreviewAnimationParams, c0804dM5050c.keyPreviewShowUpStartScaleX, c0804dM5050c.keyPreviewShowUpStartScaleY, c0804dM5050c.keyPreviewShowUpDuration, c0804dM5050c.keyPreviewDismissEndScaleX, c0804dM5050c.keyPreviewDismissEndScaleY, c0804dM5050c.keyPreviewDismissDuration);
            mainKeyboardView.setFunctionalKeyEnabled(this.subtypeManager.isShortcutImeReady());
            mainKeyboardView.setHintsEnabled(shouldShowHints());
            mainKeyboardView.setMappedMode(this.keyboardState.wasSymbolEnteredFromAlphabet());
        } else {
            Logger.warn(TAG, "keyboardView is null");
        }
        updateSecondaryKeySpec(-10, "language_switch_key");
        updateSecondaryKeySpec(-23, "show_input_menu_key");
        updateKeyboardLayout();
    }

    public void updateKeyboardLayout() {
        this.blackberryIme.updateFlickAndFilter();
        syncKeyboardLayout();
    }

    public void syncKeyboardLayout() {
        Keyboard c0965eM6834l = getCurrentKeyboard();
        if (c0965eM6834l != null) {
            boolean isTouchKb = c0965eM6834l.isTouchKeyboard();
            int width = c0965eM6834l.mOccupiedWidth;
            int height = c0965eM6834l.mOccupiedHeight;
            if (isTouchKb && height == 0) {
                float ckbYMax = DeviceProfile.current().getTouchKeypadYMax();
                if (ckbYMax > 0) {
                    if (InputPathDebug.on()) android.util.Log.i("CKB_SWIPE_TYPE_DEBUG", "KeyboardSwitcher.syncKeyboardLayout: CKB height was 0, substituting touchKeypadYMax=" + ckbYMax);
                    height = (int) ckbYMax;
                }
            }
            String localeStr = LocaleUtils.toNuanceLocaleString(this.subtypeManager.getCurrentSubtypeLocale());
            // VKB parity (2026-08-21): in the rebuilt keyboard model every current keyboard has
            // elementId 0, so isTouchKeyboard() (elementId >= 100 — the original's CKB-posing
            // layouts) never fires and the ON-SCREEN board was classified as a PKB: its view dims
            // were discarded (setKeyboardSize(0,0)) and the engine never stretched the KDB to the
            // surface — VKB swipes decoded against the wrong rows (hello->nelo). When the
            // on-screen keyboard is the input surface, sync the VKB KDB and pass the real view
            // dims so the engine applies the original app's SetKeyboardSize stretch.
            // SURFACE TEST (fixed 2026-08-29): DeviceProfile.isOnScreenKeyboardVisible() alone is
            // NOT authoritative — it caches shouldShowInputView(), whose UIM term is true whenever
            // the KEY2's aux/suggestion bar is up, i.e. during ORDINARY PAD TYPING. That framed
            // the engine VKB for pad syncs (pad swipe of "coffee" decoded "Dupree" against the
            // stretched VKB KDB). The on-screen keyboard is the surface only when the actual
            // keyboard view is DISPLAYED, so require MainKeyboardView.isShown() as well.
            // EXCEPTION: the gesture-replay rig fakes a vkb-forced window while injecting
            // engine-space touchpad events; its own marker prop keeps it on the PKB path (no
            // stretch), preserving the emulator harness frame.
            MainKeyboardView mkvForSync = getMainKeyboardView();
            // isShown() alone is NOT enough: in pad mode the KEY2 keeps the MainKeyboardView in
            // the displayed input-view hierarchy (the aux bar's container) VISIBLE but collapsed,
            // so require real on-screen size too (screenshot-verified: no keyboard displayed yet
            // isShown()=true framed the engine VKB).
            boolean keyboardViewDisplayed = mkvForSync != null && mkvForSync.isShown()
                    && mkvForSync.getHeight() > 0 && mkvForSync.getWidth() > 0;
            // ENGINE-FRAME RULE (2026-08-30, after the owner's doing->going/nonsense report):
            // the VKB frame (sid=1 + SetKeyboardSize stretch) is selected ONLY when the CURRENT
            // ELEMENT is a VKB-family layout AND its view is actually displayed. The element term
            // is load-bearing: pad mode builds the CKB-posing elements (isTouchKeyboard()=true),
            // so a transiently-measured keyboard view (window shows, UIM/board transitions, the
            // posted showWindow re-sync racing layout) can no longer flip a pad session into the
            // VKB frame — in that frame every typed key becomes regional soup ("doing" ranked
            // under "going" with nonsense pools) and pad swipes decode garbage, sticking until
            // the next layout sync. DeviceProfile.isOnScreenKeyboardVisible() is deliberately NOT
            // consulted anymore: its UIM term is true during ordinary pad typing (aux bar).
            boolean onScreenSurface = !isTouchKb && keyboardViewDisplayed && !isGestureReplayRig();
            if (InputPathDebug.on()) android.util.Log.i("CKB_SWIPE_TYPE_DEBUG",
                    "syncKeyboardLayout surface: mkv=" + (mkvForSync != null)
                    + " shown=" + (mkvForSync != null && mkvForSync.isShown())
                    + " h=" + (mkvForSync != null ? mkvForSync.getHeight() : -1)
                    + " isTouchKb=" + isTouchKb
                    + " -> onScreenSurface=" + onScreenSurface);
            // REBUILT-ARCHITECTURE CONTRACT (2026-08-29): CKB maps in JAVA (ckb-y-warp; engine
            // scale stays 0,0) — VKB maps in the ENGINE (SetKeyboardSize stretch). isTouchKb
            // (elementId >= 100, the original's CKB-posing layouts) must therefore NOT select the
            // engine-stretch path anymore: with the Java warp active it double-warps (live pad
            // replay of a known-good "coffee" trace read f-o-f-e -> "code"; first letter one row
            // up). The pre-session live state only worked by coincidence — the window-flag soup
            // built VKB-family elements whose pad-mode height is 0, so SetKeyboardSize(1080,0)
            // neutered the stretch. The engine stretch is for an ACTUALLY DISPLAYED on-screen
            // keyboard only.
            boolean vkbSync = onScreenSurface;
            if (InputPathDebug.on()) android.util.Log.i("CKB_SWIPE_TYPE_DEBUG", "KeyboardSwitcher.syncKeyboardLayout: isTouchKb=" + isTouchKb + " onScreenSurface=" + onScreenSurface + " width=" + width + " height=" + height + " locale=" + localeStr + " keyboardId=" + c0965eM6834l.mId.mElementId);
            // FIX-D2: null when the engine failed to load; there is then no layout to sync and no
            // key grid to capture, but the keyboard itself must still come up.
            com.blackberry.nuanceshim.NuanceSDK engine = NuanceSDKManager.getInstance();
            if (engine != null) engine.syncKeyboardLayout(vkbSync, localeStr, width, height);
            updateCangjieKey();
            // Debug: capture the real ET9 key rectangles for the Gesture Lab's grid overlay.
            // needsCapture() skips the read when this keypad geometry is already cached — a COST
            // guard (getKeys() builds 30 KeyInfo objects through JNI for a debug-only overlay),
            // not a safety one. It used to be a read-once-per-process latch, on the never-assessed
            // theory that repeated reads perturb ET9's gesture recognition; that is measured and
            // false (audit §4.3 — the blob's shim at 0x20fc0 touches no engine state, and 0/1/20
            // interleaved reads over the 240-swipe W6 replay are byte-identical).
            if (engine != null && isTouchKb && dev.bbkb.ime.core.gesture.arbiter.CkbKeyGridCapture.INSTANCE.needsCapture(width, height)) {
                try {
                    dev.bbkb.ime.core.gesture.arbiter.CkbKeyGridCapture.INSTANCE.capture(
                            this.blackberryIme, engine.getKeys(), width, height);
                } catch (Throwable ignored) {
                }
            }
        } else {
            if (InputPathDebug.on()) android.util.Log.w("CKB_SWIPE_TYPE_DEBUG", "KeyboardSwitcher.syncKeyboardLayout: getCurrentKeyboard() returned NULL — layout NOT synced to NuanceSDK");
        }
    }

    /** True on the emulator gesture-replay rig (its doorway sets debug.et9.replayvkbpkg): the rig
     *  fakes a vkb-forced window while injecting engine-space TOUCHPAD events, so the layout sync
     *  must stay on the PKB path (no SetKeyboardSize stretch) or the replayed frame double-scales.
     *  Not cached: syncs are rare, and clearing the prop then takes effect without a force-stop. */
    private static boolean isGestureReplayRig() {
        String v = SystemProps.get("debug.et9.replayvkbpkg");
        return v != null && !v.trim().isEmpty();
    }

    @Override
    public boolean isPkbDevice() {
        if (getCurrentKeyboard() == null) {
            return false;
        }
        return getCurrentKeyboard().isTouchKeyboard();
    }

    public Keyboard getCurrentKeyboard() {
        MainKeyboardView mainKeyboardView = this.mainKeyboardView;
        if (mainKeyboardView != null) {
            return mainKeyboardView.getKeyboard();
        }
        return null;
    }

    public Keyboard getAlphabetKeyboard() {
        Keyboard c0965eM6734a = this.keyboardBuilder.getKeyboardForShift(0, !this.blackberryIme.refreshOnScreenKeyboardShowing());
        if (c0965eM6734a != null) {
            return c0965eM6734a;
        }
        return null;
    }

    public Keyboard getUnshiftedAlphabetKeyboard() {
        return this.keyboardBuilder.getKeyboardForShift(0, false);
    }

    public UnifiedInputBoardManager getUnifiedInputBoardManager() {
        return this.unifiedInputBoardManager;
    }

    public SlideboardManager getSlideboardManager() {
        return this.slideboardManager;
    }

    public void onBackspaceInSymbolMode(int i, int i2) {
        this.keyboardState.onBackspaceInSymbolMode(i, i2);
    }

    public void onCodeInput(int i, boolean z, int i2, int i3) {
        boolean zM7248e = this.keyboardState.wasSymbolEnteredFromAlphabet();
        this.keyboardState.onCodeInput(i, z, i2, i3);
        if (!zM7248e || this.keyboardState.wasSymbolEnteredFromAlphabet()) {
            return;
        }
        this.mainKeyboardView.releaseCurrentlyPressedKeys();
        this.mainKeyboardView.setHintsEnabled(shouldShowHints());
        this.blackberryIme.updatePhysicalKeyboardFilter();
    }

    public void onCodeRelease(int i, boolean z, int i2, int i3) {
        KeyboardState c1028ak = this.keyboardState;
        if (c1028ak != null) {
            c1028ak.onCodeRelease(i, z, i2, i3);
        }
    }

    public void onHardwareKeyEvent(KeyEvent keyEvent, int i, int i2) {
        this.keyboardState.onHardwareKeyEvent(keyEvent.getKeyCode(), i, i2);
    }

    public void onMomentaryStateFinish(int i, int i2) {
        this.keyboardState.onMomentaryStateFinish(i, i2);
    }

    @Override
    public void showMenu() {
        this.switcherCallbacks.showKeyboardMenu();
    }

    @Override
    public void requestShiftOff() {
        EmojiPalettesView emojiPalettesView = this.emojiPalettesView;
        if (emojiPalettesView != null) {
            emojiPalettesView.setSwitchingToEmoji(false);
        }
        setAlphabetKeyboard(0);
        updateCangjieKey();
    }

    @Override
    public void requestManualShiftOff() {
        DeviceProfile.setForceVkbMode(true);
        setKeyboard(this.keyboardBuilder.getKeyboardForShift(0, false));
        this.blackberryIme.refreshOnScreenKeyboardShowing();
        onReturnToAlphabetFromSymbol();
    }

    @Override
    public void requestShiftOnce() {
        setAlphabetKeyboard(1);
    }

    @Override
    public void requestAutomaticShift() {
        setAlphabetKeyboard(2);
    }

    @Override
    public void requestShiftLocked() {
        setAlphabetKeyboard(3);
    }

    @Override
    public void requestShiftMomentary() {
        setAlphabetKeyboard(4);
    }

    private void setAlphabetKeyboard(int i) {
        transitions().apply(KeyboardTransition.switchLayout(KeyboardTransition.Layout.ALPHABET));
        boolean zM4042U = this.blackberryIme.refreshOnScreenKeyboardShowing();
        if (!zM4042U && this.slideboardManager != null) {
            this.slideboardManager.show();
        }
        setKeyboard(this.keyboardBuilder.getKeyboardForShift(i, !zM4042U));
    }

    @Override
    public void setVkbSymbolsKeyboard(int i, boolean z, boolean z2, int i2) {
        Keyboard c0965eM6734a;
        // SYMBOL, not PKB_SYMBOL: the on-screen symbol keyboard carries its own row of keys and
        // leaves the bar where it was — including when a PKB loads it, which
        // KeyboardState.switchToSymbolFromAlphabet always does.
        transitions().apply(KeyboardTransition.switchLayout(KeyboardTransition.Layout.SYMBOL));
        if (z2) {
            this.keyboardBuilder.setCustomSymbolPage(i2);
            c0965eM6734a = this.keyboardBuilder.getKeyboardForShift(8, z && !this.blackberryIme.refreshOnScreenKeyboardShowing());
        } else {
            int i3 = i + 5;
            boolean z3 = this.sharedPreferences.getBoolean("enable_symbol_customization_vkb", false);
            boolean z4 = this.sharedPreferences.getBoolean("vkb_custom_page_first", false);
            if (z3 && z4) {
                i3--;
            }
            c0965eM6734a = this.keyboardBuilder.getKeyboardForShift(i3, z && !this.blackberryIme.refreshOnScreenKeyboardShowing());
            replaceCurrencyKey(c0965eM6734a);
        }
        setKeyboard(c0965eM6734a);
    }

    @Override
    public void setPkbSymbolsKeyboard(int i, boolean z, boolean z2, int i2) {
        Keyboard c0965eM6735b;
        Key keyM6604b;
        // PKB_SYMBOL is the one layout whose row raises the UIM bar, and the table puts that
        // before the board sweep — the order this method has always used, and the one the sweep's
        // own "is the bar up" gate depends on.
        transitions().apply(KeyboardTransition.switchLayout(KeyboardTransition.Layout.PKB_SYMBOL));
        if (this.slideboardManager != null) {
            this.slideboardManager.showNumericPanel();
        }
        if (z2) {
            this.keyboardBuilder.setCustomSymbolPage(i2);
            c0965eM6735b = this.keyboardBuilder.getKeyboardInternal(8, z);
        } else {
            int i3 = i + 5;
            boolean z3 = this.sharedPreferences.getBoolean("enable_symbol_customization_pkb", false);
            boolean z4 = this.sharedPreferences.getBoolean("pkb_custom_page_first", false);
            if (z3 && z4) {
                i3--;
            }
            c0965eM6735b = this.keyboardBuilder.getKeyboardInternal(i3, z);
            replaceCurrencyKey(c0965eM6735b);
        }
        if (!c0965eM6735b.mId.mVoiceKeyEnabled && (keyM6604b = c0965eM6735b.getKeyByCode(-27)) != null) {
            keyM6604b.setActive(false);
        }
        setKeyboard(c0965eM6735b);
    }

    @Override
    public void onFinishShiftLongPress() {
        if (this.blackberryIme.isUimEnabled()) {
            this.unifiedInputBoardManager.show(false);
        }
    }

    @Override
    public void onStartShiftLongPress() {
        this.unifiedInputBoardManager.hide();
    }

    private void replaceCurrencyKey(Keyboard c0965e) {
        if (SettingsManager.getInstance().getSettingsValues().currencySymbol.length() > 0) {
            Key keyM6597a = c0965e.getKeyByOutputText("currencyExtendedKeyStyle");
            if (keyM6597a == null) {
                if (BuildConfig.DEBUG) Log.e(TAG, "Cannot find currency key on keyboard to replace");
                return;
            }
            char cCharAt = SettingsManager.getInstance().getSettingsValues().currencySymbol.charAt(0);
            if (cCharAt != keyM6597a.getCode()) {
                if (c0965e.replaceKey(keyM6597a, new Key(new MoreKeySpec(String.valueOf(cCharAt), 0, cCharAt, String.valueOf(cCharAt)), MoreKeySpec.getEmpty(), KeyHintPosition.HIDDEN, keyM6597a.getHintLabel(), 0, 1, keyM6597a.getX(), keyM6597a.getY(), c0965e.mHorizontalGap + keyM6597a.getWidth(), c0965e.mVerticalGap + keyM6597a.getHeight(), c0965e.mHorizontalGap, c0965e.mVerticalGap, "currencyExtendedKeyStyle", keyM6597a.getMoreKeys(), keyM6597a.getKeyLabelSet(), keyM6597a.getMoreKeysFlags(), 8, keyM6597a.getScanCode()))) {
                    c0965e.rebuildProximityGrid();
                    return;
                }
                if (BuildConfig.DEBUG) Log.e(TAG, "Cannot replace currency key: " + cCharAt + " on keyboard");
            }
        }
    }

    private void updateCangjieKey() {
        Key keyM6597a;
        String str;
        MoreKeySpec c1065x;
        String str2;
        Keyboard c0965eM6834l = getCurrentKeyboard();
        if (c0965eM6834l == null || !LocaleUtils.isChineseCangjie(this.subtypeManager.getCurrentSubtypeLocale())) {
            return;
        }
        if (SettingsManager.getInstance().getSettingsValues().cangjieMode == 1) {
            keyM6597a = c0965eM6834l.getKeyByOutputText("toQuickCangjieKeyStyle");
            str = "toCangjieKeyStyle";
            c1065x = new MoreKeySpec("速", 0, -40, null);
            str2 = "倉";
        } else {
            keyM6597a = c0965eM6834l.getKeyByOutputText("toCangjieKeyStyle");
            str = "toQuickCangjieKeyStyle";
            c1065x = new MoreKeySpec("倉", 0, -39, null);
            str2 = "速";
        }
        if (keyM6597a == null) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Could not find Cangjie Switch Key");
        } else {
            if (c0965eM6834l.replaceKey(keyM6597a, new Key(c1065x, MoreKeySpec.getEmpty(), KeyHintPosition.HIDDEN, str2, 0, 1, keyM6597a.getX(), keyM6597a.getY(), keyM6597a.getWidth() + c0965eM6834l.mHorizontalGap, keyM6597a.getHeight() + c0965eM6834l.mVerticalGap, c0965eM6834l.mHorizontalGap, c0965eM6834l.mVerticalGap, str, keyM6597a.getMoreKeys(), keyM6597a.getKeyLabelSet(), keyM6597a.getMoreKeysFlags(), 8, keyM6597a.getScanCode()))) {
                c0965eM6834l.rebuildProximityGrid();
                return;
            }
            if (BuildConfig.DEBUG) Log.e(TAG, "Could not replace Cangjie Switch Key");
        }
    }

    /**
     * The emoji board comes down: {@code BOARD_LOWERED(-11)}, an ordinary board switch. The layout
     * half and the scoped board report are the two columns of that row, applied in the order stated
     * in {@link CrossAxisRules}.
     *
     * <p>Kept as a named method because it is a {@link KeyboardState.SwitcherCallbacks} entry
     * point (the emoji state machine closes the board through it) and has out-of-package callers.
     */
    @Override
    public void hideEmojiKeyboard() {
        transitions().apply(KeyboardTransition.boardLowered(CrossAxisRules.EMOJI_KEY_CODE));
    }

    /**
     * The layout half of the emoji close: restore the main keyboard view, take the palettes down
     * and leave EMOJI mode without UI callbacks. Owner ruling R1 — this returns to the ALPHABET,
     * never to a PKB symbol page.
     */
    void leaveEmojiModeInternal() {
        // Always restore mainKeyboardView to VISIBLE. showEmojiKeyboardInternal() sets it
        // GONE unconditionally, so we must restore it here to keep keyboard_frame sized
        // correctly (prevents IME window collapse and touch passthrough on the suggestion
        // strip). On PKB+VKB disabled the keyboard is visible but harmless — the user has
        // a physical keyboard and onEvaluateInputViewShown() always returns true.
        // Voice input uses bringToFront() to render on top of this view.
        this.mainKeyboardView.setVisibility(View.VISIBLE);
        // Null until the board has been opened once: nothing to hide or detach then.
        EmojiPalettesView emojiPalettesView = this.emojiPalettesView;
        if (emojiPalettesView != null) {
            emojiPalettesView.setVisibility(View.GONE);
            emojiPalettesView.detachPagerAdapter();
        }

        this.keyboardState.resetEmojiMode(); // Reset emoji mode state so next emoji tap will show emoji (not toggle off)

        // R1: the conditional clearPkbSymbolMode() that used to follow the board report is GONE.
        // It ran after the reset above had already turned EMOJI into ALPHABET, so its own
        // isInSymbolMode() guard was false and it never fired; the owner's ruling is that leaving
        // the emoji board returns to the alphabet, which is exactly what the reset does. The scoped
        // board report is the funnel's board column and runs right after this returns.
    }

    /**
     * The emoji key, from every path that has one: the hardware bridge, the key processor and the
     * board controller. {@code onEmojiInput()} is a TOGGLE, so the board-open prologue below only
     * runs when this press is going to open the board.
     */
    public void onEmojiKeyPressed() {
        beginEmojiBoardTransition();
        this.keyboardState.onEmojiInput();
    }

    /**
     * The {@code OPEN_BOARD} prologue for the emoji board — owner ruling R2.
     *
     * <p>Every other board clears a PKB symbol mode before it takes the screen
     * ({@code UnifiedInputBoardManager.beginBoardTransition}). Emoji did not, so on a PKB, opening
     * emoji from symbol mode left {@code symbolEntryMethod} at 2 or 3 with the mode already moved
     * on — and the next Sym press then showed the board with
     * {@code wasSymbolEnteredFromAlphabet()} false, so every hinted physical key typed its letter
     * instead of its symbol. Recorded as a contradiction in PHASE1A-SUMMARY.md; this is the fix.
     *
     * <p>It has to run <em>before</em> the state machine moves the mode to EMOJI, because
     * {@code clearPkbSymbolMode()} asks {@code isInSymbolMode()} — which is why the emoji board's
     * open is the {@code BOARD_OPENING} / {@code BOARD_RAISED} pair rather than one
     * {@code OPEN_BOARD}: its mechanism spans the mode change.
     */
    private void beginEmojiBoardTransition() {
        if (this.keyboardState != null && !this.keyboardState.isInEmojiMode()) {
            transitions().apply(KeyboardTransition.boardOpening(CrossAxisRules.EMOJI_KEY_CODE));
        }
    }

    /**
     * The emoji board has been raised by the state machine: {@code BOARD_RAISED(-11)}, which is the
     * {@code OPEN_BOARD} row's epilogue — raise the UIM bar if it is down, then report the open
     * through the UIM's single bookkeeping funnel. The prologue (the PKB symbol clear) ran in
     * {@link #beginEmojiBoardTransition()}, before the mode moved.
     *
     * <p>The bar raise and the report were written out by hand here before Phase 1d, which is why
     * this path could clear no symbol mode and the emoji board had two identities.
     */
    @Override
    public void showEmojiKeyboard() {
        transitions().apply(KeyboardTransition.boardRaised(CrossAxisRules.EMOJI_KEY_CODE));
        showEmojiKeyboardInternal();
        this.unifiedInputBoardManager.refresh();
    }

    private void showEmojiKeyboardInternal() {
        Keyboard c0965eM6733a = this.keyboardBuilder.getKeyboard(0);
        if (c0965eM6733a == null) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Emoji keyboard is unable to access the main keyboard");
            return;
        }
        // Hide flick suggestions when showing emoji picker
        this.blackberryIme.hideFlickSuggestions();
        // Hide only the keyboard view - emoji picker is now inside keyboard_frame
        // main_keyboard_frame stays VISIBLE so unified input bar remains above emoji picker
        this.mainKeyboardView.setVisibility(View.GONE);
        EmojiPalettesView emojiPalettesView = getEmojiPalettesView();
        if (emojiPalettesView == null) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Emoji keyboard view is unavailable");
            return;
        }
        emojiPalettesView.updateDeleteButton(this.keyboardLayoutSet.getText("keylabel_to_alpha"), this.keyboardLayoutSet.getText("keylabel_to_symbol"), this.mainKeyboardView.getKeyVisualAttribute(), c0965eM6733a.mIconsSet, c0965eM6733a.mId.mLocale.getLanguage());
        emojiPalettesView.setVisibility(View.VISIBLE);
        emojiPalettesView.setTabChanged(false);
        emojiPalettesView.setSwitchingToEmoji(true);
        updateKeyboardLayout();

        // Dynamic search: trigger search from composing text when enabled.
        if (SettingsManager.getInstance().getSettingsValues().isEmojiDynamicSearchEnabled) {
            String composingText = getComposingText();
            emojiPalettesView.onEmojiOpened(composingText);
        }
    }

    @Override
    public boolean isVkbSymbolCustomizationEnabled() {
        return this.sharedPreferences.getBoolean("enable_symbol_customization_vkb", false);
    }

    @Override
    public boolean isVkbCustomPageFirst() {
        return this.sharedPreferences.getBoolean("vkb_custom_page_first", false);
    }

    @Override
    public boolean isPkbSymbolCustomizationEnabled() {
        return this.sharedPreferences.getBoolean("enable_symbol_customization_pkb", false);
    }

    @Override
    public boolean isPkbCustomPageFirst() {
        return this.sharedPreferences.getBoolean("pkb_custom_page_first", false);
    }

    @Override
    public boolean isSymHeld() {
        // Owner decision, 2026-09-22: closing the symbol board after one symbol is the only
        // behaviour (the "Close symbol keyboard after symbol" setting is gone). Holding Sym is
        // how the board is kept open instead, so this is the one thing symbol mode asks about.
        //
        // Phase 1f: named after ModifierState.isSymHeld(), which is the same answer — but asked
        // through the tracker's cheap boolean rather than through a snapshot. This runs on the
        // per-keystroke path (twice: once from onHardwareKeyEvent and once from
        // onSoftwareSymbolCommitted), and getModifierState() builds a value object and recomputes
        // four span states and the interpreted meta to do it. The 1b agent raised that cost for
        // getSymbolPageOrder() and it applies here for the same reason; isSymKeyHeld() is one
        // SparseBooleanArray lookup, so the cheap query is the right one at this site.
        return this.blackberryIme.getPhysicalKeyboardStateTracker().isSymKeyHeld();
    }

    @Override
    public int getSymbolPageOrder() {
        // Delegate to the implementation below that handles batch input tracking
        if (this.keyboardState.getShiftModeTracker().isAutomaticShifted()) {
            return 5;
        }
        if (this.keyboardState.getShiftModeTracker().isManualShifted()) {
            return 1;
        }
        if (!this.keyboardState.getShiftModeTracker().isShiftLocked() || this.keyboardState.getShiftModeTracker().isShiftLockShifted()) {
            return (this.keyboardState.getShiftModeTracker().isShiftLockShifted() && this.keyboardState.isShiftKeyReleasing()) ? 3 : 0;
        }
        return 3;
    }

    @Override
    public void togglePkbSymbolShift() {
        // The caller turns the PKB symbol page over and immediately reloads the symbol keyboard,
        // which replaces the meta mask the old page installed. Same ALL-scope clear as before,
        // under the reason that says why.
        this.blackberryIme.getPhysicalKeyboardStateTracker()
                .resetModifiers(ModifierResetReason.KEYBOARD_RELOADED);
    }

    @Override
    public KeyRepeatHandler getKeyRepeatHandler() {
        return this.blackberryIme.getPhysicalKeyboardStateTracker().getKeyRepeatHandler();
    }

    @Override
    public void onReturnToAlphabetFromSymbol() {
        this.blackberryIme.restoreSuggestionStrip(false, true);
    }

    @Override
    public void updateShiftIndicator(boolean z) {
        MainKeyboardView mainKeyboardViewM6790T = getMainKeyboardView();
        if (mainKeyboardViewM6790T != null) {
            mainKeyboardViewM6790T.setShiftKeyState(z);
        }
    }

    @Override
    public void updateShiftLockedIndicator(boolean locked) {
        MainKeyboardView mainKeyboardView = getMainKeyboardView();
        if (mainKeyboardView != null) {
            mainKeyboardView.setShiftLocked(locked);
        }
    }

    @Override
    public void setAlphabetKeyboard(int i, int i2) {
        this.keyboardState.requestShiftMode(i, i2);
    }

    @Override
    public void onCharacterKey() {
        this.blackberryIme.updatePhysicalKeyboardFilter();
    }

    @Override
    public void onStartBatchInput() {
        MainKeyboardView mainKeyboardViewM6790T = getMainKeyboardView();
        if (mainKeyboardViewM6790T != null) {
            mainKeyboardViewM6790T.startDoubleTapShiftKeyTimer();
        }
    }

    @Override
    public void onKeyRelease() {
        MainKeyboardView mainKeyboardViewM6790T = getMainKeyboardView();
        if (mainKeyboardViewM6790T != null) {
            mainKeyboardViewM6790T.cancelDoubleTapShiftKeyTimer();
        }
    }

    public void onKeyReleaseExternal() {
        MainKeyboardView mainKeyboardViewM6790T = getMainKeyboardView();
        if (mainKeyboardViewM6790T != null) {
            mainKeyboardViewM6790T.cancelLongPressShiftKeyTimer();
        }
    }

    @Override
    public boolean shouldCapitalizeAfterSpace() {
        MainKeyboardView mainKeyboardViewM6790T = getMainKeyboardView();
        return mainKeyboardViewM6790T != null && mainKeyboardViewM6790T.isInDoubleTapShiftKeyTimeout();
    }

    @Override
    public boolean isManualTemporaryUppercase() {
        return DeviceProfile.current().hasShiftedSymbolKeyboard();
    }

    public void onInputCodeChanged(int i, int i2, int i3) {
        // -11 reaches KeyboardState.onInputCodeChanged as a code and lands on the same emoji
        // toggle onEmojiKeyPressed() drives, so it needs the same OPEN_BOARD prologue (R2).
        if (i == CrossAxisRules.EMOJI_KEY_CODE) {
            beginEmojiBoardTransition();
        }
        this.keyboardState.onInputCodeChanged(i, i2, i3);
    }

    public void onSoftwareSymbolCommitted(int i, int i2) {
        this.keyboardState.onSoftwareSymbolCommitted(i, i2);
    }

    public boolean isEmojiKeyboardShowing() {
        return this.keyboardState != null && this.keyboardState.isInEmojiMode();
    }

    public boolean isFccViewShowing() {
        FccView fccView = this.fccView;
        return fccView != null && fccView.isShown();
    }
    
    /**
     * Lazy getter for FccView.
     * Performance optimization: Inflates from ViewStub on first access,
     * saving ~10-20ms on startup when FCC is not immediately needed.
     */
    public FccView getFccView() {
        if (this.fccView == null && this.fccViewStub != null) {
            this.fccView = (FccView) this.fccViewStub.inflate();
        }
        return this.fccView;
    }
    
    /**
     * Lazy getter for VoiceInputView.
     * Performance optimization: Inflates from ViewStub on first access,
     * saving ~10-20ms on startup when voice input is not immediately needed.
     */
    public VoiceInputView getVoiceInputView() {
        if (this.voiceInputView == null && this.voiceInputViewStub != null) {
            this.voiceInputView = (VoiceInputView) this.voiceInputViewStub.inflate();
        }
        return this.voiceInputView;
    }

    /**
     * Lazy getter for NumberPadView: inflates from its ViewStub on first access.
     */
    public NumberPadView getNumberPadView() {
        if (this.numberPadView == null && this.numberPadViewStub != null) {
            this.numberPadView = (NumberPadView) this.numberPadViewStub.inflate();
            this.numberPadView.setVisibility(View.GONE);
        }
        return this.numberPadView;
    }

    /**
     * Non-inflating variant for state queries (isShowing/hide/refresh): a board that was
     * never opened must not cost a ViewStub inflation just to answer "not showing".
     */
    public NumberPadView peekNumberPadView() {
        return this.numberPadView;
    }

    public boolean isMainKeyboardShowing() {
        MainKeyboardView mainKeyboardView;
        return (isEmojiKeyboardShowing() || (mainKeyboardView = this.mainKeyboardView) == null || !mainKeyboardView.isShowingMoreKeysPanel()) ? false : true;
    }

    public View getActiveKeyboardView() {
        if (isEmojiKeyboardShowing()) {
            return this.emojiPalettesView;
        }
        return this.mainKeyboardView;
    }

    public MainKeyboardView getMainKeyboardView() {
        return this.mainKeyboardView;
    }

    public void cleanup() {
        MainKeyboardView mainKeyboardView = this.mainKeyboardView;
        if (mainKeyboardView != null) {
            mainKeyboardView.cancelAllOngoingEvents();
            this.mainKeyboardView.cleanup();
        }
        EmojiPalettesView emojiPalettesView = this.emojiPalettesView;
        if (emojiPalettesView != null) {
            emojiPalettesView.detachPagerAdapter();
        }
    }

    public void destroy() {
        MainKeyboardView mainKeyboardView = this.mainKeyboardView;
        if (mainKeyboardView != null) {
            mainKeyboardView.deallocateMemory();
            this.mainKeyboardView = null;
        }
        EmojiPalettesView emojiPalettesView = this.emojiPalettesView;
        if (emojiPalettesView != null) {
            this.emojiPalettesView.cleanup();
            this.emojiPalettesView = null;
        }
        UnifiedInputBoardManager c1011i = this.unifiedInputBoardManager;
        if (c1011i != null) {
            c1011i.destroy();
            this.unifiedInputBoardManager = null;
        }
        SlideboardManager c1083e = this.slideboardManager;
        if (c1083e != null) {
            c1083e.hide();
            this.slideboardManager = null;
        }
        // The singleton is process-lifetime (see INSTANCE); every view field must be released
        // here or the destroyed input_view tree stays reachable until the next createInputView.
        this.fccView = null;
        this.fccViewStub = null;
        this.voiceInputView = null;
        this.voiceInputViewStub = null;
        this.numberPadView = null;
        this.numberPadViewStub = null;
        this.emojiPalettesViewStub = null;
        this.mainKeyboardFrame = null;
        this.inputView = null;
        this.keyboardBuilder = null;
        this.switcherCallbacks = null;
        this.blackberryIme = null;
        this.sharedPreferences = null;
    }

    @SuppressLint({"InflateParams"})
    public View createInputView(boolean z) {
        // UIM-09 fix: Clean up previous sub-component instances before creating new ones
        // to prevent memory leaks from orphaned UIBM/emoji/slideboard managers.
        // NOTE: We can't call full destroy() here because it nulls blackberryIme/sharedPreferences
        // which are needed below. So we clean up only the sub-components.
        EmojiPalettesView oldEmoji = this.emojiPalettesView;
        if (oldEmoji != null) {
            oldEmoji.cleanup();
            this.emojiPalettesView = null;
        }
        UnifiedInputBoardManager oldUibm = this.unifiedInputBoardManager;
        if (oldUibm != null) {
            oldUibm.destroy();
            this.unifiedInputBoardManager = null;
        }
        SlideboardManager oldSlideboard = this.slideboardManager;
        if (oldSlideboard != null) {
            oldSlideboard.hide();
            this.slideboardManager = null;
        }
        
        MainKeyboardView mainKeyboardView = this.mainKeyboardView;
        if (mainKeyboardView != null) {
            mainKeyboardView.closing();
        }
        // Remembered for getEmojiPalettesView(), which applies it at first inflation.
        this.isHardwareAccelerated = z;
        ensureThemeContext(this.blackberryIme);
        this.inputView = (InputView) LayoutInflater.from(this.themeContext).inflate(R.layout.input_view, (ViewGroup) null);
        this.mainKeyboardFrame = this.inputView.findViewById(R.id.main_keyboard_frame);
        this.emojiPalettesViewStub = this.inputView.findViewById(R.id.emoji_palettes_view_stub);
        this.emojiPalettesView = null; // Will be inflated on demand via getEmojiPalettesView()
        // Performance: Use ViewStub for lazy inflation of FCC and voice input views
        this.fccViewStub = this.inputView.findViewById(R.id.fcc_view_stub);
        this.fccView = null; // Will be inflated on demand via getFccView()
        this.voiceInputViewStub = this.inputView.findViewById(R.id.voice_input_view_stub);
        this.voiceInputView = null; // Will be inflated on demand via getVoiceInputView()
        this.numberPadViewStub = this.inputView.findViewById(R.id.number_pad_view_stub);
        this.numberPadView = null; // Will be inflated on demand via getNumberPadView()
        this.mainKeyboardView = (MainKeyboardView) this.inputView.findViewById(R.id.keyboard_view);
        this.mainKeyboardView.setHardwareAcceleratedDrawingEnabled(z);
        this.mainKeyboardView.setKeyboardActionListener(this.blackberryIme);
        this.slideboardManager = new SlideboardManager(this.blackberryIme.getApplicationContext(), this.inputView, this.blackberryIme);
        this.unifiedInputBoardManager = new UnifiedInputBoardManager(this.blackberryIme.getApplicationContext(), this.inputView, this.blackberryIme);
        NumericSubpanelKeyboardView numericSubpanelKeyboardViewM6764am = createNumericSubpanel();
        QuickPhrasesView quickPhrasesViewM6765an = createQuickPhrasesView();
        this.slideboardManager.setSlideboardComponent((SlideboardComponent) numericSubpanelKeyboardViewM6764am);
        this.slideboardManager.setSlideboardComponent((SlideboardComponent) quickPhrasesViewM6765an);
        return this.inputView;
    }

    private NumericSubpanelKeyboardView createNumericSubpanel() {
        return (NumericSubpanelKeyboardView) LayoutInflater.from(this.themeContext).inflate(R.layout.slideboard_numeric_subpanel, (ViewGroup) this.inputView.findViewById(R.id.keyboard_frame), false);
    }

    private QuickPhrasesView createQuickPhrasesView() {
        return (QuickPhrasesView) LayoutInflater.from(this.themeContext).inflate(R.layout.slideboard_quickphrases, (ViewGroup) this.inputView.findViewById(R.id.keyboard_frame), false);
    }

    public KeyboardActionListenerInterface getKeyboardActionListener() {
        return this.blackberryIme;
    }

    public void updateSubtypeDisplay() {
        MainKeyboardView mainKeyboardView = this.mainKeyboardView;
        if (mainKeyboardView != null) {
            mainKeyboardView.setFunctionalKeyEnabled(this.subtypeManager.isShortcutImeReady());
        }
    }

    public boolean isShiftKeyPressed() {
        return this.keyboardState.isShiftKeyPressed();
    }

    public boolean isShiftKeyReleasing() {
        return this.keyboardState.isShiftKeyReleasing();
    }

    public boolean isShiftKeyMomentary() {
        return this.keyboardState.isShiftKeyMomentary();
    }

    public boolean isInSymbolMode() {
        return this.keyboardState.isInSymbolMode();
    }

    /**
     * <b>WHICH BOARD IS UP.</b> One question, one answer, across every keyboard — the point of
     * Phase 1d.
     *
     * <p>Before it, "which keyboard is loaded" ({@code KeyboardState.currentMode} plus the symbol
     * page and the entry method) and "which board is open"
     * ({@code UnifiedBoardCoordinator.activeBoard}) were two states with two owners and no query
     * that could answer both. Emoji sat on both at once, which is what made its open path
     * unmigratable in Phase 1a.
     *
     * <p>A panel board wins when one is open, because it is drawn over the main keyboard view.
     * Otherwise the main keyboard view is showing one of the typing boards, and which one is the
     * layout mode read through {@link CrossAxisRules}' board vocabulary. Never
     * {@link KeyboardTransition#NO_BOARD}: some board is always up, and with nothing else it is the
     * alphabet.
     */
    public int activeBoard() {
        UnifiedInputBoardManager uim = this.unifiedInputBoardManager;
        if (uim != null) {
            int panelBoard = uim.getBoardCoordinator().activeBoard();
            if (panelBoard != UnifiedBoardCoordinator.NO_BOARD) {
                return panelBoard;
            }
        }
        KeyboardState state = this.keyboardState;
        if (state == null) {
            return CrossAxisRules.ALPHABET_BOARD_KEY_CODE;
        }
        switch (state.currentMode()) {
            case SYMBOL:
                return CrossAxisRules.SYMBOL_BOARD_KEY_CODE;
            case EMOJI:
                return CrossAxisRules.EMOJI_KEY_CODE;
            case MENU:
                return CrossAxisRules.MENU_BOARD_KEY_CODE;
            case ALPHABET:
            default:
                return CrossAxisRules.ALPHABET_BOARD_KEY_CODE;
        }
    }

    /**
     * If the PKB is currently in symbol mode, reset state and reload the alphabet keyboard.
     * Called when any UIM panel opens so the underlying mainKeyboardView has the alphabet
     * layout loaded before the panel is dismissed.
     */
    public void clearPkbSymbolMode() {
        if (isPkbDevice() && this.keyboardState.isInSymbolMode()) {
            this.keyboardState.resetSymbolMode();
            setAlphabetKeyboard(0);
            onReturnToAlphabetFromSymbol();
        }
    }

    @Override // SymbolPageProvider
    public boolean isManualShiftAndShiftPressing() {
        return this.keyboardState.getShiftModeTracker().isManualShifted() && this.keyboardState.isShiftKeyReleasing();
    }


    /**
     * NOT an element id despite the name: this returns the layout set's {@code supportedScript}
     * id (latin = 14, han = 7, thai = 20, ...), which callers forward to word-boundary
     * classification. Do NOT compare it against {@link KeyboardId} element constants.
     * The name is kept only because five out-of-package call sites use it.
     */
    public int getKeyboardElementId() {
        KeyboardBuilder c0978h = this.keyboardBuilder;
        if (c0978h == null) {
            return -1;
        }
        return c0978h.getSupportedScriptId();
    }

    /**
     * Get the KeyboardBuilder for external components like AuxBarManager.
     */
    @Nullable
    public KeyboardBuilder getKeyboardBuilder() {
        return this.keyboardBuilder;
    }

    public boolean onSymbolShiftToggle(int i, int i2, boolean z, boolean z2) {
        // The page turn does not know yet whether it lands on another symbol page or back on the
        // alphabet, so the layout is UNCHANGED — only the board column applies.
        transitions().apply(KeyboardTransition.switchLayout(KeyboardTransition.Layout.UNCHANGED));
        Keyboard c0965eM6834l = getCurrentKeyboard();
        if (c0965eM6834l == null) {
            return false;
        }
        if (c0965eM6834l.getKeyByCode(-3) == null && (!c0965eM6834l.isTouchKeyboard() || c0965eM6834l.isNumberOrDatetimeVariation())) {
            return false;
        }
        this.keyboardState.onSymbolShiftToggle(i, i2, z, z2);
        return true;
    }

    public void onSymbolKeyLongPress(int i, int i2) {
        this.keyboardState.onSymbolKeyLongPress(i, i2);
    }

    public Key getKeyByPhysicalScanCode(int i, boolean z) {
        Keyboard c0965eM6834l;
        if (!this.keyboardState.wasSymbolEnteredFromAlphabet() || (c0965eM6834l = getCurrentKeyboard()) == null) {
            return null;
        }
        Key keyM6611d = c0965eM6834l.getKeyByScanCode(i);
        if (keyM6611d != null) {
            this.mainKeyboardView.setKeyPressed(keyM6611d, z);
        }
        return keyM6611d;
    }

    @Override // dev.bbkb.ime.core.KeyboardLayoutCallback
    public String[] getMoreKeysForKey(String str) {
        Keyboard c0965eM6834l = getCurrentKeyboard();
        if (c0965eM6834l != null) {
            return c0965eM6834l.getMultiTapAlternates(str);
        }
        return null;
    }

    @Override // dev.bbkb.ime.core.KeyboardLayoutCallback
    public String[] getMoreKeysForKeyByStyle(String str) {
        Keyboard c0965eM6834l = getCurrentKeyboard();
        if (c0965eM6834l == null) {
            return null;
        }
        if (c0965eM6834l.mId.mElementId < 100 && hasShiftedSymbolKeyboard()) {
            return this.keyboardBuilder.getKeyboardInternal(c0965eM6834l.mId.mElementId, true).getMultiTapSequence(str);
        }
        return c0965eM6834l.getMultiTapSequence(str);
    }

    public String[] getMoreKeysForCode(int i) {
        Keyboard c0965eM6834l = getCurrentKeyboard();
        if (c0965eM6834l != null) {
            return c0965eM6834l.getKeyLabelSetForCode(i);
        }
        return null;
    }
    
    /**
     * Get more keys for a physical key by its scan code.
     * This mirrors the original smali logic in KeyboardSwitcher.SuggestedWordInfo(KeyEvent)String
     * which looked up the Key from the keyboard layout by scanCode and returned its moreKeys.
     * 
     * The original smali appended the key label to the moreKeys string.
     * It also required wasSymbolEnteredFromAlphabet() to be true.
     * 
     * @param scanCode The physical key's scan code
     * @return The moreKeys string for the key (with label appended), or null if not found
     */
    @Nullable
    public String getMoreKeysForScanCode(int scanCode) {
        // Original smali checked wasSymbolEnteredFromAlphabet() first
        if (!this.keyboardState.wasSymbolEnteredFromAlphabet()) {
            return null;
        }
        
        Keyboard keyboard = getCurrentKeyboard();
        if (keyboard == null) {
            return null;
        }
        
        Key key = keyboard.getKeyByScanCode(scanCode);
        if (key == null) {
            return null;
        }
        
        String moreKeys = key.getMoreKeysSpecString();
        if (moreKeys == null) {
            return "";
        }
        
        // Original smali appended the key label to moreKeys
        String label = key.getLabel();
        if (label != null) {
            return moreKeys + label;
        }
        return moreKeys;
    }

    @Override // dev.bbkb.ime.core.KeyboardLayoutCallback
    public String getKeyLabelForKeyEvent(KeyEvent keyEvent) {
        Key keyM6798a = getKeyByPhysicalScanCode(keyEvent.getScanCode(), true);
        if (keyM6798a == null) {
            return null;
        }
        String strM6224aj = keyM6798a.getMoreKeysSpecString();
        if (strM6224aj == null) {
            return "";
        }
        return strM6224aj + keyM6798a.getLabel();
    }

    public boolean hasShiftedSymbolKeyboard() {
        KeyboardBuilder c0978h = this.keyboardBuilder;
        if (c0978h != null) {
            return c0978h.hasPkbLayout();
        }
        return false;
    }

    @Override // KeyCharacterInterpreter
    public KeyCharacterResult.Interpretation interpretKeyCharacter(KeyEvent keyEvent, int i) {
        int iM6232c;
        String strM7448e;
        int iM6596a;
        if (!dev.bbkb.ime.core.device.detection.KeyEventDeviceClassifier.getInstance().isPhysicalKeyboardEvent(keyEvent)) {
            return null;
        }
        int keyCode = keyEvent.getKeyCode();
        Key keyM6798a = getKeyByPhysicalScanCode(keyEvent.getScanCode(), true);
        if (dev.bbkb.ime.core.shared.InputPathDebug.on()) {
            Keyboard cur = getCurrentKeyboard();
            dev.bbkb.ime.core.shared.Logger.info("CKB_SWIPE_TYPE_DEBUG",
                    "interpretKeyCharacter: keyCode=" + keyCode + " scanCode=" + keyEvent.getScanCode()
                    + " keyboardId=" + (cur == null ? "null" : String.valueOf(cur.mId.mElementId))
                    + " symbolFromAlphabet=" + this.keyboardState.wasSymbolEnteredFromAlphabet()
                    + " keyByScanCode=" + (cur == null ? "n/a" : (cur.getKeyByScanCode(keyEvent.getScanCode()) == null ? "null" : "found"))
                    + " key=" + (keyM6798a == null ? "null" : ("code=" + keyM6798a.getCode() + " active=" + keyM6798a.isActive() + " label=" + keyM6798a.getLabel())));
        }
        if (keyM6798a != null && keyM6798a.isActive()) {
            MoreKeySpec c1065xM6228b = keyM6798a.getKeySpec();
            if (c1065xM6228b != null && c1065xM6228b.getOutputText() != null) {
                iM6232c = -4;
                strM7448e = c1065xM6228b.getOutputText();
            } else {
                iM6232c = keyM6798a.getCode();
                strM7448e = null;
            }
        } else {
            Keyboard c0965eM6834l = getCurrentKeyboard();
            if (c0965eM6834l == null || !this.keyboardState.wasSymbolEnteredFromAlphabet() || (iM6596a = c0965eM6834l.getNumberForKeyEvent(keyEvent)) == 0) {
                iM6232c = keyCode;
                strM7448e = null;
            } else {
                iM6232c = iM6596a;
                strM7448e = null;
            }
        }
        if (strM7448e != null) {
            return new KeyCharacterResult.Interpretation(strM7448e);
        }
        if (iM6232c != keyCode) {
            return new KeyCharacterResult.Interpretation(iM6232c);
        }
        return null;
    }

    public KeyCharacterInterpreter.MetaMask getKeyCharacterMap() {
        Keyboard c0965eM6834l;
        if (this.keyboardState.wasSymbolEnteredFromAlphabet() && (c0965eM6834l = getCurrentKeyboard()) != null) {
            return c0965eM6834l.getDefaultKeyInterpretation();
        }
        return KeyCharacterInterpreter.MetaMask.IDENTITY;
    }

    private boolean shouldShowHints() {
        return (isPkbDevice() && this.keyboardState.isInSymbolMode() && !this.keyboardState.wasSymbolEnteredFromAlphabet()) ? false : true;
    }

    public Context getContext() {
        return this.blackberryIme;
    }

    /**
     * Whether a UIM <b>panel</b> board is drawn over the main keyboard view. Gates cursor mode and
     * the suggestion strip, both of which care about the panel and not about which keyboard is
     * loaded behind it — so this is the panel query, not {@link #activeBoard()}.
     */
    public boolean isUnifiedInputBoardShowing() {
        UnifiedInputBoardManager c1011i = this.unifiedInputBoardManager;
        return c1011i != null && c1011i.isPanelBoardShowing();
    }

    public void onUnifiedInputBoardAction(int i) {
        if (i == 0) {
            requestShiftOff();
        } else {
            this.unifiedInputBoardManager.onMainKeyboardViewVisibilityChanged(i);
        }
    }

    public void requestUpdateSuggestions() {
        BlackBerryIME blackBerryIME = this.blackberryIme;
        if (blackBerryIME != null) {
            blackBerryIME.refreshSubtypeSwitcher();
        }
    }
}

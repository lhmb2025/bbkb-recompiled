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
import dev.bbkb.ime.core.ime.ImeResourcesContext;
import dev.bbkb.ime.core.engine.learning.DynamicLearningManager;
import dev.bbkb.ime.core.SymbolPageProvider;
import dev.bbkb.ime.core.locale.RichInputMethodManager;
import dev.bbkb.ime.core.ime.InputView;
import dev.bbkb.ime.core.KeyboardLayoutCallback;
import dev.bbkb.ime.core.engine.NuanceSDKManager;
import dev.bbkb.ime.core.engine.EngineThreads;
import dev.bbkb.ime.core.locale.SubtypeManager;
import dev.bbkb.ime.core.layout.PhysicalKeyResolver;
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
import dev.bbkb.ime.keyboard.internal.VkbShiftModeTracker;
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

    // Process-lifetime singleton that holds Views; destroy() releases every one of them.
    @SuppressLint({"StaticFieldLeak"})
    private static final KeyboardSwitcher INSTANCE = new KeyboardSwitcher();

    private SubtypeManager subtypeManager;

    private SharedPreferences sharedPreferences;

    private InputView inputView;

    private View mainKeyboardFrame;

    private MainKeyboardView mainKeyboardView;

    private EmojiPalettesView emojiPalettesView;

    // The boards inflate lazily from these stubs on first open.
    private ViewStub emojiPalettesViewStub;

    private FccView fccView;

    private ViewStub fccViewStub;
    
    private VoiceInputView voiceInputView;

    private ViewStub voiceInputViewStub;

    private NumberPadView numberPadView;

    private ViewStub numberPadViewStub;

    private UnifiedInputBoardManager unifiedInputBoardManager;

    private SlideboardManager slideboardManager;

    private BlackBerryIME blackberryIme;

    /** Built lazily so it also exists when the constructor never ran (JVM tests). */
    private KeyboardStateCoordinator stateTransitions;

    /**
     * The single entry point for layout, board, cursor-mode and bar changes. What each does to the
     * other axes is stated once, in {@link CrossAxisRules}.
     */
    public KeyboardStateCoordinator transitions() {
        if (this.stateTransitions == null) {
            this.stateTransitions = new KeyboardStateCoordinator(new SwitcherAxes());
        }
        return this.stateTransitions;
    }

    /** The production {@link KeyboardStateCoordinator.Axes}: mechanism only, no decisions. */
    private final class SwitcherAxes implements KeyboardStateCoordinator.Axes {
        @Override
        public void leaveEmojiMode() {
            leaveEmojiModeInternal();
        }

        @Override
        public void clearPkbSymbolMode() {
            KeyboardSwitcher.this.clearPkbSymbolMode();
        }

        @Override
        public boolean isBoardUp(int keyCode) {
            UnifiedInputBoardManager uim = KeyboardSwitcher.this.unifiedInputBoardManager;
            return uim != null && uim.isBoardViewShowing(keyCode);
        }

        /**
         * Means a panel board is open ({@code isAnyBoardShowing()} delegates to
         * {@code isPanelBoardShowing()}); KeyboardSwitcherLayoutTransitionTest stubs this name.
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

    /** The IME service {@link #themeContext} was built on. */
    private Context themeContextIme;

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
     * Inflates the emoji board from its ViewStub on first access. State queries and hide paths
     * must use {@link #peekEmojiPalettesView()} so an unopened board costs no inflation.
     */
    public EmojiPalettesView getEmojiPalettesView() {
        if (this.emojiPalettesView == null && this.emojiPalettesViewStub != null) {
            this.emojiPalettesView = (EmojiPalettesView) this.emojiPalettesViewStub.inflate();
            // Start hidden so an inflation from any other path cannot briefly cover the keyboard.
            this.emojiPalettesView.setVisibility(View.GONE);
            this.emojiPalettesView.setHardwareAcceleratedDrawingEnabled(this.isHardwareAccelerated);
            this.emojiPalettesView.setKeyboardActionListener(this.blackberryIme);
        }
        return this.emojiPalettesView;
    }

    /** Non-inflating variant for state queries and hide paths. Null until first open. */
    public EmojiPalettesView peekEmojiPalettesView() {
        return this.emojiPalettesView;
    }

    /** The composing text, which seeds the emoji board's search. */
    public String getComposingText() {
        if (this.blackberryIme == null) return "";
        InputLogic logic = this.blackberryIme.getInputLogic();
        if (logic == null) return "";
        return logic.mComposingTracker.isComposing() ? logic.mComposingTracker.getComposingText() : "";
    }

    /** Discards the composing word (emoji replace mode), resetting all composing state. */
    public void clearComposingText() {
        if (this.blackberryIme == null) return;
        InputLogic logic = this.blackberryIme.getInputLogic();
        if (logic == null) return;

        int composingLength = logic.mComposingTracker.isComposing() ? logic.mComposingTracker.getComposingText().length() : 0;

        if (logic.mComposingTracker.isComposing() || logic.mTouchHighlightTracker.isHighlightActive()) {
            logic.mRichInputConnection.finishComposingText();
            logic.mTouchHighlightTracker.clear();
            logic.mComposingTracker.clearAll();
        }

        logic.clearComposingText(true);

        // finishComposingText committed the word; delete it from the editor.
        if (composingLength > 0) {
            logic.mRichInputConnection.deleteSurroundingText(composingLength, 0);
        }
    }

    /** Commits the composing word (emoji append mode). */
    public void commitComposingText() {
        if (this.blackberryIme == null) return;
        InputLogic logic = this.blackberryIme.getInputLogic();
        if (logic == null) return;
        logic.commitTouchEventText();
    }

    /** Whether the field asked for no personalized learning; the emoji board then keeps no recents. */
    public boolean isNoPersonalizedLearning() {
        if (this.blackberryIme == null) return false;
        DynamicLearningManager learning = this.blackberryIme.getDynamicLearningManager();
        return learning != null && learning.isNoPersonalizedLearning();
    }

    private KeyboardSwitcher() {
    }

    /** @param switcherCallbacks the IME's switcher callbacks */
    public static void initialize(BlackBerryIME blackBerryIME, SwitcherCallbacks switcherCallbacks) {
        INSTANCE.initializeInternal(blackBerryIME, switcherCallbacks, PrefsManager.INSTANCE.getPrefs());
    }

    private void initializeInternal(BlackBerryIME blackBerryIME, SwitcherCallbacks switcherCallbacks, SharedPreferences sharedPreferences) {
        this.blackberryIme = blackBerryIME;
        this.switcherCallbacks = switcherCallbacks;
        this.sharedPreferences = sharedPreferences;
        this.subtypeManager = SubtypeManager.getInstance();
        this.keyboardState = new KeyboardState(this);
        // Always false: the @hide enableHardwareAcceleration is not reachable by reflection.
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

    /** What the cached keyboards were built against; see {@link #ensureThemeContext}. */
    private String keyboardCacheFingerprint;

    /**
     * The themed context never changes with the theme (palettes apply at draw time), but it reads
     * resources through one IME service instance, so it is rebuilt for each new service: a stale
     * one answers with the dead service's frozen configuration (wrong orientation sizing).
     */
    private void ensureThemeContext(Context context) {
        if (this.themeContext == null || this.themeContextIme != context) {
            this.themeContext = new ContextThemeWrapper(new ImeResourcesContext(context), R.style.KeyboardTheme_LXX);
            this.themeContextIme = context;
            // Cached keyboards survive a new service as long as this fingerprint matches (their
            // icon set rebinds to the live Resources on every match); any change drops the cache.
            final Resources resources = this.themeContext.getResources();
            final android.content.res.Configuration config = resources.getConfiguration();
            final String fingerprint = config.densityDpi + "/" + config.fontScale + "/" + config.uiMode + "/"
                    + config.orientation + "/" + config.screenWidthDp + "x" + config.screenHeightDp + "/"
                    + androidx.core.os.ConfigurationCompat.getLocales(config).toLanguageTags()
                    + "/" + ResourceConfigManager.getKeyboardHeight(resources);
            if (!fingerprint.equals(this.keyboardCacheFingerprint)) {
                this.keyboardCacheFingerprint = fingerprint;
                KeyboardBuilder.onSystemLocaleChanged();
            }
        }
    }

    /**
     * @param code the key code: -23 (input menu toggle key) or -10 (space bar)
     * @param iconName the secondary icon's name
     */
    private void updateSecondaryKeySpec(int code, String iconName) {
        Key keyM6604b;
        MoreKeySpec c1065xM7444a;
        Keyboard c0965eM6834l = getCurrentKeyboard();
        if (c0965eM6834l == null) {
            return;
        }
        if (code == -23) {
            keyM6604b = c0965eM6834l.getKeyByOutputText("inputBoardToggleKeyStyle");
            if (keyM6604b == null) {
                keyM6604b = c0965eM6834l.getKeyByOutputText("numInputBoardToggleKeyStyle");
            }
        } else {
            keyM6604b = code == -10 ? c0965eM6834l.getKeyByCode(32) : null;
        }
        if (keyM6604b == null) {
            return;
        }
        boolean zM6181C = keyM6604b.hasLongPressKey();
        boolean zM6769d = shouldShowSecondaryIcon(code);
        if (zM6181C == zM6769d) {
            return;
        }
        if (zM6769d) {
            c1065xM7444a = new MoreKeySpec(null, KeyboardIconSet.getIconId(iconName), code, null);
        } else {
            c1065xM7444a = MoreKeySpec.getEmpty();
        }
        if (c0965eM6834l.replaceKey(keyM6604b, new Key(keyM6604b.getKeySpec(), c1065xM7444a, KeyHintPosition.TOP_RIGHT_CORNER, keyM6604b.getHintLabel(), keyM6604b.getLabelFlags(), keyM6604b.getBackgroundType(), keyM6604b.getX(), keyM6604b.getY(), keyM6604b.getWidth() + c0965eM6834l.mHorizontalGap, keyM6604b.getHeight() + c0965eM6834l.mVerticalGap, c0965eM6834l.mHorizontalGap, c0965eM6834l.mVerticalGap, keyM6604b.getOutputText(), keyM6604b.getMoreKeys(), keyM6604b.getKeyLabelSet(), keyM6604b.getMoreKeysFlags(), keyM6604b.getActionFlags(), keyM6604b.getScanCode()))) {
            c0965eM6834l.rebuildProximityGrid();
            return;
        }
        if (BuildConfig.DEBUG) Log.e(TAG, "Could not replace the secondary key spec of the key");
    }

    /** @param code the key code, as for {@link #updateSecondaryKeySpec} */
    private boolean shouldShowSecondaryIcon(int code) {
        if (code == -23) {
            return SettingsManager.getInstance().getSettingsValues().isUimEnabled();
        }
        if (code != -10) {
            return false;
        }
        // The globe on the space bar: whenever there is another language to switch to.
        return RichInputMethodManager.getInstance().hasMultipleEnabledSubtypesInThisIme(false);
    }

    /**
     * @param autoCapsFlags the auto-caps flags (getCapsMode)
     * @param recapitalizeMode the recapitalize mode
     */
    public void startInput(EditorInfo editorInfo, int autoCapsFlags, int recapitalizeMode) throws Resources.NotFoundException {
        if (this.blackberryIme == null) {
            return;
        }
        // destroy() drops the themed context; a start before the next createInputView needs it.
        ensureThemeContext(this.blackberryIme);
        KeyboardBuilder.Builder aVar = new KeyboardBuilder.Builder(this.themeContext, editorInfo);
        Resources resources = this.themeContext.getResources();
        aVar.setKeyboardGeometry(ResourceConfigManager.getScreenWidthPixels(resources), ResourceConfigManager.getKeyboardHeight(resources));
        aVar.setSubtype(getSubtypeForEditorInfo(editorInfo, this.subtypeManager.getCurrentSubtype()));
        aVar.useChosenLetterLayout();
        aVar.setVoiceInputKeyEnabled(InputMethodHelper.shouldShowVoiceInputKey());
        aVar.setLanguageSwitchKeyEnabled(this.blackberryIme.shouldShowLanguageSwitchKey());
        aVar.setLanguageQuickSwitchKeyEnabled(SettingsManager.isLanguageQuickSwitchEnabled());
        aVar.setInputBoardBarEnabled(this.blackberryIme.isUimEnabled());
        this.keyboardBuilder = aVar.build();
        try {
            this.keyboardState.onStartInput(autoCapsFlags, recapitalizeMode, this.subtypeManager.getCurrentSubtypeLocale(), editorInfo.inputType, editorInfo.packageName);
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

    /** @param inputType the editor's input type */
    public void saveKeyboardState(int inputType) {
        if (getCurrentKeyboard() != null || isEmojiKeyboardShowing()) {
            this.keyboardState.saveKeyboardState(inputType);
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

    /** @param keyboard the keyboard to show */
    private void setKeyboard(Keyboard keyboard) {
        SettingsValues c0804dM5050c = SettingsManager.getInstance().getSettingsValues();
        // A new layout in the main view means the emoji board is down. Scoped to -11: an unscoped
        // "nothing is open" would make the coordinator forget an open voice board.
        transitions().apply(KeyboardTransition.boardLowered(CrossAxisRules.EMOJI_KEY_CODE));
        MainKeyboardView mainKeyboardView = this.mainKeyboardView;
        if (mainKeyboardView != null) {
            // Tint first: MainKeyboardView.setKeyboard captures the shift/alt icon instances.
            if (keyboard != null && keyboard.mIconsSet != null) {
                keyboard.mIconsSet.applyColorTint();
            }
            mainKeyboardView.setKeyboard(keyboard);
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
            // Engine frame: the VKB frame (sid=1 + SetKeyboardSize stretch to the real view dims)
            // only when the current element is VKB-family (!isTouchKb) AND its view is displayed.
            // Do not use DeviceProfile.isOnScreenKeyboardVisible(): its UIM term is true during
            // ordinary pad typing (aux bar). In the VKB frame pad keys become regional soup and
            // pad swipes decode garbage until the next sync.
            MainKeyboardView mkvForSync = getMainKeyboardView();
            // isShown() is not enough: in pad mode the view stays VISIBLE but collapsed.
            boolean keyboardViewDisplayed = mkvForSync != null && mkvForSync.isShown()
                    && mkvForSync.getHeight() > 0 && mkvForSync.getWidth() > 0;
            // The replay rig fakes a vkb-forced window with engine-space pad events: keep it on PKB.
            boolean onScreenSurface = !isTouchKb && keyboardViewDisplayed && !isGestureReplayRig();
            if (InputPathDebug.on()) android.util.Log.i("CKB_SWIPE_TYPE_DEBUG",
                    "syncKeyboardLayout surface: mkv=" + (mkvForSync != null)
                    + " shown=" + (mkvForSync != null && mkvForSync.isShown())
                    + " h=" + (mkvForSync != null ? mkvForSync.getHeight() : -1)
                    + " isTouchKb=" + isTouchKb
                    + " -> onScreenSurface=" + onScreenSurface);
            // CKB maps in Java (ckb-y-warp, engine scale 0,0); VKB maps in the engine
            // (SetKeyboardSize stretch). Stretching a CKB too would double-warp.
            boolean vkbSync = onScreenSurface;
            if (InputPathDebug.on()) android.util.Log.i("CKB_SWIPE_TYPE_DEBUG", "KeyboardSwitcher.syncKeyboardLayout: isTouchKb=" + isTouchKb + " onScreenSurface=" + onScreenSurface + " width=" + width + " height=" + height + " locale=" + localeStr + " keyboardId=" + c0965eM6834l.mId.mElementId);
            // Read on main, applied in one engine-thread op, so input posted after this is fed
            // against the new layout (FIFO). The main thread never waits for the engine lock.
            // The Gesture Lab key-grid capture rides the same op; needsCapture() is a cost guard
            // only (repeated getKeys() reads do not perturb the engine; the shim at 0x20fc0 is pure).
            final LayoutSync sync = new LayoutSync(vkbSync, localeStr, width, height,
                    isTouchKb && dev.bbkb.ime.core.gesture.arbiter.CkbKeyGridCapture.INSTANCE.needsCapture(width, height)
                            ? this.blackberryIme : null);
            sLastLayoutSync = sync;
            EngineThreads.post(sync::apply);
            updateCangjieKey();
        } else {
            if (InputPathDebug.on()) android.util.Log.w("CKB_SWIPE_TYPE_DEBUG", "KeyboardSwitcher.syncKeyboardLayout: getCurrentKeyboard() returned NULL — layout NOT synced to NuanceSDK");
        }
    }

    /**
     * The last layout requested, re-applied by {@link #resyncLayoutAfterLanguageLoad} because a
     * language load re-creates the engine core. Written on the main thread only.
     */
    private static volatile LayoutSync sLastLayoutSync;

    /**
     * Called on the loader thread when a language load ends: re-applies the layout at the front of
     * the engine queue so no queued input reaches the new language first. Not inline: waiting
     * engine ops could take the lock between the load and the sync.
     */
    public static void resyncLayoutAfterLanguageLoad() {
        final LayoutSync sync = sLastLayoutSync;
        if (sync == null) return;
        final LayoutSync noCapture = sync.withoutGridCapture();
        if (EngineThreads.onLoaderThread()) {
            EngineThreads.engineHandler().postAtFrontOfQueue(() -> {
                try {
                    noCapture.apply();
                } catch (RuntimeException e) {
                    Log.e(TAG, "layout re-sync after the language load failed", e);
                }
            });
        } else {
            EngineThreads.postAtFront(noCapture::apply);
        }
    }

    /** One layout sync's values, computed on the main thread; {@link #apply} runs on the engine thread. */
    private static final class LayoutSync {
        final boolean vkbSync;
        final String localeStr;
        final int width;
        final int height;
        /** Non-null when the Gesture Lab key grid should be captured after the sync. */
        final android.content.Context captureContext;

        LayoutSync(boolean vkbSync, String localeStr, int width, int height,
                android.content.Context captureContext) {
            this.vkbSync = vkbSync;
            this.localeStr = localeStr;
            this.width = width;
            this.height = height;
            this.captureContext = captureContext;
        }

        LayoutSync withoutGridCapture() {
            return captureContext == null ? this
                    : new LayoutSync(vkbSync, localeStr, width, height, null);
        }

        void apply() {
            // Null when the engine failed to load; the keyboard must still come up.
            com.blackberry.nuanceshim.NuanceSDK engine = NuanceSDKManager.getInstance();
            if (engine == null) return;
            engine.syncKeyboardLayout(vkbSync, localeStr, width, height);
            if (captureContext != null) {
                try {
                    dev.bbkb.ime.core.gesture.arbiter.CkbKeyGridCapture.INSTANCE.capture(
                            captureContext, engine.getKeys(), width, height);
                } catch (Throwable ignored) {
                    // Debug-only overlay capture.
                }
            }
        }
    }

    /**
     * True on the emulator gesture-replay rig (debug.et9.replayvkbpkg set): its engine-space pad
     * events would double-scale under the VKB stretch. Not cached, so clearing the prop takes effect.
     */
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

    /**
     * The layout set the keyboard on screen was built from (including a chosen one such as
     * {@code colemak}), or null; the engine's on-screen KDB follows it.
     */
    public String getCurrentKeyboardLayoutSet() {
        final Keyboard keyboard = getCurrentKeyboard();
        return keyboard != null && keyboard.mId != null ? keyboard.mId.keyboardLayoutSet() : null;
    }

    /**
     * The subtype's own layout set, or null. When {@link #getCurrentKeyboardLayoutSet()} differs
     * from it, a chosen layout is on screen.
     */
    public String getCurrentKeyboardOwnLayoutSet() {
        final Keyboard keyboard = getCurrentKeyboard();
        final InputMethodSubtype subtype = keyboard != null && keyboard.mId != null ? keyboard.mId.mSubtype : null;
        return subtype != null ? ResourceLocaleUtils.getKeyboardLayoutSetName(subtype) : null;
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

    /**
     * @param autoCapsFlags the auto-caps flags (getCapsMode)
     * @param recapitalizeMode the recapitalize mode
     */
    public void onBackspaceInSymbolMode(int autoCapsFlags, int recapitalizeMode) {
        this.keyboardState.onBackspaceInSymbolMode(autoCapsFlags, recapitalizeMode);
    }

    /**
     * @param code the key code
     * @param isSinglePointer true if this is the only active pointer
     * @param autoCapsFlags the auto-caps flags (getCapsMode)
     * @param recapitalizeMode the recapitalize mode
     */
    public void onCodeInput(int code, boolean isSinglePointer, int autoCapsFlags, int recapitalizeMode) {
        boolean zM7248e = this.keyboardState.wasSymbolEnteredFromAlphabet();
        this.keyboardState.onCodeInput(code, isSinglePointer, autoCapsFlags, recapitalizeMode);
        if (!zM7248e || this.keyboardState.wasSymbolEnteredFromAlphabet()) {
            return;
        }
        this.mainKeyboardView.releaseCurrentlyPressedKeys();
        this.mainKeyboardView.setHintsEnabled(shouldShowHints());
        this.blackberryIme.updatePhysicalKeyboardFilter();
    }

    /**
     * @param code the key code
     * @param withSliding true if released during sliding input
     * @param autoCapsFlags the auto-caps flags (getCapsMode)
     * @param recapitalizeMode the recapitalize mode
     */
    public void onCodeRelease(int code, boolean withSliding, int autoCapsFlags, int recapitalizeMode) {
        KeyboardState c1028ak = this.keyboardState;
        if (c1028ak != null) {
            c1028ak.onCodeRelease(code, withSliding, autoCapsFlags, recapitalizeMode);
        }
    }

    /**
     * @param autoCapsFlags the auto-caps flags (getCapsMode)
     * @param recapitalizeMode the recapitalize mode
     */
    public void onHardwareKeyEvent(KeyEvent keyEvent, int autoCapsFlags, int recapitalizeMode) {
        this.keyboardState.onHardwareKeyEvent(keyEvent.getKeyCode(), autoCapsFlags, recapitalizeMode);
    }

    /**
     * @param autoCapsFlags the auto-caps flags (getCapsMode)
     * @param recapitalizeMode the recapitalize mode
     */
    public void onMomentaryStateFinish(int autoCapsFlags, int recapitalizeMode) {
        this.keyboardState.onMomentaryStateFinish(autoCapsFlags, recapitalizeMode);
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

    /** @param elementId the shift mode: 0 off, 1 shifted once, 2 automatic, 3 locked, 4 momentary */
    private void setAlphabetKeyboard(int elementId) {
        // A physical key can reach here before startInput() built the keyboard (cold start). The
        // shift mode is already in keyboardState; startInput() builds from it.
        if (this.keyboardBuilder == null || this.blackberryIme == null) {
            return;
        }
        transitions().apply(KeyboardTransition.switchLayout(KeyboardTransition.Layout.ALPHABET));
        boolean zM4042U = this.blackberryIme.refreshOnScreenKeyboardShowing();
        if (!zM4042U && this.slideboardManager != null) {
            this.slideboardManager.show();
        }
        setKeyboard(this.keyboardBuilder.getKeyboardForShift(elementId, !zM4042U));
    }

    @Override
    public void setVkbSymbolsKeyboard(int symbolPage, boolean usePkbLayout, boolean isCustomPage, int customSymbolPage) {
        Keyboard c0965eM6734a;
        transitions().apply(KeyboardTransition.switchLayout(KeyboardTransition.Layout.SYMBOL));
        if (isCustomPage) {
            this.keyboardBuilder.setCustomSymbolPage(customSymbolPage);
            c0965eM6734a = this.keyboardBuilder.getKeyboardForShift(8, usePkbLayout && !this.blackberryIme.refreshOnScreenKeyboardShowing());
        } else {
            int i3 = symbolPage + 5;
            boolean z3 = this.sharedPreferences.getBoolean("enable_symbol_customization_vkb", false);
            boolean z4 = this.sharedPreferences.getBoolean("vkb_custom_page_first", false);
            if (z3 && z4) {
                i3--;
            }
            c0965eM6734a = this.keyboardBuilder.getKeyboardForShift(i3, usePkbLayout && !this.blackberryIme.refreshOnScreenKeyboardShowing());
            replaceCurrencyKey(c0965eM6734a);
        }
        setKeyboard(c0965eM6734a);
    }

    @Override
    public void setPkbSymbolsKeyboard(int symbolPage, boolean usePkbLayout, boolean isCustomPage, int customSymbolPage) {
        Keyboard c0965eM6735b;
        Key keyM6604b;
        // The bar is left as it is: the Sym board comes up under whichever bar is showing.
        transitions().apply(KeyboardTransition.switchLayout(KeyboardTransition.Layout.SYMBOL));
        if (this.slideboardManager != null) {
            this.slideboardManager.showNumericPanel();
        }
        if (isCustomPage) {
            this.keyboardBuilder.setCustomSymbolPage(customSymbolPage);
            c0965eM6735b = this.keyboardBuilder.getKeyboardInternal(8, usePkbLayout);
        } else {
            int i3 = symbolPage + 5;
            boolean z3 = this.sharedPreferences.getBoolean("enable_symbol_customization_pkb", false);
            boolean z4 = this.sharedPreferences.getBoolean("pkb_custom_page_first", false);
            if (z3 && z4) {
                i3--;
            }
            c0965eM6735b = this.keyboardBuilder.getKeyboardInternal(i3, usePkbLayout);
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

    /** @param keyboard the symbol keyboard whose currency key to replace */
    private void replaceCurrencyKey(Keyboard keyboard) {
        if (SettingsManager.getInstance().getSettingsValues().currencySymbol.length() > 0) {
            Key keyM6597a = keyboard.getKeyByOutputText("currencyExtendedKeyStyle");
            if (keyM6597a == null) {
                if (BuildConfig.DEBUG) Log.e(TAG, "Cannot find currency key on keyboard to replace");
                return;
            }
            char cCharAt = SettingsManager.getInstance().getSettingsValues().currencySymbol.charAt(0);
            if (cCharAt != keyM6597a.getCode()) {
                if (keyboard.replaceKey(keyM6597a, new Key(new MoreKeySpec(String.valueOf(cCharAt), 0, cCharAt, String.valueOf(cCharAt)), MoreKeySpec.getEmpty(), KeyHintPosition.HIDDEN, keyM6597a.getHintLabel(), 0, 1, keyM6597a.getX(), keyM6597a.getY(), keyboard.mHorizontalGap + keyM6597a.getWidth(), keyboard.mVerticalGap + keyM6597a.getHeight(), keyboard.mHorizontalGap, keyboard.mVerticalGap, "currencyExtendedKeyStyle", keyM6597a.getMoreKeys(), keyM6597a.getKeyLabelSet(), keyM6597a.getMoreKeysFlags(), 8, keyM6597a.getScanCode()))) {
                    keyboard.rebuildProximityGrid();
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

    /** The emoji board comes down: {@code BOARD_LOWERED(-11)}, an ordinary board switch. */
    @Override
    public void hideEmojiKeyboard() {
        transitions().apply(KeyboardTransition.boardLowered(CrossAxisRules.EMOJI_KEY_CODE));
    }

    /**
     * The layout half of the emoji close: restore the main keyboard view, take the palettes down
     * and leave EMOJI mode. Always returns to the alphabet, never to a PKB symbol page.
     */
    void leaveEmojiModeInternal() {
        // Always restore: showEmojiKeyboardInternal() set it GONE, and keyboard_frame needs it to
        // stay sized (else the IME window collapses and touches pass through the strip).
        this.mainKeyboardView.setVisibility(View.VISIBLE);
        EmojiPalettesView emojiPalettesView = this.emojiPalettesView;
        if (emojiPalettesView != null) {
            emojiPalettesView.setVisibility(View.GONE);
            emojiPalettesView.clearQuery();
            emojiPalettesView.detachPagerAdapter();
        }

        this.keyboardState.resetEmojiMode(); // So the next emoji tap opens the board, not toggles it off.
    }

    /** The emoji key from every path. {@code onEmojiInput()} toggles; the prologue runs only on open. */
    public void onEmojiKeyPressed() {
        beginEmojiBoardTransition();
        this.keyboardState.onEmojiInput();
    }

    /**
     * The emoji board's {@code OPEN_BOARD} prologue: clears a PKB symbol mode like every other
     * board. Must run before the mode moves to EMOJI ({@code clearPkbSymbolMode()} asks
     * {@code isInSymbolMode()}), hence the BOARD_OPENING / BOARD_RAISED pair.
     */
    private void beginEmojiBoardTransition() {
        if (this.keyboardState != null && !this.keyboardState.isInEmojiMode()) {
            transitions().apply(KeyboardTransition.boardOpening(CrossAxisRules.EMOJI_KEY_CODE));
        }
    }

    /**
     * {@code BOARD_RAISED(-11)}, the {@code OPEN_BOARD} epilogue: raise the UIM bar if down and
     * report the open. The prologue ran in {@link #beginEmojiBoardTransition()}.
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
        this.blackberryIme.hideFlickSuggestions();
        // Hide only the keyboard view: main_keyboard_frame stays so the UIM bar stays above emoji.
        this.mainKeyboardView.setVisibility(View.GONE);
        EmojiPalettesView emojiPalettesView = getEmojiPalettesView();
        if (emojiPalettesView == null) {
            if (BuildConfig.DEBUG) Log.e(TAG, "Emoji keyboard view is unavailable");
            return;
        }
        emojiPalettesView.updateDeleteButton(c0965eM6733a.mIconsSet, LocaleUtils.languageCode(c0965eM6733a.mId.mLocale));
        emojiPalettesView.attachPagerAdapter();
        emojiPalettesView.setVisibility(View.VISIBLE);
        emojiPalettesView.setSwitchingToEmoji(true);
        updateKeyboardLayout();

        // On-screen only: the board opens filtered on the composing word. On a physical keyboard
        // the user types into the open board instead (KeyEventProcessor -> appendToQuery).
        if (SettingsManager.getInstance().getSettingsValues().isEmojiDynamicSearchEnabled
                && DeviceProfile.isOnScreenKeyboardVisible()) {
            emojiPalettesView.onEmojiOpened(getComposingText());
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
        // The symbol board closes after one symbol unless Sym is held. Per-keystroke path: use the
        // tracker's cheap lookup, not getModifierState() (which builds a snapshot).
        return this.blackberryIme.getPhysicalKeyboardStateTracker().isSymKeyHeld();
    }

    @Override
    public int getSymbolPageOrder() {
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
        // The caller reloads the symbol keyboard next, which replaces the old page's meta mask.
        this.blackberryIme.getPhysicalKeyboardStateTracker()
                .resetModifiers(ModifierResetReason.KEYBOARD_RELOADED);
    }

    @Override
    public KeyRepeatHandler getKeyRepeatHandler() {
        return this.blackberryIme.getPhysicalKeyboardStateTracker().getKeyRepeatHandler();
    }

    @Override
    public void onReturnToAlphabetFromSymbol() {
        this.blackberryIme.restoreSuggestionStrip(true);
    }

    @Override
    public void updateShiftIndicator(boolean isShifted) {
        MainKeyboardView mainKeyboardViewM6790T = getMainKeyboardView();
        if (mainKeyboardViewM6790T != null) {
            mainKeyboardViewM6790T.setShiftKeyState(isShifted);
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
    public void setAlphabetKeyboard(int autoCapsFlags, int recapitalizeMode) {
        this.keyboardState.requestShiftMode(autoCapsFlags, recapitalizeMode);
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

    /**
     * Whether ABC on the symbols goes to the on-screen alphabet ({@link #requestManualShiftOff})
     * rather than the hidden physical-keyboard one. Not {@code hasAlphabeticKeyboard()}: that
     * means an external keyboard and is false for a built-in keypad.
     */
    @Override
    public boolean isManualTemporaryUppercase() {
        return DeviceProfile.current().isPkbDevice()
                && this.keyboardBuilder != null && this.keyboardBuilder.hasPkbLayout();
    }

    /**
     * @param code the key code
     * @param autoCapsFlags the auto-caps flags (getCapsMode)
     * @param recapitalizeMode the recapitalize mode
     */
    public void onInputCodeChanged(int code, int autoCapsFlags, int recapitalizeMode) {
        // -11 lands on the same emoji toggle as onEmojiKeyPressed(), so it needs the same prologue.
        if (code == CrossAxisRules.EMOJI_KEY_CODE) {
            beginEmojiBoardTransition();
        }
        this.keyboardState.onInputCodeChanged(code, autoCapsFlags, recapitalizeMode);
    }

    /**
     * @param autoCapsFlags the auto-caps flags (getCapsMode)
     * @param recapitalizeMode the recapitalize mode
     */
    public void onSoftwareSymbolCommitted(int code, int autoCapsFlags, int recapitalizeMode) {
        this.keyboardState.onSoftwareSymbolCommitted(code, autoCapsFlags, recapitalizeMode);
    }

    public boolean isEmojiKeyboardShowing() {
        return this.keyboardState != null && this.keyboardState.isInEmojiMode();
    }

    public boolean isFccViewShowing() {
        FccView fccView = this.fccView;
        return fccView != null && fccView.isShown();
    }

    /** Inflates from its ViewStub on first access. */
    public FccView getFccView() {
        if (this.fccView == null && this.fccViewStub != null) {
            this.fccView = (FccView) this.fccViewStub.inflate();
        }
        return this.fccView;
    }

    /** Inflates from its ViewStub on first access. */
    public VoiceInputView getVoiceInputView() {
        if (this.voiceInputView == null && this.voiceInputViewStub != null) {
            this.voiceInputView = (VoiceInputView) this.voiceInputViewStub.inflate();
        }
        return this.voiceInputView;
    }

    /** Inflates from its ViewStub on first access. */
    public NumberPadView getNumberPadView() {
        if (this.numberPadView == null && this.numberPadViewStub != null) {
            this.numberPadView = (NumberPadView) this.numberPadViewStub.inflate();
            this.numberPadView.setVisibility(View.GONE);
        }
        return this.numberPadView;
    }

    /** Non-inflating variant for state queries and hide paths. */
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
        // Release every view field, or the destroyed input_view tree stays reachable.
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
        // The themed context wraps the dying service; drop it so the service can be collected.
        // Readers re-create it via ensureThemeContext.
        this.themeContext = null;
        this.themeContextIme = null;
        this.sharedPreferences = null;
    }

    @SuppressLint({"InflateParams"})
    /** @param isHardwareAcceleratedDrawingEnabled true to enable hardware-accelerated drawing */
    public View createInputView(boolean isHardwareAcceleratedDrawingEnabled) {
        // Clean up the previous sub-components so they do not leak. Not destroy(): it nulls
        // blackberryIme and sharedPreferences, which are needed below.
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
        this.isHardwareAccelerated = isHardwareAcceleratedDrawingEnabled;
        ensureThemeContext(this.blackberryIme);
        this.inputView = (InputView) LayoutInflater.from(this.themeContext).inflate(R.layout.input_view, (ViewGroup) null);
        this.mainKeyboardFrame = this.inputView.findViewById(R.id.main_keyboard_frame);
        this.emojiPalettesViewStub = this.inputView.findViewById(R.id.emoji_palettes_view_stub);
        this.emojiPalettesView = null;
        this.fccViewStub = this.inputView.findViewById(R.id.fcc_view_stub);
        this.fccView = null;
        this.voiceInputViewStub = this.inputView.findViewById(R.id.voice_input_view_stub);
        this.voiceInputView = null;
        this.numberPadViewStub = this.inputView.findViewById(R.id.number_pad_view_stub);
        this.numberPadView = null;
        this.mainKeyboardView = (MainKeyboardView) this.inputView.findViewById(R.id.keyboard_view);
        this.mainKeyboardView.setHardwareAcceleratedDrawingEnabled(isHardwareAcceleratedDrawingEnabled);
        this.mainKeyboardView.setKeyboardActionListener(this.blackberryIme);
        this.slideboardManager = new SlideboardManager(new ImeResourcesContext(this.blackberryIme), this.inputView, this.blackberryIme);
        this.unifiedInputBoardManager = new UnifiedInputBoardManager(new ImeResourcesContext(this.blackberryIme), this.inputView, this.blackberryIme);
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

    /**
     * Whether an on-screen Ctrl chord carries Shift: manual shift or a held Shift. Auto-caps and
     * caps lock do not count (Ctrl+C at a sentence start must stay Ctrl+C).
     */
    public boolean isShiftedForCtrlChord() {
        VkbShiftModeTracker tracker = this.keyboardState.getShiftModeTracker();
        boolean manual = tracker.isManualShifted() && !tracker.isShiftLocked();
        return manual || this.keyboardState.isShiftKeyPressed();
    }

    /** A Ctrl chord used the manual Shift: unshift, as a typed letter does. A held Shift stays. */
    public void releaseShiftAfterCtrlChord() {
        VkbShiftModeTracker tracker = this.keyboardState.getShiftModeTracker();
        if (tracker.isManualShifted() && !tracker.isShiftLocked() && this.keyboardState.isShiftKeyReleasing()) {
            requestShiftOff();
        }
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
     * Which board is up, across every keyboard. An open panel board wins (it is drawn over the main
     * view); otherwise the layout mode in {@link CrossAxisRules}' board vocabulary. Never
     * {@link KeyboardTransition#NO_BOARD}: with nothing else up it is the alphabet.
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
     * If the PKB is in symbol mode, reload the alphabet. Called when any UIM panel opens so the
     * main view has the alphabet loaded before the panel is dismissed.
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
     * Not an element id despite the name: the layout set's {@code supportedScript} id (latin = 14,
     * han = 7, thai = 20, ...), used for word-boundary classification. Never compare it with
     * {@link KeyboardId} element constants.
     */
    public int getKeyboardElementId() {
        KeyboardBuilder c0978h = this.keyboardBuilder;
        if (c0978h == null) {
            return -1;
        }
        return c0978h.getSupportedScriptId();
    }

    @Nullable
    public KeyboardBuilder getKeyboardBuilder() {
        return this.keyboardBuilder;
    }

    /**
     * @param autoCapsFlags the auto-caps flags (getCapsMode)
     * @param recapitalizeMode the recapitalize mode
     * @param fromSwipe true when triggered by a swipe gesture
     * @param fromPhysicalKeyboard true for the physical keyboard's symbol pages
     */
    public boolean onSymbolShiftToggle(int autoCapsFlags, int recapitalizeMode, boolean fromSwipe, boolean fromPhysicalKeyboard) {
        // The page turn may land on another symbol page or the alphabet: layout UNCHANGED.
        transitions().apply(KeyboardTransition.switchLayout(KeyboardTransition.Layout.UNCHANGED));
        Keyboard c0965eM6834l = getCurrentKeyboard();
        if (c0965eM6834l == null) {
            return false;
        }
        if (c0965eM6834l.getKeyByCode(-3) == null && (!c0965eM6834l.isTouchKeyboard() || c0965eM6834l.isNumberOrDatetimeVariation())) {
            return false;
        }
        this.keyboardState.onSymbolShiftToggle(autoCapsFlags, recapitalizeMode, fromSwipe, fromPhysicalKeyboard);
        return true;
    }

    /**
     * @param autoCapsFlags the auto-caps flags (getCapsMode)
     * @param recapitalizeMode the recapitalize mode
     */
    public void onSymbolKeyLongPress(int autoCapsFlags, int recapitalizeMode) {
        this.keyboardState.onSymbolKeyLongPress(autoCapsFlags, recapitalizeMode);
    }

    /**
     * @param scanCode the physical key's scan code
     * @param pressed the pressed state to show on the matching key
     */
    public Key getKeyByPhysicalScanCode(int scanCode, boolean pressed) {
        Keyboard c0965eM6834l;
        if (!this.keyboardState.wasSymbolEnteredFromAlphabet() || (c0965eM6834l = getCurrentKeyboard()) == null) {
            return null;
        }
        Key keyM6611d = c0965eM6834l.getKeyByScanCode(scanCode);
        if (keyM6611d != null) {
            this.mainKeyboardView.setKeyPressed(keyM6611d, pressed);
        }
        return keyM6611d;
    }

    /**
     * What holding the physical key that typed {@code baseCharacter} offers, the same on every keyboard
     * ({@link PhysicalKeyResolver#moreKeys}).
     *
     * @param baseCharacter the character the key typed
     */
    @Override // dev.bbkb.ime.core.KeyboardLayoutCallback
    public String[] getMoreKeysForKey(String baseCharacter) {
        return PhysicalKeyResolver.moreKeys(baseCharacter);
    }

    /**
     * The double-tap sequence for {@code baseCharacter} ({@link PhysicalKeyResolver#multitap}).
     *
     * @param baseCharacter the character the key typed
     */
    @Override // dev.bbkb.ime.core.KeyboardLayoutCallback
    public String[] getMoreKeysForKeyByStyle(String baseCharacter) {
        return PhysicalKeyResolver.multitap(baseCharacter);
    }

    /**
     * True while a symbol page is current, so physical letter keys mean symbols. Not
     * {@code wasSymbolEnteredFromAlphabet()}, which stays set on the alphabet afterwards. Safe
     * before initialisation.
     */
    public boolean isPhysicalSymbolMappingActive() {
        return this.keyboardState != null && this.keyboardState.isInSymbolMode();
    }

    /** @param code the key code */
    public String[] getMoreKeysForCode(int code) {
        Keyboard c0965eM6834l = getCurrentKeyboard();
        if (c0965eM6834l != null) {
            return c0965eM6834l.getKeyLabelSetForCode(code);
        }
        return null;
    }
    
    /**
     * The more-keys string of the physical key, with its label appended; null when not in a
     * symbol mode entered from the alphabet or no key matches.
     */
    @Nullable
    public String getMoreKeysForScanCode(int scanCode) {
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
    public KeyCharacterResult.Interpretation interpretKeyCharacter(KeyEvent keyEvent, int metaState) {
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
     * Whether a UIM panel board is drawn over the main view. Gates cursor mode and the strip, which
     * care about the panel, not the keyboard behind it (so not {@link #activeBoard()}).
     */
    public boolean isUnifiedInputBoardShowing() {
        UnifiedInputBoardManager c1011i = this.unifiedInputBoardManager;
        return c1011i != null && c1011i.isPanelBoardShowing();
    }

    /** @param visibility 0 to unshift; otherwise the main keyboard view's new visibility */
    public void onUnifiedInputBoardAction(int visibility) {
        if (visibility == 0) {
            requestShiftOff();
        } else {
            this.unifiedInputBoardManager.onMainKeyboardViewVisibilityChanged(visibility);
        }
    }

    public void requestUpdateSuggestions() {
        BlackBerryIME blackBerryIME = this.blackberryIme;
        if (blackBerryIME != null) {
            blackBerryIME.refreshSubtypeSwitcher();
        }
    }
}

package dev.bbkb.ime.keyboard.internal;

import android.util.Log;

import dev.bbkb.ime.core.Constants;
import dev.bbkb.ime.core.device.profile.DeviceProfile;
import dev.bbkb.ime.core.locale.LocaleUtils;
import dev.bbkb.ime.core.shared.Logger;

import java.util.Locale;
import dev.bbkb.ime.BuildConfig;



public final class KeyboardState {

    private static final String TAG = "KeyboardState";

    public enum KeyboardModeState {
        ALPHABET, SYMBOL, EMOJI, MENU
    }

    private boolean isShiftLockReleased;

    private boolean isCapitalizationEnabled;


    private final SwitcherCallbacks switcherCallbacks;

    private KeyboardModeState currentMode = KeyboardModeState.ALPHABET;

    private int currentSymbolPageIndex;

    private boolean wasShiftLockedBeforeSymbol;

    private int savedSymbolPageIndex;

    private int maxSymbolPages;

    private int maxPkbSymbolPages;

    private KeyRepeatHandler keyRepeatHandler;

    private String packageName;

    private ShiftKeyState shiftKeyState = new ShiftKeyState("Shift");

    private ModifierKeyState symbolKeyState = new ModifierKeyState("Symbol");

    private ModifierKeyState symbolPagingKeyState = new ModifierKeyState("SymbolPaging");

    private int switchState = 0;

    private int symbolEntryMethod = 1;

    private int vkbSymbolShiftState = 0;

    private int pkbSymbolShiftState = 0;

    private int savedVkbSymbolShiftState = this.vkbSymbolShiftState;

    private int savedPkbSymbolShiftState = this.pkbSymbolShiftState;

    private boolean isVkbCustomSymbolPage = false;

    private boolean isPkbCustomSymbolPage = false;

    /** Shift-mode state machine (UNSHIFTED / MANUAL_SHIFTED / SHIFT_LOCKED / ...). */
    private VkbShiftModeTracker shiftModeTracker = new VkbShiftModeTracker();

    private final SavedKeyboardState savedKeyboardState = new SavedKeyboardState();

    /**
     * "No recapitalize" value of the {@code recapitalizeMode} argument passed beside
     * {@code autoCapsFlags} through the entry points (the {@code i, i2} pairs below). It is -1, not
     * 0: 0 is a real mode, and passing it latches {@link #requestedShiftMode} so
     * {@link #onShiftKeyPress()} never acts again. Production always passes this value.
     */
    public static final int RECAPITALIZE_NONE = -1;

    private int requestedShiftMode = RECAPITALIZE_NONE;

    private int emojiEntryMethod = 0;

    
    public interface SwitcherCallbacks {
        void showEmojiKeyboard();

        void hideEmojiKeyboard();

        boolean isVkbSymbolCustomizationEnabled();

        boolean isVkbCustomPageFirst();

        boolean isPkbSymbolCustomizationEnabled();

        boolean isPkbCustomPageFirst();

        /**
         * Whether the hardware Sym key is held now. A PKB symbol board closes after one symbol
         * unless Sym is held. Per-keystroke path: implementations must answer cheaply.
         */
        boolean isSymHeld();

        int getSymbolPageOrder();

        void togglePkbSymbolShift();

        KeyRepeatHandler getKeyRepeatHandler();

        void onReturnToAlphabetFromSymbol();

        void onCharacterKey();

        void onStartBatchInput();

        void onKeyRelease();

        boolean shouldCapitalizeAfterSpace();

        boolean isManualTemporaryUppercase();

        /**
         * @param i the symbol page index
         * @param z whether the PKB variant of the page may be used
         * @param z2 whether the page is the custom symbol page
         * @param i2 the custom page's shift action (0 none, 1 shift, 2 unshift)
         */
        void setVkbSymbolsKeyboard(int i, boolean z, boolean z2, int i2);

        /** @param z whether shift is on or locked */
        void updateShiftIndicator(boolean z);

        void updateShiftLockedIndicator(boolean locked);

        /** Same parameters as {@link #setVkbSymbolsKeyboard}. */
        void setPkbSymbolsKeyboard(int i, boolean z, boolean z2, int i2);

        /**
         * @param i the auto-caps flags
         * @param i2 the recapitalize mode, {@link #RECAPITALIZE_NONE} for none
         */
        void setAlphabetKeyboard(int i, int i2);

        boolean isPkbDevice();

        void showMenu();

        void requestShiftOff();

        void requestManualShiftOff();

        void requestShiftOnce();

        /** Select the alphabet keyboard's AUTOMATIC-shifted element (setAlphabetKeyboard(2)). */
        void requestAutomaticShift();

        /** Select the alphabet keyboard's SHIFT-LOCKED element (setAlphabetKeyboard(3)). */
        void requestShiftLocked();

        void requestShiftMomentary();

        void onFinishShiftLongPress();

        void onStartShiftLongPress();
    }

    /** @param i the saved shift mode (0 unshifted, 1 manual, 2 automatic) */
    static String getShiftModeString(int i) {
        switch (i) {
            case 0:
                return "UNSHIFT";
            case 1:
                return "MANUAL";
            case 2:
                return "AUTOMATIC";
            default:
                return null;
        }
    }

    /** @param i the key code */
    private static boolean isWhitespaceOrEnter(int i) {
        return i == 32 || i == 10;
    }

    /** @param i the switch state */
    private static String getSwitchStateString(int i) {
        switch (i) {
            case 0:
                return "ALPHA";
            case 1:
                return "SYMBOL-BEGIN";
            case 2:
                return "SYMBOL";
            case 3:
                return "SYMBOL-AFTER-SPACE";
            case 4:
                return "MOMENTARY-ALPHA-SYMBOL";
            case 5:
                return "MOMENTARY-SYMBOL-MORE";
            case 6:
                return "MOMENTARY-ALPHA_SHIFT";
            default:
                return null;
        }
    }

    
    static final class SavedKeyboardState {

        public boolean isValid;

        public boolean wasAlphabetMode;

        public boolean wasShiftLocked;

        public boolean wasEmojiMode;

        public boolean wasMenuMode;

        public boolean wasSymbolMode;

        public boolean wasPkbDevice;

        public int savedShiftMode;

        public int savedSymbolPage;

        public int savedImeOptions;

        SavedKeyboardState() {
            // Starts invalid; filled by saveKeyboardState.
        }

        public String toString() {
            if (!this.isValid) {
                return "INVALID";
            }
            if (this.wasAlphabetMode) {
                if (this.wasShiftLocked) {
                    return "ALPHABET_SHIFT_LOCKED";
                }
                return "ALPHABET_" + KeyboardState.getShiftModeString(this.savedShiftMode);
            }
            if (this.wasEmojiMode) {
                return "EMOJI";
            }
            if (this.wasMenuMode) {
                return "MENU";
            }
            return "SYMBOLS_" + this.savedSymbolPage;
        }
    }

    /** @param i the editor's input type, compared with the one saved */
    public boolean hasImeOptionsChanged(int i) {
        return i != this.savedKeyboardState.savedImeOptions;
    }

    /** @param bVar the keyboard switcher */
    public KeyboardState(SwitcherCallbacks bVar) {
        this.switcherCallbacks = bVar;
        this.keyRepeatHandler = this.switcherCallbacks.getKeyRepeatHandler();
        if (this.keyRepeatHandler != null) {
            this.keyRepeatHandler.setModifierListener(new KeyRepeatHandler.ModifierListener() {
                @Override // dev.bbkb.ime.keyboard.internal.KeyRepeatHandler.ModifierListener
                public void onModifierKeyRepeatEnd() {
                    if (KeyboardState.this.currentMode == KeyboardModeState.SYMBOL) {
                        KeyboardState.this.setPkbSymbolShiftState(2);
                        if (KeyboardState.this.savedPkbSymbolShiftState == 0 && KeyboardState.this.isPkbCustomSymbolPage) {
                            KeyboardState.this.setPkbSymbolsKeyboard(0, true, true);
                        }
                    }
                }

                @Override // dev.bbkb.ime.keyboard.internal.KeyRepeatHandler.ModifierListener
                public void onModifierKeyRepeatStart() {
                    if (KeyboardState.this.currentMode == KeyboardModeState.SYMBOL) {
                        if (KeyboardState.this.pkbSymbolShiftState == 0) {
                            KeyboardState.this.setPkbSymbolShiftState(1);
                        } else {
                            KeyboardState.this.setPkbSymbolShiftState(0);
                        }
                        if (KeyboardState.this.isPkbCustomSymbolPage) {
                            KeyboardState.this.setPkbSymbolsKeyboard(0, true, true);
                        }
                    }
                }
            });
        }
    }

    /**
     * @param i the auto-caps flags
     * @param i2 the recapitalize mode
     * @param i3 the editor's input type
     * @param str the editor's package name
     */
    public void onStartInput(int i, int i2, Locale locale, int i3, String str) {
        this.shiftModeTracker.setShiftLocked(false);
        this.wasShiftLockedBeforeSymbol = false;
        this.savedSymbolPageIndex = 0;
        this.shiftKeyState.onRelease();
        this.symbolKeyState.onRelease();
        this.symbolPagingKeyState.onRelease();
        this.symbolEntryMethod = 1;
        if (LocaleUtils.isChinese(locale)) {
            this.maxSymbolPages = 3;
        } else {
            this.maxSymbolPages = 2;
        }
        this.maxPkbSymbolPages = isPkbSymbolCustomizationEnabled() ? this.maxSymbolPages + 1 : this.maxSymbolPages;
        this.maxSymbolPages = isVkbSymbolCustomizationEnabled() ? this.maxSymbolPages + 1 : this.maxSymbolPages;
        restoreKeyboardState(i, i2, i3);
        this.packageName = str;
    }

    /** @param i the editor's input type */
    public void saveKeyboardState(int i) {
        int i2;
        SavedKeyboardState aVar = this.savedKeyboardState;
        aVar.wasAlphabetMode = (this.currentMode == KeyboardModeState.ALPHABET);
        aVar.wasEmojiMode = (this.currentMode == KeyboardModeState.EMOJI);
        aVar.wasMenuMode = (this.currentMode == KeyboardModeState.MENU);
        aVar.wasSymbolMode = (this.currentMode == KeyboardModeState.SYMBOL);
        aVar.wasPkbDevice = this.switcherCallbacks.isPkbDevice();
        if (this.currentMode == KeyboardModeState.ALPHABET) {
            aVar.wasShiftLocked = this.shiftModeTracker.isShiftLocked();
            if (this.shiftModeTracker.isAutomaticShifted()) {
                i2 = 2;
            } else {
                i2 = this.shiftModeTracker.isShiftedOrShiftLocked() ? 1 : 0;
            }
            aVar.savedShiftMode = i2;
        } else {
            aVar.wasShiftLocked = this.wasShiftLockedBeforeSymbol;
            aVar.savedSymbolPage = this.currentSymbolPageIndex;
        }
        aVar.savedImeOptions = i;
        aVar.isValid = true;
    }

    /**
     * @param i the auto-caps flags, {@code i2} the recapitalize mode
     * @param i3 the editor's input type
     */
    private void restoreKeyboardState(int i, int i2, int i3) {
        SavedKeyboardState aVar = this.savedKeyboardState;
        if (hasImeOptionsChanged(i3)) {
            aVar.isValid = false;
        }
        if (!aVar.isValid || aVar.wasAlphabetMode) {
            switchToAlphabetUnshifted(i, i2);
        } else if (aVar.wasEmojiMode) {
            this.switcherCallbacks.showEmojiKeyboard();
        } else if (aVar.wasMenuMode) {
            switchToMenuMode();
        } else if (aVar.wasSymbolMode) {
            if (aVar.wasPkbDevice) {
                setPkbSymbolsKeyboard(aVar.savedSymbolPage, true, isVkbCustomSymbolPage(aVar.savedSymbolPage));
            } else {
                setVkbSymbolsKeyboard(aVar.savedSymbolPage, true, isVkbCustomSymbolPage(aVar.savedSymbolPage));
            }
        } else {
            if (BuildConfig.DEBUG) Log.e(TAG, "Invalid keyboard state onRestoreKeyboardState: saved=" + aVar + " " + this);
            switchToAlphabetUnshifted(i, i2);
        }
        if (aVar.isValid) {
            aVar.isValid = false;
            if (aVar.wasAlphabetMode) {
                setShiftLocked(aVar.wasShiftLocked);
                if (aVar.wasShiftLocked) {
                    return;
                }
                setShiftMode(aVar.savedShiftMode);
                return;
            }
            this.wasShiftLockedBeforeSymbol = aVar.wasShiftLocked;
        }
    }

    /** @param i the shift mode: 0 off, 1 manual, 2 automatic, 4 momentary */
    private void setShiftMode(int i) {
        int i2;
        if (this.shiftModeTracker.isAutomaticShifted()) {
            i2 = 2;
        } else if (this.shiftModeTracker.isManualShifted()) {
            i2 = 1;
        } else {
            i2 = this.shiftModeTracker.isShiftLocked() ? 3 : 0;
        }
        if (i != 4) {
            switch (i) {
                case 0:
                    this.shiftModeTracker.setShifted(false);
                    if (i != i2 && this.currentMode != KeyboardModeState.SYMBOL) {
                        this.switcherCallbacks.requestShiftOff();
                        break;
                    }
                    break;
                case 1:
                    this.shiftModeTracker.setShifted(true);
                    if (i != i2 && this.currentMode != KeyboardModeState.SYMBOL) {
                        this.switcherCallbacks.requestShiftOnce();
                        break;
                    }
                    break;
                case 2:
                    this.shiftModeTracker.setAutomaticShifted();
                    if (i != i2 && this.currentMode != KeyboardModeState.SYMBOL) {
                        this.switcherCallbacks.requestAutomaticShift();
                        break;
                    }
                    break;
            }
        } else {
            this.shiftModeTracker.setShifted(true);
            if (this.currentMode != KeyboardModeState.SYMBOL) {
                this.switcherCallbacks.requestShiftMomentary();
            }
        }
        this.switcherCallbacks.updateShiftIndicator(this.shiftModeTracker.isShiftedOrShiftLocked());
        this.switcherCallbacks.updateShiftLockedIndicator(this.shiftModeTracker.isShiftLocked());
    }

    /** @param z true to lock shift */
    private void setShiftLocked(boolean z) {
        if (this.currentMode == KeyboardModeState.ALPHABET) {
            if (z && ((!this.shiftModeTracker.isShiftLocked() || this.shiftModeTracker.isShiftLockShifted()) && this.currentMode != KeyboardModeState.SYMBOL)) {
                this.switcherCallbacks.requestShiftLocked();
            }
            if (!z && this.shiftModeTracker.isShiftLocked() && this.currentMode != KeyboardModeState.SYMBOL) {
                this.switcherCallbacks.requestShiftOff();
            }
            this.shiftModeTracker.setShiftLocked(z);
            this.switcherCallbacks.updateShiftIndicator(this.shiftModeTracker.isShiftedOrShiftLocked());
            this.switcherCallbacks.updateShiftLockedIndicator(this.shiftModeTracker.isShiftLocked());
        }
    }

    /** @param i the auto-caps flags, {@code i2} the recapitalize mode (likewise below) */
    private void switchToAlphabetFromSymbol(int i, int i2) {
        this.symbolEntryMethod = 1;
        if (this.currentMode == KeyboardModeState.ALPHABET) {
            this.wasShiftLockedBeforeSymbol = this.shiftModeTracker.isShiftLocked();
            setVkbSymbolShiftState(getVkbSymbolShiftStateFromShiftMode());
            int i3 = this.savedSymbolPageIndex;
            setVkbSymbolsKeyboard(i3, true, isVkbCustomSymbolPage(i3));
            return;
        }
        this.savedSymbolPageIndex = this.currentSymbolPageIndex;
        switchToAlphabetUnshifted(i, i2);
        if (this.wasShiftLockedBeforeSymbol) {
            setShiftLocked(true);
        }
        this.wasShiftLockedBeforeSymbol = false;
    }

    private void switchToSymbolFromAlphabet(int i, int i2) {
        this.symbolEntryMethod = 0;
        if (this.currentMode == KeyboardModeState.SYMBOL) {
            this.savedSymbolPageIndex = this.currentSymbolPageIndex;
            if (this.switcherCallbacks.isManualTemporaryUppercase()) {
                switchToAlphabetManualShifted(i, i2);
            } else {
                switchToAlphabetUnshifted(i, i2);
            }
            if (this.wasShiftLockedBeforeSymbol) {
                setShiftLocked(true);
            }
            this.wasShiftLockedBeforeSymbol = false;
            return;
        }
        this.wasShiftLockedBeforeSymbol = this.shiftModeTracker.isShiftLocked();
        setVkbSymbolShiftState(getVkbSymbolShiftStateFromShiftMode());
        int i3 = this.savedSymbolPageIndex;
        setVkbSymbolsKeyboard(i3, false, isVkbCustomSymbolPage(i3));
    }

    /**
     * Per-app workaround with no recorded rationale: backspacing out of symbol mode here returns
     * to a manually shifted alphabet. Keep any further per-app quirks next to it.
     */
    private static final String PKG_BANAMEX = "com.citibanamex.banamexmobile";

    private void onBackspace(int i, int i2) {
        if (this.currentMode == KeyboardModeState.ALPHABET) {
            return;
        }
        this.savedSymbolPageIndex = this.currentSymbolPageIndex;
        if (PKG_BANAMEX.equals(this.packageName) && this.switcherCallbacks.isManualTemporaryUppercase()) {
            switchToAlphabetManualShifted(i, i2);
        } else {
            switchToAlphabetUnshifted(i, i2);
        }
        if (this.wasShiftLockedBeforeSymbol) {
            setShiftLocked(true);
        }
        this.wasShiftLockedBeforeSymbol = false;
    }

    private void cycleThroughSymbolPages() {
        int i = this.currentSymbolPageIndex;
        int i2 = this.maxSymbolPages;
        setVkbSymbolsKeyboard((i + 1) % i2, true, isVkbCustomSymbolPage((i + 1) % i2));
    }

    /**
     * Loads the alphabet keyboard, then drops the recapitalize latch. The clear must come after:
     * the callback re-enters {@link #requestShiftMode(int, int)}, which sets the latch, so a bad
     * argument misbehaves once instead of disabling shift for the session.
     */
    private void loadAlphabetKeyboard(int autoCapsFlags, int recapitalizeMode) {
        this.switcherCallbacks.setAlphabetKeyboard(autoCapsFlags, recapitalizeMode);
        this.requestedShiftMode = RECAPITALIZE_NONE;
    }

    private void switchToAlphabetUnshifted(int i, int i2) {
        this.switcherCallbacks.requestShiftOff();
        this.currentMode = KeyboardModeState.ALPHABET;
        this.currentSymbolPageIndex = 0;
        this.requestedShiftMode = RECAPITALIZE_NONE;
        this.switchState = 0;
        this.shiftModeTracker.setShifted(false);
        loadAlphabetKeyboard(i, i2);
    }

    private void switchToAlphabetManualShifted(int i, int i2) {
        this.switcherCallbacks.requestManualShiftOff();
        this.currentMode = KeyboardModeState.ALPHABET;
        this.currentSymbolPageIndex = 0;
        this.requestedShiftMode = RECAPITALIZE_NONE;
        this.switchState = 0;
        this.shiftModeTracker.setShifted(false);
        loadAlphabetKeyboard(i, i2);
    }

    private void switchToMenuMode() {
        this.switcherCallbacks.showMenu();
        this.currentMode = KeyboardModeState.MENU;
        this.requestedShiftMode = RECAPITALIZE_NONE;
        this.wasShiftLockedBeforeSymbol = this.shiftModeTracker.isShiftLocked();
        this.shiftModeTracker.setShiftLocked(false);
    }

    /**
     * @param i the symbol page index
     * @param z whether the PKB variant of the page may be used
     * @param z2 whether the page is the custom symbol page (also for setVkbSymbolsKeyboard)
     */
        void setPkbSymbolsKeyboard(int i, boolean z, boolean z2) {
        Logger.debug(TAG, "setPkbSymbolsKeyboard page=" + i + (z2 ? "-custom" : ""));
        int iM7219l = 0;
        if (z2) {
            iM7219l = getPkbSymbolShiftAction();
            this.isPkbCustomSymbolPage = true;
        } else {
            this.isPkbCustomSymbolPage = false;
        }
        this.switcherCallbacks.setPkbSymbolsKeyboard(i, z, z2, iM7219l);
        if (z2) {
            this.savedPkbSymbolShiftState = this.pkbSymbolShiftState;
        }
        setSymbolModeCommon(i);
    }

    private void setVkbSymbolsKeyboard(int i, boolean z, boolean z2) {
        Logger.debug(TAG, "setVkbSymbolsKeyboard page=" + i + (z2 ? "-custom" : ""));
        int iM7216k = 0;
        if (z2) {
            this.isVkbCustomSymbolPage = true;
            iM7216k = getVkbSymbolShiftAction();
        } else {
            this.isVkbCustomSymbolPage = false;
        }
        this.switcherCallbacks.setVkbSymbolsKeyboard(i, z, z2, iM7216k);
        if (z2) {
            this.savedVkbSymbolShiftState = this.vkbSymbolShiftState;
        }
        setSymbolModeCommon(i);
    }

    private int getVkbSymbolShiftAction() {
        return this.vkbSymbolShiftState != 0 ? this.savedVkbSymbolShiftState == 0 ? 1 : 0 : this.savedVkbSymbolShiftState != 0 ? 2 : 0;
    }

    private int getPkbSymbolShiftAction() {
        return this.pkbSymbolShiftState != 0 ? this.savedPkbSymbolShiftState == 0 ? 1 : 0 : this.savedPkbSymbolShiftState != 0 ? 2 : 0;
    }

    /** @param i the symbol page index */
    private void setSymbolModeCommon(int i) {
        this.currentMode = KeyboardModeState.SYMBOL;
        this.currentSymbolPageIndex = i;
        this.shiftModeTracker.setShiftLocked(false);
        this.switchState = 1;
    }

    public void switchToEmojiMode(int entryMethod) {
        this.currentMode = KeyboardModeState.EMOJI;
        this.emojiEntryMethod = entryMethod;
        this.requestedShiftMode = RECAPITALIZE_NONE;
        this.wasShiftLockedBeforeSymbol = this.shiftModeTracker.isShiftLocked();
        this.shiftModeTracker.setShiftLocked(false);
    }

    /**
     * @param i the key code
     * @param z true if this is the only active pointer
     * @param i2 the auto-caps flags, {@code i3} the recapitalize mode
     */
    public void onCodeInput(int i, boolean z, int i2, int i3) {
        int i4;
        if (i != -1) {
            this.switcherCallbacks.onKeyRelease();
        }
        switch (i) {
            case -16:
            case -2:
                break;
            case -15:
                onSymbolPagingKeyPress();
                break;
            case -9:
            case -8:
                cycleThroughSymbolPages();
                break;
            case -3:
                onSymbolKeyPress(i2, i3);
                break;
            case -1:
                onShiftKeyPress();
                break;
            case -22:
                // The hardware Sym toggle (a page turn) also arrives as this code. The default
                // branch would zero symbolEntryMethod and break the hinted physical keys; the
                // toggle itself does the work.
                break;
            default:
                if (this.switchState == 1 && !isWhitespaceOrEnter(i)) {
                    this.switchState = 4;
                }
                this.shiftKeyState.onOtherKeyPressed();
                this.symbolKeyState.onOtherKeyPressed();
                this.symbolPagingKeyState.onOtherKeyPressed();
                boolean z2 = false;
                if (this.currentMode == KeyboardModeState.SYMBOL && ((i4 = this.symbolEntryMethod) == 1 || i4 == 2)) {
                    boolean pkbSymEntry = (i4 == 2) && this.switcherCallbacks.isPkbDevice();
                    // This runs on touch-down. For PKB on-screen Sym entry (method 2) leaving symbol
                    // mode now would lose the tap, which commits on touch-up, so a character key
                    // returns to the alphabet in onSoftwareSymbolCommitted() instead.
                    if (pkbSymEntry && Constants.isLetterCode(i)) {
                        // Keep symbolEntryMethod 2 for the post-commit hook.
                    } else {
                        this.symbolEntryMethod = 0;
                        if (pkbSymEntry) {
                            switchToAlphabetFromSymbol(i2, i3);
                            this.switcherCallbacks.onReturnToAlphabetFromSymbol();
                            this.savedSymbolPageIndex = 0;
                        }
                    }
                }
                if (!z && this.currentMode == KeyboardModeState.ALPHABET && i2 != 4096) {
                    if (this.shiftModeTracker.isAutomaticShifted() || (this.shiftModeTracker.isManualShifted() && this.shiftKeyState.isReleasing())) {
                        z2 = true;
                    }
                    if (z2) {
                        this.switcherCallbacks.requestShiftOff();
                        break;
                    }
                }
                break;
        }
    }

    /**
     * Returns to the alphabet after a symbol tapped on the PKB on-screen Sym board has committed;
     * no-op unless that entry (method 2) is pending. Only a character ends it: the ABC key (-3)
     * toggles on release against the current mode, so ending the entry first would toggle wrong.
     *
     * @param code the soft key's code, as given to the IME's onCodeInput
     * @param i the auto-caps flags, {@code i2} the recapitalize mode
     */
    public void onSoftwareSymbolCommitted(int code, int i, int i2) {
        if (!Constants.isLetterCode(code)) {
            return;
        }
        if (this.currentMode == KeyboardModeState.SYMBOL && this.symbolEntryMethod == 2
                && this.switcherCallbacks.isPkbDevice() && !this.switcherCallbacks.isSymHeld()) {
            this.symbolEntryMethod = 0;
            switchToAlphabetFromSymbol(i, i2);
            this.switcherCallbacks.onReturnToAlphabetFromSymbol();
            this.savedSymbolPageIndex = 0;
        }
    }

    /**
     * @param i the Android key code
     * @param i2 the auto-caps flags, {@code i3} the recapitalize mode
     */
    public void onHardwareKeyEvent(int i, int i2, int i3) {
        switch (i) {
            case 59:
            case 60:
            case 62:
                break;
            case 61:
            case 64:
            case 65:
            case 66:
            default:
                // A PKB character leaves symbol mode unless Sym is held (its release, case 63,
                // ends the entry then).
                if (this.currentMode == KeyboardModeState.SYMBOL && this.symbolEntryMethod == 2
                        && this.switcherCallbacks.isPkbDevice() && !this.switcherCallbacks.isSymHeld()) {
                    this.symbolEntryMethod = 0;
                    switchToAlphabetFromSymbol(i2, i3);
                    this.switcherCallbacks.onReturnToAlphabetFromSymbol();
                    this.savedSymbolPageIndex = 0;
                }
                int i4 = this.switchState;
                if (i4 == 1) {
                    this.switchState = 2;
                    break;
                } else if (i4 != 4) {
                    this.switchState = 4;
                    break;
                }
                break;
            case 63:
                if (this.switchState == 4) {
                    onBackspace(i2, i3);
                    break;
                } else if (this.switcherCallbacks.isPkbDevice()) {
                    this.symbolEntryMethod = 2;
                    break;
                }
                break;
            case 67:
                if (this.switchState == 3) {
                    this.switchState = 4;
                    break;
                }
                break;
        }
    }

    /**
     * @param i the key code
     * @param z true if released during sliding input
     * @param i2 the auto-caps flags, {@code i3} the recapitalize mode
     */
    public void onCodeRelease(int i, boolean z, int i2, int i3) {
        if (i == -15) {
            onSymbolPagingKeyRelease();
            return;
        }
        if (i != 32) {
            switch (i) {
                case -3:
                    onSymbolKeyRelease(z, i2, i3);
                    break;
                case -2:
                    setShiftLocked(!this.shiftModeTracker.isShiftLocked() || this.shiftModeTracker.isShiftLockShifted());
                    break;
                case -1:
                    onShiftKeyRelease(z, i2, i3);
                    break;
            }
            return;
        }
        onSpaceRelease(z, i2, i3);
    }

    /**
     * @param z true if released during sliding input (likewise for the other release handlers)
     * @param i the auto-caps flags, {@code i2} the recapitalize mode
     */
    private void onSpaceRelease(boolean z, int i, int i2) {
        if (z) {
            return;
        }
        this.shiftKeyState.onOtherKeyPressed();
        this.symbolKeyState.onOtherKeyPressed();
        this.symbolPagingKeyState.onOtherKeyPressed();
        if ((this.currentMode == KeyboardModeState.SYMBOL || this.currentMode == KeyboardModeState.EMOJI) && !this.switcherCallbacks.isPkbDevice()) {
            this.switcherCallbacks.onStartShiftLongPress();
            switchToAlphabetUnshifted(i, i2);
            this.switcherCallbacks.onFinishShiftLongPress();
        }
    }

    private void onSymbolKeyPress(int i, int i2) {
        this.symbolKeyState.onPress();
        this.switchState = 2;
    }

    /**
     * Sym released. {@link #switchToSymbolFromAlphabet} is a toggle: call it at most once. Symbol
     * mode is entered on release, so a chorded Sym never left the alphabet and must stay there.
     */
    private void onSymbolKeyRelease(boolean z, int i, int i2) {
        if (this.symbolKeyState.isChording()) {
            // Stay on the alphabet; undo the marker onSymbolKeyPress() set.
            this.switchState = 0;
            this.symbolKeyState.onRelease();
            return;
        }
        if (!z) {
            // A plain tap, not a slide: symbol mode opens on page 0 next time.
            this.savedSymbolPageIndex = 0;
        }
        switchToSymbolFromAlphabet(i, i2);
        this.symbolKeyState.onRelease();
    }

    /** @param i the auto-caps flags, {@code i2} the recapitalize mode */
    public void requestShiftMode(int i, int i2) {
        this.requestedShiftMode = i2;
        updateShiftStateOnInput(i, i2);
    }

    /** @param i the auto-caps flags, {@code i2} the recapitalize mode */
    public void onBackspaceInSymbolMode(int i, int i2) {
        onBackspace(i, i2);
    }

    /** @param i the recapitalize mode */
    private void setShiftModeFromRequest(int i) {
        switch (i) {
            case 2:
                setShiftMode(2);
                break;
            case 3:
                setShiftMode(4);
                break;
            default:
                setShiftMode(0);
                break;
        }
    }

    private void updateShiftStateOnInput(int i, int i2) {
        if (this.currentMode == KeyboardModeState.ALPHABET) {
            if (RECAPITALIZE_NONE != i2) {
                setShiftModeFromRequest(i2);
                return;
            }
            if (!this.shiftKeyState.isReleasing() || this.shiftModeTracker.isShiftLocked() || this.shiftKeyState.isIgnoring()) {
                return;
            }
            // A chorded shift cannot reach here (the guard requires isReleasing()); handle it
            // before the guard if ever needed.
            setShiftMode(i == 0 ? 0 : 2);
        }
    }

    private void onShiftKeyPress() {
        if (RECAPITALIZE_NONE == this.requestedShiftMode && this.currentMode == KeyboardModeState.ALPHABET) {
            this.isCapitalizationEnabled = this.switcherCallbacks.shouldCapitalizeAfterSpace();
            if (!this.isCapitalizationEnabled) {
                this.switcherCallbacks.onStartBatchInput();
            }
            if (this.isCapitalizationEnabled) {
                if (this.shiftModeTracker.isManualShifted() || this.isShiftLockReleased) {
                    setShiftLocked(true);
                    return;
                }
                return;
            }
            if (this.shiftModeTracker.isShiftLocked()) {
                this.shiftKeyState.onPress();
                setShiftMode(4);
            } else if (this.shiftModeTracker.isAutomaticShifted()) {
                setShiftMode(1);
                this.shiftKeyState.onPress();
            } else if (this.shiftModeTracker.isShiftedOrShiftLocked()) {
                this.shiftKeyState.onPressOnShifted();
            } else {
                setShiftMode(1);
                this.shiftKeyState.onPress();
            }
        }
    }

    public boolean isShiftKeyPressed() {
        return this.shiftKeyState.isPressing();
    }

    public boolean isShiftKeyMomentary() {
        return this.shiftKeyState.isPressingOnShifted();
    }

    /** True while the shift key is in the RELEASING (idle) state. */
    public boolean isShiftKeyReleasing() {
        return this.shiftKeyState.isReleasing();
    }

    private void onSymbolPagingKeyPress() {
        this.symbolPagingKeyState.onPress();
    }

    private void onSymbolPagingKeyRelease() {
        if (this.switcherCallbacks.isPkbDevice()) {
            int i = this.currentSymbolPageIndex;
            int i2 = this.maxPkbSymbolPages;
            setPkbSymbolsKeyboard((i + 1) % i2, true, isPkbCustomSymbolPage((i + 1) % i2));
        } else {
            int i3 = this.currentSymbolPageIndex;
            int i4 = this.maxSymbolPages;
            setVkbSymbolsKeyboard((i3 + 1) % i4, true, isVkbCustomSymbolPage((i3 + 1) % i4));
        }
        this.symbolPagingKeyState.onRelease();
    }

    private void onShiftKeyRelease(boolean z, int i, int i2) {
        int i3 = this.requestedShiftMode;
        if (RECAPITALIZE_NONE != i3) {
            setShiftModeFromRequest(i3);
        } else if (this.currentMode == KeyboardModeState.ALPHABET) {
            boolean zM7317c = this.shiftModeTracker.isShiftLocked();
            this.isShiftLockReleased = false;
            if (this.isCapitalizationEnabled) {
                this.isCapitalizationEnabled = false;
            } else {
                if (this.shiftKeyState.isChording()) {
                    setShiftMode(0);
                    this.shiftKeyState.onRelease();
                    loadAlphabetKeyboard(i, i2);
                    return;
                }
                if (this.shiftModeTracker.isShiftLockShifted() && z) {
                    setShiftLocked(true);
                } else if (this.shiftModeTracker.isManualShifted() && z) {
                    this.switchState = 6;
                } else if (!zM7317c || this.shiftModeTracker.isShiftLockShifted() || ((!this.shiftKeyState.isPressing() && !this.shiftKeyState.isPressingOnShifted()) || z)) {
                    if (zM7317c && !this.shiftKeyState.isIgnoring() && !z) {
                        setShiftLocked(false);
                    } else if (this.shiftModeTracker.isShiftedOrShiftLocked() && this.shiftKeyState.isPressingOnShifted() && !z) {
                        setShiftMode(0);
                        this.isShiftLockReleased = true;
                    } else if (this.shiftModeTracker.isManualShiftedFromAutomaticShifted() && this.shiftKeyState.isPressing() && !z) {
                        setShiftMode(0);
                        this.isShiftLockReleased = true;
                    } else if ((this.shiftModeTracker.isManualShifted() || this.shiftModeTracker.isShiftLocked()) && this.shiftKeyState.isIgnoring() && !z) {
                        setShiftMode(0);
                        this.isShiftLockReleased = true;
                    }
                }
            }
        } else if (this.shiftKeyState.isChording()) {
            cycleThroughSymbolPages();
        }
        this.shiftKeyState.onRelease();
    }

    /** @param i the auto-caps flags, {@code i2} the recapitalize mode */
    public void onMomentaryStateFinish(int i, int i2) {
        switch (this.switchState) {
            case 4:
                switchToAlphabetFromSymbol(i, i2);
                break;
            case 5:
                cycleThroughSymbolPages();
                break;
            case 6:
                switchToAlphabetUnshifted(i, i2);
                break;
        }
    }

    /**
     * @param i the key code
     * @param i2 the auto-caps flags, {@code i3} the recapitalize mode
     */
    public void onInputCodeChanged(int i, int i2, int i3) {
        switch (this.switchState) {
            case 1:
                if (this.currentMode != KeyboardModeState.EMOJI) {
                    if (!isWhitespaceOrEnter(i) && (Constants.isLetterCode(i) || i == -4)) {
                        this.switchState = 2;
                        break;
                    } else if (i == -3) {
                        this.switchState = 4;
                        break;
                    }
                }
                break;
            case 2:
                if (i == 32) {
                    this.switchState = 3;
                    break;
                } else {
                    this.switchState = 0;
                    break;
                }
            case 3:
                if (Character.isAlphabetic(i) && this.currentMode != KeyboardModeState.ALPHABET) {
                    switchToAlphabetFromSymbol(i2, i3);
                    this.savedSymbolPageIndex = 0;
                    break;
                } else if (i != -1) {
                    this.switchState = 4;
                    break;
                }
                break;
            case 4:
                if (i != -3) {
                    if (i == 32) {
                        this.switchState = 3;
                        break;
                    }
                } else if (this.currentMode == KeyboardModeState.ALPHABET) {
                    this.switchState = 0;
                    break;
                } else {
                    this.switchState = 1;
                    break;
                }
                break;
            case 5:
                if (i == -1) {
                    this.switchState = 1;
                    break;
                }
                break;
        }
        if (Constants.isLetterCode(i)) {
            if (this.currentMode == KeyboardModeState.SYMBOL) {
                if (this.isVkbCustomSymbolPage) {
                    onCharacterInCustomPage();
                } else if (this.isPkbCustomSymbolPage && this.pkbSymbolShiftState == 1) {
                    setPkbSymbolShiftState(0);
                    this.switcherCallbacks.togglePkbSymbolShift();
                    setPkbSymbolsKeyboard(0, true, true);
                }
            } else {
                updateShiftStateOnInput(i2, i3);
            }
            this.switcherCallbacks.onCharacterKey();
            return;
        }
        if (i == -43) {
            switchToAlphabetUnshifted(i2, i3);
            this.switcherCallbacks.onReturnToAlphabetFromSymbol();
            return;
        }
        if (i != -37) {
            if (i == -14) {
                switchToAlphabetUnshifted(i2, i3);
                return;
            }
            if (i == -11) {
                onEmojiInput();
                return;
            }
            switch (i) {
                case -40:
                case -39:
                    break;
                default:
                    switch (i) {
                        case -23:
                            switchToMenuMode();
                            break;
                    }
                    return;
            }
        }
        switchToAlphabetUnshifted(i2, i3);
    }


    public boolean wasSymbolEnteredFromAlphabet() {
        return this.symbolEntryMethod != 0;
    }

    /**
     * @param i the auto-caps flags, {@code i2} the recapitalize mode
     * @param z true when triggered by a swipe gesture
     * @param z2 true for the physical keyboard's symbol pages
     */
    public void onSymbolShiftToggle(int i, int i2, boolean z, boolean z2) {
        if (this.currentMode != KeyboardModeState.SYMBOL) {
            if (z2) {
                // The hardware Sym key always enters from the alphabet. Set it explicitly:
                // resetSymbolMode() leaves 0, which would make hinted keys type letters.
                this.symbolEntryMethod = 1;
                setPkbSymbolShiftState(getSymbolShiftStateFromOrder());
                setPkbSymbolsKeyboard(0, true, isPkbSymbolCustomizationEnabled() && shouldShowPkbCustomPageFirst());
            } else {
                setVkbSymbolShiftState(getVkbSymbolShiftStateFromShiftMode());
                setVkbSymbolsKeyboard(0, false, isVkbSymbolCustomizationEnabled() && shouldShowVkbCustomPageFirst());
            }
            if (z) {
                this.symbolEntryMethod = z2 ? 2 : 3;
                return;
            }
            return;
        }
        if (this.switcherCallbacks.isPkbDevice()) {
            int i3 = this.currentSymbolPageIndex;
            if (i3 == this.maxPkbSymbolPages - 1) {
                DeviceProfile.setForceVkbMode(false);
                switchToAlphabetFromSymbol(i, i2);
                this.switcherCallbacks.onReturnToAlphabetFromSymbol();
                this.savedSymbolPageIndex = 0;
                return;
            }
            setPkbSymbolsKeyboard(i3 + 1, true, isPkbCustomSymbolPage(i3 + 1));
            return;
        }
        int i4 = this.currentSymbolPageIndex;
        if (i4 == this.maxSymbolPages - 1) {
            switchToAlphabetFromSymbol(i, i2);
            this.savedSymbolPageIndex = 0;
        } else {
            setVkbSymbolsKeyboard(i4 + 1, false, isVkbCustomSymbolPage(i4 + 1));
        }
    }

    /** @param i the auto-caps flags, {@code i2} the recapitalize mode */
    public void onSymbolKeyLongPress(int i, int i2) {
        int i3 = this.symbolEntryMethod;
        if ((i3 == 2 || i3 == 3) && this.currentMode == KeyboardModeState.SYMBOL) {
            switchToAlphabetFromSymbol(i, i2);
            this.savedSymbolPageIndex = 0;
        }
    }

    /** The alphabet shift-mode state machine (unshifted / manual / automatic / shift-locked). */
    public VkbShiftModeTracker getShiftModeTracker() {
        return this.shiftModeTracker;
    }

    private int getVkbSymbolShiftStateFromShiftMode() {
        if (this.shiftModeTracker.isManualShifted() || this.shiftModeTracker.isAutomaticShifted()) {
            return 1;
        }
        return this.shiftModeTracker.isShiftLocked() ? 2 : 0;
    }

    /** @param i 0 unshifted, 1 shifted, 2 locked */
    private void setVkbSymbolShiftState(int i) {
        this.vkbSymbolShiftState = i;
    }

    public boolean isInSymbolMode() {
        return this.currentMode == KeyboardModeState.SYMBOL;
    }

    /** Which typing board is up; {@code KeyboardSwitcher.activeBoard()} maps it to board keycodes. */
    public KeyboardModeState currentMode() {
        return this.currentMode;
    }

    /** @param i the symbol page index */
    public boolean isVkbCustomSymbolPage(int i) {
        if (isVkbSymbolCustomizationEnabled()) {
            return shouldShowVkbCustomPageFirst() ? i == 0 : i == this.maxSymbolPages - 1;
        }
        return false;
    }

    /** @param i the symbol page index */
    public boolean isPkbCustomSymbolPage(int i) {
        if (isPkbSymbolCustomizationEnabled()) {
            return shouldShowPkbCustomPageFirst() ? i == 0 : i == this.maxPkbSymbolPages - 1;
        }
        return false;
    }

    private void onCharacterInCustomPage() {
        if (isVkbSymbolShifted()) {
            setVkbSymbolShiftState(0);
            setVkbSymbolsKeyboard(0, false, true);
        }
    }

    public String toString() {
        String string;
        StringBuilder sb = new StringBuilder();
        sb.append("[keyboard=");
        if (this.currentMode == KeyboardModeState.ALPHABET) {
            string = this.shiftModeTracker.toString();
        } else {
            string = "symbolPage=" + this.currentSymbolPageIndex;
        }
        sb.append(string);
        sb.append(" shift=");
        sb.append(this.shiftKeyState);
        sb.append(" symbol=");
        sb.append(this.symbolKeyState);
        sb.append(" symbolPagingKey=");
        sb.append(this.symbolPagingKeyState);
        sb.append(" symbolPageMax=");
        sb.append(this.maxSymbolPages);
        sb.append(" pkbSymbolPageMax=");
        sb.append(this.maxPkbSymbolPages);
        sb.append(" switch=");
        sb.append(getSwitchStateString(this.switchState));
        sb.append("]");
        return sb.toString();
    }

    private boolean isVkbSymbolCustomizationEnabled() {
        return this.switcherCallbacks.isVkbSymbolCustomizationEnabled();
    }

    private boolean shouldShowVkbCustomPageFirst() {
        return this.switcherCallbacks.isVkbCustomPageFirst();
    }

    public void onEmojiInput() {
        if (this.currentMode == KeyboardModeState.EMOJI) {
            switchFromEmojiMode();
        } else {
            int entryMethod = this.currentMode == KeyboardModeState.SYMBOL ? 2 : 1;
            switchToEmojiMode(entryMethod);
            this.switcherCallbacks.showEmojiKeyboard();
        }
    }

    private void switchFromEmojiMode() {
        this.currentMode = KeyboardModeState.ALPHABET;
        // Argument 2 is a recapitalize mode: never pass 0 for none (see RECAPITALIZE_NONE).
        loadAlphabetKeyboard(0, RECAPITALIZE_NONE);

        this.emojiEntryMethod = 0;
        this.switcherCallbacks.hideEmojiKeyboard();
    }

    public boolean isInEmojiMode() {
        return this.currentMode == KeyboardModeState.EMOJI;
    }

    /** Leaves emoji mode without UI callbacks, when emoji was hidden from elsewhere. */
    public void resetEmojiMode() {
        // Only from EMOJI: this also runs whenever the emoji view is hidden (even after a normal
        // commit), and forcing ALPHABET would clobber an active SYMBOL or MENU mode.
        if (this.currentMode == KeyboardModeState.EMOJI) {
            this.currentMode = KeyboardModeState.ALPHABET;
        }
        this.emojiEntryMethod = 0;
    }

    /**
     * Leaves symbol mode without UI callbacks, when a UIM panel opens on a PKB device, so the
     * keyboard underneath is the alphabet when the panel closes.
     */
    public void resetSymbolMode() {
        this.currentMode = KeyboardModeState.ALPHABET;
        this.symbolEntryMethod = 0;
        this.savedSymbolPageIndex = 0;
    }

    private boolean isPkbSymbolCustomizationEnabled() {
        return this.switcherCallbacks.isPkbSymbolCustomizationEnabled();
    }

    private boolean shouldShowPkbCustomPageFirst() {
        return this.switcherCallbacks.isPkbCustomPageFirst();
    }

    public boolean isVkbSymbolShifted() {
        return this.vkbSymbolShiftState == 1;
    }

    /** @param i 0 unshifted, 1 shifted, 2 locked */
        void setPkbSymbolShiftState(int i) {
        this.pkbSymbolShiftState = i;
    }

    private int getSymbolShiftStateFromOrder() {
        int iMo6776F = this.switcherCallbacks.getSymbolPageOrder();
        if (iMo6776F != 1) {
            if (iMo6776F == 3) {
                return 2;
            }
            if (iMo6776F != 5) {
                return iMo6776F != 7 ? 0 : 2;
            }
        }
        return 1;
    }
}

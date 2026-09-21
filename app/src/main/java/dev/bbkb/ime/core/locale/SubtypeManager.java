package dev.bbkb.ime.core.locale;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.IBinder;
import android.os.LocaleList;
import android.util.Log;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;

import dev.bbkb.ime.R;
import dev.bbkb.ime.compat.InputMethodSubtypeCompat;
import dev.bbkb.ime.core.locale.multilanguage.MultiLanguageUtils;
import dev.bbkb.ime.core.shared.Logger;
import dev.bbkb.ime.keyboard.KeyboardSwitcher;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Set;
import dev.bbkb.ime.BuildConfig;



public final class SubtypeManager {

    private static final String TAG = "SubtypeManager";

    private static final SubtypeManager sInstance = new SubtypeManager();

    /** Shared worker for the binder call in {@link #switchToTargetIme} (audit CT-2). */
    private static final java.util.concurrent.ExecutorService IME_SWITCH_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "ImeSwitch");
                t.setDaemon(true);
                return t;
            });

    private static final InputMethodSubtype DUMMY_NO_LANGUAGE_SUBTYPE = InputMethodSubtypeCompat.newInputMethodSubtype(R.string.subtype_no_language_qwerty, R.drawable.ic_ime_switcher, "zz", "keyboard", "KeyboardLayoutSet=qwerty,AsciiCapable,EnabledWhenDefaultIsNotAsciiCapable,EmojiCapable", false, false, -572473389);

    private static final InputMethodSubtype DUMMY_EMOJI_SUBTYPE = InputMethodSubtypeCompat.newInputMethodSubtype(R.string.subtype_emoji, R.drawable.ic_ime_switcher, "zz", "keyboard", "KeyboardLayoutSet=emoji,EmojiCapable", false, false, -678744368);

    private RichInputMethodManager richImm;

    /**
     * Audit CT-31: this used to cache the {@link Resources} instance captured at first
     * {@code initialize(context)} and never refresh it, on a process-lifetime singleton. Holding
     * the {@link Context} and asking it for resources at use time keeps the configuration live
     * whatever context is passed (the service, a ContextThemeWrapper, the application).
     */
    private Context context;

    private InputMethodInfo shortcutIme;

    private InputMethodSubtype currentSubtype;

    private InputMethodSubtype shortcutSubtype;

    private InputMethodSubtype noLanguageSubtype;

    private InputMethodSubtype emojiSubtype;

    private boolean isNetworkConnected;


    private boolean initialized = false;

    
    public enum ShortcutImeState {
        DISABLED,
        ENABLED,
        READY
    }

    public static SubtypeManager getInstance() {
        return sInstance;
    }

    private static void initializeStaticCaches(Context context) {
        ResourceLocaleUtils.init(context);
        RichInputMethodManager.init(context);
    }

    public static void initialize(Context context) {
        initializeStaticCaches(context);
        sInstance.initializeInternal(context);
    }

    public static void ensureInitialized(Context context) {
        initializeStaticCaches(context);
        sInstance.initializeIfNeeded(context);
    }

    private SubtypeManager() {
    }

    private void initializeIfNeeded(Context context) {
        if (this.context == null) {
            initializeInternal(context);
        }
    }

    private void initializeInternal(Context context) {
        this.context = context;
        this.richImm = RichInputMethodManager.getInstance();
        // Start optimistic: language-pack downloads are local, so nothing this IME does needs the
        // network. The only readers are the two `requireNetworkConnectivity` branches below, which
        // gate a *third-party* voice IME that declares it needs connectivity;
        // onNetworkStateChanged can still flip this false. (Audit CT-28/UT-19: the CONNECTIVITY_CHANGE
        // broadcast that feeds it is deprecated and unreliable, but replacing it with a
        // NetworkCallback needs ACCESS_NETWORK_STATE, which this app deliberately does not hold.)
        this.isNetworkConnected = true;
        onSubtypeChanged(this.richImm.getCurrentInputMethodSubtype(getNoLanguageSubtype()));
        updateParametersOnStartInputView();
        this.initialized = true;
    }

    public boolean isInitialized() {
        return this.initialized;
    }

    public void updateParametersOnStartInputView() {
        updateShortcutIme();
    }

    private void updateShortcutIme() {
        Map<InputMethodInfo, List<InputMethodSubtype>> shortcutInputMethodsAndSubtypes;
        try {
            shortcutInputMethodsAndSubtypes = this.richImm.getInputMethodManager().getShortcutInputMethodsAndSubtypes();
        } catch (SecurityException e) {
            HashMap map = new HashMap();
            if (BuildConfig.DEBUG) Log.i(TAG, "Caught SecurityException. Running as background user? Ignoring.", e);
            shortcutInputMethodsAndSubtypes = map;
        }
        this.shortcutIme = null;
        this.shortcutSubtype = null;
        Iterator<InputMethodInfo> it = shortcutInputMethodsAndSubtypes.keySet().iterator();
        if (it.hasNext()) {
            InputMethodInfo next = it.next();
            List<InputMethodSubtype> list = shortcutInputMethodsAndSubtypes.get(next);
            this.shortcutIme = next;
            this.shortcutSubtype = list.size() > 0 ? list.get(0) : null;
        }
    }

    public void onSubtypeChanged(InputMethodSubtype inputMethodSubtype) {
        this.currentSubtype = inputMethodSubtype;
        updateShortcutIme();
    }

    public void switchToShortcutIme(InputMethodService inputMethodService) {
        InputMethodInfo inputMethodInfo = this.shortcutIme;
        if (inputMethodInfo == null) {
            Logger.error(TAG, "Voice Input IME cannot be launched because it is not initialized");
        } else {
            switchToTargetIme(inputMethodInfo.getId(), this.shortcutSubtype, inputMethodService);
        }
    }

    public boolean switchToRapidInputIme(InputMethodService inputMethodService) {
        IBinder iBinder;
        String strM4255d = getRapidInputImeId();
        if (strM4255d.isEmpty() || (iBinder = inputMethodService.getWindow().getWindow().getAttributes().token) == null) {
            return false;
        }
        this.richImm.getInputMethodManager().setInputMethod(iBinder, strM4255d);
        return true;
    }

    public String getRapidInputImeId() {
        for (InputMethodInfo inputMethodInfo : this.richImm.getInputMethodManager().getEnabledInputMethodList()) {
            if (inputMethodInfo.getId().endsWith("/.RapidInput")) {
                return inputMethodInfo.getId();
            }
        }
        return "";
    }

    private void switchToTargetIme(final String str, final InputMethodSubtype inputMethodSubtype, InputMethodService inputMethodService) {
        final IBinder iBinder = inputMethodService.getWindow().getWindow().getAttributes().token;
        if (iBinder == null) {
            Logger.error(TAG, "Unable to switch IME to [" + str + "] with subtype [" + inputMethodSubtype.getMode() + "] because window attributes are unavailable");
            return;
        }
        final InputMethodManager inputMethodManagerM5887c = this.richImm.getInputMethodManager();
        // Audit CT-2: this used to construct a NEW single-thread ExecutorService on every call and
        // never shut it down. Its core thread has no keep-alive, so it parked alive after the task
        // and reclamation depended on a finalizer — one leaked thread per shortcut-IME key press,
        // in a process that lives for the whole user session. One shared daemon executor instead.
        IME_SWITCH_EXECUTOR.execute(() -> {
            try {
                inputMethodManagerM5887c.setInputMethodAndSubtype(iBinder, str, inputMethodSubtype);
            } catch (RuntimeException e) {
                Logger.errorWithException(SubtypeManager.TAG, e, "Exception while invoking InputMethodManager.setInputMethodAndSubtype(" + iBinder + ", " + str + ", " + inputMethodSubtype.getMode() + ")");
            }
        });
    }

    /**
     * Audit CT-5: this and {@link #isShortcutImeReady()} used to call {@link #updateShortcutIme()}
     * unconditionally on entry — a synchronous binder IPC
     * ({@code imm.getShortcutInputMethodsAndSubtypes()}) on the main thread. {@code
     * KeyboardSwitcher.setKeyboard} calls {@code isShortcutImeReady()} once per keyboard swap
     * (shift toggle, symbol page, layout rebuild) and {@code SettingsValues} adds a third caller
     * per settings load. The data — which shortcut IMEs are installed — only changes on package
     * install/uninstall, and {@link #onSubtypeChanged} and {@link #updateParametersOnStartInputView}
     * already refresh it.
     */
    public boolean isShortcutImeEnabled() {
        InputMethodInfo inputMethodInfo = this.shortcutIme;
        if (inputMethodInfo == null) {
            return false;
        }
        InputMethodSubtype inputMethodSubtype = this.shortcutSubtype;
        if (inputMethodSubtype == null) {
            return true;
        }
        return this.richImm.checkIfSubtypeBelongsToImeAndEnabled(inputMethodInfo, inputMethodSubtype);
    }

    /** See {@link #isShortcutImeEnabled()} for why the refresh call is gone (audit CT-5). */
    public boolean isShortcutImeReady() {
        if (this.shortcutIme == null) {
            return false;
        }
        InputMethodSubtype inputMethodSubtype = this.shortcutSubtype;
        if (inputMethodSubtype != null && inputMethodSubtype.containsExtraValueKey("requireNetworkConnectivity")) {
            return this.isNetworkConnected;
        }
        return true;
    }

    public ShortcutImeState getShortcutImeState() {
        if (!isShortcutImeEnabled()) {
            return ShortcutImeState.DISABLED;
        }
        InputMethodSubtype inputMethodSubtype = this.shortcutSubtype;
        if (inputMethodSubtype == null) {
            return ShortcutImeState.READY;
        }
        if (inputMethodSubtype.containsExtraValueKey("requireNetworkConnectivity")) {
            return this.isNetworkConnected ? ShortcutImeState.READY : ShortcutImeState.ENABLED;
        }
        return ShortcutImeState.READY;
    }

    public void onNetworkStateChanged(Intent intent) {
        this.isNetworkConnected = !intent.getBooleanExtra("noConnectivity", false);
        KeyboardSwitcher.getInstance().updateSubtypeDisplay();
    }

    public boolean shouldRemoveActiveKeyboardLocale(Context context, LocaleList localeList) {
        if (Build.VERSION.SDK_INT < 24 || !MultiLanguageUtils.isUsingSystemLanguages(context)) {
            return false;
        }
        Locale localeM4259h = getCurrentSubtypeLocale();
        for (int i = 0; i < localeList.size(); i++) {
            try {
                if (localeM4259h.getISO3Language().equals(localeList.get(i).getISO3Language())) {
                    return false;
                }
            } catch (MissingResourceException unused) {
                Logger.info(TAG, "The active locale or system locale doesn't have a 3 letter region code to compare so assume it is correct.");
                return false;
            }
        }
        Logger.info(TAG, "The currently active locale " + localeM4259h + " and system locales " + localeList + " do not match therefore we need to remove the active keyboard locale.");
        return true;
    }


    /**
     * Audit W1-F: the forcedSubtype override (set only by the zero-caller forceSubtype) used to
     * be re-read here and in getCurrentSubtype on every keystroke, via
     * LocaleUtils.isCurrentSubtypeX().
     */
    public Locale getCurrentSubtypeLocale() {
        return ResourceLocaleUtils.getSubtypeLocale(getCurrentSubtype());
    }

    public Set<Locale> getCurrentSubtypeAdditionalLocales() {
        return ResourceLocaleUtils.getAdditionalLocales(getCurrentSubtype());
    }

    public InputMethodSubtype getCurrentSubtype() {
        return this.currentSubtype;
    }

    public InputMethodSubtype getNoLanguageSubtype() {
        if (this.noLanguageSubtype == null) {
            this.noLanguageSubtype = this.richImm.findSubtypeByLocaleAndKeyboardLayoutSet("zz", "qwerty");
        }
        InputMethodSubtype inputMethodSubtype = this.noLanguageSubtype;
        if (inputMethodSubtype != null) {
            return inputMethodSubtype;
        }
        if (BuildConfig.DEBUG) Log.w(TAG, "Can't find any language with QWERTY subtype");
        if (BuildConfig.DEBUG) Log.w(TAG, "No input method subtype found; returning dummy subtype: " + DUMMY_NO_LANGUAGE_SUBTYPE);
        return DUMMY_NO_LANGUAGE_SUBTYPE;
    }

    public InputMethodSubtype getEmojiSubtype() {
        if (this.emojiSubtype == null) {
            this.emojiSubtype = this.richImm.findSubtypeByLocaleAndKeyboardLayoutSet("zz", "emoji");
        }
        InputMethodSubtype inputMethodSubtype = this.emojiSubtype;
        if (inputMethodSubtype != null) {
            return inputMethodSubtype;
        }
        if (BuildConfig.DEBUG) Log.w(TAG, "Can't find emoji subtype");
        if (BuildConfig.DEBUG) Log.w(TAG, "No input method subtype found; returning dummy subtype: " + DUMMY_EMOJI_SUBTYPE);
        return DUMMY_EMOJI_SUBTYPE;
    }

    public String getCurrentConverterDescriptor() {
        return ResourceLocaleUtils.getConverterDescriptor(getCurrentSubtype());
    }
}

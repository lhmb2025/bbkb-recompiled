package dev.bbkb.ime.core.locale;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.app.Dialog;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.SpeechRecognizer;
import android.view.Window;
import dev.bbkb.ime.core.settings.PrefsManager;
import android.util.Log;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;

import androidx.core.content.ContextCompat;
import androidx.core.os.ConfigurationCompat;

import dev.bbkb.ime.core.locale.multilanguage.MultiLanguageUtils;
import dev.bbkb.ime.core.settings.util.SettingsManager;
import dev.bbkb.ime.core.device.detection.HardwareProbe;
import dev.bbkb.ime.core.subtypeswitcher.SideloadedSubtypes;
import dev.bbkb.ime.core.subtypeswitcher.SubtypeFactory;
import dev.bbkb.ime.core.shared.Logger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import dev.bbkb.ime.BuildConfig;



public class RichInputMethodManager {

    private static final String TAG = "RichInputMethodManager";

    private static final RichInputMethodManager sInstance = new RichInputMethodManager();

    final HashMap<InputMethodInfo, List<InputMethodSubtype>> subtypeListCacheWithImplicit = new HashMap<>();

    final HashMap<InputMethodInfo, List<InputMethodSubtype>> subtypeListCacheWithoutImplicit = new HashMap<>();

    final ArrayList<InputMethodSubtype> subtypeSwitchHistory = new ArrayList<>();

    private InputMethodManager imm;

    private ImeInfoCache imeInfoCache;

    /*
     * Invalidation matrix (P-12). Every field focus used to clear these caches, which cost about
     * a dozen binder calls to system_server per focus. They are now dropped together, by
     * clearSubtypeCaches(), only when something that feeds them can have changed:
     *
     *   event                                       | source
     *   --------------------------------------------+--------------------------------------------
     *   current subtype changed                     | SubtypeManager.onSubtypeChanged (from the
     *                                               |   IME's onCurrentInputMethodSubtypeChanged)
     *   enabled IMEs or subtypes changed elsewhere  | ContentObserver on enabled_input_methods
     *   package added/removed/changed/replaced      | package receiver (recogniser, shortcut and
     *                                               |   other IMEs come and go)
     *   Language screen saved / pack registered     | SubtypeEnabler, KeyboardLanguages,
     *                                               |   setAdditionalInputMethodSubtypes
     *   system locale changed                       | LocaleChangeReceiver; onInputViewStarted
     *                                               |   compares the system locale list
     *   safety net                                  | onInputViewStarted, after MAX_CACHE_AGE_MS
     *
     *   cache                                       | readers
     *   --------------------------------------------+--------------------------------------------
     *   imeInfoCache (this IME's InputMethodInfo)   | getInputMethodInfoOfThisIme and its users
     *   subtypeListCacheWith/WithoutImplicit        | getMyEnabledInputMethodSubtypeList,
     *                                               |   hasMultipleEnabledSubtypesInThisIme,
     *                                               |   checkIfSubtypeBelongsTo*, isShortcutImeEnabled
     *   recognitionAvailable                        | isRecognitionAvailable (SettingsValues mic
     *                                               |   key, InputMethodHelper)
     *   offerSwitchingToNextIme                     | shouldOfferSwitchingToNextInputMethod(ims)
     *   generation                                  | SubtypeManager's shortcut IME and system-
     *                                               |   languages memos, moveToEnabledSubtype...
     *
     * getEnabledSubtypesOfThisIme() stays uncached on purpose: the switch-language paths and the
     * dialogs must see the framework's list even if an event above was missed.
     */

    /** The caches are refreshed at least this often while fields keep starting. */
    static final long MAX_CACHE_AGE_MS = 60_000L;

    /** Bumped by every {@link #clearSubtypeCaches()}; memos elsewhere compare it. */
    private volatile int generation;

    private volatile long cachesClearedAtMs = SystemClock.elapsedRealtime();

    private volatile Boolean recognitionAvailable;

    private volatile Context recognitionAvailableFor;

    private volatile Boolean offerSwitchingToNextIme;

    /** The system locale list the caches were last valid for, as language tags. */
    private String lastSystemLocales;

    /** Generation for which moveToEnabledSubtypeIfCurrentIsNot found nothing to do. */
    private int enabledSubtypeCheckedGeneration = -1;

    private RichInputMethodManager() {
    }

    public static RichInputMethodManager getInstance() {
        sInstance.checkInitialized();
        return sInstance;
    }

    public static void init(Context context) {
        sInstance.initInternal(context);
    }

    public static boolean isInitialized() {
        return sInstance.isInitializedInternal();
    }

    private synchronized boolean isInitializedInternal() {
        return this.imm != null;
    }

    private void checkInitialized() {
        if (isInitializedInternal()) {
            return;
        }
        throw new RuntimeException(TAG + " is used before initialization");
    }

    private synchronized void initInternal(Context context) {
        if (isInitializedInternal()) {
            return;
        }
        this.imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        this.imeInfoCache = new ImeInfoCache(context.getPackageName());
        ResourceLocaleUtils.init(context);
        registerInvalidationSources(context.getApplicationContext());
        if (HardwareProbe.isDalvikVM()) {
            setAdditionalInputMethodSubtypes(getAdditionalSubtypes(context));
        }
    }

    /**
     * The events from outside this process that change what the caches hold. Registered once on
     * the application context, so they live as long as the caches do (the process).
     */
    private void registerInvalidationSources(Context appContext) {
        if (appContext == null) {
            return;
        }
        final Handler main = new Handler(Looper.getMainLooper());
        try {
            final IntentFilter packages = new IntentFilter();
            packages.addAction(Intent.ACTION_PACKAGE_ADDED);
            packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
            packages.addAction(Intent.ACTION_PACKAGE_CHANGED);
            packages.addAction(Intent.ACTION_PACKAGE_REPLACED);
            packages.addDataScheme("package");
            ContextCompat.registerReceiver(appContext, new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    clearSubtypeCaches();
                }
            }, packages, null, main, ContextCompat.RECEIVER_NOT_EXPORTED);
        } catch (RuntimeException e) {
            Logger.warn(TAG, "Package receiver not registered: " + e);
        }
        try {
            // Enabling another keyboard, or a language of ours, in the system settings writes
            // this setting and tells this IME nothing else.
            appContext.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(ENABLED_INPUT_METHODS), false,
                    new ContentObserver(main) {
                        @Override
                        public void onChange(boolean selfChange) {
                            clearSubtypeCaches();
                        }
                    });
        } catch (RuntimeException e) {
            Logger.warn(TAG, "enabled_input_methods observer not registered: " + e);
        }
    }

    /** {@code Settings.Secure.ENABLED_INPUT_METHODS}, which is hidden API. */
    private static final String ENABLED_INPUT_METHODS = "enabled_input_methods";

    /**
     * Called as an input view starts, in place of the old unconditional cache clear: drops the
     * caches only when the system locales changed since they were filled, or when they are older
     * than {@link #MAX_CACHE_AGE_MS} (in case an invalidation event was missed).
     */
    public void onInputViewStarted() {
        final String locales = ConfigurationCompat.getLocales(Resources.getSystem().getConfiguration()).toLanguageTags();
        final boolean localesChanged = this.lastSystemLocales != null && !this.lastSystemLocales.equals(locales);
        this.lastSystemLocales = locales;
        if (localesChanged || SystemClock.elapsedRealtime() - this.cachesClearedAtMs >= MAX_CACHE_AGE_MS) {
            clearSubtypeCaches();
        }
    }

    /**
     * For tests: forget the framework handle, so the next {@link #init} registers its event
     * sources on that test's application.
     */
    static synchronized void resetForTest() {
        sInstance.imm = null;
        sInstance.lastSystemLocales = null;
        sInstance.enabledSubtypeCheckedGeneration = -1;
        sInstance.subtypeListCacheWithImplicit.clear();
        sInstance.subtypeListCacheWithoutImplicit.clear();
        sInstance.recognitionAvailable = null;
        sInstance.offerSwitchingToNextIme = null;
        sInstance.generation++;
    }

    /** Changes whenever the caches are dropped; for memos kept outside this class. */
    public int getCacheGeneration() {
        return this.generation;
    }

    /**
     * {@link SpeechRecognizer#isRecognitionAvailable(Context)}, a PackageManager query, asked once
     * per cache generation instead of twice per field focus. A recogniser appears or goes with a
     * package change, which drops the caches.
     */
    public boolean isRecognitionAvailable(Context context) {
        if (context == null) {
            return false;
        }
        // Kept per application: one in a real process, a fresh one per Robolectric test.
        final Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        Boolean available = this.recognitionAvailable;
        if (available == null || this.recognitionAvailableFor != app) {
            available = SpeechRecognizer.isRecognitionAvailable(context);
            this.recognitionAvailable = available;
            this.recognitionAvailableFor = app;
        }
        return available;
    }

    /**
     * Whether the switcher key should offer the next input method, asked once per cache
     * generation. It depends on the enabled IMEs and subtypes, whose changes drop the caches.
     */
    @SuppressWarnings("deprecation")
    public boolean shouldOfferSwitchingToNextInputMethod(InputMethodService ims) {
        Boolean offer = this.offerSwitchingToNextIme;
        if (offer != null) {
            return offer;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            offer = ims.shouldOfferSwitchingToNextInputMethod();
        } else {
            final IBinder token = windowToken(ims);
            if (token == null) {
                // No window yet: answer without caching, the next field asks again.
                return hasMultipleEnabledSubtypesInThisIme(false);
            }
            offer = this.imm.shouldOfferSwitchingToNextInputMethod(token);
        }
        this.offerSwitchingToNextIme = offer;
        return offer;
    }

    public InputMethodSubtype[] getAdditionalSubtypes(Context context) {
        ResourceLocaleUtils.init(context);
        ArrayList arrayList = new ArrayList();
        SharedPreferences defaultSharedPreferences = PrefsManager.INSTANCE.getPrefs(context);
        arrayList.addAll(Arrays.asList(SubtypeFactory.createSubtypesFromPref(SettingsManager.getCustomInputStyles(defaultSharedPreferences, context.getResources()))));
        arrayList.addAll(MultiLanguageUtils.getSubtypesFromPrefs(defaultSharedPreferences));
        // Third source: runtime subtypes for engine-supported languages method.xml does not
        // declare, added when the user side-loads a pack for one. Kept in its own preference so
        // that custom_input_styles keeps falling back to the predefined_subtypes resource arrays.
        arrayList.addAll(Arrays.asList(SubtypeFactory.createSubtypesFromPref(
                SideloadedSubtypes.readRaw(defaultSharedPreferences))));
        return (InputMethodSubtype[]) arrayList.toArray(new InputMethodSubtype[arrayList.size()]);
    }

    public InputMethodManager getInputMethodManager() {
        checkInitialized();
        return this.imm;
    }

    public List<InputMethodSubtype> getMyEnabledInputMethodSubtypeList(boolean z) {
        return getEnabledInputMethodSubtypeList(getInputMethodInfoOfThisIme(), z);
    }

    /**
     * The subtypes the user has enabled for this IME, read straight from the framework.
     *
     * <p>Deliberately NOT the {@link #getMyEnabledInputMethodSubtypeList(boolean)} cache: the
     * callers here (the subtype-switch dialog, the locale-change receiver, the space-bar label
     * map) run at moments when the enabled set may have just changed outside our process, and
     * {@link #clearSubtypeCaches()} is only driven from two of them. Two byte-identical copies of
     * this call used to live in {@code SubtypeSwitcherReceiver} and {@code
     * QuickSubtypeSwitchHandler}; this is the single one.
     *
     * <p>Defect 1: empty when {@link #getInputMethodInfoOfThisIme()} is null. NOT passed through
     * to the framework — see {@link #getEnabledInputMethodSubtypeList(InputMethodInfo, boolean)}
     * for why a null {@code InputMethodInfo} is the wrong thing to hand it.
     */
    public List<InputMethodSubtype> getEnabledSubtypesOfThisIme() {
        final InputMethodInfo thisIme = getInputMethodInfoOfThisIme();
        if (thisIme == null) {
            return Collections.emptyList();
        }
        return this.imm.getEnabledInputMethodSubtypeList(thisIme, true);
    }

    /**
     * Audit CT-16: this and {@link #setInputMethodAndSubtype(IBinder, InputMethodSubtype)} /
     * {@link #shouldOfferSwitchingToNextInputMethod(IBinder)} wrap the IBinder-token
     * {@link InputMethodManager} variants deprecated in API 28-29, in favour of
     * {@code InputMethodService#switchToNextInputMethod(boolean)},
     * {@code #switchInputMethod(String, InputMethodSubtype)} and
     * {@code #shouldOfferSwitchingToNextInputMethod()}. The token variants have been
     * progressively restricted, and every in-repo caller has an InputMethodService in hand.
     * {@code BlackBerryIME.shouldShowLanguageSwitchKey} is migrated; the remaining callers live
     * in {@code SubtypeState} and {@code SubtypeSwitcherReceiver}.
     */
    /**
     * CT-16: token-free form for callers that hold the {@link InputMethodService}. Uses
     * {@code InputMethodService#switchToNextInputMethod(boolean)} (API 28+) and falls back to the
     * deprecated IBinder-token path, plus this class's own next-subtype/next-IME chain, below P
     * or when the framework declines.
     */
    public boolean switchToNextInputMethod(InputMethodService ims, boolean z) {
        if (z && mustRotateWithinIme(this.imm.getCurrentInputMethodSubtype(), getEnabledSubtypesOfThisIme())) {
            // The keyboard is on a language that is no longer enabled (the Language screen
            // removed it while it was active). Android 15 does not decline that rotation: its
            // switching controller logs "not in the list, falling back to most recent item" and
            // jumps to whatever was used last, which can be another input method even with
            // onlyCurrentIme. On the KEY2 that handed the keyboard to AOSP LatinIME when the
            // owner switched from a removed Indonesian keyboard to Arabic (2026-10-01), and it
            // read as a crash. Never ask the framework from that state.
            return switchToNextSubtypeWithinThisIme(ims);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && ims.switchToNextInputMethod(z)) {
            return true;
        }
        if (z) {
            // "Only this IME" means exactly that. The framework declines when there is no other
            // subtype to rotate to (one enabled keyboard, or the current one not in the enabled
            // list), and the fallback below used to hand the whole IME over to the next one on
            // the phone - AOSP LatinIME on the KEY2, whose suggestion strip then "disappeared"
            // after a multifunction-key language switch (owner report 2026-09-26). Stay here.
            return switchToNextSubtypeWithinThisIme(ims);
        }
        final IBinder token = windowToken(ims);
        if (token == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return switchToNextInputMethod(token, z);
        }
        return switchToNextInputSubtypeInThisIme(token, z) || switchToNextInputMethodAndSubtype(token);
    }

    /**
     * Rotate to the next enabled subtype of this IME, or to its first one when the current
     * subtype is not in the enabled list; with a single enabled subtype there is nowhere to go
     * and nothing happens. Reads the enabled list fresh (the user may just have changed it) and
     * never switches to another IME.
     */
    private boolean switchToNextSubtypeWithinThisIme(InputMethodService ims) {
        final List<InputMethodSubtype> enabled = getEnabledSubtypesOfThisIme();
        final InputMethodSubtype next = nextSubtypeWithinIme(this.imm.getCurrentInputMethodSubtype(), enabled);
        if (next == null) {
            return false;
        }
        setInputMethodAndSubtype(ims, next);
        return true;
    }

    /**
     * Whether a "switch language" press must be answered by this class and not the framework:
     * the current subtype is unknown or is not one of this IME's enabled ones. Pure, for the
     * unit test.
     */
    static boolean mustRotateWithinIme(InputMethodSubtype current, List<InputMethodSubtype> enabled) {
        if (enabled == null || enabled.isEmpty()) {
            return false;
        }
        return current == null || getSubtypeIndexInList(current, enabled) == -1;
    }

    /**
     * Leave a language that is no longer enabled: when the current subtype is not in this IME's
     * enabled list, switch to the first one that is. Called as a field takes focus, so removing
     * the active keyboard on the Language screen does not leave the user typing in it.
     *
     * @return true when it switched
     */
    public boolean moveToEnabledSubtypeIfCurrentIsNot(InputMethodService ims) {
        // Both inputs (the current subtype and the enabled list) only change through events that
        // drop the caches, so a generation already checked has nothing new to find: three binder
        // calls per field focus otherwise.
        final int checking = this.generation;
        if (checking == this.enabledSubtypeCheckedGeneration) {
            return false;
        }
        final InputMethodSubtype current = this.imm.getCurrentInputMethodSubtype();
        if (current == null) {
            return false;
        }
        final List<InputMethodSubtype> enabled = getEnabledSubtypesOfThisIme();
        if (!mustRotateWithinIme(current, enabled)) {
            this.enabledSubtypeCheckedGeneration = checking;
            return false;
        }
        setInputMethodAndSubtype(ims, enabled.get(0));
        return true;
    }

    /**
     * The subtype a "switch language" press should land on, given the current one and this
     * IME's enabled list. Null when there is nothing to switch to. Pure, for the unit test.
     */
    static InputMethodSubtype nextSubtypeWithinIme(InputMethodSubtype current, List<InputMethodSubtype> enabled) {
        if (enabled == null || enabled.isEmpty()) {
            return null;
        }
        final int index = current == null ? -1 : getSubtypeIndexInList(current, enabled);
        if (index == -1) {
            return enabled.get(0);
        }
        if (enabled.size() == 1) {
            return null;
        }
        return enabled.get((index + 1) % enabled.size());
    }

    /**
     * CT-16: token-free form of {@link #setInputMethodAndSubtype(IBinder, InputMethodSubtype)},
     * using {@code InputMethodService#switchInputMethod(String, InputMethodSubtype)} (API 28+).
     */
    public void setInputMethodAndSubtype(InputMethodService ims, InputMethodSubtype inputMethodSubtype) {
        // Defect 1: no id means we are not registered with the framework, so there is nothing to
        // switch to. Do nothing rather than NPE / hand the framework a null id.
        final String thisImeId = getInputMethodIdOfThisIme();
        if (thisImeId == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ims.switchInputMethod(thisImeId, inputMethodSubtype);
            return;
        }
        final IBinder token = windowToken(ims);
        if (token != null) {
            setInputMethodAndSubtype(token, inputMethodSubtype);
        }
    }


    /** The IME window's token, or null before the window exists. */
    private static IBinder windowToken(InputMethodService ims) {
        final Dialog dialog = (ims == null) ? null : ims.getWindow();
        final Window window = (dialog == null) ? null : dialog.getWindow();
        return (window == null) ? null : window.getAttributes().token;
    }

    @SuppressWarnings("deprecation")
    public boolean switchToNextInputMethod(IBinder iBinder, boolean z) {
        if (this.imm.switchToNextInputMethod(iBinder, z) || switchToNextInputSubtypeInThisIme(iBinder, z)) {
            return true;
        }
        return switchToNextInputMethodAndSubtype(iBinder);
    }

    private boolean switchToNextInputSubtypeInThisIme(IBinder iBinder, boolean z) {
        InputMethodSubtype currentInputMethodSubtype = this.imm.getCurrentInputMethodSubtype();
        List<InputMethodSubtype> listM5877a = getMyEnabledInputMethodSubtypeList(true);
        int iM5869b = getSubtypeIndexInList(currentInputMethodSubtype, listM5877a);
        if (iM5869b == -1) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Can't find current subtype in enabled subtypes");
            return false;
        }
        int size = (iM5869b + 1) % listM5877a.size();
        if (size <= iM5869b && !z) {
            return false;
        }
        setInputMethodAndSubtype(iBinder, listM5877a.get(size));
        return true;
    }

    private boolean switchToNextInputMethodAndSubtype(IBinder iBinder) {
        InputMethodManager inputMethodManager = this.imm;
        List<InputMethodInfo> enabledInputMethodList = inputMethodManager.getEnabledInputMethodList();
        // Defect 1: hoisted into a local. thisIme is null when we are not registered with the
        // framework, and getImiIndexInList() then returns -1 — so the debug log below used to
        // dereference the null on the one path that can reach it.
        final InputMethodInfo thisIme = getInputMethodInfoOfThisIme();
        int iM5860a = getImiIndexInList(thisIme, enabledInputMethodList);
        if (iM5860a == -1) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Can't find current IME in enabled IMEs: IME package="
                    + (thisIme == null ? "<no InputMethodInfo for this IME>" : thisIme.getPackageName()));
            return false;
        }
        InputMethodInfo inputMethodInfoM5861a = getNextNonAuxiliaryIme(iM5860a, enabledInputMethodList);
        List<InputMethodSubtype> listM5864a = getEnabledInputMethodSubtypeList(inputMethodInfoM5861a, true);
        if (listM5864a.isEmpty()) {
            inputMethodManager.setInputMethod(iBinder, inputMethodInfoM5861a.getId());
            return true;
        }
        inputMethodManager.setInputMethodAndSubtype(iBinder, inputMethodInfoM5861a.getId(), listM5864a.get(0));
        return true;
    }

    private static int getImiIndexInList(InputMethodInfo inputMethodInfo, List<InputMethodInfo> list) {
        int size = list.size();
        for (int i = 0; i < size; i++) {
            if (list.get(i).equals(inputMethodInfo)) {
                return i;
            }
        }
        return -1;
    }

    private static InputMethodInfo getNextNonAuxiliaryIme(int i, List<InputMethodInfo> list) {
        int size = list.size();
        for (int i2 = 1; i2 < size; i2++) {
            InputMethodInfo inputMethodInfo = list.get((i + i2) % size);
            if (!isAuxiliaryIme(inputMethodInfo)) {
                return inputMethodInfo;
            }
        }
        return list.get(i);
    }

    private static boolean isAuxiliaryIme(InputMethodInfo inputMethodInfo) {
        int subtypeCount = inputMethodInfo.getSubtypeCount();
        if (subtypeCount == 0) {
            return false;
        }
        for (int i = 0; i < subtypeCount; i++) {
            if (!inputMethodInfo.getSubtypeAt(i).isAuxiliary()) {
                return false;
            }
        }
        return true;
    }

    
    private class ImeInfoCache {

        private final String imePackageName;

        private InputMethodInfo cachedThisImeInfo;

        ImeInfoCache(String str) {
            this.imePackageName = str;
        }

        public synchronized InputMethodInfo get() {
            if (this.cachedThisImeInfo == null) {
                for (InputMethodInfo inputMethodInfo : RichInputMethodManager.this.imm.getInputMethodList()) {
                    if (inputMethodInfo.getPackageName().equals(this.imePackageName)) {
                        this.cachedThisImeInfo = inputMethodInfo;
                        return inputMethodInfo;
                    }
                }
                throw new RuntimeException("Input method id for " + this.imePackageName + " not found.");
            }
            return this.cachedThisImeInfo;
        }

        public synchronized void clear() {
            this.cachedThisImeInfo = null;
        }
    }

    public InputMethodInfo getInputMethodInfoOfThisIme() {
        try {
            return this.imeInfoCache.get();
        } catch (RuntimeException e) {
            Logger.errorWithException(TAG, e, "getInputMethodInfoOfThisIme() failed to get InputMethodInfo.");
            return null;
        }
    }

    /**
     * This IME's framework id, or null when {@link #getInputMethodInfoOfThisIme()} has none.
     *
     * <p>Defect 1: this used to dereference the null and take the caller out with an NPE. Null is
     * the right degraded answer because every out-of-class caller feeds it to
     * {@code IntentUtils.getInputLanguageSelectionIntent()}, which drops an empty id and launches
     * the system's input-language screen without pre-selecting us — which is precisely the screen
     * a user in this state (installed, not yet enabled) needs to reach. The two in-class callers
     * guard it themselves, because {@code InputMethodManager} will not take a null id.
     */
    public String getInputMethodIdOfThisIme() {
        final InputMethodInfo thisIme = getInputMethodInfoOfThisIme();
        return thisIme == null ? null : thisIme.getId();
    }

    public boolean checkIfSubtypeBelongsToThisImeAndEnabled(InputMethodSubtype inputMethodSubtype) {
        return checkIfSubtypeBelongsToImeAndEnabled(getInputMethodInfoOfThisIme(), inputMethodSubtype);
    }

    public boolean checkIfSubtypeBelongsToThisImeAndImplicitlyEnabled(InputMethodSubtype inputMethodSubtype) {
        return checkIfSubtypeBelongsToThisImeAndEnabled(inputMethodSubtype) && !checkIfSubtypeBelongsToList(inputMethodSubtype, getMyEnabledInputMethodSubtypeList(false));
    }

    public boolean checkIfSubtypeBelongsToImeAndEnabled(InputMethodInfo inputMethodInfo, InputMethodSubtype inputMethodSubtype) {
        return checkIfSubtypeBelongsToList(inputMethodSubtype, getEnabledInputMethodSubtypeList(inputMethodInfo, true));
    }

    private static boolean checkIfSubtypeBelongsToList(InputMethodSubtype inputMethodSubtype, List<InputMethodSubtype> list) {
        return getSubtypeIndexInList(inputMethodSubtype, list) != -1;
    }

    private static int getSubtypeIndexInList(InputMethodSubtype inputMethodSubtype, List<InputMethodSubtype> list) {
        int size = list.size();
        for (int i = 0; i < size; i++) {
            if (list.get(i).equals(inputMethodSubtype)) {
                return i;
            }
        }
        return -1;
    }

    public InputMethodSubtype getCurrentInputMethodSubtype(InputMethodSubtype inputMethodSubtype) {
        InputMethodSubtype currentInputMethodSubtype = this.imm.getCurrentInputMethodSubtype();
        return currentInputMethodSubtype != null ? currentInputMethodSubtype : inputMethodSubtype;
    }

    public boolean hasMultipleEnabledIMEsOrSubtypes(boolean z) {
        return hasMultipleEnabledSubtypes(z, this.imm.getEnabledInputMethodList());
    }

    public boolean hasMultipleEnabledSubtypesInThisIme(boolean z) {
        return hasMultipleEnabledSubtypes(z, Collections.singletonList(getInputMethodInfoOfThisIme()));
    }

    private boolean hasMultipleEnabledSubtypes(boolean z, List<InputMethodInfo> list) {
        int i = 0;
        for (InputMethodInfo inputMethodInfo : list) {
            if (i > 1) {
                return true;
            }
            List<InputMethodSubtype> listM5864a = getEnabledInputMethodSubtypeList(inputMethodInfo, true);
            if (listM5864a.isEmpty()) {
                i++;
            } else {
                Iterator<InputMethodSubtype> it = listM5864a.iterator();
                int i2 = 0;
                while (it.hasNext()) {
                    if (it.next().isAuxiliary()) {
                        i2++;
                    }
                }
                if (listM5864a.size() - i2 > 0 || (z && i2 > 1)) {
                    i++;
                }
            }
        }
        if (i > 1) {
            return true;
        }
        Iterator<InputMethodSubtype> it2 = getMyEnabledInputMethodSubtypeList(true).iterator();
        int i3 = 0;
        while (it2.hasNext()) {
            if ("keyboard".equals(it2.next().getMode())) {
                i3++;
            }
        }
        return i3 > 1;
    }

    public InputMethodSubtype findSubtypeByLocaleAndKeyboardLayoutSet(String str, String str2) {
        InputMethodInfo inputMethodInfoM5890d = getInputMethodInfoOfThisIme();
        if (inputMethodInfoM5890d == null) {
            return null;
        }
        int subtypeCount = inputMethodInfoM5890d.getSubtypeCount();
        for (int i = 0; i < subtypeCount; i++) {
            InputMethodSubtype subtypeAt = inputMethodInfoM5890d.getSubtypeAt(i);
            String strM5628e = ResourceLocaleUtils.getKeyboardLayoutSetName(subtypeAt);
            if (str.equals(subtypeAt.getLocale()) && str2.equals(strM5628e)) {
                return subtypeAt;
            }
        }
        return null;
    }

    public void setInputMethodAndSubtype(IBinder iBinder, InputMethodSubtype inputMethodSubtype) {
        // Defect 1: see setInputMethodAndSubtype(InputMethodService, InputMethodSubtype).
        final String thisImeId = getInputMethodIdOfThisIme();
        if (thisImeId == null) {
            return;
        }
        this.imm.setInputMethodAndSubtype(iBinder, thisImeId, inputMethodSubtype);
    }

    public void setAdditionalInputMethodSubtypes(InputMethodSubtype[] inputMethodSubtypeArr) {
        InputMethodInfo inputMethodInfoM5890d = getInputMethodInfoOfThisIme();
        if (inputMethodInfoM5890d != null) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Registering " + inputMethodSubtypeArr.length + " additional subtypes");
            for (InputMethodSubtype subtype : inputMethodSubtypeArr) {
                if (BuildConfig.DEBUG) {
                Log.d(TAG, "  Registering subtype: locale=" + subtype.getLocale()
                    + " extraValue=" + subtype.getExtraValue()
                    + " hashCode=" + subtype.hashCode());
                }
            }
            this.imm.setAdditionalInputMethodSubtypes(inputMethodInfoM5890d.getId(), inputMethodSubtypeArr);
        }
        clearSubtypeCaches();
        verifySubtypeRegistration(inputMethodSubtypeArr);
    }

    private void verifySubtypeRegistration(InputMethodSubtype[] requested) {
        List<InputMethodSubtype> enabled = getMyEnabledInputMethodSubtypeList(true);
        if (BuildConfig.DEBUG) {
        Log.d(TAG, "Subtype registration verification: requested=" + requested.length
            + " totalEnabled=" + enabled.size());
        }
        for (InputMethodSubtype subtype : requested) {
            boolean found = false;
            for (InputMethodSubtype enabledSubtype : enabled) {
                if (enabledSubtype.equals(subtype)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                if (BuildConfig.DEBUG) {
                Log.w(TAG, "SUBTYPE REGISTRATION FAILED: locale=" + subtype.getLocale()
                    + " extraValue=" + subtype.getExtraValue()
                    + " hashCode=" + subtype.hashCode()
                    + " — possible hash collision, add to collisionHack in SubtypeFactory");
                }
            } else {
                if (BuildConfig.DEBUG) Log.d(TAG, "  Verified: locale=" + subtype.getLocale() + " registered OK");
            }
        }
    }

    /**
     * Defect 1: a null {@code inputMethodInfo} means {@link #getInputMethodInfoOfThisIme()} could
     * not find this IME in the framework's list — we are installed but not yet enabled, or a
     * package update is in flight. Answer "no subtypes" rather than forwarding the null.
     *
     * <p>Forwarding it would be worse than a crash, not better: {@code
     * InputMethodManager#getEnabledInputMethodSubtypeList(null, ...)} is documented to mean "the
     * CURRENTLY SELECTED IME", which in exactly this state is somebody else's keyboard — so we
     * would silently answer with another IME's subtypes and then cache them under the null key
     * until the next {@link #clearSubtypeCaches()}. Every caller of this method treats the result
     * as "our subtypes".
     */
    private List<InputMethodSubtype> getEnabledInputMethodSubtypeList(InputMethodInfo inputMethodInfo, boolean z) {
        if (inputMethodInfo == null) {
            Logger.warn(TAG, "getEnabledInputMethodSubtypeList() with no InputMethodInfo for this IME"
                    + " (not enabled yet, or mid-update); reporting no subtypes.");
            return Collections.emptyList();
        }
        HashMap<InputMethodInfo, List<InputMethodSubtype>> map = z ? this.subtypeListCacheWithImplicit : this.subtypeListCacheWithoutImplicit;
        List<InputMethodSubtype> list = map.get(inputMethodInfo);
        if (list != null) {
            return list;
        }
        List<InputMethodSubtype> enabledInputMethodSubtypeList = this.imm.getEnabledInputMethodSubtypeList(inputMethodInfo, z);
        map.put(inputMethodInfo, enabledInputMethodSubtypeList);
        return enabledInputMethodSubtypeList;
    }

    public InputMethodSubtype getLastInputMethodSubtype() {
        return this.imm.getLastInputMethodSubtype();
    }

    public void recordSubtypeSwitch(InputMethodSubtype inputMethodSubtype) {
        if (this.subtypeSwitchHistory.isEmpty()) {
            this.subtypeSwitchHistory.add(getLastInputMethodSubtype());
        }
        this.subtypeSwitchHistory.add(inputMethodSubtype);
    }

    public ArrayList<InputMethodSubtype> getSubtypeSwitchHistory() {
        return this.subtypeSwitchHistory;
    }

    /**
     * Drops every cache this class keeps (see the invalidation matrix at the top). Cheap: the
     * framework is asked again only by the next reader.
     */
    public void clearSubtypeCaches() {
        this.subtypeListCacheWithImplicit.clear();
        this.subtypeListCacheWithoutImplicit.clear();
        this.imeInfoCache.clear();
        this.recognitionAvailable = null;
        this.offerSwitchingToNextIme = null;
        this.cachesClearedAtMs = SystemClock.elapsedRealtime();
        this.generation++;
    }

    public boolean shouldOfferSwitchingToNextInputMethod(IBinder iBinder) {
        return this.imm.shouldOfferSwitchingToNextInputMethod(iBinder);
    }

}

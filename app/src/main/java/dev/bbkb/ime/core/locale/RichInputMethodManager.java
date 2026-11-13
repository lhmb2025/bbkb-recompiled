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
     * Cache invalidation: clearSubtypeCaches() drops all caches together, only on these events
     * (clearing per field focus costs about a dozen binder calls):
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

    /** Safety-net cache age, checked as input views start. */
    static final long MAX_CACHE_AGE_MS = 60_000L;

    /** Bumped by every {@link #clearSubtypeCaches()}; memos elsewhere compare it. */
    private volatile int generation;

    private volatile long cachesClearedAtMs = SystemClock.elapsedRealtime();

    private volatile Boolean recognitionAvailable;

    private volatile Context recognitionAvailableFor;

    private volatile Boolean offerSwitchingToNextIme;

    /** Language tags of the system locales the caches were last valid for. */
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

    /** Registered once on the application context, so they live as long as the caches. */
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
            // System settings changes to enabled keyboards or our languages only write this setting.
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

    /** Drops the caches if the system locales changed or they are older than {@link #MAX_CACHE_AGE_MS}. */
    public void onInputViewStarted() {
        final String locales = ConfigurationCompat.getLocales(Resources.getSystem().getConfiguration()).toLanguageTags();
        final boolean localesChanged = this.lastSystemLocales != null && !this.lastSystemLocales.equals(locales);
        this.lastSystemLocales = locales;
        if (localesChanged || SystemClock.elapsedRealtime() - this.cachesClearedAtMs >= MAX_CACHE_AGE_MS) {
            clearSubtypeCaches();
        }
    }

    /** For tests: the next {@link #init} registers its event sources on that test's application. */
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
     * {@link SpeechRecognizer#isRecognitionAvailable(Context)} (a PackageManager query), cached per
     * generation; a recogniser comes or goes with a package change, which drops the caches.
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

    /** Cached per generation; enabled IME or subtype changes drop the caches. */
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
        // Runtime subtypes for side-loaded packs method.xml does not declare. Own preference, so
        // custom_input_styles keeps falling back to the predefined_subtypes resource arrays.
        arrayList.addAll(Arrays.asList(SubtypeFactory.createSubtypesFromPref(
                SideloadedSubtypes.readRaw(defaultSharedPreferences))));
        return (InputMethodSubtype[]) arrayList.toArray(new InputMethodSubtype[arrayList.size()]);
    }

    public InputMethodManager getInputMethodManager() {
        checkInitialized();
        return this.imm;
    }

    /** @param allowsImplicitlySelectedSubtypes whether to include implicitly enabled subtypes */
    public List<InputMethodSubtype> getMyEnabledInputMethodSubtypeList(boolean allowsImplicitlySelectedSubtypes) {
        return getEnabledInputMethodSubtypeList(getInputMethodInfoOfThisIme(), allowsImplicitlySelectedSubtypes);
    }

    /**
     * This IME's enabled subtypes, uncached: callers run when the set may just have changed outside
     * this process. Empty (never a framework call) when this IME's InputMethodInfo is null.
     */
    public List<InputMethodSubtype> getEnabledSubtypesOfThisIme() {
        final InputMethodInfo thisIme = getInputMethodInfoOfThisIme();
        if (thisIme == null) {
            return Collections.emptyList();
        }
        return this.imm.getEnabledInputMethodSubtypeList(thisIme, true);
    }

    /**
     * Uses {@code InputMethodService#switchToNextInputMethod(boolean)} on API 28+, else the
     * deprecated IBinder-token path plus this class's own next-subtype/next-IME chain.
     * @param onlyCurrentIme true to rotate only within this IME
     */
    public boolean switchToNextInputMethod(InputMethodService ims, boolean onlyCurrentIme) {
        if (onlyCurrentIme && mustRotateWithinIme(this.imm.getCurrentInputMethodSubtype(), getEnabledSubtypesOfThisIme())) {
            // Current language no longer enabled: Android 15 then jumps to the most recent item,
            // possibly another IME even with onlyCurrentIme. Never ask the framework from here.
            return switchToNextSubtypeWithinThisIme(ims);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && ims.switchToNextInputMethod(onlyCurrentIme)) {
            return true;
        }
        if (onlyCurrentIme) {
            // The framework declined (nothing to rotate to); the token fallback below would hand
            // over to another IME, so stay here.
            return switchToNextSubtypeWithinThisIme(ims);
        }
        final IBinder token = windowToken(ims);
        if (token == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return switchToNextInputMethod(token, onlyCurrentIme);
        }
        return switchToNextInputSubtypeInThisIme(token, onlyCurrentIme) || switchToNextInputMethodAndSubtype(token);
    }

    /** Next enabled subtype of this IME (fresh list); never switches to another IME. */
    private boolean switchToNextSubtypeWithinThisIme(InputMethodService ims) {
        final List<InputMethodSubtype> enabled = getEnabledSubtypesOfThisIme();
        final InputMethodSubtype next = nextSubtypeWithinIme(this.imm.getCurrentInputMethodSubtype(), enabled);
        if (next == null) {
            return false;
        }
        setInputMethodAndSubtype(ims, next);
        return true;
    }

    /** True when the current subtype is unknown or not enabled, so the framework must not be asked. */
    static boolean mustRotateWithinIme(InputMethodSubtype current, List<InputMethodSubtype> enabled) {
        if (enabled == null || enabled.isEmpty()) {
            return false;
        }
        return current == null || getSubtypeIndexInList(current, enabled) == -1;
    }

    /**
     * On field focus, switches to the first enabled subtype when the current one was removed.
     * @return true when it switched
     */
    public boolean moveToEnabledSubtypeIfCurrentIsNot(InputMethodService ims) {
        // Both inputs only change through cache-dropping events, so a checked generation is done.
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

    /** Where a "switch language" press lands; null when there is nothing to switch to. */
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

    /** Token-free form; uses {@code InputMethodService#switchInputMethod} on API 28+. */
    public void setInputMethodAndSubtype(InputMethodService ims, InputMethodSubtype inputMethodSubtype) {
        // No id: not registered with the framework, nothing to switch to.
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

    /** @param onlyCurrentIme true to rotate only within this IME */
    @SuppressWarnings("deprecation")
    public boolean switchToNextInputMethod(IBinder iBinder, boolean onlyCurrentIme) {
        if (this.imm.switchToNextInputMethod(iBinder, onlyCurrentIme) || switchToNextInputSubtypeInThisIme(iBinder, onlyCurrentIme)) {
            return true;
        }
        return switchToNextInputMethodAndSubtype(iBinder);
    }

    /** @param onlyCurrentIme true to wrap from the last subtype back to the first */
    private boolean switchToNextInputSubtypeInThisIme(IBinder iBinder, boolean onlyCurrentIme) {
        InputMethodSubtype currentInputMethodSubtype = this.imm.getCurrentInputMethodSubtype();
        List<InputMethodSubtype> listM5877a = getMyEnabledInputMethodSubtypeList(true);
        int iM5869b = getSubtypeIndexInList(currentInputMethodSubtype, listM5877a);
        if (iM5869b == -1) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Can't find current subtype in enabled subtypes");
            return false;
        }
        int size = (iM5869b + 1) % listM5877a.size();
        if (size <= iM5869b && !onlyCurrentIme) {
            return false;
        }
        setInputMethodAndSubtype(iBinder, listM5877a.get(size));
        return true;
    }

    private boolean switchToNextInputMethodAndSubtype(IBinder iBinder) {
        InputMethodManager inputMethodManager = this.imm;
        List<InputMethodInfo> enabledInputMethodList = inputMethodManager.getEnabledInputMethodList();
        // Null when not registered with the framework (then the index is -1).
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

    /** @param currentIndex index of the current IME in {@code list} */
    private static InputMethodInfo getNextNonAuxiliaryIme(int currentIndex, List<InputMethodInfo> list) {
        int size = list.size();
        for (int i2 = 1; i2 < size; i2++) {
            InputMethodInfo inputMethodInfo = list.get((currentIndex + i2) % size);
            if (!isAuxiliaryIme(inputMethodInfo)) {
                return inputMethodInfo;
            }
        }
        return list.get(currentIndex);
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

        /** @param imePackageName this IME's package name */
        ImeInfoCache(String imePackageName) {
            this.imePackageName = imePackageName;
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
     * Null when not registered (installed, not enabled). Outside callers pass it to
     * {@code IntentUtils.getInputLanguageSelectionIntent()}, which then opens the unfiltered screen
     * the user needs; in-class callers guard it, as {@code InputMethodManager} rejects a null id.
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

    /** @param shouldIncludeAuxiliarySubtypes whether two auxiliary subtypes of one IME count as multiple */
    public boolean hasMultipleEnabledIMEsOrSubtypes(boolean shouldIncludeAuxiliarySubtypes) {
        return hasMultipleEnabledSubtypes(shouldIncludeAuxiliarySubtypes, this.imm.getEnabledInputMethodList());
    }

    /** @param shouldIncludeAuxiliarySubtypes whether two auxiliary subtypes count as multiple */
    public boolean hasMultipleEnabledSubtypesInThisIme(boolean shouldIncludeAuxiliarySubtypes) {
        return hasMultipleEnabledSubtypes(shouldIncludeAuxiliarySubtypes, Collections.singletonList(getInputMethodInfoOfThisIme()));
    }

    /** @param shouldIncludeAuxiliarySubtypes whether two auxiliary subtypes of one IME count as multiple */
    private boolean hasMultipleEnabledSubtypes(boolean shouldIncludeAuxiliarySubtypes, List<InputMethodInfo> list) {
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
                if (listM5864a.size() - i2 > 0 || (shouldIncludeAuxiliarySubtypes && i2 > 1)) {
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

    /**
     * @param localeString subtype locale
     * @param keyboardLayoutSetName keyboard layout set name
     */
    public InputMethodSubtype findSubtypeByLocaleAndKeyboardLayoutSet(String localeString, String keyboardLayoutSetName) {
        InputMethodInfo inputMethodInfoM5890d = getInputMethodInfoOfThisIme();
        if (inputMethodInfoM5890d == null) {
            return null;
        }
        int subtypeCount = inputMethodInfoM5890d.getSubtypeCount();
        for (int i = 0; i < subtypeCount; i++) {
            InputMethodSubtype subtypeAt = inputMethodInfoM5890d.getSubtypeAt(i);
            String strM5628e = ResourceLocaleUtils.getKeyboardLayoutSetName(subtypeAt);
            if (localeString.equals(subtypeAt.getLocale()) && keyboardLayoutSetName.equals(strM5628e)) {
                return subtypeAt;
            }
        }
        return null;
    }

    public void setInputMethodAndSubtype(IBinder iBinder, InputMethodSubtype inputMethodSubtype) {
        // No id: not registered with the framework.
        final String thisImeId = getInputMethodIdOfThisIme();
        if (thisImeId == null) {
            return;
        }
        this.imm.setInputMethodAndSubtype(iBinder, thisImeId, inputMethodSubtype);
    }

    public void setAdditionalInputMethodSubtypes(InputMethodSubtype[] inputMethodSubtypes) {
        InputMethodInfo inputMethodInfoM5890d = getInputMethodInfoOfThisIme();
        if (inputMethodInfoM5890d != null) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Registering " + inputMethodSubtypes.length + " additional subtypes");
            for (InputMethodSubtype subtype : inputMethodSubtypes) {
                if (BuildConfig.DEBUG) {
                Log.d(TAG, "  Registering subtype: locale=" + subtype.getLocale()
                    + " extraValue=" + subtype.getExtraValue()
                    + " hashCode=" + subtype.hashCode());
                }
            }
            this.imm.setAdditionalInputMethodSubtypes(inputMethodInfoM5890d.getId(), inputMethodSubtypes);
        }
        clearSubtypeCaches();
        verifySubtypeRegistration(inputMethodSubtypes);
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
     * Never forwards a null {@code inputMethodInfo} (not enabled yet, or mid-update): the framework
     * reads null as "the currently selected IME", i.e. another keyboard's subtypes.
     * @param allowsImplicitlySelectedSubtypes whether to include implicitly enabled subtypes
     */
    private List<InputMethodSubtype> getEnabledInputMethodSubtypeList(InputMethodInfo inputMethodInfo, boolean allowsImplicitlySelectedSubtypes) {
        if (inputMethodInfo == null) {
            Logger.warn(TAG, "getEnabledInputMethodSubtypeList() with no InputMethodInfo for this IME"
                    + " (not enabled yet, or mid-update); reporting no subtypes.");
            return Collections.emptyList();
        }
        HashMap<InputMethodInfo, List<InputMethodSubtype>> map = allowsImplicitlySelectedSubtypes ? this.subtypeListCacheWithImplicit : this.subtypeListCacheWithoutImplicit;
        List<InputMethodSubtype> list = map.get(inputMethodInfo);
        if (list != null) {
            return list;
        }
        List<InputMethodSubtype> enabledInputMethodSubtypeList = this.imm.getEnabledInputMethodSubtypeList(inputMethodInfo, allowsImplicitlySelectedSubtypes);
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

    /** Drops every cache (see the invalidation table at the top); readers refill lazily. */
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

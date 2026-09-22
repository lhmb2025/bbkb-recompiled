package dev.bbkb.ime.core.locale;

import android.content.Context;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.IBinder;
import android.view.Window;
import dev.bbkb.ime.core.settings.PrefsManager;
import android.util.Log;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;

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
        if (HardwareProbe.isDalvikVM()) {
            setAdditionalInputMethodSubtypes(getAdditionalSubtypes(context));
        }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && ims.switchToNextInputMethod(z)) {
            return true;
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

    public void clearSubtypeCaches() {
        this.subtypeListCacheWithImplicit.clear();
        this.subtypeListCacheWithoutImplicit.clear();
        this.imeInfoCache.clear();
    }

    public boolean shouldOfferSwitchingToNextInputMethod(IBinder iBinder) {
        return this.imm.shouldOfferSwitchingToNextInputMethod(iBinder);
    }

}

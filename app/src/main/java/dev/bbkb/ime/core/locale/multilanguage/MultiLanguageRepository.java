package dev.bbkb.ime.core.locale.multilanguage;

import android.content.Context;
import android.content.SharedPreferences;
import dev.bbkb.ime.core.settings.PrefsManager;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodSubtype;

import dev.bbkb.ime.compat.InputMethodSubtypeCompat;
import dev.bbkb.ime.core.locale.RichInputMethodManager;
import dev.bbkb.ime.core.subtypeswitcher.SubtypeFactory;
import dev.bbkb.ime.core.locale.ResourceLocaleUtils;
import dev.bbkb.ime.core.shared.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;



public class MultiLanguageRepository {

    private static final String TAG = "MultiLanguageRepository";

    private static MultiLanguageRepository sInstance;

    private final Context context;

    private final TreeMap<LocaleItem, String> localeToLayoutSet = new TreeMap<>(LocaleItem.DISPLAY_NAME_COMPARATOR);

    private final TreeSet<MultiLanguageConfig> configs = new TreeSet<>();

    MultiLanguageRepository(Context context, InputMethodInfo inputMethodInfo) {
        // Use applicationContext to prevent memory leaks in static singleton
        this.context = context.getApplicationContext();
        loadAvailableLocales(inputMethodInfo);
    }

    /**
     * Defect 1: {@link RichInputMethodManager#getInputMethodInfoOfThisIme()} returns null while
     * this IME is installed but not yet enabled in system settings, and again briefly during a
     * package update. The repository copes (see {@link #loadAvailableLocales}), but an instance
     * built in that state has an empty locale list forever, so it is deliberately NOT memoised:
     * the next caller retries, and picks up the real subtype list once the framework knows us.
     * Only the fully-populated instance is cached.
     */
    public static MultiLanguageRepository getInstance(Context context) {
        if (sInstance == null) {
            RichInputMethodManager.init(context);
            final InputMethodInfo thisIme = RichInputMethodManager.getInstance().getInputMethodInfoOfThisIme();
            final MultiLanguageRepository repository = new MultiLanguageRepository(context, thisIme);
            if (thisIme == null) {
                return repository;
            }
            sInstance = repository;
        }
        return sInstance;
    }

    public static void clearInstance() {
        sInstance = null;
    }

    private void loadAvailableLocales(InputMethodInfo inputMethodInfo) {
        // Defect 1: null when the framework has no InputMethodInfo for us — installed but not yet
        // enabled in system settings, or mid package-update. This used to NPE and take out both
        // MultiLanguageWizardScreen and whatever else was on the way to it. An IME the framework
        // does not know about has no subtypes, so the honest answer is an empty locale list.
        if (inputMethodInfo == null) {
            Logger.warn(TAG, "No InputMethodInfo for this IME (not enabled yet, or mid-update);"
                    + " no multi-language locales are available.");
            return;
        }
        int subtypeCount = inputMethodInfo.getSubtypeCount();
        for (int i = 0; i < subtypeCount; i++) {
            InputMethodSubtype subtypeAt = inputMethodInfo.getSubtypeAt(i);
            if (InputMethodSubtypeCompat.isAsciiCapable(subtypeAt)) {
                String locale = subtypeAt.getLocale();
                if (!locale.equals("zz") && !SubtypeFactory.isAdditionalSubtype(subtypeAt)) {
                    this.localeToLayoutSet.put(new LocaleItem(locale), ResourceLocaleUtils.getKeyboardLayoutSetName(subtypeAt));
                }
            }
        }
    }

    public String getLayoutSetFor(LocaleItem c0711f) {
        return this.localeToLayoutSet.get(c0711f);
    }

    public ArrayList<LocaleItem> getAvailableLocales() {
        return new ArrayList<>(this.localeToLayoutSet.keySet());
    }

    /**
     * The primary language a new multi-language keyboard starts on: the system language when it
     * is one of {@link #getAvailableLocales()}, else another keyboard for the same language, else
     * the first in the list. Null only when nothing is available.
     *
     * <p>The Compose wizard used to start on {@code Locale.getDefault()} unconditionally, so with
     * a Korean system language it showed "Korean" as primary although only Latin-script languages
     * can be one, and saved a keyboard with no layout that typed English. The original app's
     * spinner only pre-selected the system language when the list held it.
     */
    public LocaleItem getDefaultPrimaryLocale(String systemLocale) {
        return pickDefaultPrimaryLocale(getAvailableLocales(), systemLocale);
    }

    static LocaleItem pickDefaultPrimaryLocale(List<LocaleItem> available, String systemLocale) {
        if (available.isEmpty()) {
            return null;
        }
        final String language = systemLocale.split("_")[0];
        LocaleItem sameLanguage = null;
        for (LocaleItem item : available) {
            final String locale = (String) item.first;
            if (locale.equals(systemLocale)) {
                return item;
            }
            if (sameLanguage == null && locale.split("_")[0].equals(language)) {
                sameLanguage = item;
            }
        }
        return sameLanguage != null ? sameLanguage : available.get(0);
    }

    /**
     * Re-reads the persisted list. {@code configs} used to be filled only by this instance's own
     * mutations, so on a fresh process the first addConfig/removeConfig saved a list holding just
     * that one entry, wiping every stored config (the original APK had the same gap). Reloading on
     * every read and mutation also picks up CombineLanguages, which appends to the pref directly.
     * The stored format is read as-is by MultiLanguageUtils.loadConfigs; unparseable entries
     * (null) are skipped because TreeSet cannot hold them.
     */
    private void reloadConfigs(SharedPreferences prefs) {
        this.configs.clear();
        for (MultiLanguageConfig config : MultiLanguageUtils.loadConfigs(prefs)) {
            if (config != null) {
                this.configs.add(config);
            }
        }
    }

    public ArrayList<MultiLanguageConfig> getConfigs() {
        reloadConfigs(PrefsManager.INSTANCE.getPrefs(this.context));
        return new ArrayList<>(this.configs);
    }

    public boolean addConfig(MultiLanguageConfig c0710e) {
        if (c0710e == null) {
            return false;
        }
        SharedPreferences defaultSharedPreferences = PrefsManager.INSTANCE.getPrefs(this.context);
        reloadConfigs(defaultSharedPreferences);
        boolean zAdd = this.configs.add(c0710e);
        MultiLanguageUtils.saveConfigs(defaultSharedPreferences, this.configs);
        return zAdd;
    }

    public boolean removeConfig(MultiLanguageConfig c0710e) {
        if (c0710e == null) {
            return false;
        }
        SharedPreferences defaultSharedPreferences = PrefsManager.INSTANCE.getPrefs(this.context);
        reloadConfigs(defaultSharedPreferences);
        boolean zRemove = this.configs.remove(c0710e);
        MultiLanguageUtils.saveConfigs(defaultSharedPreferences, this.configs);
        return zRemove;
    }
}

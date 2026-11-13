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
import dev.bbkb.ime.core.shared.ScriptUtils;
import dev.bbkb.ime.core.locale.LocaleUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;



public class MultiLanguageRepository {

    private static final String TAG = "MultiLanguageRepository";

    private static MultiLanguageRepository sInstance;

    private final Context context;

    private final TreeMap<LocaleItem, String> localeToLayoutSet = new TreeMap<>(LocaleItem.DISPLAY_NAME_COMPARATOR);

    /** The Latin-script subset, listed as primaries. */
    private final TreeSet<LocaleItem> asciiLocales = new TreeSet<>(LocaleItem.DISPLAY_NAME_COMPARATOR);

    private final TreeSet<MultiLanguageConfig> configs = new TreeSet<>();

    MultiLanguageRepository(Context context, InputMethodInfo inputMethodInfo) {
        this.context = context.getApplicationContext();
        loadAvailableLocales(inputMethodInfo);
    }

    /**
     * {@link RichInputMethodManager#getInputMethodInfoOfThisIme()} is null while this IME is not yet
     * enabled or mid package-update; an instance built then has no locales, so it is not cached
     * and the next caller retries.
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
        // Null when not yet enabled or mid package-update: no subtypes, so an empty locale list.
        if (inputMethodInfo == null) {
            Logger.warn(TAG, "No InputMethodInfo for this IME (not enabled yet, or mid-update);"
                    + " no multi-language locales are available.");
            return;
        }
        int subtypeCount = inputMethodInfo.getSubtypeCount();
        for (int i = 0; i < subtypeCount; i++) {
            InputMethodSubtype subtypeAt = inputMethodInfo.getSubtypeAt(i);
            String locale = subtypeAt.getLocale();
            if (locale.equals("zz") || SubtypeFactory.isAdditionalSubtype(subtypeAt)) {
                continue;
            }
            // Every declared keyboard is listed; which can share a keyboard is decided by script (canCombine).
            this.localeToLayoutSet.put(new LocaleItem(locale), ResourceLocaleUtils.getKeyboardLayoutSetName(subtypeAt));
            if (InputMethodSubtypeCompat.isAsciiCapable(subtypeAt)) {
                this.asciiLocales.add(new LocaleItem(locale));
            }
        }
    }

    /** @param localeItem the keyboard language */
    public String getLayoutSetFor(LocaleItem localeItem) {
        return this.localeToLayoutSet.get(localeItem);
    }

    /** The Latin-script keyboards, the only valid primaries. Extras come from {@link #getAvailableLocalesFor}. */
    public ArrayList<LocaleItem> getAvailableLocales() {
        return new ArrayList<>(this.asciiLocales);
    }

    /** Every declared keyboard language that can be an extra prediction language of {@code primaryLocale}: same script, not itself. */
    public ArrayList<LocaleItem> getAvailableLocalesFor(String primaryLocale) {
        ArrayList<LocaleItem> out = new ArrayList<>();
        for (LocaleItem item : this.localeToLayoutSet.keySet()) {
            if (!item.first.equals(primaryLocale) && canCombine(primaryLocale, item.first)) {
                out.add(item);
            }
        }
        return out;
    }

    /**
     * Whether two keyboard languages can share one keyboard: the same script, so one layout types
     * both. Latin covers Vietnamese (script 21, Latin with tone marks). Chinese, Japanese and
     * Korean are excluded on both sides: their candidates come from a converter or the CJK strip,
     * one language at a time.
     */
    public static boolean canCombine(String primaryLocale, String extraLocale) {
        int a = scriptOf(primaryLocale);
        int b = scriptOf(extraLocale);
        if (a == ScriptUtils.SCRIPT_UNKNOWN || b == ScriptUtils.SCRIPT_UNKNOWN) {
            return false;
        }
        if (isCjk(a) || isCjk(b)) {
            return false;
        }
        return a == b;
    }

    /** True for a language typed on Latin keys, which decides a combined keyboard's AsciiCapable flag. */
    public static boolean isLatinScript(String locale) {
        return scriptOf(locale) == 14;
    }

    private static boolean isCjk(int script) {
        return script == 7 || script == 8 || script == 10;
    }

    private static int scriptOf(String locale) {
        if (locale == null || locale.isEmpty()) {
            return ScriptUtils.SCRIPT_UNKNOWN;
        }
        int script = ScriptUtils.getScriptFromLocale(LocaleUtils.constructLocaleFromString(locale));
        return script == 21 ? 14 : script;
    }

    /**
     * The primary language a new multi-language keyboard starts on: the system language when it
     * is one of {@link #getAvailableLocales()}, else another keyboard for the same language, else
     * the first in the list. Null only when nothing is available.
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
     * Re-reads the persisted list before every read and mutation, so a save never drops stored
     * configs this instance has not seen. Unparseable (null) entries are skipped: TreeSet cannot hold them.
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

    /** @param multiLanguageConfig the keyboard config to add */
    public boolean addConfig(MultiLanguageConfig multiLanguageConfig) {
        if (multiLanguageConfig == null) {
            return false;
        }
        SharedPreferences defaultSharedPreferences = PrefsManager.INSTANCE.getPrefs(this.context);
        reloadConfigs(defaultSharedPreferences);
        boolean zAdd = this.configs.add(multiLanguageConfig);
        MultiLanguageUtils.saveConfigs(defaultSharedPreferences, this.configs);
        return zAdd;
    }

    /** @param multiLanguageConfig the keyboard config to remove */
    public boolean removeConfig(MultiLanguageConfig multiLanguageConfig) {
        if (multiLanguageConfig == null) {
            return false;
        }
        SharedPreferences defaultSharedPreferences = PrefsManager.INSTANCE.getPrefs(this.context);
        reloadConfigs(defaultSharedPreferences);
        boolean zRemove = this.configs.remove(multiLanguageConfig);
        MultiLanguageUtils.saveConfigs(defaultSharedPreferences, this.configs);
        return zRemove;
    }
}

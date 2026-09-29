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

    /** The Latin-script subset, which the old wizard and dialog still list as primaries. */
    private final TreeSet<LocaleItem> asciiLocales = new TreeSet<>(LocaleItem.DISPLAY_NAME_COMPARATOR);

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
            String locale = subtypeAt.getLocale();
            if (locale.equals("zz") || SubtypeFactory.isAdditionalSubtype(subtypeAt)) {
                continue;
            }
            // Every declared keyboard is known here. Which of them can share a keyboard is decided
            // per primary language by script (see canCombine): until 2026-09-28 only ASCII-capable
            // subtypes were listed, so Russian could not add Ukrainian nor Hindi Marathi, though
            // they share a layout.
            this.localeToLayoutSet.put(new LocaleItem(locale), ResourceLocaleUtils.getKeyboardLayoutSetName(subtypeAt));
            if (InputMethodSubtypeCompat.isAsciiCapable(subtypeAt)) {
                this.asciiLocales.add(new LocaleItem(locale));
            }
        }
    }

    public String getLayoutSetFor(LocaleItem c0711f) {
        return this.localeToLayoutSet.get(c0711f);
    }

    /** The Latin-script keyboards, as the old wizard lists them. Extras for a given keyboard come from {@link #getAvailableLocalesFor}. */
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
        int script = ScriptUtils.getScript(LocaleUtils.constructLocaleFromString(locale).getLanguage());
        return script == 21 ? 14 : script;
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
     * every read and mutation also picks up any code that appends to the pref directly.
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

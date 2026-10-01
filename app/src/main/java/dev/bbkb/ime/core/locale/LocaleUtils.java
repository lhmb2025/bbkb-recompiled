package dev.bbkb.ime.core.locale;

import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.text.TextUtils;

import dev.bbkb.ime.core.settings.util.SettingsManager;
import com.blackberry.nuanceshim.NuanceSDK;

import java.util.HashMap;
import java.util.Locale;



public final class LocaleUtils {

    private static final HashMap<String, Locale> sLocaleCache = new HashMap<>();

    /**
     * The app's spelling of {@code locale}'s language code.
     *
     * <p>Everything this app keys by language uses the codes Java returned for twenty years:
     * {@code res/xml/method.xml}, both language-pack catalogs, the installed-pack directories, the
     * script and right-to-left tables, the keyboard XML {@code languageCode} cases, and the engine's
     * own language table, which knows {@code in} and {@code iw} and nothing else. Since Java 17, and
     * on Android 15+ for apps targeting API 35 (this app does), {@link Locale} no longer converts
     * to those codes: {@code new Locale("in").getLanguage()} is {@code "id"}, Hebrew is {@code "he"}
     * and Yiddish {@code "yi"}. So on Android 15 an Indonesian or Hebrew keyboard missed every
     * table: no dictionary offer, no engine language, no right-to-left. Use this wherever a
     * language code taken from a Locale is compared with, or handed to, the app's own tables or the
     * engine. It is the identity for every other language, and on every Android version that still
     * returns the old codes; the JVM tests run on a modern JDK, so they see the Android 15 form.
     */
    public static String languageCode(Locale locale) {
        final String language = locale.getLanguage();
        switch (language) {
            case "id":
                return "in";
            case "he":
                return "iw";
            case "yi":
                return "ji";
            default:
                return language;
        }
    }

    /**
     * {@link Locale#toString()} with the language in the app's spelling ({@link #languageCode}):
     * {@code in_ID}, {@code zh_TW_stroke}, {@code iw}. The form the keyboard text tables and the
     * engine's layout sync are keyed by.
     */
    public static String localeString(Locale locale) {
        final String country = locale.getCountry();
        final String variant = locale.getVariant();
        final StringBuilder sb = new StringBuilder(languageCode(locale));
        if (!country.isEmpty() || !variant.isEmpty()) {
            sb.append('_').append(country);
        }
        if (!variant.isEmpty()) {
            sb.append('_').append(variant);
        }
        return sb.toString();
    }

    public static Locale constructLocaleFromString(String str) {
        if (str == null) {
            return null;
        }
        synchronized (sLocaleCache) {
            Locale locale = sLocaleCache.get(str);
            if (locale != null) {
                return locale;
            }
            String[] strArrSplit = str.split("_", 3);
            if (strArrSplit.length == 1 && strArrSplit[0].equals(str)) {
                strArrSplit = str.split("-", 3);
            }
            if (strArrSplit.length >= 1 && "tl".equals(strArrSplit[0])) {
                strArrSplit[0] = "fil";
            }
            if (strArrSplit.length == 1) {
                locale = new Locale(strArrSplit[0]);
            } else if (strArrSplit.length == 2) {
                locale = new Locale(strArrSplit[0], strArrSplit[1]);
            } else if (strArrSplit.length == 3) {
                locale = new Locale(strArrSplit[0], strArrSplit[1], strArrSplit[2]);
            }
            if (locale != null) {
                sLocaleCache.put(str, locale);
            }
            return locale;
        }
    }

    public static boolean isNoLanguage(Locale locale) {
        return locale != null && locale.getLanguage().equals("zz");
    }

    public static boolean isChinese(Locale locale) {
        return locale != null && locale.getLanguage().equals(NuanceSDK.CHINESE_LOCALE);
    }

    public static boolean isCurrentSubtypeChinese() {
        return isChinese(SubtypeManager.getInstance().getCurrentSubtypeLocale());
    }

    public static boolean isChinesePinyin(Locale locale) {
        return locale != null && isChinese(locale) && locale.getVariant().equals("pinyin");
    }

    public static boolean isCurrentSubtypePinyin() {
        return isChinesePinyin(SubtypeManager.getInstance().getCurrentSubtypeLocale());
    }

    public static boolean isChineseStroke(Locale locale) {
        return locale != null && isChinese(locale) && locale.getVariant().equals("stroke");
    }

    public static boolean isChineseZhuyin(Locale locale) {
        return locale != null && isChinese(locale) && locale.getVariant().equals("zhuyin");
    }

    public static boolean isChineseCangjie(Locale locale) {
        return locale != null && isChinese(locale) && locale.getVariant().equals(NuanceSDK.CANGJIE_VARIANT);
    }

    public static boolean isCurrentSubtypeCangjie() {
        return isChineseCangjie(SubtypeManager.getInstance().getCurrentSubtypeLocale());
    }

    public static boolean isChineseOrJapanese(Locale locale) {
        return locale != null && (locale.getLanguage().equals(NuanceSDK.CHINESE_LOCALE) || locale.getLanguage().equals("ja"));
    }

    public static boolean isCurrentSubtypeJapanese() {
        return isJapanese(SubtypeManager.getInstance().getCurrentSubtypeLocale());
    }

    public static boolean isJapanese(Locale locale) {
        return locale != null && locale.getLanguage().equals("ja");
    }

    public static boolean isCurrentSubtypeKorean() {
        return isKorean(SubtypeManager.getInstance().getCurrentSubtypeLocale());
    }

    private static boolean isKorean(Locale locale) {
        return locale != null && locale.getLanguage().equals("ko");
    }

    public static boolean isCurrentSubtypeHindi() {
        return isHindi(SubtypeManager.getInstance().getCurrentSubtypeLocale());
    }

    private static boolean isHindi(Locale locale) {
        return locale != null && locale.getLanguage().equals("hi");
    }

    public static boolean isCurrentSubtypeNonCanadianFrench() {
        return isNonCanadianFrench(SubtypeManager.getInstance().getCurrentSubtypeLocale());
    }

    private static boolean isNonCanadianFrench(Locale locale) {
        return (locale == null || !TextUtils.equals(locale.getLanguage(), "fr") || TextUtils.equals(locale.getCountry(), "CA")) ? false : true;
    }

    public static String toNuanceLocaleString(Locale locale) {
        if (isChineseCangjie(locale)) {
            return SettingsManager.getInstance().getSettingsValues().cangjieMode == 0 ? NuanceSDK.CANGJIE_VARIANT : NuanceSDK.QUICK_CANGJIE_VARIANT;
        }
        // The keyboard-layout sync (KeyboardSwitcher) needs the full locale string: the original
        // getVariant() is empty for every non-Chinese locale. The engine's setInputMethod call is
        // the one place that wants the variant, and it goes through toNuanceInputMethodName.
        // The engine spells Indonesian and Hebrew the old way, so not Locale.toString() directly.
        return localeString(locale);
    }

    /**
     * The input-method name the engine's {@code setInputMethod} expects for a Chinese subtype: the
     * locale VARIANT ("pinyin", "stroke", "zhuyin"), or the Cangjie mode the user chose. The
     * original app passed {@code locale.getVariant()}; when {@link #toNuanceLocaleString} was
     * changed to return the full locale string for the layout sync, this call went with it and the
     * engine silently stayed in its default Pinyin mode, so stroke and Zhuyin keyboards produced no
     * candidates on either Chinese pack (KEY2, 2026-09-28).
     */
    public static String toNuanceInputMethodName(Locale locale) {
        if (isChineseCangjie(locale)) {
            return SettingsManager.getInstance().getSettingsValues().cangjieMode == 0 ? NuanceSDK.CANGJIE_VARIANT : NuanceSDK.QUICK_CANGJIE_VARIANT;
        }
        return locale.getVariant();
    }

    /**
     * Gets the primary locale from a Configuration with proper API version handling.
     * Uses getLocales().get(0) on API 24+ and falls back to the deprecated locale field on older APIs.
     * @param configuration The Configuration to get locale from
     * @return The primary locale
     */
    @SuppressWarnings("deprecation")
    public static Locale getConfigurationLocale(Configuration configuration) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return configuration.getLocales().get(0);
        } else {
            return configuration.locale;
        }
    }

    /**
     * Gets the primary locale from Resources with proper API version handling.
     * @param resources The Resources to get locale from
     * @return The primary locale
     */
    public static Locale getConfigurationLocale(Resources resources) {
        return getConfigurationLocale(resources.getConfiguration());
    }
}

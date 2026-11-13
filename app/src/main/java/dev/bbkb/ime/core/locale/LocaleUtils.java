package dev.bbkb.ime.core.locale;

import android.content.res.Configuration;
import android.content.res.Resources;
import androidx.core.os.ConfigurationCompat;
import android.text.TextUtils;

import dev.bbkb.ime.core.settings.util.SettingsManager;
import com.blackberry.nuanceshim.NuanceSDK;

import java.util.HashMap;
import java.util.Locale;



public final class LocaleUtils {

    private static final HashMap<String, Locale> sLocaleCache = new HashMap<>();

    /**
     * The app's spelling of the language code: the old ISO codes ({@code in}, {@code iw},
     * {@code ji}) that method.xml, pack catalogs and directories, script/RTL tables and the engine
     * use. Android 15+ with targetSdk 35 (and Java 17) return {@code id}/{@code he}/{@code yi}.
     * Use it wherever a Locale's language meets the app's tables or the engine.
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

    /** {@link Locale#toString()} in the app's spelling ({@code in_ID}, {@code zh_TW_stroke}), as the text tables and layout sync key it. */
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

    /** @param localeString locale string, {@code _} or {@code -} separated; {@code tl} becomes {@code fil} */
    public static Locale constructLocaleFromString(String localeString) {
        if (localeString == null) {
            return null;
        }
        synchronized (sLocaleCache) {
            Locale locale = sLocaleCache.get(localeString);
            if (locale != null) {
                return locale;
            }
            String[] strArrSplit = localeString.split("_", 3);
            if (strArrSplit.length == 1 && strArrSplit[0].equals(localeString)) {
                strArrSplit = localeString.split("-", 3);
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
                sLocaleCache.put(localeString, locale);
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
        // The layout sync needs the full locale string (old ISO spelling); the engine's
        // setInputMethod wants the variant and uses toNuanceInputMethodName.
        return localeString(locale);
    }

    /**
     * For the engine's {@code setInputMethod}: the locale variant ("pinyin", "stroke", "zhuyin") or
     * the chosen Cangjie mode. Not the full locale string, or the engine stays in Pinyin.
     */
    public static String toNuanceInputMethodName(Locale locale) {
        if (isChineseCangjie(locale)) {
            return SettingsManager.getInstance().getSettingsValues().cangjieMode == 0 ? NuanceSDK.CANGJIE_VARIANT : NuanceSDK.QUICK_CANGJIE_VARIANT;
        }
        return locale.getVariant();
    }

    /** The first of the configuration's locale list. */
    public static Locale getConfigurationLocale(Configuration configuration) {
        return ConfigurationCompat.getLocales(configuration).get(0);
    }

    public static Locale getConfigurationLocale(Resources resources) {
        return getConfigurationLocale(resources.getConfiguration());
    }
}

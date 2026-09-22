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
        // FIX: was locale.getVariant() which is empty for most locales (e.g. "en_US" variant is "").
        // NuanceSDK needs a valid locale string for dictionary loading and gesture recognition.
        return locale.toString();
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

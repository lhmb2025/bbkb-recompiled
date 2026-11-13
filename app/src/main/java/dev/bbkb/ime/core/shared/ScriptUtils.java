package dev.bbkb.ime.core.shared;

import com.blackberry.nuanceshim.NuanceSDK;
import dev.bbkb.ime.core.locale.LocaleUtils;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;



public final class ScriptUtils {
    /** Returned for an unlisted language; consumers treat it as "accept anything". */
    public static final int SCRIPT_UNKNOWN = -1;

    /** Language tag to script code. Read from the main and suggestion threads, so never mutate after init. */
    private static final Map<String, Integer> SCRIPT_MAP;

    static {
        final TreeMap<String, Integer> map = new TreeMap<>();
        map.put("af", 14);
        map.put("ar", 0);
        map.put("az", 14);
        map.put("be", 3);
        map.put("bg", 3);
        map.put("bn", 2);
        map.put("bs", 14);
        map.put("ca", 14);
        map.put("cs", 14);
        map.put("cy", 14);
        map.put("da", 14);
        map.put("de", 14);
        map.put("el", 6);
        map.put("en", 14);
        map.put("es", 14);
        map.put("et", 14);
        map.put("eu", 14);
        map.put("fa", 0);
        map.put("fi", 14);
        map.put("fil", 14);
        map.put("fr", 14);
        map.put("ga", 14);
        map.put("gl", 14);
        map.put("hi", 4);
        map.put("hr", 14);
        map.put("hu", 14);
        map.put("hy", 1);
        map.put("in", 14);
        map.put("is", 14);
        map.put("it", 14);
        map.put("iw", 9);
        map.put("ja", 10);
        map.put("jv", 14);
        map.put("ka", 5);
        map.put("kk", 3);
        map.put("km", 12);
        map.put("kn", 11);
        map.put("ko", 8);
        map.put("ky", 3);
        map.put("lo", 13);
        map.put("lt", 14);
        map.put("lv", 14);
        map.put("mk", 3);
        map.put("ml", 15);
        map.put("mn", 3);
        map.put("mr", 4);
        map.put("ms", 14);
        map.put("my", 16);
        map.put("nb", 14);
        map.put("nl", 14);
        map.put("pl", 14);
        map.put("pt", 14);
        map.put("ro", 14);
        map.put("ru", 3);
        map.put("si", 17);
        map.put("sk", 14);
        map.put("sl", 14);
        map.put("sq", 14);
        map.put("sr", 14);
        map.put("su", 14);
        map.put("sv", 14);
        map.put("sw", 14);
        map.put("ta", 18);
        map.put("te", 19);
        map.put("th", 20);
        map.put("tr", 14);
        map.put("uk", 3);
        map.put("ur", 0);
        map.put("uz", 14);
        map.put("vi", 21);
        // Runtime-only subtypes from installed packs (SideloadedSubtypes).
        map.put("tg", 3);
        map.put("tt", 3);
        for (String latin : new String[] {"ha", "ig", "ku", "ln", "mg", "st", "tk", "xh", "yo", "zu"}) {
            map.put(latin, 14);
        }
        map.put(NuanceSDK.CHINESE_LOCALE, 7);
        // Ids continue attrs.xml's supportedScript enum after 21 = Vietnamese.
        map.put("gu", 22);   // Gujarati
        map.put("pa", 23);   // Punjabi, Gurmukhi
        map.put("or", 24);   // Odia
        map.put("am", 25);   // Amharic, Ethiopic
        map.put("bo", 26);   // Tibetan
        SCRIPT_MAP = Collections.unmodifiableMap(map);
    }

    /** @return the script code for {@code language}, or {@link #SCRIPT_UNKNOWN} if it is not listed. */
    public static int getScript(String language) {
        final Integer script = SCRIPT_MAP.get(language);
        return script == null ? SCRIPT_UNKNOWN : script.intValue();
    }

    /**
     * @param codePoint code point
     * @param scriptId script code from {@link #getScript}
     */
    public static boolean isLetterPartOfScript(int codePoint, int scriptId) {
        switch (scriptId) {
            case -1:
                return true;
            case 0:
                return (codePoint >= 1536 && codePoint <= 1791) || (codePoint >= 1872 && codePoint <= 1983) || ((codePoint >= 2208 && codePoint <= 2303) || ((codePoint >= 64336 && codePoint <= 65023) || (codePoint >= 65136 && codePoint <= 65279)));
            case 1:
                return (codePoint >= 1328 && codePoint <= 1423) || (codePoint >= 64275 && codePoint <= 64279);
            case 2:
                return codePoint >= 2432 && codePoint <= 2559;
            case 3:
                return codePoint >= 1024 && codePoint <= 1327 && Character.isLetter(codePoint);
            case 4:
                return codePoint >= 2304 && codePoint <= 2431;
            case 5:
                return (codePoint >= 4256 && codePoint <= 4351) || (codePoint >= 11520 && codePoint <= 11567);
            case 6:
                return (codePoint >= 880 && codePoint <= 1023) || (codePoint >= 7936 && codePoint <= 8191) || codePoint == 242;
            case 7:
                return (codePoint >= 19968 && codePoint <= 40959) || (codePoint >= 12544 && codePoint <= 12591);
            case 8:
                return (codePoint >= 44032 && codePoint <= 55203) || (codePoint >= 4352 && codePoint <= 4607) || ((codePoint >= 12592 && codePoint <= 12687) || ((codePoint >= 43360 && codePoint <= 43391) || (codePoint >= 55216 && codePoint <= 55295)));
            case 9:
                return (codePoint >= 1424 && codePoint <= 1535) || (codePoint >= 64285 && codePoint <= 64335);
            case 10:
                return (codePoint <= 687 && Character.isLetter(codePoint)) || (codePoint >= 12352 && codePoint <= 12447) || ((codePoint >= 12448 && codePoint <= 12543) || ((codePoint >= 12784 && codePoint <= 12799) || ((codePoint >= 12800 && codePoint <= 13055) || ((codePoint >= 13056 && codePoint <= 13311) || ((codePoint >= 65280 && codePoint <= 65519) || ((codePoint >= 110592 && codePoint <= 110847) || ((codePoint >= 127488 && codePoint <= 127743) || (codePoint >= 12288 && codePoint <= 12351))))))));
            case 11:
                return codePoint >= 3200 && codePoint <= 3327;
            case 12:
                return (codePoint >= 6016 && codePoint <= 6143) || (codePoint >= 6624 && codePoint <= 6655);
            case 13:
                return codePoint >= 3712 && codePoint <= 3839;
            case 14:
                return codePoint <= 687 && Character.isLetter(codePoint);
            case 15:
                return codePoint >= 3328 && codePoint <= 3455;
            case 16:
                return (codePoint >= 4096 && codePoint <= 4255) || (codePoint >= 43616 && codePoint <= 43647) || (codePoint >= 43488 && codePoint <= 43519);
            case 17:
                return codePoint >= 3456 && codePoint <= 3583;
            case 18:
                return codePoint >= 2944 && codePoint <= 3071;
            case 19:
                return codePoint >= 3072 && codePoint <= 3199;
            case 20:
                return codePoint >= 3584 && codePoint <= 3711;
            case 21:
                return (codePoint <= 687 && Character.isLetter(codePoint)) || codePoint == 769 || codePoint == 768 || codePoint == 777 || codePoint == 771 || codePoint == 803;
            case 22:
                return codePoint >= 2688 && codePoint <= 2815;                                   // Gujarati
            case 23:
                return codePoint >= 2560 && codePoint <= 2687;                                   // Gurmukhi
            case 24:
                return codePoint >= 2816 && codePoint <= 2943;                                   // Oriya
            case 25:
                return (codePoint >= 4608 && codePoint <= 5023) || (codePoint >= 11648 && codePoint <= 11743);  // Ethiopic + supplement + extended
            case 26:
                return codePoint >= 3840 && codePoint <= 4095;                                   // Tibetan
            default:
                throw new RuntimeException("Impossible value of script: " + scriptId);
        }
    }

    /** @return the script code for {@code locale}'s language, or {@link #SCRIPT_UNKNOWN} if unlisted (never throws). */
    public static int getScriptFromLocale(Locale locale) {
        return getScript(LocaleUtils.languageCode(locale));
    }

    public static boolean isUnsupportedScript(String language) {
        Integer num;
        return language == null || language.isEmpty() || (num = SCRIPT_MAP.get(language)) == null || num.intValue() == 14 || num.intValue() == 3 || num.intValue() == 1 || num.intValue() == 6 || num.intValue() == 21;
    }
}

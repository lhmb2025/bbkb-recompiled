package dev.bbkb.ime.core.shared;

import com.blackberry.nuanceshim.NuanceSDK;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;



public final class ScriptUtils {

    /** Sentinel returned for a locale whose script we do not know; every consumer treats it as "accept anything". */
    public static final int SCRIPT_UNKNOWN = -1;

    /**
     * Language tag -> script code. Immutable after class initialisation: it is read from the main
     * thread and from the suggestion worker, so it must never be mutated at runtime.
     */
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
        map.put(NuanceSDK.CHINESE_LOCALE, 7);
        SCRIPT_MAP = Collections.unmodifiableMap(map);
    }

    /** @return the script code for {@code language}, or {@link #SCRIPT_UNKNOWN} if it is not listed. */
    public static int getScript(String language) {
        final Integer script = SCRIPT_MAP.get(language);
        return script == null ? SCRIPT_UNKNOWN : script.intValue();
    }

    public static boolean isLetterPartOfScript(int i, int i2) {
        switch (i2) {
            case -1:
                return true;
            case 0:
                return (i >= 1536 && i <= 1791) || (i >= 1872 && i <= 1983) || ((i >= 2208 && i <= 2303) || ((i >= 64336 && i <= 65023) || (i >= 65136 && i <= 65279)));
            case 1:
                return (i >= 1328 && i <= 1423) || (i >= 64275 && i <= 64279);
            case 2:
                return i >= 2432 && i <= 2559;
            case 3:
                return i >= 1024 && i <= 1327 && Character.isLetter(i);
            case 4:
                return i >= 2304 && i <= 2431;
            case 5:
                return (i >= 4256 && i <= 4351) || (i >= 11520 && i <= 11567);
            case 6:
                return (i >= 880 && i <= 1023) || (i >= 7936 && i <= 8191) || i == 242;
            case 7:
                return (i >= 19968 && i <= 40959) || (i >= 12544 && i <= 12591);
            case 8:
                return (i >= 44032 && i <= 55203) || (i >= 4352 && i <= 4607) || ((i >= 12592 && i <= 12687) || ((i >= 43360 && i <= 43391) || (i >= 55216 && i <= 55295)));
            case 9:
                return (i >= 1424 && i <= 1535) || (i >= 64285 && i <= 64335);
            case 10:
                return (i <= 687 && Character.isLetter(i)) || (i >= 12352 && i <= 12447) || ((i >= 12448 && i <= 12543) || ((i >= 12784 && i <= 12799) || ((i >= 12800 && i <= 13055) || ((i >= 13056 && i <= 13311) || ((i >= 65280 && i <= 65519) || ((i >= 110592 && i <= 110847) || ((i >= 127488 && i <= 127743) || (i >= 12288 && i <= 12351))))))));
            case 11:
                return i >= 3200 && i <= 3327;
            case 12:
                return (i >= 6016 && i <= 6143) || (i >= 6624 && i <= 6655);
            case 13:
                return i >= 3712 && i <= 3839;
            case 14:
                return i <= 687 && Character.isLetter(i);
            case 15:
                return i >= 3328 && i <= 3455;
            case 16:
                return (i >= 4096 && i <= 4255) || (i >= 43616 && i <= 43647) || (i >= 43488 && i <= 43519);
            case 17:
                return i >= 3456 && i <= 3583;
            case 18:
                return i >= 2944 && i <= 3071;
            case 19:
                return i >= 3072 && i <= 3199;
            case 20:
                return i >= 3584 && i <= 3711;
            case 21:
                return (i <= 687 && Character.isLetter(i)) || i == 769 || i == 768 || i == 777 || i == 771 || i == 803;
            default:
                throw new RuntimeException("Impossible value of script: " + i2);
        }
    }

    /**
     * @return the script code for {@code locale}'s language, or {@link #SCRIPT_UNKNOWN} when the
     *         language is not listed. An added language or a "no language" subtype used to throw
     *         here and take the IME down; {@code isLetterPartOfScript} already handles the sentinel.
     */
    public static int getScriptFromLocale(Locale locale) {
        return getScript(locale.getLanguage());
    }

    public static boolean isUnsupportedScript(String str) {
        Integer num;
        return str == null || str.isEmpty() || (num = SCRIPT_MAP.get(str)) == null || num.intValue() == 14 || num.intValue() == 3 || num.intValue() == 1 || num.intValue() == 6 || num.intValue() == 21;
    }
}

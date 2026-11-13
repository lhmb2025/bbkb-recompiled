package com.blackberry.nuanceshim.languagepack;

import android.util.ArrayMap;
import android.util.Log;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import dev.bbkb.ime.BuildConfig;

/** Available language packs by language, with country fallbacks for lookup. */

public class LanguagePackRegistry {

    // A plain HashMap, not ArrayMap: this table is read by PackOffer, which is tested on the JVM
    // where android.util.ArrayMap is a stub that stores nothing.
    private static final Map<String, String> LOCALE_FALLBACKS = new HashMap<String, String>(8);

    public Map<String, List<LanguagePackInfo>> packsByLanguage;

    static {
        LOCALE_FALLBACKS.put("es-MX", "es-419");
        // The keyboard says GB; the bundled pack is catalogued as UK.
        LOCALE_FALLBACKS.put("en-GB", "en-UK");
        LOCALE_FALLBACKS.put("en-NZ", "en-AU");
        LOCALE_FALLBACKS.put("en-SG", "en-UK");
        LOCALE_FALLBACKS.put("en-IE", "en-UK");
        LOCALE_FALLBACKS.put("en-ZA", "en-UK");
    }

    /** The hyphenated tag substituted for {@code languageTag} ({@code en-NZ} to {@code en-AU}), or null. */
    public static String fallbackFor(String languageTag) {
        return LOCALE_FALLBACKS.get(languageTag);
    }

    /**
     * @param language language code
     * @param country country code, or null/empty for none
     */
    public synchronized LanguagePackInfo findLanguagePack(String language, String country) {
        return findLanguagePackInternal(language, country, true);
    }

    /**
     * @param language language code
     * @param country country code, or null/empty for none
     * @param allowFallback true to try the fallback table and the language's default pack
     */
    private LanguagePackInfo findLanguagePackInternal(String language, String country, boolean allowFallback) {
        if (country != null && country.isEmpty()) {
            country = null;
        }
        String str3 = country != null ? language + "-" + country : language;
        Map<String, List<LanguagePackInfo>> map = this.packsByLanguage;
        if (map == null) {
            return null;
        }
        List<LanguagePackInfo> list = map.get(language);
        if (list != null) {
            for (LanguagePackInfo pack : list) {
                if (Objects.equals(pack.country, country)) {
                    if (BuildConfig.DEBUG) Log.d("NuanceShim", "Language found: " + str3);
                    return pack;
                }
            }
        }
        if (!allowFallback) {
            return null;
        }
        String str4 = LOCALE_FALLBACKS.get(str3);
        if (str4 != null) {
            String[] fallbackParts = str4.split("-");
            LanguagePackInfo result = findLanguagePackInternal(fallbackParts[0], fallbackParts.length > 1 ? fallbackParts[1] : null, false);
            if (result != null) {
                if (BuildConfig.DEBUG) Log.d("NuanceShim", "Language found via fallback: " + str3 + " -> " + str4);
            }
            return result;
        }
        // For a language-only fallback, add a bare-language key to LOCALE_FALLBACKS.
        if (list != null) {
            for (LanguagePackInfo candidate : list) {
                if (candidate.isDefault) {
                    if (BuildConfig.DEBUG) Log.d("NuanceShim", "Language found (default): " + str3);
                    return candidate;
                }
            }
        }
        return null;
    }

    synchronized void addLanguagePack(LanguagePackInfo pack) throws IOException {
        if (findLanguagePackInternal(pack.language, pack.country, false) != null) {
            throw new IOException("Duplicate entry for language " + pack.language + "_" + pack.country);
        }
        if (this.packsByLanguage == null) {
            this.packsByLanguage = new ArrayMap<String, List<LanguagePackInfo>>(100);
        }
        List<LanguagePackInfo> linkedList = this.packsByLanguage.get(pack.language);
        if (linkedList == null) {
            linkedList = new LinkedList<>();
            this.packsByLanguage.put(pack.language, linkedList);
        }
        linkedList.add(pack);
    }
}

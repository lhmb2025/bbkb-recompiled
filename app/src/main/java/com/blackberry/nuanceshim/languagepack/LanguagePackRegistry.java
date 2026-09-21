package com.blackberry.nuanceshim.languagepack;

import android.util.ArrayMap;
import android.util.Log;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import dev.bbkb.ime.BuildConfig;

/**
 * LanguagePackRegistry - Central registry of available NuanceSDK language packs
 * Manages language pack metadata, locale mappings, and installation status.
 * Provides lookup methods to find appropriate language packs for given locales.
 */

public class LanguagePackRegistry {

    private static final Map<String, String> LOCALE_FALLBACKS = new ArrayMap<String, String>(8);

    public Map<String, List<LanguagePackInfo>> packsByLanguage;

    static {
        LOCALE_FALLBACKS.put("es-MX", "es-419");
        LOCALE_FALLBACKS.put("en-NZ", "en-AU");
        LOCALE_FALLBACKS.put("en-SG", "en-UK");
        LOCALE_FALLBACKS.put("en-IE", "en-UK");
        LOCALE_FALLBACKS.put("en-ZA", "en-UK");
    }

    public synchronized LanguagePackInfo findLanguagePack(String str, String str2) {
        return findLanguagePackInternal(str, str2, true);
    }

    private LanguagePackInfo findLanguagePackInternal(String str, String str2, boolean z) {
        if (str2 != null && str2.isEmpty()) {
            str2 = null;
        }
        String str3 = str2 != null ? str + "-" + str2 : str;
        Map<String, List<LanguagePackInfo>> map = this.packsByLanguage;
        if (map == null) {
            return null;
        }
        List<LanguagePackInfo> list = map.get(str);
        if (list != null) {
            for (LanguagePackInfo pack : list) {
                if (Objects.equals(pack.country, str2)) {
                    if (BuildConfig.DEBUG) Log.d("NuanceShim", "Language found: " + str3);
                    return pack;
                }
            }
        }
        if (!z) {
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
        // A language-only fallback block used to sit here, looking up LOCALE_FALLBACKS by the
        // bare language code. Every key in that map is a full language-COUNTRY tag (es-MX,
        // en-NZ, en-SG, en-IE, en-ZA), so the lookup could never hit and the block was
        // unreachable. Removed rather than left to read as live logic. To add language-only
        // fallback, put bare-language keys in the map (e.g. "es" -> "es-419"); the
        // language-COUNTRY branch above already handles them.
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

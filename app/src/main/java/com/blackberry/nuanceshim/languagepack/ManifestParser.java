package com.blackberry.nuanceshim.languagepack;

import android.content.Context;
import android.util.JsonReader;

import dev.bbkb.ime.core.shared.Logger;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * ManifestParser - Parses the language pack manifest.json file
 * Reads language pack metadata from assets and populates LanguagePackRegistry.
 * Handles JSON parsing of language pack definitions.
 */

public class ManifestParser {
    public static LanguagePackRegistry parseManifest(Context context) throws IOException {
        String str = "ldb" + File.separator + "manifest.json";
        LanguagePackRegistry registry = new LanguagePackRegistry();
        try (JsonReader jsonReader = new JsonReader(
                new InputStreamReader(context.getAssets().open(str), StandardCharsets.UTF_8))) {
            jsonReader.beginObject();
            while (jsonReader.hasNext()) {
                String strNextName = jsonReader.nextName();
                if (strNextName.equals("languages")) {
                    jsonReader.beginArray();
                    while (jsonReader.hasNext()) {
                        registry.addLanguagePack(parseLanguagePackEntry(jsonReader, str));
                    }
                    jsonReader.endArray();
                } else {
                    // Skip unrecognized keys (including deprecated server_uri)
                    jsonReader.skipValue();
                }
            }
            jsonReader.endObject();
        }
        mergeCustomPacks(context, registry);
        return registry;
    }

    /**
     * Merge the user's side-loaded packs on top of the shipped catalogue.
     *
     * <p>The shipped manifest is an APK asset and cannot be written to, so a pack installed with
     * the "+" button records itself in {@link CustomPackRegistryStore} instead; without this merge
     * the registry would not know the locale, {@code LanguagePackInstaller.isSupported()} would be
     * false, and {@code LanguagePackManager}'s hourly cleanup would delete the pack directory.
     *
     * <p>Shipped entries win: {@code addLanguagePack} throws on a duplicate language+country, and
     * a custom entry that collides is skipped rather than allowed to shadow what the APK ships.
     */
    private static void mergeCustomPacks(Context context, LanguagePackRegistry registry) {
        for (LanguagePackInfo custom : CustomPackRegistryStore.read(context)) {
            try {
                registry.addLanguagePack(custom);
            } catch (IOException duplicate) {
                Logger.error("LPM", "Ignoring custom pack that duplicates a shipped one: " + custom);
            }
        }
    }

    static LanguagePackInfo parseLanguagePackEntry(JsonReader jsonReader, String str) throws IOException {
        LanguagePackInfo packInfo = new LanguagePackInfo();
        jsonReader.beginObject();
        while (jsonReader.hasNext()) {
            String strNextName = jsonReader.nextName();
            if (strNextName.equals("language")) {
                packInfo.language = jsonReader.nextString();
            } else if (strNextName.equals("country")) {
                packInfo.country = jsonReader.nextString();
            } else if (strNextName.equals("name")) {
                packInfo.name = jsonReader.nextString();
            } else if (strNextName.equals("preinstalled")) {
                packInfo.setPreinstalledFlag(jsonReader.nextBoolean());
            } else if (strNextName.equals("path")) {
                packInfo.path = jsonReader.nextString();
            } else if (strNextName.equals("version")) {
                packInfo.version = jsonReader.nextDouble();
            } else if (strNextName.equals("default")) {
                packInfo.isDefault = jsonReader.nextBoolean();
            } else {
                // Skip unrecognized keys (including deprecated sha256)
                jsonReader.skipValue();
            }
        }
        jsonReader.endObject();
        if (packInfo.isValid()) {
            return packInfo;
        }
        throw new IOException("Encountered incomplete language specification in file " + str);
    }
}

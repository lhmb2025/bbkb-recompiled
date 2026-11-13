package com.blackberry.nuanceshim.languagepack;

import android.content.Context;
import android.util.JsonReader;

import dev.bbkb.ime.core.shared.Logger;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Builds the {@link LanguagePackRegistry} from {@code assets/ldb/manifest.json} plus side-loaded packs. */

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
                    // e.g. server_uri
                    jsonReader.skipValue();
                }
            }
            jsonReader.endObject();
        }
        mergeCustomPacks(context, registry);
        return registry;
    }

    /** Adds {@link CustomPackRegistryStore} entries; a custom entry duplicating a shipped one is skipped. */
    private static void mergeCustomPacks(Context context, LanguagePackRegistry registry) {
        for (LanguagePackInfo custom : CustomPackRegistryStore.read(context)) {
            try {
                registry.addLanguagePack(custom);
            } catch (IOException duplicate) {
                Logger.error("LPM", "Ignoring custom pack that duplicates a shipped one: " + custom);
            }
        }
    }

    /** @param fileName source file name, for the error message */
    static LanguagePackInfo parseLanguagePackEntry(JsonReader jsonReader, String fileName) throws IOException {
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
                // e.g. sha256
                jsonReader.skipValue();
            }
        }
        jsonReader.endObject();
        if (packInfo.isValid()) {
            return packInfo;
        }
        throw new IOException("Encountered incomplete language specification in file " + fileName);
    }
}

package com.blackberry.nuanceshim.languagepack;

import android.content.Context;
import android.util.JsonReader;
import android.util.JsonWriter;

import androidx.core.util.AtomicFile;

import dev.bbkb.ime.core.shared.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Writable catalogue of side-loaded packs, same entry shape as the read-only
 * {@code assets/ldb/manifest.json}, merged on top of it by {@link ManifestParser} so the locale
 * is supported. Entries are never preinstalled, so {@link LanguagePackInstaller#uninstall()} can
 * remove them. Locale identifiers are validated because they become directory names.
 */
public final class CustomPackRegistryStore {

    private static final String TAG = "LPM";

    /** In filesDir (backed up), unlike the .ldb files; a restored entry without its directory reads as not installed. */
    private static final String FILE_NAME = "nuance_custom_packs.json";

    private CustomPackRegistryStore() {
    }

    static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    /**
     * A language or language_COUNTRY id safe as a directory name: 2 or 3 letters or digits per part.
     * Not {@code Locale}-based because the manifest ships {@code es-419} (a UN M.49 region).
     */
    public static boolean isValidLocaleIdentifier(String id) {
        if (id == null) {
            return false;
        }
        final int underscore = id.indexOf('_');
        final String language = underscore < 0 ? id : id.substring(0, underscore);
        if (!isCodePart(language, 2, 3)) {
            return false;
        }
        if (underscore < 0) {
            return true;
        }
        final String country = id.substring(underscore + 1);
        return isCodePart(country, 2, 3);
    }

    private static boolean isCodePart(String s, int min, int max) {
        if (s == null || s.length() < min || s.length() > max) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isLetterOrDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** Every side-loaded entry, or an empty list when the file is absent or unreadable. */
    public static synchronized List<LanguagePackInfo> read(Context context) {
        final List<LanguagePackInfo> out = new ArrayList<>();
        final File f = file(context);
        if (!f.isFile()) {
            return out;
        }
        try (JsonReader reader = new JsonReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            reader.beginObject();
            while (reader.hasNext()) {
                if ("languages".equals(reader.nextName())) {
                    reader.beginArray();
                    while (reader.hasNext()) {
                        try {
                            out.add(ManifestParser.parseLanguagePackEntry(reader, FILE_NAME));
                        } catch (IOException incomplete) {
                            // One bad entry must not cost the user the rest of their packs.
                            Logger.errorWithException(TAG, incomplete, "Skipping custom pack entry");
                        }
                    }
                    reader.endArray();
                } else {
                    reader.skipValue();
                }
            }
            reader.endObject();
        } catch (IOException | IllegalStateException | NumberFormatException e) {
            // A corrupt file reads as "no custom packs"; the shipped manifest still loads.
            Logger.errorWithException(TAG, e, "Unreadable custom pack registry; ignoring it");
            return new ArrayList<>();
        }
        return out;
    }

    /** Records a side-loaded pack, replacing any entry for the same language+country. */
    public static synchronized boolean add(Context context, String language, String country,
            String name, String path, double version) {
        if (!isCodePart(language, 2, 3) || (country != null && !isCodePart(country, 2, 3))) {
            Logger.error(TAG, "Refusing to register custom pack with invalid locale");
            return false;
        }
        final List<LanguagePackInfo> packs = read(context);
        removeMatching(packs, language, country);
        final LanguagePackInfo info = new LanguagePackInfo();
        info.language = language;
        info.country = country;
        info.name = name;
        info.path = path;
        info.version = version;
        info.setPreinstalledFlag(false);
        info.isDefault = false;
        if (!info.isValid()) {
            Logger.error(TAG, "Refusing to register incomplete custom pack " + info);
            return false;
        }
        packs.add(info);
        return write(context, packs);
    }

    /** Safe to call when no entry exists. */
    public static synchronized boolean remove(Context context, String language, String country) {
        final List<LanguagePackInfo> packs = read(context);
        if (!removeMatching(packs, language, country)) {
            return true;
        }
        return write(context, packs);
    }

    private static boolean removeMatching(List<LanguagePackInfo> packs, String language,
            String country) {
        final String c = (country != null && country.isEmpty()) ? null : country;
        boolean removed = false;
        for (int i = packs.size() - 1; i >= 0; i--) {
            final LanguagePackInfo p = packs.get(i);
            if (Objects.equals(p.language, language) && Objects.equals(p.country, c)) {
                packs.remove(i);
                removed = true;
            }
        }
        return removed;
    }

    /** Atomic replace, so a crash mid-write cannot leave a half-written registry. */
    private static boolean write(Context context, List<LanguagePackInfo> packs) {
        final File f = file(context);
        final AtomicFile atomic = new AtomicFile(f);
        FileOutputStream out = null;
        try {
            out = atomic.startWrite();
            final JsonWriter w = new JsonWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
            w.beginObject();
            w.name("languages").beginArray();
            for (LanguagePackInfo p : packs) {
                w.beginObject();
                w.name("language").value(p.language);
                if (p.country != null) {
                    w.name("country").value(p.country);
                }
                w.name("name").value(p.name);
                w.name("path").value(p.path);
                w.name("version").value(p.version);
                w.name("preinstalled").value(false);
                w.endObject();
            }
            w.endArray();
            w.endObject();
            // Flushed, not closed: finishWrite syncs the stream before it closes it.
            w.flush();
            atomic.finishWrite(out);
        } catch (IOException e) {
            atomic.failWrite(out);
            Logger.errorWithException(TAG, e, "Failed to write custom pack registry");
            return false;
        } catch (RuntimeException e) {
            atomic.failWrite(out);
            throw e;
        }
        // finishWrite only logs a failed rename, which leaves the registry in the .new file.
        final File pending = new File(f.getPath() + ".new");
        if (pending.exists()) {
            Logger.error(TAG, "Failed to replace custom pack registry");
            //noinspection ResultOfMethodCallIgnored
            pending.delete();
            return false;
        }
        return true;
    }
}

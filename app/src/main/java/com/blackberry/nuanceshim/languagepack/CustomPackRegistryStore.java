package com.blackberry.nuanceshim.languagepack;

import android.content.Context;
import android.util.JsonReader;
import android.util.JsonWriter;

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
 * The writable half of the language-pack registry: packs the user side-loaded with the "+" button.
 *
 * <p><b>Why this exists.</b> {@code assets/ldb/manifest.json} is the shipped catalogue of packs,
 * and it is an APK asset - read-only for the life of the install. Everything downstream decides
 * what a locale <em>is</em> by looking it up there: {@link LanguagePackInstaller#isSupported()} is
 * simply "the registry knows this language", and {@code LanguagePackManager}'s hourly cleanup
 * deletes the directory of any installed locale that is not supported. So before this class, a
 * side-loaded pack for a locale the app does not ship was deleted by the next cleanup pass - the
 * "+" button could only ever "install" a language the APK already contained.
 *
 * <p>The fix is not to write to the shipped manifest (impossible) but to keep a second, writable
 * catalogue in {@code filesDir} with the same entry shape, which {@link ManifestParser} merges on
 * top of the shipped one. A side-loaded pack then has a real registry entry - name, language,
 * country, version - and behaves like any other: it is supported, it survives cleanup, it can be
 * uninstalled, and it is never {@code preinstalled}, so {@link LanguagePackInstaller#uninstall()}
 * will actually remove it.
 *
 * <p>Entries here are USER DATA describing files the user chose, not a trust boundary: the locale
 * identifier is validated before it becomes a directory name (see
 * {@link #isValidLocaleIdentifier}), because it is what the pack directory is named after.
 */
public final class CustomPackRegistryStore {

    private static final String TAG = "LPM";

    /**
     * Not in {@code noBackupFilesDir} alongside the packs themselves: this is a small record of
     * the user's own choices and is worth restoring with their settings, whereas the multi-megabyte
     * .ldb files are not. A restored entry whose directory is missing simply reads as not installed.
     */
    private static final String FILE_NAME = "nuance_custom_packs.json";

    private CustomPackRegistryStore() {
    }

    static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    /**
     * A language or language_COUNTRY identifier that is safe to use as a directory name.
     *
     * <p>Deliberately not {@code Locale}-based: the shipped manifest itself carries {@code es-419},
     * whose "country" is a UN M.49 region code, so a 2-letter ISO test would reject a pack the app
     * already ships. Letters and digits only, 2-3 of them per part - which excludes {@code ..},
     * separators, and everything else that could escape the nuance directory.
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
            // A truncated or hand-edited file reads as "no custom packs" rather than taking the
            // whole registry down with it - the shipped manifest still loads.
            Logger.errorWithException(TAG, e, "Unreadable custom pack registry; ignoring it");
            return new ArrayList<>();
        }
        return out;
    }

    /**
     * Record a side-loaded pack, replacing any existing entry for the same language+country.
     *
     * @return {@code true} when the registry was written
     */
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

    /** Forget a side-loaded pack. Safe to call when no entry exists. */
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

    private static boolean write(Context context, List<LanguagePackInfo> packs) {
        final File f = file(context);
        final File tmp = new File(f.getParentFile(), FILE_NAME + ".tmp");
        try (JsonWriter w = new JsonWriter(
                new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8))) {
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
        } catch (IOException e) {
            Logger.errorWithException(TAG, e, "Failed to write custom pack registry");
            tmp.delete();
            return false;
        }
        // Rename over the live file so a crash mid-write cannot leave a half-written registry -
        // losing the record of a pack would let the cleanup delete the pack itself.
        if (!tmp.renameTo(f)) {
            Logger.error(TAG, "Failed to replace custom pack registry");
            tmp.delete();
            return false;
        }
        return true;
    }
}

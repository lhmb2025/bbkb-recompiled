package com.blackberry.nuanceshim.languagepack;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import dev.bbkb.ime.core.shared.Logger;
import com.blackberry.nuanceshim.NuanceSDK;
import dev.bbkb.ime.BuildConfig;

/**
 * LanguagePackInstaller - Installation status of the NuanceSDK language pack resolved for one
 * requested locale, plus ZIP extraction.
 * Coordinates with LanguagePackRegistry for available language pack metadata.
 */
public class LanguagePackInstaller {

    /**
     * Process-wide cache, keyed by the REQUESTED language tag (the resolved pack may differ via
     * registry fallbacks and defaults; the first registry to create an entry wins). It is the only
     * thing standing between {@code isInstalled()} and a version.txt disk read per call, so it
     * cannot simply be dropped -- but it is reached from the locale monitor and from
     * LanguagePackManager's cleanup task, and its callers are not synchronized, so it must be a
     * concurrent map rather than the raw HashSet-era {@code new HashMap()} it used to be.
     */
    private static final Map<String, LanguagePackInstaller> sByLanguageTag = new ConcurrentHashMap<>();

    private Context context;

    private LanguagePackInfo languagePackInfo;

    private double installedVersion;

    static LanguagePackInstaller getOrCreate(Context context, Locale locale, LanguagePackRegistry registry) {
        final String tag = locale.toLanguageTag();
        final LanguagePackInstaller cached = sByLanguageTag.get(tag);
        if (cached != null) {
            return cached;
        }
        // putIfAbsent, not computeIfAbsent: the latter is API 24+ and minSdk is 23.
        final LanguagePackInstaller created = new LanguagePackInstaller(context, locale, registry);
        final LanguagePackInstaller prior = sByLanguageTag.putIfAbsent(tag, created);
        return prior != null ? prior : created;
    }

    static boolean setLanguage(NuanceSDK nuanceSDK, LanguagePackInstaller[] packs) {
        Locale[] localeArr = new Locale[packs.length];
        for (int i = 0; i < packs.length; i++) {
            if (!packs[i].isSupported()) {
                Logger.warn("LPM", "Can't set language to unsupported locale");
                return false;
            }
            localeArr[i] = packs[i].getLocale();
        }
        return nuanceSDK.setLanguage(localeArr);
    }

    public static Set<String> getInstalledLocales(Context context) {
        String[] list;
        HashSet<String> hashSet = new HashSet<>();
        File[] fileArrListFiles = new File(context.getNoBackupFilesDir().getPath() + File.separator + NuanceSDK.DYNAMIC_MODEL_ABSTRACT_DIR).listFiles();
        if (fileArrListFiles == null) {
            return hashSet;
        }
        for (File file : fileArrListFiles) {
            if (file.isDirectory() && (list = file.list(new FilenameFilter() {
                @Override
                public boolean accept(File file2, String str) {
                    return str.endsWith(".ldb") || str.equals("version.txt");
                }
            })) != null && list.length >= 2) {
                hashSet.add(file.getName());
            }
        }
        return hashSet;
    }

    public static void removeUnsupportedLocale(Context context, String str) {
        if (BuildConfig.DEBUG) Log.i("NuanceShim", "Removing unsupported locale " + str);
        File fileM7966b = getLanguagePackDirectory(context, str);
        if (fileM7966b.exists()) {
            deleteRecursively(fileM7966b);
        }
    }

    public LanguagePackInstaller(Context context, Locale locale, LanguagePackRegistry registry) {
        this(context, locale.getLanguage(), locale.getCountry(), registry);
    }

    public LanguagePackInstaller(Context context, String str, String str2, LanguagePackRegistry registry) {
        this.installedVersion = -1.0d;
        this.languagePackInfo = registry.findLanguagePack(str, str2);
        this.context = context;
        LanguagePackInfo c1135b = this.languagePackInfo;
        if (c1135b == null || !c1135b.isPreinstalled()) {
            return;
        }
        this.installedVersion = this.languagePackInfo.version;
    }

    public String getLocaleIdentifier() {
        if (!isSupported()) {
            return null;
        }
        if (this.languagePackInfo.country == null) {
            return this.languagePackInfo.language;
        }
        return this.languagePackInfo.language + "_" + this.languagePackInfo.country;
    }

    public Locale getLocale() {
        if (!isSupported()) {
            return null;
        }
        if (this.languagePackInfo.country == null) {
            return new Locale(this.languagePackInfo.language);
        }
        return new Locale(this.languagePackInfo.language, this.languagePackInfo.country);
    }

    public static File getLanguagePackDirectory(Context context, String str) {
        return new File(context.getNoBackupFilesDir().getPath() + File.separator + NuanceSDK.DYNAMIC_MODEL_ABSTRACT_DIR + File.separator + str);
    }

    private File getDirectory() {
        return getLanguagePackDirectory(this.context, getLocaleIdentifier());
    }

    public boolean isSupported() {
        return this.languagePackInfo != null;
    }

    public boolean isPreinstalled() {
        return isSupported() && this.languagePackInfo.isPreinstalled();
    }

    public boolean isInstalled() {
        if (!isSupported()) {
            return false;
        }
        if (this.installedVersion > -1.0d || this.languagePackInfo.isPreinstalled()) {
            return true;
        }
        File fileM7971j = getDirectory();
        if (fileM7971j.isDirectory()) {
            try (FileReader fileReader =
                         new FileReader(new File(fileM7971j, "version.txt"))) {
                char[] cArr = new char[8];
                int i = fileReader.read(cArr, 0, cArr.length);
                while (i > 0) {
                    int i2 = i - 1;
                    if (Character.isDigit(cArr[i2])) {
                        break;
                    }
                    i = i2;
                }
                this.installedVersion = Double.parseDouble(new String(cArr, 0, i));
                return true;
            } catch (IOException | IndexOutOfBoundsException | NumberFormatException e) {
                if (BuildConfig.DEBUG) Log.w("NuanceShim", "Error reading version file for language " + getLocaleIdentifier(), e);
            }
        }
        return false;
    }

    public static void deleteRecursively(File file) {
        if (file.isDirectory()) {
            for (File file2 : file.listFiles()) {
                deleteRecursively(file2);
            }
        }
        file.delete();
    }

    public synchronized void uninstall() {
        if (isInstalled() && !isPreinstalled()) {
            if (BuildConfig.DEBUG) Log.i("NuanceShim", "Removing locale " + getLocaleIdentifier());
            File fileM7971j = getDirectory();
            if (fileM7971j.exists()) {
                deleteRecursively(fileM7971j);
            }
            // The cached version pinned isInstalled() to true after the directory was gone, and
            // the cache keeps this installer alive for the process lifetime. Entries are keyed by
            // the requested tag, not this installer's resolved locale, so drop them by identity.
            this.installedVersion = -1.0d;
            sByLanguageTag.values().removeAll(Collections.singleton(this));
            return;
        }
        if (BuildConfig.DEBUG) Log.w("NuanceShim", "Can't remove uninstalled or preinstalled locale " + getLocaleIdentifier());
    }

    /**
     * Resolves a zip entry name against {@code destDir} and rejects anything that escapes it.
     *
     * <p>Zip Slip: the entry name is attacker-controlled, so {@code new File(destDir, name)}
     * with a name like {@code ../../databases/x} writes outside the language-pack directory.
     * The destination here is {@code getNoBackupFilesDir()/nuance/<locale>}, whose siblings
     * include the dynamic learning model this project treats as security-sensitive state.
     */
    private static File resolveEntry(File destDir, String entryName) throws IOException {
        final File out = new File(destDir, entryName).getCanonicalFile();
        final String prefix = destDir.getCanonicalPath() + File.separator;
        if (!out.getPath().startsWith(prefix)) {
            throw new IOException("Zip entry escapes the destination directory: " + entryName);
        }
        return out;
    }

    /**
     * Extracts {@code inputStream} into {@code destDir}.
     *
     * <p>Throws on any failure. The previous shape collapsed a try/finally into an outer catch
     * that assigned the throwable to a dead local and returned normally, so a completely failed
     * extraction was reported to {@link #unpackArchive} as success -- {@code isInstalled()} then
     * found a directory and the engine registered a broken language database.
     */
    private void extractZip(InputStream inputStream, File destDir) throws IOException {
        destDir.mkdirs();
        final byte[] buffer = new byte[NuanceSDK.MAX_CONTEXT_LENGTH];
        try (ZipInputStream zipInputStream = new ZipInputStream(inputStream)) {
            for (ZipEntry entry = zipInputStream.getNextEntry();
                    entry != null;
                    entry = zipInputStream.getNextEntry()) {
                final File out = resolveEntry(destDir, entry.getName());
                if (entry.isDirectory()) {
                    if (BuildConfig.DEBUG) Log.i("NuanceShim", entry.getName() + " is a directory");
                    out.mkdirs();
                    continue;
                }
                final File parent = out.getParentFile();
                if (parent != null && !parent.exists()) {
                    if (BuildConfig.DEBUG) Log.i("NuanceShim", out.getParent() + " does not exist, creating");
                    parent.mkdirs();
                }
                if (BuildConfig.DEBUG) Log.i("NuanceShim", "Copying " + entry.getName() + " to " + destDir.getAbsolutePath());
                try (FileOutputStream fileOutputStream = new FileOutputStream(out)) {
                    for (int i = zipInputStream.read(buffer); i != -1; i = zipInputStream.read(buffer)) {
                        fileOutputStream.write(buffer, 0, i);
                    }
                    fileOutputStream.flush();
                }
                zipInputStream.closeEntry();
            }
        }
    }

    public boolean unpackArchive(InputStream inputStream) {
        File fileM7971j = getDirectory();
        try {
            extractZip(inputStream, fileM7971j);
            return true;
        } catch (IOException e) {
            if (BuildConfig.DEBUG) Log.e("NuanceShim", "Failed to unpack archive at " + fileM7971j, e);
            return false;
        }
    }
}

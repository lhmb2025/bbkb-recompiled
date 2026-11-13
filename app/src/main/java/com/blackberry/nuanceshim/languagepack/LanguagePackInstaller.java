package com.blackberry.nuanceshim.languagepack;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import dev.bbkb.ime.core.locale.LocaleUtils;
import dev.bbkb.ime.core.shared.Logger;
import com.blackberry.nuanceshim.NuanceSDK;
import dev.bbkb.ime.BuildConfig;

/** Installation status of the language pack resolved for one requested locale. */
public class LanguagePackInstaller {

    /**
     * Keyed by the requested tag (the resolved pack may differ). Saves a version.txt read per
     * {@code isInstalled()} call; concurrent because callers are not synchronized.
     */
    private static final ConcurrentMap<String, LanguagePackInstaller> sByLanguageTag = new ConcurrentHashMap<>();

    private Context context;

    private LanguagePackInfo languagePackInfo;

    private double installedVersion;

    static LanguagePackInstaller getOrCreate(Context context, Locale locale, LanguagePackRegistry registry) {
        final String tag = locale.toLanguageTag();
        final LanguagePackInstaller cached = sByLanguageTag.get(tag);
        if (cached != null) {
            return cached;
        }
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

    public LanguagePackInstaller(Context context, Locale locale, LanguagePackRegistry registry) {
        // The registry uses old codes (`in`, `iw`); Android 15 Locales say `id` / `he`.
        this(context, LocaleUtils.languageCode(locale), locale.getCountry(), registry);
    }

    /**
     * @param str language code
     * @param str2 country code, or null/empty for none
     */
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

    /** @param str locale identifier, language or language_COUNTRY */
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
        final File[] children = file.isDirectory() ? file.listFiles() : null;
        if (children != null) {
            for (File file2 : children) {
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
            // The cached version would keep isInstalled() true. Cache keys are requested tags,
            // so drop this installer by identity.
            this.installedVersion = -1.0d;
            sByLanguageTag.values().removeAll(Collections.singleton(this));
            return;
        }
        if (BuildConfig.DEBUG) Log.w("NuanceShim", "Can't remove uninstalled or preinstalled locale " + getLocaleIdentifier());
    }
}

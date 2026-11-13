package com.blackberry.nuanceshim.languagepack;

import android.content.Context;

import dev.bbkb.ime.core.shared.Logger;
import dev.bbkb.ime.core.shared.StartupTiming;
import com.blackberry.nuanceshim.NuanceSDK;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

/**
 * Process-wide entry point for language pack lookups and engine language setting.
 * Installed packs are never cleaned up automatically (they are user data); only
 * {@code LanguagePackInstaller.uninstall()} removes one.
 */
public class LanguagePackManager {

    private static LanguagePackManager sInstance;

    private LanguagePackRegistry registry;

    /** Separate from {@code registry != null}: a failed parse leaves it null and must not be retried on every lookup. */
    private boolean registryLoaded = false;

    private final Context context;

    private boolean bootCompleted = false;

    public static synchronized LanguagePackManager getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new LanguagePackManager(context.getApplicationContext());
        }
        return sInstance;
    }

    LanguagePackManager(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * The catalogue, parsed lazily to keep it off the IME's cold-start main thread. Null means
     * the manifest failed to parse (degraded mode). {@link #prewarmRegistry()} parses it on a worker.
     */
    private synchronized LanguagePackRegistry registry() {
        if (!this.registryLoaded) {
            loadRegistry(this.context);
            this.registryLoaded = true;
        }
        return this.registry;
    }

    /** Parses the catalogue now on the calling thread; an optimisation only. */
    public void prewarmRegistry() {
        final long token = StartupTiming.begin();
        registry();
        StartupTiming.end("languagePacks.registryParse", token);
    }

    private void loadRegistry(Context context) {
        try {
            this.registry = ManifestParser.parseManifest(context);
        } catch (IOException | NullPointerException e) {
            Logger.errorWithException("LPM", e, "Failed to read LDB manifest");
        }
    }

    /** Re-reads the catalogue after a pack is side-loaded or removed; the singleton otherwise parses it once. */
    public synchronized void reloadRegistry() {
        loadRegistry(this.context);
        this.registryLoaded = true;
    }

    public LanguagePackInstaller getStatus(Locale locale) {
        LanguagePackRegistry registry = registry();
        if (registry == null) {
            Logger.warn("LPM", "Can't lookup language with null manifest");
            return null;
        }
        return LanguagePackInstaller.getOrCreate(this.context, locale, registry);
    }

    public void forceBootComplete() {
        if (this.bootCompleted) {
            return;
        }
        Logger.info("LPM", "Forcing boot complete from packageReplaced");
        onBootCompleted(this.context);
    }

    public synchronized void onBootCompleted(Context context) {
        if (this.bootCompleted) {
            Logger.info("LPM", "Second BOOT_COMPLETED action received - ignoring");
            return;
        }
        Logger.info("LPM", "BOOT_COMPLETED received");
        this.bootCompleted = true;
    }

    public boolean isBootCompleted() {
        return this.bootCompleted;
    }

    // getStatus() is null when the manifest failed to parse; the locale monitor calls these on
    // every locale change, so they must not throw.
    public boolean isInstalled(Locale locale) {
        final LanguagePackInstaller status = getStatus(locale);
        return status != null && status.isInstalled();
    }

    public boolean isSupported(Locale locale) {
        final LanguagePackInstaller status = getStatus(locale);
        return status != null && status.isSupported();
    }

    public synchronized boolean setLanguages(NuanceSDK nuanceSDK, Locale[] locales) {
        final LanguagePackInstaller[] statuses = new LanguagePackInstaller[locales.length];
        for (int i = 0; i < locales.length; i++) {
            statuses[i] = getStatus(locales[i]);
            if (statuses[i] == null) {
                Logger.warn("LPM", "No language pack status for locale: " + locales[i]);
                return false;
            }
        }
        return LanguagePackInstaller.setLanguage(nuanceSDK, statuses);
    }
}

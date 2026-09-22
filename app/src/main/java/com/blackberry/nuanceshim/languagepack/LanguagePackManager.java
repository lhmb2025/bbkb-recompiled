package com.blackberry.nuanceshim.languagepack;

import android.content.Context;

import dev.bbkb.ime.core.shared.Logger;
import dev.bbkb.ime.core.shared.StartupTiming;
import com.blackberry.nuanceshim.NuanceSDK;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

/**
 * LanguagePackManager - Central manager for language pack installation status.
 * Coordinates with LanguagePackRegistry for metadata and LanguagePackInstaller for status.
 * Note: Network downloads are disabled - language packs are installed locally.
 *
 * <p><b>There is no automatic cleanup of installed language packs (removed 2026-09-16, owner
 * decision).</b> An hourly task used to delete the directory of every installed locale that was
 * not an ENABLED SUBTYPE - not merely every unsupported one. Three things made that indefensible
 * once packs could be side-loaded:
 *
 * <ul>
 *   <li>It deleted user data. A pack the user picked by hand is not reclaimable cache; the
 *       feature was written when every pack came from the APK and could be re-extracted.</li>
 *   <li>Its rule was wrong for the 28 engine-supported languages {@code method.xml} has no
 *       subtype for. Those can never be "in use", so they were deleted within the hour of being
 *       installed, every time, with no way for a user to prevent it.</li>
 *   <li>It raced the user. The window between installing a pack and enabling its subtype was
 *       enough to lose the pack.</li>
 * </ul>
 *
 * <p>The cost is that disabling a language no longer reclaims its directory. Packs are a few MB
 * each and only appear because someone installed or enabled that language, so they are bounded by
 * user action; {@code LanguagePackInstaller.uninstall()} and the Language packs screen's delete
 * button remain the way to remove one. That is a deliberate trade of disk for not losing data.
 */
public class LanguagePackManager {

    private static LanguagePackManager sInstance;

    private LanguagePackRegistry registry;

    /**
     * Whether {@link #loadRegistry} has run. Separate from {@code registry != null} because a
     * failed parse legitimately leaves the registry null and puts the manager in its documented
     * degraded mode; without this flag every lookup would retry the failing parse.
     */
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
     * The catalogue, parsed on first use.
     *
     * <p>This used to be parsed in the constructor, and the constructor runs from
     * {@code LanguagePackLocaleMonitor}'s, which runs from {@code BlackBerryIME.onCreate} - so a
     * 105-entry JSON asset parse plus the side-loaded-pack file read sat on the IME's cold-start
     * main thread, ahead of the first keyboard frame, for a catalogue nothing reads until the
     * first dictionary load.
     *
     * <p>Every reader goes through here and the method is synchronized, so the value a caller
     * sees is the same one it saw before - including the null the degraded "manifest failed to
     * parse" mode depends on. {@link #prewarmRegistry()} exists so the IME can pay for it on a
     * worker instead; a caller that arrives first simply does the parse itself.
     */
    private synchronized LanguagePackRegistry registry() {
        if (!this.registryLoaded) {
            loadRegistry(this.context);
            this.registryLoaded = true;
        }
        return this.registry;
    }

    /**
     * Parses the catalogue now, off whatever thread calls this. Purely an optimisation: it moves
     * no decision, only the cost, and a reader that beats it to the monitor is unaffected.
     */
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

    /**
     * Re-read the catalogue after the user side-loaded or removed a pack.
     *
     * <p>The manager is a process-lifetime singleton that parses the registry once in its
     * constructor, so without this a pack installed from Settings stays invisible to every
     * lookup - `isSupported`, `setLanguages`, the whole chain. Cheap: the shipped manifest is
     * 105 asset-file entries.
     */
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
        // No unused-locale cleanup is armed here any more - see the class javadoc.
    }

    public boolean isBootCompleted() {
        return this.bootCompleted;
    }

    // getStatus() deliberately returns null when the manifest failed to parse (loadRegistry
    // swallows IOException|NullPointerException by design, leaving registry null and the
    // feature in a degraded "no language database support" mode). setLanguages checked for
    // that; these did not, so a corrupt ldb/manifest.json crashed the IME on focus -- the
    // locale monitor calls isSupported on every locale change.
    public boolean isInstalled(Locale locale) {
        final LanguagePackInstaller status = getStatus(locale);
        return status != null && status.isInstalled();
    }

    public boolean isSupported(Locale locale) {
        final LanguagePackInstaller status = getStatus(locale);
        return status != null && status.isSupported();
    }

    public synchronized boolean setLanguages(NuanceSDK nuanceSDK, Locale[] localeArr) {
        final LanguagePackInstaller[] statuses = new LanguagePackInstaller[localeArr.length];
        for (int i = 0; i < localeArr.length; i++) {
            statuses[i] = getStatus(localeArr[i]);
            if (statuses[i] == null) {
                Logger.warn("LPM", "No language pack status for locale: " + localeArr[i]);
                return false;
            }
        }
        return LanguagePackInstaller.setLanguage(nuanceSDK, statuses);
    }
}

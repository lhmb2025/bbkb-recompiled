package com.blackberry.nuanceshim.languagepack;

import android.content.Context;

import dev.bbkb.ime.core.BlackBerryIME;
import dev.bbkb.ime.core.ime.UIUpdateHandler;
import dev.bbkb.ime.core.locale.LocaleUtils;
import dev.bbkb.ime.core.shared.Logger;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * LanguagePackLocaleMonitor - Monitors locale changes and manages language pack availability.
 * Coordinates with LanguagePackManager for installation status.
 * Note: Network downloads are disabled - language packs are installed locally.
 */
public class LanguagePackLocaleMonitor {

    private Locale currentLocale;

    private Set<Locale> additionalLocales;

    private final LanguagePackManager languagePackManager;

    private final Context context;

    private final UIUpdateHandler uiUpdateHandler;

    public LanguagePackLocaleMonitor(Context context, UIUpdateHandler uiUpdateHandler) {
        this.context = context;
        this.uiUpdateHandler = uiUpdateHandler;
        this.languagePackManager = LanguagePackManager.getInstance(context);
    }

    /**
     * No-op. Kept because BlackBerryIME.onDestroy calls it; the monitor holds no registration
     * to tear down (it stopped registering with a coordinator when downloads were removed).
     */
    public void cleanup() {
    }

    public void onLocaleChanged(Locale locale, Set<Locale> set) {
        boolean z = !(set == null || set.equals(this.additionalLocales)) || (set == null && this.additionalLocales != null);
        if ((!Objects.equals(this.currentLocale, locale) || z) && !LocaleUtils.isNoLanguage(locale)) {
            this.currentLocale = locale;
            Logger.info("LPM", "Changing locale to " + locale.toString());
            this.additionalLocales = set;
            this.uiUpdateHandler.removeAdditionalLocalesReady();
            if (this.languagePackManager.isSupported(this.currentLocale)) {
                if (!this.languagePackManager.isInstalled(locale)) {
                    Logger.info("LPM", "Language pack not installed for locale " + locale);
                }
                processAdditionalLocales();
            } else {
                Logger.warn("LPM", "No language database support for Locale " + locale);
            }
        }
    }

    private void processAdditionalLocales() {
        Set<Locale> set = this.additionalLocales;
        if (set == null || set.isEmpty() || !this.languagePackManager.isInstalled(this.currentLocale)) {
            return;
        }
        Logger.info("LPM", "Subtype has additional locales" + this.additionalLocales.toString());
        for (Locale locale : this.additionalLocales) {
            if (!this.languagePackManager.isSupported(locale)) {
                Logger.warn("LPM", "No language database support for Locale " + locale);
            } else if (!this.languagePackManager.isInstalled(locale)) {
                Logger.info("LPM", "Additional locale not installed: " + locale.toString());
            } else {
                Logger.info("LPM", "ready to load additional locale " + locale.toString());
                this.uiUpdateHandler.postAdditionalLocalesReady();
            }
        }
    }

    /** No language database support for this locale. */
    public static final int NOTIFICATION_UNSUPPORTED = 0;
    /** Nothing to report: no-language subtype, or the pack is installed. */
    public static final int NOTIFICATION_NONE = 1;
    /** Supported but not installed. Downloads are disabled, so this is informational. */
    public static final int NOTIFICATION_NOT_INSTALLED = 3;

    public int getLanguageNotificationState(Locale locale) {
        if (LocaleUtils.isNoLanguage(locale)) {
            return NOTIFICATION_NONE;
        }
        if (!this.languagePackManager.isSupported(locale)) {
            return NOTIFICATION_UNSUPPORTED;
        }
        if (this.languagePackManager.isInstalled(locale)) {
            return NOTIFICATION_NONE;
        }
        return NOTIFICATION_NOT_INSTALLED;
    }

    public void forceBootComplete(Context context) {
        if (this.languagePackManager.isBootCompleted()) {
            return;
        }
        Logger.warn("LPM", "Forcing boot complete");
        this.languagePackManager.onBootCompleted(context);
    }
}

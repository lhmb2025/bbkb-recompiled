package dev.bbkb.ime.core.shared;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;

import java.util.Locale;
import dev.bbkb.ime.core.locale.LocaleUtils;



public abstract class RunInLocale<T> {

    private static final Object sLockForRunInLocale = new Object();

    protected abstract T job(Resources resources);

    /**
     * Runs {@link #job} against a {@code Resources} configured for {@code locale}.
     *
     * <p>This is the preferred entry point: it derives a throw-away configuration context instead
     * of mutating the shared process-wide {@code Resources}, so a concurrent resource read on
     * another thread cannot observe the swapped locale, and no global lock is taken.
     */
    public T runInLocale(Context context, Locale locale) {
        final Resources resources = context.getResources();
        if (locale == null || locale.equals(LocaleUtils.getConfigurationLocale(resources))) {
            return job(resources);
        }
        final Configuration configuration = new Configuration(resources.getConfiguration());
        configuration.setLocale(locale);
        return job(context.createConfigurationContext(configuration).getResources());
    }

    /**
     * @deprecated Globally re-configures the shared {@code Resources} for the duration of the
     *             callback under a process-wide lock. Use {@link #runInLocale(Context, Locale)}.
     */
    @Deprecated
    public T runInLocale(Resources resources, Locale locale) {
        synchronized (sLockForRunInLocale) {
            Configuration configuration = resources.getConfiguration();
            if (locale != null && !locale.equals(LocaleUtils.getConfigurationLocale(resources))) {
                Locale locale2 = LocaleUtils.getConfigurationLocale(resources);
                try {
                    configuration.setLocale(locale);
                    resources.updateConfiguration(configuration, null);
                    return job(resources);
                } finally {
                    configuration.setLocale(locale2);
                    resources.updateConfiguration(configuration, null);
                }
            }
            return job(resources);
        }
    }
}

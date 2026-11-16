package dev.bbkb.ime.core.settings;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;

/**
 * First-run setup gate. The setup checklist is shown once, when the settings database is brand new,
 * and then never again once it is completed or skipped.
 *
 * <p>"Brand new" is a preferences store with no keys of its own: a fresh install, before any setting
 * has been written. An upgrade already has a populated store, so it is not onboarded. The check must
 * run before anything else writes a preference, or the store is no longer empty.
 */
public final class SetupState {

    private static final String KEY_COMPLETED = "pref_setup_completed";

    private SetupState() {
    }

    /**
     * Whether the first-run setup checklist should open as the start destination. True only for a
     * brand-new preferences store that has not completed or skipped setup.
     */
    public static boolean shouldShowSetup(Context context) {
        final SharedPreferences prefs = PrefsManager.INSTANCE.getPrefs(context);
        if (prefs.getBoolean(KEY_COMPLETED, false)) {
            return false;
        }
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (!KEY_COMPLETED.equals(entry.getKey())) {
                // A key other than this gate's own: the store predates setup (an upgrade).
                return false;
            }
        }
        return true;
    }

    /** Records that setup was completed or skipped, so it does not open again. */
    public static void markCompleted(Context context) {
        PrefsManager.INSTANCE.getPrefs(context).edit().putBoolean(KEY_COMPLETED, true).apply();
    }
}

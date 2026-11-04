package dev.bbkb.ime.core;

import android.app.Application;

import dev.bbkb.ime.core.settings.backup.AutoBackup;
import dev.bbkb.ime.core.shared.StartupTiming;


public class ImeApplication extends Application {

    private static ImeApplication sInstance;

    public static ImeApplication getInstance() {
        return sInstance;
    }

    @Override // android.app.Application
    public void onCreate() {
        // Debug builds: the fallback process-start reference for the IME's cold-start timing
        // (StartupTiming.markSinceProcessStart) on API 23. A no-op in release.
        StartupTiming.applicationCreated();
        super.onCreate();
        sInstance = this;
        // A bundle Android restored with the app's data (same Google account, new phone) is
        // applied on the first start after it: settings and layouts now, words once the keyboard
        // has its engine. Returns at once when there is none, which is every other start.
        AutoBackup.applyPendingRestore(this);
    }
}

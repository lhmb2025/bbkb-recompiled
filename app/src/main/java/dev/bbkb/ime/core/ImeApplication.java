package dev.bbkb.ime.core;

import android.app.Application;

import dev.bbkb.ime.core.settings.backup.AutoBackup;


public class ImeApplication extends Application {

    private static ImeApplication sInstance;

    public static ImeApplication getInstance() {
        return sInstance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        // Applies a bundle Android restored (settings now, words once the engine is up); no-op otherwise.
        AutoBackup.applyPendingRestore(this);
    }
}

package dev.bbkb.ime.core;

import android.app.Application;

import dev.bbkb.ime.core.settings.backup.AutoBackup;


public class ImeApplication extends Application {

    private static ImeApplication sInstance;

    public static ImeApplication getInstance() {
        return sInstance;
    }

    @Override // android.app.Application
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        // A bundle Android restored with the app's data (same Google account, new phone) is
        // applied on the first start after it: settings and layouts now, words once the keyboard
        // has its engine. Returns at once when there is none, which is every other start.
        AutoBackup.applyPendingRestore(this);
    }
}

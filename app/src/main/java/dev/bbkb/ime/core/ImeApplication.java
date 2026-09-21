package dev.bbkb.ime.core;

import android.app.Application;


public class ImeApplication extends Application {

    private static ImeApplication sInstance;

    public static ImeApplication getInstance() {
        return sInstance;
    }

    @Override // android.app.Application
    public void onCreate() {
        super.onCreate();
        sInstance = this;
    }
}

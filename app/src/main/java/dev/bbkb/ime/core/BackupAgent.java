package dev.bbkb.ime.core;

import android.app.backup.BackupAgentHelper;
import android.app.backup.SharedPreferencesBackupHelper;


public final class BackupAgent extends BackupAgentHelper {
    @Override // android.app.backup.BackupAgent
    public void onCreate() {
        addHelper("blackberry_keyboard_shared_prefs", new SharedPreferencesBackupHelper(this, "shared_pref", getPackageName() + "_preferences"));
    }
}

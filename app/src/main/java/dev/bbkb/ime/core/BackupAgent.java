package dev.bbkb.ime.core;

import android.app.backup.BackupAgentHelper;
import android.app.backup.BackupDataInput;
import android.app.backup.BackupDataOutput;
import android.app.backup.FileBackupHelper;
import android.os.ParcelFileDescriptor;

import java.io.IOException;

import dev.bbkb.ime.core.settings.backup.AutoBackup;

/**
 * BBKB's part in the phone's own backup: one file, the backup bundle the keyboard keeps fresh
 * ({@link AutoBackup#AUTO_BUNDLE_PATH}), carried by Android's key/value backup.
 *
 * <p>This used to back up the default preference file directly. That carried the preferences
 * alone — no letter maps, dictionary, shortcuts or learned words — and carried the device-specific
 * keys the settings backup leaves out on purpose. The bundle holds all three kinds of data in the
 * formats the Backup and restore screen writes and reads, and {@link AutoBackup} applies it on
 * the first start after a restore with the same rules a manual restore follows.
 */
public final class BackupAgent extends BackupAgentHelper {

    private static final String KEY_BUNDLE = "bbkb_backup_bundle";

    @Override // android.app.backup.BackupAgent
    public void onCreate() {
        addHelper(KEY_BUNDLE, new FileBackupHelper(this, AutoBackup.AUTO_BUNDLE_PATH));
    }

    @Override // android.app.backup.BackupAgentHelper
    public void onBackup(ParcelFileDescriptor oldState, BackupDataOutput data,
                         ParcelFileDescriptor newState) throws IOException {
        AutoBackup.prepareForPhoneBackup(this);
        super.onBackup(oldState, data, newState);
        AutoBackup.onPhoneBackupWritten(this);
    }

    @Override // android.app.backup.BackupAgentHelper
    public void onRestore(BackupDataInput data, int appVersionCode,
                          ParcelFileDescriptor newState) throws IOException {
        super.onRestore(data, appVersionCode, newState);
    }

    @Override // android.app.backup.BackupAgent
    public void onRestoreFinished() {
        AutoBackup.onPhoneRestoreFinished(this);
    }
}

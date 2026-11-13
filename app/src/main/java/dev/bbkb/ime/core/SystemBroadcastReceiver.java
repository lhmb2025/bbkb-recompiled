package dev.bbkb.ime.core;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.blackberry.nuanceshim.languagepack.LanguagePackManager;
import dev.bbkb.ime.core.shared.Logger;
import dev.bbkb.ime.keyboard.KeyboardBuilder;
import dev.bbkb.ime.core.locale.RichInputMethodManager;
import dev.bbkb.ime.core.update.UpdateJobService;


/**
 * Receiver for {@code MY_PACKAGE_REPLACED}, {@code BOOT_COMPLETED}, {@code USER_INITIALIZE},
 * {@code LOCALE_CHANGED} and our own language-pack update action.
 *
 * <p>Never kill the process here: a kill inside {@code onReceive} leaves the broadcast unacknowledged
 * so it is re-delivered in a loop, and Settings and pack downloads share this process.
 */
public final class SystemBroadcastReceiver extends BroadcastReceiver {

    private static final String TAG = "SystemBroadcastReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if ("android.intent.action.MY_PACKAGE_REPLACED".equals(action)) {
            Logger.info(TAG, "Package has been replaced: " + context.getPackageName());
            if (RichInputMethodManager.isInitialized()) {
                RichInputMethodManager c0910yM5863a = RichInputMethodManager.getInstance();
                c0910yM5863a.setAdditionalInputMethodSubtypes(c0910yM5863a.getAdditionalSubtypes(context));
            }
            LanguagePackManager.getInstance(context).forceBootComplete();
            // Re-sync so the daily check exists on an install that has never been rebooted.
            UpdateJobService.sync(context);
        } else if ("android.intent.action.BOOT_COMPLETED".equals(action)) {
            Logger.info(TAG, "Boot has been completed");
            LanguagePackManager.getInstance(context).onBootCompleted(context);
            // The job is persisted; syncing covers a preference changed since the last boot.
            // Idempotent, and schedules nothing on a build with no update channel.
            UpdateJobService.sync(context);
        } else if ("android.intent.action.LOCALE_CHANGED".equals(action)) {
            Logger.info(TAG, "System locale changed");
            KeyboardBuilder.clearKeyboardCache();
        }
    }
}

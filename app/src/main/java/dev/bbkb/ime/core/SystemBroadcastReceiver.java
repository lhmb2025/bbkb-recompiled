package dev.bbkb.ime.core;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.view.inputmethod.InputMethodManager;

import com.blackberry.nuanceshim.languagepack.LanguagePackManager;
import dev.bbkb.ime.core.shared.Logger;
import dev.bbkb.ime.core.shared.UncachedInputMethodManagerUtils;
import dev.bbkb.ime.keyboard.KeyboardBuilder;
import dev.bbkb.ime.core.locale.RichInputMethodManager;


/**
 * Receiver for {@code MY_PACKAGE_REPLACED}, {@code BOOT_COMPLETED}, {@code USER_INITIALIZE},
 * {@code LOCALE_CHANGED} and our own language-pack update action.
 *
 * <p><b>Why there is no {@code Process.killProcess} here any more (2026-09-21).</b> The original
 * app ended every {@code onReceive} with
 * {@code if (!isThisImeEnabled(...)) Process.killProcess(Process.myPid())} - a 2018-era way to
 * give back a process the system had only spun up to deliver a broadcast. Two things make that
 * wrong today:
 * <ul>
 *   <li>The kill happened <em>inside</em> {@code onReceive}, so the broadcast was never
 *       acknowledged and the system re-delivered it to each freshly started process. On the
 *       owner's KEY2 the process died three times in three seconds on {@code BOOT_COMPLETED}
 *       while the default IME was AOSP LatinIME.</li>
 *   <li>Settings ({@code ComposeSettingsActivity}) and the language-pack downloader live in this
 *       same process, so a {@code LOCALE_CHANGED} arriving while the user is configuring a
 *       not-yet-enabled keyboard would take their screen - and any in-flight pack download -
 *       down with it.</li>
 * </ul>
 * A process that is only holding a receiver is already the first thing the low-memory killer
 * reclaims, so nothing has to be done by hand. The enabled check is kept, at INFO, purely as a
 * breadcrumb; {@link UncachedInputMethodManagerUtils#isThisImeEnabled} now genuinely means
 * "enabled" rather than "is the selected default".
 */
public final class SystemBroadcastReceiver extends BroadcastReceiver {

    private static final String TAG = "SystemBroadcastReceiver";

    @Override // android.content.BroadcastReceiver
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if ("android.intent.action.MY_PACKAGE_REPLACED".equals(action)) {
            Logger.info(TAG, "Package has been replaced: " + context.getPackageName());
            // REMOVED: setOnBoardNotification - Tutorial system eliminated
            if (RichInputMethodManager.isInitialized()) {
                RichInputMethodManager c0910yM5863a = RichInputMethodManager.getInstance();
                c0910yM5863a.setAdditionalInputMethodSubtypes(c0910yM5863a.getAdditionalSubtypes(context));
            }
            LanguagePackManager.getInstance(context).forceBootComplete();
        } else if ("android.intent.action.BOOT_COMPLETED".equals(action)) {
            Logger.info(TAG, "Boot has been completed");
            LanguagePackManager.getInstance(context).onBootCompleted(context);
        } else if ("android.intent.action.LOCALE_CHANGED".equals(action)) {
            Logger.info(TAG, "System locale changed");
            KeyboardBuilder.clearKeyboardCache();
        }
        final InputMethodManager inputMethodManager =
                (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (!UncachedInputMethodManagerUtils.isThisImeEnabled(context, inputMethodManager)) {
            Logger.info(TAG, "This IME is not enabled; leaving the process to the system ("
                    + action + ")");
        }
    }
}

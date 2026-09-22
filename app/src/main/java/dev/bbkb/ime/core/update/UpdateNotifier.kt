package dev.bbkb.ime.core.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.bbkb.ime.R
import dev.bbkb.ime.core.distribution.AppBuild
import dev.bbkb.ime.core.settings.ComposeSettingsActivity
import dev.bbkb.ime.core.settings.SettingsRoute
import dev.bbkb.ime.core.shared.Logger

/**
 * The one notification this feature posts: "a newer BBKB is published".
 *
 * Low importance, no sound, no vibration — it is an offer, not an event. Tapping it opens the
 * Updates screen, where downloading and installing are the user's own two taps; nothing about an
 * update ever happens behind their back.
 *
 * On API 33+ posting needs the runtime `POST_NOTIFICATIONS` grant. Without it [notifyAvailable]
 * returns `false` and posts nothing — **silently**, because the daily check runs with no UI to
 * explain itself. The Updates screen is where the permission is asked for, and its own banner
 * still shows the update, so a denied grant costs the notification and nothing else.
 */
object UpdateNotifier {

    private const val TAG = "UpdateNotifier"

    /** Notification channel id. Created on demand; low importance so it never interrupts. */
    const val CHANNEL_ID: String = "updates"

    /** One notification, replaced in place when a newer version supersedes it. */
    const val NOTIFICATION_ID: Int = 4801

    /**
     * Post (or replace) the "update available" notification.
     *
     * @return whether it went out. `false` means the `POST_NOTIFICATIONS` grant is missing or
     *   notifications are disabled for the app — the caller must **not** record the version as
     *   seen in that case, or granting the permission later would never produce a notification.
     */
    fun notifyAvailable(context: Context, build: AppBuild): Boolean {
        if (!canPostNotifications(context)) {
            Logger.info(TAG, "Update ${build.versionName} found; no notification permission")
            return false
        }
        ensureChannel(context)

        val intent = Intent(context, ComposeSettingsActivity::class.java)
            .putExtra("screen", SettingsRoute.Updates.route)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_update)
            .setContentTitle(context.getString(R.string.settings_update_available_title))
            .setContentText(
                context.getString(R.string.settings_update_notification_text, build.versionName)
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(pending)
            .build()

        return try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            true
        } catch (denied: SecurityException) {
            // The grant can be revoked between the check above and the post.
            Logger.info(TAG, "Notification refused: ${denied.message}")
            false
        }
    }

    /** Take the notification down — used once the user has the Updates screen in front of them. */
    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    /** Whether a notification would actually be shown if we posted one. */
    fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** The "Updates" channel, created idempotently. No-op below API 26. */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.settings_updates_title),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.settings_updates_summary)
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }
}

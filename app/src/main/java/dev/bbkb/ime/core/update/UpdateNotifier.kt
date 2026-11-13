package dev.bbkb.ime.core.update

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.bbkb.ime.R
import dev.bbkb.ime.core.distribution.AppBuild
import dev.bbkb.ime.core.settings.ComposeSettingsActivity
import dev.bbkb.ime.core.settings.SettingsRoute
import dev.bbkb.ime.core.shared.Logger

/**
 * The "a newer BBKB is published" notification: low importance and silent; a tap opens the Updates
 * screen. Without `POST_NOTIFICATIONS` (API 33+) [notifyAvailable] silently posts nothing; the
 * Updates screen asks for the grant and still shows the update.
 */
object UpdateNotifier {

    private const val TAG = "UpdateNotifier"

    /** Created on demand, low importance. */
    const val CHANNEL_ID: String = "updates"

    /** One notification, replaced in place. */
    const val NOTIFICATION_ID: Int = 4801

    /**
     * @return false when the grant is missing or notifications are off; the caller must not mark the
     *   version seen then, or a later grant would never produce a notification
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

    /** Used once the Updates screen is in front of the user. */
    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun ensureChannel(context: Context) {
        val channel = NotificationChannelCompat.Builder(
            CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_LOW,
        )
            .setName(context.getString(R.string.settings_updates_title))
            .setDescription(context.getString(R.string.settings_updates_summary))
            .setSound(null, null)
            .setVibrationEnabled(false)
            .build()
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }
}

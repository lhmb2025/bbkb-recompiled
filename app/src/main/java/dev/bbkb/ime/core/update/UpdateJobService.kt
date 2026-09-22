package dev.bbkb.ime.core.update

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.shared.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The one background thing this feature does: look once a day, and if there is a newer BBKB that
 * the user has not been told about, post a notification.
 *
 * **Notify only.** It never downloads and never installs — both of those are user-initiated, from
 * the Updates screen, every time. So the job's entire budget is one conditional HTTPS GET of a
 * small JSON file (usually a `304`, because `ManifestSource` sends the `ETag` and the cache is
 * six hours fresh), which is why `force = false`.
 *
 * Scheduling rules, all enforced by [sync]:
 *
 *  - **periodic ~24 h**, `setPersisted(true)` so it survives a reboot, and re-scheduled from
 *    `SystemBroadcastReceiver`'s `BOOT_COMPLETED` path anyway — a persisted job is restored by
 *    the framework, but a user who has never rebooted since installing gets it there too, and
 *    `sync` is idempotent;
 *  - **any network** — the check is kilobytes, so there is no reason to demand unmetered;
 *  - gated on [PREF_BACKGROUND_CHECK] (default on) **and** on there being a channel at all, so a
 *    debug build with no `pref_distribution_channel_override` schedules nothing. Turning the
 *    preference off cancels the job rather than letting it run and return early.
 *
 * No WorkManager: the app has no such dependency and this does not justify adding one.
 */
class UpdateJobService : JobService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        if (!isEnabled(applicationContext)) {
            // The preference was turned off (or the channel went away) since this was scheduled.
            cancel(applicationContext)
            return false
        }
        running = scope.launch {
            val checker = UpdateChecker(applicationContext)
            when (val status = checker.check(force = false)) {
                is UpdateStatus.Available -> {
                    if (checker.shouldNotify(status)) {
                        if (UpdateNotifier.notifyAvailable(applicationContext, status.build)) {
                            // Only once the notification actually went out; see UpdateNotifier.
                            checker.markSeen(status.build.versionCode)
                        }
                    }
                }
                is UpdateStatus.Failed ->
                    Logger.info(TAG, "Daily update check failed: ${status.error.message}")
                else -> Unit
            }
            // Never reschedule on failure: the next periodic run is at most a day away and a
            // retry now would only burn battery on a phone that is still offline.
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        running?.cancel()
        // The periodic job comes back on its own schedule; nothing here needs an immediate retry.
        return false
    }

    companion object {

        private const val TAG = "UpdateJobService"

        /** Whether to run the daily background check at all. Default on. */
        const val PREF_BACKGROUND_CHECK: String = "pref_update_background_check"

        /** JobScheduler id. Fixed, so [sync] replaces rather than stacks. */
        const val JOB_ID: Int = 4801

        /** Nominal period. JobScheduler treats it as a window, not a promise. */
        const val INTERVAL_MS: Long = 24L * 60L * 60L * 1000L

        /** Whether the background check is switched on *and* has something to check. */
        @JvmStatic
        fun isEnabled(context: Context): Boolean {
            val on = try {
                PrefsManager.getPrefs(context).getBoolean(PREF_BACKGROUND_CHECK, true)
            } catch (unavailable: RuntimeException) {
                // Direct boot / early startup: treat as the default rather than throwing into a
                // broadcast receiver.
                true
            }
            return on && UpdateChecker.hasUpdateChannel(context)
        }

        /**
         * Bring the scheduled state in line with the preferences: schedule when
         * [isEnabled], cancel when not. Idempotent — safe to call on boot, on package replace,
         * and every time the toggle moves.
         *
         * @return whether the job is scheduled afterwards.
         */
        @JvmStatic
        fun sync(context: Context): Boolean {
            return if (isEnabled(context)) {
                schedule(context)
            } else {
                cancel(context)
                false
            }
        }

        /** Schedule (or replace) the periodic job, ignoring the preference gate. */
        @JvmStatic
        fun schedule(context: Context): Boolean {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
            val job = JobInfo.Builder(
                JOB_ID,
                ComponentName(context.packageName, UpdateJobService::class.java.name)
            )
                .setPeriodic(INTERVAL_MS)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build()
            val result = try {
                scheduler.schedule(job)
            } catch (refused: IllegalArgumentException) {
                // A persisted job needs RECEIVE_BOOT_COMPLETED; the manifest declares it, but do
                // not let a scheduling refusal take down a settings toggle or a boot receiver.
                Logger.info(TAG, "Could not schedule the update check: ${refused.message}")
                JobScheduler.RESULT_FAILURE
            }
            return result == JobScheduler.RESULT_SUCCESS
        }

        /** Cancel the periodic job if it is scheduled. */
        @JvmStatic
        fun cancel(context: Context) {
            context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
        }

        /** Whether the job is currently scheduled. */
        @JvmStatic
        fun isScheduled(context: Context): Boolean {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
            return scheduler.allPendingJobs.any { it.id == JOB_ID }
        }
    }
}

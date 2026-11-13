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
 * Daily check that posts a notification for a newer BBKB the user has not been told about. It never
 * downloads or installs, so its cost is one conditional GET (usually a 304), hence `force = false`.
 * [sync] schedules it periodic (~24 h), persisted, any network, and only while
 * [PREF_BACKGROUND_CHECK] is on and a channel exists; turning the pref off cancels the job.
 * Also re-synced on BOOT_COMPLETED; [sync] is idempotent.
 */
class UpdateJobService : JobService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        if (!isEnabled(applicationContext)) {
            cancel(applicationContext)
            return false
        }
        running = scope.launch {
            val checker = UpdateChecker(applicationContext)
            when (val status = checker.check(force = false)) {
                is UpdateStatus.Available -> {
                    if (checker.shouldNotify(status)) {
                        if (UpdateNotifier.notifyAvailable(applicationContext, status.build)) {
                            // Only once it actually went out (see UpdateNotifier).
                            checker.markSeen(status.build.versionCode)
                        }
                    }
                }
                is UpdateStatus.Failed ->
                    Logger.info(TAG, "Daily update check failed: ${status.error.message}")
                else -> Unit
            }
            // No reschedule on failure: the next run is at most a day away.
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        running?.cancel()
        return false
    }

    companion object {

        private const val TAG = "UpdateJobService"

        /** Default on. */
        const val PREF_BACKGROUND_CHECK: String = "pref_update_background_check"

        /** Fixed, so [sync] replaces rather than stacks. */
        const val JOB_ID: Int = 4801

        /** Nominal period; JobScheduler treats it as a window. */
        const val INTERVAL_MS: Long = 24L * 60L * 60L * 1000L

        /** Switched on and has a channel to check. */
        @JvmStatic
        fun isEnabled(context: Context): Boolean {
            val on = try {
                PrefsManager.getPrefs(context).getBoolean(PREF_BACKGROUND_CHECK, true)
            } catch (unavailable: RuntimeException) {
                // Direct boot / early startup: assume the default rather than throw into a receiver.
                true
            }
            return on && UpdateChecker.hasUpdateChannel(context)
        }

        /** Schedules when [isEnabled], cancels otherwise; returns whether it is scheduled afterwards. */
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
                // Never let a scheduling refusal take down a settings toggle or the boot receiver.
                Logger.info(TAG, "Could not schedule the update check: ${refused.message}")
                JobScheduler.RESULT_FAILURE
            }
            return result == JobScheduler.RESULT_SUCCESS
        }

        @JvmStatic
        fun cancel(context: Context) {
            context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
        }

        @JvmStatic
        fun isScheduled(context: Context): Boolean {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
            return scheduler.allPendingJobs.any { it.id == JOB_ID }
        }
    }
}

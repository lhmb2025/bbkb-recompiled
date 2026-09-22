package dev.bbkb.ime.core.update

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.distribution.DistributionConfig
import dev.bbkb.ime.core.settings.PrefsManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What [UpdateJobService.sync] schedules, and what it refuses to.
 *
 * The tests run against the **debug** `BuildConfig`, so the honest default state of this app under
 * test is "no update channel" and therefore "no job" — which is itself one of the properties worth
 * pinning. `pref_distribution_channel_override` is how the other half is reached, exactly as a
 * developer previewing the release channel on a debug build would set it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateJobSchedulingTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PrefsManager.getPrefs(context).edit().clear().commit()
        UpdateJobService.cancel(context)
    }

    @After
    fun tearDown() {
        UpdateJobService.cancel(context)
        PrefsManager.getPrefs(context).edit().clear().commit()
    }

    private fun scheduler(): JobScheduler = context.getSystemService(JobScheduler::class.java)

    private fun pendingJob(): JobInfo? =
        scheduler().allPendingJobs.firstOrNull { it.id == UpdateJobService.JOB_ID }

    private fun previewTheReleaseChannel() {
        PrefsManager.getPrefs(context).edit()
            .putString(DistributionConfig.PREF_CHANNEL_OVERRIDE, "release")
            .commit()
    }

    @Test
    fun aBuildWithNoUpdateChannelSchedulesNothing() {
        assertFalse("a debug build has no channel to check", UpdateJobService.isEnabled(context))
        assertFalse(UpdateJobService.sync(context))
        assertFalse(UpdateJobService.isScheduled(context))
    }

    @Test
    fun theBackgroundCheckIsOnByDefaultOnceThereIsAChannel() {
        previewTheReleaseChannel()
        assertTrue("default ON", UpdateJobService.isEnabled(context))
        assertTrue(UpdateJobService.sync(context))
        assertTrue(UpdateJobService.isScheduled(context))
    }

    @Test
    fun theScheduledJobIsDailyPersistedAndNeedsAnyNetwork() {
        previewTheReleaseChannel()
        UpdateJobService.sync(context)
        val job = pendingJob()
        assertTrue("nothing was scheduled", job != null)
        assertEquals(UpdateJobService.INTERVAL_MS, job!!.intervalMillis)
        assertTrue("must survive a reboot", job.isPersisted)
        assertEquals(JobInfo.NETWORK_TYPE_ANY, job.networkType)
        assertEquals(
            UpdateJobService::class.java.name,
            job.service.className
        )
    }

    @Test
    fun turningThePreferenceOffCancelsTheJob() {
        previewTheReleaseChannel()
        UpdateJobService.sync(context)
        assertTrue(UpdateJobService.isScheduled(context))

        PrefsManager.getPrefs(context).edit()
            .putBoolean(UpdateJobService.PREF_BACKGROUND_CHECK, false)
            .commit()
        assertFalse(UpdateJobService.isEnabled(context))
        assertFalse(UpdateJobService.sync(context))
        assertFalse("the job must go, not just return early", UpdateJobService.isScheduled(context))
    }

    @Test
    fun syncIsIdempotentSoBootAndTheToggleCanBothCallIt() {
        previewTheReleaseChannel()
        UpdateJobService.sync(context)
        UpdateJobService.sync(context)
        UpdateJobService.sync(context)
        assertEquals(
            "a fixed job id must replace rather than stack",
            1,
            scheduler().allPendingJobs.count { it.id == UpdateJobService.JOB_ID }
        )
    }

    @Test
    fun theIntervalIsADay() {
        assertEquals(24L * 60L * 60L * 1000L, UpdateJobService.INTERVAL_MS)
    }
}

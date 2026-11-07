package dev.bbkb.ime.core.settings.screens

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * "Phone backup settings" opens Android's own Backup page first, which forwards to whichever
 * backup service is on (Google, Seedvault, ...); Google's page and the top of Settings are only
 * fallbacks (owner request 2026-11-07: the section was Google-only).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhoneBackupSettingsLinkTest {

    private lateinit var app: Application

    private val androidBackup = ComponentName("com.android.settings", "com.android.settings.backup.UserBackupSettingsActivity")
    private val olderAndroidBackup = ComponentName("com.android.settings", "com.android.settings.backup.BackupSettingsActivity")
    private val googleBackup = ComponentName("com.google.android.gms", "com.google.android.gms.backup.component.BackupSettingsActivity")
    private val settingsRoot = ComponentName("com.android.settings", "com.android.settings.Settings")

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        // Starting an activity nothing provides throws, as on a phone.
        shadowOf(app).checkActivities(true)
    }

    private fun provide(component: ComponentName, action: String? = null) {
        val pm = shadowOf(app.packageManager)
        pm.addOrUpdateActivity(ActivityInfo().apply {
            packageName = component.packageName
            name = component.className
            exported = true
        })
        if (action != null) {
            pm.addIntentFilterForActivity(component, IntentFilter(action).apply { addCategory(Intent.CATEGORY_DEFAULT) })
        }
    }

    private fun opened(): Intent? = shadowOf(app).nextStartedActivity

    @Test
    fun theOrderIsAndroidsPageThenItsOlderNameThenGoogleThenSettings() {
        val intents = phoneBackupSettingsIntents()
        assertEquals(androidBackup, intents[0].component)
        assertEquals("android.settings.BACKUP_AND_RESET_SETTINGS", intents[1].action)
        assertEquals(googleBackup, intents[2].component)
        assertEquals(Settings.ACTION_SETTINGS, intents[3].action)
        assertEquals(4, intents.size)
    }

    @Test
    fun androidsOwnPageWinsEvenWithPlayServices() {
        provide(androidBackup)
        provide(googleBackup)
        openPhoneBackupSettings(app)
        assertEquals(androidBackup, opened()!!.component)
    }

    @Test
    fun androidEightReachesThePageByItsAction() {
        provide(olderAndroidBackup, "android.settings.BACKUP_AND_RESET_SETTINGS")
        provide(googleBackup)
        openPhoneBackupSettings(app)
        assertEquals("android.settings.BACKUP_AND_RESET_SETTINGS", opened()!!.action)
    }

    @Test
    fun aSettingsAppWithoutThePageFallsBackToGoogleThenSettings() {
        provide(googleBackup)
        provide(settingsRoot, Settings.ACTION_SETTINGS)
        openPhoneBackupSettings(app)
        assertEquals(googleBackup, opened()!!.component)

        shadowOf(app.packageManager).removeActivity(googleBackup)
        openPhoneBackupSettings(app)
        assertEquals(Settings.ACTION_SETTINGS, opened()!!.action)
        assertNull(opened())
    }
}

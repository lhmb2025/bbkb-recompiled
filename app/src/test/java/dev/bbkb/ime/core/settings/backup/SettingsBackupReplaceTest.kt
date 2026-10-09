package dev.bbkb.ime.core.settings.backup

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [SettingsBackup.apply] in replace mode — what a bundle restore uses: the phone ends up with
 * exactly the backup's settings, while the per-device keys and the keys the caller keeps stay.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SettingsBackupReplaceTest {

    private lateinit var source: SharedPreferences
    private lateinit var target: SharedPreferences

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        source = context.getSharedPreferences("replace_source", Context.MODE_PRIVATE)
        target = context.getSharedPreferences("replace_target", Context.MODE_PRIVATE)
        source.edit().clear().putBoolean("auto_cap", false).putString("control_mode", "1").commit()
        target.edit().clear()
            .putBoolean("auto_cap", true)                      // in the backup: overwritten
            .putInt("vibration_ms", 40)                        // not in the backup: back to default
            .putString("active_device_config_id", "custom:k")  // per-device: kept
            .putString("pref_update_last_check", "yesterday")  // per-device prefix: kept
            .putString("pref_currency_key", "€")               // a kept key (the Layouts part's)
            .commit()
    }

    private fun backup() = SettingsBackup.parse(SettingsBackup.serialize(source, "t", 1)).getOrThrow()

    @Test
    fun replaceRemovesSettingsTheBackupDoesNotNameAndKeepsTheRest() {
        val written = SettingsBackup.apply(target, backup(), replace = true, keepKeys = setOf("pref_currency_key"))

        assertEquals(2, written)
        assertEquals(false, target.getBoolean("auto_cap", true))
        assertEquals("1", target.getString("control_mode", null))
        assertFalse("a setting absent from the backup goes back to its default", target.contains("vibration_ms"))
        assertEquals("custom:k", target.getString("active_device_config_id", null))
        assertEquals("yesterday", target.getString("pref_update_last_check", null))
        assertEquals("€", target.getString("pref_currency_key", null))
    }

    @Test
    fun mergeStillLeavesOtherSettingsAlone() {
        SettingsBackup.apply(target, backup())
        assertTrue(target.contains("vibration_ms"))
        assertEquals(false, target.getBoolean("auto_cap", true))
    }
}

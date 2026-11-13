package dev.bbkb.ime.core.settings.util

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.textinput.connection.EditorCapabilities
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale

/** A burst of preference changes costs one SettingsValues rebuild and one notification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SettingsManagerCoalescingTest {

    private lateinit var context: Context
    private val manager get() = SettingsManager.getInstance()
    private val notified = mutableListOf<SettingsValues>()
    private val listener = SettingsManager.OnSettingsChangeListener { notified += it }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs().edit().clear().commit()
        DeviceProfile.clearPendingInitForTest()
        DeviceProfile.initialize(null)
        SettingsManager.initialize(context)
        manager.loadSettings(context, Locale.US, EditorCapabilities(null, false, context.packageName, Locale.US, false))
        manager.addOnSettingsChangeListener(listener)
        shadowOf(Looper.getMainLooper()).idle()
        notified.clear()
    }

    @After
    fun tearDown() {
        // Same reset as SettingsValuesSnapshotTest: the singleton outlives this class.
        runCatching { manager.unregisteredListener() }
        listOf("settingsValues", "sharedPreferences", "deviceProfile", "listeners").forEach {
            SettingsManager::class.java.getDeclaredField(it).apply { isAccessible = true }.set(manager, null)
        }
        SettingsManager::class.java.getDeclaredField("rebuildPending").apply { isAccessible = true }.setBoolean(manager, false)
        prefs().edit().clear().commit()
        DeviceProfile.clearPendingInitForTest()
    }

    private fun prefs() = PrefsManager.getPrefs(context)

    /** The built object, without the rebuild a read would trigger. */
    private fun built(): SettingsValues =
        SettingsManager::class.java.getDeclaredField("settingsValues").apply { isAccessible = true }.get(manager) as SettingsValues

    @Test
    fun aBurstIsRebuiltOnceAndNotifiedOnce() {
        val before = built()
        prefs().edit()
            .putBoolean("show_predictions", false)
            .putString("pref_keyboard_height_mode", "compact")
            .putBoolean("sound_on", true)
            .commit()
        // Nothing rebuilt per key.
        assertSame(before, built())
        assertTrue(notified.isEmpty())

        shadowOf(Looper.getMainLooper()).idle()
        val after = built()
        assertNotSame(before, after)
        assertEquals(false, after.isPredictionsEnabled)
        assertEquals("compact", after.keyboardHeightMode)
        assertEquals(listOf(after), notified)
    }

    @Test
    fun aReadStraightAfterAWriteSeesTheWrite() {
        prefs().edit().putString("pref_keyboard_height_mode", "expanded").commit()
        val read = manager.settingsValues
        assertEquals("expanded", read.keyboardHeightMode)
        // The posted rebuild finds nothing left to do and only notifies.
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(read, built())
        assertEquals(listOf(read), notified)
    }

    @Test
    fun keysSettingsValuesNeverReadsDoNotRebuild() {
        val before = built()
        prefs().edit()
            .putLong("pref_update_last_check_ms", 123L)
            .putString("emoji_recent_keys", "x")
            .commit()
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(before, manager.settingsValues)
        assertTrue(notified.isEmpty())
    }

    @Test
    fun keyFilter() {
        assertTrue(SettingsManager.isReadBySettingsValues(null))
        assertTrue(SettingsManager.isReadBySettingsValues("show_predictions"))
        assertFalse(SettingsManager.isReadBySettingsValues("pref_update_available_url"))
        assertFalse(SettingsManager.isReadBySettingsValues("emoji_tone_memory"))
    }
}

package dev.bbkb.ime.core.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.device.config.model.DeviceSettingOverride
import dev.bbkb.ime.core.device.config.model.DeviceSettingOverride.SettingType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ProfileForcedPrefs]: a forced setting reads as its forced value, and nothing else about the
 * stored preferences changes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ProfileForcedPrefsTest {

    private lateinit var stored: SharedPreferences
    private val forced = HashMap<String, DeviceSettingOverride>()
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        stored = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("forced_prefs_test", Context.MODE_PRIVATE)
        stored.edit().clear().commit()
        forced.clear()
        prefs = ProfileForcedPrefs(stored) { forced[it] }
    }

    private fun force(key: String, type: SettingType, value: Any?, readOnly: Boolean = true) {
        forced[key] = DeviceSettingOverride(key, type, value, readOnly)
    }

    @Test
    fun aForcedValue_winsOverTheStoredOneAndTheDefault() {
        stored.edit().putBoolean("swipe", true).putInt("size", 3).putString("mode", "a").commit()
        force("swipe", SettingType.BOOLEAN, false)
        force("size", SettingType.INTEGER, 7)
        force("mode", SettingType.STRING, "b")
        force("unset", SettingType.BOOLEAN, true)

        assertFalse(prefs.getBoolean("swipe", true))
        assertEquals(7, prefs.getInt("size", 0))
        assertEquals("b", prefs.getString("mode", null))
        assertTrue("forced even when nothing is stored", prefs.getBoolean("unset", false))
    }

    @Test
    fun withoutAForcedValue_theStoredOneIsRead() {
        stored.edit().putBoolean("swipe", true).commit()
        assertTrue(prefs.getBoolean("swipe", false))
        force("swipe", SettingType.BOOLEAN, null, readOnly = false)
        assertTrue("an override that only locks or hides forces nothing", prefs.getBoolean("swipe", false))
        assertTrue("an unknown key reads its default", prefs.getBoolean("other", true))
    }

    @Test
    fun nothingIsWritten_soABackupCarriesTheUsersOwnValue() {
        stored.edit().putBoolean("swipe", true).commit()
        force("swipe", SettingType.BOOLEAN, false)

        assertEquals(true, prefs.all["swipe"])
        assertTrue(prefs.contains("swipe"))
        assertFalse(prefs.contains("unset"))
        prefs.edit().putBoolean("other", true).commit()
        assertTrue("edits go to the stored file", stored.getBoolean("other", false))

        forced.clear()
        assertTrue("the user's value is back once the profile stops forcing it",
            prefs.getBoolean("swipe", false))
    }

    @Test
    fun aListener_isHandedThesePreferencesAndCanBeRemoved() {
        force("swipe", SettingType.BOOLEAN, false)
        val seen = ArrayList<Pair<SharedPreferences, String?>>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key -> seen.add(p to key) }

        prefs.registerOnSharedPreferenceChangeListener(listener)
        prefs.edit().putBoolean("swipe", true).commit()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals(1, seen.size)
        assertSame("not the stored file underneath", prefs, seen[0].first)
        assertEquals("swipe", seen[0].second)
        assertFalse("so the listener reads the forced value", seen[0].first.getBoolean("swipe", true))

        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        prefs.edit().putBoolean("swipe", false).commit()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals(1, seen.size)
    }
}

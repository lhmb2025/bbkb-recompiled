package dev.bbkb.ime.core.settings.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.R
import dev.bbkb.ime.core.device.config.parser.DeviceInputMappingParser
import dev.bbkb.ime.core.device.profile.DeviceCapabilities
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.device.profile.DeviceProfileTestSupport
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.textinput.connection.EditorCapabilities
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * A profile's `forced-value` decides what the keyboard does, not only what the Settings switch
 * shows. The stored preference can differ from it (set before the profile applied, or restored
 * from a backup), and the keyboard used to follow the stored one while Settings showed the
 * forced one, locked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ForcedSettingValuesTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs().edit().clear().commit()
        DeviceProfile.clearPendingInitForTest()
        DeviceProfile.initialize(null)
        SettingsManager.initialize(context)
    }

    @After
    fun tearDown() {
        val manager = SettingsManager.getInstance()
        runCatching { manager.unregisteredListener() }
        listOf("settingsValues", "sharedPreferences", "deviceProfile", "listeners").forEach {
            SettingsManager::class.java.getDeclaredField(it)
                .apply { isAccessible = true }.set(manager, null)
        }
        prefs().edit().clear().commit()
        DeviceProfile.initialize(null)
        DeviceProfile.clearPendingInitForTest()
    }

    private fun prefs() = PrefsManager.getPrefs(context)

    private fun installProfile(resId: Int) {
        DeviceProfile.installForTest(DeviceCapabilities.forShape(
            DeviceCapabilities.DetectedDeviceType.PKB, true, false, false, "qwerty", "4row"))
        DeviceProfileTestSupport.installMapping(
            DeviceInputMappingParser.parseConfigFromXmlResource(context, resId).mappings[0])
    }

    private fun load(): SettingsValues {
        val manager = SettingsManager.getInstance()
        manager.loadSettings(context, Locale.US,
            EditorCapabilities(null, false, context.packageName, Locale.US, false))
        return manager.settingsValues
    }

    private val metaStateKey get() = context.getString(R.string.pref_override_device_meta_state_key)

    @Test
    fun minimalPhone_forcedValuesBeatTheStoredOnes() {
        prefs().edit().putBoolean(metaStateKey, false).putBoolean("type_by_swiping_ckb", true).commit()
        installProfile(R.xml.device_config_minimal)
        val settings = load()
        assertEquals(true, settings.overrideDeviceMetaState)
        assertFalse(SettingsManager.isCbkTypeBySwipingEnabled(prefs(), context.resources))
    }

    @Test
    fun aProfileThatForcesNothing_leavesTheStoredValues() {
        prefs().edit().putBoolean(metaStateKey, false).putBoolean("type_by_swiping_ckb", true).commit()
        installProfile(R.xml.device_config_athena)
        val settings = load()
        assertEquals(false, settings.overrideDeviceMetaState)
        assertTrue(SettingsManager.isCbkTypeBySwipingEnabled(prefs(), context.resources))
    }
}

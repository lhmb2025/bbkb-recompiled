package dev.bbkb.ime.core.settings.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.R
import dev.bbkb.ime.core.device.config.parser.DeviceInputMappingParser
import dev.bbkb.ime.core.device.profile.DeviceCapabilities
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.device.profile.DeviceProfileTestSupport
import dev.bbkb.ime.core.layout.PhysicalKeyResolver
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.textinput.connection.EditorCapabilities
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * The touch-keypad swipe gate's device term: off on a pad the engine has no key geometry for (a
 * profile declaring its own `<touch-keypad>` and no `<kdb-variant>`, the Titans), unchanged
 * elsewhere including the KEY2. Same set-up as [CustomLayoutSwipeGateTest]; no layout is active.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class TouchKeypadSwipeGateTest {

    private lateinit var context: Context
    private lateinit var saved: PhysicalKeyResolver.Snapshot

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs().edit().clear().commit()
        DeviceProfile.clearPendingInitForTest()
        DeviceProfile.initialize(null)
        SettingsManager.initialize(context)
        saved = PhysicalKeyResolver.Snapshot()
        PhysicalKeyResolver.chosenLayoutSource = { null }
    }

    @After
    fun tearDown() {
        saved.restore()
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

    private fun swipeTypingOn(): SettingsValues {
        prefs().edit().putBoolean("type_by_swiping_ckb", true).commit()
        val manager = SettingsManager.getInstance()
        manager.loadSettings(context, Locale.US,
            EditorCapabilities(null, false, context.packageName, Locale.US, false))
        return manager.settingsValues
    }

    private fun installProfile(resId: Int?) {
        DeviceProfile.installForTest(DeviceCapabilities.forShape(
            DeviceCapabilities.DetectedDeviceType.PKB, true, true, false, "qwerty", "4row"))
        if (resId != null) {
            DeviceProfileTestSupport.installMapping(
                DeviceInputMappingParser.parseConfigFromXmlResource(context, resId).mappings[0])
        }
    }

    @Test
    fun key2_swipeTypingStaysAsTheUserSetIt() {
        val settings = swipeTypingOn()
        installProfile(R.xml.device_config_athena)
        assertTrue(settings.isCkbGestureInputEnabledForLocale)
        installProfile(null)
        assertTrue("no profile at all (an unknown pad): unchanged", settings.isCkbGestureInputEnabledForLocale)
    }

    @Test
    fun aTitanPadWithoutAKdbVariant_turnsSwipeTypingOff() {
        val settings = swipeTypingOn()
        for (resId in intArrayOf(R.xml.device_config_titan2, R.xml.device_config_titan2_elite,
                R.xml.device_config_titan, R.xml.device_config_titan_pocket,
                R.xml.device_config_titan_slim)) {
            installProfile(resId)
            assertFalse(settings.isCkbGestureInputEnabledForLocale)
        }
    }

    @Test
    fun aTitanPadWithAKdbVariant_leavesItToTheUser() {
        val settings = swipeTypingOn()
        installProfile(R.xml.device_config_titan2)
        DeviceProfile.current().deviceMapping!!.kdbVariant = "titan2"
        assertTrue(settings.isCkbGestureInputEnabledForLocale)
    }

    @Test
    fun switchedOff_staysOff() {
        prefs().edit().putBoolean("type_by_swiping_ckb", false).commit()
        val manager = SettingsManager.getInstance()
        manager.loadSettings(context, Locale.US,
            EditorCapabilities(null, false, context.packageName, Locale.US, false))
        installProfile(R.xml.device_config_athena)
        assertFalse(manager.settingsValues.isCkbGestureInputEnabledForLocale)
    }
}

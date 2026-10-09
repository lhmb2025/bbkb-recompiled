package dev.bbkb.ime.core.settings.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.R
import dev.bbkb.ime.core.device.config.parser.DeviceInputMappingParser
import dev.bbkb.ime.core.device.profile.DeviceCapabilities
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.device.profile.DeviceProfileTestSupport
import dev.bbkb.ime.core.keyevent.HardwareScriptLayouts
import dev.bbkb.ime.core.keyevent.UserLetterMap
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
 * The touch-keypad swipe-typing gate's device term: `SettingsValues.isCkbGestureInputEnabledForLocale`
 * is off on a pad the engine has no key geometry for — a profile that declares its own
 * `<touch-keypad>` (the Titans) and no `<kdb-variant>` — and unchanged everywhere else, the KEY2
 * included. Same set-up as [CustomLayoutSwipeGateTest], which holds the other AND terms.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class TouchKeypadSwipeGateTest {

    private lateinit var context: Context
    private lateinit var savedMapSource: () -> UserLetterMap?

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs().edit().clear().commit()
        DeviceProfile.clearPendingInitForTest()
        DeviceProfile.initialize(null)
        SettingsManager.initialize(context)
        savedMapSource = HardwareScriptLayouts.userMapSource
        HardwareScriptLayouts.userMapSource = { null }
    }

    @After
    fun tearDown() {
        HardwareScriptLayouts.userMapSource = savedMapSource
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

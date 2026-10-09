package dev.bbkb.ime.core.settings.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.device.profile.DeviceProfile
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
 * The touch keypad's type-by-swiping gate, `SettingsValues.isCkbGestureInputEnabledForLocale`,
 * with a custom physical layout switched on: off while the layout changes a letter (the swipe
 * decoder reads the engine's key geometry, which still has the old letters), unchanged otherwise.
 *
 * Every caller that decides whether a keypad swipe types a word reads this one method — the
 * subtype switch and start-of-input refresh of `MainKeyboardView`'s CKB flag, and
 * `BlackBerryIME.shouldHandleGestureEvent` per motion event — so pinning it pins them all.
 *
 * Set up and torn down the way `SettingsValuesSnapshotTest` does, for the reasons it gives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class CustomLayoutSwipeGateTest {

    private lateinit var context: Context
    private lateinit var savedMapSource: () -> UserLetterMap?
    private lateinit var savedLanguageSource: () -> String?
    private lateinit var savedKeypadSource: () -> String?
    private var activeMap: UserLetterMap? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs().edit().clear().commit()
        DeviceProfile.clearPendingInitForTest()
        DeviceProfile.initialize(null)
        SettingsManager.initialize(context)

        savedMapSource = HardwareScriptLayouts.userMapSource
        savedLanguageSource = HardwareScriptLayouts.languageSource
        savedKeypadSource = HardwareScriptLayouts.keypadLayoutSource
        HardwareScriptLayouts.userMapSource = { activeMap }
        HardwareScriptLayouts.languageSource = { "en" }
        HardwareScriptLayouts.keypadLayoutSource = { "qwerty" }
    }

    @After
    fun tearDown() {
        HardwareScriptLayouts.userMapSource = savedMapSource
        HardwareScriptLayouts.languageSource = savedLanguageSource
        HardwareScriptLayouts.keypadLayoutSource = savedKeypadSource

        val manager = SettingsManager.getInstance()
        runCatching { manager.unregisteredListener() }
        listOf("settingsValues", "sharedPreferences", "deviceProfile", "listeners").forEach {
            SettingsManager::class.java.getDeclaredField(it)
                .apply { isAccessible = true }.set(manager, null)
        }
        prefs().edit().clear().commit()
        DeviceProfile.clearPendingInitForTest()
    }

    private fun prefs() = PrefsManager.getPrefs(context)

    private fun swipeTypingOn(): SettingsValues {
        prefs().edit().putBoolean("type_by_swiping_ckb", true).commit()
        val manager = SettingsManager.getInstance()
        manager.loadSettings(
            context,
            Locale.US,
            EditorCapabilities(null, false, context.packageName, Locale.US, false)
        )
        return manager.settingsValues
    }

    private fun map(keys: String, locales: String = "") = UserLetterMap.parse(
        """{"format": "bbkb-layout", "version": 1, "kind": "pkb", "id": "m", "name": "m",
            "bind": {"locales": [$locales], "keypadLayout": "qwerty"}, "keys": $keys}"""
    )

    @Test
    fun noCustomLayout_leavesSwipeTypingAsTheUserSetIt() {
        activeMap = null
        assertTrue(swipeTypingOn().isCkbGestureInputEnabledForLocale)
    }

    @Test
    fun aLayoutThatChangesALetter_turnsSwipeTypingOff() {
        val settings = swipeTypingOn()
        activeMap = map("""{"KEYCODE_Q": {"base": "w"}, "KEYCODE_W": {"base": "q"}}""")
        assertFalse(settings.isCkbGestureInputEnabledForLocale)
        // Evaluated per call: switching the layout off brings swipe typing straight back.
        activeMap = null
        assertTrue(settings.isCkbGestureInputEnabledForLocale)
    }

    @Test
    fun aLayoutThatOnlyAddsAltOrPunctuation_leavesSwipeTypingOn() {
        val settings = swipeTypingOn()
        activeMap = map("""{"KEYCODE_Q": {"base": "q", "alt": "#"}, "KEYCODE_PERIOD": {"base": "·"}}""")
        assertTrue(settings.isCkbGestureInputEnabledForLocale)
    }

    @Test
    fun aLayoutBoundToAnotherKeyboard_leavesSwipeTypingOn() {
        val settings = swipeTypingOn()
        activeMap = map("""{"KEYCODE_Q": {"base": "w"}}""", locales = "\"de\"")
        assertTrue(settings.isCkbGestureInputEnabledForLocale)
    }

    @Test
    fun swipeTypingSwitchedOff_staysOff() {
        prefs().edit().putBoolean("type_by_swiping_ckb", false).commit()
        val manager = SettingsManager.getInstance()
        manager.loadSettings(context, Locale.US, EditorCapabilities(null, false, context.packageName, Locale.US, false))
        activeMap = null
        assertFalse(manager.settingsValues.isCkbGestureInputEnabledForLocale)
    }
}

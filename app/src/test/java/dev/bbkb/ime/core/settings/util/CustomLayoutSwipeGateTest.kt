package dev.bbkb.ime.core.settings.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.layout.LetterLayout
import dev.bbkb.ime.core.layout.LetterLayoutXml
import dev.bbkb.ime.core.layout.PhysicalKeyResolver
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.textinput.connection.EditorCapabilities
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * The touch keypad's swipe gate, `SettingsValues.isCkbGestureInputEnabledForLocale`, stays as the
 * user set it whatever a custom physical layout changes (the engine's letters follow the layout via
 * `LetterLayoutEngineSync`). Every caller deciding whether a keypad swipe types reads this method.
 * Set up and torn down as in `SettingsValuesSnapshotTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class CustomLayoutSwipeGateTest {

    private lateinit var context: Context
    private lateinit var saved: PhysicalKeyResolver.Snapshot
    private var activeMap: LetterLayout? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs().edit().clear().commit()
        DeviceProfile.clearPendingInitForTest()
        DeviceProfile.initialize(null)
        SettingsManager.initialize(context)

        saved = PhysicalKeyResolver.Snapshot()
        PhysicalKeyResolver.chosenLayoutSource = { _ -> activeMap }
        PhysicalKeyResolver.localeSource = { Locale.ENGLISH }
        PhysicalKeyResolver.keypadLayoutSource = { "qwerty" }
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

    private fun map(keys: String, languages: String = "") = LetterLayoutXml.parse(
        """<letter-layout version="1" id="m" name="m">
            <bind languages="$languages" keypad="qwerty"/>
            $keys
        </letter-layout>"""
    )

    @Test
    fun noCustomLayout_leavesSwipeTypingAsTheUserSetIt() {
        activeMap = null
        assertTrue(swipeTypingOn().isCkbGestureInputEnabledForLocale)
    }

    @Test
    fun aLayoutThatChangesLetters_leavesSwipeTypingOn() {
        val settings = swipeTypingOn()
        activeMap = map("""<key code="KEYCODE_Q" keySpec="w"/><key code="KEYCODE_W" keySpec="q"/>""")
        // The layout really drives the keys: the typing path sees it for this keyboard.
        assertNotNull(PhysicalKeyResolver.userLayout())
        assertTrue(settings.isCkbGestureInputEnabledForLocale)
        activeMap = null
        assertTrue(settings.isCkbGestureInputEnabledForLocale)
    }

    @Test
    fun aLayoutThatReplacesEveryLetter_leavesSwipeTypingOn() {
        val settings = swipeTypingOn()
        // Cyrillic on every letter key of an English keyboard: as far from the engine's letters as
        // a layout can get.
        val cyrillic = "йцукенгшщзфывапролдячсмить"
        val qwerty = "QWERTYUIOPASDFGHJKLZXCVBNM"
        activeMap = map(qwerty.indices.joinToString("") { """<key code="KEYCODE_${qwerty[it]}" keySpec="${cyrillic[it]}"/>""" })
        assertNotNull(PhysicalKeyResolver.userLayout())
        assertTrue(settings.isCkbGestureInputEnabledForLocale)
    }

    @Test
    fun aLayoutThatOnlyAddsAltOrPunctuation_leavesSwipeTypingOn() {
        val settings = swipeTypingOn()
        activeMap = map("""<key code="KEYCODE_Q" keySpec="q" alt="#"/><key code="KEYCODE_PERIOD" keySpec="·"/>""")
        assertTrue(settings.isCkbGestureInputEnabledForLocale)
    }

    @Test
    fun aLayoutBoundToAnotherKeyboard_leavesSwipeTypingOn() {
        val settings = swipeTypingOn()
        activeMap = map("""<key code="KEYCODE_Q" keySpec="w"/>""", languages = "de")
        assertTrue(settings.isCkbGestureInputEnabledForLocale)
    }

    @Test
    fun swipeTypingSwitchedOff_staysOffWithOrWithoutALayout() {
        prefs().edit().putBoolean("type_by_swiping_ckb", false).commit()
        val manager = SettingsManager.getInstance()
        manager.loadSettings(context, Locale.US, EditorCapabilities(null, false, context.packageName, Locale.US, false))
        activeMap = null
        assertFalse(manager.settingsValues.isCkbGestureInputEnabledForLocale)
        activeMap = map("""<key code="KEYCODE_Q" keySpec="w"/><key code="KEYCODE_W" keySpec="q"/>""")
        assertFalse(manager.settingsValues.isCkbGestureInputEnabledForLocale)
    }
}

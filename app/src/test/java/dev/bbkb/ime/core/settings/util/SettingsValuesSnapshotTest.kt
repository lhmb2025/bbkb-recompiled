package dev.bbkb.ime.core.settings.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.textinput.connection.EditorCapabilities
import dev.bbkb.ime.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * The settings-load restructure (DeviceProfile built on a worker, the preloaded-config scan
 * memoised, the language-pack catalogue made lazy) touches everything `loadSettings` runs
 * through. `pref_*` keys and their defaults are frozen user state, so this pins the values a
 * `SettingsValues` comes out with — first for an untouched preferences file, then for one the
 * user has actually edited — against literals rather than against "whatever the code does now".
 *
 * A failure here means a stored preference changed meaning, which is the one outcome the startup
 * work was not allowed to have.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SettingsValuesSnapshotTest {

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
        // SettingsManager is a process singleton and Robolectric shares its sandbox classloader
        // between test classes with the same @Config, so an initialized manager outlives this
        // class: its preference-change listener stays registered on the shared prefs instance
        // and fires a full loadSettings() out of the next class's first `edit().commit()`, with
        // whatever EditorCapabilities this class left behind. Hand the singleton back the way it
        // was found.
        val manager = SettingsManager.getInstance()
        runCatching { manager.unregisteredListener() }
        listOf("settingsValues", "sharedPreferences", "deviceProfile", "listeners").forEach {
            SettingsManager::class.java.getDeclaredField(it)
                .apply { isAccessible = true }.set(manager, null)
        }
        prefs().edit().clear().commit()
        DeviceProfile.clearPendingInitForTest()
    }

    private fun load(): SettingsValues {
        val manager = SettingsManager.getInstance()
        manager.loadSettings(
            context,
            Locale.US,
            EditorCapabilities(null, false, context.packageName, Locale.US, false)
        )
        return manager.settingsValues
    }

    private fun bool(id: Int) = context.resources.getBoolean(id)

    /**
     * The same instance SettingsManager reads through. PrefsManager is a process singleton and
     * Robolectric shares its sandbox classloader between test classes, so reaching for
     * PreferenceManager.getDefaultSharedPreferences() here can hand back a *different* file from
     * the one SettingsManager captured.
     */
    private fun prefs() = PrefsManager.getPrefs(context)

    @Test
    fun anUntouchedPreferencesFileLoadsTheShippedDefaults() {
        val sv = load()
        assertNotNull(sv)

        // Resource-backed defaults: the value the shipped config says, read independently of the
        // path SettingsValues took to it.
        assertEquals(bool(R.bool.config_default_show_predictions), sv.isPredictionsEnabled)
        assertEquals(bool(R.bool.config_default_on_key_predictions), sv.isOnKeyPredictionsEnabled)
        assertEquals(bool(R.bool.config_default_emoji_predictions), sv.isEmojiPredictionsEnabled)
        assertEquals(bool(R.bool.config_default_uim_enabled), sv.isUimEnabled)
        assertEquals(bool(R.bool.config_default_flick_commit_animation), sv.flickCommitAnimationEnabled)
        assertEquals(bool(R.bool.config_default_sound_enabled), sv.isSoundEnabled)

        // Hard-coded defaults, which have no resource to compare against and are therefore the
        // ones a refactor can silently move.
        assertEquals("regular", sv.keyboardHeightMode)
        assertEquals("disabled", sv.altSymShortcutAction)
        assertEquals("", sv.multifunctionKeyAction)
        assertEquals(true, sv.slideboardStillBoardsEnabled)
        assertEquals(true, sv.overrideDeviceMetaState)
        assertEquals(true, sv.shiftDoubleTapLock)
        assertEquals(true, sv.altDoubleTapLock)
        assertEquals(true, sv.showPkbModifierStatusIcon)
        assertEquals("en-US", sv.voiceInputLanguageList)
        assertEquals(Locale.US, sv.locale)
    }

    @Test
    fun anEditedPreferencesFileLoadsWhatTheUserStored() {
        prefs().edit()
            .putBoolean("show_predictions", false)
            .putBoolean("emoji_predictions", false)
            .putBoolean("pref_uim_enabled", false)
            .putBoolean("sound_on", true)
            .putString("pref_keyboard_height_mode", "compact")
            .putString("pref_alt_sym_shortcut_action", "switch_language")
            .putString("pref_multifunction_key_action", "emoji")
            .putBoolean("pref_slideboard_still_boards", false)
            .putString("quick_phrase_1", "hello there")
            .commit()

        val sv = load()

        assertEquals(false, sv.isPredictionsEnabled)
        assertEquals(false, sv.isEmojiPredictionsEnabled)
        assertEquals(false, sv.isUimEnabled)
        assertEquals(true, sv.isSoundEnabled)
        assertEquals("compact", sv.keyboardHeightMode)
        assertEquals("switch_language", sv.altSymShortcutAction)
        assertEquals("emoji", sv.multifunctionKeyAction)
        assertEquals(false, sv.slideboardStillBoardsEnabled)
        assertEquals("hello there", sv.quickPhrase1)
    }

    /**
     * `loadSettings` is called repeatedly on the cold path (once from `onCreate`, again from
     * `onStartInputView` with the real editor). Whatever the count turns out to be on hardware,
     * the values must not drift between loads for identical inputs.
     */
    @Test
    fun repeatedLoadsWithTheSameInputsAgreeFieldForField() {
        prefs().edit()
            .putString("pref_keyboard_height_mode", "expanded")
            .putBoolean("show_predictions", false)
            .commit()

        val first = load()
        val second = load()

        assertEquals(first.keyboardHeightMode, second.keyboardHeightMode)
        assertEquals(first.isPredictionsEnabled, second.isPredictionsEnabled)
        assertEquals(first.isUimEnabled, second.isUimEnabled)
        assertEquals(first.isSoundEnabled, second.isSoundEnabled)
        assertEquals(first.altSymShortcutAction, second.altSymShortcutAction)
        assertEquals(first.keypressVibrationDuration, second.keypressVibrationDuration)
        assertEquals(first.keyLongpressTimeoutMs, second.keyLongpressTimeoutMs)
        assertEquals(first.locale, second.locale)
    }

    /**
     * `SettingsManager.loadSettings` also republishes the device profile. With the profile now
     * built on a worker, the one thing that must stay true is that a load never leaves the
     * manager holding a null profile.
     */
    @Test
    fun everyLoadPublishesADeviceProfile() {
        load()
        assertNotNull(SettingsManager.getInstance().deviceProfile)
    }
}

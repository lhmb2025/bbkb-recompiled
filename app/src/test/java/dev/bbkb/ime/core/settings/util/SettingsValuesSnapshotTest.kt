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
 * Pins the values a `SettingsValues` comes out with, for an untouched and an edited preferences
 * file, against literals. `pref_*` keys and defaults are frozen user state, so a failure means a
 * stored preference changed meaning.
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
        // SettingsManager is a process singleton shared across test classes by Robolectric's
        // sandbox; a left-registered listener would fire loadSettings() in the next class's first
        // commit. Hand the singleton back the way it was found.
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
     * The instance SettingsManager reads through: with the shared sandbox classloader,
     * PreferenceManager.getDefaultSharedPreferences() can return a different file.
     */
    private fun prefs() = PrefsManager.getPrefs(context)

    @Test
    fun anUntouchedPreferencesFileLoadsTheShippedDefaults() {
        val sv = load()
        assertNotNull(sv)

        // Resource-backed defaults, read independently of the path SettingsValues took to them.
        assertEquals(bool(R.bool.config_default_show_predictions), sv.isPredictionsEnabled)
        assertEquals(bool(R.bool.config_default_on_key_predictions), sv.isOnKeyPredictionsEnabled)
        assertEquals(bool(R.bool.config_default_emoji_predictions), sv.isEmojiPredictionsEnabled)
        assertEquals(bool(R.bool.config_default_uim_enabled), sv.isUimEnabled)
        assertEquals(bool(R.bool.config_default_flick_commit_animation), sv.flickCommitAnimationEnabled)
        assertEquals(bool(R.bool.config_default_sound_enabled), sv.isSoundEnabled)

        // Hard-coded defaults have no resource to compare against, so a refactor can silently move them.
        assertEquals("regular", sv.keyboardHeightMode)
        assertEquals("disabled", sv.altSymShortcutAction)
        assertEquals("", sv.multifunctionKeyAction)
        assertEquals(true, sv.slideboardStillBoardsEnabled)
        assertEquals(true, sv.overrideDeviceMetaState)
        assertEquals(true, sv.shiftDoubleTapLock)
        assertEquals(true, sv.altDoubleTapLock)
        assertEquals(true, sv.showPkbModifierStatusIcon)
        assertEquals(true, sv.isPkbEmojiSearchEnabled)
        assertEquals("en-US", sv.voiceInputLanguageList)
        assertEquals(true, sv.voiceInputShowPartialResults)
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
            .putBoolean("voice_input_show_partial_results", false)
            .putBoolean("pref_pkb_emoji_search", false)
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
        assertEquals(false, sv.voiceInputShowPartialResults)
        assertEquals(false, sv.isPkbEmojiSearchEnabled)
    }

    /**
     * `loadSettings` runs repeatedly on the cold path (`onCreate`, then `onStartInputView`); values
     * must not drift between loads for identical inputs.
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

    /** `loadSettings` republishes the device profile (built on a worker) and must never leave it null. */
    @Test
    fun everyLoadPublishesADeviceProfile() {
        load()
        assertNotNull(SettingsManager.getInstance().deviceProfile)
    }
}

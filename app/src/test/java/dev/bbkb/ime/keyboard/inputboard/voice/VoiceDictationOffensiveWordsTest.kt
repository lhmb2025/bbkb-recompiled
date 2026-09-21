package dev.bbkb.ime.keyboard.inputboard.voice

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.speech.RecognizerIntent
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.settings.util.SettingsManager
import dev.bbkb.ime.core.textinput.connection.EditorCapabilities
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSpeechRecognizer
import java.util.Locale

/**
 * "Block offensive words" (`pref_key_block_potentially_offensive`) used to be a dead flag for
 * dictation: [VoiceRecognitionManager.startDictation] built the recogniser intent without
 * `EXTRA_MASK_OFFENSIVE_WORDS`, so the recogniser's own default (mask) applied whatever the user
 * had chosen, and turning the setting off did nothing.
 *
 * This pins the extra to the preference in both positions. It drives the real
 * `startDictation()` rather than a copy of the intent-building code, so the assertion is about
 * what the recogniser is actually handed — Robolectric's `ShadowSpeechRecognizer` records the
 * intent passed to `startListening`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class VoiceDictationOffensiveWordsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Whatever the previous class in this Robolectric sandbox left on the singleton, before
        // the first commit: a leftover `settingsValues` makes SettingsManager's own preference
        // listener rebuild settings out of that class's stale locale/editor state.
        resetSettingsManager()
        prefs().edit().clear().commit()
        DeviceProfile.clearPendingInitForTest()
        DeviceProfile.initialize(null)
        SettingsManager.initialize(context)
        // initialize() registers the manager as a preference listener. This test writes
        // preferences and then loads settings itself, so the reactive reload is only a second,
        // less controlled path to the same place — drop it.
        runCatching { SettingsManager.getInstance().unregisteredListener() }
        ShadowSpeechRecognizer.reset()
    }

    private fun resetSettingsManager() {
        val manager = SettingsManager.getInstance()
        runCatching { manager.unregisteredListener() }
        listOf("settingsValues", "sharedPreferences", "deviceProfile", "listeners").forEach {
            SettingsManager::class.java.getDeclaredField(it)
                .apply { isAccessible = true }.set(manager, null)
        }
    }

    /**
     * SettingsManager is a process singleton and Robolectric shares its sandbox classloader
     * between test classes, so an initialized manager outlives this class and its
     * preference-change listener would fire a full loadSettings() out of the next class's first
     * `edit().commit()`. Hand the singleton back the way it was found — the same teardown
     * `SettingsValuesSnapshotTest` uses.
     */
    @After
    fun tearDown() {
        resetSettingsManager()
        prefs().edit().clear().commit()
        DeviceProfile.clearPendingInitForTest()
        ShadowSpeechRecognizer.reset()
    }

    @Test
    fun `blocking on masks offensive words`() {
        assertTrue(dictationIntent(blockOffensive = true)
            .getBooleanExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, false))
    }

    @Test
    fun `blocking off unmasks offensive words`() {
        assertEquals(
            false,
            dictationIntent(blockOffensive = false)
                .getBooleanExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, true)
        )
    }

    /** The extra must be present either way — an absent extra means the recogniser default wins. */
    @Test
    fun `the extra is always set`() {
        listOf(true, false).forEach {
            assertTrue(
                "EXTRA_MASK_OFFENSIVE_WORDS missing for blockOffensive=$it",
                dictationIntent(it).hasExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS)
            )
        }
    }

    /**
     * Writes the preference, rebuilds `SettingsValues` the way a settings change does, then runs
     * a real dictation start and returns the intent the recogniser received.
     *
     * `voice_input_use_input_language` is turned off so the intent takes the stored-language-list
     * branch; the input-language branch would reach `SubtypeManager`, which is unrelated to what
     * is under test here.
     */
    private fun dictationIntent(blockOffensive: Boolean): Intent {
        prefs().edit()
            .putBoolean("pref_key_block_potentially_offensive", blockOffensive)
            .putBoolean("voice_input_use_input_language", false)
            .commit()
        val manager = SettingsManager.getInstance()
        manager.loadSettings(
            context,
            Locale.US,
            EditorCapabilities(null, false, context.packageName, Locale.US, false)
        )
        assertEquals(blockOffensive, manager.settingsValues.blockPotentiallyOffensiveWords)

        VoiceRecognitionManager(context, mock(VoiceInputController::class.java)).startDictation()
        shadowOf(Looper.getMainLooper()).idle()

        val recognizer = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
        assertNotNull("no SpeechRecognizer was created", recognizer)
        val intent = shadowOf(recognizer).lastRecognizerIntent
        assertNotNull("startListening was never reached", intent)
        return intent
    }

    /**
     * The same instance SettingsManager reads through; PrefsManager is a process singleton, so
     * `PreferenceManager.getDefaultSharedPreferences()` can hand back a different file.
     */
    private fun prefs() = PrefsManager.getPrefs(context)
}

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
 * Pins `EXTRA_MASK_OFFENSIVE_WORDS` to "Block offensive words" in both positions, through the real
 * `startDictation()` and the intent Robolectric's `ShadowSpeechRecognizer` records. Without the extra the
 * recogniser's own default (mask) applies whatever the user chose.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class VoiceDictationOffensiveWordsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Clear what the previous class left on the singleton: a leftover `settingsValues` makes its
        // preference
         // listener rebuild settings from that class's stale locale/editor state.
        resetSettingsManager()
        prefs().edit().clear().commit()
        DeviceProfile.clearPendingInitForTest()
        DeviceProfile.initialize(null)
        SettingsManager.initialize(context)
        // The test loads settings itself; the reactive reload is a second, less controlled path, so drop it.
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
     * SettingsManager is a process singleton shared across test classes in the sandbox; left initialized, its
     * listener fires a full loadSettings() on the next class's first commit. Same teardown as
     * `SettingsValuesSnapshotTest`.
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

    /**
     * A fresh install masks nothing: `R.bool.config_block_potentially_offensive` is off, asserted through
     * `SettingsManager`, the single reader the settings row and the recogniser share.
     */
    @Test
    fun `blocking is off on a fresh install`() {
        assertEquals(false, prefs().contains("pref_key_block_potentially_offensive"))
        assertEquals(false, SettingsManager.isBlockOffensiveEnabled(prefs(), context.resources))
    }

    /** The extra must be present either way: an absent extra means the recogniser default wins. */
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
     * Writes the preference, rebuilds `SettingsValues` as a settings change does, runs a real dictation start and
     * returns the intent. `voice_input_use_input_language` is off so the intent skips `SubtypeManager`.
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

    /** The instance SettingsManager reads; `getDefaultSharedPreferences()` can hand back a different file. */
    private fun prefs() = PrefsManager.getPrefs(context)
}

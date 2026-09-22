package dev.bbkb.ime.core.settings.util

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.R
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The engine-side readers whose stored type, scale or default disagreed with the settings screen
 * that writes the same key. Each case is what a real user's preferences file can contain.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SettingsManagerPrefReadersTest {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = context.getSharedPreferences("settings_manager_pref_readers_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    private fun defaultVolume() =
        context.resources.getFraction(R.fraction.config_default_keypress_sound_volume, 1, 1)

    // ── pref_keypress_sound_volume ────────────────────────────────────────────────────────────

    @Test
    fun anUnsetKeypressVolumeIsTheResourceDefault() {
        assertEquals(defaultVolume(), SettingsManager.getKeypressSoundVolume(prefs, context.resources), 1e-6f)
    }

    @Test
    fun anIntPercentWrittenByTheComposeScreenReadsAsAFraction() {
        // KeyPressFeedbackScreen writes putInt(10..100). getFloat on that entry used to throw
        // ClassCastException out of every SettingsValues rebuild.
        prefs.edit().putInt("pref_keypress_sound_volume", 50).commit()
        assertEquals(0.5f, SettingsManager.getKeypressSoundVolume(prefs, context.resources), 1e-6f)
        prefs.edit().putInt("pref_keypress_sound_volume", 100).commit()
        assertEquals(1.0f, SettingsManager.getKeypressSoundVolume(prefs, context.resources), 1e-6f)
    }

    @Test
    fun aFloatFractionWrittenByTheOriginalSeekBarReadsUnchanged() {
        prefs.edit().putFloat("pref_keypress_sound_volume", 0.3f).commit()
        assertEquals(0.3f, SettingsManager.getKeypressSoundVolume(prefs, context.resources), 1e-6f)
    }

    @Test
    fun theMinusOneSentinelInEitherTypeIsTheResourceDefault() {
        prefs.edit().putFloat("pref_keypress_sound_volume", -1f).commit()
        assertEquals(defaultVolume(), SettingsManager.getKeypressSoundVolume(prefs, context.resources), 1e-6f)
        prefs.edit().putInt("pref_keypress_sound_volume", -1).commit()
        assertEquals(defaultVolume(), SettingsManager.getKeypressSoundVolume(prefs, context.resources), 1e-6f)
    }

    // ── voice_input_language_list ─────────────────────────────────────────────────────────────

    @Test
    fun theVoiceLanguageDefaultIsTheTagTheScreensShow() {
        assertEquals("en-US", SettingsManager.getVoiceInputLanguageList(prefs))
        assertEquals(SettingsManager.DEFAULT_VOICE_INPUT_LANGUAGE, SettingsManager.getVoiceInputLanguageList(prefs))
    }

    @Test
    fun aStoredVoiceLanguageReadsAsALanguageTagWhicheverSeparatorItWasStoredWith() {
        prefs.edit().putString("voice_input_language_list", "fr-CA").commit()
        assertEquals("fr-CA", SettingsManager.getVoiceInputLanguageList(prefs))
        prefs.edit().putString("voice_input_language_list", "pt_BR").commit()
        assertEquals("pt-BR", SettingsManager.getVoiceInputLanguageList(prefs))
    }
}

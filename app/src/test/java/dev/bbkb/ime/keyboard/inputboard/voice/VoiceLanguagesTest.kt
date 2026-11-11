package dev.bbkb.ime.keyboard.inputboard.voice

import android.content.Context
import android.os.Looper
import android.speech.RecognitionSupport
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.keyboard.inputboard.voice.VoiceLanguages.Snapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSpeechRecognizer

/**
 * [VoiceLanguages]: what the speech recogniser has for each language, as the Language packs
 * screen's Voice chips read it, and the download a newly added keyboard asks for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class VoiceLanguagesTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs().edit().clear().commit()
        ShadowSpeechRecognizer.reset()
    }

    @After
    fun tearDown() {
        prefs().edit().clear().commit()
        ShadowSpeechRecognizer.reset()
    }

    private fun prefs() = PrefsManager.getPrefs(context)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun recognizer() = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())

    private fun answer(
        installed: List<String> = emptyList(),
        online: List<String> = emptyList(),
        pending: List<String> = emptyList(),
        supported: List<String> = emptyList(),
    ) {
        recognizer().triggerSupportResult(RecognitionSupport.Builder()
            .setInstalledOnDeviceLanguages(installed)
            .setOnlineLanguages(online)
            .setPendingOnDeviceLanguages(pending)
            .setSupportedOnDeviceLanguages(supported)
            .build())
        idle()
    }

    // ── what a row shows ─────────────────────────────────────────────────────

    private val google = Snapshot(
        installed = listOf("en-US", "fr-FR", "cmn-Hant-TW"),
        online = listOf("it-IT"),
        pending = listOf("de-DE"),
        supported = listOf("es-ES", "pt-BR", "cmn-Hans-CN", "yue-Hant-HK"),
    )

    @Test
    fun `a language the recogniser has on the phone is downloaded`() {
        assertTrue(google.isDownloaded("en_US"))
        assertTrue("a bare pack locale", google.isDownloaded("fr"))
        assertTrue("a regional variant will do, as it does in a dictation", google.isDownloaded("fr_CA"))
    }

    @Test
    fun `Chinese packs match the recogniser's Mandarin and Cantonese tags`() {
        assertTrue(google.isDownloaded("zh_TW_stroke"))
        assertNull("Taiwan's is there already", google.downloadable("zh_TW"))
        assertFalse("the mainland's is offered, not there", google.isDownloaded("zh_CN_pinyin"))
        assertEquals("cmn-Hans-CN", google.downloadable("zh_CN_pinyin"))
        assertEquals("yue-Hant-HK", Snapshot(emptyList(), emptyList(), emptyList(),
            listOf("cmn-Hans-CN", "yue-Hant-HK")).downloadable("zh_HK_cangjie"))
    }

    @Test
    fun `a language that only works online is not downloaded`() {
        assertFalse(google.isDownloaded("it"))
    }

    @Test
    fun `a language on its way, not yet fetched or not offered is not downloaded`() {
        assertFalse("on its way", google.isDownloaded("de"))
        assertFalse("offered, not fetched", google.isDownloaded("es"))
        assertFalse("not offered", google.isDownloaded("uk"))
        assertFalse("not offered", google.isDownloaded("ko"))
    }

    @Test
    fun `no language and a recogniser that lists nothing are not downloaded`() {
        val silent = Snapshot(emptyList(), emptyList(), emptyList(), emptyList())
        assertFalse(silent.isDownloaded("en_US"))
        assertFalse("no language at all", google.isDownloaded("zz"))
        assertFalse(google.isDownloaded(null))
    }

    @Test
    fun `only a language that is offered and missing is downloaded`() {
        assertEquals("es-ES", google.downloadable("es"))
        assertEquals("pt-BR", google.downloadable("pt_BR"))
        assertNull("already there", google.downloadable("fr"))
        assertNull("usable online", google.downloadable("it"))
        assertNull("already on its way", google.downloadable("de"))
        assertNull("not offered", google.downloadable("uk"))
    }

    @Test
    fun `an answer survives being stored`() {
        assertEquals(google, Snapshot.decode(google.encode()))
        val sparse = Snapshot(listOf("en-US"), emptyList(), emptyList(), emptyList())
        assertEquals(sparse, Snapshot.decode(sparse.encode()))
        assertNull(Snapshot.decode(null))
        assertNull(Snapshot.decode("not an answer"))
    }

    // ── asking the recogniser ────────────────────────────────────────────────

    @Test
    fun `refresh reports the recogniser's answer and keeps it`() {
        assertNull(VoiceLanguages.snapshot(context))
        var reported: Snapshot? = null
        VoiceLanguages.refresh(context) { reported = it }
        idle()

        answer(installed = listOf("en-US"), supported = listOf("fr-FR"))

        assertEquals(listOf("en-US"), reported!!.installed)
        assertEquals("kept for the next time the screen opens", reported, VoiceLanguages.snapshot(context))
        assertTrue("the recogniser is let go", recognizer().isDestroyed)
        assertTrue("under the prefix backups leave out",
            prefs().all.keys.single().startsWith("voice_input_language_cache"))
    }

    @Test
    fun `a recogniser that cannot say leaves the last answer in place`() {
        VoiceLanguages.refresh(context)
        idle()
        answer(installed = listOf("en-US"))

        var reported: Snapshot? = null
        VoiceLanguages.refresh(context) { reported = it }
        idle()
        recognizer().triggerSupportError(SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT)
        idle()

        assertEquals(listOf("en-US"), reported!!.installed)
    }

    @Test
    @Config(sdk = [32])
    fun `before Android 13 there is nothing to ask`() {
        var called = false
        var reported: Snapshot? = Snapshot(listOf("x"), emptyList(), emptyList(), emptyList())
        VoiceLanguages.refresh(context) { called = true; reported = it }
        idle()

        assertTrue(called)
        assertNull(reported)
        assertNull("no recogniser was built", ShadowSpeechRecognizer.getLatestSpeechRecognizer())
    }

    // ── a keyboard was added ─────────────────────────────────────────────────

    @Test
    @Config(sdk = [33])
    fun `adding a keyboard asks for its voice language`() {
        VoiceLanguages.downloadForKeyboard(context, "fr")
        idle()

        // Android 13 reports nothing back, so the recogniser is let go at once and a fresh one
        // re-reads the lists: look at the one that was asked.
        val asked = recognizer()

        answer(installed = listOf("en-US"), supported = listOf("fr-CA", "fr-FR"))

        assertEquals("fr-FR", asked.latestModelDownloadIntent!!
            .getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertTrue(asked.isDestroyed)
    }

    @Test
    @Config(sdk = [33])
    fun `nothing is asked for a language that is already there or not offered`() {
        VoiceLanguages.downloadForKeyboard(context, "fr")
        idle()
        answer(installed = listOf("fr-FR"), supported = listOf("fr-CA"))
        assertNull(recognizer().latestModelDownloadIntent)
        assertTrue(recognizer().isDestroyed)

        VoiceLanguages.downloadForKeyboard(context, "uk")
        idle()
        answer(installed = listOf("fr-FR"), supported = listOf("de-DE"))
        assertNull(recognizer().latestModelDownloadIntent)
    }

    @Test
    @Config(sdk = [33])
    fun `the switch and voice input being off both stop the request`() {
        prefs().edit().putBoolean(VoiceLanguages.PREF_AUTO_DOWNLOAD, false).commit()
        VoiceLanguages.downloadForKeyboard(context, "fr")
        idle()
        assertNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer())

        prefs().edit().putBoolean(VoiceLanguages.PREF_AUTO_DOWNLOAD, true)
            .putBoolean("voice_input_enabled", false).commit()
        VoiceLanguages.downloadForKeyboard(context, "fr")
        idle()
        assertNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer())

        prefs().edit().putBoolean("voice_input_enabled", true).commit()
        VoiceLanguages.downloadForKeyboard(context, "fr")
        idle()
        assertNotNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
    }
}

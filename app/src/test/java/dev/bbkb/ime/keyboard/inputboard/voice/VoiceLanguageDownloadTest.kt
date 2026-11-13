package dev.bbkb.ime.keyboard.inputboard.voice

import android.content.Context
import android.os.Looper
import android.speech.RecognitionSupport
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.settings.util.SettingsManager
import dev.bbkb.ime.core.textinput.connection.EditorCapabilities
import dev.bbkb.ime.keyboard.inputboard.voice.VoiceLanguageSupport.Kind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSpeechRecognizer
import java.util.Locale

/**
 * A recogniser that refuses the dictation language is asked what it has (Android 13+): the language under
 * another tag is used, a downloadable one is downloaded, and only a language with nothing is reported unavailable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class VoiceLanguageDownloadTest {

    private lateinit var context: Context
    private lateinit var callback: VoiceInputController
    private lateinit var manager: VoiceRecognitionManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        resetSettingsManager()
        prefs().edit().clear()
            .putBoolean("voice_input_use_input_language", false)
            .putString("voice_input_language_list", "fr")
            .commit()
        DeviceProfile.clearPendingInitForTest()
        DeviceProfile.initialize(null)
        SettingsManager.initialize(context)
        runCatching { SettingsManager.getInstance().unregisteredListener() }
        SettingsManager.getInstance().loadSettings(
            context, Locale.US, EditorCapabilities(null, false, context.packageName, Locale.US, false))
        ShadowSpeechRecognizer.reset()
        callback = mock(VoiceInputController::class.java)
        `when`(callback.isViewShowing).thenReturn(true)
        manager = VoiceRecognitionManager(context, callback)
    }

    @After
    fun tearDown() {
        resetSettingsManager()
        prefs().edit().clear().commit()
        DeviceProfile.clearPendingInitForTest()
        ShadowSpeechRecognizer.reset()
    }

    private fun resetSettingsManager() {
        val settings = SettingsManager.getInstance()
        runCatching { settings.unregisteredListener() }
        listOf("settingsValues", "sharedPreferences", "deviceProfile", "listeners").forEach {
            SettingsManager::class.java.getDeclaredField(it)
                .apply { isAccessible = true }.set(settings, null)
        }
    }

    private fun prefs() = PrefsManager.getPrefs(context)

    private fun recognizer(): ShadowSpeechRecognizer {
        val recognizer = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
        assertNotNull("no SpeechRecognizer was created", recognizer)
        return shadowOf(recognizer)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun start() {
        manager.startDictation()
        idle()
    }

    private fun refuse(error: Int) {
        recognizer().triggerOnError(error)
        idle()
    }

    /**
     * A dictation the recogniser refuses for its language as Google's on-device one does: the
     * bare `fr` as unknown, then, sent again as `fr-FR`, as not downloaded.
     */
    private fun startRefused() {
        start()
        refuse(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)
        refuse(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
    }

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

    private fun sentLanguage() =
        recognizer().lastRecognizerIntent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)

    private fun downloadListener() =
        manager.languageDownloadListenerForTest() as VoiceLanguageSupport.DownloadListener

    @Test
    @Config(sdk = [32])
    fun `a bare language refused as unknown is sent again with its likely region`() {
        start()
        assertEquals("fr", sentLanguage())

        refuse(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)

        assertEquals("fr-FR", sentLanguage())
        assertEquals(VoiceRecognitionManager.Mode.DICTATION, manager.mode)
        verify(callback, never()).onLanguageNotSupported(anyString())
    }

    @Test
    @Config(sdk = [32])
    fun `the fuller tag is remembered for the next dictation`() {
        start()
        refuse(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)
        manager.stopListening()

        start()

        assertEquals("fr-FR", sentLanguage())
        assertEquals("the board still names the language the user chose", "fr", manager.lastLanguageTag)
    }

    @Test
    @Config(sdk = [32])
    fun `before Android 13 a refusal of the fuller tag is reported`() {
        startRefused()

        assertEquals("fr-FR", sentLanguage())
        verify(callback).onLanguageNotSupported("fr")
    }

    @Test
    fun `a language the recogniser knows under another tag is dictated with that tag`() {
        startRefused()

        answer(installed = listOf("en-US", "fr-CA"))

        assertEquals("fr-CA", sentLanguage())
        assertEquals(VoiceRecognitionManager.Mode.DICTATION, manager.mode)
        verify(callback, never()).onLanguageNotSupported(anyString())

        manager.stopListening()
        start()
        assertEquals("and for the dictations after it", "fr-CA", sentLanguage())
    }

    @Test
    fun `a further refusal in the same dictation is reported, not checked again`() {
        startRefused()
        answer(online = listOf("fr-CA"))

        refuse(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)

        verify(callback).onLanguageNotSupported("fr")
    }

    @Test
    @Config(sdk = [33])
    fun `a downloadable language is requested and the user is told`() {
        startRefused()

        answer(installed = listOf("en-US"), supported = listOf("fr-FR", "de-DE"))

        assertEquals("fr-FR", recognizer().latestModelDownloadIntent!!
            .getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        verify(callback).onLanguageDownloading("fr", -1)
        // Android 13 cannot follow the download.
        verify(callback).onLanguageDownloadPending("fr")
        verify(callback, never()).onLanguageNotSupported(anyString())
    }

    @Test
    @Config(sdk = [33])
    fun `a download already under way is followed rather than reported as unavailable`() {
        startRefused()

        answer(pending = listOf("fr-FR"))

        verify(callback).onLanguageDownloading("fr", -1)
        verify(callback, never()).onLanguageNotSupported(anyString())
    }

    @Test
    @Config(sdk = [33])
    fun `progress is passed on and dictation starts when the download finishes`() {
        startRefused()
        answer(supported = listOf("fr-FR"))

        downloadListener().onProgress(40)
        verify(callback).onLanguageDownloading("fr", 40)

        downloadListener().onSuccess()
        idle()
        assertEquals("fr-FR", sentLanguage())
        assertEquals(VoiceRecognitionManager.Mode.DICTATION, manager.mode)
    }

    @Test
    @Config(sdk = [33])
    fun `a finished download does not open the mic once the board has gone`() {
        startRefused()
        answer(supported = listOf("fr-FR"))
        `when`(callback.isViewShowing).thenReturn(false)

        downloadListener().onSuccess()
        idle()

        assertEquals(VoiceRecognitionManager.Mode.NONE, manager.mode)
    }

    @Test
    @Config(sdk = [33])
    fun `a failed download is reported as the language being unavailable`() {
        startRefused()
        answer(supported = listOf("fr-FR"))

        downloadListener().onError()

        verify(callback).onLanguageNotSupported("fr")
    }

    @Test
    fun `a language the recogniser has nothing for is reported as before`() {
        startRefused()

        answer(installed = listOf("en-US"), supported = listOf("de-DE"))

        verify(callback).onLanguageNotSupported("fr")
        verify(callback, never()).onLanguageDownloading(anyString(), anyInt())
    }

    /** Older Google services answer that the check is switched off. */
    @Test
    fun `a recogniser that cannot say what it has is still asked for a missing download`() {
        startRefused()

        recognizer().triggerSupportError(SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT)
        idle()

        verify(callback).onLanguageDownloading("fr", -1)
    }

    /** ...but not on Android 13, where nothing would say whether the request was taken. */
    @Test
    @Config(sdk = [33])
    fun `on Android 13 a recogniser that cannot say what it has is reported as before`() {
        startRefused()

        recognizer().triggerSupportError(SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT)
        idle()

        assertNull(recognizer().latestModelDownloadIntent)
        verify(callback).onLanguageNotSupported("fr")
        verify(callback, never()).onLanguageDownloading(anyString(), anyInt())
    }

    /** A recogniser with downloads switched off drops the request without answering. */
    @Test
    fun `a download request nothing answers is reported as the language being unavailable`() {
        startRefused()
        answer(supported = listOf("fr-FR"))
        verify(callback).onLanguageDownloading("fr", -1)

        shadowOf(Looper.getMainLooper()).idleFor(
            VoiceRecognitionManager.DOWNLOAD_ANSWER_MILLIS + 1, java.util.concurrent.TimeUnit.MILLISECONDS)

        verify(callback).onLanguageNotSupported("fr")
    }

    @Test
    @Config(sdk = [33])
    fun `a recogniser that cannot say and does not know the language is reported as before`() {
        start()
        refuse(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)
        refuse(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)

        recognizer().triggerSupportError(SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT)
        idle()

        assertNull(recognizer().latestModelDownloadIntent)
        verify(callback).onLanguageNotSupported("fr")
    }

    @Test
    fun `an answer for an earlier dictation is ignored`() {
        startRefused()
        start()  // the user tapped the mic again before the recogniser answered

        answer(installed = listOf("fr-CA"))

        assertEquals("fr-FR", sentLanguage())
        verify(callback, never()).onLanguageNotSupported(anyString())
    }

    private fun decide(
        wanted: String,
        installed: List<String> = emptyList(),
        online: List<String> = emptyList(),
        pending: List<String> = emptyList(),
        supported: List<String> = emptyList(),
    ) = VoiceLanguageSupport.decide(wanted, VoiceLanguageTags.likelyRegion(wanted),
        installed, online, pending, supported)

    @Test
    fun `usable beats pending beats downloadable`() {
        var d = decide("fr", installed = listOf("fr-FR"), pending = listOf("fr-CA"), supported = listOf("fr-BE"))
        assertEquals(Kind.USE, d.kind); assertEquals("fr-FR", d.tag)
        d = decide("fr", online = listOf("fr-FR"), supported = listOf("fr-CA"))
        assertEquals(Kind.USE, d.kind); assertEquals("fr-FR", d.tag)
        d = decide("fr", pending = listOf("fr-CA"), supported = listOf("fr-FR"))
        assertEquals(Kind.PENDING, d.kind); assertEquals("fr-CA", d.tag)
        d = decide("fr", supported = listOf("fr-CA", "fr-FR"))
        assertEquals(Kind.DOWNLOAD, d.kind); assertEquals("fr-FR", d.tag)
        d = decide("fr", installed = listOf("en-US"))
        assertEquals(Kind.NONE, d.kind); assertNull(d.tag)
    }

    @Test
    fun `the tag that was refused is never the answer`() {
        // The recogniser lists it as installed and refused it anyway: nothing more to try.
        assertEquals(Kind.NONE, decide("fr-FR", installed = listOf("fr-FR"), supported = listOf("fr-CA")).kind)
    }

    @Test
    fun `an installed regional variant is used rather than downloading the exact one`() {
        val d = decide("fr-CA", installed = listOf("fr-FR"), supported = listOf("fr-CA"))
        assertEquals(Kind.USE, d.kind); assertEquals("fr-FR", d.tag)
    }

    @Test
    fun `matching prefers the same region, then the likely one, then any`() {
        assertEquals("pt-PT", VoiceLanguageTags.bestMatch("pt-PT", "BR", listOf("pt-BR", "pt-PT")))
        assertEquals("pt-BR", VoiceLanguageTags.bestMatch("pt", "BR", listOf("pt-PT", "pt-BR")))
        assertEquals("pt-PT", VoiceLanguageTags.bestMatch("pt", null, listOf("en-US", "pt-PT", "pt-BR")))
        assertEquals("sr-Latn-RS", VoiceLanguageTags.bestMatch("sr-RS", null, listOf("sr-Latn-RS")))
        assertEquals("the candidate's own spelling", "FR-fr", VoiceLanguageTags.bestMatch("fr-FR", null, listOf("FR-fr")))
        assertEquals("old codes are the same language", "iw-IL", VoiceLanguageTags.bestMatch("he", null, listOf("iw-IL")))
        assertNull(VoiceLanguageTags.bestMatch("fr", "FR", listOf("en-US", "de-DE")))
        assertNull(VoiceLanguageTags.bestMatch("fr", "FR", null))
    }
}

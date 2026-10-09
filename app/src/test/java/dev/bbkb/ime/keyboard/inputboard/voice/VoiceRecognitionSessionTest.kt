package dev.bbkb.ime.keyboard.inputboard.voice

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Looper
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.R
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.settings.util.SettingsManager
import dev.bbkb.ime.core.textinput.connection.EditorCapabilities
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSpeechRecognizer
import java.util.Locale

/**
 * How a dictation session ends, driven through the real [VoiceRecognitionManager] and
 * Robolectric's [ShadowSpeechRecognizer] (which records the intent and lets the test play the
 * recogniser's side of the conversation).
 *
 * The case that started this (KEY2, 2026-10-06): with no speech-recognition service selected the
 * platform answers `startListening` with `ERROR_CLIENT` before `onReadyForSpeech`. That used to
 * re-arm DICTATION and never report STOPPED, so the board sat "listening" to nothing and the user
 * was told nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class VoiceRecognitionSessionTest {

    private lateinit var context: Context
    private lateinit var callback: VoiceInputController
    private lateinit var manager: VoiceRecognitionManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        resetSettingsManager()
        prefs().edit().clear().putBoolean("voice_input_use_input_language", false).commit()
        DeviceProfile.clearPendingInitForTest()
        DeviceProfile.initialize(null)
        SettingsManager.initialize(context)
        runCatching { SettingsManager.getInstance().unregisteredListener() }
        SettingsManager.getInstance().loadSettings(
            context, Locale.US, EditorCapabilities(null, false, context.packageName, Locale.US, false))
        ShadowSpeechRecognizer.reset()
        callback = mock(VoiceInputController::class.java)
        manager = VoiceRecognitionManager(context, callback)
    }

    /** Hand SettingsManager back as it was found; see VoiceDictationOffensiveWordsTest. */
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

    private fun start() {
        manager.startDictation()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun results(vararg texts: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(*texts))
    }

    // ── the request ──────────────────────────────────────────────────────────

    @Test
    fun `dictation asks for the free-form language model`() {
        start()
        assertEquals(RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            recognizer().lastRecognizerIntent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL))
    }

    // ── no service ───────────────────────────────────────────────────────────

    @Test
    fun `client error before ready is reported as no recognition service`() {
        `when`(callback.isViewShowing).thenReturn(true)
        start()

        recognizer().triggerOnError(SpeechRecognizer.ERROR_CLIENT)

        verify(callback).onNoRecognitionService()
        // ...and the session is over, not re-armed as the old "transient" handling did.
        assertEquals(VoiceRecognitionManager.Mode.NONE, manager.mode)
        verify(callback).onStateChanged(VoiceRecognitionManager.Mode.NONE, VoiceRecognitionManager.STATE_STOPPED)
        verify(callback, never()).onRecognitionError(anyInt())
    }

    /** The platform can answer twice; the second answer must not report (or re-arm) again. */
    @Test
    fun `a second client error after the session ended does nothing`() {
        `when`(callback.isViewShowing).thenReturn(true)
        start()
        recognizer().triggerOnError(SpeechRecognizer.ERROR_CLIENT)
        recognizer().triggerOnError(SpeechRecognizer.ERROR_CLIENT)

        verify(callback, times(1)).onNoRecognitionService()
        assertEquals(VoiceRecognitionManager.Mode.NONE, manager.mode)
    }

    /**
     * Stop, then start again at once: the stop's own ERROR_CLIENT can land in the new session. That
     * is what the transient re-arm is for, and it must not read as a missing service.
     */
    @Test
    fun `a client error that may answer an earlier stop keeps the new session`() {
        `when`(callback.isViewShowing).thenReturn(true)
        start()
        manager.stopListening()
        start()

        recognizer().triggerOnError(SpeechRecognizer.ERROR_CLIENT)

        verify(callback, never()).onNoRecognitionService()
        assertEquals(VoiceRecognitionManager.Mode.DICTATION, manager.mode)
    }

    @Test
    fun `client error after ready is not a missing service`() {
        start()
        recognizer().triggerOnReadyForSpeech(Bundle())

        recognizer().triggerOnError(SpeechRecognizer.ERROR_CLIENT)

        verify(callback, never()).onNoRecognitionService()
    }

    // ── errors that used to end the session silently ─────────────────────────

    @Test
    fun `no match ends the session with a message`() {
        start()
        recognizer().triggerOnReadyForSpeech(Bundle())

        recognizer().triggerOnError(SpeechRecognizer.ERROR_NO_MATCH)

        assertEquals(VoiceRecognitionManager.Mode.NONE, manager.mode)
        verify(callback).onRecognitionError(R.string.voice_status_no_match)
        verify(callback).onDictationAbandoned()
    }

    @Test
    fun `a network error ends the session with a message`() {
        start()
        recognizer().triggerOnError(SpeechRecognizer.ERROR_NETWORK)

        verify(callback).onRecognitionError(R.string.voice_status_network_error)
        verify(callback, never()).onNoRecognitionService()
    }

    // ── partial and final results ────────────────────────────────────────────

    @Test
    fun `partial results reach the callback`() {
        start()
        recognizer().triggerOnPartialResults(results("hello wor"))

        verify(callback).onPartialResult("hello wor")
    }

    @Test
    fun `the final result commits and does not abandon the partial`() {
        start()
        recognizer().triggerOnPartialResults(results("hello wor"))
        recognizer().triggerOnResults(results("hello world"))

        verify(callback).onDictationResult("hello world")
        verify(callback, never()).onDictationAbandoned()
    }

    @Test
    fun `an empty final result abandons the partial and commits nothing`() {
        start()
        recognizer().triggerOnPartialResults(results("hello"))
        recognizer().triggerOnResults(results(""))

        verify(callback, never()).onDictationResult(anyString())
        verify(callback).onDictationAbandoned()
    }

    @Test
    fun `a cancelled session abandons the partial and ignores late partials`() {
        start()
        recognizer().triggerOnPartialResults(results("hello"))

        manager.stopListening()
        recognizer().triggerOnPartialResults(results("hello there"))

        verify(callback).onDictationAbandoned()
        verify(callback, never()).onPartialResult("hello there")
    }

    // ── "Show words as you speak" ────────────────────────────────────────────

    private fun showPartialResults(show: Boolean) {
        prefs().edit().putBoolean("voice_input_show_partial_results", show).commit()
        SettingsManager.getInstance().loadSettings(
            context, Locale.US, EditorCapabilities(null, false, context.packageName, Locale.US, false))
    }

    /** Off: partials stay out of the editor and the final result commits once, as before partials were shown. */
    @Test
    fun `with show words as you speak off partials are not forwarded and the final result still commits`() {
        showPartialResults(false)
        start()

        recognizer().triggerOnPartialResults(results("hello"))
        recognizer().triggerOnPartialResults(results("hello wor"))
        recognizer().triggerOnResults(results("hello world"))

        verify(callback, never()).onPartialResult(anyString())
        verify(callback, times(1)).onDictationResult("hello world")
        verify(callback, never()).onDictationAbandoned()
        assertEquals(VoiceRecognitionManager.Mode.NONE, manager.mode)
    }

    /** The setting changes what reaches the editor, not what is asked of the recogniser. */
    @Test
    fun `with show words as you speak off the request is unchanged`() {
        showPartialResults(false)
        start()

        assertTrue(recognizer().lastRecognizerIntent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false))
    }

    /** Read per partial, not when the recogniser was built: the same manager follows the setting. */
    @Test
    fun `changing show words as you speak applies to the next dictation without a new recogniser`() {
        start()
        recognizer().triggerOnPartialResults(results("one"))
        recognizer().triggerOnResults(results("one"))
        verify(callback).onPartialResult("one")

        showPartialResults(false)
        start()
        recognizer().triggerOnPartialResults(results("two"))
        recognizer().triggerOnResults(results("two"))
        verify(callback, never()).onPartialResult("two")
        verify(callback).onDictationResult("two")

        showPartialResults(true)
        start()
        recognizer().triggerOnPartialResults(results("three"))
        verify(callback).onPartialResult("three")
    }

    // ── the "Speech recognizer" setting ──────────────────────────────────────

    private val sayboard = ComponentName("com.elishaazaria.sayboard", "com.elishaazaria.sayboard.recognition.RecognitionService")

    /** A recognition app the package query can see, labelled as its launcher would show it. */
    private fun installRecognizer(component: ComponentName, label: String) {
        val app = ApplicationInfo().apply {
            packageName = component.packageName
            nonLocalizedLabel = label
        }
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = component.packageName
            applicationInfo = app
        })
        shadowOf(context.packageManager).addResolveInfoForIntent(
            Intent(RecognitionService.SERVICE_INTERFACE),
            ResolveInfo().apply {
                serviceInfo = ServiceInfo().apply {
                    packageName = component.packageName
                    name = component.className
                    applicationInfo = app
                }
            })
    }

    private fun chooseRecognizer(value: String) {
        prefs().edit().putString("voice_input_recognizer", value).commit()
    }

    /** The component a recogniser was built for: null for the system default. */
    private fun boundComponent(recognizer: SpeechRecognizer): ComponentName? =
        SpeechRecognizer::class.java.getDeclaredField("mServiceComponent")
            .apply { isAccessible = true }.get(recognizer) as ComponentName?

    private fun isOnDevice(recognizer: SpeechRecognizer): Boolean =
        SpeechRecognizer::class.java.getDeclaredField("mOnDevice")
            .apply { isAccessible = true }.getBoolean(recognizer)

    private fun latest(): SpeechRecognizer = ShadowSpeechRecognizer.getLatestSpeechRecognizer()!!

    private fun grantMicrophone(granted: Boolean) {
        val app = shadowOf(context as Application)
        if (granted) app.grantPermissions(Manifest.permission.RECORD_AUDIO)
        else app.denyPermissions(Manifest.permission.RECORD_AUDIO)
    }

    @Test
    fun `with the setting empty the system default recogniser is used`() {
        start()

        assertNull(boundComponent(latest()))
        assertFalse(isOnDevice(latest()))
    }

    @Test
    fun `a chosen installed app is bound by its component`() {
        installRecognizer(sayboard, "Sayboard")
        chooseRecognizer(sayboard.flattenToString())

        start()

        assertEquals(sayboard, boundComponent(latest()))
        assertEquals("the request is the same whichever service answers it",
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            recognizer().lastRecognizerIntent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL))
    }

    /** No keyboard restart: the next tap after a change in settings uses the new recogniser. */
    @Test
    fun `changing the setting rebuilds the recogniser before the next dictation`() {
        installRecognizer(sayboard, "Sayboard")
        start()
        val first = latest()
        recognizer().triggerOnResults(results("one"))

        chooseRecognizer(sayboard.flattenToString())
        start()

        val second = latest()
        assertTrue("a new recogniser is built", second !== first)
        assertTrue("the old one is destroyed, not leaked", shadowOf(first).isDestroyed)
        assertEquals(sayboard, boundComponent(second))

        recognizer().triggerOnResults(results("two"))
        verify(callback).onDictationResult("two")

        chooseRecognizer("")
        start()
        assertTrue(shadowOf(second).isDestroyed)
        assertNull("back to the system default", boundComponent(latest()))
    }

    @Test
    fun `an unchanged setting keeps the same recogniser`() {
        start()
        val first = latest()
        recognizer().triggerOnResults(results("one"))

        start()

        assertTrue(latest() === first)
        assertFalse(shadowOf(first).isDestroyed)
    }

    /** Uninstalled since it was chosen: the default it falls back to says "no service" if it is gone too. */
    @Test
    fun `a chosen app that is no longer installed falls back to the system default`() {
        chooseRecognizer(sayboard.flattenToString())

        start()

        assertNull(boundComponent(latest()))
        assertFalse(isOnDevice(latest()))
    }

    @Test
    fun `the on-device recogniser is used where the phone has one`() {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        chooseRecognizer("ondevice")

        start()

        assertTrue(isOnDevice(latest()))
    }

    @Test
    fun `without an on-device recogniser that choice falls back to the system default`() {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(false)
        chooseRecognizer("ondevice")

        start()

        assertFalse(isOnDevice(latest()))
        assertNull(boundComponent(latest()))
    }

    // ── a refused microphone ─────────────────────────────────────────────────

    @Test
    fun `a permission error from a chosen app names that app`() {
        installRecognizer(sayboard, "Sayboard")
        chooseRecognizer(sayboard.flattenToString())
        grantMicrophone(true)
        `when`(callback.isViewShowing).thenReturn(true)
        start()

        recognizer().triggerOnError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)

        verify(callback).onRecognizerNeedsPermission("Sayboard")
        verify(callback, never()).onPermissionNeeded()
    }

    @Test
    fun `with the system default a permission error still asks for ours`() {
        grantMicrophone(true)
        `when`(callback.isViewShowing).thenReturn(true)
        start()

        recognizer().triggerOnError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)

        verify(callback).onPermissionNeeded()
        verify(callback, never()).onRecognizerNeedsPermission(anyString())
    }

    /** The service checks its caller as well as itself: our own permission comes first. */
    @Test
    fun `a chosen app is not blamed while the keyboard lacks the permission itself`() {
        installRecognizer(sayboard, "Sayboard")
        chooseRecognizer(sayboard.flattenToString())
        grantMicrophone(false)
        `when`(callback.isViewShowing).thenReturn(true)
        start()

        recognizer().triggerOnError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)

        verify(callback).onPermissionNeeded()
        verify(callback, never()).onRecognizerNeedsPermission(anyString())
    }
}

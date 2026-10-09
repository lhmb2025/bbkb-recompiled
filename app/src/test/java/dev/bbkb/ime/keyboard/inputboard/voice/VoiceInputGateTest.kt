package dev.bbkb.ime.keyboard.inputboard.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.provider.Settings
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.BlackBerryIME
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.settings.util.SettingsManager
import dev.bbkb.ime.core.textinput.connection.EditorCapabilities
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.RETURNS_DEEP_STUBS
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSpeechRecognizer
import org.robolectric.shadows.ShadowToast
import java.util.Locale

/**
 * The gate in front of every way of opening voice input, through the real [VoiceInputController].
 *
 * KEY2, 2026-10-06, no recognition service selected: the mic key's toggle set voice mode on,
 * opened a board the UIM bar's consistency pass then hid, and left voice mode latched on with no
 * message. Now the open is refused before anything is shown, voice mode stays off, and the notice
 * says why — in the exact words the owner asked for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class VoiceInputGateTest {

    private lateinit var context: Context
    private lateinit var ime: BlackBerryIME
    private lateinit var listener: VoiceInputController.Listener
    private lateinit var controller: VoiceInputController

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        resetSettingsManager()
        prefs().edit().clear().commit()
        DeviceProfile.clearPendingInitForTest()
        DeviceProfile.initialize(null)
        SettingsManager.initialize(context)
        runCatching { SettingsManager.getInstance().unregisteredListener() }
        loadSettings()
        ShadowSpeechRecognizer.reset()
        ShadowToast.reset()
        ime = mock(BlackBerryIME::class.java, RETURNS_DEEP_STUBS)
        `when`(ime.applicationContext).thenReturn(context)
        listener = mock(VoiceInputController.Listener::class.java)
        controller = VoiceInputController(ime, listener)
    }

    @After
    fun tearDown() {
        controller.destroy()
        resetSettingsManager()
        prefs().edit().clear().commit()
        Settings.Secure.putString(context.contentResolver, "voice_recognition_service", null)
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

    private fun loadSettings() {
        SettingsManager.getInstance().loadSettings(
            context, Locale.US, EditorCapabilities(null, false, context.packageName, Locale.US, false))
    }

    /** A recognition service the package query can see, optionally chosen in the secure setting. */
    private fun installRecognitionService(selected: Boolean) {
        val component = ComponentName("com.example.speech", "com.example.speech.Recognizer")
        val info = ResolveInfo().apply {
            serviceInfo = ServiceInfo().apply {
                packageName = component.packageName
                name = component.className
            }
        }
        shadowOf(context.packageManager)
            .addResolveInfoForIntent(Intent(RecognitionService.SERVICE_INTERFACE), info)
        Settings.Secure.putString(context.contentResolver, "voice_recognition_service",
            if (selected) component.flattenToString() else "")
    }

    @Test
    fun `with no recognition service the mic toggle opens nothing and latches nothing`() {
        assertFalse(SpeechRecognizer.isRecognitionAvailable(context))

        controller.toggleVoiceInput()

        assertFalse("voice mode must not stay on for a board that never opened", controller.isInVoiceMode)
        verify(listener, never()).onVoicePanelShown()
        assertEquals("No selected voice recognition service", ShadowToast.getTextOfLatestToast())
    }

    /** The KEY2 shape: something could recognise speech, but nothing is chosen to. */
    @Test
    fun `an installed but unselected service is still no service`() {
        installRecognitionService(selected = false)
        assertTrue(SpeechRecognizer.isRecognitionAvailable(context))

        controller.toggleVoiceInput()

        assertFalse(controller.isInVoiceMode)
        assertEquals("No selected voice recognition service", ShadowToast.getTextOfLatestToast())
    }

    /** The next press is a fresh open (and a fresh notice), not a "close" of the refused one. */
    @Test
    fun `a second press after a refusal is refused again rather than closing`() {
        controller.toggleVoiceInput()
        ShadowToast.reset()

        controller.toggleVoiceInput()

        assertFalse(controller.isInVoiceMode)
        assertEquals("No selected voice recognition service", ShadowToast.getTextOfLatestToast())
    }

    /** The on-screen -27/-7 key calls show() directly; it is gated the same way. */
    @Test
    fun `show refuses too`() {
        controller.show()

        verify(listener, never()).onVoicePanelShown()
        assertEquals("No selected voice recognition service", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `an installed and selected service passes the gate silently`() {
        installRecognitionService(selected = true)

        assertTrue(controller.ensureRecognitionService())
        assertNull(ShadowToast.getTextOfLatestToast())
    }

    /**
     * The "Speech recognizer" setting binds the chosen app directly, so the phone's own (empty)
     * choice does not matter: this is the KEY2 shape with a way out.
     */
    @Test
    fun `a chosen installed app passes the gate with no system service selected`() {
        installRecognitionService(selected = false)
        prefs().edit().putString("voice_input_recognizer", "com.example.speech/com.example.speech.Recognizer").commit()

        assertTrue(controller.ensureRecognitionService())
        assertNull(ShadowToast.getTextOfLatestToast())
    }

    /** Uninstalled since it was chosen: back to the system default, and its notice if there is none. */
    @Test
    fun `a chosen app that is gone falls back to the system default's notice`() {
        prefs().edit().putString("voice_input_recognizer", "com.gone/com.gone.Recognizer").commit()

        assertFalse(controller.ensureRecognitionService())
        assertEquals("No selected voice recognition service", ShadowToast.getTextOfLatestToast())
    }

    /** A board showing its status line: the notice goes there, naming the app to fix. */
    @Test
    fun `the permission notice names the chosen app on the status line`() {
        val view = mock(VoiceInputView::class.java)
        `when`(view.isShowing).thenReturn(true)
        `when`(view.showStatusMessage(any(CharSequence::class.java))).thenReturn(true)
        controller.setVoiceInputView(view)

        controller.onRecognizerNeedsPermission("Sayboard")

        verify(view).showStatusMessage("Sayboard needs microphone permission")
        assertNull(ShadowToast.getTextOfLatestToast())
    }

    /** Classic/Modern boards have no status line, so the same words go in a toast. */
    @Test
    fun `the permission notice is a toast where the board has no status line`() {
        val view = mock(VoiceInputView::class.java)
        `when`(view.isShowing).thenReturn(true)
        `when`(view.showStatusMessage(any(CharSequence::class.java))).thenReturn(false)
        controller.setVoiceInputView(view)

        controller.onRecognizerNeedsPermission("Sayboard")

        assertEquals("Sayboard needs microphone permission", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `no permission notice once the board has gone`() {
        controller.onRecognizerNeedsPermission("Sayboard")
        controller.onRecognizerRefused("Claude")

        assertNull(ShadowToast.getTextOfLatestToast())
    }

    /**
     * KEY2, 2026-10-08: Claude holds the microphone and still refused. The notice must not send the
     * user after a permission Claude already has; another recognizer is the only fix.
     */
    @Test
    fun `a refusing app is named and another recognizer suggested on the status line`() {
        val view = mock(VoiceInputView::class.java)
        `when`(view.isShowing).thenReturn(true)
        `when`(view.showStatusMessage(any(CharSequence::class.java))).thenReturn(true)
        controller.setVoiceInputView(view)

        controller.onRecognizerRefused("Claude")

        verify(view).showStatusMessage("Claude did not allow dictation. Try another recognizer in Voice input settings.")
        assertNull(ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `the refusal notice is a toast where the board has no status line`() {
        val view = mock(VoiceInputView::class.java)
        `when`(view.isShowing).thenReturn(true)
        `when`(view.showStatusMessage(any(CharSequence::class.java))).thenReturn(false)
        controller.setVoiceInputView(view)

        controller.onRecognizerRefused("Claude")

        assertEquals("Claude did not allow dictation. Try another recognizer in Voice input settings.",
            ShadowToast.getTextOfLatestToast())
    }

    /** Built-in voice off hands the mic to the system voice keyboard, which needs no recogniser of ours. */
    @Test
    fun `with built-in voice input off the gate does not apply`() {
        prefs().edit().putBoolean("voice_input_enabled", false).commit()
        loadSettings()

        assertTrue(controller.ensureRecognitionService())
        assertNull(ShadowToast.getTextOfLatestToast())
    }
}

package dev.bbkb.ime.keyboard.inputboard.voice

import android.speech.SpeechRecognizer
import dev.bbkb.ime.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decisions behind voice input's "No selected voice recognition service" notice and its
 * per-error status messages. Pure functions, so no Android runtime: the KEY2 case (2026-10-06,
 * Google app disabled, `Settings.Secure.voice_recognition_service` empty) is spelt out as plain
 * values.
 */
class VoiceRecognitionAvailabilityTest {

    // ── before starting ──────────────────────────────────────────────────────

    @Test
    fun `no recognition service installed is no service`() {
        assertTrue(VoiceRecognitionAvailability.isNoService(false, true, "com.google/.Recognizer"))
    }

    /** The KEY2 report: a service may be installed, but with none selected nothing can bind. */
    @Test
    fun `an empty selected-service setting is no service`() {
        assertTrue(VoiceRecognitionAvailability.isNoService(true, true, null))
        assertTrue(VoiceRecognitionAvailability.isNoService(true, true, ""))
        assertTrue(VoiceRecognitionAvailability.isNoService(true, true, "  "))
    }

    @Test
    fun `an installed and selected service is available`() {
        assertFalse(VoiceRecognitionAvailability.isNoService(true, true,
            "com.google.android.tts/com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService"))
    }

    /** A ROM that refuses the settings read must not lock voice input out on that alone. */
    @Test
    fun `an unreadable setting leaves the decision to the package query`() {
        assertFalse(VoiceRecognitionAvailability.isNoService(true, false, null))
        assertTrue(VoiceRecognitionAvailability.isNoService(false, false, null))
    }

    // ── after starting ───────────────────────────────────────────────────────

    /** What SpeechRecognizer answers when it has no service to bind: ERROR_CLIENT, before ready. */
    @Test
    fun `client error before ready in a live session is no service`() {
        assertTrue(VoiceRecognitionAvailability.isNoServiceError(
            SpeechRecognizer.ERROR_CLIENT, true, false, false))
    }

    @Test
    fun `client error after ready is not no service`() {
        assertFalse(VoiceRecognitionAvailability.isNoServiceError(
            SpeechRecognizer.ERROR_CLIENT, true, true, false))
    }

    /** A stop with no session behind it is answered with ERROR_CLIENT too; that is not a missing service. */
    @Test
    fun `client error with no session of ours running is not no service`() {
        assertFalse(VoiceRecognitionAvailability.isNoServiceError(
            SpeechRecognizer.ERROR_CLIENT, false, false, false))
    }

    /**
     * Stop, then a quick restart: the stop's ERROR_CLIENT can arrive after the new session began.
     * That is the case the recognition manager's "transient" re-arm exists for.
     */
    @Test
    fun `client error that may answer an earlier stop is not no service`() {
        assertFalse(VoiceRecognitionAvailability.isNoServiceError(
            SpeechRecognizer.ERROR_CLIENT, true, false, true))
    }

    @Test
    fun `other errors before ready are not no service`() {
        for (error in listOf(
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_AUDIO,
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
        )) {
            assertFalse("error $error", VoiceRecognitionAvailability.isNoServiceError(error, true, false, false))
        }
    }

    // ── what the board says when a session ends on an error ──────────────────

    @Test
    fun `each recoverable error has its own message`() {
        val expected = mapOf(
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT to R.string.voice_status_network_error,
            SpeechRecognizer.ERROR_NETWORK to R.string.voice_status_network_error,
            SpeechRecognizer.ERROR_AUDIO to R.string.voice_status_audio_error,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT to R.string.voice_status_no_speech,
            SpeechRecognizer.ERROR_NO_MATCH to R.string.voice_status_no_match,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY to R.string.voice_status_busy,
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS to R.string.voice_status_too_many_requests,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED to R.string.voice_status_service_disconnected,
        )
        for ((error, message) in expected) {
            assertEquals("error $error", message, VoiceRecognitionAvailability.messageForError(error))
        }
    }

    /**
     * Errors handled elsewhere get no status message here: ERROR_CLIENT (no service, or our own
     * cancel), ERROR_SERVER (the offline language-pack offer), the permission request, and the two
     * language errors, which name the language in their own message.
     */
    @Test
    fun `errors with their own handling have no status message`() {
        for (error in listOf(
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        )) {
            assertEquals("error $error", VoiceRecognitionAvailability.NO_MESSAGE,
                VoiceRecognitionAvailability.messageForError(error))
        }
    }
}

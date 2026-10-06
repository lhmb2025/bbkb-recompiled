package dev.bbkb.ime.keyboard.inputboard.voice;

import android.content.Context;
import android.provider.Settings;
import android.speech.SpeechRecognizer;

import dev.bbkb.ime.R;
import dev.bbkb.ime.core.shared.Logger;

/**
 * Whether dictation can start at all, and what to tell the user when a session fails.
 *
 * <p>Verified on the KEY2 (2026-10-06) with the Google app disabled and nothing chosen as the
 * phone's speech service: the mic key opened the board, {@code SpeechRecognizer} logged "no
 * selected voice recognition service" and answered {@code ERROR_CLIENT}, and the user saw nothing.
 * {@link SpeechRecognizer#createSpeechRecognizer(Context)} without a component binds whatever the
 * secure setting {@value #SECURE_VOICE_RECOGNITION_SERVICE} names, so an empty setting means no
 * session can ever start, whatever services are installed.
 *
 * <p>The decisions are pure functions over plain values so they can be tested without Android;
 * {@link #hasRecognitionService(Context)} is the one place that reads the device.
 */
public final class VoiceRecognitionAvailability {

    private static final String TAG = "VoiceRecognition";

    /** {@code Settings.Secure.VOICE_RECOGNITION_SERVICE}, which is hidden API. */
    static final String SECURE_VOICE_RECOGNITION_SERVICE = "voice_recognition_service";

    /** No message: the session ended in a way the board already shows, or one the user caused. */
    public static final int NO_MESSAGE = 0;

    private VoiceRecognitionAvailability() {
    }

    /**
     * Whether there is no recogniser to start.
     *
     * @param recognitionAvailable {@link SpeechRecognizer#isRecognitionAvailable(Context)}
     * @param settingReadable whether the secure setting could be read at all; when it could not,
     *        only {@code recognitionAvailable} decides
     * @param selectedService the secure setting's value: a flattened component name, or null/empty
     *        when the user has chosen none
     */
    public static boolean isNoService(boolean recognitionAvailable, boolean settingReadable, String selectedService) {
        if (!recognitionAvailable) {
            return true;
        }
        return settingReadable && (selectedService == null || selectedService.trim().isEmpty());
    }

    /**
     * Whether an error that ended a session means the platform found no service to bind.
     *
     * <p>{@code ERROR_CLIENT} before {@code onReadyForSpeech} is what an absent service produces.
     * It is also what a stop or cancel produces when the recogniser has no session to stop, so it
     * only counts when a session of ours was live, we did not cancel it, and no stop of ours issued
     * before this session started can still be answering (the reason for the "transient" handling
     * in {@link VoiceRecognitionManager}).
     *
     * @param sessionActive a dictation session was running when the error arrived
     * @param readyForSpeech {@code onReadyForSpeech} had arrived for it
     * @param staleClientErrorPossible a stop or cancel was still unanswered when the session started
     */
    public static boolean isNoServiceError(int error, boolean sessionActive, boolean readyForSpeech,
            boolean staleClientErrorPossible) {
        return error == SpeechRecognizer.ERROR_CLIENT && sessionActive && !readyForSpeech
                && !staleClientErrorPossible;
    }

    /**
     * The status-line message for an error that ended a session, or {@link #NO_MESSAGE}. Errors with
     * their own handling elsewhere (the language and permission errors, and {@code ERROR_SERVER},
     * which offers the offline language pack) and {@code ERROR_CLIENT} have none here.
     */
    public static int messageForError(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
            case SpeechRecognizer.ERROR_NETWORK:
                return R.string.voice_status_network_error;
            case SpeechRecognizer.ERROR_AUDIO:
                return R.string.voice_status_audio_error;
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return R.string.voice_status_no_speech;
            case SpeechRecognizer.ERROR_NO_MATCH:
                return R.string.voice_status_no_match;
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return R.string.voice_status_busy;
            case SpeechRecognizer.ERROR_TOO_MANY_REQUESTS:
                return R.string.voice_status_too_many_requests;
            case SpeechRecognizer.ERROR_SERVER_DISCONNECTED:
                return R.string.voice_status_service_disconnected;
            default:
                return NO_MESSAGE;
        }
    }

    /** Whether a dictation session could start on this device right now. */
    public static boolean hasRecognitionService(Context context) {
        if (context == null) {
            return false;
        }
        final boolean available = SpeechRecognizer.isRecognitionAvailable(context);
        boolean readable = true;
        String selected = null;
        try {
            selected = Settings.Secure.getString(context.getContentResolver(), SECURE_VOICE_RECOGNITION_SERVICE);
        } catch (RuntimeException e) {
            // A ROM that refuses the read: fall back to the package query alone.
            readable = false;
        }
        final boolean noService = isNoService(available, readable, selected);
        if (noService) {
            Logger.debug(TAG, "No recognition service: available=" + available + " selected=" + selected);
        }
        return !noService;
    }
}

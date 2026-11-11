package dev.bbkb.ime.keyboard.inputboard.voice;

import android.content.Intent;
import android.os.Build;
import android.speech.ModelDownloadListener;
import android.speech.RecognitionSupport;
import android.speech.RecognitionSupportCallback;
import android.speech.SpeechRecognizer;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * What to do when a recogniser refuses a language: ask it which languages it has, can use online
 * or can download (Android 13+), and work out whether ours is usable under another tag, is
 * already on its way, or can be downloaded now.
 *
 * <p>The case that started this (KEY2, 2026-11-10): Google's on-device recogniser answered a
 * French dictation with "Failed to get language pack of required locale", and all the board could
 * say was that French is not available.
 *
 * <p>Every use of an Android 13+ class is in here, so nothing in the rest of the voice code loads
 * one on an older phone; {@link VoiceRecognitionManager} only calls in after checking the version.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
final class VoiceLanguageSupport {

    private VoiceLanguageSupport() {
    }

    /** What {@link #decide} concluded. */
    enum Kind {
        /** The recogniser has the language under {@link Decision#tag}, a different tag from the one refused. */
        USE,
        /** {@link Decision#tag} is already being downloaded. */
        PENDING,
        /** {@link Decision#tag} can be downloaded. */
        DOWNLOAD,
        /** The recogniser has nothing for this language. */
        NONE
    }

    static final class Decision {
        final Kind kind;
        /** The recogniser's own tag for the language; null for {@link Kind#NONE}. */
        final String tag;

        Decision(Kind kind, String tag) {
            this.kind = kind;
            this.tag = tag;
        }

        @Override
        public String toString() {
            return kind + (tag == null ? "" : "(" + tag + ")");
        }
    }

    /**
     * The decision, from the four lists a recogniser reports. Installed and online languages are
     * usable now; of those, only one under a <em>different</em> tag is an answer, because the
     * recogniser has just refused the tag we sent. An installed regional variant is used rather
     * than downloading the exact one.
     *
     * @param wanted the tag that was refused
     * @param likelyRegion the region the language is most likely meant in ("FR" for "fr"), or null
     */
    static Decision decide(String wanted, String likelyRegion, List<String> installed,
            List<String> online, List<String> pending, List<String> supported) {
        String usable = VoiceLanguageTags.bestMatch(wanted, likelyRegion, installed);
        if (usable == null) {
            usable = VoiceLanguageTags.bestMatch(wanted, likelyRegion, online);
        }
        if (usable != null) {
            return usable.equalsIgnoreCase(wanted)
                    ? new Decision(Kind.NONE, null) : new Decision(Kind.USE, usable);
        }
        final String coming = VoiceLanguageTags.bestMatch(wanted, likelyRegion, pending);
        if (coming != null) {
            return new Decision(Kind.PENDING, coming);
        }
        final String downloadable = VoiceLanguageTags.bestMatch(wanted, likelyRegion, supported);
        return downloadable != null
                ? new Decision(Kind.DOWNLOAD, downloadable) : new Decision(Kind.NONE, null);
    }

    /** The answer to {@link #check}. Called on the executor given to it. */
    interface CheckListener {
        void onDecision(Decision decision);

        /** The recogniser cannot say what it supports. */
        void onCheckFailed();
    }

    /** The four lists a recogniser reports. Called on the executor given to {@link #lists}. */
    interface ListsListener {
        void onLists(List<String> installed, List<String> online, List<String> pending, List<String> supported);

        /** The recogniser cannot say what it supports. */
        void onFailed();
    }

    /** Asks the recogniser which languages it has, can use online, is fetching and can fetch. */
    static void lists(SpeechRecognizer recognizer, Intent request, Executor executor,
            final ListsListener listener) {
        recognizer.checkRecognitionSupport(request, executor, new RecognitionSupportCallback() {
            @Override
            public void onSupportResult(@NonNull RecognitionSupport support) {
                listener.onLists(support.getInstalledOnDeviceLanguages(), support.getOnlineLanguages(),
                        support.getPendingOnDeviceLanguages(), support.getSupportedOnDeviceLanguages());
            }

            @Override
            public void onError(int error) {
                listener.onFailed();
            }
        });
    }

    /** Asks the recogniser what it supports and reports what that means for {@code wanted}. */
    static void check(SpeechRecognizer recognizer, Intent request, final String wanted,
            Executor executor, final CheckListener listener) {
        lists(recognizer, request, executor, new ListsListener() {
            @Override
            public void onLists(List<String> installed, List<String> online, List<String> pending,
                    List<String> supported) {
                listener.onDecision(decide(wanted, VoiceLanguageTags.likelyRegion(wanted),
                        installed, online, pending, supported));
            }

            @Override
            public void onFailed() {
                listener.onCheckFailed();
            }
        });
    }

    /** How a download requested with {@link #download} goes. Called on the executor given to it. */
    interface DownloadListener {
        /** @param percent 0 to 100 */
        void onProgress(int percent);

        void onSuccess();

        /** Accepted, but for later; or (Android 13) accepted with no way to follow it. */
        void onScheduled();

        void onError();
    }

    /**
     * Asks the recogniser to download the language {@code request} names. The recogniser decides
     * how: straight away, later (on Wi-Fi, say), or after asking the user itself. Android 14+
     * reports how it goes; Android 13 has no listener, so the request is reported as scheduled.
     */
    static void download(SpeechRecognizer recognizer, Intent request, Executor executor,
            DownloadListener listener) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Api34.download(recognizer, request, executor, listener);
        } else {
            recognizer.triggerModelDownload(request);
            listener.onScheduled();
        }
    }

    /** The Android 14 listener, in its own class so Android 13 never loads it. */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private static final class Api34 {
        static void download(SpeechRecognizer recognizer, Intent request, Executor executor,
                final DownloadListener listener) {
            recognizer.triggerModelDownload(request, executor, new ModelDownloadListener() {
                @Override
                public void onProgress(int completedPercent) {
                    listener.onProgress(completedPercent);
                }

                @Override
                public void onSuccess() {
                    listener.onSuccess();
                }

                @Override
                public void onScheduled() {
                    listener.onScheduled();
                }

                @Override
                public void onError(int error) {
                    listener.onError();
                }
            });
        }
    }
}

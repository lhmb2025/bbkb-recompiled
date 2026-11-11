package dev.bbkb.ime.keyboard.inputboard.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Xml
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.shared.Logger
import org.xmlpull.v1.XmlPullParser

/**
 * What the phone's speech recogniser has for each language, outside a dictation: the answer the
 * Language packs screen draws its Voice chips from, the download a newly added keyboard asks
 * for, and the way into the recogniser's own settings.
 *
 * The voice languages belong to the recogniser (on most phones, Google's speech service), not to
 * this keyboard. It can be asked what it has and asked to fetch one (Android 13+); it cannot be
 * made to, and nothing here can remove one.
 */
object VoiceLanguages {

    private const val TAG = "VoiceLanguages"

    /** "Download voice languages": ask for the voice language of each keyboard the user adds. */
    const val PREF_AUTO_DOWNLOAD = "voice_input_download_languages"

    /**
     * Where the last answer is kept, per recogniser, so the chips are there the moment the screen
     * opens. Under the voice picker's cache prefix, which backups leave out: the answer describes
     * this phone.
     */
    private const val SUPPORT_CACHE_KEY = "voice_input_language_cache_support"

    /** How long the recogniser is given to say what it has. */
    private const val ANSWER_MILLIS = 5_000L

    /** How long a recogniser is kept for a download it has not reported on. */
    private const val DOWNLOAD_HOLD_MILLIS = 120_000L

    /** The four lists a recogniser reports, as language tags ("fr-FR"). */
    data class Snapshot(
        val installed: List<String>,
        val online: List<String>,
        val pending: List<String>,
        val supported: List<String>,
    ) {
        /**
         * Whether the recogniser has the voice language for a keyboard or pack locale on the phone,
         * so dictation in it works offline. Takes the locale in any of the app's spellings (`fr`,
         * `pt_BR`, `zh_TW_stroke`); an installed regional variant counts, as it does in a dictation.
         * False for a language that is on its way, only works online or is not offered at all.
         */
        fun isDownloaded(locale: String?): Boolean {
            val tag = VoiceLanguageTags.normalise(locale) ?: return false
            return match(tag, VoiceLanguageTags.likelyRegion(tag), installed) != null
        }

        /** The recogniser's tag to download for [locale], or null: already usable, on its way, or not offered. */
        fun downloadable(locale: String?): String? {
            val tag = VoiceLanguageTags.normalise(locale) ?: return null
            val region = VoiceLanguageTags.likelyRegion(tag)
            if (match(tag, region, installed) != null || match(tag, region, online) != null) return null
            if (match(tag, region, pending) != null) return null
            return match(tag, region, supported)
        }

        private fun match(tag: String, region: String?, candidates: List<String>) =
            VoiceLanguageTags.bestMatch(tag, region, candidates)

        internal fun encode(): String =
            listOf(installed, online, pending, supported).joinToString("|") { it.joinToString(",") }

        internal companion object {
            fun decode(stored: String?): Snapshot? {
                val parts = stored?.split("|") ?: return null
                if (parts.size != 4) return null
                val lists = parts.map { part -> part.split(",").filter { it.isNotBlank() } }
                return Snapshot(lists[0], lists[1], lists[2], lists[3])
            }
        }
    }

    /** The last answer of the recogniser the "Speech recognizer" setting resolves to now, or null. */
    @JvmStatic
    fun snapshot(context: Context): Snapshot? = try {
        val app = context.applicationContext
        Snapshot.decode(PrefsManager.getPrefs(app).getString(cacheKey(InstalledVoiceRecognizers.resolve(app)), null))
    } catch (e: RuntimeException) {
        null
    }

    /**
     * Asks the recogniser what it has (Android 13+) and reports the answer on the main thread:
     * the fresh one, or the last one kept when it does not answer. Null before Android 13 and
     * for a recogniser that has never answered.
     */
    @JvmStatic
    fun refresh(context: Context, onResult: (Snapshot?) -> Unit = {}) {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            onMain { onResult(null) }
            return
        }
        onMain {
            ask(app) { recognizer, fresh ->
                recognizer?.destroy()
                onResult(fresh ?: snapshot(app))
            }
        }
    }

    /**
     * A keyboard for [keyboardLocale] was just added: ask the recogniser to download its voice
     * language, when "Download voice languages" is on, voice input is, and the recogniser offers
     * one that is not already there or on its way. The recogniser decides when the download
     * happens and may ask the user itself.
     */
    @JvmStatic
    fun downloadForKeyboard(context: Context, keyboardLocale: String?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val app = context.applicationContext
        val prefs = PrefsManager.getPrefs(app)
        if (!prefs.getBoolean("voice_input_enabled", true) || !prefs.getBoolean(PREF_AUTO_DOWNLOAD, true)) return
        onMain {
            ask(app) { recognizer, fresh ->
                val target = fresh?.downloadable(keyboardLocale)
                if (recognizer == null || target == null) {
                    recognizer?.destroy()
                } else {
                    Logger.info(TAG, "Asking for the voice language $target for keyboard $keyboardLocale")
                    download(app, recognizer, target)
                }
            }
        }
    }

    /**
     * The recogniser's own settings screen, where its offline languages are managed, with the
     * name of the app it belongs to; null when the recogniser declares none this keyboard can open.
     */
    @JvmStatic
    fun settingsLink(context: Context): Pair<Intent, String>? {
        val pm = context.packageManager
        return try {
            val component = recognizerComponent(context) ?: return null
            val info = pm.getServiceInfo(component, PackageManager.GET_META_DATA)
            val activity = info.loadXmlMetaData(pm, RecognitionService.SERVICE_META_DATA)?.use { parser ->
                var type = parser.next()
                while (type != XmlPullParser.START_TAG && type != XmlPullParser.END_DOCUMENT) type = parser.next()
                if (type != XmlPullParser.START_TAG || parser.name != "recognition-service") {
                    null
                } else {
                    val attributes = pm.getResourcesForApplication(info.applicationInfo)
                        .obtainAttributes(Xml.asAttributeSet(parser), intArrayOf(android.R.attr.settingsActivity))
                    try { attributes.getString(0) } finally { attributes.recycle() }
                }
            } ?: return null
            val intent = Intent(Intent.ACTION_MAIN)
                .setClassName(component.packageName, activity)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val target = pm.resolveActivity(intent, 0)?.activityInfo
            if (target == null || !target.exported) return null
            intent to pm.getApplicationLabel(info.applicationInfo).toString()
        } catch (e: Exception) {
            Logger.debug(TAG, "No recogniser settings screen: $e")
            null
        }
    }

    /** The service the setting resolves to; null for the platform's on-device recogniser, which names none. */
    private fun recognizerComponent(context: Context): ComponentName? {
        val selection = InstalledVoiceRecognizers.resolve(context)
        val flat = when (selection.kind) {
            VoiceRecognizerChoice.Kind.COMPONENT -> selection.component
            VoiceRecognizerChoice.Kind.SYSTEM_DEFAULT -> InstalledVoiceRecognizers.systemDefaultService(context)
            else -> null
        }
        return flat?.let(ComponentName::unflattenFromString)
    }

    private fun cacheKey(selection: VoiceRecognizerChoice.Selection): String {
        val id = selection.id()
        return if (id.isNullOrBlank()) SUPPORT_CACHE_KEY else SUPPORT_CACHE_KEY + "_" + id.trim()
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else Handler(Looper.getMainLooper()).post(block)
    }

    /**
     * Builds a recogniser and asks it for its lists. [then] gets the recogniser, which it must
     * destroy, and the answer; a null recogniser means none could be built, a null answer that
     * it failed or did not reply in [ANSWER_MILLIS]. A good answer is kept for [snapshot].
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun ask(app: Context, then: (SpeechRecognizer?, Snapshot?) -> Unit) {
        val selection = InstalledVoiceRecognizers.resolve(app)
        val recognizer = try {
            InstalledVoiceRecognizers.create(app, selection)
        } catch (e: RuntimeException) {
            null
        }
        if (recognizer == null) {
            then(null, null)
            return
        }
        val handler = Handler(Looper.getMainLooper())
        var answered = false
        val giveUp = Runnable {
            if (!answered) {
                answered = true
                then(recognizer, null)
            }
        }
        fun answer(snapshot: Snapshot?) {
            if (answered) return
            answered = true
            handler.removeCallbacks(giveUp)
            if (snapshot != null) {
                PrefsManager.getPrefs(app).edit().putString(cacheKey(selection), snapshot.encode()).apply()
            }
            then(recognizer, snapshot)
        }
        handler.postDelayed(giveUp, ANSWER_MILLIS)
        try {
            VoiceLanguageSupport.lists(recognizer, request(app, null), app.mainExecutor,
                object : VoiceLanguageSupport.ListsListener {
                    override fun onLists(installed: List<String>, online: List<String>,
                            pending: List<String>, supported: List<String>) =
                        answer(Snapshot(installed, online, pending, supported))

                    override fun onFailed() = answer(null)
                })
        } catch (e: RuntimeException) {
            Logger.debug(TAG, "Could not ask the recogniser for its languages: $e")
            answer(null)
        }
    }

    /** Requests [tag] and lets the recogniser go when it reports, or after [DOWNLOAD_HOLD_MILLIS]. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun download(app: Context, recognizer: SpeechRecognizer, tag: String) {
        val handler = Handler(Looper.getMainLooper())
        var released = false
        fun release(refreshAfter: Boolean) {
            if (released) return
            released = true
            handler.removeCallbacksAndMessages(null)
            recognizer.destroy()
            if (refreshAfter) refresh(app)
        }
        handler.postDelayed({ release(false) }, DOWNLOAD_HOLD_MILLIS)
        try {
            VoiceLanguageSupport.download(recognizer, request(app, tag), app.mainExecutor,
                object : VoiceLanguageSupport.DownloadListener {
                    override fun onProgress(percent: Int) = Unit
                    override fun onSuccess() = release(true)
                    override fun onScheduled() = release(true)
                    override fun onError() = release(false)
                })
        } catch (e: RuntimeException) {
            Logger.debug(TAG, "Voice language download request failed: $e")
            release(false)
        }
    }

    @VisibleForTesting
    internal fun request(app: Context, tag: String?): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
            if (tag != null) {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
            }
        }
}

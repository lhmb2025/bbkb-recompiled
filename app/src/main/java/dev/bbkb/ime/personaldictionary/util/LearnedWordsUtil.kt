package dev.bbkb.ime.personaldictionary.util

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.blackberry.nuanceshim.NuanceSDK
import com.blackberry.nuanceshim.languagepack.LanguagePackManager
import dev.bbkb.ime.core.engine.engineLoaderDispatcher
import dev.bbkb.ime.core.locale.ResourceLocaleUtils
import dev.bbkb.ime.core.locale.SubtypeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*
import kotlin.collections.HashSet

/**
 * The engine's (DLM) learned words, not filtered against the Android User Dictionary (see
 * [getLearnedWords]).
 *
 * @param str the model name (unused)
 */
class LearnedWordsUtil @VisibleForTesting internal constructor(
    private val mNuanceSDK: NuanceSDK,
    context: Context,
    @Suppress("UNUSED_PARAMETER") str: String
) {
    private val appContext: Context = context.applicationContext

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        @Volatile
        private var sInstance: LearnedWordsUtil? = null

        /** Drops the cached instance, which otherwise keeps a disposed NuanceSDK after re-init. */
        @JvmStatic
        fun reset() {
            sInstance = null
        }

        /** @param str the model name (unused) */
        @JvmStatic
        fun getInstance(nuanceSDK: NuanceSDK, context: Context, str: String): LearnedWordsUtil {
            return sInstance ?: synchronized(LearnedWordsUtil::class.java) {
                sInstance ?: LearnedWordsUtil(nuanceSDK, context, str).also { sInstance = it }
            }
        }
    }

    /**
     * Enumerates on the engine's loader thread (an IO thread must never call the engine) and
     * delivers [listener] on the main thread.
     */
    fun getLearnedWords(listener: LearnedWordsListener, includeShortcuts: Boolean) {
        scope.launch {
            val words = withContext(engineLoaderDispatcher) { getLearnedWords(false, includeShortcuts) }
            withContext(Dispatchers.Main) { listener.complete(words) }
        }
    }

    /**
     * The engine attaches the learned model only inside `NuanceSDK.setLanguage`, so a settings
     * process that never bound the keyboard would enumerate nothing. Give it the keyboard's
     * languages first, as the spell checker does.
     */
    private fun ensureEngineHasLanguage() {
        // Not getPrimaryLanguage(): it answers English for an engine with nothing loaded.
        if (mNuanceSDK.isDLMAttached) return
        val locales: List<Locale> = try {
            SubtypeManager.ensureInitialized(appContext)
            val subtypes = SubtypeManager.getInstance()
            ResourceLocaleUtils.getKeyboardLocales(
                subtypes.currentSubtypeLocale, subtypes.currentSubtypeAdditionalLocales)
        } catch (e: Exception) {
            emptyList()
        }
        val wanted = locales.ifEmpty { listOf(Locale.US) }.toTypedArray()
        val set = try {
            LanguagePackManager.getInstance(appContext).setLanguages(mNuanceSDK, wanted)
        } catch (e: Exception) {
            false
        }
        if (!set && !mNuanceSDK.setLanguage(wanted)) {
            mNuanceSDK.setLanguage(arrayOf(Locale.US))
        }
    }

    /** Calls the engine: run it on the loader thread (see the listener overload). */
    fun getLearnedWords(toLowerCase: Boolean, explicit: Boolean): List<String> {
        ensureEngineHasLanguage()
        val dlmWords = if (explicit) {
            mNuanceSDK.workDLMWordsExplicitly
        } else {
            mNuanceSDK.dlmWords
        }
        val learnedSet = HashSet(dlmWords)

        // Never subtract the Android User Dictionary: this app mirrors learned words into it,
        // so the subtraction would erase every entry.
        if (toLowerCase) {
            val lowered = learnedSet.map { it.lowercase(Locale.getDefault()) }
            learnedSet.clear()
            learnedSet.addAll(lowered)
        }

        val result = learnedSet.toTypedArray()
        Arrays.sort(result)
        return result.toList()
    }
}

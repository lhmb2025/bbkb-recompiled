package dev.bbkb.ime.personaldictionary.util

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.blackberry.nuanceshim.NuanceSDK
import com.blackberry.nuanceshim.languagepack.LanguagePackManager
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
 * Provides access to NuanceSDK's learned words.
 *
 * Returns the DLM's learned words. Note this does NOT subtract the Android User Dictionary —
 * doing so made the Learned Words screen permanently empty, because this app syncs learned words
 * into the AUD. See getLearnedWords().
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

        /**
         * Drops the cached instance. Audit PD-21: the singleton cached the *first*
         * NuanceSDK forever, so after a NuanceSDK.dispose() + re-init this class still
         * held the disposed engine (mNativeHandle == 0) and every DLM call went into a
         * null handle. PersonalDictionaryUtil.shutDown and
         * PersonalDictionaryManager.shutDown already clear their own singletons.
         */
        @JvmStatic
        fun reset() {
            sInstance = null
        }

        @JvmStatic
        fun getInstance(nuanceSDK: NuanceSDK, context: Context, str: String): LearnedWordsUtil {
            return sInstance ?: synchronized(LearnedWordsUtil::class.java) {
                sInstance ?: LearnedWordsUtil(nuanceSDK, context, str).also { sInstance = it }
            }
        }
    }

    /**
     * Audit PD-21: this used to start a raw unnamed Thread per call and deliver the
     * callback on it, with no main-thread hop - and the caller is a settings screen.
     */
    fun getLearnedWords(listener: LearnedWordsListener, includeShortcuts: Boolean) {
        scope.launch {
            val words = getLearnedWords(false, includeShortcuts)
            withContext(Dispatchers.Main) { listener.complete(words) }
        }
    }

    /**
     * The engine attaches the learned model only inside `NuanceSDK.setLanguage`, which the
     * keyboard calls from its dictionary setup. A settings process that has never bound the
     * keyboard (Personal learned words opened straight after an install or a force-stop) has
     * an engine with no language and no model, so the enumeration came back empty and the
     * screen said "No learned words" for a full model (KEY2, 2026-10-17). Give the engine the
     * keyboard's languages first, the way the spell checker does for its own session.
     */
    private fun ensureEngineHasLanguage() {
        // (getPrimaryLanguage() answers English for an engine with nothing loaded, so it cannot
        // be the test; the attached model is.)
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

    fun getLearnedWords(toLowerCase: Boolean, explicit: Boolean): List<String> {
        ensureEngineHasLanguage()
        val dlmWords = if (explicit) {
            mNuanceSDK.workDLMWordsExplicitly
        } else {
            mNuanceSDK.dlmWords
        }
        val learnedSet = HashSet(dlmWords)

        /* Do NOT subtract the Android User Dictionary here.
         *
         * This used to do `learnedSet.removeAll(audWords)` to "avoid duplicates", which made the
         * Learned Words screen permanently empty: this app SYNCS learned words into the AUD, so
         * every DLM word is by construction also an AUD word and the filter removed 100% of them.
         * Measured on device 2026-08-03: dlm=8, aud=11, afterSubtract=0 — the screen showed
         * "No learned words" while the engine was actively predicting from those 8.
         *
         * The DLM is the authoritative record of "words learned from your typing"; the AUD copy is
         * a mirror this app writes itself, so subtracting it can only ever erase real entries.
         * Duplication with the User Dictionary screen is not a concern — that is a separate screen
         * listing a separate thing. */
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

package dev.bbkb.ime.personaldictionary

import android.content.Context
import androidx.annotation.VisibleForTesting
import dev.bbkb.ime.personaldictionary.sync.AudSyncer
import dev.bbkb.ime.personaldictionary.util.LearnedWordsUtil
import dev.bbkb.ime.personaldictionary.util.LogUtil
import com.blackberry.nuanceshim.NuanceSDK
import java.io.File
import java.io.IOException

/**
 * Entry point and singleton factory for the Personal Dictionary subsystem.
 *
 * Manages initialization, lifecycle, and access to dictionary utilities including
 * [PersonalDictionaryUtil] for word substitutions and [LearnedWordsUtil] for NuanceSDK learned words.
 *
 * **Renamed from:** `BASL.kt` (original `com.blackberry.basl` package)
 *
 * @see PersonalDictionaryUtil
 * @see LearnedWordsUtil
 * @see AudSyncer
 */
class PersonalDictionaryManager private constructor(
    private val nuanceSDK: NuanceSDK,
    context: Context,
    file: File
) {
    private var audSyncer: AudSyncer? = null
    private var context: Context? = context.applicationContext
    private var internalStorageDir: File? = null
    private var pdu: PersonalDictionaryUtil? = null

    init {
        LogUtil.d(TAG, "Creating PersonalDictionaryManager instance")
        // (The requireNotNull(file) that stood here was a decompiled null check on a
        // non-null Kotlin File parameter - the type system already guarantees it.)
        setInternalStorageDirectory(file)
        audSyncer = AudSyncer.getInstance()
    }

    @Throws(IOException::class)
    fun getPersonalDictionaryUtil(str: String, @Suppress("UNUSED_PARAMETER") str2: String): PersonalDictionaryUtil {
        pdu = PersonalDictionaryUtil.getInstance(context!!, internalStorageDir!!.absolutePath, str, nuanceSDK)
        return pdu!!
    }

    fun getLearnedWordsUtil(str: String): LearnedWordsUtil {
        return LearnedWordsUtil.getInstance(nuanceSDK, context!!, str)
    }

    @Throws(IOException::class)
    fun setInternalStorageDirectory(file: File) {
        if (file.exists()) {
            require(file.isDirectory) { "Provided directory must be a folder" }
        }
        // Audit PD-35: this used to be
        // `require(!path.startsWith(Environment.getExternalStorageDirectory()...))`.
        // getExternalStorageDirectory() is deprecated since API 29 and, under scoped
        // storage, no longer describes where an app may write - so the check was both
        // deprecated and no longer meaningful. The intent ("this must be app-internal")
        // is expressible exactly with the Context this class already holds.
        val filesDir = context?.filesDir
        require(filesDir == null || file.canonicalPath.startsWith(filesDir.canonicalPath)) {
            "Provided directory must be on internal storage"
        }
        if (!file.exists()) {
            file.mkdirs()
            if (!file.isDirectory) {
                throw IOException("Failed to create support library storage directory: " + file.path)
            }
        }
        if (!file.canWrite()) {
            throw IOException("Cannot write to support library storage directory: " + file.path)
        }
        internalStorageDir = file
    }

    @Synchronized
    fun shutDown(clearData: Boolean) {
        LogUtil.d(TAG, "Shutting down PersonalDictionaryManager")
        audSyncer?.shutDown(clearData)
        audSyncer = null
        pdu?.shutDown(clearData)
        pdu = null
        // Otherwise LearnedWordsUtil keeps the now-disposed NuanceSDK (audit PD-21).
        LearnedWordsUtil.reset()
        context = null
        sInstance = null
    }

    override fun toString(): String {
        return "PersonalDictionaryManager Singleton"
    }

    companion object {
        private const val TAG = "PersonalDictionaryManager"
        @Volatile
        private var sInstance: PersonalDictionaryManager? = null

        @JvmStatic
        @Throws(IOException::class)
        fun getInstance(nuanceSDK: NuanceSDK, context: Context, file: File): PersonalDictionaryManager {
            return sInstance ?: synchronized(PersonalDictionaryManager::class.java) {
                sInstance ?: PersonalDictionaryManager(nuanceSDK, context, file).also { sInstance = it }
            }
        }

        @VisibleForTesting
        fun resetInstanceForTest() {
            sInstance = null
        }
    }
}

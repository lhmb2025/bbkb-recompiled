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
 * Singleton entry point for the personal dictionary: owns [PersonalDictionaryUtil] (word
 * substitutions), [LearnedWordsUtil] (engine learned words) and the [AudSyncer].
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
        setInternalStorageDirectory(file)
        audSyncer = AudSyncer.getInstance()
    }

    @Throws(IOException::class)
    /**
     * @param resourceDir the resource directory for the built-in shortcuts
     * @param modelName unused (model name)
     */
    fun getPersonalDictionaryUtil(resourceDir: String, @Suppress("UNUSED_PARAMETER") modelName: String): PersonalDictionaryUtil {
        pdu = PersonalDictionaryUtil.getInstance(context!!, internalStorageDir!!.absolutePath, resourceDir, nuanceSDK)
        return pdu!!
    }

    /** @param modelName the model name (unused) */
    fun getLearnedWordsUtil(modelName: String): LearnedWordsUtil {
        return LearnedWordsUtil.getInstance(nuanceSDK, context!!, modelName)
    }

    @Throws(IOException::class)
    fun setInternalStorageDirectory(file: File) {
        if (file.exists()) {
            require(file.isDirectory) { "Provided directory must be a folder" }
        }
        // Must be app-internal storage.
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
        // Otherwise LearnedWordsUtil keeps the now-disposed NuanceSDK.
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

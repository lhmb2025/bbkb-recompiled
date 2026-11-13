package dev.bbkb.ime.core.layout

import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetManager
import androidx.core.util.AtomicFile
import dev.bbkb.ime.core.layout.LetterLayout.Origin
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.shared.Logger
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Every letter layout: built-in (`assets/letter_layouts/<id>.xml`, parsed lazily) and user-imported
 * (`files/layouts/letter/<id>.xml`). The typing path goes through [LetterLayoutChoice], which caches
 * parsed layouts; [save] and [delete] [invalidate] that cache because a re-import keeps the id.
 * A document that does not parse is skipped and logged, never half-applied.
 *
 * @param builtInDocuments source of built-in documents (the app's assets when null; tests pass their own)
 */
class LetterLayoutRegistry @JvmOverloads constructor(
    context: Context,
    builtInDocuments: BuiltInDocuments? = null,
) {
    interface BuiltInDocuments {
        /** The id of every built-in document: its file name without `.xml`. */
        fun ids(): List<String>

        /** The document [id] names, or null when there is none. The caller closes it. */
        fun open(id: String): InputStream?
    }

    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences = PrefsManager.getPrefs(appContext)
    private val builtIns: BuiltIns =
        builtInDocuments?.let { BuiltIns(it) } ?: assetBuiltIns(appContext.assets)

    init {
    }

    /** `files/layouts/letter`. Shared through the app's FileProvider (`res/xml/file_paths.xml`). */
    val directory: File get() = directoryOf(appContext)

    /** Every readable user layout, by name. A file that no longer parses is skipped, not deleted. */
    fun list(): List<LetterLayout> =
        directory.listFiles { f -> f.isFile && f.name.endsWith(LetterLayoutXml.FILE_SUFFIX) }
            ?.mapNotNull { read(it) }
            ?.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            ?: emptyList()

    /** The user layout with [id], or null when there is none or it does not parse. */
    fun get(id: String): LetterLayout? = if (LetterLayout.isValidId(id)) read(fileFor(id)) else null

    /** The file for [id]; the id check is what keeps the path inside [directory]. */
    fun fileFor(id: String): File {
        require(LetterLayout.isValidId(id)) { "invalid layout id $id" }
        return File(directory, id + LetterLayoutXml.FILE_SUFFIX)
    }

    /**
     * Writes [layout], replacing any user layout with its id, through [AtomicFile] so [list] always
     * sees a whole document even after a crash. A leftover `.new` is never listed (only `*.xml` is).
     */
    @Throws(IOException::class)
    fun save(layout: LetterLayout) {
        val dir = directory
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("could not create ${dir.absolutePath}")
        val file = fileFor(layout.id)
        val bytes = LetterLayoutXml.serialize(layout).toByteArray(Charsets.UTF_8)
        val atomic = AtomicFile(file)
        var out: FileOutputStream? = null
        try {
            out = atomic.startWrite()
            out.write(bytes)
            atomic.finishWrite(out)
        } catch (e: IOException) {
            atomic.failWrite(out)
            throw e
        }
        // finishWrite only logs a failed rename, which leaves the document in the .new file.
        val pending = File(file.path + ".new")
        if (pending.exists()) {
            pending.delete()
            throw IOException("could not move ${file.name} into place")
        }
        invalidate()
    }

    /** Deletes the user layout with [id]; if it was the active one, the keys go back to their own letters. */
    fun delete(id: String): Boolean {
        if (!LetterLayout.isValidId(id)) return false
        val file = fileFor(id)
        val deleted = file.delete()
        // Also any `<id>.xml.new` a crash during save left behind.
        AtomicFile(file).delete()
        invalidate()
        return deleted
    }

    private fun read(file: File): LetterLayout? {
        if (!file.isFile) return null
        if (file.length() > LetterLayoutXml.MAX_BYTES) {
            Logger.warn(TAG, "Skipping ${file.name}: larger than ${LetterLayoutXml.MAX_BYTES / 1024} KB")
            return null
        }
        // The file name is the id; a file copied in by hand under another name keeps its name.
        val stem = file.name.removeSuffix(LetterLayoutXml.FILE_SUFFIX)
        val validStem = LetterLayout.isValidId(stem)
        return try {
            val layout = file.inputStream().use { input ->
                LetterLayoutXml.parse(input, Origin.USER) { if (validStem) stem else LetterLayout.newId() }
            }
            if (layout.id == stem || !validStem) layout else layout.withId(stem)
        } catch (e: InvalidLetterLayoutException) {
            Logger.warn(TAG, "Skipping ${file.name}: ${e.message}")
            null
        } catch (e: IOException) {
            Logger.warn(TAG, "Could not read ${file.name}: ${e.message}")
            null
        }
    }

    /** The id of every built-in document, whether or not it parses. */
    fun builtInIds(): List<String> = builtIns.ids

    /** The built-in layout with [id], or null when there is none or it does not parse. */
    fun builtIn(id: String): LetterLayout? = builtIns.get(id)

    /**
     * The default built-in layout for [language] (app spelling: `iw`, not `he`), [keypad], [keypadType]
     * and [layoutSet] (the subtype's `KeyboardLayoutSet`), or null. Only language-bound, non-opt-in
     * `pkb` documents qualify; a bound keypad, type or layout set must be known and equal. Ties break
     * by specificity (keypad, type, layout set, fewer languages), then id == language, then lowest id.
     */
    fun builtIn(language: String?, keypad: String?, keypadType: String?, layoutSet: String?): LetterLayout? =
        builtIns.select(
            language,
            keypad?.lowercase(Locale.ROOT),
            keypadType?.lowercase(Locale.ROOT),
            layoutSet?.lowercase(Locale.ROOT),
        )

    /** The parsed built-ins and the selections made from them, for one source of documents. */
    internal class BuiltIns(private val documents: BuiltInDocuments) {

        val ids: List<String> by lazy {
            try {
                documents.ids().filter { LetterLayout.isValidId(it) }.sorted()
            } catch (e: Exception) {
                Logger.warn(TAG, "Could not list the built-in layouts: ${e.message}")
                emptyList()
            }
        }

        /** By id: the parsed layout, or [NONE] for a document that is missing or does not parse. */
        private val byId = ConcurrentHashMap<String, Any>()

        /** By "language|keypad|keypadType|layoutSet": the selected layout, or [NONE]. */
        private val selections = ConcurrentHashMap<String, Any>()

        /** By id: the document's `<bind>` read alone, or [NONE] when it could not be. */
        private val bindings = ConcurrentHashMap<String, Any>()

        /**
         * Whether document [id] could serve [language], from its `<bind>` alone so selection need not
         * parse every document. True when the binding cannot be read; the full parse decides then.
         */
        private fun mayServe(id: String, language: String): Boolean {
            if (byId.containsKey(id)) return true // parsed already, nothing to save
            val binding = bindings.getOrPut(id) { peek(id) ?: NONE } as? LetterLayout.Binding ?: return true
            return binding.isLanguageBound && binding.matchesLanguage(language)
        }

        private fun peek(id: String): LetterLayout.Binding? = try {
            if (!LetterLayout.isValidId(id)) null else documents.open(id)?.use { LetterLayoutXml.peekBinding(it) }
        } catch (e: IOException) {
            null
        }

        fun get(id: String): LetterLayout? {
            if (!LetterLayout.isValidId(id)) return null
            byId[id]?.let { return it as? LetterLayout }
            val layout = load(id)
            byId[id] = layout ?: NONE
            return layout
        }

        private fun load(id: String): LetterLayout? = try {
            val stream = documents.open(id) ?: return null
            val layout = stream.use { LetterLayoutXml.parse(it, Origin.BUILT_IN) }
            if (layout.id != id) {
                Logger.warn(TAG, "Skipping built-in $id.xml: it names itself \"${layout.id}\"")
                null
            } else layout
        } catch (e: InvalidLetterLayoutException) {
            Logger.warn(TAG, "Skipping built-in $id.xml: ${e.message}")
            null
        } catch (e: IOException) {
            Logger.warn(TAG, "Could not read built-in $id.xml: ${e.message}")
            null
        }

        fun select(language: String?, keypad: String?, keypadType: String?, layoutSet: String?): LetterLayout? {
            if (language.isNullOrEmpty()) return null
            val cacheKey = "$language|$keypad|$keypadType|$layoutSet"
            selections[cacheKey]?.let { return it as? LetterLayout }
            val result = ids.filter { mayServe(it, language) }.mapNotNull { get(it) }
                .filter { !it.optIn && LetterLayout.Target.PKB in it.targets && selects(it.binding, language, keypad, keypadType, layoutSet) }
                .sortedWith(SPECIFICITY.thenByDescending { it.id == language }.thenBy { it.id })
                .firstOrNull()
            selections[cacheKey] = result ?: NONE
            return result
        }

        private fun selects(
            binding: LetterLayout.Binding,
            language: String,
            keypad: String?,
            keypadType: String?,
            layoutSet: String?,
        ): Boolean {
            if (!binding.isLanguageBound || !binding.matchesLanguage(language)) return false
            if (binding.keypad != LetterLayout.ANY && binding.keypad != keypad) return false
            if (binding.keypadType != LetterLayout.ANY && !binding.keypadTypeMatches(keypadType)) return false
            if (binding.layoutSet != LetterLayout.ANY && !binding.layoutSetMatches(layoutSet)) return false
            return true
        }

        private companion object {
            val NONE = Any()

            /** Most specific first: named keypad, keypad type, layout set, then fewer languages. */
            val SPECIFICITY: Comparator<LetterLayout> =
                compareByDescending<LetterLayout> { it.binding.keypad != LetterLayout.ANY }
                    .thenByDescending { it.binding.keypadType != LetterLayout.ANY }
                    .thenByDescending { it.binding.layoutSet != LetterLayout.ANY }
                    .thenBy { it.binding.languages.size }
        }
    }

    private class AssetDocuments(private val assets: AssetManager) : BuiltInDocuments {
        override fun ids(): List<String> =
            assets.list(ASSET_DIRECTORY).orEmpty()
                .filter { it.endsWith(LetterLayoutXml.FILE_SUFFIX) }
                .map { it.removeSuffix(LetterLayoutXml.FILE_SUFFIX) }

        override fun open(id: String): InputStream? = try {
            assets.open("$ASSET_DIRECTORY/$id${LetterLayoutXml.FILE_SUFFIX}")
        } catch (e: IOException) {
            null
        }
    }

    companion object {
        /** Retired: the one layout for every language; read only by [LetterLayoutChoice.migrate]. */
        const val PREF_ACTIVE_LAYOUT: String = "pref_pkb_active_layout"

        /** Retired predecessor of [PREF_ACTIVE_LAYOUT]; read once, then removed. */
        const val LEGACY_PREF_ACTIVE_LETTER_MAP: String = "pref_pkb_active_letter_map"

        /** Under the app's files directory. */
        const val DIRECTORY: String = "layouts/letter"

        /** Under the app's assets. */
        const val ASSET_DIRECTORY: String = "letter_layouts"

        private const val TAG = "LetterLayouts"

        @JvmStatic
        fun directoryOf(context: Context): File = File(context.applicationContext.filesDir, DIRECTORY)

        /** The process's copy of the app's built-ins, so each document is parsed once. */
        @Volatile
        private var assetBuiltIns: BuiltIns? = null

        private fun assetBuiltIns(assets: AssetManager): BuiltIns =
            assetBuiltIns ?: synchronized(this) {
                assetBuiltIns ?: BuiltIns(AssetDocuments(assets)).also { assetBuiltIns = it }
            }

        /** Moves [LEGACY_PREF_ACTIVE_LETTER_MAP] to [PREF_ACTIVE_LAYOUT] once, for [LetterLayoutChoice.migrate]. */
        @JvmStatic
        fun migrateActivePreference(prefs: SharedPreferences) {
            if (prefs.contains(PREF_ACTIVE_LAYOUT) || !prefs.contains(LEGACY_PREF_ACTIVE_LETTER_MAP)) return
            val legacy = prefs.getString(LEGACY_PREF_ACTIVE_LETTER_MAP, "") ?: ""
            prefs.edit()
                .putString(PREF_ACTIVE_LAYOUT, legacy)
                .remove(LEGACY_PREF_ACTIVE_LETTER_MAP)
                .apply()
        }

        /** Drops [LetterLayoutChoice]'s cached layouts and re-decides the engine's key letters ([LetterLayoutEngineSync]). */
        @JvmStatic
        fun invalidate() {
            LetterLayoutChoice.invalidate()
            LetterLayoutEngineSync.onActiveLayoutChanged()
        }
    }
}

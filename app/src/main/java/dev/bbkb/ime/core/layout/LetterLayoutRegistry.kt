package dev.bbkb.ime.core.layout

import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetManager
import dev.bbkb.ime.core.layout.LetterLayout.Origin
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.shared.Logger
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Every letter layout on the phone: the ones the app ships, read from
 * `assets/letter_layouts/<id>.xml` when first asked for, and the ones the user imported, one file
 * each under `files/layouts/letter/<id>.xml`. The id of the layout the physical keys follow is in
 * [PREF_ACTIVE_LAYOUT] (`""` for none).
 *
 * The settings screens and the importer use an instance; the typing path asks [activeForTyping],
 * which keeps the parsed active layout so a keystroke never reads a file. That copy is keyed by
 * the active id, read from preferences on each call, so switching layouts (here, or by any other
 * write to the preference) takes effect on the next key; [save] and [delete] drop it as well,
 * because re-importing a layout under the same id changes the file and not the id.
 *
 * A document that does not parse is skipped and logged, never half-applied: a user file stays on
 * disk for the user to fix or delete, and a built-in one is simply not offered.
 *
 * @param builtInDocuments where the built-in documents come from; the app's assets when null.
 *   Tests hand in their own so selection can be checked without shipping documents.
 */
class LetterLayoutRegistry @JvmOverloads constructor(
    context: Context,
    builtInDocuments: BuiltInDocuments? = null,
) {

    /** The built-in documents, by id. */
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
        migrateActivePreference(prefs)
    }

    // ── the user's layouts ───────────────────────────────────────────────────

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

    /** The file a layout with [id] lives in. [id] must be a valid id; it is what keeps this in [directory]. */
    fun fileFor(id: String): File {
        require(LetterLayout.isValidId(id)) { "invalid layout id $id" }
        return File(directory, id + LetterLayoutXml.FILE_SUFFIX)
    }

    /**
     * Writes [layout], replacing any user layout with its id. Through a sibling temp file, as
     * `DeviceProfileExporter` writes device configs: a half-written file here is one [list] would
     * skip and the keys would stop following.
     */
    @Throws(IOException::class)
    fun save(layout: LetterLayout) {
        val dir = directory
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("could not create ${dir.absolutePath}")
        val file = fileFor(layout.id)
        val temp = File(dir, file.name + ".tmp")
        try {
            temp.writeText(LetterLayoutXml.serialize(layout), Charsets.UTF_8)
            if (file.exists() && !file.delete()) throw IOException("could not replace ${file.name}")
            if (!temp.renameTo(file)) throw IOException("could not move ${file.name} into place")
        } finally {
            if (temp.exists()) temp.delete()
        }
        invalidate()
    }

    /** Deletes the user layout with [id]; if it was the active one, the keys go back to their own letters. */
    fun delete(id: String): Boolean {
        if (!LetterLayout.isValidId(id)) return false
        val deleted = fileFor(id).delete()
        if (activeId == id) activeId = ""
        invalidate()
        return deleted
    }

    /** The id of the layout the physical keys follow, or `""` for none. */
    var activeId: String
        get() = prefs.getString(PREF_ACTIVE_LAYOUT, "") ?: ""
        set(value) {
            prefs.edit().putString(PREF_ACTIVE_LAYOUT, value).apply()
            invalidate()
        }

    private fun read(file: File): LetterLayout? {
        if (!file.isFile) return null
        if (file.length() > LetterLayoutXml.MAX_BYTES) {
            Logger.warn(TAG, "Skipping ${file.name}: larger than ${LetterLayoutXml.MAX_BYTES / 1024} KB")
            return null
        }
        // The file name is the id this registry addresses it by; a file copied in by hand under
        // another name keeps the name it has.
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

    // ── the built-in layouts ─────────────────────────────────────────────────

    /** The id of every built-in document, whether or not it parses. */
    fun builtInIds(): List<String> = builtIns.ids

    /** The built-in layout with [id], or null when there is none or it does not parse. */
    fun builtIn(id: String): LetterLayout? = builtIns.get(id)

    /**
     * The built-in layout for a keyboard in [language] (the app's spelling: `iw`, not `he`) on a
     * [keypad] keypad (`qwerty`/`qwertz`/`azerty`) of [keypadType] (`3row`, or what the device
     * profile reports), built from the layout set [layoutSet] (the subtype's `KeyboardLayoutSet`:
     * `east_slavic`, `translit`, `qwerty`, …), or null when none is bound to that keyboard.
     *
     * Only documents for the physical keys (`pkb` among their targets) and bound to a language take
     * part: one bound to none is a layout to choose, not a language's default. Among those that
     * match, the most specific binding wins — a named keypad beats `any`, then a named keypad type,
     * then a named layout set, then the document bound to fewer languages, then the one whose id is
     * the language code — and a document bound to a keypad, keypad type or layout set is chosen
     * only when the keyboard's is known and is that one. Remaining ties go to the lowest id, so the
     * answer never depends on the order the files are listed in.
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
            val result = ids.mapNotNull { get(it) }
                .filter { LetterLayout.Target.PKB in it.targets && selects(it.binding, language, keypad, keypadType, layoutSet) }
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

            /**
             * Most specific first: a named keypad, a named keypad type, a named layout set, then
             * fewer languages.
             */
            val SPECIFICITY: Comparator<LetterLayout> =
                compareByDescending<LetterLayout> { it.binding.keypad != LetterLayout.ANY }
                    .thenByDescending { it.binding.keypadType != LetterLayout.ANY }
                    .thenByDescending { it.binding.layoutSet != LetterLayout.ANY }
                    .thenBy { it.binding.languages.size }
        }
    }

    /** The app's assets as [BuiltInDocuments]. */
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
        /** The active layout's id; `""` (or absent) means the keys type their own letters. */
        const val PREF_ACTIVE_LAYOUT: String = "pref_pkb_active_layout"

        /** Where the active id was kept before layouts were XML; read once, then removed. */
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

        /**
         * Moves the active id from [LEGACY_PREF_ACTIVE_LETTER_MAP] to [PREF_ACTIVE_LAYOUT], once:
         * only while the new key is absent and the old one present. Cheap enough to run on every
         * read of the active id, which is what makes "once" hold whichever reader comes first.
         */
        @JvmStatic
        fun migrateActivePreference(prefs: SharedPreferences) {
            if (prefs.contains(PREF_ACTIVE_LAYOUT) || !prefs.contains(LEGACY_PREF_ACTIVE_LETTER_MAP)) return
            val legacy = prefs.getString(LEGACY_PREF_ACTIVE_LETTER_MAP, "") ?: ""
            prefs.edit()
                .putString(PREF_ACTIVE_LAYOUT, legacy)
                .remove(LEGACY_PREF_ACTIVE_LETTER_MAP)
                .apply()
        }

        /** The active id and the layout it named when last read; the layout is null for none or unreadable. */
        private class Snapshot(val id: String, val layout: LetterLayout?)

        @Volatile
        private var snapshot: Snapshot? = null

        /**
         * The active user layout for the typing path, parsed once per change. The file is read on
         * the first key after a change and not again.
         */
        @JvmStatic
        fun activeForTyping(context: Context): LetterLayout? {
            val prefs = PrefsManager.getPrefs(context)
            migrateActivePreference(prefs)
            val id = prefs.getString(PREF_ACTIVE_LAYOUT, "") ?: ""
            snapshot?.let { if (it.id == id) return it.layout }
            val layout = if (id.isEmpty()) null else LetterLayoutRegistry(context).get(id)
            snapshot = Snapshot(id, layout)
            return layout
        }

        /**
         * Drops the typing path's copies — the active layout's and each language's chosen layout
         * ([LetterLayoutChoice]) — so the next key reads them again, and has the engine's key
         * letters decided again ([LetterLayoutEngineSync]).
         */
        @JvmStatic
        fun invalidate() {
            snapshot = null
            LetterLayoutChoice.invalidate()
            LetterLayoutEngineSync.onActiveLayoutChanged()
        }
    }
}

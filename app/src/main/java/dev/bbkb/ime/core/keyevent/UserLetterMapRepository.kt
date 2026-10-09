package dev.bbkb.ime.core.keyevent

import android.content.Context
import android.content.SharedPreferences
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.shared.Logger
import java.io.File
import java.io.IOException

/**
 * The imported letter maps ([UserLetterMap]): one file each under `files/layouts/pkb/<id>.json`,
 * and the id of the one the physical keys follow in [PREF_ACTIVE_LETTER_MAP] (`""` for none).
 *
 * The settings screens and the importer use an instance; the typing path asks [activeForTyping],
 * which keeps the parsed active map so a keystroke never reads a file. That copy is keyed by the
 * active id, read from preferences on each call, so switching maps (here, or by any other write to
 * the preference) takes effect on the next key; [save] and [delete] drop it as well, because
 * re-importing a map under the same id changes the file and not the id.
 */
class UserLetterMapRepository(context: Context) {

    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences = PrefsManager.getPrefs(appContext)

    /** `files/layouts/pkb`. Shared through the app's FileProvider (`res/xml/file_paths.xml`). */
    val directory: File get() = directoryOf(appContext)

    /** Every readable map, by name. A file that no longer parses is skipped, not deleted. */
    fun list(): List<UserLetterMap> =
        directory.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) }
            ?.mapNotNull { read(it) }
            ?.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            ?: emptyList()

    fun get(id: String): UserLetterMap? = if (UserLetterMap.isValidId(id)) read(fileFor(id)) else null

    /** The file a map with [id] lives in. [id] must be a valid id; it is what keeps this in [directory]. */
    fun fileFor(id: String): File {
        require(UserLetterMap.isValidId(id)) { "invalid layout id $id" }
        return File(directory, id + SUFFIX)
    }

    /**
     * Writes [map], replacing any map with its id. Through a sibling temp file, as
     * `DeviceProfileExporter` writes device configs: a half-written file here is one [list] would
     * skip and the keys would stop following.
     */
    @Throws(IOException::class)
    fun save(map: UserLetterMap) {
        val dir = directory
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("could not create ${dir.absolutePath}")
        val file = fileFor(map.id)
        val temp = File(dir, file.name + ".tmp")
        try {
            temp.writeText(map.serialize(), Charsets.UTF_8)
            if (file.exists() && !file.delete()) throw IOException("could not replace ${file.name}")
            if (!temp.renameTo(file)) throw IOException("could not move ${file.name} into place")
        } finally {
            if (temp.exists()) temp.delete()
        }
        invalidate()
    }

    /** Deletes the map with [id]; if it was the active one, the keys go back to their own letters. */
    fun delete(id: String): Boolean {
        if (!UserLetterMap.isValidId(id)) return false
        val deleted = fileFor(id).delete()
        if (activeId == id) activeId = ""
        invalidate()
        return deleted
    }

    /** The id of the map the physical keys follow, or `""` for none. */
    var activeId: String
        get() = prefs.getString(PREF_ACTIVE_LETTER_MAP, "") ?: ""
        set(value) {
            prefs.edit().putString(PREF_ACTIVE_LETTER_MAP, value).apply()
            invalidate()
        }

    private fun read(file: File): UserLetterMap? {
        if (!file.isFile || file.length() > UserLetterMap.MAX_BYTES) return null
        return try {
            val map = UserLetterMap.parse(file.readText(Charsets.UTF_8))
            // The file name is the id this repository addresses it by; a file copied in by hand
            // under another name keeps the name it has.
            val id = file.name.removeSuffix(SUFFIX)
            if (map.id == id || !UserLetterMap.isValidId(id)) map else map.withId(id)
        } catch (e: InvalidLetterMapException) {
            Logger.warn(TAG, "Skipping ${file.name}: ${e.message}")
            null
        } catch (e: IOException) {
            Logger.warn(TAG, "Could not read ${file.name}: ${e.message}")
            null
        }
    }

    companion object {
        /** The active map's id; `""` (or absent) means the keys type their own letters. */
        const val PREF_ACTIVE_LETTER_MAP: String = "pref_pkb_active_letter_map"
        const val DIRECTORY: String = "layouts/pkb"
        private const val SUFFIX = ".json"
        private const val TAG = "UserLetterMaps"

        @JvmStatic
        fun directoryOf(context: Context): File = File(context.applicationContext.filesDir, DIRECTORY)

        /** The active id and the map it named when last read; the map is null for none or unreadable. */
        private class Snapshot(val id: String, val map: UserLetterMap?)

        @Volatile
        private var snapshot: Snapshot? = null

        /**
         * The active map for the typing path, parsed once per change. The file is read on the
         * first key after a change and not again.
         */
        @JvmStatic
        fun activeForTyping(context: Context): UserLetterMap? {
            val prefs = PrefsManager.getPrefs(context)
            val id = prefs.getString(PREF_ACTIVE_LETTER_MAP, "") ?: ""
            snapshot?.let { if (it.id == id) return it.map }
            val map = if (id.isEmpty()) null else UserLetterMapRepository(context).get(id)
            snapshot = Snapshot(id, map)
            return map
        }

        /** Drops the typing path's copy, so the next key reads the active map again. */
        @JvmStatic
        fun invalidate() {
            snapshot = null
        }
    }
}

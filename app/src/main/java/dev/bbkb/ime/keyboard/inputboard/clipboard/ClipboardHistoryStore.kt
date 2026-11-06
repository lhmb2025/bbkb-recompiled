package dev.bbkb.ime.keyboard.inputboard.clipboard

import android.content.Context
import android.util.AtomicFile
import android.util.JsonWriter
import dev.bbkb.ime.core.shared.Logger
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.StringWriter
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * The clipboard history on disk: one JSON file, written through [AtomicFile] so a crash mid-write
 * leaves the previous version rather than a truncated one.
 *
 * ## Where it lives
 *
 * The app's `filesDir`, which is **credential-protected** storage: the manifest sets
 * `defaultToDeviceProtectedStorage`, but the platform ignores that for a non-system app, so the
 * ordinary context's files directory is the credential-protected one. (There is no public API to
 * ask for the credential-protected context explicitly; `createCredentialProtectedStorageContext`
 * is a system API.) So copied text is never readable before the user unlocks — and the file
 * cannot be read until then either: [ClipboardHistoryManager] keeps clips in memory and loads
 * lazily once the user is unlocked.
 *
 * ## Threads
 *
 * [load] is a plain synchronous read; [ClipboardHistoryManager] runs it once per process, on a
 * background thread, the first time the history is needed after unlock, and merges the result on
 * the main thread. The file is a few kilobytes. [save] and [delete] are queued on one
 * process-wide serial executor, so writes land in order and a delete is never overtaken by a save
 * queued before it — including a delete issued from the settings screen while the keyboard has a
 * save in flight.
 *
 * What gets persisted is the caller's decision: the manager filters out Password Keeper rows and
 * oversized clips before calling [save]. Sensitive clips never reach the history at all.
 */
class ClipboardHistoryStore private constructor(
    private val fileProvider: () -> File,
    private val io: Executor,
) {

    /** The stored history, newest-first within each section as saved; empty if none or unreadable. */
    fun load(): List<ClipEntry> {
        val file = atomicFile() ?: return emptyList()
        return try {
            file.openRead().use { decode(String(it.readBytes(), Charsets.UTF_8)) }
        } catch (missing: FileNotFoundException) {
            emptyList()
        } catch (e: IOException) {
            Logger.debug(TAG, "Clipboard history unreadable: ${e.message}")
            emptyList()
        }
    }

    /** Replace the stored history with [entries], on the IO thread. */
    fun save(entries: List<ClipEntry>) {
        val snapshot = entries.toList()
        io.execute { write(snapshot) }
    }

    /** Remove the stored history, on the IO thread. */
    fun delete() {
        io.execute { atomicFile()?.delete() }
    }

    private fun write(entries: List<ClipEntry>) {
        val file = atomicFile() ?: return
        val stream = try {
            file.startWrite()
        } catch (e: IOException) {
            Logger.debug(TAG, "Clipboard history not writable: ${e.message}")
            return
        }
        try {
            stream.write(encode(entries).toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: IOException) {
            file.failWrite(stream)
            Logger.debug(TAG, "Clipboard history write failed: ${e.message}")
        }
    }

    private fun atomicFile(): AtomicFile? = try {
        AtomicFile(fileProvider())
    } catch (e: RuntimeException) {
        // A credential-protected filesDir can refuse to resolve while the user is locked.
        Logger.debug(TAG, "Clipboard history location unavailable: ${e.message}")
        null
    }

    companion object {

        private const val TAG = "ClipboardHistoryStore"

        const val FILE_NAME = "clipboard_history.json"

        /** The file format. A file announcing any other version is ignored, not misread. */
        const val VERSION = 1

        /**
         * One serial thread for every store in the process, so the settings screen's "Clear
         * history" and the keyboard's own writes are ordered against each other.
         */
        private val SHARED_IO: Executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "clipboard-history-io").apply { isDaemon = true }
        }

        /** The store for this app's clipboard history. */
        @JvmStatic
        fun forContext(context: Context): ClipboardHistoryStore {
            val app = context.applicationContext ?: context
            return ClipboardHistoryStore({ File(app.filesDir, FILE_NAME) }, SHARED_IO)
        }

        /** A store on an explicit file and executor: tests pass a temp file and a direct executor. */
        @JvmStatic
        fun forFile(file: File, io: Executor): ClipboardHistoryStore = ClipboardHistoryStore({ file }, io)

        // ── format ───────────────────────────────────────────────────────────

        @JvmStatic
        fun encode(entries: List<ClipEntry>): String {
            val out = StringWriter()
            JsonWriter(out).use { w ->
                w.beginObject()
                w.name("version").value(VERSION.toLong())
                w.name("entries").beginArray()
                for (entry in entries) {
                    w.beginObject()
                    w.name("id").value(entry.id)
                    w.name("text").value(entry.text)
                    if (entry.label != null) w.name("label").value(entry.label)
                    w.name("mime").value(entry.mime)
                    w.name("createdAt").value(entry.createdAtMs)
                    w.name("pinned").value(entry.isPinned)
                    w.name("pinnedAt").value(entry.pinnedAtMs)
                    w.endObject()
                }
                w.endArray()
                w.endObject()
            }
            return out.toString()
        }

        /**
         * Walks the `org.json` tree and checks each field by hand rather than binding the JSON
         * reflectively, for the reason `DistributionManifestParser` gives: reflective binding
         * fills a missing field with null behind Kotlin's back. Forgiving per entry — one
         * malformed row is dropped, the rest load — and strict about the envelope: not JSON
         * (including anything after the object), not an object, or another version yields
         * nothing.
         */
        @JvmStatic
        fun decode(json: String): List<ClipEntry> {
            val obj = try {
                val tokener = JSONTokener(json)
                val root = tokener.nextValue()
                if (tokener.nextClean() != '\u0000') return emptyList()
                root as? JSONObject ?: return emptyList()
            } catch (e: JSONException) {
                return emptyList()
            }
            if (obj.longOrNull("version") != VERSION.toLong()) return emptyList()
            val array = obj.opt("entries") as? JSONArray ?: return emptyList()
            return (0 until array.length()).mapNotNull { i -> (array.opt(i) as? JSONObject)?.toEntry() }
        }

        private fun JSONObject.toEntry(): ClipEntry? {
            val id = longOrNull("id") ?: return null
            val text = stringOrNull("text") ?: return null
            val createdAt = longOrNull("createdAt") ?: return null
            val pinned = booleanOrNull("pinned") ?: false
            return ClipEntry(
                id = id,
                text = text,
                label = stringOrNull("label"),
                mime = stringOrNull("mime") ?: "text/plain",
                createdAtMs = createdAt,
                isPinned = pinned,
                pinnedAtMs = if (pinned) longOrNull("pinnedAt") ?: createdAt else 0L,
            )
        }

        // Named apart from JSONObject's own optLong/optString/optBoolean, which coerce between
        // types. A number with a fraction is truncated, as Gson's asLong truncated it.

        private fun JSONObject.longOrNull(key: String): Long? = (opt(key) as? Number)?.toLong()

        private fun JSONObject.stringOrNull(key: String): String? = opt(key) as? String

        private fun JSONObject.booleanOrNull(key: String): Boolean? = opt(key) as? Boolean
    }
}

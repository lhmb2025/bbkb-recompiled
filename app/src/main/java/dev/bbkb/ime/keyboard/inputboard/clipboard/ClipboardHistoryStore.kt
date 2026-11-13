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
 * The clipboard history on disk: one JSON file via [AtomicFile]. It lives in `filesDir`, which is
 * credential-protected (the platform ignores the manifest's `defaultToDeviceProtectedStorage` for a
 * non-system app), so it is unreadable until unlock. [load] is synchronous; [save] and [delete] go
 * through one process-wide serial executor, so a delete (even from settings) is never overtaken by
 * an earlier save. The caller decides what is persisted.
 */
class ClipboardHistoryStore private constructor(
    private val fileProvider: () -> File,
    private val io: Executor,
) {
    /** The stored history as saved; empty if none or unreadable. */
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

        /** A file announcing any other version is ignored, not misread. */
        const val VERSION = 1

        /** Shared so settings' "Clear history" and the keyboard's writes are ordered. */
        private val SHARED_IO: Executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "clipboard-history-io").apply { isDaemon = true }
        }

        @JvmStatic
        fun forContext(context: Context): ClipboardHistoryStore {
            val app = context.applicationContext ?: context
            return ClipboardHistoryStore({ File(app.filesDir, FILE_NAME) }, SHARED_IO)
        }

        /** For tests: an explicit file and executor. */
        @JvmStatic
        fun forFile(file: File, io: Executor): ClipboardHistoryStore = ClipboardHistoryStore({ file }, io)

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
         * Checks each field by hand (reflective binding fills missing fields with null behind
         * Kotlin's back). A malformed row is dropped; a bad envelope, trailing data or another
         * version yields nothing.
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

        // Unlike JSONObject's optLong/optString/optBoolean, these do not coerce between types.
        // A number with a fraction is truncated.

        private fun JSONObject.longOrNull(key: String): Long? = (opt(key) as? Number)?.toLong()

        private fun JSONObject.stringOrNull(key: String): String? = opt(key) as? String

        private fun JSONObject.booleanOrNull(key: String): Boolean? = opt(key) as? Boolean
    }
}

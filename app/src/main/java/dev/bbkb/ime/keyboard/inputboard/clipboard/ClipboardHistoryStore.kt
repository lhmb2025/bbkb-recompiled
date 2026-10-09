package dev.bbkb.ime.keyboard.inputboard.clipboard

import android.content.Context
import android.util.AtomicFile
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import dev.bbkb.ime.core.shared.Logger
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
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
 * [load] is synchronous: it runs once per process, on the main thread, the first time the history
 * is needed after unlock, and the file is a few kilobytes. [save] and [delete] are queued on one
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
            val array = JsonArray()
            for (entry in entries) {
                array.add(JsonObject().apply {
                    addProperty("id", entry.id)
                    addProperty("text", entry.text)
                    if (entry.label != null) addProperty("label", entry.label)
                    addProperty("mime", entry.mime)
                    addProperty("createdAt", entry.createdAtMs)
                    addProperty("pinned", entry.isPinned)
                    addProperty("pinnedAt", entry.pinnedAtMs)
                })
            }
            return JsonObject().apply {
                addProperty("version", VERSION)
                add("entries", array)
            }.toString()
        }

        /**
         * Written against Gson's tree API, not its reflective binder, for the reason
         * `DistributionManifestParser` gives: reflective binding fills a missing field with null
         * behind Kotlin's back. Forgiving per entry — one malformed row is dropped, the rest load —
         * and strict about the envelope: not JSON, not an object, or another version yields
         * nothing.
         */
        @JvmStatic
        fun decode(json: String): List<ClipEntry> {
            val root = try {
                JsonParser.parseString(json)
            } catch (e: JsonParseException) {
                return emptyList()
            } catch (e: IllegalStateException) {
                return emptyList()
            }
            if (root == null || !root.isJsonObject) return emptyList()
            val obj = root.asJsonObject
            if (obj.longOrNull("version") != VERSION.toLong()) return emptyList()
            val array = obj["entries"]?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
            return array.mapNotNull { element -> element.takeIf { it.isJsonObject }?.asJsonObject?.toEntry() }
        }

        private fun JsonObject.toEntry(): ClipEntry? {
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

        private fun JsonObject.primitive(key: String): JsonElement? =
            get(key)?.takeIf { it.isJsonPrimitive }

        private fun JsonObject.longOrNull(key: String): Long? {
            val p = primitive(key)?.asJsonPrimitive ?: return null
            return if (p.isNumber) try { p.asLong } catch (e: NumberFormatException) { null } else null
        }

        private fun JsonObject.stringOrNull(key: String): String? {
            val p = primitive(key)?.asJsonPrimitive ?: return null
            return if (p.isString) p.asString else null
        }

        private fun JsonObject.booleanOrNull(key: String): Boolean? {
            val p = primitive(key)?.asJsonPrimitive ?: return null
            return if (p.isBoolean) p.asBoolean else null
        }
    }
}

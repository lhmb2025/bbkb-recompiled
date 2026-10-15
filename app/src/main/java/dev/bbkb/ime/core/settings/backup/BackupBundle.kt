package dev.bbkb.ime.core.settings.backup

import dev.bbkb.ime.core.layout.InvalidLetterLayoutException
import dev.bbkb.ime.core.layout.LetterLayout
import dev.bbkb.ime.core.layout.LetterLayoutXml
import dev.bbkb.ime.personaldictionary.macro.CustomMacro
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * One backup file: a zip holding up to three kinds of data, each in the document format it
 * already had — so the parts stay readable on their own, and the file is the one thing the user
 * saves, restores, and the phone's own backup carries.
 *
 * ```
 * bbkb-backup-2026-10-09.zip
 * ├── manifest.json          format, version, app, created, source phone, every part + checksum
 * ├── settings.json          Settings  — the preferences (SettingsBackup), minus the layout keys
 * ├── macros.json            Settings  — custom macros (MacrosBundle)
 * ├── device-configs/<name>.xml   Settings  — custom device profiles, as imported
 * ├── layouts.json           Layouts   — symbol pages, palette, slideboard, phrases, currency
 * │                                      (LayoutsBundle)
 * ├── letter_layouts/<id>.xml     Layouts   — the user's letter layouts (LetterLayoutXml)
 * └── words.json             Words     — personal dictionary, shortcuts, learned words (WordsBundle)
 * ```
 *
 * The three user-facing parts ([Part]) are what the Back up and Restore checkboxes select. They
 * do not overlap: the layout preferences are kept out of `settings.json` ([LayoutsBundle.PREF_KEYS])
 * so that restoring or resetting Settings leaves the layouts alone and vice versa.
 *
 * ## Reading
 *
 * [read] takes either a bundle or one of the single documents the app used to write
 * (`bbkb-settings`, `bbkb-layouts`, `bbkb-words`), or one `letter-layout` XML document, so a
 * backup saved by an earlier build still restores and a layout someone shared imports the same
 * way. Everything is validated before anything is applied: the manifest must list every entry and
 * each entry's size and SHA-256 must match it, entry names must be the known ones (no paths, so a
 * crafted zip cannot write outside its parts), and every part must parse. A file that fails any
 * of this changes nothing.
 *
 * Version 2 added the `letter_layouts/` entries; a version 1 bundle has none and reads as before.
 */
object BackupBundle {

    const val FORMAT: String = "bbkb-backup"
    const val VERSION: Int = 2
    const val MIME_TYPE: String = "application/zip"

    const val MANIFEST: String = "manifest.json"
    const val SETTINGS: String = "settings.json"
    const val MACROS: String = "macros.json"
    const val LAYOUTS: String = "layouts.json"
    const val WORDS: String = "words.json"
    const val DEVICE_CONFIG_DIR: String = "device-configs/"
    const val LETTER_LAYOUT_DIR: String = "letter_layouts/"

    const val MAX_SETTINGS_BYTES: Int = 4 * 1024 * 1024
    const val MAX_DEVICE_CONFIG_BYTES: Int = 512 * 1024
    const val MAX_DEVICE_CONFIGS: Int = 32
    const val MAX_LETTER_LAYOUTS: Int = 64
    /** Largest file [read] accepts, zip or single document. */
    const val MAX_TOTAL_BYTES: Long = 24L * 1024 * 1024

    private val DEVICE_CONFIG_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}\\.xml")

    /** A layout's id (`LetterLayout.isValidId`) and `.xml`: the file name the registry gives it. */
    private val LETTER_LAYOUT_NAME = Regex("[a-z0-9][a-z0-9._-]{0,63}\\.xml")

    /** The three things a backup can hold, as the user sees them. */
    enum class Part { SETTINGS, LAYOUTS, WORDS }

    /** Which parts to write, restore or reset. */
    data class Selection(
        val settings: Boolean = true,
        val layouts: Boolean = true,
        val words: Boolean = true,
    ) {
        val isEmpty: Boolean get() = !settings && !layouts && !words

        fun has(part: Part): Boolean = when (part) {
            Part.SETTINGS -> settings
            Part.LAYOUTS -> layouts
            Part.WORDS -> words
        }

        fun with(part: Part, on: Boolean): Selection = when (part) {
            Part.SETTINGS -> copy(settings = on)
            Part.LAYOUTS -> copy(layouts = on)
            Part.WORDS -> copy(words = on)
        }

        /** This selection narrowed to the parts [bundle] carries. */
        fun limitedTo(bundle: Bundle): Selection = Selection(
            settings = settings && bundle.hasSettings,
            layouts = layouts && bundle.hasLayouts,
            words = words && bundle.hasWords,
        )

        companion object {
            val ALL = Selection()
            val NONE = Selection(settings = false, layouts = false, words = false)
        }
    }

    /** One custom device profile, as the file the user imported. */
    class DeviceConfigFile(val name: String, val xml: ByteArray)

    /** One of the user's letter layouts, as the document `<id>.xml` the registry keeps it in. */
    class LetterLayoutFile(val name: String, val xml: ByteArray)

    /** The phone a bundle was written on, for the restore summary. */
    class DeviceInfo(val manufacturer: String?, val model: String?)

    /**
     * What goes into a bundle, already serialised: the caller gathers each document through the
     * part's own object, so this writer needs nothing from Android.
     */
    class Contents(
        val settingsJson: String? = null,
        val macrosJson: String? = null,
        val deviceConfigs: List<DeviceConfigFile> = emptyList(),
        val layoutsJson: String? = null,
        val wordsJson: String? = null,
        val letterLayouts: List<LetterLayoutFile> = emptyList(),
    )

    /** The header of a bundle or single document: where it came from. */
    class Origin(
        val app: String?,
        val versionCode: Int,
        val created: String?,
        val device: DeviceInfo?,
        /** False for one of the single-document formats an earlier build wrote. */
        val isBundle: Boolean,
    )

    /** A validated backup, every part parsed and ready to apply. */
    class Bundle(
        val origin: Origin,
        val settings: SettingsBackup.Backup? = null,
        val macros: List<CustomMacro>? = null,
        val deviceConfigs: List<DeviceConfigFile> = emptyList(),
        val layouts: LayoutsBundle.Layouts? = null,
        val words: WordsBundle.Words? = null,
        /** The user's letter layouts, each parsed; part of [Part.LAYOUTS]. */
        val letterLayouts: List<LetterLayout> = emptyList(),
    ) {
        val hasSettings: Boolean get() = settings != null || macros != null || deviceConfigs.isNotEmpty()
        val hasLayouts: Boolean get() = layouts != null || letterLayouts.isNotEmpty()
        val hasWords: Boolean get() = words != null

        fun parts(): Set<Part> = buildSet {
            if (hasSettings) add(Part.SETTINGS)
            if (hasLayouts) add(Part.LAYOUTS)
            if (hasWords) add(Part.WORDS)
        }
    }

    /**
     * The file is not a BBKB backup, or is one this build cannot read; [message] says why. The
     * caller keeps "could not read the file" for the `IOException` the storage layer may throw
     * before the bytes get here.
     */
    class NotABackupException(message: String) : Exception(message)

    // ── writing ──────────────────────────────────────────────────────────────

    /** `bbkb-backup-2026-10-09.zip` — what the save dialog opens pre-filled. */
    fun defaultFileName(nowMillis: Long = System.currentTimeMillis()): String =
        "bbkb-backup-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(nowMillis)) + ".zip"

    /**
     * Writes [contents] to [out] as a bundle. Closes [out]. Parts that are null are simply not
     * written; the manifest lists exactly the parts present.
     */
    @Throws(IOException::class)
    fun write(
        out: OutputStream,
        contents: Contents,
        appVersion: String,
        versionCode: Int,
        device: DeviceInfo?,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        val entries = LinkedHashMap<String, ByteArray>()
        contents.settingsJson?.let { entries[SETTINGS] = it.toByteArray(Charsets.UTF_8) }
        contents.macrosJson?.let { entries[MACROS] = it.toByteArray(Charsets.UTF_8) }
        contents.deviceConfigs.forEach { config ->
            require(DEVICE_CONFIG_NAME.matches(config.name)) { "device config name ${config.name}" }
            entries[DEVICE_CONFIG_DIR + config.name] = config.xml
        }
        contents.layoutsJson?.let { entries[LAYOUTS] = it.toByteArray(Charsets.UTF_8) }
        contents.letterLayouts.forEach { layout ->
            require(LETTER_LAYOUT_NAME.matches(layout.name)) { "letter layout name ${layout.name}" }
            entries[LETTER_LAYOUT_DIR + layout.name] = layout.xml
        }
        contents.wordsJson?.let { entries[WORDS] = it.toByteArray(Charsets.UTF_8) }

        val manifest = JSONObject().apply {
            put("format", FORMAT)
            put("version", VERSION)
            put("app", appVersion)
            put("versionCode", versionCode)
            put("created", timestampFormat().format(Date(nowMillis)))
            device?.let {
                put("device", JSONObject().apply {
                    it.manufacturer?.let { m -> put("manufacturer", m) }
                    it.model?.let { m -> put("model", m) }
                })
            }
            put("parts", JSONArray().apply {
                entries.forEach { (path, bytes) ->
                    put(JSONObject().apply {
                        put("path", path)
                        put("bytes", bytes.size)
                        put("sha256", sha256(bytes))
                    })
                }
            })
        }.toString(2).toByteArray(Charsets.UTF_8)

        ZipOutputStream(out).use { zip ->
            putEntry(zip, MANIFEST, manifest, nowMillis)
            entries.forEach { (path, bytes) -> putEntry(zip, path, bytes, nowMillis) }
        }
    }

    private fun putEntry(zip: ZipOutputStream, name: String, bytes: ByteArray, timeMillis: Long) {
        val entry = ZipEntry(name)
        entry.time = timeMillis
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    // ── reading ──────────────────────────────────────────────────────────────

    /**
     * Reads a bundle, or one of the single documents an earlier build wrote, from [input].
     * Failures that mean "not a backup" are [NotABackupException]s; an `IOException` from the
     * stream itself propagates.
     */
    @Throws(IOException::class)
    fun read(input: InputStream): Result<Bundle> = try {
        Result.success(readOrThrow(input))
    } catch (e: NotABackupException) {
        Result.failure(e)
    }

    private fun readOrThrow(input: InputStream): Bundle {
        val stream = PushbackInputStream(input, 2)
        val head = ByteArray(2)
        val n = stream.read(head)
        if (n > 0) stream.unread(head, 0, n)
        return if (n == 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) {
            readZip(stream)
        } else {
            readSingleDocument(stream)
        }
    }

    private fun readZip(input: InputStream): Bundle {
        val entries = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (entry.isDirectory) fail("unexpected folder \"$name\"")
                if (!isKnownEntry(name)) fail("unexpected entry \"$name\"")
                if (name in entries) fail("entry \"$name\" appears twice")
                val cap = capFor(name)
                val bytes = readCapped(zip, cap) ?: fail("\"$name\" is larger than ${cap / 1024} KB")
                total += bytes.size
                if (total > MAX_TOTAL_BYTES) fail("the file is larger than ${MAX_TOTAL_BYTES / (1024 * 1024)} MB")
                entries[name] = bytes
                zip.closeEntry()
            }
        }
        val manifestBytes = entries.remove(MANIFEST) ?: fail("no $MANIFEST: not a BBKB backup")
        val manifest = try {
            JSONObject(String(manifestBytes, Charsets.UTF_8))
        } catch (e: JSONException) {
            fail("$MANIFEST is not JSON: ${e.message}")
        }
        val format = manifest.optString("format")
        if (format != FORMAT) fail("format is \"$format\", expected \"$FORMAT\"")
        val version = manifest.opt("version") as? Int ?: 0
        if (version < 1 || version > VERSION) fail("version ${manifest.opt("version")}, this build reads up to $VERSION")

        // Every entry must be in the manifest with the right size and hash, and every manifest
        // part must be present: a zip edited by hand, truncated or rebuilt by another tool is
        // refused as a whole rather than half-applied.
        val listed = manifest.opt("parts") as? JSONArray ?: fail("$MANIFEST lists no parts")
        val listedPaths = HashSet<String>()
        for (i in 0 until listed.length()) {
            val part = listed.opt(i) as? JSONObject ?: fail("$MANIFEST parts[$i] must be an object")
            val path = part.opt("path") as? String ?: fail("$MANIFEST parts[$i] has no path")
            val bytes = entries[path] ?: fail("$MANIFEST lists \"$path\" but the file does not hold it")
            if (!listedPaths.add(path)) fail("$MANIFEST lists \"$path\" twice")
            val size = (part.opt("bytes") as? Number)?.toInt() ?: -1
            if (size != bytes.size) fail("\"$path\" is ${bytes.size} bytes, the manifest says $size")
            val digest = part.opt("sha256") as? String ?: fail("$MANIFEST parts[$i] has no checksum")
            if (!digest.equals(sha256(bytes), ignoreCase = true)) fail("\"$path\" does not match its checksum")
        }
        entries.keys.firstOrNull { it !in listedPaths }?.let { fail("\"$it\" is not listed in $MANIFEST") }

        val configNames = entries.keys.filter { it.startsWith(DEVICE_CONFIG_DIR) }
        if (configNames.size > MAX_DEVICE_CONFIGS) fail("more than $MAX_DEVICE_CONFIGS device profiles")
        val layoutNames = entries.keys.filter { it.startsWith(LETTER_LAYOUT_DIR) }
        if (layoutNames.size > MAX_LETTER_LAYOUTS) fail("more than $MAX_LETTER_LAYOUTS letter layouts")

        val bundle = Bundle(
            origin = Origin(
                app = manifest.optString("app").takeIf { it.isNotEmpty() },
                versionCode = manifest.optInt("versionCode", 0),
                created = manifest.optString("created").takeIf { it.isNotEmpty() },
                device = manifest.optJSONObject("device")?.let {
                    DeviceInfo(
                        manufacturer = it.optString("manufacturer").takeIf { s -> s.isNotEmpty() },
                        model = it.optString("model").takeIf { s -> s.isNotEmpty() },
                    )
                },
                isBundle = true,
            ),
            settings = entries[SETTINGS]?.let { parseSettings(String(it, Charsets.UTF_8)) },
            macros = entries[MACROS]?.let { bytes ->
                MacrosBundle.parse(String(bytes, Charsets.UTF_8)).getOrElse { fail("$MACROS: ${it.message}") }
            },
            deviceConfigs = configNames.map { DeviceConfigFile(it.removePrefix(DEVICE_CONFIG_DIR), entries.getValue(it)) },
            layouts = entries[LAYOUTS]?.let { parseLayouts(String(it, Charsets.UTF_8), LAYOUTS) },
            words = entries[WORDS]?.let { bytes ->
                WordsBundle.parse(String(bytes, Charsets.UTF_8)).getOrElse { fail("$WORDS: ${it.message}") }
            },
            letterLayouts = layoutNames.map { parseLetterLayout(it, entries.getValue(it)) },
        )
        if (bundle.parts().isEmpty()) fail("the backup is empty")
        return bundle
    }

    /**
     * One `letter_layouts/<id>.xml` entry. The entry name is the layout's id, as the file name is
     * in the registry, so a layout restores under the name the backup listed it by.
     */
    private fun parseLetterLayout(path: String, bytes: ByteArray): LetterLayout {
        val id = path.removePrefix(LETTER_LAYOUT_DIR).removeSuffix(LetterLayoutXml.FILE_SUFFIX)
        val layout = try {
            LetterLayoutXml.parse(bytes.inputStream()) { id }
        } catch (e: InvalidLetterLayoutException) {
            fail("$path: ${e.message}")
        }
        return if (layout.id == id) layout else layout.withId(id)
    }

    /**
     * A single document: the settings backup or layouts file an earlier build wrote, or a words
     * document, dispatched on its `format` header; or one letter layout, which is XML.
     */
    private fun readSingleDocument(input: InputStream): Bundle {
        val bytes = readCapped(input, MAX_SETTINGS_BYTES.coerceAtLeast(WordsBundle.MAX_BYTES))
            ?: fail("the file is larger than ${WordsBundle.MAX_BYTES / (1024 * 1024)} MB")
        val json = String(bytes, Charsets.UTF_8)
        if (LetterLayoutXml.looksLikeXml(json)) {
            val layout = try {
                LetterLayoutXml.parse(bytes.inputStream())
            } catch (e: InvalidLetterLayoutException) {
                fail(e.message ?: "not a letter layout")
            }
            return Bundle(Origin(app = null, versionCode = 0, created = null, device = null, isBundle = false),
                letterLayouts = listOf(layout))
        }
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            fail("not a BBKB backup")
        }
        val origin = Origin(
            app = root.optString("app").takeIf { it.isNotEmpty() },
            versionCode = root.optInt("versionCode", 0),
            created = root.optString("created").takeIf { it.isNotEmpty() },
            device = null,
            isBundle = false,
        )
        return when (val format = root.optString("format")) {
            SettingsBackup.FORMAT -> Bundle(origin, settings = parseSettings(json))
            LayoutsBundle.FORMAT -> Bundle(origin, layouts = parseLayouts(json, "the file"))
            WordsBundle.FORMAT -> Bundle(origin, words = WordsBundle.parse(json).getOrElse { fail(it.message ?: "not a words file") })
            else -> fail("format is \"$format\": not a BBKB backup")
        }
    }

    private fun parseSettings(json: String): SettingsBackup.Backup =
        SettingsBackup.parse(json).getOrElse { fail("$SETTINGS: ${it.message}") }

    /** `layouts.json`, which is always the JSON bundle: a letter layout travels as its own entry. */
    private fun parseLayouts(json: String, where: String): LayoutsBundle.Layouts =
        when (val parsed = LayoutsBundle.parse(json).getOrElse { fail("$where: ${it.message}") }) {
            is LayoutsBundle.Import.Bundle -> parsed.layouts
            is LayoutsBundle.Import.LetterMap -> fail("$where: a letter layout, where the layouts file belongs")
        }

    private fun isKnownEntry(name: String): Boolean = when {
        name == MANIFEST || name == SETTINGS || name == MACROS || name == LAYOUTS || name == WORDS -> true
        name.startsWith(DEVICE_CONFIG_DIR) -> DEVICE_CONFIG_NAME.matches(name.removePrefix(DEVICE_CONFIG_DIR))
        name.startsWith(LETTER_LAYOUT_DIR) -> LETTER_LAYOUT_NAME.matches(name.removePrefix(LETTER_LAYOUT_DIR))
        else -> false
    }

    private fun capFor(name: String): Int = when {
        name == MANIFEST -> 64 * 1024
        name == SETTINGS -> MAX_SETTINGS_BYTES
        name == MACROS -> MacrosBundle.MAX_BYTES
        name == LAYOUTS -> LayoutsBundle.MAX_BYTES
        name == WORDS -> WordsBundle.MAX_BYTES
        name.startsWith(LETTER_LAYOUT_DIR) -> LetterLayoutXml.MAX_BYTES
        else -> MAX_DEVICE_CONFIG_BYTES
    }

    /** At most [cap] bytes of [input], or null if the stream holds more than that. */
    private fun readCapped(input: InputStream, cap: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer, 0, minOf(buffer.size, cap + 1 - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > cap) return null
        }
        return out.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun timestampFormat() =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    private fun fail(message: String): Nothing = throw NotABackupException(message)
}

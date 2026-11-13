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
 * One backup file: a zip whose parts keep their own document formats.
 *
 * ```
 * bbkb-backup-<date>.zip
 * ├── manifest.json               format, version, app, created, source phone, parts + SHA-256
 * ├── settings.json               Settings: preferences (SettingsBackup) minus the layout keys
 * ├── macros.json                 Settings: custom macros (MacrosBundle)
 * ├── device-configs/<name>.xml   Settings: imported device profiles
 * ├── layouts.json                Layouts: editor sections (LayoutsBundle)
 * ├── letter_layouts/<id>.xml     Layouts: letter layouts (LetterLayoutXml), added in version 2
 * └── words.json                  Words: dictionary, shortcuts, learned words (WordsBundle)
 * ```
 *
 * The [Part]s do not overlap, so each restores or resets alone. [read] also accepts a single
 * `bbkb-settings`/`bbkb-layouts`/`bbkb-words` document or one `letter-layout` XML. Everything is
 * validated before anything is applied: every entry listed with matching size and SHA-256, only
 * known entry names (no paths), every part parses.
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

    /** A valid `LetterLayout` id plus `.xml`, as the registry names its files. */
    private val LETTER_LAYOUT_NAME = Regex("[a-z0-9][a-z0-9._-]{0,63}\\.xml")

    enum class Part { SETTINGS, LAYOUTS, WORDS }

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

    class DeviceConfigFile(val name: String, val xml: ByteArray)

    class LetterLayoutFile(val name: String, val xml: ByteArray)

    class DeviceInfo(val manufacturer: String?, val model: String?)

    /** Already-serialized parts, so this writer needs nothing from Android. */
    class Contents(
        val settingsJson: String? = null,
        val macrosJson: String? = null,
        val deviceConfigs: List<DeviceConfigFile> = emptyList(),
        val layoutsJson: String? = null,
        val wordsJson: String? = null,
        val letterLayouts: List<LetterLayoutFile> = emptyList(),
    )

    class Origin(
        val app: String?,
        val versionCode: Int,
        val created: String?,
        val device: DeviceInfo?,
        /** False for a single-document file. */
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
        /** Part of [Part.LAYOUTS]. */
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

    /** Not a BBKB backup, or one this build cannot read; distinct from a storage `IOException`. */
    class NotABackupException(message: String) : Exception(message)

    /** `bbkb-backup-<yyyy-MM-dd>.zip`, the save dialog's suggested name. */
    fun defaultFileName(nowMillis: Long = System.currentTimeMillis()): String =
        "bbkb-backup-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(nowMillis)) + ".zip"

    /** Writes [contents] to [out] as a bundle and closes [out]; null parts are left out. */
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

    /** Reads a bundle or single document; "not a backup" is a failure, stream `IOException`s propagate. */
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

        // Entries and manifest must match exactly, so a hand-edited or truncated zip is refused
        // whole rather than half-applied.
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

    /** The entry name is the layout id, so it restores under the name the backup listed. */
    private fun parseLetterLayout(path: String, bytes: ByteArray): LetterLayout {
        val id = path.removePrefix(LETTER_LAYOUT_DIR).removeSuffix(LetterLayoutXml.FILE_SUFFIX)
        val layout = try {
            LetterLayoutXml.parse(bytes.inputStream()) { id }
        } catch (e: InvalidLetterLayoutException) {
            fail("$path: ${e.message}")
        }
        return if (layout.id == id) layout else layout.withId(id)
    }

    /** A single JSON document dispatched on its `format`, or one letter layout XML. */
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

    /** `layouts.json` is always the JSON form; letter layouts are their own entries. */
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

    /** At most [cap] bytes of [input], or null if it holds more. */
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

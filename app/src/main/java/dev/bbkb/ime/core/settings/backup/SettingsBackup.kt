package dev.bbkb.ime.core.settings.backup

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The preferences backup document format, free of UI so JVM tests can drive it. Shape:
 * `{"format":"bbkb-settings","version":1,"app":…,"versionCode":…,"created":…,"settings":{key:
 * {"type":"boolean|int|long|float|string|stringSet","value":…}}}`. Each value carries its type
 * because reading a pref with the wrong getter throws later. [apply] merges by default; with
 * `replace` it also removes every setting the backup does not name (except [isDenied] and
 * `keepKeys`), so the phone ends with exactly the backup's settings.
 */
object SettingsBackup {
    const val FORMAT: String = "bbkb-settings"

    /** Highest `version` this build reads, and the one [serialize] writes. */
    const val VERSION: Int = 1

    const val MIME_TYPE: String = "application/json"

    /**
     * Key prefixes that never travel between devices:
     *  - `pref_update_`: OTA check state (restored, it would offer an unverified download)
     *  - `pref_distribution_`: manifest URL/channel overrides (would redirect update checks)
     *  - `voice_input_language_cache`: per-recognizer language lists reported by this device
     */
    val DENIED_PREFIXES: List<String> = listOf("pref_update_", "pref_distribution_", "voice_input_language_cache")

    /** `pref_update_` keys that are user settings (the daily update check switch), so they travel. */
    val ALLOWED_DESPITE_PREFIX: Set<String> = setOf("pref_update_background_check")

    /**
     * Per-device keys. The device profile keys can name a file in this device's storage, and on
     * other hardware leave the keyboard unable to type; the emoji tab is session scratch state.
     */
    val DENIED_KEYS: Set<String> = setOf(
        "active_device_config_id",
        "active_device_config_chosen",
        "last_shown_emoji_category_id",
        // Cached key rectangles for this device's keypad geometry (CkbKeyGridCapture).
        "ckb_key_grid_cache_v2",
        // Names a letter-layout file in this device's storage; the layouts travel as files in
        // BackupBundle. The second key is the legacy one, kept until every phone has migrated.
        "pref_pkb_active_layout",
        "pref_pkb_active_letter_map",
    )

    /** Whether [key] is excluded from export and import. */
    fun isDenied(key: String): Boolean {
        if (key in ALLOWED_DESPITE_PREFIX) return false
        if (key in DENIED_KEYS) return true
        return DENIED_PREFIXES.any { key.startsWith(it) }
    }

    /** `bbkb-settings-<yyyy-MM-dd>.json`, the save dialog's suggested name. */
    fun defaultFileName(nowMillis: Long = System.currentTimeMillis()): String =
        "bbkb-settings-" + dateFormat().format(Date(nowMillis)) + ".json"

    /**
     * The whole preference file as a backup document, minus [isDenied] keys.
     *
     * @param appVersion recorded for the reader, never checked on import
     * @param excludeKeys further keys to leave out (a bundle passes [LayoutsBundle.PREF_KEYS])
     */
    fun serialize(
        prefs: SharedPreferences,
        appVersion: String,
        versionCode: Int,
        nowMillis: Long = System.currentTimeMillis(),
        excludeKeys: Set<String> = emptySet(),
    ): String {
        val settings = JSONObject()
        // Sorted so equal preferences give identical bytes.
        prefs.all.entries.sortedBy { it.key }.forEach { (key, value) ->
            if (isDenied(key) || key in excludeKeys) return@forEach
            typedValue(value)?.let { settings.put(key, it) }
        }

        return JSONObject().apply {
            put("format", FORMAT)
            put("version", VERSION)
            put("app", appVersion)
            put("versionCode", versionCode)
            put("created", timestampFormat().format(Date(nowMillis)))
            put("settings", settings)
        }.toString(2)
    }

    /** `{"type": …, "value": …}`, or null for a value `SharedPreferences` cannot hold. */
    private fun typedValue(value: Any?): JSONObject? {
        val type: String
        val encoded: Any
        when (value) {
            is Boolean -> { type = "boolean"; encoded = value }
            is Int -> { type = "int"; encoded = value }
            is Long -> { type = "long"; encoded = value }
            is Float -> { type = "float"; encoded = value.toDouble() }
            is String -> { type = "string"; encoded = value }
            // Sorted for deterministic output, as with the keys.
            is Set<*> -> { type = "stringSet"; encoded = JSONArray(value.map { it.toString() }.sorted()) }
            else -> return null
        }
        return JSONObject().put("type", type).put("value", encoded)
    }

    /** A parsed, header-validated backup; [values] are denylisted and typed, in document order. */
    class Backup internal constructor(
        val version: Int,
        val app: String?,
        val versionCode: Int,
        val created: String?,
        val values: Map<String, Any>,
    ) {
        val size: Int get() = values.size
    }

    /** The user picked the wrong file (not JSON, or not ours), as opposed to an I/O failure. */
    class NotABackupException(message: String) : Exception(message)

    /** Reads [json] and validates its header; failures carry [NotABackupException]. */
    fun parse(json: String): Result<Backup> {
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            return Result.failure(NotABackupException("not JSON: ${e.message}"))
        }

        val format = root.optString("format")
        if (format != FORMAT) {
            return Result.failure(NotABackupException("format is \"$format\", expected \"$FORMAT\""))
        }
        // optInt's 0 fallback is out of range, so a missing version fails the same check.
        val version = root.optInt("version", 0)
        if (version < 1 || version > VERSION) {
            return Result.failure(NotABackupException("version $version, this build reads up to $VERSION"))
        }

        val settings = root.optJSONObject("settings")
            ?: return Result.failure(NotABackupException("no \"settings\" object"))

        val values = LinkedHashMap<String, Any>()
        settings.keys().forEach { key ->
            if (isDenied(key)) return@forEach
            val entry = settings.optJSONObject(key) ?: return@forEach
            decode(entry)?.let { values[key] = it }
        }

        return Result.success(
            Backup(
                version = version,
                app = root.optString("app").takeIf { it.isNotEmpty() },
                versionCode = root.optInt("versionCode", 0),
                created = root.optString("created").takeIf { it.isNotEmpty() },
                values = values,
            )
        )
    }

    /** One typed entry, or null when malformed (skipped, so one bad key cannot sink a restore). */
    private fun decode(entry: JSONObject): Any? {
        if (entry.isNull("value")) return null
        return when (entry.optString("type")) {
            "boolean" -> entry.opt("value") as? Boolean
            "int" -> (entry.opt("value") as? Number)?.toInt()
            "long" -> (entry.opt("value") as? Number)?.toLong()
            "float" -> (entry.opt("value") as? Number)?.toFloat()
            "string" -> entry.opt("value") as? String
            "stringSet" -> (entry.opt("value") as? JSONArray)?.let { array ->
                (0 until array.length()).mapNotNull { array.opt(it)?.toString() }.toSet()
            }
            else -> null
        }
    }

    /**
     * Writes [backup] into [prefs] and returns how many values were written. One editor, so the
     * IME's preference listeners rebuild once per concern, not once per key.
     */
    fun apply(
        prefs: SharedPreferences,
        backup: Backup,
        replace: Boolean = false,
        keepKeys: Set<String> = emptySet(),
    ): Int {
        val editor = prefs.edit()
        if (replace) {
            prefs.all.keys.forEach { key ->
                if (key in backup.values || isDenied(key) || key in keepKeys) return@forEach
                editor.remove(key)
            }
        }
        var written = 0
        backup.values.forEach { (key, value) ->
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value.map { it.toString() }.toSet())
                else -> return@forEach
            }
            written++
        }
        editor.apply()
        return written
    }

    /** Not a field: `SimpleDateFormat` is not thread-safe. */
    private fun dateFormat() = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private fun timestampFormat() =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
}

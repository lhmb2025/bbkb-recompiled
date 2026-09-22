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
 * Serializing the app's preferences to a JSON document and reading one back.
 *
 * Deliberately free of Android UI: [AdvancedSettingsScreen][dev.bbkb.ime.core.settings.screens]
 * owns the file pickers, the confirmation dialog and the toasts, and this object owns the
 * document format. That split is what makes the format testable — the JVM tests drive
 * [serialize] / [parse] / [apply] against a Robolectric `SharedPreferences` with no Activity
 * anywhere.
 *
 * ## Document shape
 *
 * ```json
 * {
 *   "format": "bbkb-settings",
 *   "version": 1,
 *   "app": "5.0.0-beta.20",
 *   "versionCode": 20,
 *   "created": "2026-09-22T09:14:07Z",
 *   "settings": {
 *     "auto_cap": { "type": "boolean", "value": true },
 *     "control_mode": { "type": "string", "value": "0" },
 *     "enabled_subtypes": { "type": "stringSet", "value": ["en_US", "fr_FR"] }
 *   }
 * }
 * ```
 *
 * Every value carries its own `type` because `SharedPreferences` is typed and reading a key with
 * the wrong getter throws `ClassCastException` at some unrelated point later. A restore that
 * wrote every value back as a string would not fail here — it would fail the next time the
 * keyboard read `auto_cap`.
 *
 * ## Restore semantics: merge, not replace
 *
 * [apply] writes the keys the backup contains and **leaves every other key alone**. It does not
 * clear keys that are absent from the backup. Two reasons:
 *
 *  1. The preference file is not only settings. Other subsystems keep bookkeeping in the same
 *     default `SharedPreferences` — migration flags (`PrefsManager.migrateThemePrefs`), the
 *     active device-config id, update-check state. Clearing them because an older backup did not
 *     mention them would re-run migrations and reset device state the user never asked to touch.
 *  2. A backup taken by an older build cannot mention keys that build did not have. Under
 *     replace semantics, restoring it would silently reset every setting added since.
 *
 * The practical difference is small: a key absent from a backup was at its default on the device
 * the backup came from, so merging usually lands on the same effective value anyway. Where it
 * differs, merging is the direction that cannot destroy anything.
 */
object SettingsBackup {

    /** `format` header value. Anything else is somebody else's JSON file. */
    const val FORMAT: String = "bbkb-settings"

    /** Highest `version` this build knows how to read, and the one [serialize] writes. */
    const val VERSION: Int = 1

    const val MIME_TYPE: String = "application/json"

    /**
     * Search-anchor ids for the two rows this feature adds to the Advanced screen. Declared here
     * because the rows hold no preference key of their own, so there is no other place that names
     * them; `SettingsSearchIndex` indexes them by these constants and the screen repeats the
     * literals inside its `settingsSearchAnchor(...)` calls, which is the form the index's sync
     * test scans for.
     */
    const val ANCHOR_BACK_UP: String = "settings_backup"
    const val ANCHOR_RESTORE: String = "settings_restore"

    // ── the denylist ─────────────────────────────────────────────────────────

    /**
     * Key prefixes that never travel between devices.
     *
     *  - `pref_update_` — the OTA check's own state: last-check timestamp, the version/URL/size/
     *    hash of whatever the last check found, and which version the user has already been shown.
     *    Restoring it on another device makes the app believe it already checked, and makes it
     *    offer a download it has not verified.
     *  - `pref_distribution_` — the manifest-URL and channel overrides. These point the updater at
     *    a specific server (often a laptop on the local network during development); carrying them
     *    to another device silently redirects its update checks.
     */
    val DENIED_PREFIXES: List<String> = listOf("pref_update_", "pref_distribution_")

    /**
     * The one `pref_update_` key that *is* a user setting rather than check state: the
     * "check for updates daily" switch on the Updates screen (`UpdateJobService.PREF_BACKGROUND_CHECK`,
     * and an entry in `SettingsSearchIndex`). It is a preference the user set on purpose, so it
     * belongs in a backup even though it shares the prefix with the state around it.
     */
    val ALLOWED_DESPITE_PREFIX: Set<String> = setOf("pref_update_background_check")

    /**
     * Individual per-device keys, for the same reason the prefixes are excluded.
     *
     *  - `active_device_config_id` — names the hardware profile this device resolved or imported
     *    (`CustomDeviceConfigManager.PREF_ACTIVE_CONFIG_ID`). For a `custom:` id it names a file in
     *    *this* device's storage. Restoring it onto different hardware points the key mapping at a
     *    profile for a phone the user is not holding, which is the one setting that can leave the
     *    keyboard unable to type.
     *  - `last_shown_emoji_category_id` — which emoji tab was open last. Session scratch state,
     *    not a preference; there is nothing to restore.
     */
    val DENIED_KEYS: Set<String> = setOf(
        "active_device_config_id",
        "last_shown_emoji_category_id",
        // CkbKeyGridCapture's cached key rectangles for THIS device's keypad geometry (a cost
        // cache, rebuilt on the next layout sync). Carrying it to another device would hand the
        // gesture arbiter the wrong grid until it refreshed.
        "ckb_key_grid_cache_v2",
    )

    /** Whether [key] is excluded from both export and import. */
    fun isDenied(key: String): Boolean {
        if (key in ALLOWED_DESPITE_PREFIX) return false
        if (key in DENIED_KEYS) return true
        return DENIED_PREFIXES.any { key.startsWith(it) }
    }

    // ── export ───────────────────────────────────────────────────────────────

    /** `bbkb-settings-2026-09-22.json` — what the save dialog opens pre-filled. */
    fun defaultFileName(nowMillis: Long = System.currentTimeMillis()): String =
        "bbkb-settings-" + dateFormat().format(Date(nowMillis)) + ".json"

    /**
     * The whole preference file as a backup document, minus [isDenied] keys.
     *
     * @param appVersion  `BuildConfig.VERSION_NAME` of the writing build — recorded for the reader,
     *                    never checked on import. A backup is not tied to a version.
     */
    fun serialize(
        prefs: SharedPreferences,
        appVersion: String,
        versionCode: Int,
        nowMillis: Long = System.currentTimeMillis(),
    ): String {
        val settings = JSONObject()
        // Sorted so two backups of the same preferences are the same bytes, which makes them
        // diffable and makes the round-trip test's failures readable.
        prefs.all.entries.sortedBy { it.key }.forEach { (key, value) ->
            if (isDenied(key)) return@forEach
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
            // Sorted for the same reason the keys are: a `Set` has no order of its own, so two
            // exports of one unchanged preference file would otherwise differ.
            is Set<*> -> { type = "stringSet"; encoded = JSONArray(value.map { it.toString() }.sorted()) }
            else -> return null
        }
        return JSONObject().put("type", type).put("value", encoded)
    }

    // ── import ───────────────────────────────────────────────────────────────

    /**
     * A parsed, header-validated backup. [values] holds the already-denylisted-and-typed entries
     * in the order the document listed them; each value is one of `Boolean`, `Int`, `Long`,
     * `Float`, `String`, `Set<String>` — exactly the six types `SharedPreferences` stores.
     */
    class Backup internal constructor(
        val version: Int,
        val app: String?,
        val versionCode: Int,
        val created: String?,
        val values: Map<String, Any>,
    ) {
        /** How many settings [apply] would write. */
        val size: Int get() = values.size
    }

    /**
     * Thrown when the document parses but is not one of ours, or does not parse at all — the two
     * cases that mean "the user picked the wrong file", as opposed to the storage layer failing to
     * hand us its bytes.
     */
    class NotABackupException(message: String) : Exception(message)

    /**
     * Reads [json] and validates its header. Failures carry [NotABackupException]; the caller
     * turns that into the "not a BBKB settings backup" toast and keeps "could not read the backup"
     * for the `IOException` it may have hit before getting here.
     */
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
        // optInt's 0 fallback is itself out of range, so a missing/garbage version is rejected by
        // the same check that rejects a document from a future build.
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

    /**
     * One `{"type": …, "value": …}` entry, or null if the type is unknown or the value does not
     * fit it. A single malformed entry is skipped rather than failing the whole restore: the
     * alternative is a backup the user cannot use at all because one key went bad.
     */
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
     * Writes [backup]'s values into [prefs] in one `apply()` and returns how many were written.
     *
     * One editor, so the running IME's `OnSharedPreferenceChangeListener`s (notably
     * `ThemePrefsListener`) see the whole restore as a single burst of per-key callbacks and
     * rebuild the keyboard once per concern rather than once per key. See the class KDoc for why
     * absent keys are left alone.
     */
    fun apply(prefs: SharedPreferences, backup: Backup): Int {
        val editor = prefs.edit()
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

    // ── formatting ───────────────────────────────────────────────────────────

    /** Not a field: `SimpleDateFormat` is not thread-safe and these are used off the main thread. */
    private fun dateFormat() = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private fun timestampFormat() =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
}

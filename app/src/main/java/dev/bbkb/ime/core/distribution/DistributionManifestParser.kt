package dev.bbkb.ime.core.distribution

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Turns the raw bytes of `dist/manifest.json` into a [DistributionManifest].
 *
 * The parser walks the `org.json` tree and checks every field by hand rather than binding the
 * JSON to the model reflectively. A reflective binder would silently accept a missing required
 * field as `null` (Gson, which this parser used before, constructs objects through `Unsafe`,
 * bypassing Kotlin's null checks), which is exactly the failure mode a manifest fetched over the
 * network must not have.
 *
 * ### What is strict and what is forgiving
 *
 * Forgiving, because the manifest is a file a future release tool writes and this app may be
 * months old by the time it reads one:
 *
 *  - **Unknown fields are ignored**, at every level. Adding a key to schema 1 is not a breaking
 *    change.
 *  - **A malformed entry is dropped, not fatal.** An `app` channel or a `packs` item that is not
 *    an object, or that is missing a required field, is skipped; the rest of the manifest still
 *    parses. A broken `release` entry must not take the `debug` channel down with it.
 *  - **Duplicate pack locales**: the first wins, later ones are dropped.
 *
 * Strict, because these are contract violations that would make everything downstream
 * meaningless:
 *
 *  - Not valid JSON, or not a JSON object at the top level → [ManifestFormatException].
 *  - `schema` missing or not an integer → [ManifestFormatException].
 *  - `schema` anything other than [DistributionManifest.SCHEMA] → [UnsupportedSchemaException].
 *    A *newer* schema is a real state the app must handle (the app is too old); a schema below
 *    1 never existed.
 *  - `app` or `packs` present but not a JSON object → [ManifestFormatException]. That is a
 *    structural error, not a single bad entry.
 *
 * The result always carries `fromCache = false, fetchedAt = 0L`; [ManifestSource] stamps real
 * provenance on it.
 *
 * Thread-safe and stateless: this is a pure function on a string.
 */
object DistributionManifestParser {

    /**
     * @throws ManifestFormatException on malformed JSON or a structurally invalid manifest.
     * @throws UnsupportedSchemaException when the manifest announces a schema this build cannot
     *   read.
     */
    @Throws(DistributionException::class)
    fun parse(json: String): DistributionManifest {
        val root = try {
            val tokener = JSONTokener(json)
            val value = tokener.nextValue()
            // nextValue stops after one value; anything but whitespace after it is not JSON.
            if (tokener.nextClean() != '\u0000') {
                throw ManifestFormatException("Manifest is not valid JSON")
            }
            value
        } catch (malformed: JSONException) {
            throw ManifestFormatException("Manifest is not valid JSON", malformed)
        }
        val obj = root as? JSONObject
            ?: throw ManifestFormatException("Manifest root is not a JSON object")

        val schema = obj.intOrNull("schema")
            ?: throw ManifestFormatException("Manifest has no integer \"schema\" field")
        if (schema != DistributionManifest.SCHEMA) {
            throw UnsupportedSchemaException(schema)
        }

        return DistributionManifest(
            schema = schema,
            generated = obj.stringOrNull("generated"),
            apps = parseApps(obj.valueOrNull("app")),
            packs = parsePacks(obj.valueOrNull("packs")),
        )
    }

    /** [parse], with every [DistributionException] turned into a `Result.failure`. */
    fun tryParse(json: String): Result<DistributionManifest> = try {
        Result.success(parse(json))
    } catch (failure: DistributionException) {
        Result.failure(failure)
    }

    private fun parseApps(element: Any?): Map<String, AppBuild> {
        if (element == null) return emptyMap()
        if (element !is JSONObject) throw ManifestFormatException("\"app\" is not a JSON object")
        val out = LinkedHashMap<String, AppBuild>()
        for (buildType in element.keys()) {
            parseAppBuild(element.valueOrNull(buildType))?.let { out[buildType] = it }
        }
        return out
    }

    /** `null` when the entry is unusable — the channel is then simply absent. */
    private fun parseAppBuild(element: Any?): AppBuild? {
        val obj = element as? JSONObject ?: return null
        val versionCode = obj.intOrNull("versionCode") ?: return null
        val versionName = obj.stringOrNull("versionName") ?: return null
        val url = obj.stringOrNull("url") ?: return null
        val sha256 = obj.stringOrNull("sha256") ?: return null
        val size = obj.longOrNull("size") ?: return null
        // minSdk is the one optional-with-a-default field: a manifest that omits it is read as
        // "installable anywhere this app runs", which is what the app's own minSdk already says.
        val minSdk = obj.intOrNull("minSdk") ?: 0
        return AppBuild(
            versionCode = versionCode,
            versionName = versionName,
            url = url,
            sha256 = sha256.lowercase(),
            size = size,
            minSdk = minSdk,
            notes = obj.stringOrNull("notes"),
        )
    }

    private fun parsePacks(element: Any?): Packs? {
        if (element == null) return null
        if (element !is JSONObject) throw ManifestFormatException("\"packs\" is not a JSON object")
        val version = element.stringOrNull("version") ?: return null
        val baseUrl = element.stringOrNull("baseUrl") ?: return null
        val itemsElement = element.valueOrNull("items")
        if (itemsElement != null && itemsElement !is JSONArray) {
            throw ManifestFormatException("\"packs.items\" is not a JSON array")
        }
        val seen = HashSet<String>()
        val items = ArrayList<PackEntry>()
        if (itemsElement is JSONArray) {
            for (i in 0 until itemsElement.length()) {
                val entry = parsePackEntry(itemsElement.opt(i)) ?: continue
                if (seen.add(entry.locale)) items.add(entry)
            }
        }
        return Packs(version = version, baseUrl = baseUrl, items = items)
    }

    /** `null` when the item is unusable — it is then simply not in the catalogue. */
    private fun parsePackEntry(element: Any?): PackEntry? {
        val obj = element as? JSONObject ?: return null
        val locale = obj.stringOrNull("locale") ?: return null
        val file = obj.stringOrNull("file") ?: return null
        val sha256 = obj.stringOrNull("sha256") ?: return null
        val size = obj.longOrNull("size") ?: return null
        return PackEntry(
            locale = locale,
            // A pack with no display name falls back to its own locale rather than dropping out
            // of the catalogue: the name is cosmetic, everything else is load-bearing.
            name = obj.stringOrNull("name") ?: locale,
            file = file,
            sha256 = sha256.lowercase(),
            size = size,
            group = obj.stringOrNull("group"),
        )
    }

    // ── Tree accessors ────────────────────────────────────────────────────────────────────────
    //
    // Each returns null for "absent, JSON null, not a primitive, or the wrong primitive type",
    // so a caller can spell a required field as `?: return null` and an optional one as `?:
    // default`. These deliberately do not use JSONObject's own optString/optInt, which coerce
    // (a number to a string, "5" or 5.9 to 5). org.json hands back an Integer or a Long for a
    // number written without a fraction or an exponent and a Double otherwise, so a float where
    // an int belongs (1430.0) is rejected rather than truncated.

    /** The value under [name], with JSON null read as absent. */
    private fun JSONObject.valueOrNull(name: String): Any? =
        opt(name)?.takeIf { it != JSONObject.NULL }

    private fun JSONObject.stringOrNull(name: String): String? =
        (valueOrNull(name) as? String)?.takeIf { it.isNotEmpty() }

    private fun JSONObject.intOrNull(name: String): Int? = valueOrNull(name) as? Int

    private fun JSONObject.longOrNull(name: String): Long? = when (val value = valueOrNull(name)) {
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }
}

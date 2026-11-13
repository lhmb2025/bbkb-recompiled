package dev.bbkb.ime.core.distribution

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Parses `dist/manifest.json` by hand so a missing required field can never slip through as `null`.
 * Forgiving: unknown fields ignored, a malformed `app` channel or pack item is dropped, duplicate
 * locales keep the first. Strict: invalid JSON, non-object root, missing/non-integer `schema`, or
 * `app`/`packs` not an object is [ManifestFormatException]; a schema other than
 * [DistributionManifest.SCHEMA] is [UnsupportedSchemaException] (the app is too old). Stateless.
 */
object DistributionManifestParser {
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

    private fun parseAppBuild(element: Any?): AppBuild? {
        val obj = element as? JSONObject ?: return null
        val versionCode = obj.intOrNull("versionCode") ?: return null
        val versionName = obj.stringOrNull("versionName") ?: return null
        val url = obj.stringOrNull("url") ?: return null
        val sha256 = obj.stringOrNull("sha256") ?: return null
        val size = obj.longOrNull("size") ?: return null
            // Optional: absent means installable anywhere this app runs.
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

    private fun parsePackEntry(element: Any?): PackEntry? {
        val obj = element as? JSONObject ?: return null
        val locale = obj.stringOrNull("locale") ?: return null
        val file = obj.stringOrNull("file") ?: return null
        val sha256 = obj.stringOrNull("sha256") ?: return null
        val size = obj.longOrNull("size") ?: return null
        return PackEntry(
            locale = locale,
            // The name is cosmetic; fall back to the locale rather than dropping the pack.
            name = obj.stringOrNull("name") ?: locale,
            file = file,
            sha256 = sha256.lowercase(),
            size = size,
            group = obj.stringOrNull("group"),
        )
    }

    // Accessors return null for absent, JSON null or the wrong type, unlike optString/optInt which
    // coerce. org.json yields Integer/Long only for whole numbers, so 1430.0 is rejected, not truncated.

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

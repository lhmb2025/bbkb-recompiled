package dev.bbkb.ime.core.distribution

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException

/**
 * Turns the raw bytes of `dist/manifest.json` into a [DistributionManifest].
 *
 * The parser is deliberately written against Gson's *tree* API rather than its reflective
 * binder. Reflective binding would silently accept a missing required field as `null` (Gson
 * constructs objects through `Unsafe`, bypassing Kotlin's null checks), which is exactly the
 * failure mode a manifest fetched over the network must not have.
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
            JsonParser.parseString(json)
        } catch (malformed: JsonSyntaxException) {
            throw ManifestFormatException("Manifest is not valid JSON", malformed)
        } catch (malformed: IllegalStateException) {
            // Gson raises this for some truncated inputs rather than JsonSyntaxException.
            throw ManifestFormatException("Manifest is not valid JSON", malformed)
        }
        if (root == null || !root.isJsonObject) {
            throw ManifestFormatException("Manifest root is not a JSON object")
        }
        val obj = root.asJsonObject

        val schema = obj.optInt("schema")
            ?: throw ManifestFormatException("Manifest has no integer \"schema\" field")
        if (schema != DistributionManifest.SCHEMA) {
            throw UnsupportedSchemaException(schema)
        }

        return DistributionManifest(
            schema = schema,
            generated = obj.optString("generated"),
            apps = parseApps(obj["app"]),
            packs = parsePacks(obj["packs"]),
        )
    }

    /** [parse], with every [DistributionException] turned into a `Result.failure`. */
    fun tryParse(json: String): Result<DistributionManifest> = try {
        Result.success(parse(json))
    } catch (failure: DistributionException) {
        Result.failure(failure)
    }

    private fun parseApps(element: JsonElement?): Map<String, AppBuild> {
        if (element == null || element.isJsonNull) return emptyMap()
        if (!element.isJsonObject) throw ManifestFormatException("\"app\" is not a JSON object")
        val out = LinkedHashMap<String, AppBuild>()
        for ((buildType, value) in element.asJsonObject.entrySet()) {
            parseAppBuild(value)?.let { out[buildType] = it }
        }
        return out
    }

    /** `null` when the entry is unusable — the channel is then simply absent. */
    private fun parseAppBuild(element: JsonElement?): AppBuild? {
        val obj = element?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val versionCode = obj.optInt("versionCode") ?: return null
        val versionName = obj.optString("versionName") ?: return null
        val url = obj.optString("url") ?: return null
        val sha256 = obj.optString("sha256") ?: return null
        val size = obj.optLong("size") ?: return null
        // minSdk is the one optional-with-a-default field: a manifest that omits it is read as
        // "installable anywhere this app runs", which is what the app's own minSdk already says.
        val minSdk = obj.optInt("minSdk") ?: 0
        return AppBuild(
            versionCode = versionCode,
            versionName = versionName,
            url = url,
            sha256 = sha256.lowercase(),
            size = size,
            minSdk = minSdk,
            notes = obj.optString("notes"),
        )
    }

    private fun parsePacks(element: JsonElement?): Packs? {
        if (element == null || element.isJsonNull) return null
        if (!element.isJsonObject) throw ManifestFormatException("\"packs\" is not a JSON object")
        val obj = element.asJsonObject
        val version = obj.optString("version") ?: return null
        val baseUrl = obj.optString("baseUrl") ?: return null
        val itemsElement = obj["items"]
        if (itemsElement != null && !itemsElement.isJsonNull && !itemsElement.isJsonArray) {
            throw ManifestFormatException("\"packs.items\" is not a JSON array")
        }
        val seen = HashSet<String>()
        val items = ArrayList<PackEntry>()
        itemsElement?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { item ->
            val entry = parsePackEntry(item) ?: return@forEach
            if (seen.add(entry.locale)) items.add(entry)
        }
        return Packs(version = version, baseUrl = baseUrl, items = items)
    }

    /** `null` when the item is unusable — it is then simply not in the catalogue. */
    private fun parsePackEntry(element: JsonElement?): PackEntry? {
        val obj = element?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val locale = obj.optString("locale") ?: return null
        val file = obj.optString("file") ?: return null
        val sha256 = obj.optString("sha256") ?: return null
        val size = obj.optLong("size") ?: return null
        return PackEntry(
            locale = locale,
            // A pack with no display name falls back to its own locale rather than dropping out
            // of the catalogue: the name is cosmetic, everything else is load-bearing.
            name = obj.optString("name") ?: locale,
            file = file,
            sha256 = sha256.lowercase(),
            size = size,
            group = obj.optString("group"),
        )
    }

    // ── Gson tree accessors ───────────────────────────────────────────────────────────────────
    //
    // Each returns null for "absent, JSON null, not a primitive, or the wrong primitive type",
    // so a caller can spell a required field as `?: return null` and an optional one as `?:
    // default`. Numbers are read through their string form so a float where an int belongs
    // (1430.0) is rejected rather than truncated.

    private fun JsonObject.optString(name: String): String? {
        val element = this[name] ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) return null
        return element.asString.takeIf { it.isNotEmpty() }
    }

    private fun JsonObject.optInt(name: String): Int? = optNumberText(name)?.toIntOrNull()

    private fun JsonObject.optLong(name: String): Long? = optNumberText(name)?.toLongOrNull()

    private fun JsonObject.optNumberText(name: String): String? {
        val element = this[name] ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) return null
        return element.asString
    }
}

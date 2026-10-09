package dev.bbkb.ime.core.settings.backup

import dev.bbkb.ime.personaldictionary.macro.CustomMacro
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The user's custom macros (Advanced > Custom macros: `%tag` placeholders that expand to text)
 * as one JSON document, and reading one back. They live in their own preference file, so the
 * settings document ([SettingsBackup]) never sees them; this is their part of a backup bundle.
 *
 * ```json
 * {
 *   "format": "bbkb-macros",
 *   "version": 1,
 *   "macros": [ { "tag": "p", "name": "Phone", "value": "+1 555 0100", "createdAt": 1759000000000 } ]
 * }
 * ```
 *
 * A restore replaces the macro set with the document's: a macro is addressed by its tag, and
 * merging two sets by tag would silently keep whichever side wrote a tag last.
 */
object MacrosBundle {

    const val FORMAT: String = "bbkb-macros"
    const val VERSION: Int = 1
    const val MAX_BYTES: Int = 1024 * 1024
    const val MAX_MACROS: Int = 500
    const val MAX_TAG_LENGTH: Int = 32
    const val MAX_NAME_LENGTH: Int = 64
    const val MAX_VALUE_LENGTH: Int = 4000

    private val KNOWN_FIELDS = setOf("format", "version", "macros")

    class InvalidMacrosException(message: String) : Exception(message)

    fun serialize(macros: List<CustomMacro>): String = JSONObject().apply {
        put("format", FORMAT)
        put("version", VERSION)
        put("macros", JSONArray().apply {
            macros.sortedBy { it.tag }.forEach { macro ->
                put(JSONObject().apply {
                    put("tag", macro.tag)
                    put("name", macro.name)
                    put("value", macro.value)
                    put("createdAt", macro.createdAt)
                })
            }
        })
    }.toString(2)

    fun parse(json: String): Result<List<CustomMacro>> = try {
        Result.success(parseOrThrow(json))
    } catch (e: InvalidMacrosException) {
        Result.failure(e)
    }

    private fun parseOrThrow(json: String): List<CustomMacro> {
        if (json.toByteArray(Charsets.UTF_8).size > MAX_BYTES) fail("the macros are larger than ${MAX_BYTES / 1024} KB")
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            fail("not JSON: ${e.message}")
        }
        val format = root.optString("format")
        if (format != FORMAT) fail("format is \"$format\", expected \"$FORMAT\"")
        val version = root.opt("version") as? Int ?: 0
        if (version < 1 || version > VERSION) fail("version ${root.opt("version")}, this build reads up to $VERSION")
        root.keys().forEach { if (it !in KNOWN_FIELDS) fail("unknown field \"$it\"") }

        val array = root.opt("macros") as? JSONArray ?: fail("macros must be a list")
        if (array.length() > MAX_MACROS) fail("macros has ${array.length()} entries, at most $MAX_MACROS")
        val tags = HashSet<String>()
        return (0 until array.length()).map { i ->
            val obj = array.opt(i) as? JSONObject ?: fail("macros[$i] must be an object")
            val tag = text(obj.opt("tag"), "macros[$i].tag", MAX_TAG_LENGTH)
            if (tag.any { it.isWhitespace() || it == '%' }) fail("macros[$i].tag contains whitespace or %")
            if (!tags.add(tag)) fail("macros has two entries with the tag \"$tag\"")
            val name = text(obj.opt("name"), "macros[$i].name", MAX_NAME_LENGTH)
            val value = text(obj.opt("value"), "macros[$i].value", MAX_VALUE_LENGTH, allowLineBreaks = true)
            val createdAt = when (val raw = obj.opt("createdAt")) {
                null, JSONObject.NULL -> System.currentTimeMillis()
                is Number -> raw.toLong()
                else -> fail("macros[$i].createdAt must be a number")
            }
            CustomMacro(tag = tag, name = name, value = value, createdAt = createdAt)
        }
    }

    private fun text(raw: Any?, where: String, maxLength: Int, allowLineBreaks: Boolean = false): String {
        val value = raw as? String ?: fail("$where must be a string")
        if (value.isEmpty()) fail("$where is empty")
        if (value.length > maxLength) fail("$where is longer than $maxLength characters")
        if (value.any { Character.isISOControl(it) && !(allowLineBreaks && (it == '\n' || it == '\t')) }) {
            fail("$where contains a control character")
        }
        return value
    }

    private fun fail(message: String): Nothing = throw InvalidMacrosException(message)
}

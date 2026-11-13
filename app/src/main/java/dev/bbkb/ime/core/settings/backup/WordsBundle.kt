package dev.bbkb.ime.core.settings.backup

import dev.bbkb.ime.core.settings.data.DictionaryEntry
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The personal dictionary (words and text shortcuts) and learned words as one JSON document:
 * `{"format":"bbkb-words","version":1,…,"dictionary":[{"word":"on my way","shortcut":"omw",
 * "locale":"en_US","fixedCase":true}],"learnedWords":["athena"]}`. No `shortcut` means a plain word,
 * `locale` `""` means every language. Learned words travel as words only, never the raw model
 * (it carries one phone's mistakes); they are replayed through the engine's learn call. Restore only adds.
 */
object WordsBundle {

    const val FORMAT: String = "bbkb-words"
    const val VERSION: Int = 1

    /** Largest document [parse] reads. */
    const val MAX_BYTES: Int = 8 * 1024 * 1024
    const val MAX_DICTIONARY_ENTRIES: Int = 50_000
    const val MAX_LEARNED_WORDS: Int = 200_000
    /** The engine's own word length cap. */
    const val MAX_WORD_LENGTH: Int = 64
    const val MAX_SHORTCUT_LENGTH: Int = 64
    const val MAX_LOCALE_LENGTH: Int = 16

    private val KNOWN_FIELDS = setOf("format", "version", "app", "versionCode", "created", "dictionary", "learnedWords")
    private val LOCALE_PATTERN = Regex("[A-Za-z0-9_-]*")

    /** A words document; a null section is absent. Entries carry no `id` (a per-phone storage detail). */
    class Words(
        val dictionary: List<DictionaryEntry>? = null,
        val learnedWords: List<String>? = null,
    ) {
        val isEmpty: Boolean get() = dictionary == null && learnedWords == null
    }

    /** [message] says which field is wrong and why. */
    class InvalidWordsException(message: String) : Exception(message)

    fun serialize(
        words: Words,
        appVersion: String,
        versionCode: Int,
        nowMillis: Long = System.currentTimeMillis(),
    ): String = JSONObject().apply {
        put("format", FORMAT)
        put("version", VERSION)
        put("app", appVersion)
        put("versionCode", versionCode)
        put("created", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(nowMillis)))
        words.dictionary?.let { entries ->
            // Sorted for deterministic output.
            put("dictionary", JSONArray().apply {
                entries.sortedWith(compareBy({ it.locale }, { it.displayKey.lowercase(Locale.ROOT) }, { it.word }))
                    .forEach { put(entryJson(it)) }
            })
        }
        words.learnedWords?.let { put("learnedWords", JSONArray(it.sorted())) }
    }.toString(2)

    private fun entryJson(entry: DictionaryEntry): JSONObject = JSONObject().apply {
        put("word", entry.word)
        // The store spells "no shortcut" as shortcut == word; the document omits the field.
        entry.shortcut?.takeIf { it != entry.word }?.let { put("shortcut", it) }
        put("locale", entry.locale)
        if (entry.fixedCase) put("fixedCase", true)
    }

    fun parse(json: String): Result<Words> = try {
        Result.success(parseOrThrow(json))
    } catch (e: InvalidWordsException) {
        Result.failure(e)
    }

    private fun parseOrThrow(json: String): Words {
        if (json.toByteArray(Charsets.UTF_8).size > MAX_BYTES) fail("the file is larger than ${MAX_BYTES / (1024 * 1024)} MB")
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

        val words = Words(
            dictionary = dictionary(root),
            learnedWords = learnedWords(root),
        )
        if (words.isEmpty) fail("the file holds no words")
        return words
    }

    private fun dictionary(root: JSONObject): List<DictionaryEntry>? {
        val array = optArray(root, "dictionary") ?: return null
        if (array.length() > MAX_DICTIONARY_ENTRIES) fail("dictionary has ${array.length()} entries, at most $MAX_DICTIONARY_ENTRIES")
        val seen = HashSet<String>()
        val entries = ArrayList<DictionaryEntry>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.opt(i) as? JSONObject ?: fail("dictionary[$i] must be an object")
            val word = checkText(obj.opt("word"), "dictionary[$i].word", MAX_WORD_LENGTH)
            val shortcut = obj.opt("shortcut")?.takeIf { it != JSONObject.NULL }
                ?.let { checkText(it, "dictionary[$i].shortcut", MAX_SHORTCUT_LENGTH) }
            val locale = locale(obj.opt("locale"), "dictionary[$i].locale")
            val fixedCase = when (val raw = obj.opt("fixedCase")) {
                null, JSONObject.NULL -> false
                is Boolean -> raw
                else -> fail("dictionary[$i].fixedCase must be true or false")
            }
            // The store keys entries by locale + shortcut (or word); a collision would merge on restore.
            if (!seen.add("$locale\u0000${shortcut ?: word}")) fail("dictionary[$i] repeats \"${shortcut ?: word}\"")
            entries += DictionaryEntry(word = word, shortcut = shortcut, locale = locale, fixedCase = fixedCase)
        }
        return entries
    }

    private fun learnedWords(root: JSONObject): List<String>? {
        val array = optArray(root, "learnedWords") ?: return null
        if (array.length() > MAX_LEARNED_WORDS) fail("learnedWords has ${array.length()} words, at most $MAX_LEARNED_WORDS")
        val words = LinkedHashSet<String>(array.length())
        for (i in 0 until array.length()) {
            val word = checkText(array.opt(i), "learnedWords[$i]", MAX_WORD_LENGTH)
            // A learned word never contains whitespace (phrases belong to the shortcut store).
            if (word.any { it.isWhitespace() }) fail("learnedWords[$i] contains whitespace")
            words += word
        }
        return words.toList()
    }

    private fun locale(raw: Any?, where: String): String {
        if (raw == null || raw == JSONObject.NULL) return ""
        val value = raw as? String ?: fail("$where must be a string")
        if (value.length > MAX_LOCALE_LENGTH) fail("$where is longer than $MAX_LOCALE_LENGTH characters")
        if (!LOCALE_PATTERN.matches(value)) fail("$where is not a locale code")
        return value
    }

    private fun checkText(raw: Any?, where: String, maxLength: Int): String {
        val value = raw as? String ?: fail("$where must be a string")
        if (value.isEmpty()) fail("$where is empty")
        if (value.length > maxLength) fail("$where is longer than $maxLength characters")
        if (value.any { Character.isISOControl(it) }) fail("$where contains a control character")
        return value
    }

    private fun optArray(root: JSONObject, field: String): JSONArray? {
        val raw = root.opt(field) ?: return null
        if (raw == JSONObject.NULL) return null
        return raw as? JSONArray ?: fail("$field must be a list")
    }

    private fun fail(message: String): Nothing = throw InvalidWordsException(message)
}

package dev.bbkb.ime.core.settings.backup

import android.content.Context
import dev.bbkb.ime.core.layout.InvalidLetterLayoutException
import dev.bbkb.ime.core.layout.LetterLayout
import dev.bbkb.ime.core.layout.LetterLayoutXml
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.settings.data.CustomSymbolRepository
import dev.bbkb.ime.core.settings.screens.QUICK_PHRASE_KEYS
import dev.bbkb.ime.core.settings.screens.keyeditor.EMPTY_SLOT
import dev.bbkb.ime.core.settings.screens.keyeditor.KeyEditorSpec
import dev.bbkb.ime.core.settings.screens.keyeditor.PkbSymbolPageSpec
import dev.bbkb.ime.core.settings.screens.keyeditor.SlideboardNumpadSpec
import dev.bbkb.ime.core.settings.screens.keyeditor.VkbSymbolPageSpec
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The user's editor layouts as one human-readable JSON file (symbol pages, palette, slideboard,
 * quick phrases, currency key). Letter layouts travel as separate XML files ([BackupBundle]).
 * Shape:
 *
 * ```json
 * {
 *   "format": "bbkb-layouts", "version": 1, "app": "…", "versionCode": 1437, "created": "…Z",
 *   "vkbSymbolPage": [26 slots], "pkbSymbolPage": [27 slots], "palette": ["★", "→"],
 *   "slideboard": [20 slots], "quickPhrases": ["On my way!", null, null, null, null],
 *   "currency": "€"
 * }
 * ```
 *
 * Lists are in editor order (row by row on the editor's mock keyboard), read and written through
 * the [KeyEditorSpec]s because two stores keep a permutation only the specs know. A blank slot is
 * `""`, a `null` quick phrase shows the translated default, `""` currency means the keyboard's own.
 * On import a missing section is left alone; a present one must be complete (never padded), and
 * the whole file is validated before anything is written. A legacy `pkbLetterMaps` field is skipped.
 */
object LayoutsBundle {

    const val FORMAT: String = "bbkb-layouts"
    const val VERSION: Int = 1
    const val MIME_TYPE: String = "application/json"

    /** Largest file [parse] reads (a full bundle is tens of KB). */
    const val MAX_BYTES: Int = 1024 * 1024

    const val VKB_SLOTS: Int = 26
    const val PKB_SLOTS: Int = 27

    /** Older bundles carry a 28th physical slot; accepted, and the editor trims it on load. */
    const val PKB_SLOTS_LEGACY: Int = 28
    const val SLIDEBOARD_SLOTS: Int = 20
    const val QUICK_PHRASES: Int = 5
    const val MAX_PALETTE: Int = 500
    const val MAX_SLOT_LENGTH: Int = 64
    const val MAX_PHRASE_LENGTH: Int = 1000
    const val MAX_CURRENCY_LENGTH: Int = 8

    private const val CURRENCY_KEY = "pref_currency_key"

    /**
     * Preference keys of the editor sections. A bundle keeps these out of its settings part so
     * Settings and Layouts restore or reset separately; [resetEditors] clears exactly these.
     */
    val PREF_KEYS: Set<String> by lazy {
        setOf(
            "pref_vkb_symbol_page_layout",
            "pref_pkb_symbol_page_layout",
            "pref_additional_symbol_list",
            "custom_slideboard_symbols",
            CURRENCY_KEY,
        ) + QUICK_PHRASE_KEYS
    }

    /**
     * Resets every editor section by removing [PREF_KEYS] and clears the keyboard cache as an edit
     * does. Main thread. Letter layout files are the caller's to delete.
     */
    fun resetEditors(context: Context) {
        val editor = PrefsManager.getPrefs(context).edit()
        PREF_KEYS.forEach { editor.remove(it) }
        editor.apply()
        dev.bbkb.ime.keyboard.KeyboardBuilder.onKeyboardThemeChanged()
    }

    /** `CustomSymbolRepository.DELIMITER`, the slot stores' separator. */
    private const val STORAGE_DELIMITER = '͸'

    private val KNOWN_FIELDS = setOf(
        "format", "version", "app", "versionCode", "created",
        "vkbSymbolPage", "pkbSymbolPage", "palette", "slideboard", "quickPhrases", "currency",
    )

    /** Fields read past without refusing the file. */
    private val RETIRED_FIELDS = setOf("pkbLetterMaps")

    /** A layouts file; a null section is absent (left alone on import). */
    class Layouts(
        val vkbSymbolPage: List<String>? = null,
        val pkbSymbolPage: List<String>? = null,
        val palette: List<String>? = null,
        val slideboard: List<String>? = null,
        /** `null` entries are phrases the user never set. */
        val quickPhrases: List<String?>? = null,
        val currency: String? = null,
    ) {
        val hasEditorSections: Boolean
            get() = vkbSymbolPage != null || pkbSymbolPage != null || palette != null ||
                slideboard != null || quickPhrases != null || currency != null
    }

    /** What the Import row was handed: a bundle or one letter layout. */
    sealed interface Import {
        class Bundle(val layouts: Layouts) : Import
        class LetterMap(val layout: LetterLayout) : Import
    }

    /** [message] says which field is wrong and why. */
    class InvalidLayoutsException(message: String) : Exception(message)

    /** `bbkb-layouts-<yyyy-MM-dd>.json`, the save dialog's suggested name. */
    fun defaultFileName(nowMillis: Long = System.currentTimeMillis()): String =
        "bbkb-layouts-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(nowMillis)) + ".json"

    /** Current layouts with every section, defaults included, as the editors read them. Any thread. */
    fun capture(context: Context): Layouts {
        val prefs = PrefsManager.getPrefs(context)
        return Layouts(
            vkbSymbolPage = fromSlots(VkbSymbolPageSpec.load(context)),
            pkbSymbolPage = fromSlots(PkbSymbolPageSpec.load(context)),
            palette = CustomSymbolRepository(context).loadCustomSymbols(),
            slideboard = fromSlots(SlideboardNumpadSpec.load(context)),
            quickPhrases = QUICK_PHRASE_KEYS.map { key -> if (prefs.contains(key)) prefs.getString(key, null) else null },
            currency = prefs.getString(CURRENCY_KEY, "") ?: "",
        )
    }

    fun serialize(
        layouts: Layouts,
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
        layouts.vkbSymbolPage?.let { put("vkbSymbolPage", JSONArray(it)) }
        layouts.pkbSymbolPage?.let { put("pkbSymbolPage", JSONArray(it)) }
        layouts.palette?.let { put("palette", JSONArray(it)) }
        layouts.slideboard?.let { put("slideboard", JSONArray(it)) }
        layouts.quickPhrases?.let { phrases ->
            put("quickPhrases", JSONArray().apply { phrases.forEach { put(it ?: JSONObject.NULL) } })
        }
        layouts.currency?.let { put("currency", it) }
    }.toString(2)

    /** Reads at most [MAX_BYTES] + 1 bytes, so [parse] can refuse an oversized file cheaply. */
    @Throws(IOException::class)
    fun readCapped(input: InputStream): String {
        val bytes = input.readNBytesCompat(MAX_BYTES + 1)
        return bytes.toString(Charsets.UTF_8)
    }

    private fun InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /** Parses and fully validates a layouts bundle (JSON) or one letter layout (XML). */
    fun parse(text: String): Result<Import> = try {
        Result.success(parseOrThrow(text))
    } catch (e: InvalidLayoutsException) {
        Result.failure(e)
    } catch (e: InvalidLetterLayoutException) {
        Result.failure(InvalidLayoutsException(e.message ?: "not a valid layout"))
    }

    private fun parseOrThrow(text: String): Import {
        if (LetterLayoutXml.looksLikeXml(text)) return Import.LetterMap(LetterLayoutXml.parse(text))
        val size = text.toByteArray(Charsets.UTF_8).size
        if (size > MAX_BYTES) fail("the file is larger than ${MAX_BYTES / 1024} KB")
        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            fail("not JSON: ${e.message}")
        }
        val format = root.optString("format")
        if (format != FORMAT) fail("format is \"$format\", expected \"$FORMAT\"")
        val version = root.opt("version") as? Int ?: 0
        if (version < 1 || version > VERSION) fail("version ${root.opt("version")}, this build reads up to $VERSION")
        root.keys().forEach { if (it !in KNOWN_FIELDS && it !in RETIRED_FIELDS) fail("unknown field \"$it\"") }

        val layouts = Layouts(
            vkbSymbolPage = slots(root, "vkbSymbolPage", VKB_SLOTS..VKB_SLOTS),
            pkbSymbolPage = slots(root, "pkbSymbolPage", PKB_SLOTS..PKB_SLOTS_LEGACY),
            palette = palette(root),
            slideboard = slots(root, "slideboard", SLIDEBOARD_SLOTS..SLIDEBOARD_SLOTS),
            quickPhrases = quickPhrases(root),
            currency = currency(root),
        )
        if (!layouts.hasEditorSections) fail("the file holds no layouts")
        return Import.Bundle(layouts)
    }

    private fun slots(root: JSONObject, field: String, count: IntRange): List<String>? {
        val array = optArray(root, field) ?: return null
        if (array.length() !in count) {
            val expected = if (count.first == count.last) "${count.first}" else "${count.first} or ${count.last}"
            fail("$field has ${array.length()} slots, expected $expected")
        }
        return (0 until array.length()).map { i ->
            val value = array.opt(i) as? String ?: fail("$field[$i] must be a string")
            checkSymbol(value, "$field[$i]", allowBlank = true)
        }
    }

    private fun palette(root: JSONObject): List<String>? {
        val array = optArray(root, "palette") ?: return null
        if (array.length() > MAX_PALETTE) fail("palette has ${array.length()} symbols, at most $MAX_PALETTE")
        return (0 until array.length()).map { i ->
            val value = array.opt(i) as? String ?: fail("palette[$i] must be a string")
            checkSymbol(value, "palette[$i]", allowBlank = false)
        }
    }

    private fun quickPhrases(root: JSONObject): List<String?>? {
        val array = optArray(root, "quickPhrases") ?: return null
        if (array.length() != QUICK_PHRASES) fail("quickPhrases has ${array.length()} phrases, expected $QUICK_PHRASES")
        return (0 until array.length()).map { i ->
            when (val value = array.opt(i)) {
                null, JSONObject.NULL -> null
                is String -> value.also {
                    if (it.length > MAX_PHRASE_LENGTH) fail("quickPhrases[$i] is longer than $MAX_PHRASE_LENGTH characters")
                    if (it.any { c -> Character.isISOControl(c) && c != '\n' && c != '\t' }) {
                        fail("quickPhrases[$i] contains a control character")
                    }
                }
                else -> fail("quickPhrases[$i] must be a string or null")
            }
        }
    }

    private fun currency(root: JSONObject): String? {
        val raw = root.opt("currency") ?: return null
        if (raw == JSONObject.NULL) return null
        val value = raw as? String ?: fail("currency must be a string")
        if (value.length > MAX_CURRENCY_LENGTH) fail("currency is longer than $MAX_CURRENCY_LENGTH characters")
        if (value.any { Character.isISOControl(it) }) fail("currency contains a control character")
        return value
    }

    private fun optArray(root: JSONObject, field: String): JSONArray? {
        val raw = root.opt(field) ?: return null
        if (raw == JSONObject.NULL) return null
        return raw as? JSONArray ?: fail("$field must be a list")
    }

    /** A slot or palette symbol: short, printable, and without the stores' list separator. */
    private fun checkSymbol(value: String, where: String, allowBlank: Boolean): String {
        if (value.isEmpty()) {
            if (allowBlank) return value
            fail("$where is empty")
        }
        if (value.length > MAX_SLOT_LENGTH) fail("$where is longer than $MAX_SLOT_LENGTH characters")
        if (value.any { Character.isISOControl(it) }) fail("$where contains a control character")
        if (STORAGE_DELIMITER in value) fail("$where contains U+0378, which the keyboard cannot store")
        return value
    }

    /**
     * Writes the sections [layouts] carries through the editors' own specs, so caches clear as an
     * edit does. Main thread: `KeyboardBuilder`'s cache is a plain `HashMap`.
     *
     * @return how many sections were written
     */
    fun applyEditors(context: Context, layouts: Layouts): Int {
        var written = 0
        layouts.vkbSymbolPage?.let { VkbSymbolPageSpec.save(context, toSlots(it)); written++ }
        layouts.pkbSymbolPage?.let { PkbSymbolPageSpec.save(context, toSlots(it)); written++ }
        layouts.slideboard?.let { SlideboardNumpadSpec.save(context, toSlots(it)); written++ }
        layouts.palette?.let { CustomSymbolRepository(context).replaceCustomSymbols(it); written++ }
        if (layouts.quickPhrases != null || layouts.currency != null) {
            val editor = PrefsManager.getPrefs(context).edit()
            layouts.quickPhrases?.let { phrases ->
                QUICK_PHRASE_KEYS.forEachIndexed { i, key ->
                    val phrase = phrases.getOrNull(i)
                    if (phrase == null) editor.remove(key) else editor.putString(key, phrase)
                }
                written++
            }
            layouts.currency?.let { editor.putString(CURRENCY_KEY, it); written++ }
            editor.apply()
        }
        return written
    }

    private fun fromSlots(slots: List<String>): List<String> = slots.map { if (it == EMPTY_SLOT) "" else it }

    /** `""` to the blank-slot marker; the slideboard loader treats any `""` as "reset to default". */
    private fun toSlots(slots: List<String>): List<String> = slots.map { it.ifEmpty { EMPTY_SLOT } }

    private fun fail(message: String): Nothing = throw InvalidLayoutsException(message)
}

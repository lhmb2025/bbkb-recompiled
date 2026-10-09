package dev.bbkb.ime.core.settings.backup

import android.content.Context
import dev.bbkb.ime.core.keyevent.InvalidLetterMapException
import dev.bbkb.ime.core.keyevent.UserLetterMap
import dev.bbkb.ime.core.keyevent.UserLetterMapRepository
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
 * The user's layouts as one JSON file, and reading one back: the two symbol pages, the custom
 * palette, the slideboard keypad, the quick phrases, the currency key and any imported physical
 * letter maps. The settings backup ([SettingsBackup]) carries the raw preferences; this carries
 * the layouts in a form a person can read, edit and hand to someone else.
 *
 * ## Document shape
 *
 * ```json
 * {
 *   "format": "bbkb-layouts",
 *   "version": 1,
 *   "app": "5.0.0-beta.24",
 *   "versionCode": 1437,
 *   "created": "2026-10-06T09:14:07Z",
 *   "vkbSymbolPage": ["1", "2", "3", …26 slots],
 *   "pkbSymbolPage": ["!", "@", "#", …28 slots],
 *   "palette": ["★", "→"],
 *   "slideboard": ["7", "8", "9", "&", "*", …20 slots],
 *   "quickPhrases": ["On my way!", null, null, null, null],
 *   "currency": "€",
 *   "pkbLetterMaps": [ { "format": "bbkb-layout", … } ]
 * }
 * ```
 *
 * Every list is in **editor order** — the order the slots appear on the editor's mock keyboard,
 * row by row — because that is the order a person reading the file sees, and because two of the
 * stores are not in that order: the on-screen symbol page and the slideboard keep a permutation
 * only `KeyEditorSpec.load`/`save` know (see [KeyEditorSpec]). So this reads and writes them
 * through the specs and never touches those preferences directly. A blank slot is `""`. A `null`
 * quick phrase is one the user never set, which shows the translated default; `""` for the
 * currency means the keyboard's own currency key.
 *
 * ## Import semantics
 *
 * Every section is optional, and a section the file leaves out is left alone, as [SettingsBackup]
 * merges rather than replaces. A section that is present must be complete: a symbol page with 25
 * slots is refused, not padded, because the user's 26th key would silently go blank. The parse
 * runs to completion before anything is written ([parse] then [applyEditors]), so a bad file
 * changes nothing. [parse] also accepts a single `bbkb-layout` letter map, so one Import row
 * serves both kinds of file.
 */
object LayoutsBundle {

    const val FORMAT: String = "bbkb-layouts"
    const val VERSION: Int = 1
    const val MIME_TYPE: String = "application/json"

    /** Search anchors for the two Manage data rows; see [SettingsBackup.ANCHOR_BACK_UP]. */
    const val ANCHOR_EXPORT: String = "layouts_export"
    const val ANCHOR_IMPORT: String = "layouts_import"

    /** Largest file [parse] reads. A full bundle with a few letter maps is tens of kilobytes. */
    const val MAX_BYTES: Int = 1024 * 1024

    const val VKB_SLOTS: Int = 26
    const val PKB_SLOTS: Int = 28

    /**
     * The physical page may also come with 27 slots: the shipped default page (`pkb_page_1`) has
     * 27 entries, so a page that was never saved, or saved straight from it, loads as 27, and the
     * keyboard keeps its own symbol on the 28th key. Padding it to 28 would blank that key instead
     * (a stored blank is drawn as an empty key), so 27 is carried as 27.
     */
    const val PKB_SLOTS_MIN: Int = 27
    const val SLIDEBOARD_SLOTS: Int = 20
    const val QUICK_PHRASES: Int = 5
    const val MAX_PALETTE: Int = 500
    const val MAX_SLOT_LENGTH: Int = 64
    const val MAX_PHRASE_LENGTH: Int = 1000
    const val MAX_CURRENCY_LENGTH: Int = 8
    const val MAX_LETTER_MAPS: Int = 32

    /** `SettingsManager.getCurrencySymbol` / `SymbolCustomizationScreen`'s key. */
    private const val CURRENCY_KEY = "pref_currency_key"

    /** `CustomSymbolRepository.DELIMITER`: the separator of the stores the slots live in. */
    private const val STORAGE_DELIMITER = '͸'

    private val KNOWN_FIELDS = setOf(
        "format", "version", "app", "versionCode", "created",
        "vkbSymbolPage", "pkbSymbolPage", "palette", "slideboard", "quickPhrases", "currency",
        "pkbLetterMaps",
    )

    /**
     * What a layouts file holds. A null section is one the file does not carry (on import: leave
     * that layout alone). Lists are in editor order; a blank slot is `""`.
     */
    class Layouts(
        val vkbSymbolPage: List<String>? = null,
        val pkbSymbolPage: List<String>? = null,
        val palette: List<String>? = null,
        val slideboard: List<String>? = null,
        /** `null` entries are phrases the user never set. */
        val quickPhrases: List<String?>? = null,
        val currency: String? = null,
        val pkbLetterMaps: List<UserLetterMap> = emptyList(),
    ) {
        /** Whether the file carries any of the editor sections, as opposed to only letter maps. */
        val hasEditorSections: Boolean
            get() = vkbSymbolPage != null || pkbSymbolPage != null || palette != null ||
                slideboard != null || quickPhrases != null || currency != null
    }

    /** What the Import row was handed: a layouts bundle, or one physical letter map. */
    sealed interface Import {
        class Bundle(val layouts: Layouts) : Import
        class LetterMap(val map: UserLetterMap) : Import
    }

    /** The file is not one this build can import; [message] says why, for the user to fix it. */
    class InvalidLayoutsException(message: String) : Exception(message)

    // ── export ───────────────────────────────────────────────────────────────

    /** `bbkb-layouts-2026-10-06.json` — what the save dialog opens pre-filled. */
    fun defaultFileName(nowMillis: Long = System.currentTimeMillis()): String =
        "bbkb-layouts-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(nowMillis)) + ".json"

    /**
     * The user's current layouts, every section present, read the way the editors read them —
     * defaults included, so the file describes what the keyboard shows. Safe off the main thread.
     */
    fun capture(context: Context, letterMaps: List<UserLetterMap>): Layouts {
        val prefs = PrefsManager.getPrefs(context)
        return Layouts(
            vkbSymbolPage = fromSlots(VkbSymbolPageSpec.load(context)),
            pkbSymbolPage = fromSlots(PkbSymbolPageSpec.load(context)),
            palette = CustomSymbolRepository(context).loadCustomSymbols(),
            slideboard = fromSlots(SlideboardNumpadSpec.load(context)),
            quickPhrases = QUICK_PHRASE_KEYS.map { key -> if (prefs.contains(key)) prefs.getString(key, null) else null },
            currency = prefs.getString(CURRENCY_KEY, "") ?: "",
            pkbLetterMaps = letterMaps,
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
        if (layouts.pkbLetterMaps.isNotEmpty()) {
            put("pkbLetterMaps", JSONArray().apply { layouts.pkbLetterMaps.forEach { put(it.toJson()) } })
        }
    }.toString(2)

    // ── import ───────────────────────────────────────────────────────────────

    /**
     * Reads at most one byte past [MAX_BYTES] from [input], so an oversized file is refused by
     * [parse] without the whole of it being held in memory.
     */
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

    /**
     * Reads a layouts bundle or a single letter map, validating all of it. Failures are
     * [InvalidLayoutsException]s whose message names the field and the problem.
     */
    fun parse(json: String): Result<Import> = try {
        Result.success(parseOrThrow(json))
    } catch (e: InvalidLayoutsException) {
        Result.failure(e)
    } catch (e: InvalidLetterMapException) {
        Result.failure(InvalidLayoutsException(e.message ?: "not a valid layout"))
    }

    private fun parseOrThrow(json: String): Import {
        val size = json.toByteArray(Charsets.UTF_8).size
        if (size > MAX_BYTES) fail("the file is larger than ${MAX_BYTES / 1024} KB")
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            fail("not JSON: ${e.message}")
        }
        when (val format = root.optString("format")) {
            UserLetterMap.FORMAT -> {
                if (size > UserLetterMap.MAX_BYTES) fail("the layout is larger than ${UserLetterMap.MAX_BYTES / 1024} KB")
                return Import.LetterMap(UserLetterMap.fromJson(root))
            }
            FORMAT -> {}
            else -> fail("format is \"$format\", expected \"$FORMAT\" or \"${UserLetterMap.FORMAT}\"")
        }
        val version = root.opt("version") as? Int ?: 0
        if (version < 1 || version > VERSION) fail("version ${root.opt("version")}, this build reads up to $VERSION")
        root.keys().forEach { if (it !in KNOWN_FIELDS) fail("unknown field \"$it\"") }

        val layouts = Layouts(
            vkbSymbolPage = slots(root, "vkbSymbolPage", VKB_SLOTS..VKB_SLOTS),
            pkbSymbolPage = slots(root, "pkbSymbolPage", PKB_SLOTS_MIN..PKB_SLOTS),
            palette = palette(root),
            slideboard = slots(root, "slideboard", SLIDEBOARD_SLOTS..SLIDEBOARD_SLOTS),
            quickPhrases = quickPhrases(root),
            currency = currency(root),
            pkbLetterMaps = letterMaps(root),
        )
        if (!layouts.hasEditorSections && layouts.pkbLetterMaps.isEmpty()) fail("the file holds no layouts")
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

    private fun letterMaps(root: JSONObject): List<UserLetterMap> {
        val array = optArray(root, "pkbLetterMaps") ?: return emptyList()
        if (array.length() > MAX_LETTER_MAPS) fail("pkbLetterMaps has ${array.length()} layouts, at most $MAX_LETTER_MAPS")
        val maps = (0 until array.length()).map { i ->
            val obj = array.opt(i) as? JSONObject ?: fail("pkbLetterMaps[$i] must be an object")
            try {
                UserLetterMap.fromJson(obj)
            } catch (e: InvalidLetterMapException) {
                fail("pkbLetterMaps[$i]: ${e.message}")
            }
        }
        maps.groupBy { it.id }.filterValues { it.size > 1 }.keys.firstOrNull()?.let {
            fail("pkbLetterMaps has two layouts with the id \"$it\"")
        }
        return maps
    }

    private fun optArray(root: JSONObject, field: String): JSONArray? {
        val raw = root.opt(field) ?: return null
        if (raw == JSONObject.NULL) return null
        return raw as? JSONArray ?: fail("$field must be a list")
    }

    /**
     * One key or palette symbol: short, printable, and free of the two characters the stores
     * cannot hold — their list separator and the blank-slot marker.
     */
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
     * Writes the editor sections [layouts] carries, through the same repositories and specs the
     * editors save with — so the symbol pages and the palette clear `KeyboardBuilder`'s cache as
     * an edit does. Main thread: that cache is a plain `HashMap` the keyboard reads on the main
     * thread. Letter maps are not written here; [saveLetterMaps] does that, off it.
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

    /** Saves [maps] into [repository], replacing maps with the same ids. File IO: not the main thread. */
    @Throws(IOException::class)
    fun saveLetterMaps(repository: UserLetterMapRepository, maps: List<UserLetterMap>) {
        maps.forEach { repository.save(it) }
    }

    /** Editor slots as the file writes them: the blank-slot marker becomes `""`. */
    private fun fromSlots(slots: List<String>): List<String> = slots.map { if (it == EMPTY_SLOT) "" else it }

    /**
     * File slots as the editors store them: `""` becomes the blank-slot marker. The slideboard in
     * particular must never be handed `""`: its loader treats any empty entry as "never
     * customised" and would put the whole default pad back.
     */
    private fun toSlots(slots: List<String>): List<String> = slots.map { it.ifEmpty { EMPTY_SLOT } }

    private fun fail(message: String): Nothing = throw InvalidLayoutsException(message)
}

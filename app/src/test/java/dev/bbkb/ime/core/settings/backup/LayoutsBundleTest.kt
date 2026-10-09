package dev.bbkb.ime.core.settings.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.keyevent.UserLetterMap
import dev.bbkb.ime.core.keyevent.UserLetterMapRepository
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.settings.data.CustomSymbolRepository
import dev.bbkb.ime.core.settings.screens.keyeditor.EMPTY_SLOT
import dev.bbkb.ime.core.settings.screens.keyeditor.PkbSymbolPageSpec
import dev.bbkb.ime.core.settings.screens.keyeditor.SlideboardNumpadSpec
import dev.bbkb.ime.core.settings.screens.keyeditor.VkbSymbolPageSpec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The layouts file ([LayoutsBundle]): it carries every editor's layout in **editor order** —
 * including the two stores kept in a scrambled order, which is the part that would silently
 * corrupt someone's keyboard if it went through the raw preferences — and a round trip lands the
 * same bytes back in storage. A bad file is refused, with a reason, before anything is written.
 *
 * Reads and writes the [PrefsManager] singleton, as the editors do (see
 * `KeyLayoutEditorBehaviourTest` for why), and leaves it empty for the next class.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class LayoutsBundleTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val prefs get() = PrefsManager.getPrefs(context)

    /** `CustomSymbolRepository.DELIMITER` / `SettingsManager`'s list separator. */
    private val delimiter = "͸"

    /** Where the keyboard reads the VKB page's editor slot i from (`CustomSymbolRepository`). */
    private val vkbReadOrder = intArrayOf(
        6, 7, 8, 9, 10, 11, 12, 13, 14, 5,
        25, 21, 20, 19, 22, 23, 24, 18, 17,
        1, 3, 4, 15, 0, 16, 2,
    )

    /** Where the slideboard reads editor slot i from (`KeyEditorSpec`'s NUMPAD_KEYBOARD_READ_ORDER). */
    private val numpadReadOrder = intArrayOf(15, 16, 17, 4, 5, 12, 13, 14, 2, 3, 9, 10, 11, 19, 1, 8, 7, 6, 18, 0)

    @Before
    fun clear() {
        prefs.edit().clear().commit()
        UserLetterMapRepository.directoryOf(context).deleteRecursively()
    }

    @After
    fun clearAgain() = clear()

    private fun storedList(key: String): List<String> =
        prefs.getString(key, null)?.split(delimiter)?.let {
            if (it.isNotEmpty() && it.last().isEmpty()) it.dropLast(1) else it
        } ?: emptyList()

    private fun seed(key: String, values: List<String>) {
        prefs.edit().putString(key, values.joinToString(delimiter)).commit()
    }

    private fun export(): String = LayoutsBundle.serialize(
        LayoutsBundle.capture(context, UserLetterMapRepository(context).list()),
        appVersion = "5.0.0-beta.24",
        versionCode = 1437,
    )

    private fun bundleOf(json: String): LayoutsBundle.Layouts =
        (LayoutsBundle.parse(json).getOrThrow() as LayoutsBundle.Import.Bundle).layouts

    private fun strings(array: JSONArray) = (0 until array.length()).map { array.optString(it) }

    private val letterMap = """
        {"format": "bbkb-layout", "version": 1, "kind": "pkb", "id": "swap", "name": "Swap",
         "bind": {"locales": [], "keypadLayout": "qwerty"},
         "keys": {"KEYCODE_Q": {"base": "w"}, "KEYCODE_W": {"base": "q"}}}
    """.trimIndent()

    // ── export: editor order ─────────────────────────────────────────────────

    @Test
    fun theScrambledStoresAreExportedInEditorOrder() {
        // Storage tokens s0..s27 / n0..n19: the file must hold them in the order the editor shows.
        seed("pref_vkb_symbol_page_layout", List(28) { "s$it" })
        prefs.edit().putString("custom_slideboard_symbols", List(20) { "n$it" }.joinToString(delimiter) + delimiter).commit()

        val json = JSONObject(export())

        assertEquals(vkbReadOrder.map { "s$it" }, strings(json.getJSONArray("vkbSymbolPage")))
        assertEquals(numpadReadOrder.map { "n$it" }, strings(json.getJSONArray("slideboard")))
        // And that order is the editors' own.
        assertEquals(VkbSymbolPageSpec.load(context), strings(json.getJSONArray("vkbSymbolPage")))
        assertEquals(SlideboardNumpadSpec.load(context), strings(json.getJSONArray("slideboard")))
    }

    @Test
    fun theUnscrambledStoresAndTheRestAreExportedAsStored() {
        seed("pref_pkb_symbol_page_layout", List(28) { "p$it" })
        seed("pref_additional_symbol_list", listOf("★", "→", "♪"))
        prefs.edit()
            .putString("quick_phrase_2", "On my way")
            .putString("pref_currency_key", "€")
            .commit()

        val json = JSONObject(export())

        assertEquals("bbkb-layouts", json.getString("format"))
        assertEquals(1, json.getInt("version"))
        assertEquals(List(28) { "p$it" }, strings(json.getJSONArray("pkbSymbolPage")))
        assertEquals(listOf("★", "→", "♪"), strings(json.getJSONArray("palette")))
        val phrases = json.getJSONArray("quickPhrases")
        assertEquals(5, phrases.length())
        assertTrue("an unset phrase is null, so the translated default survives", phrases.isNull(0))
        assertEquals("On my way", phrases.getString(1))
        assertEquals("€", json.getString("currency"))
        assertFalse("no letter maps, no section", json.has("pkbLetterMaps"))
    }

    @Test
    fun aFreshInstallExportsTheDefaultsTheEditorsShow() {
        val json = JSONObject(export())
        assertEquals(26, json.getJSONArray("vkbSymbolPage").length())
        // The shipped physical page is 27 entries; the 28th key keeps the keyboard's own symbol.
        assertEquals(27, json.getJSONArray("pkbSymbolPage").length())
        assertEquals(PkbSymbolPageSpec.load(context), strings(json.getJSONArray("pkbSymbolPage")))
        assertEquals(context.resources.getStringArray(dev.bbkb.ime.R.array.slideboard_numpad_symbols).toList(),
            strings(json.getJSONArray("slideboard")))
        assertEquals(0, json.getJSONArray("palette").length())
        assertEquals("", json.getString("currency"))
    }

    @Test
    fun aFreshInstallRoundTripsWithoutBlankingThe28thPhysicalKey() {
        val json = export()
        clear()
        LayoutsBundle.applyEditors(context, bundleOf(json))
        // 27 stored entries: past the end the keyboard keeps its own key. A 28th entry would be a
        // stored blank, which KeyboardBuilder draws as an empty, inactive key.
        assertEquals(27, storedList("pref_pkb_symbol_page_layout").size)
    }

    @Test
    fun aBlankSlotIsAnEmptyStringInTheFile() {
        val page = VkbSymbolPageSpec.load(context).toMutableList().apply { this[3] = EMPTY_SLOT }
        VkbSymbolPageSpec.save(context, page)
        assertEquals("", JSONObject(export()).getJSONArray("vkbSymbolPage").getString(3))
    }

    // ── round trip ───────────────────────────────────────────────────────────

    @Test
    fun aRoundTripPutsTheSameBytesBackInEveryStore() {
        seed("pref_vkb_symbol_page_layout", List(28) { "s$it" })
        seed("pref_pkb_symbol_page_layout", List(28) { "p$it" })
        seed("pref_additional_symbol_list", listOf("★", "→"))
        prefs.edit()
            .putString("custom_slideboard_symbols", List(20) { "n$it" }.joinToString(delimiter) + delimiter)
            .putString("quick_phrase_1", "Hello")
            .putString("quick_phrase_5", "Bye")
            .putString("pref_currency_key", "£")
            .commit()
        val before = mapOf(
            "vkb" to storedList("pref_vkb_symbol_page_layout"),
            "pkb" to storedList("pref_pkb_symbol_page_layout"),
            "palette" to storedList("pref_additional_symbol_list"),
            "slideboard" to storedList("custom_slideboard_symbols"),
        )
        val json = export()

        clear()
        // Something different in every store, so a section that failed to apply would show.
        prefs.edit().putString("quick_phrase_3", "stale").putString("pref_currency_key", "$").commit()
        assertEquals(6, LayoutsBundle.applyEditors(context, bundleOf(json)))

        // The VKB store holds 28 entries but the keyboard reads only the 26 in its read order.
        val vkbAfter = storedList("pref_vkb_symbol_page_layout")
        vkbReadOrder.forEach { assertEquals(before.getValue("vkb")[it], vkbAfter[it]) }
        assertEquals(before.getValue("pkb"), storedList("pref_pkb_symbol_page_layout"))
        assertEquals(before.getValue("palette"), storedList("pref_additional_symbol_list"))
        assertEquals(before.getValue("slideboard"), storedList("custom_slideboard_symbols"))
        assertEquals("Hello", prefs.getString("quick_phrase_1", null))
        assertEquals("Bye", prefs.getString("quick_phrase_5", null))
        assertFalse("a phrase unset in the file goes back to its default", prefs.contains("quick_phrase_3"))
        assertEquals("£", prefs.getString("pref_currency_key", null))
    }

    @Test
    fun aBlankSlideboardSlotStaysBlankAndDoesNotResetThePad() {
        val pad = List(20) { "k$it" }.toMutableList().apply { this[7] = "" }
        val json = JSONObject().put("format", "bbkb-layouts").put("version", 1)
            .put("slideboard", JSONArray(pad)).toString()
        LayoutsBundle.applyEditors(context, bundleOf(json))

        val loaded = SlideboardNumpadSpec.load(context)
        assertEquals("k0", loaded[0])
        assertEquals(EMPTY_SLOT, loaded[7])
        assertEquals("k19", loaded[19])
    }

    @Test
    fun sectionsTheFileLeavesOutAreLeftAlone() {
        seed("pref_pkb_symbol_page_layout", List(28) { "keep$it" })
        prefs.edit().putString("pref_currency_key", "¥").commit()
        val json = JSONObject().put("format", "bbkb-layouts").put("version", 1)
            .put("palette", JSONArray(listOf("✓"))).toString()

        assertEquals(1, LayoutsBundle.applyEditors(context, bundleOf(json)))
        assertEquals(List(28) { "keep$it" }, storedList("pref_pkb_symbol_page_layout"))
        assertEquals("¥", prefs.getString("pref_currency_key", null))
        assertEquals(listOf("✓"), CustomSymbolRepository(context).loadCustomSymbols())
    }

    @Test
    fun letterMapsTravelInTheBundle() {
        val repository = UserLetterMapRepository(context)
        repository.save(UserLetterMap.parse(letterMap))
        val json = export()
        assertEquals(1, JSONObject(json).getJSONArray("pkbLetterMaps").length())

        UserLetterMapRepository.directoryOf(context).deleteRecursively()
        val layouts = bundleOf(json)
        LayoutsBundle.saveLetterMaps(repository, layouts.pkbLetterMaps)
        val back = repository.get("swap")!!
        assertEquals("Swap", back.name)
        assertEquals('w'.code, back.key(android.view.KeyEvent.KEYCODE_Q, -1)!!.base)
        assertEquals("importing never switches a map on", "", repository.activeId)
    }

    // ── one import row, two kinds of file ────────────────────────────────────

    @Test
    fun aSingleLetterMapIsRecognisedByItsFormat() {
        val parsed = LayoutsBundle.parse(letterMap).getOrThrow()
        assertTrue(parsed is LayoutsBundle.Import.LetterMap)
        assertEquals("swap", (parsed as LayoutsBundle.Import.LetterMap).map.id)
    }

    @Test
    fun aBundleOfOnlyLetterMapsHasNoEditorSections() {
        val json = JSONObject().put("format", "bbkb-layouts").put("version", 1)
            .put("pkbLetterMaps", JSONArray().put(JSONObject(letterMap))).toString()
        val layouts = bundleOf(json)
        assertFalse(layouts.hasEditorSections)
        assertEquals(1, layouts.pkbLetterMaps.size)
        assertEquals(0, LayoutsBundle.applyEditors(context, layouts))
    }

    // ── refusals ─────────────────────────────────────────────────────────────

    private fun base() = JSONObject().put("format", "bbkb-layouts").put("version", 1)

    private fun assertRefused(json: String, vararg fragments: String) {
        val result = LayoutsBundle.parse(json)
        if (result.isSuccess) fail("expected the file to be refused: ${json.take(200)}")
        val e = result.exceptionOrNull()!!
        assertTrue(e is LayoutsBundle.InvalidLayoutsException)
        for (fragment in fragments) {
            assertTrue("message \"${e.message}\" should mention \"$fragment\"", e.message!!.contains(fragment))
        }
    }

    @Test
    fun refusesOtherFilesAndVersions() {
        assertRefused("not json at all", "not JSON")
        assertRefused(JSONObject().put("format", "bbkb-settings").put("version", 1).toString(), "bbkb-settings")
        assertRefused(base().put("version", 2).put("currency", "€").toString(), "version 2")
        assertRefused(base().toString(), "holds no layouts")
        assertRefused(base().put("currency", "€").put("vkbSymbolpage", JSONArray()).toString(), "vkbSymbolpage")
    }

    @Test
    fun refusesIncompleteOrOverfullLayouts() {
        assertRefused(base().put("vkbSymbolPage", JSONArray(List(25) { "x" })).toString(), "vkbSymbolPage", "25", "26")
        assertRefused(base().put("pkbSymbolPage", JSONArray(List(29) { "x" })).toString(), "pkbSymbolPage", "29", "27 or 28")
        assertRefused(base().put("pkbSymbolPage", JSONArray(List(26) { "x" })).toString(), "pkbSymbolPage", "26", "27 or 28")
        assertRefused(base().put("slideboard", JSONArray(List(19) { "x" })).toString(), "slideboard", "20")
        assertRefused(base().put("quickPhrases", JSONArray(List(4) { "x" })).toString(), "quickPhrases", "5")
        assertRefused(base().put("palette", JSONArray(List(LayoutsBundle.MAX_PALETTE + 1) { "x$it" })).toString(), "palette")
        assertRefused(base().put("palette", "★").toString(), "palette must be a list")
    }

    @Test
    fun refusesSymbolsTheStoresCannotHold() {
        val tooLong = "x".repeat(LayoutsBundle.MAX_SLOT_LENGTH + 1)
        assertRefused(base().put("pkbSymbolPage", JSONArray(List(28) { if (it == 4) tooLong else "x" })).toString(), "pkbSymbolPage[4]", "longer")
        // The stores' own separator would split one slot into two and shift every key after it.
        assertRefused(base().put("pkbSymbolPage", JSONArray(List(28) { if (it == 9) "a͸b" else "x" })).toString(), "pkbSymbolPage[9]", "U+0378")
        assertRefused(base().put("pkbSymbolPage", JSONArray(List(28) { if (it == 2) "\u0000" else "x" })).toString(), "pkbSymbolPage[2]", "control")
        assertRefused(base().put("pkbSymbolPage", JSONArray(List(28) { if (it == 0) 7 else "x" })).toString(), "pkbSymbolPage[0]", "string")
        assertRefused(base().put("palette", JSONArray(listOf("★", ""))).toString(), "palette[1]", "empty")
        assertRefused(base().put("quickPhrases", JSONArray(listOf("a", "b", "c", "d", "x".repeat(LayoutsBundle.MAX_PHRASE_LENGTH + 1)))).toString(), "quickPhrases[4]")
        assertRefused(base().put("quickPhrases", JSONArray(listOf("a", 5, "c", "d", "e"))).toString(), "quickPhrases[1]")
        assertRefused(base().put("currency", "EUR-EURO-€").toString(), "currency", "longer")
        assertRefused(base().put("currency", 1).toString(), "currency must be a string")
    }

    @Test
    fun refusesABadLetterMapInsideABundleAndSaysWhichOne() {
        val bad = JSONObject(letterMap).apply { getJSONObject("keys").put("KEYCODE_SPACE", JSONObject().put("base", "x")) }
        assertRefused(base().put("pkbLetterMaps", JSONArray().put(JSONObject(letterMap)).put(bad)).toString(), "pkbLetterMaps[1]", "KEYCODE_SPACE")
        assertRefused(base().put("pkbLetterMaps", JSONArray().put(JSONObject(letterMap)).put(JSONObject(letterMap))).toString(), "two layouts", "swap")
        assertRefused(base().put("pkbLetterMaps", JSONArray().put("swap")).toString(), "pkbLetterMaps[0]", "object")
        // And a single map with a problem is refused with the map's own reason.
        assertRefused(JSONObject(letterMap).put("kind", "vkb").toString(), "kind")
    }

    @Test
    fun refusesAnOversizedFileWithoutReadingAllOfIt() {
        val huge = base().put("currency", "€").put("app", "x".repeat(LayoutsBundle.MAX_BYTES)).toString()
        val read = LayoutsBundle.readCapped(huge.byteInputStream())
        assertEquals(LayoutsBundle.MAX_BYTES + 1, read.toByteArray(Charsets.UTF_8).size)
        assertRefused(read, "larger than")
        // A single letter map has the smaller cap.
        val bigMap = JSONObject(letterMap).put("name", "big").toString()
            .replace("\"big\"", "\"big\", \"pad\": \"${"x".repeat(UserLetterMap.MAX_BYTES)}\"")
        assertRefused(bigMap, "larger than")
    }

    @Test
    fun aRefusedFileWritesNothing() {
        seed("pref_pkb_symbol_page_layout", List(28) { "keep$it" })
        val json = base()
            .put("pkbSymbolPage", JSONArray(List(28) { "new$it" }))
            .put("slideboard", JSONArray(List(3) { "x" }))
            .toString()
        assertTrue(LayoutsBundle.parse(json).isFailure)
        assertEquals(List(28) { "keep$it" }, storedList("pref_pkb_symbol_page_layout"))
        assertNull(prefs.getString("custom_slideboard_symbols", null))
    }
}

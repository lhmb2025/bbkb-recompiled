package dev.bbkb.ime.core.layout

import android.content.Context
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.settings.PrefsManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.InputStream

/**
 * [LetterLayoutRegistry]: the user's files (save, list, delete, the active choice and the one-time
 * move of the preference it is kept in), and choosing a built-in for a keyboard from documents
 * handed in by the test, so the selection rules are checked apart from what the app ships
 * (`LetterLayoutAssetsParseTest` checks the shipped documents).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class LetterLayoutRegistryTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val prefs get() = PrefsManager.getPrefs(context)

    @Before
    fun clear() {
        prefs.edit().clear().commit()
        LetterLayoutRegistry.directoryOf(context).deleteRecursively()
        LetterLayoutRegistry.invalidate()
    }

    @After
    fun clearAgain() = clear()

    private fun layout(
        id: String,
        name: String = id,
        languages: String = "",
        keypad: String = "any",
        keypadType: String = "any",
        keys: String = """<key code="KEYCODE_Q" keySpec="w"/>""",
        origin: LetterLayout.Origin = LetterLayout.Origin.USER,
    ): LetterLayout = LetterLayoutXml.parse(document(id, name, languages, keypad, keypadType, keys), origin)

    private fun document(
        id: String,
        name: String = id,
        languages: String = "",
        keypad: String = "any",
        keypadType: String = "any",
        keys: String = """<key code="KEYCODE_Q" keySpec="w"/>""",
        layoutSet: String = "any",
    ) = """<letter-layout version="1" id="$id" name="$name">
            <bind languages="$languages" keypad="$keypad" keypadType="$keypadType" layoutSet="$layoutSet"/>
            $keys
        </letter-layout>"""

    /** Built-in documents from memory, counting how often each is opened. */
    private class Documents(private val byId: Map<String, String>) : LetterLayoutRegistry.BuiltInDocuments {
        val opened = HashMap<String, Int>()
        override fun ids(): List<String> = byId.keys.toList()
        override fun open(id: String): InputStream? {
            opened[id] = (opened[id] ?: 0) + 1
            return byId[id]?.byteInputStream()
        }
    }

    // ── the user's files ─────────────────────────────────────────────────────

    @Test
    fun savedLayoutsAreListedByNameAndReadBack() {
        val registry = LetterLayoutRegistry(context)
        assertTrue(registry.list().isEmpty())
        registry.save(layout("b-layout", name = "Beta", languages = "de"))
        registry.save(layout("a-layout", name = "alpha"))

        assertEquals(listOf("alpha", "Beta"), registry.list().map { it.name })
        val back = registry.get("b-layout")!!
        assertEquals(listOf("de"), back.binding.languages)
        assertEquals('w'.code, back.key(KeyEvent.KEYCODE_Q, -1)!!.keySpec)
        assertEquals(LetterLayout.Origin.USER, back.origin)
        assertTrue(registry.fileFor("b-layout").path.endsWith("files/layouts/letter/b-layout.xml"))
        assertTrue("written as the XML document", registry.fileFor("b-layout").readText().startsWith("<?xml"))
        assertFalse("no temp file is left behind", registry.directory.listFiles()!!.any { it.name.endsWith(".tmp") })
    }

    @Test
    fun savingUnderAnExistingIdReplacesIt() {
        val registry = LetterLayoutRegistry(context)
        registry.save(layout("one", name = "First"))
        registry.save(layout("one", name = "Second"))
        assertEquals(listOf("Second"), registry.list().map { it.name })
    }

    @Test
    fun theFileNameIsTheId() {
        val registry = LetterLayoutRegistry(context)
        registry.directory.mkdirs()
        // Copied in by hand under another name, and one with no id at all.
        registry.fileFor("renamed").writeText(document("original"))
        registry.fileFor("anonymous").writeText(document("x").replace(""" id="x"""", ""))
        assertEquals(listOf("anonymous", "renamed"), registry.list().map { it.id }.sorted())
        assertEquals("renamed", registry.get("renamed")!!.id)
    }

    @Test
    fun unreadableAndForeignFilesAreSkippedNotDeleted() {
        val registry = LetterLayoutRegistry(context)
        registry.save(layout("good"))
        registry.fileFor("broken").writeText("<letter-layout version=\"9\"/>")
        // The JSON letter maps of earlier builds lived elsewhere and are not read at all.
        java.io.File(context.filesDir, "layouts/pkb").apply { mkdirs() }.resolve("old.json").writeText("{}")
        java.io.File(registry.directory, "notes.txt").writeText("hello")

        assertEquals(listOf("good"), registry.list().map { it.id })
        assertNull(registry.get("broken"))
        assertTrue(registry.fileFor("broken").isFile)
        assertNull("an id that could leave the folder is never looked up", registry.get("../x"))
    }

    @Test
    fun deletingTheActiveLayoutSwitchesItOff() {
        val registry = LetterLayoutRegistry(context)
        registry.save(layout("one"))
        registry.save(layout("two"))
        registry.activeId = "one"
        assertEquals("one", prefs.getString(LetterLayoutRegistry.PREF_ACTIVE_LAYOUT, null))

        assertTrue(registry.delete("two"))
        assertEquals("deleting another layout leaves the choice alone", "one", registry.activeId)
        assertTrue(registry.delete("one"))
        assertEquals("", registry.activeId)
        assertFalse(registry.delete("one"))
        assertTrue(registry.list().isEmpty())
    }

    // ── the active layout for typing ─────────────────────────────────────────

    @Test
    fun theTypingCopyFollowsThePreferenceAndTheFile() {
        val registry = LetterLayoutRegistry(context)
        registry.save(layout("first", keys = """<key code="KEYCODE_Q" keySpec="ж"/>"""))
        assertNull(LetterLayoutRegistry.activeForTyping(context))

        registry.activeId = "first"
        val active = LetterLayoutRegistry.activeForTyping(context)!!
        assertEquals("first", active.id)
        assertSame("parsed once, then kept", active, LetterLayoutRegistry.activeForTyping(context))

        registry.save(layout("first", keys = """<key code="KEYCODE_Q" keySpec="з"/>"""))
        assertEquals('з'.code, LetterLayoutRegistry.activeForTyping(context)!!.key(KeyEvent.KEYCODE_Q, -1)!!.keySpec)

        prefs.edit().putString(LetterLayoutRegistry.PREF_ACTIVE_LAYOUT, "missing").commit()
        assertNull(LetterLayoutRegistry.activeForTyping(context))
    }

    @Test
    fun theOldPreferenceIsMovedOnce() {
        prefs.edit().putString(LetterLayoutRegistry.LEGACY_PREF_ACTIVE_LETTER_MAP, "kept").commit()
        LetterLayoutRegistry(context).save(layout("kept"))
        assertEquals("kept", LetterLayoutRegistry.activeForTyping(context)?.id)
        assertEquals("kept", prefs.getString(LetterLayoutRegistry.PREF_ACTIVE_LAYOUT, null))
        assertFalse(prefs.contains(LetterLayoutRegistry.LEGACY_PREF_ACTIVE_LETTER_MAP))

        // Once the new key exists, a stray old one is not copied over it.
        prefs.edit().putString(LetterLayoutRegistry.LEGACY_PREF_ACTIVE_LETTER_MAP, "stale").commit()
        assertEquals("kept", LetterLayoutRegistry(context).activeId)
    }

    @Test
    fun theOldPreferenceIsMovedByTheSettingsScreenToo() {
        prefs.edit().putString(LetterLayoutRegistry.LEGACY_PREF_ACTIVE_LETTER_MAP, "").commit()
        assertEquals("", LetterLayoutRegistry(context).activeId)
        assertTrue(prefs.contains(LetterLayoutRegistry.PREF_ACTIVE_LAYOUT))
        assertFalse(prefs.contains(LetterLayoutRegistry.LEGACY_PREF_ACTIVE_LETTER_MAP))
    }

    // ── built-ins ────────────────────────────────────────────────────────────

    private fun registryWith(vararg documents: Pair<String, String>) =
        Documents(documents.toMap()).let { it to LetterLayoutRegistry(context, it) }

    @Test
    fun aBuiltInIsReadOnceAndOnlyWhenAskedFor() {
        val (documents, registry) = registryWith("ru" to document("ru", languages = "ru"))
        assertTrue(documents.opened.isEmpty())
        val ru = registry.builtIn("ru")!!
        assertEquals(LetterLayout.Origin.BUILT_IN, ru.origin)
        assertSame(ru, registry.builtIn("ru"))
        assertEquals(1, documents.opened["ru"])
        assertNull(registry.builtIn("ar"))
        assertEquals(listOf("ru"), registry.builtInIds())
    }

    @Test
    fun aBrokenOrMisnamedBuiltInIsSkipped() {
        val (_, registry) = registryWith(
            "ar" to document("ar", languages = "ar").replace("""version="1"""", """version="2""""),
            "uk" to document("ukrainian", languages = "uk"),
            "be" to document("be", languages = "be").replace(""" id="be"""", ""),
        )
        assertNull(registry.builtIn("ar"))
        assertNull("the file name is the id", registry.builtIn("uk"))
        assertNull("a built-in must name itself", registry.builtIn("be"))
        assertNull(registry.builtIn("ar", "qwerty", "4row", "arabic"))
    }

    @Test
    fun theBuiltInForAKeyboardIsTheMostSpecificMatch() {
        val (_, registry) = registryWith(
            "de" to document("de", languages = "de"),
            "de-qwertz" to document("de-qwertz", languages = "de", keypad = "qwertz"),
            "de-3row" to document("de-3row", languages = "de", keypadType = "3row"),
            "de-qwertz-3row" to document("de-qwertz-3row", languages = "de", keypad = "qwertz", keypadType = "3row"),
            "colemak" to document("colemak"),
        )
        assertEquals("de", registry.builtIn("de", "qwerty", "4row", "qwertz")?.id)
        assertEquals("a named keypad beats any", "de-qwertz", registry.builtIn("de", "qwertz", "4row", "qwertz")?.id)
        assertEquals("de-3row", registry.builtIn("de", "qwerty", "3row", "qwertz")?.id)
        assertEquals("de-qwertz-3row", registry.builtIn("de", "QWERTZ", "3row", "qwertz")?.id)
        assertEquals("an unknown keypad picks no keypad's document", "de", registry.builtIn("de", null, null, null)?.id)
        assertNull("a layout bound to no language is chosen, never picked for a keyboard", registry.builtIn("en", "qwerty", "4row", "qwerty"))
        assertNull(registry.builtIn(null, "qwerty", "4row", "qwerty"))
    }

    @Test
    fun theLayoutSetTellsTwoKeyboardsOfOneLanguageApart() {
        val (_, registry) = registryWith(
            "ru" to document("ru", languages = "ru", layoutSet = "east_slavic"),
            "ru-translit" to document("ru-translit", languages = "ru", layoutSet = "translit"),
            "zh" to document("zh", languages = "zh", layoutSet = "pinyin"),
        )
        assertEquals("ru", registry.builtIn("ru", "qwerty", null, "east_slavic")?.id)
        assertEquals("ru-translit", registry.builtIn("ru", "qwerty", null, "translit")?.id)
        assertEquals("the resource name in any case", "ru-translit", registry.builtIn("ru", "qwerty", null, "Translit")?.id)
        assertNull("a layout set no document names", registry.builtIn("ru", "qwerty", null, "qwerty"))
        assertNull("an unknown layout set picks no layout set's document", registry.builtIn("ru", "qwerty", null, null))
        assertEquals("zh", registry.builtIn("zh", "qwerty", null, "pinyin")?.id)
        assertNull("the stroke keyboard is not the pinyin one", registry.builtIn("zh", "qwerty", null, "stroke"))
    }

    @Test
    fun aNamedLayoutSetRanksAfterTheKeypadAndBeforeFewerLanguages() {
        val (_, registry) = registryWith(
            "de" to document("de", languages = "de"),
            "de-swiss" to document("de-swiss", languages = "de", layoutSet = "swiss"),
            "de-swiss-group" to document("de-swiss-group", languages = "de,fr,it", layoutSet = "swiss"),
            "de-3row" to document("de-3row", languages = "de", keypadType = "3row"),
            "de-qwertz" to document("de-qwertz", languages = "de,fr", keypad = "qwertz"),
            "fr" to document("fr", languages = "fr"),
        )
        assertEquals("a named layout set beats any", "de-swiss", registry.builtIn("de", "qwerty", "4row", "swiss")?.id)
        assertEquals("another layout set: the any document", "de", registry.builtIn("de", "qwerty", "4row", "qwertz")?.id)
        assertEquals("a named keypad type beats a named layout set", "de-3row", registry.builtIn("de", "qwerty", "3row", "swiss")?.id)
        assertEquals("a named keypad beats them both", "de-qwertz", registry.builtIn("de", "qwertz", "3row", "swiss")?.id)
        assertEquals(
            "a named layout set beats a document bound to fewer languages",
            "de-swiss-group", registry.builtIn("fr", "qwerty", "4row", "swiss")?.id,
        )
        assertEquals("fr", registry.builtIn("fr", "qwerty", "4row", "azerty")?.id)
    }

    @Test
    fun aLanguagesOwnDocumentBeatsAGroupThatIncludesIt() {
        val (_, registry) = registryWith(
            "cyrillic-group" to document("cyrillic-group", languages = "kk,ky,ru,uk"),
            "kk" to document("kk", languages = "kk"),
            "ru" to document("ru", languages = "ru"),
            "be-uk" to document("be-uk", languages = "be,uk"),
            "uk" to document("uk", languages = "be,uk"),
        )
        assertEquals("kk", registry.builtIn("kk", "qwerty", null, "east_slavic")?.id)
        assertEquals("ky", "cyrillic-group", registry.builtIn("ky", "qwerty", null, "east_slavic")?.id)
        assertEquals("ru", registry.builtIn("ru", "qwerty", null, "east_slavic")?.id)
        assertEquals("equal bindings: the one named after the language", "uk", registry.builtIn("uk", "qwerty", null, "east_slavic")?.id)
        assertEquals("equal bindings, neither named after it: the lowest id", "be-uk", registry.builtIn("be", "qwerty", null, "east_slavic")?.id)
    }

    @Test
    fun hebrewFindsItsBuiltInWhicheverWayItIsSpelled() {
        val (_, registry) = registryWith("iw" to document("iw", languages = "iw", layoutSet = "hebrew"))
        assertEquals("iw", registry.builtIn("iw", "qwerty", null, "hebrew")?.id)
        assertEquals("iw", registry.builtIn("he", "qwerty", null, "hebrew")?.id)
    }
}

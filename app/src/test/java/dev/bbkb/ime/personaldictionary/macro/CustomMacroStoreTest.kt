package dev.bbkb.ime.personaldictionary.macro

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CHARACTERISATION TEST — the persisted form of a user's custom macros.
 *
 * `CustomMacrosScreen` had no coverage of any kind before Wave 3, and the thing most worth pinning
 * about it is not its layout but the bytes it leaves behind: a user who has defined `%p` expects it
 * to still expand after an upgrade. So this file records, against the current code, **where** the
 * macros live (the `custom_macros` preferences file, string key `macros`), **in what shape** (a JSON
 * array of objects with exactly `tag`, `name`, `value`, `createdAt`), and the ordering, update and
 * availability rules layered on top.
 *
 * Everything here is plain SharedPreferences and JSON — no Compose, no native dictionary — so these
 * cases run the production write path end to end rather than a stand-in for it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CustomMacroStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs().edit().clear().commit()
        CustomMacroRepository.invalidateCache()
    }

    /**
     * Robolectric shares one sandbox — and therefore one set of statics and one preferences
     * store — between test classes with the same `@Config`. `SettingsScreenRenderTest` renders
     * `CustomMacrosScreen` in the fresh-install state, so anything left behind here would show up
     * as rows it did not expect.
     */
    @After
    fun tearDown() {
        prefs().edit().clear().commit()
        CustomMacroRepository.invalidateCache()
    }

    private fun prefs() = context.getSharedPreferences("custom_macros", Context.MODE_PRIVATE)

    private fun repo() = CustomMacroRepository(context)

    /** The raw stored array, parsed but not interpreted. */
    private fun rawStore(): List<Map<String, String>> {
        val json = prefs().getString("macros", null) ?: return emptyList()
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            o.keys().asSequence().associateWith { o.get(it).toString() }
        }
    }

    private fun seedRaw(vararg macros: CustomMacro) {
        val array = JSONArray()
        macros.forEach {
            array.put(
                JSONObject()
                    .put("tag", it.tag).put("name", it.name)
                    .put("value", it.value).put("createdAt", it.createdAt)
            )
        }
        prefs().edit().putString("macros", array.toString()).commit()
        CustomMacroRepository.invalidateCache()
    }

    // ------------------------------------------------------- where and in what shape

    @Test
    fun macrosArePersistedInTheCustomMacrosPrefsFileUnderTheKeyMacros() {
        repo().saveMacro(CustomMacro("P", "Phone", "555", createdAt = 42L))

        assertEquals(
            "the store moved off custom_macros/macros — existing users would lose their macros",
            """[{"tag":"P","name":"Phone","value":"555","createdAt":42}]""",
            prefs().getString("macros", null)
        )
    }

    @Test
    fun aStoredMacroCarriesExactlyTagNameValueAndCreatedAt() {
        repo().saveMacro(CustomMacro("P", "Phone", "555", createdAt = 42L))

        assertEquals(
            setOf("tag", "name", "value", "createdAt"),
            rawStore().single().keys
        )
    }

    @Test
    fun anEmptyStoreReadsAsNoMacrosRatherThanFailing() {
        assertEquals(emptyList<CustomMacro>(), repo().getAllMacros())
    }

    @Test
    fun aCorruptStoreIsIgnoredRatherThanCrashingTheKeyboard() {
        prefs().edit().putString("macros", "not json at all").commit()
        CustomMacroRepository.invalidateCache()

        assertEquals(emptyList<CustomMacro>(), repo().getAllMacros())
    }

    @Test
    fun aStoredEntryWithNoCreatedAtStillLoads() {
        prefs().edit()
            .putString("macros", """[{"tag":"P","name":"Phone","value":"555"}]""").commit()
        CustomMacroRepository.invalidateCache()

        val loaded = repo().getAllMacros().single()
        assertEquals("P", loaded.tag)
        assertTrue("a missing createdAt is defaulted, not treated as corruption", loaded.createdAt > 0)
    }

    // ----------------------------------------------------------------- read and write

    @Test
    fun everyStoredMacroIsReadBackInStoredOrder() {
        seedRaw(
            CustomMacro("A", "Address", "1 Main Street", 1L),
            CustomMacro("P", "Phone", "555", 2L)
        )

        assertEquals(listOf("A", "P"), repo().getAllMacros().map { it.tag })
    }

    @Test
    fun aNewMacroIsAppendedAfterTheOnesAlreadyStored() {
        seedRaw(CustomMacro("A", "Address", "1 Main Street", 1L))

        repo().saveMacro(CustomMacro("P", "Phone", "555", 2L))

        assertEquals(listOf("A", "P"), rawStore().map { it["tag"] })
    }

    @Test
    fun savingAnExistingTagReplacesThatEntryInPlaceRatherThanAppending() {
        seedRaw(
            CustomMacro("A", "Address", "1 Main Street", 1L),
            CustomMacro("P", "Phone", "555", 2L)
        )

        repo().saveMacro(CustomMacro("A", "Home", "2 Other Street", 1L))

        assertEquals("no duplicate row", listOf("A", "P"), rawStore().map { it["tag"] })
        assertEquals("2 Other Street", rawStore().first()["value"])
        assertEquals("Home", rawStore().first()["name"])
    }

    @Test
    fun deletingAMacroRemovesOnlyThatEntry() {
        seedRaw(
            CustomMacro("A", "Address", "1 Main Street", 1L),
            CustomMacro("P", "Phone", "555", 2L)
        )

        repo().deleteMacro("A")

        assertEquals(listOf("P"), rawStore().map { it["tag"] })
    }

    @Test
    fun deletingATagThatIsNotThereLeavesTheStoreAlone() {
        seedRaw(CustomMacro("P", "Phone", "555", 2L))

        repo().deleteMacro("Z")

        assertEquals(listOf("P"), rawStore().map { it["tag"] })
    }

    @Test
    fun aMacroIsLookedUpByItsBareTagNotItsPercentForm() {
        seedRaw(CustomMacro("P", "Phone", "555", 2L))

        assertEquals("Phone", repo().getMacro("P")?.name)
        assertNull(repo().getMacro("%P"))
    }

    @Test
    fun theFullTagIsTheBareTagWithAPercentInFront() {
        assertEquals("%P", CustomMacro("P", "Phone", "555").fullTag)
    }

    @Test
    fun aWriteThroughOneInstanceIsVisibleFromAnother() {
        // The parsed list is memoised process-wide; a write must publish to every reader, or a
        // macro added in settings would not expand until the process restarted.
        repo().saveMacro(CustomMacro("P", "Phone", "555", 2L))

        assertEquals(listOf("P"), repo().getAllMacros().map { it.tag })
    }

    // ------------------------------------------------------------ availability rules

    @Test
    fun theBuiltInMacroTagsAreReserved() {
        assertEquals(
            "the reserved set is the built-in macro alphabet; changing it changes what users may define",
            setOf("D", "T", "d", "t", "n", "w", "y", "b"),
            CustomMacro.RESERVED_TAGS
        )
    }

    @Test
    fun aReservedTagIsNeverValid() {
        CustomMacro.RESERVED_TAGS.forEach {
            assertFalse("$it should be reserved", CustomMacro.isTagValid(it))
        }
    }

    @Test
    fun aTagMustBeExactlyOneLetterOrDigit() {
        assertTrue(CustomMacro.isTagValid("P"))
        assertTrue(CustomMacro.isTagValid("7"))
        assertFalse("empty", CustomMacro.isTagValid(""))
        assertFalse("two characters", CustomMacro.isTagValid("PQ"))
        assertFalse("punctuation", CustomMacro.isTagValid("!"))
        assertFalse("space", CustomMacro.isTagValid(" "))
    }

    @Test
    fun aTagAlreadyInUseIsNotAvailable() {
        seedRaw(CustomMacro("P", "Phone", "555", 2L))

        assertFalse(repo().isTagAvailable("P"))
        assertTrue(repo().isTagAvailable("Q"))
    }

    @Test
    fun whileEditingAMacroItsOwnTagStillCountsAsAvailable() {
        seedRaw(
            CustomMacro("P", "Phone", "555", 2L),
            CustomMacro("A", "Address", "1 Main Street", 1L)
        )

        assertTrue("keeping your own tag is not a clash", repo().isTagAvailableForEdit("P", "P"))
        assertFalse("another macro's tag still is", repo().isTagAvailableForEdit("A", "P"))
        assertTrue(repo().isTagAvailableForEdit("Q", "P"))
    }

    @Test
    fun aReservedTagIsUnavailableEvenWhenNoMacroUsesIt() {
        assertFalse(repo().isTagAvailable("D"))
        assertFalse(repo().isTagAvailableForEdit("D", "P"))
    }
}

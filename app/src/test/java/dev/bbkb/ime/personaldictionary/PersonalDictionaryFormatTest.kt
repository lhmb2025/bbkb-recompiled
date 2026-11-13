package dev.bbkb.ime.personaldictionary

import dev.bbkb.ime.personaldictionary.model.PersonalWord
import dev.bbkb.ime.personaldictionary.model.WordSubstitution
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The on-disk (and backup) format of the personal-dictionary files: files Gson wrote still load,
 * and new files keep the same fields so an older build restores a newer backup. Robolectric for
 * the real `org.json` and `android.util.JsonWriter` (empty stubs on the plain JVM).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PersonalDictionaryFormatTest {

    private fun ws(json: String) = PersonalDictionaryUtil.wordSubstitutionFromJson(JSONObject(json))
    private fun pw(json: String) = PersonalDictionaryUtil.personalWordFromJson(JSONObject(json))

    /** As a default Gson wrote it on the device: subclass fields first, each class's by name, HTML-escaped. */
    @Test
    fun aShortcutAsGsonWroteItLoads() {
        val loaded = ws(
            """{"key":"brb","type":"USER","version":16,"autoCapsEnabled":true,"locale":"en_US",""" +
                """"word":"be <right> back & = it's"}"""
        )
        assertEquals("brb", loaded.key)
        assertEquals("be <right> back & = it's", loaded.word)
        assertEquals("en_US", loaded.locale)
        assertEquals(WordSubstitution.Type.USER, loaded.type)
        assertTrue(loaded.isAutoCapsEnabled)
    }

    /** The JVM's field order, as the older test fixtures have it; order never mattered. */
    @Test
    fun fieldOrderDoesNotMatter() {
        val loaded = ws("""{"key":"zz","type":"USER","version":16,"word":"zz word","locale":"de","autoCapsEnabled":false}""")
        assertEquals("zz word", loaded.word)
        assertFalse(loaded.isAutoCapsEnabled)
    }

    @Test
    fun aPersonalWordAsGsonWroteItLoads() {
        val loaded = pw("""{"version":1,"autoCapsEnabled":false,"locale":"en_US","word":"don't="}""")
        assertEquals("don't=", loaded.word)
        assertEquals("en_US", loaded.locale)
        assertFalse(loaded.isAutoCapsEnabled)
    }

    /** A deleted built-in shortcut keeps its auto-caps flag and is not held to the user-shortcut rules. */
    @Test
    fun aDeletedDefaultKeepsItsFlagAndItsKey() {
        val loaded = ws("""{"key":"two words","type":"DEFAULT","version":16,"autoCapsEnabled":true,"locale":"all","word":"x"}""")
        assertEquals(WordSubstitution.Type.DEFAULT, loaded.type)
        assertEquals("two words", loaded.key)
        assertTrue(loaded.isAutoCapsEnabled)
    }

    /** Gson read a missing or null boolean as false and a missing version as nothing at all. */
    @Test
    fun missingOptionalFieldsReadAsGsonReadThem() {
        val loaded = ws("""{"key":"k","type":"USER","locale":"en","word":"w","autoCapsEnabled":null}""")
        assertFalse(loaded.isAutoCapsEnabled)
        assertEquals("the version is the class's own", 16L, loaded.version)
        assertEquals(1L, pw("""{"locale":"en","word":"w"}""").version)
    }

    @Test
    fun anEntryMissingARequiredFieldIsRefused() {
        val bad = listOf(
            """{"type":"USER","locale":"en","word":"w"}""",
            """{"key":"k","locale":"en","word":"w"}""",
            """{"key":"k","type":"SOMETHING","locale":"en","word":"w"}""",
            """{"key":"k","type":"USER","word":"w"}""",
            """{"key":"k","type":"USER","locale":"en"}""",
            """{"key":"k","type":"USER","locale":"en","word":null}""",
        )
        for (json in bad) {
            try {
                ws(json)
                fail("accepted $json")
            } catch (expected: JSONException) {
                // expected
            }
        }
        for (notAnObject in listOf<Any?>(JSONObject.NULL, "a string", 5, JSONArray())) {
            try {
                PersonalDictionaryUtil.personalWordFromJson(notAnObject)
                fail("accepted $notAnObject")
            } catch (expected: JSONException) {
                // expected
            }
        }
    }

    /** What the model itself refuses (an empty word or locale) is refused here too; the loader skips it. */
    @Test
    fun anEmptyWordIsRefusedByTheModel() {
        try {
            pw("""{"locale":"en","word":""}""")
            fail("accepted an empty word")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun theWrittenFormatHasTheSameFieldsInGsonsDeviceOrder() {
        val json = PersonalDictionaryUtil.dictionaryWordsToJson(
            listOf(
                WordSubstitution.createUserSubstitution("en_US", "brb", "be <right> back/now", true),
                WordSubstitution.createWithoutPreconditions("all", "two words", "x", WordSubstitution.Type.DEFAULT),
            )
        )
        assertEquals(
            """[{"key":"brb","type":"USER","version":16,"autoCapsEnabled":true,"locale":"en_US","word":"be <right> back/now"},""" +
                """{"key":"two words","type":"DEFAULT","version":16,"autoCapsEnabled":false,"locale":"all","word":"x"}]""",
            json
        )
        assertEquals(
            """[{"version":1,"autoCapsEnabled":false,"locale":"en_US","word":"politicus"}]""",
            PersonalDictionaryUtil.dictionaryWordsToJson(listOf(PersonalWord("politicus", "en_US", false)))
        )
        assertEquals("[]", PersonalDictionaryUtil.dictionaryWordsToJson(emptyList()))
    }

    @Test
    fun whatIsWrittenReadsBack() {
        val original = WordSubstitution.createUserSubstitution("fr", "stp", "s'il te plaît \"vite\"\n", false)
        val array = JSONArray(PersonalDictionaryUtil.dictionaryWordsToJson(listOf(original)))
        val back = PersonalDictionaryUtil.wordSubstitutionFromJson(array.get(0))
        assertEquals(original, back)
        assertEquals(original.word, back.word)
        assertEquals(original.type, back.type)
        assertEquals(original.isAutoCapsEnabled, back.isAutoCapsEnabled)
    }
}

package dev.bbkb.ime.core.keyevent

import android.view.KeyEvent
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The `bbkb-layout` letter-map document: what it reads, what it refuses (and that the refusal
 * names the key and the field), and what the map answers once read.
 *
 * Robolectric only for `org.json`; nothing here touches a keyboard.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class LetterMapLayoutFileTest {

    private val swapQw = """
        {
          "format": "bbkb-layout",
          "version": 1,
          "kind": "pkb",
          "id": "swap-q-w",
          "name": "Q and W swapped",
          "bind": { "locales": ["en", "de_CH"], "keypadLayout": "qwerty" },
          "keys": {
            "KEYCODE_Q": { "base": "w", "shift": "W", "alt": "#", "multitap": ["ŵ", "ẁ"], "moreKeys": ["ŵ", "ẃ", "ẅ"] },
            "KEYCODE_W": { "scanCode": 17, "base": "q" },
            "KEYCODE_4": { "base": "€", "alt": "😀" }
          }
        }
    """.trimIndent()

    private fun parse(json: String): UserLetterMap = UserLetterMap.parse(json) { "generated-id" }

    /** Asserts [json] is refused with a message containing every one of [fragments]. */
    private fun assertRefused(json: String, vararg fragments: String) {
        try {
            parse(json)
            fail("expected the layout to be refused: $json")
        } catch (e: InvalidLetterMapException) {
            for (fragment in fragments) {
                assertTrue("message \"${e.message}\" should mention \"$fragment\"", e.message!!.contains(fragment))
            }
        }
    }

    /** [swapQw] with one edit made to its parsed JSON. */
    private fun edited(edit: JSONObject.() -> Unit): String = JSONObject(swapQw).apply(edit).toString()

    private fun keys(json: JSONObject) = json.getJSONObject("keys")

    // ── reading ──────────────────────────────────────────────────────────────

    @Test
    fun readsEveryField() {
        val map = parse(swapQw)
        assertEquals("swap-q-w", map.id)
        assertEquals("Q and W swapped", map.name)
        assertEquals(listOf("en", "de_CH"), map.locales)
        assertEquals("qwerty", map.keypadLayout)
        assertEquals(3, map.keyCount)

        val q = map.key(KeyEvent.KEYCODE_Q, -1)!!
        assertEquals('w'.code, q.letter(false))
        assertEquals('W'.code, q.letter(true))
        assertEquals('#'.code, q.alt)
        assertArrayEquals(intArrayOf('ŵ'.code, 'ẁ'.code), q.multitap)
        assertArrayEquals(intArrayOf('ŵ'.code, 'ẃ'.code, 'ẅ'.code), q.moreKeys)

        // Shift defaults to the upper case of base.
        assertEquals('Q'.code, map.key(KeyEvent.KEYCODE_W, -1)!!.letter(true))
        // Characters outside the BMP are one character.
        assertEquals(0x1F600, map.key(KeyEvent.KEYCODE_4, -1)!!.alt)
    }

    @Test
    fun aScanCodeNarrowsTheKeyToOneSwitch() {
        val map = parse(swapQw)
        assertEquals('q'.code, map.key(KeyEvent.KEYCODE_W, 17)!!.base)
        assertNull("another switch sending KEYCODE_W is not this key", map.key(KeyEvent.KEYCODE_W, 18))
        // Without an event there is no scan code to compare, so the key code decides.
        assertEquals('q'.code, map.key(KeyEvent.KEYCODE_W, -1)!!.base)
        // A key without a scanCode matches every switch.
        assertEquals('w'.code, map.key(KeyEvent.KEYCODE_Q, 99)!!.base)
    }

    @Test
    fun aMissingIdIsGeneratedAndKeptFromThenOn() {
        val map = UserLetterMap.parse(edited { remove("id") }) { "layout-abc" }
        assertEquals("layout-abc", map.id)
        assertEquals("layout-abc", parse(map.serialize()).id)
    }

    @Test
    fun serializeReadsBackToTheSameMap() {
        val map = parse(swapQw)
        val again = parse(map.serialize())
        assertEquals(map.id, again.id)
        assertEquals(map.name, again.name)
        assertEquals(map.locales, again.locales)
        assertEquals(map.keypadLayout, again.keypadLayout)
        assertEquals(map.keys.keys.toList(), again.keys.keys.toList())
        map.keys.values.zip(again.keys.values).forEach { (a, b) ->
            assertEquals(a.scanCode, b.scanCode)
            assertEquals(a.base, b.base)
            assertEquals(a.shift, b.shift)
            assertEquals(a.alt, b.alt)
            assertArrayEquals(a.multitap, b.multitap)
            assertArrayEquals(a.moreKeys, b.moreKeys)
        }
        // Optional fields that were not set stay out of the file.
        val w = JSONObject(map.serialize()).getJSONObject("keys").getJSONObject("KEYCODE_W")
        assertFalse(w.has("shift"))
        assertFalse(w.has("alt"))
        assertFalse(w.has("multitap"))
    }

    // ── binding ──────────────────────────────────────────────────────────────

    @Test
    fun appliesToItsLanguagesOnItsKeypad() {
        val map = parse(swapQw)
        assertTrue(map.appliesTo("en", "qwerty"))
        assertTrue("de_CH binds the German language", map.appliesTo("de", "qwerty"))
        assertFalse(map.appliesTo("fr", "qwerty"))
        assertFalse("written for QWERTY", map.appliesTo("en", "qwertz"))
        assertTrue("an unknown keypad is not held against it", map.appliesTo("en", null))
        assertFalse("no keyboard, no language to match", map.appliesTo(null, "qwerty"))
    }

    @Test
    fun anUnboundMapAppliesToEveryKeyboard() {
        val map = parse(edited { getJSONObject("bind").put("locales", org.json.JSONArray()) })
        assertTrue(map.appliesTo("en", "qwerty"))
        assertTrue(map.appliesTo("ar", "qwerty"))
        assertTrue(map.appliesTo(null, "qwerty"))
    }

    @Test
    fun hebrewMatchesWhicheverCodeTheLocaleSpells() {
        // Java 17+ and Android 15 say "he"; the app's tables (and the current keyboard) say "iw".
        val map = parse(edited { getJSONObject("bind").put("locales", org.json.JSONArray(listOf("he"))) })
        assertTrue(map.appliesTo("iw", "qwerty"))
    }

    // ── sequences and the swipe question ─────────────────────────────────────

    @Test
    fun multitapAndMoreKeysAreFoundByTheLetterTheKeyTyped() {
        val map = parse(swapQw)
        assertArrayEquals(arrayOf("ŵ", "ẁ"), map.multitapFor("w"))
        assertArrayEquals(arrayOf("ŵ", "ẃ", "ẅ"), map.moreKeysFor("w"))
        // The Shift letter finds the key too, and gets upper-case alternates.
        assertArrayEquals(arrayOf("Ŵ", "Ẁ"), map.multitapFor("W"))
        // A key with no sequence, and a letter no key types, both say "nothing here".
        assertNull(map.multitapFor("q"))
        assertNull(map.multitapFor("z"))
        assertNull(map.moreKeysFor(null))
    }

    @Test
    fun changesLettersOnlyWhenALetterKeyTypesSomethingElse() {
        val latin = { keyCode: Int -> 'a'.code + (keyCode - KeyEvent.KEYCODE_A) }
        assertTrue(parse(swapQw).changesLetters(latin))

        // Alt, multitap and long-press additions on a key that still types its own letter do not.
        val additionsOnly = parse(edited {
            put("keys", JSONObject("""{ "KEYCODE_Q": { "base": "q", "alt": "#", "moreKeys": ["ʠ"] }, "KEYCODE_4": { "base": "€" } }"""))
        })
        assertFalse(additionsOnly.changesLetters(latin))
    }

    // ── refusals ─────────────────────────────────────────────────────────────

    @Test
    fun refusesSomebodyElsesJson() {
        assertRefused("[1, 2]", "not JSON")
        assertRefused("""{"format": "bbkb-settings", "version": 1}""", "format", "bbkb-layout")
        assertRefused(edited { put("version", 2) }, "version 2")
        assertRefused(edited { remove("version") }, "version")
        assertRefused(edited { put("kind", "vkb") }, "kind", "pkb")
    }

    @Test
    fun refusesUnknownKeyNames() {
        // The letter O where the digit 0 was meant: the kind of typo that must not pass silently.
        assertRefused(edited { keys(this).put("KEYCODE_ZERO", JSONObject("""{"base": "x"}""")) }, "KEYCODE_ZERO")
        assertRefused(edited { keys(this).put("Q", JSONObject("""{"base": "x"}""")) }, "Q is not a key")
        // Function keys are not letters.
        assertRefused(edited { keys(this).put("KEYCODE_SPACE", JSONObject("""{"base": "x"}""")) }, "KEYCODE_SPACE")
        assertRefused(edited { keys(this).put("KEYCODE_ENTER", JSONObject("""{"base": "x"}""")) }, "KEYCODE_ENTER")
    }

    @Test
    fun refusesAnythingButOneCharacterPerLegend() {
        assertRefused(edited { keys(this).getJSONObject("KEYCODE_W").put("base", "qu") }, "KEYCODE_W.base", "single character")
        assertRefused(edited { keys(this).getJSONObject("KEYCODE_W").put("base", "") }, "KEYCODE_W.base", "empty")
        assertRefused(edited { keys(this).getJSONObject("KEYCODE_W").remove("base") }, "KEYCODE_W.base", "missing")
        assertRefused(edited { keys(this).getJSONObject("KEYCODE_W").put("shift", "\n") }, "KEYCODE_W.shift", "control")
        assertRefused(edited { keys(this).getJSONObject("KEYCODE_W").put("alt", 5) }, "KEYCODE_W.alt", "string")
        // A flag is two code points.
        assertRefused(edited { keys(this).getJSONObject("KEYCODE_W").put("alt", "🇩🇪") }, "KEYCODE_W.alt")
        assertRefused(edited { keys(this).getJSONObject("KEYCODE_Q").put("multitap", org.json.JSONArray(listOf("ŵ", "ab"))) }, "KEYCODE_Q.multitap[1]")
        assertRefused(edited { keys(this).getJSONObject("KEYCODE_Q").put("moreKeys", "ŵ") }, "KEYCODE_Q.moreKeys", "list")
        assertRefused(edited { keys(this).getJSONObject("KEYCODE_W").put("scanCode", "17") }, "KEYCODE_W.scanCode")
        assertRefused(edited { keys(this).getJSONObject("KEYCODE_W").put("scanCode", 0) }, "KEYCODE_W.scanCode", "range")
    }

    @Test
    fun refusesUnknownFieldsRatherThanIgnoringATypo() {
        assertRefused(edited { keys(this).getJSONObject("KEYCODE_W").put("shfit", "Q") }, "KEYCODE_W", "shfit")
        assertRefused(edited { getJSONObject("bind").put("locale", "en") }, "bind", "locale")
        assertRefused(edited { put("author", "me") }, "author")
    }

    @Test
    fun refusesABadBindingOrHeader() {
        assertRefused(edited { getJSONObject("bind").put("keypadLayout", "dvorak") }, "keypadLayout", "dvorak")
        assertRefused(edited { getJSONObject("bind").remove("keypadLayout") }, "keypadLayout")
        assertRefused(edited { getJSONObject("bind").remove("locales") }, "bind.locales")
        assertRefused(edited { getJSONObject("bind").put("locales", org.json.JSONArray(listOf("english"))) }, "bind.locales[0]")
        assertRefused(edited { remove("bind") }, "bind")
        assertRefused(edited { put("name", "  ") }, "name is empty")
        assertRefused(edited { remove("name") }, "name")
        // An id becomes a file name: nothing that could leave the layouts directory.
        assertRefused(edited { put("id", "../../shared_prefs/x") }, "id")
        assertRefused(edited { put("id", "a/b") }, "id")
    }

    @Test
    fun refusesTooManyKeysAndTooBigAFile() {
        assertRefused(edited { put("keys", JSONObject()) }, "keys is empty")

        // Every key a map may name, at once, is within the cap...
        val everyKey = JSONObject()
        val names = ('A'..'Z').map { "KEYCODE_$it" } + (0..9).map { "KEYCODE_$it" } +
            listOf("COMMA", "PERIOD", "GRAVE", "MINUS", "EQUALS", "LEFT_BRACKET", "RIGHT_BRACKET",
                "BACKSLASH", "SEMICOLON", "APOSTROPHE", "SLASH", "AT", "POUND", "STAR", "PLUS").map { "KEYCODE_$it" }
        names.forEach { everyKey.put(it, JSONObject("""{"base": "x"}""")) }
        assertEquals(names.size, parse(edited { put("keys", everyKey) }).keyCount)

        // ...and a file past it is refused by count before any name is looked at.
        val tooMany = JSONObject()
        repeat(UserLetterMap.MAX_KEYS + 1) { tooMany.put("KEY_$it", JSONObject("""{"base": "x"}""")) }
        assertRefused(edited { put("keys", tooMany) }, "at most ${UserLetterMap.MAX_KEYS}")

        val padding = "x".repeat(UserLetterMap.MAX_BYTES)
        assertRefused(edited { put("name", "big") }.replace("\"big\"", "\"big\", \"pad\": \"$padding\""), "larger than")
    }
}

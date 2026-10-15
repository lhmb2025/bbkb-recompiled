package dev.bbkb.ime.core.layout

import android.view.KeyEvent
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
 * The `letter-layout` document: what it reads, what it refuses (and that the refusal names the
 * key and the field), what the layout answers once read, and that what [LetterLayoutXml.serialize]
 * writes reads back to the same layout.
 *
 * Robolectric for the platform's pull parser; nothing here touches a keyboard.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class LetterLayoutXmlTest {

    private val swapQw = """
        <?xml version="1.0" encoding="utf-8"?>
        <!-- A comment is fine anywhere. -->
        <letter-layout version="1" id="swap-q-w" name="Q and W swapped">
            <bind languages="en,de_CH" keypad="qwerty"/>
            <key code="KEYCODE_Q" keySpec="w" characterMapKeys="W" alt="#" multitapKeys="ŵ,ẁ" moreKeys="ŵ,ẃ,ẅ"/>
            <key code="KEYCODE_W" scanCode="17" keySpec="q"/>
            <key code="KEYCODE_4" keySpec="€" alt="😀"/>
        </letter-layout>
    """.trimIndent()

    private fun parse(xml: String): LetterLayout = LetterLayoutXml.parse(xml) { "generated-id" }

    /** The string of [codePoints]. */
    private fun text(vararg codePoints: Int): String = String(codePoints, 0, codePoints.size)

    /** Asserts [xml] is refused with a message containing every one of [fragments]. */
    private fun assertRefused(xml: String, vararg fragments: String) {
        try {
            parse(xml)
            fail("expected the layout to be refused: $xml")
        } catch (e: InvalidLetterLayoutException) {
            for (fragment in fragments) {
                assertTrue("message \"${e.message}\" should mention \"$fragment\"", e.message!!.contains(fragment))
            }
        }
    }

    /** [swapQw] with [old] replaced by [new], which must be there to replace. */
    private fun edited(old: String, new: String): String {
        assertTrue("\"$old\" is not in the document", swapQw.contains(old))
        return swapQw.replace(old, new)
    }

    /** A document around [keys], bound to every keyboard. */
    private fun withKeys(keys: String) = """
        <letter-layout version="1" id="t" name="t">
            <bind/>
            $keys
        </letter-layout>
    """.trimIndent()

    // ── reading ──────────────────────────────────────────────────────────────

    @Test
    fun readsEveryField() {
        val layout = parse(swapQw)
        assertEquals("swap-q-w", layout.id)
        assertEquals("Q and W swapped", layout.name)
        assertEquals(LetterLayout.Origin.USER, layout.origin)
        assertEquals(setOf(LetterLayout.Target.PKB), layout.targets)
        assertEquals(listOf("en", "de_CH"), layout.binding.languages)
        assertEquals("qwerty", layout.binding.keypad)
        assertEquals("any", layout.binding.keypadType)
        assertEquals("absent means any layout set", "any", layout.binding.layoutSet)
        assertNull(layout.rowsTemplate)
        assertEquals(3, layout.keyCount)
        assertEquals(listOf(KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_4), layout.keys.keys.toList())

        val q = layout.key(KeyEvent.KEYCODE_Q, -1)!!
        assertEquals('w'.code, q.letter(false))
        assertEquals('W'.code, q.letter(true))
        assertEquals('#'.code, q.alt)
        assertArrayEquals(arrayOf("ŵ", "ẁ"), q.multitap)
        assertArrayEquals(arrayOf("ŵ", "ẃ", "ẅ"), q.moreKeys)

        // Shift defaults to the upper case of keySpec.
        assertEquals('Q'.code, layout.key(KeyEvent.KEYCODE_W, -1)!!.letter(true))
        // Characters outside the BMP are one character.
        assertEquals(0x1F600, layout.key(KeyEvent.KEYCODE_4, -1)!!.alt)
    }

    @Test
    fun readsTheOnScreenParts() {
        val layout = parse("""
            <letter-layout version="1" id="colemak" name="Colemak" targets="pkb,vkb">
                <bind languages="" keypad="any" keypadType="any"/>
                <rows template="qwerty"/>
                <key code="KEYCODE_E" keySpec="f"/>
                <key code="KEYCODE_SEMICOLON" scanCode="39" keySpec="o"/>
            </letter-layout>
        """.trimIndent())
        assertEquals(setOf(LetterLayout.Target.PKB, LetterLayout.Target.VKB), layout.targets)
        assertEquals("qwerty", layout.rowsTemplate)
        assertTrue(layout.binding.languages.isEmpty())
        assertEquals('o'.code, layout.key(KeyEvent.KEYCODE_SEMICOLON, 39)!!.keySpec)
    }

    @Test
    fun anyCodePointIsAKeySpecAndXmlEscapesWork() {
        // Dvorak puts punctuation on letter positions.
        val layout = parse(withKeys("""
            <key code="KEYCODE_Q" keySpec="'"/>
            <key code="KEYCODE_W" keySpec="," characterMapKeys="&lt;"/>
            <key code="KEYCODE_E" keySpec="&amp;" characterMapKeys="&quot;" alt="&#x1F600;"/>
        """))
        assertEquals('\''.code, layout.key(KeyEvent.KEYCODE_Q, -1)!!.keySpec)
        assertEquals(','.code, layout.key(KeyEvent.KEYCODE_W, -1)!!.keySpec)
        assertEquals('<'.code, layout.key(KeyEvent.KEYCODE_W, -1)!!.shift)
        assertEquals('&'.code, layout.key(KeyEvent.KEYCODE_E, -1)!!.keySpec)
        assertEquals('"'.code, layout.key(KeyEvent.KEYCODE_E, -1)!!.shift)
        assertEquals(0x1F600, layout.key(KeyEvent.KEYCODE_E, -1)!!.alt)
    }

    @Test
    fun aListEscapesItsCommaAndBackslash() {
        val layout = parse(withKeys("""<key code="KEYCODE_Q" keySpec="q" moreKeys="\,,\\,x" multitapKeys="\,"/>"""))
        val q = layout.key(KeyEvent.KEYCODE_Q, -1)!!
        assertArrayEquals(arrayOf(",", "\\", "x"), q.moreKeys)
        assertArrayEquals(arrayOf(","), q.multitap)
    }

    @Test
    fun listEntriesAreStringsOfUpToEightCharacters() {
        // The physical-key tables' ligatures: Arabic lam-alef and heh with a joiner, Hebrew letters
        // with geresh (and one with a vowel point), Catalan l·l, Uzbek oʻ; escapes work inside
        // an entry, and a character outside the BMP counts once.
        val layout = parse(withKeys("""
            <key code="KEYCODE_F" keySpec="ل" moreKeys="لا,لأ,ه&#x200D;"/>
            <key code="KEYCODE_D" keySpec="ג" moreKeys="ג׳,&#x5F2;&#x5B7;"/>
            <key code="KEYCODE_L" keySpec="l" moreKeys="l·l,ł" multitapKeys="oʻ,a\,b,\\\\"/>
            <key code="KEYCODE_Q" keySpec="q" moreKeys="😀😀😀😀😀😀😀😀, "/>
        """))
        // Spelled as code points where the characters are invisible or combine in the source.
        assertArrayEquals(
            arrayOf(text(0x644, 0x627), text(0x644, 0x623), text(0x647, 0x200D)),
            layout.key(KeyEvent.KEYCODE_F, -1)!!.moreKeys,
        )
        assertArrayEquals(arrayOf(text(0x5D2, 0x5F3), text(0x5F2, 0x5B7)), layout.key(KeyEvent.KEYCODE_D, -1)!!.moreKeys)
        val l = layout.key(KeyEvent.KEYCODE_L, -1)!!
        assertArrayEquals(arrayOf("l·l", "ł"), l.moreKeys)
        assertArrayEquals(arrayOf("oʻ", "a,b", "\\\\"), l.multitap)
        // Eight emoji are sixteen chars but eight characters; a lone space is a character.
        assertArrayEquals(arrayOf("😀".repeat(8), " "), layout.key(KeyEvent.KEYCODE_Q, -1)!!.moreKeys)

        // Lists hold up to 32 entries.
        val thirtyTwo = List(32) { "x$it" }
        assertArrayEquals(
            thirtyTwo.toTypedArray(),
            parse(withKeys("""<key code="KEYCODE_Q" keySpec="q" moreKeys="${thirtyTwo.joinToString(",")}"/>""")).key(KeyEvent.KEYCODE_Q, -1)!!.moreKeys,
        )
    }

    @Test
    fun aKeyIsFoundByTheLetterItTypesWithItsStringEntries() {
        // Upper-case lists for the Shift letter are the resolver's (PhysicalKeyResolverTest).
        val layout = parse(withKeys("""<key code="KEYCODE_L" keySpec="l" moreKeys="l·l,ł" multitapKeys="lj"/>"""))
        val l = layout.keyForLetter('l'.code)!!
        assertArrayEquals(arrayOf("l·l", "ł"), l.moreKeys)
        assertArrayEquals(arrayOf("lj"), l.multitap)
        assertNull("the Shift letter is not a letter a key types", layout.keyForLetter('L'.code))
    }

    @Test
    fun aDocumentMayOpenWithACommentAndNoDeclaration() {
        // The generator's form: the GENERATED comment first, which a declaration could not follow.
        val commentFirst = """
            <!-- GENERATED by xt9kdb-scaffold/tools/letter_layouts_gen.py — do not edit -->
            <!-- Sources: a second comment, with non-ASCII text: ل ж ŵ. -->
            <letter-layout version="1" id="ru" name="Russian" targets="pkb">
                <bind languages="ru" keypad="any" keypadType="any" layoutSet="east_slavic"/>
                <key code="KEYCODE_Q" keySpec="й" multitapKeys="ё"/>
            </letter-layout>
        """.trimIndent()
        val fromText = parse(commentFirst)
        assertEquals("ru", fromText.id)
        assertEquals('й'.code, fromText.key(KeyEvent.KEYCODE_Q, -1)!!.keySpec)

        // From bytes, with no declaration to name the encoding: UTF-8, XML's default.
        val fromBytes = LetterLayoutXml.parse(commentFirst.toByteArray(Charsets.UTF_8).inputStream(), LetterLayout.Origin.BUILT_IN)
        assertEquals("east_slavic", fromBytes.binding.layoutSet)
        assertArrayEquals(arrayOf("ё"), fromBytes.key(KeyEvent.KEYCODE_Q, -1)!!.multitap)
        // With a byte-order mark too.
        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + commentFirst.toByteArray(Charsets.UTF_8)
        assertEquals("ru", LetterLayoutXml.parse(withBom.inputStream()).id)

        // The form without a declaration is no way round the refusals.
        assertRefused(
            commentFirst.replace("<letter-layout ", """<!DOCTYPE letter-layout [ <!ENTITY a "a"> ]><letter-layout """),
            "DOCTYPE",
        )
        assertRefused(commentFirst.replace("""targets="pkb"""", """targets="pkb" xmlns:x="urn:x""""), "xmlns:x")
    }

    @Test
    fun aScanCodeNarrowsTheKeyToOneSwitch() {
        val layout = parse(swapQw)
        assertEquals('q'.code, layout.key(KeyEvent.KEYCODE_W, 17)!!.keySpec)
        assertNull("another switch sending KEYCODE_W is not this key", layout.key(KeyEvent.KEYCODE_W, 18))
        // Without an event there is no scan code to compare, so the key code decides.
        assertEquals('q'.code, layout.key(KeyEvent.KEYCODE_W, -1)!!.keySpec)
        // A key without a scanCode matches every switch.
        assertEquals('w'.code, layout.key(KeyEvent.KEYCODE_Q, 99)!!.keySpec)
        // Zero is a scan code like any other.
        assertEquals(0, parse(withKeys("""<key code="KEYCODE_Q" scanCode="0" keySpec="q"/>""")).key(KeyEvent.KEYCODE_Q, 0)!!.scanCode)
    }

    @Test
    fun aMissingIdIsGeneratedAndKeptFromThenOn() {
        val layout = LetterLayoutXml.parse(edited(""" id="swap-q-w"""", "")) { "layout-abc" }
        assertEquals("layout-abc", layout.id)
        assertEquals("layout-abc", parse(LetterLayoutXml.serialize(layout)).id)
    }

    @Test
    fun aBuiltInMustNameItself() {
        try {
            LetterLayoutXml.parse(edited(""" id="swap-q-w"""", ""), LetterLayout.Origin.BUILT_IN)
            fail("a built-in without an id should be refused")
        } catch (e: InvalidLetterLayoutException) {
            assertTrue(e.message!!.contains("no id"))
        }
        assertEquals(LetterLayout.Origin.BUILT_IN, LetterLayoutXml.parse(swapQw, LetterLayout.Origin.BUILT_IN).origin)
    }

    @Test
    fun readsFromAStreamWithAByteOrderMark() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + swapQw.toByteArray(Charsets.UTF_8)
        assertEquals("swap-q-w", LetterLayoutXml.parse(bytes.inputStream()).id)
        assertEquals("swap-q-w", LetterLayoutXml.parse("\uFEFF" + swapQw).id)
    }

    @Test
    fun serializeReadsBackToTheSameLayout() {
        val layout = parse(swapQw)
        val written = LetterLayoutXml.serialize(layout)
        val again = parse(written)
        assertEquals(layout.id, again.id)
        assertEquals(layout.name, again.name)
        assertEquals(layout.targets, again.targets)
        assertEquals(layout.binding.languages, again.binding.languages)
        assertEquals(layout.binding.keypad, again.binding.keypad)
        assertEquals(layout.binding.keypadType, again.binding.keypadType)
        assertEquals(layout.binding.layoutSet, again.binding.layoutSet)
        assertEquals(layout.rowsTemplate, again.rowsTemplate)
        assertEquals(layout.keys.keys.toList(), again.keys.keys.toList())
        layout.keys.values.zip(again.keys.values).forEach { (a, b) ->
            assertEquals(a.scanCode, b.scanCode)
            assertEquals(a.keySpec, b.keySpec)
            assertEquals(a.shift, b.shift)
            assertEquals(a.alt, b.alt)
            assertArrayEquals(a.multitap, b.multitap)
            assertArrayEquals(a.moreKeys, b.moreKeys)
        }
        // Optional attributes that were not set stay out of the file.
        val w = written.lines().first { it.contains("KEYCODE_W") }
        assertEquals("""    <key code="KEYCODE_W" scanCode="17" keySpec="q"/>""", w)
        assertFalse("pkb is the default target", written.contains("targets="))
        assertFalse("any is the default keypad type", written.contains("keypadType="))
        assertFalse("any is the default layout set", written.contains("layoutSet="))
        assertTrue(written.startsWith("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<letter-layout version=\"1\" id=\"swap-q-w\""))
    }

    @Test
    fun serializeKeepsTheLayoutSetAndMultiCharacterEntries() {
        val layout = parse(withKeys("""<key code="KEYCODE_L" keySpec="l" moreKeys="l·l,a\,b,ł" multitapKeys="oʻ"/>""")
            .replace("<bind/>", """<bind languages="ca" layoutSet="Spanish"/>"""))
        assertEquals("stored in lower case, as the resources spell it", "spanish", layout.binding.layoutSet)
        val written = LetterLayoutXml.serialize(layout)
        assertTrue(written, written.contains("""<bind languages="ca" layoutSet="spanish"/>"""))
        assertTrue(written, written.contains("""multitapKeys="oʻ" moreKeys="l·l,a\,b,ł""""))
        val again = parse(written)
        assertEquals("spanish", again.binding.layoutSet)
        assertArrayEquals(arrayOf("l·l", "a,b", "ł"), again.key(KeyEvent.KEYCODE_L, -1)!!.moreKeys)
        assertArrayEquals(arrayOf("oʻ"), again.key(KeyEvent.KEYCODE_L, -1)!!.multitap)
    }

    @Test
    fun serializeEscapesWhatNeedsIt() {
        val layout = parse("""
            <letter-layout version="1" id="esc" name="A &lt;&amp;&gt; &quot;B&quot;" targets="vkb,pkb">
                <bind keypadType="3row"/>
                <rows template="qwerty"/>
                <key code="KEYCODE_Q" keySpec="&quot;" characterMapKeys="&amp;" moreKeys="\,,\\,&lt;" multitapKeys="&#x1F600;"/>
            </letter-layout>
        """.trimIndent())
        val written = LetterLayoutXml.serialize(layout)
        assertTrue(written, written.contains("""name="A &lt;&amp;&gt; &quot;B&quot;""""))
        assertTrue(written, written.contains("""targets="pkb,vkb""""))
        assertTrue(written, written.contains("""<bind keypadType="3row"/>"""))
        assertTrue(written, written.contains("""<rows template="qwerty"/>"""))
        assertTrue(written, written.contains("""moreKeys="\,,\\,&lt;""""))
        val again = parse(written)
        assertEquals("A <&> \"B\"", again.name)
        val q = again.key(KeyEvent.KEYCODE_Q, -1)!!
        assertEquals('"'.code, q.keySpec)
        assertEquals('&'.code, q.shift)
        assertArrayEquals(arrayOf(",", "\\", "<"), q.moreKeys)
        assertArrayEquals(arrayOf(String(Character.toChars(0x1F600))), q.multitap)
        assertEquals("3row", again.binding.keypadType)
    }

    // ── binding ──────────────────────────────────────────────────────────────

    @Test
    fun appliesToItsLanguagesOnItsKeypad() {
        val layout = parse(swapQw)
        assertTrue(layout.appliesTo("en", "qwerty", null, null))
        assertTrue("de_CH binds the German language", layout.appliesTo("de", "qwerty", null, null))
        assertFalse(layout.appliesTo("fr", "qwerty", null, null))
        assertFalse("written for QWERTY", layout.appliesTo("en", "qwertz", null, null))
        assertTrue("an unknown keypad is not held against it", layout.appliesTo("en", null, null, null))
        assertFalse("no keyboard, no language to match", layout.appliesTo(null, "qwerty", null, null))
        assertTrue("bound to no layout set, so any", layout.appliesTo("en", "qwerty", null, "translit"))
    }

    @Test
    fun anUnboundLayoutAppliesToEveryKeyboard() {
        val layout = parse(edited("""<bind languages="en,de_CH" keypad="qwerty"/>""", """<bind languages="" keypad="any"/>"""))
        assertTrue(layout.appliesTo("en", "qwerty", null, null))
        assertTrue(layout.appliesTo("ar", "azerty", "4row", "arabic"))
        assertTrue(layout.appliesTo(null, "qwerty", null, null))
        assertTrue("absent attributes mean the same", parse(withKeys("")).appliesTo("ko", "qwertz", "3row", "hangul"))
    }

    @Test
    fun aKeypadTypeBindingMatchesOnlyThreeRowKeypads() {
        val layout = parse(edited("""keypad="qwerty"/>""", """keypadType="3row"/>"""))
        assertTrue(layout.appliesTo("en", "qwerty", "3row", null))
        assertTrue("the keyboard XML's other spelling", layout.appliesTo("en", "qwerty", "3", null))
        assertFalse(layout.appliesTo("en", "qwerty", "4row", null))
        assertTrue("an unknown keypad type is not held against it", layout.appliesTo("en", "qwerty", null, null))
    }

    @Test
    fun aLayoutSetBindingMatchesOnlyThatLayoutSet() {
        // Russian and Russian (translit) are both "ru"; only the layout set tells them apart.
        val translit = parse(edited("""<bind languages="en,de_CH" keypad="qwerty"/>""", """<bind languages="ru" layoutSet="translit"/>"""))
        assertEquals("translit", translit.binding.layoutSet)
        assertTrue(translit.appliesTo("ru", "qwerty", null, "translit"))
        assertTrue("the resource name in any case", translit.appliesTo("ru", "qwerty", null, "TRANSLIT"))
        assertFalse(translit.appliesTo("ru", "qwerty", null, "east_slavic"))
        assertFalse("the language still has to match", translit.appliesTo("uk", "qwerty", null, "translit"))
        assertTrue("an unknown layout set is not held against it", translit.appliesTo("ru", "qwerty", null, null))
        assertTrue(translit.binding.layoutSetMatches("translit"))
        assertFalse(translit.binding.layoutSetMatches(null))

        val any = parse(edited("""<bind languages="en,de_CH" keypad="qwerty"/>""", """<bind languages="ru" layoutSet="ANY"/>"""))
        assertEquals("any", any.binding.layoutSet)
        assertTrue(any.appliesTo("ru", "qwerty", null, "east_slavic"))
    }

    @Test
    fun hebrewMatchesWhicheverCodeTheLanguageIsSpelledWith() {
        // Java 17+ and Android 15 say "he"; the app's tables (and the current keyboard) say "iw".
        assertTrue(parse(edited("en,de_CH", "he")).appliesTo("iw", "qwerty", null, null))
        assertTrue(parse(edited("en,de_CH", "iw")).appliesTo("iw", "qwerty", null, null))
    }

    @Test
    fun aRegionNarrowsOnlyAgainstAnotherRegion() {
        val layout = parse(edited("en,de_CH", "zh_TW"))
        assertTrue("the keyboard's language alone", layout.appliesTo("zh", "qwerty", null, null))
        assertTrue(layout.appliesTo("zh_TW", "qwerty", null, null))
        assertFalse(layout.appliesTo("zh_CN", "qwerty", null, null))
    }

    // ── sequences and the swipe question ─────────────────────────────────────

    @Test
    fun multitapAndMoreKeysAreFoundByTheLetterTheKeyTyped() {
        val layout = parse(swapQw)
        assertArrayEquals(arrayOf("ŵ", "ẁ"), layout.keyForLetter('w'.code)!!.multitap)
        assertArrayEquals(arrayOf("ŵ", "ẃ", "ẅ"), layout.keyForLetter('w'.code)!!.moreKeys)
        // A key with no sequence, and a letter no key types.
        assertEquals(0, layout.keyForLetter('q'.code)!!.multitap.size)
        assertNull(layout.keyForLetter('z'.code))
        assertEquals(KeyEvent.KEYCODE_W, layout.keyForLetter('q'.code)!!.keyCode)
    }

    @Test
    fun changesLettersOnlyWhenALetterKeyTypesSomethingElse() {
        val latin = { keyCode: Int -> 'a'.code + (keyCode - KeyEvent.KEYCODE_A) }
        assertTrue(parse(swapQw).changesLetters(latin))

        // Alt, multitap and long-press additions on a key that still types its own letter do not.
        val additionsOnly = parse(withKeys("""
            <key code="KEYCODE_Q" keySpec="q" alt="#" moreKeys="ʠ"/>
            <key code="KEYCODE_4" keySpec="€"/>
        """))
        assertFalse(additionsOnly.changesLetters(latin))
    }

    // ── refusals ─────────────────────────────────────────────────────────────

    @Test
    fun refusesSomebodyElsesDocument() {
        // Whether the pull parser or this reader notices first, these are refused, not read.
        assertRefused("""{"format": "bbkb-layout"}""")
        assertRefused("<letter-layout version=\"1\" name=\"x\"><bind/>")
        assertRefused("<!-- nothing -->")
        assertRefused("""<device-input-config version="1"/>""", "root element", "<device-input-config>", "<letter-layout>")
        assertRefused(edited("""version="1"""", """version="2""""), "version \"2\"")
        assertRefused(edited("""version="1" """, ""), "no version")
        // A syntax error says where, without the parser's internals.
        try {
            parse(edited("""<key code="KEYCODE_4"""", """<key code="KEYCODE_4" code"""))
            fail("a broken attribute should be refused")
        } catch (e: InvalidLetterLayoutException) {
            assertTrue(e.message, e.message!!.startsWith("not well-formed XML on line 7"))
            assertFalse(e.message, e.message!!.contains("StringReader"))
        }
    }

    @Test
    fun refusesUnknownKeyNames() {
        // The letter O where the digit 0 was meant: the kind of typo that must not pass silently.
        assertRefused(withKeys("""<key code="KEYCODE_ZERO" keySpec="x"/>"""), "KEYCODE_ZERO")
        assertRefused(withKeys("""<key code="Q" keySpec="x"/>"""), "Q is not an Android key code name")
        assertRefused(withKeys("""<key code="45" keySpec="x"/>"""), "45 is not")
        assertRefused(withKeys("""<key code="KEYCODE_UNKNOWN" keySpec="x"/>"""), "KEYCODE_UNKNOWN")
        assertRefused(withKeys("""<key keySpec="x"/>"""), "no code")
        // Any key code the platform names is a key a document may name.
        assertEquals(1, parse(withKeys("""<key code="KEYCODE_SPACE" keySpec="_"/>""")).keyCount)
    }

    @Test
    fun refusesAnythingButOneCharacterPerLegend() {
        assertRefused(edited("""scanCode="17" keySpec="q"""", """scanCode="17" keySpec="qu""""), "KEYCODE_W.keySpec", "single character")
        assertRefused(edited("""scanCode="17" keySpec="q"""", """scanCode="17" keySpec="""""), "KEYCODE_W.keySpec", "empty")
        assertRefused(edited("""scanCode="17" keySpec="q"""", """scanCode="17""""), "KEYCODE_W.keySpec", "missing")
        assertRefused(edited("""keySpec="q"/>""", """keySpec="q" characterMapKeys="&#10;"/>"""), "KEYCODE_W.characterMapKeys", "control")
        assertRefused(edited("""keySpec="q"/>""", """keySpec="q" alt="ab"/>"""), "KEYCODE_W.alt", "single character")
        // A flag is two code points.
        assertRefused(edited("""keySpec="q"/>""", """keySpec="q" alt="🇩🇪"/>"""), "KEYCODE_W.alt")
        // A list entry may be a string, but of at most eight characters.
        assertRefused(edited("""multitapKeys="ŵ,ẁ"""", """multitapKeys="ŵ,abcdefghi""""), "KEYCODE_Q.multitapKeys[1]", "longer than 8")
        assertRefused(edited("""moreKeys="ŵ,ẃ,ẅ"""", """moreKeys="ŵ,${"😀".repeat(9)}""""), "KEYCODE_Q.moreKeys[1]", "longer than 8")
        // A space after the comma is a typo, not a two-character entry; so is one before it.
        assertRefused(edited("""multitapKeys="ŵ,ẁ"""", """multitapKeys="ŵ, ẁ""""), "KEYCODE_Q.multitapKeys[1]", "white space")
        assertRefused(edited("""multitapKeys="ŵ,ẁ"""", """multitapKeys="ŵ ,ẁ""""), "KEYCODE_Q.multitapKeys[0]", "white space")
        assertRefused(edited("""moreKeys="ŵ,ẃ,ẅ"""", """moreKeys="ŵ,a&#10;b""""), "KEYCODE_Q.moreKeys[1]", "control character")
        assertRefused(edited("""multitapKeys="ŵ,ẁ"""", """multitapKeys="""""), "KEYCODE_Q.multitapKeys", "empty")
        assertRefused(edited("""multitapKeys="ŵ,ẁ"""", """multitapKeys="ŵ,""""), "KEYCODE_Q.multitapKeys[1]", "empty")
        assertRefused(edited("""moreKeys="ŵ,ẃ,ẅ"""", """moreKeys="ŵ\ẃ""""), "KEYCODE_Q.moreKeys", "backslash")
        assertRefused(edited("""moreKeys="ŵ,ẃ,ẅ"""", """moreKeys="${List(33) { "x" }.joinToString(",")}""""), "KEYCODE_Q.moreKeys", "more than 32")
        assertRefused(edited("""scanCode="17"""", """scanCode="x17""""), "KEYCODE_W.scanCode")
        assertRefused(edited("""scanCode="17"""", """scanCode="-1""""), "KEYCODE_W.scanCode", "0 or more")
    }

    @Test
    fun refusesUnknownElementsAndAttributesRatherThanIgnoringATypo() {
        assertRefused(edited("""keySpec="q"/>""", """keySpec="q" shfit="Q"/>"""), "KEYCODE_W", "shfit")
        assertRefused(edited("""keypad="qwerty"/>""", """keypad="qwerty" locale="en"/>"""), "bind", "locale")
        assertRefused(edited("""name="Q and W swapped"""", """name="Q and W swapped" author="me""""), "author")
        assertRefused(edited("<bind ", "<keys/><bind "), "unknown element <keys>")
        assertRefused(edited("""keySpec="q"/>""", """keySpec="q"><alt/></key>"""), "KEYCODE_W", "<alt>")
        assertRefused(edited("""keySpec="q"/>""", """keySpec="q">q</key>"""), "unexpected text")
        assertRefused(edited("</letter-layout>", "stray words</letter-layout>"), "unexpected text", "stray words")
        assertRefused(edited("""name="Q and W swapped"""", """name="Q and W swapped" xmlns:x="urn:x""""), "xmlns:x")
    }

    @Test
    fun refusesADoctype() {
        val withDoctype = """<?xml version="1.0"?>
            <!DOCTYPE letter-layout [ <!ENTITY a "aaaaaaaaaa"> ]>
            <letter-layout version="1" id="t" name="&a;"><bind/></letter-layout>
        """.trimIndent()
        assertRefused(withDoctype, "DOCTYPE")
    }

    @Test
    fun refusesABadBindingOrHeader() {
        assertRefused(edited("""keypad="qwerty"""", """keypad="dvorak""""), "keypad", "dvorak")
        assertRefused(edited("""keypad="qwerty"""", """keypadType="4row""""), "keypadType", "4row")
        assertRefused(edited("""keypad="qwerty"""", """layoutSet="east-slavic""""), "layoutSet", "east-slavic")
        assertRefused(edited("""keypad="qwerty"""", """layoutSet="""""), "layoutSet", "layout set name")
        assertRefused(edited("""keypad="qwerty"""", """layoutSet="qwerty,translit""""), "layoutSet", "qwerty,translit")
        assertRefused(edited("en,de_CH", "english"), "languages[0]", "english")
        assertRefused(edited("en,de_CH", "en,,de"), "languages[1]", "empty")
        assertRefused(edited("en,de_CH", List(33) { "en" }.joinToString(",")), "languages", "32")
        assertRefused(edited("""<bind languages="en,de_CH" keypad="qwerty"/>""", ""), "<bind> is missing")
        assertRefused(edited("<bind ", "<bind/><bind "), "<bind> appears twice")
        assertRefused(edited("<bind ", """<rows template="dvorak"/><bind """), "rows template", "dvorak")
        assertRefused(edited("<bind ", """<rows template="qwerty"/><rows template="qwerty"/><bind """), "<rows> appears twice")
        assertRefused(edited("<bind ", "<rows/><bind "), "no template")
        assertRefused(edited("""name="Q and W swapped"""", """name="  """"), "name is empty")
        assertRefused(edited("""name="Q and W swapped"""", """name="${"n".repeat(65)}""""), "name is longer than 64")
        assertRefused(edited(""" name="Q and W swapped"""", ""), "no name")
        assertRefused(edited("""name="Q and W swapped"""", """name="Q and W swapped" targets="pkb,tablet""""), "targets", "tablet")
        assertRefused(edited("""name="Q and W swapped"""", """name="Q and W swapped" targets="""""), "targets is empty")
        // An id becomes a file name: nothing that could leave the layouts directory.
        assertRefused(edited("""id="swap-q-w"""", """id="../../shared_prefs/x""""), "id")
        assertRefused(edited("""id="swap-q-w"""", """id="a/b""""), "id")
        assertRefused(edited("""id="swap-q-w"""", """id="Swap""""), "id \"Swap\"")
    }

    @Test
    fun refusesTooManyKeysDuplicatesAndTooBigAFile() {
        assertRefused(edited("""<key code="KEYCODE_4"""", """<key code="KEYCODE_Q" keySpec="x"/><key code="KEYCODE_4""""), "KEYCODE_Q appears twice")

        // No keys at all is a layout that changes nothing, and that is allowed.
        assertEquals(0, parse(withKeys("")).keyCount)

        // The engine's ceiling of 80 keys is within the cap...
        val names = ('A'..'Z').map { "KEYCODE_$it" } + (0..9).map { "KEYCODE_$it" } +
            listOf("COMMA", "PERIOD", "GRAVE", "MINUS", "EQUALS", "LEFT_BRACKET", "RIGHT_BRACKET",
                "BACKSLASH", "SEMICOLON", "APOSTROPHE", "SLASH", "AT", "POUND", "STAR", "PLUS", "SPACE").map { "KEYCODE_$it" } +
            (1..12).map { "KEYCODE_F$it" } + (0..9).map { "KEYCODE_NUMPAD_$it" } +
            listOf("KEYCODE_NUMPAD_DOT", "KEYCODE_NUMPAD_COMMA", "KEYCODE_NUMPAD_ADD",
                "KEYCODE_NUMPAD_SUBTRACT", "KEYCODE_NUMPAD_MULTIPLY", "KEYCODE_NUMPAD_DIVIDE")
        assertEquals(LetterLayoutXml.MAX_KEYS, names.size)
        val everyKey = names.joinToString("\n") { """<key code="$it" keySpec="x"/>""" }
        assertEquals(LetterLayoutXml.MAX_KEYS, parse(withKeys(everyKey)).keyCount)

        // ...and one more is refused.
        assertRefused(withKeys(everyKey + """<key code="KEYCODE_NUMPAD_EQUALS" keySpec="x"/>"""), "more than ${LetterLayoutXml.MAX_KEYS} keys")

        val padding = "<!-- ${"x".repeat(LetterLayoutXml.MAX_BYTES)} -->"
        assertRefused(swapQw + padding, "larger than")
        try {
            LetterLayoutXml.parse((swapQw + padding).byteInputStream())
            fail("an oversized stream should be refused")
        } catch (e: InvalidLetterLayoutException) {
            assertTrue(e.message!!.contains("larger than"))
        }
    }

    // ── engineKeys ───────────────────────────────────────────────────────────

    @Test
    fun engineKeysAreReadAsSingleCharacters_andWrittenBack() {
        val layout = parse(withKeys("""<key code="KEYCODE_N" keySpec="n" moreKeys="ñ,ń" engineKeys="ñ"/>"""))
        assertEquals(listOf('ñ'.code), layout.keys[android.view.KeyEvent.KEYCODE_N]!!.engineKeys.toList())
        val again = parse(LetterLayoutXml.serialize(layout))
        assertEquals(listOf('ñ'.code), again.keys[android.view.KeyEvent.KEYCODE_N]!!.engineKeys.toList())
        assertTrue(LetterLayoutXml.serialize(layout).contains("engineKeys=\"ñ\""))
    }

    @Test
    fun aKeyWithoutEngineKeysHasNone_andWritesNone() {
        val layout = parse(withKeys("""<key code="KEYCODE_N" keySpec="n"/>"""))
        assertEquals(0, layout.keys[android.view.KeyEvent.KEYCODE_N]!!.engineKeys.size)
        assertFalse(LetterLayoutXml.serialize(layout).contains("engineKeys"))
    }

    @Test
    fun anEngineKeyOfTwoCharactersIsRefused() {
        assertRefused(withKeys("""<key code="KEYCODE_L" keySpec="l" engineKeys="l·l"/>"""), "KEYCODE_L.engineKeys")
    }
}

package dev.bbkb.ime.core.layout

import android.view.KeyEvent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.text.Normalizer

/**
 * The engine's KDB rewritten from a letter layout ([LetterLayoutKdb]), on the KDB files the app
 * ships, read straight off disk: which keys change and how (the `keyLabel` and `keyCodes` forms,
 * alternates, Hangul, the extra key, `keyType`), and that nothing else does — no geometry, no other
 * key, no other byte.
 *
 * Robolectric for the layout documents' pull parser; nothing here touches the engine.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class LetterLayoutKdbTest {

    // ── fixtures ─────────────────────────────────────────────────────────────

    private fun kdbDir(): File {
        val dir = sequenceOf("src/main/assets/kdb", "app/src/main/assets/kdb", "../app/src/main/assets/kdb")
            .map { File(it).canonicalFile }
            .firstOrNull { it.isDirectory }
        assertNotNull("assets/kdb not found from ${File(".").canonicalPath}", dir)
        return dir!!
    }

    private fun kdb(name: String, variant: String = ""): String =
        File(if (variant.isEmpty()) kdbDir() else File(kdbDir(), variant), "${name}_pkb.xml").readText(Charsets.UTF_8)

    private fun allPkbFiles(): List<File> =
        (kdbDir().listFiles()?.toList().orEmpty() + File(kdbDir(), "athena").listFiles()?.toList().orEmpty())
            .filter { it.isFile && it.name.endsWith("_pkb.xml") }
            .sortedBy { it.path }

    private fun layout(keys: String, id: String = "test", targets: String? = null): LetterLayout =
        LetterLayoutXml.parse(
            """<letter-layout version="1" id="$id" name="Test"${targets?.let { " targets=\"$it\"" } ?: ""}>
                <bind/>
                $keys
            </letter-layout>"""
        )

    private fun key(letter: Char, spec: String, extra: String = "") =
        """<key code="KEYCODE_$letter" keySpec="$spec" $extra/>"""

    private val qwerty = "QWERTYUIOPASDFGHJKLZXCVBNM"
    private val cyrillic = "йцукенгшщзфывапролдячсмить"

    /** Cyrillic on every letter key and ю on the extra key: every position of a PKB KDB named. */
    private val everyKey: LetterLayout by lazy {
        layout(qwerty.indices.joinToString("") { key(qwerty[it], cyrillic[it].toString()) } + """<key code="KEYCODE_4" keySpec="ю"/>""")
    }

    private fun codes(xml: String, keyCode: Int): List<Int> =
        LetterLayoutKdb.codesByKeyCode(xml)[keyCode]?.toList() ?: emptyList()

    private fun cp(s: String): List<Int> = s.codePoints().toArray().toList()

    /** The lines of [after] that differ from [before]; the two must have the same number of lines. */
    private fun changedLines(before: String, after: String): List<Pair<String, String>> {
        val a = before.split("\n")
        val b = after.split("\n")
        assertEquals("line count", a.size, b.size)
        return a.indices.filter { a[it] != b[it] }.map { a[it] to b[it] }
    }

    private val geometry = Regex("""\b(keyLeft|keyTop|keyWidth|keyHeight|biasX|biasY)="[^"]*"""")

    // ── which keys, and nothing else ─────────────────────────────────────────

    @Test
    fun aSwapChangesOnlyTheTwoKeysAndOnlyTheirLabels() {
        val before = kdb("qwerty")
        val after = LetterLayoutKdb.rewrite(before, layout(key('Q', "w") + key('W', "q")))
        assertEquals(cp("w"), codes(after, KeyEvent.KEYCODE_Q))
        assertEquals(cp("q"), codes(after, KeyEvent.KEYCODE_W))
        val changed = changedLines(before, after)
        assertEquals(2, changed.size)
        changed.forEach { (old, new) ->
            // Swapping the labels back gives the original line, byte for byte.
            val restored = new.replace("keyLabel=\"W\"", "keyLabel=\"#\"").replace("keyLabel=\"Q\"", "keyLabel=\"W\"")
                .replace("keyLabel=\"#\"", "keyLabel=\"Q\"")
            assertEquals(old, restored)
        }
        // Every other position reads exactly as before.
        val untouched = LetterLayoutKdb.codesByKeyCode(before).filterKeys { it != KeyEvent.KEYCODE_Q && it != KeyEvent.KEYCODE_W }
        untouched.forEach { (code, list) -> assertArrayEquals(list, LetterLayoutKdb.codesByKeyCode(after)[code]) }
    }

    @Test
    fun keysTheLayoutDoesNotNameAndKeysOffTheGridChangeNothing() {
        val before = kdb("qwerty")
        // Space, a digit, punctuation: real keys, but no position in the keypad's KDB.
        val offGrid = layout("""<key code="KEYCODE_SPACE" keySpec="_"/><key code="KEYCODE_1" keySpec="!"/><key code="KEYCODE_PERIOD" keySpec="·"/>""")
        assertSame(before, LetterLayoutKdb.rewrite(before, offGrid))
        // A named key that already has the layout's letters is not rewritten either.
        assertSame(before, LetterLayoutKdb.rewrite(before, layout(key('Q', "q", """characterMapKeys="Q" alt="1""""))))
    }

    @Test
    fun geometryAndEveryNonLetterKeyAreUntouchedInEveryShippedKdb() {
        for (file in allPkbFiles()) {
            val before = file.readText(Charsets.UTF_8)
            val after = LetterLayoutKdb.rewrite(before, everyKey)
            val where = file.relativeTo(kdbDir()).path
            assertEquals(where, geometry.findAll(before).map { it.value }.toList(), geometry.findAll(after).map { it.value }.toList())
            assertEquals(where, before.substringBefore("<area"), after.substringBefore("<area"))
            assertEquals(where, Regex("<key\\b").findAll(before).count(), Regex("<key\\b").findAll(after).count())
            // Function keys (Alt, backspace, enter) are copied through line for line.
            val functionKeys = { xml: String -> xml.lines().filter { "keyType=\"function\"" in it } }
            assertEquals(where, functionKeys(before), functionKeys(after))
            // And every position now carries the layout's letter.
            qwerty.indices.forEach {
                assertEquals("$where ${qwerty[it]}", cyrillic[it].code, codes(after, KeyEvent.KEYCODE_A + (qwerty[it] - 'A')).firstOrNull())
            }
            assertEquals(where, 'ю'.code, codes(after, KeyEvent.KEYCODE_4).firstOrNull())
        }
    }

    @Test
    fun aLayoutOfAFilesOwnLettersRewritesNothing_inEveryShippedKdb() {
        // Read every position's symbols and write them back as a layout: keySpec the primary (in
        // compatibility jamo, as layouts hold Hangul), the rest as double-tap letters. Reading and
        // writing agree exactly when the result is the very same text.
        for (file in allPkbFiles()) {
            val xml = file.readText(Charsets.UTF_8)
            val keys = LetterLayoutKdb.codesByKeyCode(xml).entries.joinToString("") { (keyCode, list) ->
                val name = LetterLayoutXml.nameOf(keyCode)
                val spec = escape(LetterLayoutKdb.toCompatibility(list[0]))
                val rest = list.drop(1).joinToString(",") { escape(LetterLayoutKdb.toCompatibility(it)).replace(",", "\\,") }
                """<key code="$name" keySpec="$spec"${if (rest.isEmpty()) "" else " multitapKeys=\"$rest\""}/>"""
            }
            assertSame(file.relativeTo(kdbDir()).path, xml, LetterLayoutKdb.rewrite(xml, layout(keys)))
        }
    }

    private fun escape(codePoint: Int): String = when (codePoint) {
        '&'.code -> "&amp;"
        '<'.code -> "&lt;"
        '"'.code -> "&quot;"
        '\\'.code -> "\\\\"
        else -> String(Character.toChars(codePoint))
    }

    // ── the two forms ────────────────────────────────────────────────────────

    @Test
    fun aLatinLetterIsWrittenAsKeyLabelWithAlternatesInKeyCodes() {
        val after = LetterLayoutKdb.rewrite(kdb("qwerty"), layout(key('A', "a", """multitapKeys="å,æ"""")))
        assertTrue(after.contains("""keyLabel="A" keyCodes="0x00E5, 0x00E6" keyWidth="108dp" keyHeight="108dp" keyTop="108dp" keyLeft="0dp""""))
        assertEquals(cp("aåæ"), codes(after, KeyEvent.KEYCODE_A))
    }

    @Test
    fun theNordicFormIsReadAsLetterThenAlternates_andRewrittenFromTheLayoutAlone() {
        val danish = kdb("danish")
        assertEquals("keyLabel A + keyCodes å æ", cp("aåæ"), codes(danish, KeyEvent.KEYCODE_A))
        assertEquals(cp("oø"), codes(danish, KeyEvent.KEYCODE_O))

        // A named key takes its letters from the layout only: å and æ go with the swap.
        val swapped = LetterLayoutKdb.rewrite(danish, layout(key('A', "q") + key('Q', "a")))
        assertEquals(cp("q"), codes(swapped, KeyEvent.KEYCODE_A))
        assertEquals(cp("a"), codes(swapped, KeyEvent.KEYCODE_Q))
        val line = swapped.lines().single { "keyTop=\"108dp\" keyLeft=\"0dp\"" in it }
        assertEquals("""        <key keyType="regional" keyLabel="Q" keyWidth="108dp" keyHeight="108dp" keyTop="108dp" keyLeft="0dp" />""", line)
        // The O key, not named, keeps its ø.
        assertEquals(cp("oø"), codes(swapped, KeyEvent.KEYCODE_O))

        // The same letters written by a layout leave the file as it is.
        assertSame(danish, LetterLayoutKdb.rewrite(danish, layout(key('A', "a", """multitapKeys="å,æ""""))))

        // A non-ASCII primary cannot be a keyLabel: the key becomes a keyCodes key.
        val oSlash = LetterLayoutKdb.rewrite(danish, layout(key('O', "ø", """multitapKeys="o"""")))
        assertEquals(cp("øo"), codes(oSlash, KeyEvent.KEYCODE_O))
        assertTrue(oSlash.contains("""<key keyType="regional" keyCodes="0x00F8, 0x006F" keyWidth="108dp" keyHeight="108dp" keyTop="0dp" keyLeft="864dp" />"""))

        // Finnish: keyLabel="A" keyCodes="0x00E4, 0x00E5" is a, ä, å; ä first makes it a keyCodes key.
        val finnish = kdb("finnish")
        assertEquals(cp("aäå"), codes(finnish, KeyEvent.KEYCODE_A))
        val aUmlaut = LetterLayoutKdb.rewrite(finnish, layout(key('A', "ä", """multitapKeys="a,å"""")))
        assertTrue(aUmlaut.contains("""<key keyType="regional" keyCodes="0x00E4, 0x0061, 0x00E5" keyWidth="108dp" keyHeight="108dp" keyTop="108dp" keyLeft="0dp" />"""))
        assertEquals(1, changedLines(finnish, aUmlaut).size)
    }

    @Test
    fun aScriptLetterIsWrittenAsKeyCodes() {
        val after = LetterLayoutKdb.rewrite(kdb("qwerty"), layout(key('Q', "ж")))
        assertTrue(after.contains("""<key keyType="regional" keyCodes="0x0436" keyWidth="108dp" keyHeight="108dp" keyTop="0dp" keyLeft="0dp" />"""))
        assertEquals(cp("ж"), codes(after, KeyEvent.KEYCODE_Q))
    }

    @Test
    fun aLatinLetterOnAScriptKeyIsWrittenAsKeyLabel() {
        val after = LetterLayoutKdb.rewrite(kdb("arabic"), layout(key('Q', "q")))
        assertTrue(after.contains("""<key keyType="regional" keyLabel="Q" keyWidth="108dp" keyHeight="108dp" keyTop="0dp" keyLeft="0dp" />"""))
        assertEquals(cp("q"), codes(after, KeyEvent.KEYCODE_Q))
    }

    @Test
    fun anUpperCaseKeySpecIsStoredLowerCase() {
        val after = LetterLayoutKdb.rewrite(kdb("qwerty"), layout(key('Q', "W")))
        assertEquals(cp("w"), codes(after, KeyEvent.KEYCODE_Q))
        assertTrue(after.contains("""keyLabel="W" keyWidth="108dp" keyHeight="108dp" keyTop="0dp" keyLeft="0dp""""))
    }

    // ── alternates ───────────────────────────────────────────────────────────

    @Test
    fun aShiftLegendThatIsNotTheUpperCaseBecomesTheFirstAlternate() {
        val arabic = kdb("arabic")
        // ص with ض on Shift: exactly what the Arabic KDB already says, so nothing changes.
        assertSame(arabic, LetterLayoutKdb.rewrite(arabic, layout(key('Q', "ص", """characterMapKeys="ض""""))))
        // On the English file the same key gets both.
        val english = LetterLayoutKdb.rewrite(kdb("qwerty"), layout(key('Q', "ص", """characterMapKeys="ض" multitapKeys="ﺽ"""")))
        assertEquals(listOf(0x0635, 0x0636, 0xFEBD), codes(english, KeyEvent.KEYCODE_Q))
    }

    @Test
    fun aShiftLegendThatIsTheUpperCaseAddsNothing() {
        val after = LetterLayoutKdb.rewrite(kdb("qwerty"), layout(key('Q', "w", """characterMapKeys="W"""")))
        assertEquals(cp("w"), codes(after, KeyEvent.KEYCODE_Q))
        assertFalse(after.lines().single { "keyTop=\"0dp\" keyLeft=\"0dp\"" in it }.contains("keyCodes"))
    }

    @Test
    fun alternatesAreDeduplicatedAndCaseFree() {
        // ŵ twice, its upper case, and the key's own letter: one ŵ survives.
        val after = LetterLayoutKdb.rewrite(kdb("qwerty"), layout(key('W', "w", """characterMapKeys="Ŵ" multitapKeys="ŵ,w,ẁ"""")))
        assertEquals(cp("wŵẁ"), codes(after, KeyEvent.KEYCODE_W))
    }

    @Test
    fun whatTheEngineCannotHoldIsLeftOut() {
        val qwerty = kdb("qwerty")
        // A double-tap entry beyond U+FFFF is dropped (the engine's symbols are 16-bit)...
        val emoji = LetterLayoutKdb.rewrite(qwerty, layout(key('Q', "ж", """multitapKeys="😀,з"""")))
        assertEquals(cp("жз"), codes(emoji, KeyEvent.KEYCODE_Q))
        // ...and a primary beyond it leaves the key as it was rather than letterless.
        assertSame(qwerty, LetterLayoutKdb.rewrite(qwerty, layout(key('Q', "𝒜"))))
        // Alt characters are the typing path's business; the engine never sees them.
        assertSame(qwerty, LetterLayoutKdb.rewrite(qwerty, layout(key('Q', "q", """alt="ж""""))))
    }

    // ── Hangul ───────────────────────────────────────────────────────────────

    @Test
    fun hangulIsWrittenInConjoiningJamo() {
        val korean = kdb("korean")
        // The layout holds ㅂ with ㅃ on Shift; the KDB has ᄇ.
        assertEquals(listOf(0x1107), codes(korean, KeyEvent.KEYCODE_Q))
        val after = LetterLayoutKdb.rewrite(korean, layout(key('Q', "ㅂ", """characterMapKeys="ㅃ"""")))
        assertEquals(listOf(0x1107, 0x1108), codes(after, KeyEvent.KEYCODE_Q))
        assertTrue(after.contains("""<key keyType="regional" keyCodes="0x1107, 0x1108" keyWidth="108dp""""))
        // A vowel: ㅛ is ᅭ.
        val vowel = LetterLayoutKdb.rewrite(korean, layout(key('Q', "ㅛ")))
        assertEquals(listOf(0x116D), codes(vowel, KeyEvent.KEYCODE_Q))
        // What the typing path reads from the same file is the compatibility form.
        assertEquals(0x3142, LetterLayoutKdb.lettersByKeyCode(korean)[KeyEvent.KEYCODE_Q])
    }

    @Test
    fun conjoiningAndCompatibilityJamoAreExactInverses_andMatchUnicode() {
        // Unicode's own compatibility decomposition of each modern compatibility jamo (ㄱ..ㅣ) is
        // the reference: the ones that decompose to an initial consonant or a vowel, the only
        // jamo a Korean KDB key holds, map both ways; a cluster that decomposes to a final
        // consonant only (ㄳ) has no initial form and stays as it is.
        val expected = HashMap<Int, Int>()
        for (compat in 0x3131..0x3163) {
            val decomposed = Normalizer.normalize(String(Character.toChars(compat)), Normalizer.Form.NFKD)
            val conjoining = decomposed.codePointAt(0)
            val initialOrVowel = conjoining in 0x1100..0x1112 || conjoining in 0x1161..0x1175
            if (decomposed.codePointCount(0, decomposed.length) == 1 && initialOrVowel) {
                expected[conjoining] = compat
                assertEquals(Integer.toHexString(compat), conjoining, LetterLayoutKdb.toConjoining(compat))
            } else {
                assertEquals(Integer.toHexString(compat), compat, LetterLayoutKdb.toConjoining(compat))
            }
        }
        assertEquals("19 initial consonants and 21 vowels", 40, expected.size)
        for (cp in 0x1100..0x11FF) {
            assertEquals(Integer.toHexString(cp), expected[cp] ?: cp, LetterLayoutKdb.toCompatibility(cp))
        }
        assertEquals(0x3133, LetterLayoutKdb.toConjoining(0x3133))
        assertEquals('a'.code, LetterLayoutKdb.toConjoining('a'.code))
    }

    // ── the extra key and keyType ────────────────────────────────────────────

    @Test
    fun aLetterOnTheDollarKeyMakesItRegional() {
        val qwerty = kdb("qwerty")
        assertEquals(cp("$"), codes(qwerty, KeyEvent.KEYCODE_4))
        val after = LetterLayoutKdb.rewrite(qwerty, layout("""<key code="KEYCODE_4" keySpec="ю"/>"""))
        assertTrue(after.contains("""<key keyType="regional" keyCodes="0x044E" keyWidth="108dp" keyHeight="108dp" keyTop="216dp" keyLeft="864dp" />"""))
        // And the Arabic file's ط key, already regional, stays so with another letter.
        val arabic = LetterLayoutKdb.rewrite(kdb("arabic"), layout("""<key code="KEYCODE_4" keySpec="ظ"/>"""))
        assertTrue(arabic.contains("""<key keyType="regional" keyCodes="0x0638" keyWidth="108dp" keyHeight="108dp" keyTop="216dp" keyLeft="864dp" />"""))
    }

    @Test
    fun punctuationOnALetterKeyMakesItNonRegional() {
        // Dvorak's apostrophe on Q: not a letter, and a keyLabel the engine would ignore.
        val after = LetterLayoutKdb.rewrite(kdb("qwerty"), layout(key('Q', "'")))
        assertTrue(after.contains("""<key keyType="nonRegional" keyCodes="0x0027" keyWidth="108dp" keyHeight="108dp" keyTop="0dp" keyLeft="0dp" />"""))
        assertEquals(cp("'"), codes(after, KeyEvent.KEYCODE_Q))
        // A combining mark counts as a letter (Indic vowel signs sit on regional keys).
        val mark = LetterLayoutKdb.rewrite(kdb("qwerty"), layout(key('Q', "ा")))
        assertTrue(mark.contains("""<key keyType="regional" keyCodes="0x093E""""))
    }

    // ── the athena variant's uneven rows ─────────────────────────────────────

    @Test
    fun theAthenaVariantMapsByTheSameColumnsWhateverItsRowHeights() {
        val athena = kdb("qwerty", "athena")
        val after = LetterLayoutKdb.rewrite(athena, layout(key('A', "z") + key('Z', "a") + """<key code="KEYCODE_4" keySpec="ю"/>"""))
        assertTrue(after.contains("""<key keyType="regional" keyLabel="Z" keyWidth="108dp" keyHeight="180dp" keyTop="90dp" keyLeft="0dp" />"""))
        assertTrue(after.contains("""<key keyType="regional" keyLabel="A" keyWidth="108dp" keyHeight="180dp" keyTop="270dp" keyLeft="108dp" />"""))
        assertTrue(after.contains("""<key keyType="regional" keyCodes="0x044E" keyWidth="108dp" keyHeight="180dp" keyTop="270dp" keyLeft="864dp" />"""))
        assertEquals(3, changedLines(athena, after).size)
    }

    // ── reading ──────────────────────────────────────────────────────────────

    @Test
    fun theFilesLettersReadAsTheTypingPathReadsThem() {
        // The typing path reads the built-in documents, which the generator wrote from these
        // files; what this reader takes from a file must be what its documents type. The translit
        // documents carry the same letters as their language's own.
        val documents = mapOf(
            "arabic" to listOf("ar"), "east_slavic" to listOf("ru", "ru-translit"),
            "ukranian" to listOf("uk", "uk-translit"), "belarusian" to listOf("be", "be-translit"),
            "hebrew" to listOf("iw"), "greek" to listOf("el"), "korean" to listOf("ko"),
        )
        for ((name, ids) in documents) {
            val engine = LetterLayoutKdb.lettersByKeyCode(kdb(name))
            for (id in ids) {
                val typing = builtInDocument(id)
                for (keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
                    assertEquals("$id against $name ${LetterLayoutXml.nameOf(keyCode)}", engine[keyCode], typing.keys[keyCode]?.keySpec)
                }
                // The key past M types a letter only where the file puts one there (Greek and
                // Korean keep `$` on it, and the document leaves it the currency key).
                val extra = engine[KeyEvent.KEYCODE_4]?.takeIf { Character.isLetter(it) }
                assertEquals("$id against $name extra key", extra, typing.keys[KeyEvent.KEYCODE_4]?.keySpec)
            }
        }
    }

    /** A shipped built-in document, read off disk as the registry reads one. */
    private fun builtInDocument(id: String): LetterLayout {
        val file = File(kdbDir().parentFile, "${LetterLayoutRegistry.ASSET_DIRECTORY}/$id${LetterLayoutXml.FILE_SUFFIX}")
        return file.inputStream().use { LetterLayoutXml.parse(it, LetterLayout.Origin.BUILT_IN) }
    }

    @Test
    fun commentedOutKeysAreNotKeys() {
        val xml = """
            <keyboard primaryId="9" secondaryId="6" defaultLayoutWidth="1080" defaultLayoutHeight="324">
              <!-- <area><key keyType="regional" keyLabel="Q" keyWidth="108dp" keyTop="0dp" keyLeft="0dp" /></area> -->
              <area>
                <!-- <key keyType="regional" keyLabel="X" keyWidth="108dp" keyTop="0dp" keyLeft="0dp" /> -->
                <key keyType="regional" keyLabel="Q" keyWidth="108dp" keyTop="0dp" keyLeft="0dp" />
              </area>
            </keyboard>
        """.trimIndent()
        val after = LetterLayoutKdb.rewrite(xml, layout(key('Q', "w")))
        assertEquals(cp("w"), codes(after, KeyEvent.KEYCODE_Q))
        assertTrue("the comments are copied through", after.contains("""<!-- <key keyType="regional" keyLabel="X""""))
        assertTrue(after.contains("""<!-- <area><key keyType="regional" keyLabel="Q""""))
    }

    // ── names and kinds ──────────────────────────────────────────────────────

    @Test
    fun theVariantIsNamedAfterTheLayout() {
        assertEquals("layout:test", LetterLayoutKdb.variantName(layout(key('Q', "w"))))
        assertEquals("_pkb.xml", LetterLayoutKdb.Template.PKB.fileSuffix)
    }

    @Test
    fun onScreenKdbsAreNotRewrittenYet() {
        try {
            LetterLayoutKdb.rewrite(kdb("qwerty"), layout(key('Q', "w")), LetterLayoutKdb.Template.VKB)
            fail("a VKB rewrite was accepted")
        } catch (expected: UnsupportedOperationException) {
        }
    }
}

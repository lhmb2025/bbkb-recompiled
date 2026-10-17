package dev.bbkb.ime.core.layout

import android.content.Context
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.device.detection.KeyEventDeviceClassifier
import dev.bbkb.ime.core.locale.LocaleUtils
import dev.bbkb.ime.core.locale.ResourceLocaleUtils
import dev.bbkb.ime.keyboard.KeyboardSwitcher
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.MockedStatic
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale

/**
 * The golden fixture for the move to letter layouts (`src/test/resources/letter_layouts_golden.json`):
 * what the OLD physical-key path answered (the engine's script layouts through
 * `HardwareScriptLayouts`, and the label-keyed `pkbd_*.xml` tables through `PhysicalKeySpecTable`),
 * recorded before that path was deleted. It is frozen: nothing writes it any more.
 *
 * [theResolverGivesTheRecordedAnswers] asks [PhysicalKeyResolver], over the shipped documents,
 * the same questions for every keyboard and keypad the fixture covers, and holds it to the
 * recorded answers entry for entry:
 *
 *  - `scriptLayoutActive` — [PhysicalKeyResolver.isScriptLayoutActive];
 *  - `letters` — [PhysicalKeyResolver.letter] for `KEYCODE_A`..`KEYCODE_Z` and `KEYCODE_4`,
 *    unshifted and with Shift; `KEYCODE_4` both as the KEY2's currency key (its key map types `$`)
 *    and as a digit key;
 *  - `lists` — for every label the fixture recorded, and every label the documents make
 *    answerable (each key's letter, its upper case, its Shift legend, the currency symbols):
 *    `doubleTap` = [PhysicalKeyResolver.multitap], `hold` = [PhysicalKeyResolver.moreKeys],
 *    `shiftLegend` = [PhysicalKeyResolver.shiftLegend]. These are the answers the typing code
 *    received; the fixture's other list fields (`alternatesFor`, `getMultiTapSequence`,
 *    `getMultiTapAlternates`, `getCharacterMapKeys`) were the old path's inner workings, which the
 *    switcher composed into those three, and have no counterpart to ask. `element` is the
 *    keyboard builder's, not a physical-key answer.
 *
 * It also checks that [PhysicalKeyResolver.multitapTable] holds exactly the double-tap answers.
 *
 * Where the new answer is meant to differ, the difference is listed in [TOLERATED] with its
 * reason, and each tolerance holds an exact count: a difference no tolerance names fails the
 * test, and so does a tolerance whose count changes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LetterLayoutGoldenTest {

    private lateinit var context: Context
    private lateinit var classifierStatic: MockedStatic<KeyEventDeviceClassifier>
    private lateinit var switcherStatic: MockedStatic<KeyboardSwitcher>
    private lateinit var saved: PhysicalKeyResolver.Snapshot

    private var locale: Locale = Locale.US
    private var layoutSet: String = "qwerty"
    private var keypad: String = "qwerty"
    private var alphabetSwitch = true

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ResourceLocaleUtils.reinit(context)
        saved = PhysicalKeyResolver.Snapshot()
        PhysicalKeyResolver.builtIns = PhysicalKeyResolver.RegistryBuiltIns(LetterLayoutRegistry(context))
        PhysicalKeyResolver.localeSource = { locale }
        PhysicalKeyResolver.additionalLocalesSource = { null }
        PhysicalKeyResolver.keypadLayoutSource = { keypad }
        PhysicalKeyResolver.keypadTypeSource = { "4row" }
        PhysicalKeyResolver.layoutSetSource = { layoutSet }
        // The fixture's "alphabetSwitchOff" answers are what a language that chose the keypad's
        // own letters gets today.
        PhysicalKeyResolver.keypadLettersSource = { _ -> !alphabetSwitch }
        // The fixture was recorded with the default (empty) "Default currency" setting.
        PhysicalKeyResolver.currencySettingSource = { "" }
        PhysicalKeyResolver.zeroKeySource = { "0" }

        classifierStatic = mockStatic(KeyEventDeviceClassifier::class.java)
        val classifier = mock(KeyEventDeviceClassifier::class.java)
        `when`(classifier.isPhysicalKeyboardEvent(any())).thenReturn(true)
        classifierStatic.`when`<KeyEventDeviceClassifier> { KeyEventDeviceClassifier.getInstance() }
            .thenReturn(classifier)
        switcherStatic = mockStatic(KeyboardSwitcher::class.java)
        val switcher = mock(KeyboardSwitcher::class.java)
        `when`(switcher.isPhysicalSymbolMappingActive).thenReturn(false)
        switcherStatic.`when`<KeyboardSwitcher> { KeyboardSwitcher.getInstance() }.thenReturn(switcher)
    }

    @After
    fun tearDown() {
        saved.restore()
        switcherStatic.close()
        classifierStatic.close()
    }

    /** One answer that is not the recorded one. */
    data class Difference(
        val subtype: String,
        val switchOff: Boolean,
        val keypad: String,
        val field: String,
        val label: String,
        val recorded: Any?,
        val now: Any?,
    ) {
        override fun toString() =
            "$subtype${if (switchOff) " [alphabet switch off]" else ""} ($keypad) $field '$label': recorded $recorded, now $now"
    }

    @Test
    fun theResolverGivesTheRecordedAnswers() {
        val fixture = JSONObject(fixtureFile().readText(Charsets.UTF_8))
        val subtypes = fixture.getJSONArray("subtypes")
        val differences = ArrayList<Difference>()
        var questions = 0
        for (i in 0 until subtypes.length()) {
            val entry = subtypes.getJSONObject(i)
            val name = entry.getString("subtype")
            val language = entry.getString("language")
            locale = LocaleUtils.constructLocaleFromString(name.substringBefore(':'))!!
            layoutSet = name.substringAfter(':')
            assertEquals("$name: the fixture's language", language, LocaleUtils.languageCode(locale))
            val groups = entry.getJSONArray("keypads")
            for (g in 0 until groups.length()) {
                val group = groups.getJSONObject(g)
                val keypads = group.getJSONArray("keypads")
                for (k in 0 until keypads.length()) {
                    keypad = keypads.getString(k)
                    alphabetSwitch = true
                    questions += compare(name, false, group, differences)
                }
            }
            entry.optJSONObject("alphabetSwitchOff")?.let { off ->
                keypad = "qwerty"
                alphabetSwitch = false
                questions += compare(name, true, off, differences)
                alphabetSwitch = true
            }
        }
        assertTrue("only $questions questions asked", questions > 20_000)

        val unexplained = differences.filter { d -> TOLERATED.none { it.covers(d) } }
        val counts = TOLERATED.associateWith { t -> differences.count { t.covers(it) } }
        val report = StringBuilder()
        if (unexplained.isNotEmpty()) {
            report.append("${unexplained.size} answers differ from the fixture and no tolerance names them:\n")
            unexplained.take(400).forEach { report.append("  ").append(it).append('\n') }
        }
        for ((tolerance, count) in counts) {
            if (count != tolerance.count) {
                report.append("tolerance \"${tolerance.name}\" covers $count differences, not ${tolerance.count}:\n")
                differences.filter { tolerance.covers(it) }.take(60).forEach { report.append("  ").append(it).append('\n') }
            }
        }
        if (report.isNotEmpty()) fail(report.toString())
    }

    /** Asks every question of one recorded group; returns how many were asked. */
    private fun compare(subtype: String, switchOff: Boolean, group: JSONObject, out: MutableList<Difference>): Int {
        var asked = 0
        fun differ(field: String, label: String, recorded: Any?, now: Any?) {
            asked++
            if (recorded != now) out.add(Difference(subtype, switchOff, keypad, field, label, recorded, now))
        }
        differ("scriptLayoutActive", "", group.getBoolean("scriptLayoutActive"), PhysicalKeyResolver.isScriptLayoutActive())

        val letters = group.getJSONObject("letters")
        for (code in letters.keys()) {
            val recorded = letters.getJSONArray(code).let { pair -> listOf(pair.optStringOrNull(0), pair.optStringOrNull(1)) }
            val event = keyEvent(code)
            val now = listOf(0, KeyEvent.META_SHIFT_ON).map { meta ->
                PhysicalKeyResolver.letter(event, meta).takeIf { it != 0 }?.let { String(Character.toChars(it)) }
            }
            differ("letter", code, recorded, now)
        }

        val lists = group.getJSONObject("lists")
        val labels = LinkedHashSet<String>()
        lists.keys().forEach { labels.add(it) }
        PhysicalKeyResolver.builtInLayout()?.keys?.values?.forEach { key ->
            val letter = LetterLayout.string(key.keySpec)
            labels.add(letter)
            labels.add(letter.uppercase(locale))
            if (key.shift != 0) labels.add(LetterLayout.string(key.shift))
        }
        labels.addAll(PhysicalKeyResolver.CURRENCY_SYMBOLS)
        val doubleTaps = LinkedHashMap<String, List<String>>()
        for (label in labels) {
            val recorded = lists.optJSONObject(label)
            val doubleTap = PhysicalKeyResolver.multitap(label)?.toList()
            doubleTap?.let { doubleTaps[label] = it }
            differ("doubleTap", label, recorded?.optList("doubleTap"), doubleTap)
            differ("hold", label, recorded?.optList("hold"), PhysicalKeyResolver.moreKeys(label)?.toList())
            differ("shiftLegend", label, recorded?.optStringOrNull("shiftLegend"), PhysicalKeyResolver.shiftLegend(label))
        }
        // The bulk table is the same answers, all at once.
        val table = PhysicalKeyResolver.multitapTable().mapValues { it.value.toList() }
        assertEquals("$subtype ($keypad): multitapTable", doubleTaps, table.filterKeys { it in labels })
        for ((label, sequence) in table) {
            assertEquals("$subtype ($keypad): multitapTable '$label'", PhysicalKeyResolver.multitap(label)?.toList(), sequence)
        }
        return asked
    }

    /** The fixture's key events: the letter keys, the KEY2's currency key and a digit key. */
    private fun keyEvent(name: String): KeyEvent = when (name) {
        // The KEY2's key past M sends KEYCODE_4 and its key map types `$`.
        "KEYCODE_4" -> object : KeyEvent(0L, 0L, ACTION_DOWN, KEYCODE_4, 0, 0, 1, 0) {
            override fun getUnicodeChar(): Int = '$'.code
        }
        "KEYCODE_4 as a digit" -> object : KeyEvent(0L, 0L, ACTION_DOWN, KEYCODE_4, 0, 0, 1, 0) {
            override fun getUnicodeChar(): Int = '4'.code
        }
        else -> KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.keyCodeFromString(name), 0, 0, 1, 0)
    }

    private fun JSONArray.optStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

    private fun JSONObject.optStringOrNull(name: String): String? = if (isNull(name)) null else getString(name)

    private fun JSONObject.optList(name: String): List<String>? =
        optJSONArray(name)?.let { a -> (0 until a.length()).map { a.getString(it) } }

    /**
     * The generated documents say what the fixture recorded, for every keyboard that has one:
     * an independent check of the generator, which reads the old tables as text, against what
     * the app's own parser made of the compiled resources when the fixture was recorded.
     *
     * Each keyboard's document is the one its binding selects: bound to the keyboard's language
     * and either to its layout set or to any (a named layout set first), as the registry chooses.
     *
     * Script documents: each key's letter and Shift letter (`characterMapKeys`, else the upper
     * case) are the old path's; its `multitapKeys` are the double-tap answer for its letter, and
     * its `moreKeys` the hold answer, entry for entry, ligatures (لا, צ׳, l·l) included. Latin
     * documents: the old path leaves every letter to the key map, and the lists are compared the
     * same way. Answers for upper-case labels are derived at run time and not compared.
     */
    @Test
    fun theGeneratedDocumentsCarryTheRecordedAnswers() {
        val fixture = JSONObject(fixtureFile().readText(Charsets.UTF_8))
        val docs = LetterLayoutsMatchEngineTest.documents().values.toList()
        val subtypes = fixture.getJSONArray("subtypes")
        var compared = 0
        var withDocument = 0
        for (i in 0 until subtypes.length()) {
            val entry = subtypes.getJSONObject(i)
            val name = entry.getString("subtype")
            val language = entry.getString("language")
            val layoutSet = name.substringAfter(':')
            val groups = entry.getJSONArray("keypads")
            val doc = documentFor(docs, language, layoutSet)
            // The translit keyboards and Chinese pinyin and cangjie have documents of their own
            // layout set; every other keyboard takes its language's document.
            val expectedId = when {
                layoutSet == "translit" -> "$language-translit"
                language == "zh" -> "zh-$layoutSet"
                else -> language
            }
            doc?.let { assertEquals("$name selects", expectedId, it.id) }
            if (doc == null) {
                // No document: the old path must give these keys nothing a document would carry.
                for (g in 0 until groups.length()) {
                    val group = groups.getJSONObject(g)
                    val letters = group.getJSONObject("letters")
                    for (code in letters.keys()) {
                        val pair = letters.getJSONArray(code)
                        assertTrue("$name $code types ${pair} but has no document", pair.isNull(0) && pair.isNull(1))
                    }
                    val lists = group.getJSONObject("lists")
                    for (letter in 'a'..'z') {
                        assertTrue("$name '$letter' has lists ${lists.optJSONObject(letter.toString())} but no document",
                            !lists.has(letter.toString()))
                    }
                }
                continue
            }
            assertTrue("$name: document ${doc.id} is bound to ${doc.languages}", language in doc.languages)
            withDocument++
            for (g in 0 until groups.length()) {
                val group = groups.getJSONObject(g)
                val where = "$name ${group.getJSONArray("keypads")} vs ${doc.id}.xml"
                val letters = group.getJSONObject("letters")
                val lists = group.getJSONObject("lists")
                val keys = doc.keys.associateBy { it.code }
                val script = group.getBoolean("scriptLayoutActive")
                for (code in letters.keys()) {
                    if (code == "KEYCODE_4 as a digit") continue
                    val pair = letters.getJSONArray(code)
                    val key = keys[code]
                    if (!script) {
                        assertTrue("$where $code: the old path types ${pair}", pair.isNull(0) && pair.isNull(1))
                        continue
                    }
                    if (pair.isNull(0)) {
                        assertTrue("$where $code: no letter today, but the document names it", key == null)
                        continue
                    }
                    assertTrue("$where $code: the old path types ${pair.getString(0)}, the document has no key", key != null)
                    assertEquals("$where $code unshifted", pair.getString(0), key!!.keySpec)
                    val shifted = key.characterMapKeys
                        ?: String(Character.toChars(Character.toUpperCase(key.keySpec.codePointAt(0))))
                    assertEquals("$where $code shifted", pair.getString(1), shifted)
                }
                for (key in doc.keys) {
                    val answers = lists.optJSONObject(key.keySpec)
                    fun list(field: String): List<String> =
                        answers?.optJSONArray(field)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
                    assertEquals("$where ${key.code} '${key.keySpec}' double-tap", list("doubleTap"), key.multitapKeys)
                    assertEquals("$where ${key.code} '${key.keySpec}' hold", list("hold"), key.moreKeys)
                    compared++
                }
            }
        }
        // 71 of the 93 keyboards have a document, each compared key by key.
        assertTrue("only $withDocument keyboards had a document", withDocument >= 60)
        assertTrue("only $compared keys compared", compared >= 26 * withDocument)
    }

    /**
     * The registry's choice among [docs] for a keyboard in [language] built from [layoutSet],
     * every document being for any keypad: bound to the language, and to that layout set or to
     * any; a named layout set first, then fewer languages, then the id that is the language,
     * then the lowest id. Spelled out here rather than asking the registry, so a fault in either
     * shows as a disagreement.
     */
    private fun documentFor(
        docs: List<LetterLayoutsMatchEngineTest.Companion.Doc>,
        language: String,
        layoutSet: String,
    ): LetterLayoutsMatchEngineTest.Companion.Doc? = docs
        .filter { !it.optIn && language in it.languages && (it.layoutSet == "any" || it.layoutSet == layoutSet) }
        .sortedWith(
            compareByDescending<LetterLayoutsMatchEngineTest.Companion.Doc> { it.layoutSet != "any" }
                .thenBy { it.languages.size }
                .thenByDescending { it.id == language }
                .thenBy { it.id }
        )
        .firstOrNull()

    /** `app/src/test/resources/<FIXTURE>`, found from the module or the repository directory. */
    private fun fixtureFile(): File {
        val testDir = sequenceOf("src/test", "app/src/test", "../app/src/test")
            .map { File(it).canonicalFile }
            .firstOrNull { File(it, "java").isDirectory }
            ?: throw AssertionError("src/test not found from ${File(".").canonicalPath}")
        return File(testDir, "resources/$FIXTURE").also { assertTrue("${it.path} is missing", it.isFile) }
    }

    /** A kind of difference the new path is meant to have, with its reason and exact size. */
    class Tolerance(val name: String, val count: Int, val covers: (Difference) -> Boolean)

    private companion object {
        const val FIXTURE = "letter_layouts_golden.json"

        /** The keyboards whose Cyrillic letters are cased, so Shift types the capital. */
        val CASED_CYRILLIC = setOf(
            "ru:east_slavic", "uk:east_slavic", "be:east_slavic", "ru:translit", "uk:translit", "be:translit",
        )

        /** The legends Shift typed from the engine layout's second letter, now named by the documents. */
        val LEGENDS_NOW_IN_THE_DOCUMENTS = setOf(
            "iw:hebrew" to "ג", "iw:hebrew" to "ז", "iw:hebrew" to "ח", "iw:hebrew" to "ת", "iw:hebrew" to "י",
            "el:greek" to "\u037E",
        )

        /** Table entries for letters no key types, or that the documents leave out; see [TOLERATED]. */
        val TABLE_ENTRIES_NO_KEY_TYPES = setOf(
            // The Greek table keys its `:` to U+003B; the key types U+037E GREEK QUESTION MARK.
            Triple("el:greek", "hold", ";"),
            // Turkish keyspec_i is ı, which no key types (the key map types i and I there). The
            // old table also derived I's list from it, so Shift+I then hold offered İ Î Ï Ì Í Į Ī;
            // the Turkish document has no key typing ı, so neither I nor ı has a list now.
            Triple("tr:qwerty", "hold", "ı"), Triple("tr:qwerty", "hold", "I"),
            // The translit table's second ж entry carries the currency list (a slip for the
            // currency key); on Russian and Ukrainian translit ж is a double-tap alternate of д,
            // never a key's letter, so holding a key never asked for it.
            Triple("ru:translit", "hold", "ж"), Triple("ru:translit", "hold", "Ж"),
            Triple("uk:translit", "hold", "ж"), Triple("uk:translit", "hold", "Ж"),
            // The be|uk case: і is a double-tap alternate on Ukrainian translit, not a key's letter.
            Triple("uk:translit", "hold", "і"), Triple("uk:translit", "hold", "І"),
        )

        /**
         * The answers the resolver is meant to give differently, each with its reason and the
         * exact number of differences it covers (keyboards x keypads x labels). Disjoint: no
         * difference is covered twice.
         */
        val TOLERATED: List<Tolerance> = listOf(
            // The currency key's list (plan §7) is the resolver's now, the same on every keyboard:
            // the old tables listed only some currency symbols per table ($ £ € ₺ ₫ in the Latin
            // one, $ in the Arabic, € in the Greek, ₩ in the Korean, ₽ in the Cyrillic, none in
            // Hebrew, translit, or a keyboard with no table). Every list the fixture recorded is
            // still given exactly; these are the symbols that gain one.
            Tolerance("currency symbols the old table did not list", 1081) { d ->
                d.field == "hold" && d.label in PhysicalKeyResolver.CURRENCY_SYMBOLS && d.recorded == null && d.now != null
            },
            // The old tables answered characterMapKeys (or, without one, multitapKeys) for every
            // label, but only a caseless script's letter ever read it: Shift on a Cyrillic letter
            // types its capital (asserted in `letters`). The documents carry a legend only where
            // Shift types it.
            Tolerance("Cyrillic legends Shift never typed", 150) { d ->
                !d.switchOff && d.field == "shiftLegend" && d.subtype in CASED_CYRILLIC && d.recorded != null && d.now == null
            },
            // Shift typed these from the engine layout's second letter (asserted in `letters`);
            // the documents name that letter as the key's legend, where the table had none.
            Tolerance("legends the engine layout supplied", 18) { d ->
                !d.switchOff && d.field == "shiftLegend" && d.recorded == null &&
                    (d.subtype to d.label) in LEGENDS_NOW_IN_THE_DOCUMENTS
            },
            // Kyrgyz keys type the key map's Latin letters (no Kyrgyz engine layout), so the
            // Cyrillic-keyed tables it was given never matched a typed letter, and it has no
            // document.
            Tolerance("Kyrgyz: tables keyed by letters its keys never type", 168) { d ->
                !d.switchOff && d.subtype.startsWith("ky:") && d.now == null
            },
            // "Use active language's alphabet" off turns the built-in documents off; the keys type
            // the key map's Latin letters, and the old tables' lists, legends and double taps for
            // the script's own letters are no longer offered. (The currency key's are, above.)
            Tolerance("the alphabet switch off", 198) { d ->
                d.switchOff && d.now == null && !(d.field == "hold" && d.label in PhysicalKeyResolver.CURRENCY_SYMBOLS)
            },
            Tolerance("table entries no key types", 27) { d ->
                !d.switchOff && d.now == null && Triple(d.subtype, d.field, d.label) in TABLE_ENTRIES_NO_KEY_TYPES
            },
            // Rule 3: an upper-case double tap is upper-cased with the keyboard language's rules,
            // and an entry that comes out as the letter itself leaves the cycle, as the hold lists
            // always did. ΐ and ΰ have no one-letter capital (Greek gives Ι and Υ); the old cycle
            // offered the lower-case letter after the capitals.
            Tolerance("Greek capitals' double tap without ΐ and ΰ", 6) { d ->
                !d.switchOff && d.subtype == "el:greek" && d.field == "doubleTap" && d.label in setOf("Ι", "Υ") &&
                    d.recorded == (d.now as? List<*>)?.plus(if (d.label == "Ι") "ΐ" else "ΰ")
            },
        )
    }
}

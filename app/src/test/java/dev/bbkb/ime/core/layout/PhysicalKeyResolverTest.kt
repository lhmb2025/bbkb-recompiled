package dev.bbkb.ime.core.layout

import android.content.Context
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.device.detection.KeyEventDeviceClassifier
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.keyboard.KeyboardSwitcher
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
import java.io.InputStream
import java.util.Locale

/**
 * [PhysicalKeyResolver]: the user's letter layout, then the built-in document for the keyboard,
 * then the key map (0/null), for every question the typing path asks about a physical key.
 *
 * The keyboard state the resolver reads (the active user layout, the built-in documents, the
 * locale, layout set, keypad, the alphabet switch, the currency setting) comes in through its
 * sources, replaced here; the physical-keyboard check and the switcher's symbol page are the two
 * statics the tests mock, as `KeyIdentityCharacterisationTest` does. The shipped documents are
 * read from the app's assets for the keycap checks, which come from the KEY2 keycap photos
 * (Arabic, Russian and Korean units, 2026-09-30) and BlackBerry's Shift layer tables.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhysicalKeyResolverTest {

    private lateinit var classifierStatic: MockedStatic<KeyEventDeviceClassifier>
    private lateinit var switcherStatic: MockedStatic<KeyboardSwitcher>
    private lateinit var switcher: KeyboardSwitcher
    private lateinit var saved: PhysicalKeyResolver.Snapshot

    private var activeLayout: LetterLayout? = null
    private var locale: Locale? = Locale.ENGLISH
    private var extraLocales: List<Locale>? = null
    private var keypad: String? = "qwerty"
    private var keypadType: String? = "4row"
    private var layoutSet: String? = "qwerty"
    private var alphabetSwitch = true
    private var currencySetting = ""
    private var layoutSetAsked = 0

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        saved = PhysicalKeyResolver.Snapshot()
        PhysicalKeyResolver.chosenLayoutSource = { _ -> activeLayout }
        PhysicalKeyResolver.builtIns = shipped()
        PhysicalKeyResolver.localeSource = { locale }
        PhysicalKeyResolver.additionalLocalesSource = { extraLocales }
        PhysicalKeyResolver.keypadLayoutSource = { keypad }
        PhysicalKeyResolver.keypadTypeSource = { keypadType }
        PhysicalKeyResolver.layoutSetSource = { layoutSetAsked++; layoutSet }
        PhysicalKeyResolver.keypadLettersSource = { _ -> !alphabetSwitch }
        PhysicalKeyResolver.currencySettingSource = { currencySetting }
        PhysicalKeyResolver.zeroKeySource = { "0" }

        classifierStatic = mockStatic(KeyEventDeviceClassifier::class.java)
        val classifier = mock(KeyEventDeviceClassifier::class.java)
        `when`(classifier.isPhysicalKeyboardEvent(any())).thenReturn(true)
        classifierStatic.`when`<KeyEventDeviceClassifier> { KeyEventDeviceClassifier.getInstance() }
            .thenReturn(classifier)

        // The switcher singleton exists from class load but has no keyboard state outside a
        // running IME; a mock answers whether a symbol page is mapping the keys.
        switcherStatic = mockStatic(KeyboardSwitcher::class.java)
        switcher = mock(KeyboardSwitcher::class.java)
        `when`(switcher.isPhysicalSymbolMappingActive).thenReturn(false)
        switcherStatic.`when`<KeyboardSwitcher> { KeyboardSwitcher.getInstance() }.thenReturn(switcher)
    }

    @After
    fun tearDown() {
        saved.restore()
        classifierStatic.close()
        switcherStatic.close()
        LetterLayoutRegistry.invalidate()
        PrefsManager.getPrefs(context).edit().clear().commit()
        LetterLayoutRegistry.directoryOf(context).deleteRecursively()
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** The app's own documents, as the running keyboard reads them. */
    private fun shipped(): PhysicalKeyResolver.BuiltIns =
        PhysicalKeyResolver.RegistryBuiltIns(LetterLayoutRegistry(context))

    /** Built-ins made of [documents] (XML), selected by the registry's own rules. */
    private fun builtIns(vararg documents: String): PhysicalKeyResolver.BuiltIns {
        val byId = documents.associateBy { Regex("""id="([^"]+)"""").find(it)!!.groupValues[1] }
        return PhysicalKeyResolver.RegistryBuiltIns(LetterLayoutRegistry(context, object : LetterLayoutRegistry.BuiltInDocuments {
            override fun ids(): List<String> = byId.keys.toList()
            override fun open(id: String): InputStream? = byId[id]?.byteInputStream()
        }))
    }

    private fun document(
        id: String,
        keys: String,
        languages: String = "en",
        keypadLayout: String = "any",
        keypadType: String = "any",
        layoutSet: String = "any",
    ) = """<letter-layout version="1" id="$id" name="$id">
            <bind languages="$languages" keypad="$keypadLayout" keypadType="$keypadType" layoutSet="$layoutSet"/>
            $keys
        </letter-layout>"""

    private fun user(
        id: String = "swap",
        languages: String = "en",
        keypadLayout: String = "qwerty",
        keypadType: String = "any",
        keys: String = SWAP_KEYS,
        layoutSet: String = "any",
    ): LetterLayout = LetterLayoutXml.parse(document(id, keys, languages, keypadLayout, keypadType, layoutSet))

    private fun down(keyCode: Int, scanCode: Int = 0, meta: Int = 0) =
        KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, keyCode, 0, meta, 1, scanCode)

    /** The KEY2's key past M: KEYCODE_4, which its key map spells `$`. */
    private fun currencyKey(scanCode: Int = 5, meta: Int = 0) =
        object : KeyEvent(0L, 0L, ACTION_DOWN, KEYCODE_4, 0, meta, 1, scanCode) {
            override fun getUnicodeChar(): Int = '$'.code
            override fun getUnicodeChar(metaState: Int): Int = '$'.code
        }

    private fun text(codePoint: Int): String? = codePoint.takeIf { it != 0 }?.let { String(Character.toChars(it)) }

    private fun letter(event: KeyEvent, interpretedMeta: Int = event.metaState): String? =
        text(PhysicalKeyResolver.letter(event, interpretedMeta))

    private fun letter(keyCode: Int, scanCode: Int = 0, meta: Int = 0, interpretedMeta: Int = meta): String? =
        letter(down(keyCode, scanCode, meta), interpretedMeta)

    private fun shifted(keyCode: Int): String? = letter(keyCode, interpretedMeta = KeyEvent.META_SHIFT_ON)

    private fun keyboard(language: Locale, set: String) {
        locale = language
        layoutSet = set
    }

    private fun row(keys: String): String = keys.map { letter(KeyEvent.keyCodeFromString("KEYCODE_$it")) ?: "·" }.joinToString("")

    // ── the user's layout ────────────────────────────────────────────────────

    @Test
    fun aUserLayoutTypesItsLettersAndShiftLegendsOnALatinKeyboard() {
        activeLayout = user()
        assertEquals("w", letter(KeyEvent.KEYCODE_Q))
        assertEquals("W", shifted(KeyEvent.KEYCODE_Q))
        assertEquals("q", letter(KeyEvent.KEYCODE_W, scanCode = 17))
        // No Shift legend: the upper case of keySpec.
        assertEquals("Q", letter(KeyEvent.KEYCODE_W, scanCode = 17, interpretedMeta = KeyEvent.META_SHIFT_ON))
        // Holding for a capital gives the Shift letter.
        assertEquals('W'.code, PhysicalKeyResolver.heldLetter(down(KeyEvent.KEYCODE_Q)))
    }

    @Test
    fun aUserLayoutReachesPastTheLetterKeysButNotTheKeyboardsOwnKeys() {
        activeLayout = user(keys = """<key code="KEYCODE_PERIOD" keySpec="·"/><key code="KEYCODE_SPACE" keySpec="_" alt="#"/>""")
        assertEquals("·", letter(KeyEvent.KEYCODE_PERIOD))
        assertNull(letter(KeyEvent.KEYCODE_SPACE))
        assertEquals(0, PhysicalKeyResolver.alt(KeyEvent.KEYCODE_SPACE, -1))
        assertEquals(1, PhysicalKeyResolver.userLayout()!!.keyCount)
        assertEquals("the document itself keeps both", 2, activeLayout!!.keyCount)
    }

    @Test
    fun aUserLayoutWinsOverTheBuiltInDocumentForTheKeysItNames() {
        keyboard(Locale("ru"), "east_slavic")
        activeLayout = user(languages = "ru", keys = """<key code="KEYCODE_Q" keySpec="q"/>""")
        // A user layout's Latin letter applies (rule 2 is for built-in documents only).
        assertEquals("q", letter(KeyEvent.KEYCODE_Q))
        // Keys it does not name fall through to the Russian document, then to the key map.
        assertEquals("ц", letter(KeyEvent.KEYCODE_W))
        assertNull(letter(KeyEvent.KEYCODE_SPACE))
    }

    @Test
    fun keysTheLayoutsDoNotNameFallThroughToTheKeyMap() {
        activeLayout = user()
        // English: its document's letters are the key map's own (rule 2), so 0 either way.
        assertNull(letter(KeyEvent.KEYCODE_E))
        // KEYCODE_W is narrowed to scan code 17; another switch sending it is not the layout's key.
        assertNull(letter(KeyEvent.KEYCODE_W, scanCode = 18))
        activeLayout = null
        assertNull(letter(KeyEvent.KEYCODE_Q))
        assertNull(PhysicalKeyResolver.userLayout())
        assertEquals(0, PhysicalKeyResolver.alt(KeyEvent.KEYCODE_Q, -1))
    }

    @Test
    fun aUserLayoutAppliesOnlyToTheKeyboardsAndKeypadItIsBoundTo() {
        activeLayout = user(languages = "de")
        assertNull("bound to German, typing English", letter(KeyEvent.KEYCODE_Q))
        locale = Locale.GERMAN
        assertEquals("w", letter(KeyEvent.KEYCODE_Q))
        keypad = "qwertz"
        assertNull("written for a QWERTY keypad", letter(KeyEvent.KEYCODE_Q))

        activeLayout = user(languages = "")
        keypad = "qwerty"
        keyboard(Locale("ar"), "arabic")
        assertEquals("an unbound layout applies on every keyboard", "w", letter(KeyEvent.KEYCODE_Q))

        activeLayout = user(languages = "", keypadLayout = "any", keypadType = "3row")
        assertEquals("not for a 4-row keypad: the Arabic document types", "ص", letter(KeyEvent.KEYCODE_Q))
        keypadType = "3row"
        assertEquals("w", letter(KeyEvent.KEYCODE_Q))
        keypadType = null
        assertEquals("an unknown keypad type is not held against it", "w", letter(KeyEvent.KEYCODE_Q))
    }

    @Test
    fun aUserLayoutAppliesOnlyToTheLayoutSetItIsBoundTo() {
        keyboard(Locale("ru"), "east_slavic")
        activeLayout = user(languages = "ru", layoutSet = "translit")
        assertEquals("written for the translit keyboard: the Russian document types", "й", letter(KeyEvent.KEYCODE_Q))
        layoutSet = "translit"
        assertEquals("w", letter(KeyEvent.KEYCODE_Q))
    }

    @Test
    fun anUnboundLayoutNeverAsksForTheLayoutSet() {
        alphabetSwitch = false
        activeLayout = user(languages = "")
        layoutSetAsked = 0
        assertEquals("w", letter(KeyEvent.KEYCODE_Q))
        assertEquals(0, layoutSetAsked)
    }

    // ── the built-in documents ───────────────────────────────────────────────

    @Test
    fun arabicKeycapsRowByRowWithTheirShiftLayer() {
        keyboard(Locale("ar"), "arabic")
        assertEquals("صثقفغعهخحج", row("QWERTYUIOP"))
        assertEquals("سيبلاتنمك", row("ASDFGHJKL"))
        assertEquals("ذدزرةوظ", row("ZXCVBNM"))
        // The KEY2's `$` key carries ط; a Titan's 4 key sending the same key code stays a digit.
        assertEquals("ط", letter(currencyKey()))
        assertNull(letter(KeyEvent.KEYCODE_4))
        // Doubled keycaps give their second letter on Shift; table legends ة→ء, ط→ى.
        assertEquals("ض", shifted(KeyEvent.KEYCODE_Q))
        assertEquals("ش", shifted(KeyEvent.KEYCODE_A))
        assertEquals("ء", shifted(KeyEvent.KEYCODE_B))
        assertEquals("ى", letter(currencyKey(meta = KeyEvent.META_SHIFT_ON)))
        // A single-letter key stays itself under Shift.
        assertEquals("ث", shifted(KeyEvent.KEYCODE_W))
        assertEquals('ض'.code, PhysicalKeyResolver.heldLetter(down(KeyEvent.KEYCODE_Q)))
        assertEquals(0, PhysicalKeyResolver.heldLetter(down(KeyEvent.KEYCODE_W)))
        assertTrue(PhysicalKeyResolver.isScriptLayoutActive())
    }

    @Test
    fun russianKeycapsAreCasedAndDoubleTapToTheirSecondLegend() {
        keyboard(Locale("ru"), "east_slavic")
        assertEquals("йцукенгшщх", row("QWERTYUIOP"))
        assertEquals("фывапролд", row("ASDFGHJKL"))
        assertEquals("ячсмить", row("ZXCVBNM"))
        assertEquals("б", letter(currencyKey()))
        assertEquals("Й", shifted(KeyEvent.KEYCODE_Q))
        assertArrayEquals(arrayOf("ё"), PhysicalKeyResolver.multitap("й"))
        assertArrayEquals(arrayOf("Ё"), PhysicalKeyResolver.multitap("Й"))
        assertArrayEquals(arrayOf("ю"), PhysicalKeyResolver.multitap("б"))
        assertNull(PhysicalKeyResolver.multitap("ц"))
    }

    @Test
    fun koreanHebrewGreekUkrainianAndBelarusianKeycaps() {
        keyboard(Locale.KOREAN, "hangul")
        assertEquals("ㅂㅈㄷㄱㅅㅛㅕㅑㅐㅔ", row("QWERTYUIOP"))
        assertEquals("ㅁㄴㅇㄹㅎㅗㅓㅏㅣ", row("ASDFGHJKL"))
        assertEquals("ㅋㅌㅊㅍㅠㅜㅡ", row("ZXCVBNM"))
        assertEquals("ㅃ", shifted(KeyEvent.KEYCODE_Q))
        assertNull("the Korean `\$` key stays the currency key", letter(currencyKey()))

        keyboard(Locale("iw"), "hebrew")
        assertEquals("'-קראטוןםפ", row("QWERTYUIOP"))
        assertEquals("ף", shifted(KeyEvent.KEYCODE_P))
        assertEquals("ת", letter(currencyKey()))

        keyboard(Locale("el"), "greek")
        assertEquals("ςερτυθιοπ", row("WERTYUIOP"))
        assertEquals("Ε", shifted(KeyEvent.KEYCODE_E))

        keyboard(Locale("uk"), "east_slavic")
        assertEquals("йцукенгшзх", row("QWERTYUIOP"))
        keyboard(Locale("be"), "east_slavic")
        assertEquals("йцукенгшзх", row("QWERTYUIOP"))
    }

    @Test
    fun aBuiltInLatinLetterIsTheKeyMapsButItsListsApply() {
        // Rule 2: the English document names a-z, and leaves them to the key map (AZERTY and
        // QWERTZ firmware maps, dead keys), while its hold lists still answer.
        assertNull(letter(KeyEvent.KEYCODE_E))
        assertNull(shifted(KeyEvent.KEYCODE_E))
        assertFalse(PhysicalKeyResolver.isScriptLayoutActive())
        assertArrayEquals(arrayOf("é", "è", "ê", "ë", "ē"), PhysicalKeyResolver.moreKeys("e"))
        assertArrayEquals(arrayOf("É", "È", "Ê", "Ë", "Ē"), PhysicalKeyResolver.moreKeys("E"))
        assertNull("no double tap on Latin keyboards", PhysicalKeyResolver.multitap("e"))
    }

    @Test
    fun theChineseStrokeAndZhuyinKeyboardsGetNoDocument() {
        keyboard(Locale("zh", "CN"), "pinyin")
        assertArrayEquals(arrayOf("©"), PhysicalKeyResolver.moreKeys("c"))
        keyboard(Locale("zh", "HK"), "cangjie")
        assertArrayEquals(arrayOf("©"), PhysicalKeyResolver.moreKeys("c"))
        for (set in listOf("stroke", "zhuyin")) {
            keyboard(Locale("zh", "TW"), set)
            assertNull(set, PhysicalKeyResolver.builtInLayout())
            assertNull(set, PhysicalKeyResolver.moreKeys("c"))
        }
    }

    @Test
    fun rule1_aKeycode4EntryWithoutAScanCodeIsTheCurrencyKeysOnly() {
        activeLayout = user(keys = """<key code="KEYCODE_4" keySpec="€" alt="£"/>""")
        assertEquals("€", letter(currencyKey()))
        assertNull("a Titan's 4 key stays a digit", letter(KeyEvent.KEYCODE_4))
        assertEquals('£'.code, PhysicalKeyResolver.alt(currencyKey()))
        assertEquals(0, PhysicalKeyResolver.alt(down(KeyEvent.KEYCODE_4)))
        // Without an event there is nothing to check: the entry is taken as the currency key's.
        assertEquals('£'.code, PhysicalKeyResolver.alt(KeyEvent.KEYCODE_4, -1))

        // Narrowed to a scan code, it is that switch whatever it types.
        activeLayout = user(keys = """<key code="KEYCODE_4" scanCode="5" keySpec="€"/>""")
        assertEquals("€", letter(KeyEvent.KEYCODE_4, scanCode = 5))
        assertNull(letter(KeyEvent.KEYCODE_4, scanCode = 6))
    }

    @Test
    fun rule2_aBuiltInDocumentsLatinLetterFallsThroughButAUsersApplies() {
        PhysicalKeyResolver.builtIns = builtIns(
            document("xx", """<key code="KEYCODE_Q" keySpec="q" characterMapKeys="!" moreKeys="ʠ"/><key code="KEYCODE_W" keySpec="ж"/><key code="KEYCODE_E" keySpec="-"/>""", languages = "en"),
        )
        assertNull("a Latin letter is the key map's", letter(KeyEvent.KEYCODE_Q))
        assertNull(shifted(KeyEvent.KEYCODE_Q))
        assertArrayEquals("its lists still apply", arrayOf("ʠ"), PhysicalKeyResolver.moreKeys("q"))
        assertEquals("its legend still answers", "!", PhysicalKeyResolver.shiftLegend("q"))
        assertEquals("ж", letter(KeyEvent.KEYCODE_W))
        assertEquals("punctuation is not Latin: it applies", "-", letter(KeyEvent.KEYCODE_E))

        activeLayout = user(keys = """<key code="KEYCODE_Q" keySpec="q"/>""")
        assertEquals("q", letter(KeyEvent.KEYCODE_Q))
    }

    @Test
    fun rule3_upperCaseListsFollowTheKeyboardLanguagesCaseRules() {
        PhysicalKeyResolver.builtIns = builtIns(
            document("de", """<key code="KEYCODE_S" keySpec="s" moreKeys="ß,ś"/><key code="KEYCODE_L" keySpec="l" moreKeys="l·l,ł" multitapKeys="lj"/>""", languages = "de"),
            document("tr", """<key code="KEYCODE_I" keySpec="i" moreKeys="ı,î"/>""", languages = "tr"),
            document("el", """<key code="KEYCODE_I" keySpec="ι" multitapKeys="ί,ϊ,ΐ" moreKeys="ί,ϊ,ΐ"/>""", languages = "el"),
        )
        locale = Locale.GERMAN
        assertArrayEquals(arrayOf("SS", "Ś"), PhysicalKeyResolver.moreKeys("S"))
        assertArrayEquals(arrayOf("L·L", "Ł"), PhysicalKeyResolver.moreKeys("L"))
        assertArrayEquals(arrayOf("LJ"), PhysicalKeyResolver.multitap("L"))

        // Turkish: the capital of i is İ, so İ finds the i key and I (the capital of ı) does not.
        locale = Locale("tr")
        assertArrayEquals(arrayOf("I", "Î"), PhysicalKeyResolver.moreKeys("İ"))
        assertNull(PhysicalKeyResolver.moreKeys("I"))

        // Greek keeps its tonos; ΐ has no one-letter capital and comes out as Ι, the letter
        // itself, which leaves the list.
        locale = Locale("el")
        assertArrayEquals(arrayOf("Ί", "Ϊ"), PhysicalKeyResolver.moreKeys("Ι"))
        assertArrayEquals(arrayOf("Ί", "Ϊ"), PhysicalKeyResolver.multitap("Ι"))
        assertArrayEquals(arrayOf("ί", "ϊ", "ΐ"), PhysicalKeyResolver.multitap("ι"))

        // A user layout follows the same rules.
        locale = Locale.GERMAN
        activeLayout = user(languages = "", keys = """<key code="KEYCODE_S" keySpec="s" moreKeys="ß"/>""")
        assertArrayEquals(arrayOf("SS"), PhysicalKeyResolver.moreKeys("S"))
    }

    @Test
    fun aShiftLegendIsALetterOfItsOwnNotACapital() {
        keyboard(Locale("ar"), "arabic")
        // ض is Shift on the ص key; holding it does not offer ص's list.
        assertEquals("ض", PhysicalKeyResolver.shiftLegend("ص"))
        assertNull(PhysicalKeyResolver.moreKeys("ض"))
    }

    @Test
    fun whatComesBackIsTheCallersOwnCopy() {
        PhysicalKeyResolver.builtIns = builtIns(document("en", """<key code="KEYCODE_L" keySpec="l" moreKeys="l·l,ł"/>"""))
        PhysicalKeyResolver.moreKeys("l")!![0] = "changed"
        assertArrayEquals(arrayOf("l·l", "ł"), PhysicalKeyResolver.moreKeys("l"))
    }

    // ── the switch ───────────────────────────────────────────────────────────

    @Test
    fun theAlphabetSwitchTurnsTheBuiltInDocumentsOffAndLeavesAUserLayout() {
        keyboard(Locale("ru"), "east_slavic")
        alphabetSwitch = false
        assertNull(letter(KeyEvent.KEYCODE_Q))
        assertNull(PhysicalKeyResolver.multitap("й"))
        assertNull(PhysicalKeyResolver.moreKeys("е"))
        assertFalse(PhysicalKeyResolver.isScriptLayoutActive())
        // The currency key's list does not depend on the switch.
        assertNotNull(PhysicalKeyResolver.moreKeys("₽"))

        activeLayout = user(languages = "ru", keys = """<key code="KEYCODE_Q" keySpec="ж" multitapKeys="з"/>""")
        assertEquals("ж", letter(KeyEvent.KEYCODE_Q))
        assertArrayEquals(arrayOf("з"), PhysicalKeyResolver.multitap("ж"))
    }

    // ── modifiers and the other guards ───────────────────────────────────────

    @Test
    fun aModifierOtherThanShiftOrTheSymbolPageLeavesTheKeyToTheKeyMap() {
        keyboard(Locale("ru"), "east_slavic")
        assertNull("Alt", letter(KeyEvent.KEYCODE_Q, meta = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON))
        assertNull("Ctrl", letter(KeyEvent.KEYCODE_Q, meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON))
        assertNull("Sym", letter(KeyEvent.KEYCODE_Q, meta = KeyEvent.META_SYM_ON))
        assertNull("Meta", letter(KeyEvent.KEYCODE_Q, meta = KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON))
        assertNull("Fn", letter(KeyEvent.KEYCODE_Q, meta = KeyEvent.META_FUNCTION_ON))
        assertEquals("Shift from the event", "Й", letter(KeyEvent.KEYCODE_Q, meta = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON))

        `when`(switcher.isPhysicalSymbolMappingActive).thenReturn(true)
        assertNull("the symbol page maps the keys", letter(KeyEvent.KEYCODE_Q))
        `when`(switcher.isPhysicalSymbolMappingActive).thenReturn(false)

        val classifier = mock(KeyEventDeviceClassifier::class.java)
        `when`(classifier.isPhysicalKeyboardEvent(any())).thenReturn(false)
        classifierStatic.`when`<KeyEventDeviceClassifier> { KeyEventDeviceClassifier.getInstance() }.thenReturn(classifier)
        assertNull("not a physical keyboard", letter(KeyEvent.KEYCODE_Q))
    }

    // ── Alt ──────────────────────────────────────────────────────────────────

    @Test
    fun altComesFromTheUserLayoutForTheKeysItNames() {
        activeLayout = user()
        assertEquals('#'.code, PhysicalKeyResolver.alt(KeyEvent.KEYCODE_Q, -1))
        assertEquals(0, PhysicalKeyResolver.alt(KeyEvent.KEYCODE_E, -1))
        val altQ = down(KeyEvent.KEYCODE_Q, meta = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON)
        assertEquals('#'.code, PhysicalKeyResolver.altFor(altQ, altQ.metaState))
        assertEquals("not without Alt", 0, PhysicalKeyResolver.altFor(down(KeyEvent.KEYCODE_Q), 0))
    }

    // ── double tap and hold ──────────────────────────────────────────────────

    @Test
    fun theUserLayoutsListsComeFirstAndAnEmptyOneFallsThrough() {
        activeLayout = user()
        assertArrayEquals(arrayOf("ŵ", "ẁ"), PhysicalKeyResolver.multitap("w"))
        assertArrayEquals(arrayOf("ŵ", "ẃ"), PhysicalKeyResolver.moreKeys("w"))
        // The Shift letter is the upper case: upper-case lists.
        assertArrayEquals(arrayOf("Ŵ", "Ẁ"), PhysicalKeyResolver.multitap("W"))
        // The user's q key has no lists: the English document answers for q.
        assertNull(PhysicalKeyResolver.multitap("q"))
        assertNull(PhysicalKeyResolver.moreKeys("q"))
        // e is no key of the user's: the English document's list.
        assertArrayEquals(arrayOf("é", "è", "ê", "ë", "ē"), PhysicalKeyResolver.moreKeys("e"))
        assertNull(PhysicalKeyResolver.moreKeys(null))
        assertNull(PhysicalKeyResolver.moreKeys("ab"))

        val table = PhysicalKeyResolver.multitapTable()
        assertEquals(setOf("W", "w"), table.keys)
        assertArrayEquals(arrayOf("Ŵ", "Ẁ"), table["W"])
    }

    @Test
    fun aMultiLanguageKeyboardJoinsItsLanguagesHoldListsForTheSameLetter() {
        PhysicalKeyResolver.builtIns = builtIns(
            document("en", """<key code="KEYCODE_E" keySpec="e" moreKeys="é,è"/><key code="KEYCODE_D" keySpec="d"/>"""),
            document("cs", """<key code="KEYCODE_E" keySpec="e" moreKeys="é,ě"/><key code="KEYCODE_D" keySpec="d" moreKeys="ď"/>""", languages = "cs"),
        )
        extraLocales = listOf(Locale("cs"))
        assertArrayEquals(arrayOf("é", "è", "ě"), PhysicalKeyResolver.moreKeys("e"))
        assertArrayEquals(arrayOf("É", "È", "Ě"), PhysicalKeyResolver.moreKeys("E"))
        assertArrayEquals("an empty list joins the others", arrayOf("ď"), PhysicalKeyResolver.moreKeys("d"))
        // Double taps are the primary language's alone, as they were.
        extraLocales = null
        assertArrayEquals(arrayOf("é", "è"), PhysicalKeyResolver.moreKeys("e"))
    }

    @Test
    fun theCurrencyKeyOffersTheOtherCurrenciesOnEveryKeyboard() {
        assertArrayEquals(arrayOf("€", "¥", "£", "₱", "¢"), PhysicalKeyResolver.moreKeys("$"))
        assertArrayEquals(arrayOf("$", "¥", "£", "₱", "¢"), PhysicalKeyResolver.moreKeys("€"))
        keyboard(Locale("el"), "greek")
        assertArrayEquals(arrayOf("€", "¥", "£", "₱", "¢"), PhysicalKeyResolver.moreKeys("$"))
        assertNull(PhysicalKeyResolver.moreKeys("฿"))
        // "Default currency": the setting's symbol leaves the list it would repeat, and a symbol
        // typed some other way leads its own list.
        currencySetting = "€"
        assertArrayEquals(arrayOf("$", "¥", "£", "₱", "¢"), PhysicalKeyResolver.moreKeys("€"))
        assertArrayEquals(arrayOf("$", "¥", "£", "₱", "¢"), PhysicalKeyResolver.moreKeys("$"))
        currencySetting = "£"
        assertArrayEquals(arrayOf("$", "€", "¥", "₱", "¢"), PhysicalKeyResolver.moreKeys("$"))
    }

    // ── what the rest of the app asks ────────────────────────────────────────

    /** The Language screen offers the switch only when an enabled keyboard is one of these. */
    @Test
    fun onlyKeyboardsWithTheirOwnAlphabetOfferTheSwitch() {
        for (locale in listOf("ar", "ru", "uk", "be", "iw", "he", "iw_IL", "el", "ko")) {
            assertTrue("$locale should offer it", PhysicalKeyResolver.supportsLocale(locale))
        }
        for (locale in listOf("en_US", "fr", "in", "fa", "hi", "th", "bg", "zz", "zh_CN_pinyin", "ky", "", null)) {
            assertFalse("$locale should not offer it", PhysicalKeyResolver.supportsLocale(locale))
        }
    }

    @Test
    fun theCurrencyArrangementMatchesTheOldTable() {
        val list = arrayOf("€", "¥", "£", "₱", "¢")
        assertArrayEquals(list, PhysicalKeyResolver.arrangeCurrencyList("$", list, "", "0"))
        assertArrayEquals(arrayOf("$", "€", "¥", "₱", "¢"), PhysicalKeyResolver.arrangeCurrencyList("$", list, "£", "0"))
        assertArrayEquals(arrayOf("€", "¥", "£", "₱"), PhysicalKeyResolver.arrangeCurrencyList("¢", list, "¢", "0"))
        assertArrayEquals("a list led by the 0 key's character is left alone",
            arrayOf("0", "1"), PhysicalKeyResolver.arrangeCurrencyList("$", arrayOf("0", "1"), "£", "0"))
    }

    // ── switching layouts ────────────────────────────────────────────────────

    @Test
    fun theTypingPathFollowsTheChosenLayoutAndItsFile() {
        PhysicalKeyResolver.chosenLayoutSource = { language -> LetterLayoutChoice.forTyping(context, language) }
        val registry = LetterLayoutRegistry(context)
        registry.save(user(id = "first"))
        registry.save(user(id = "second", keys = """<key code="KEYCODE_Q" keySpec="ж"/>"""))

        assertNull("nothing chosen yet", letter(KeyEvent.KEYCODE_Q))

        LetterLayoutChoice.choose(context, "en", LetterLayout.Target.PKB, "first")
        assertEquals("w", letter(KeyEvent.KEYCODE_Q))

        LetterLayoutChoice.choose(context, "en", LetterLayout.Target.PKB, "second")
        assertEquals("ж", letter(KeyEvent.KEYCODE_Q))

        // Re-importing under the same id replaces what the keys type, though the id is unchanged.
        registry.save(user(id = "second", keys = """<key code="KEYCODE_Q" keySpec="з"/>"""))
        assertEquals("з", letter(KeyEvent.KEYCODE_Q))

        // So does a write to the preference that did not come through the choice.
        PrefsManager.getPrefs(context).edit()
            .putString(LetterLayoutChoice.prefKey("en", LetterLayout.Target.PKB), "first").commit()
        assertEquals("w", letter(KeyEvent.KEYCODE_Q))

        // Deleting the chosen layout leaves the keys to the key map.
        assertTrue(registry.delete("first"))
        assertNull(letter(KeyEvent.KEYCODE_Q))
        assertEquals(listOf("second"), registry.list().map { it.id })
    }

    @Test
    fun anUnreadableChosenFileMeansNoUserLayout() {
        PhysicalKeyResolver.chosenLayoutSource = { language -> LetterLayoutChoice.forTyping(context, language) }
        val registry = LetterLayoutRegistry(context)
        registry.save(user(id = "broken"))
        registry.fileFor("broken").writeText("<letter-layout")
        LetterLayoutChoice.choose(context, "en", LetterLayout.Target.PKB, "broken")
        assertNull(letter(KeyEvent.KEYCODE_Q))
        assertTrue("the directory listing skips it rather than failing", registry.list().isEmpty())
    }

    private companion object {
        const val SWAP_KEYS = """
            <key code="KEYCODE_Q" keySpec="w" characterMapKeys="W" alt="#" multitapKeys="ŵ,ẁ" moreKeys="ŵ,ẃ"/>
            <key code="KEYCODE_W" scanCode="17" keySpec="q"/>
        """
    }
}

package dev.bbkb.ime.core.layout

import android.content.Context
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.device.detection.KeyEventDeviceClassifier
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.keyboard.KeyboardSwitcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
import java.util.Locale

/**
 * [PhysicalKeyResolver]'s precedence between the two ways a user picks a letter layout for the
 * physical keys: the layout chosen for a keyboard language on the Language screen
 * ([LetterLayoutChoice]) and the one switched on in Physical layouts. The choice wins outright for
 * its language while it is for the physical keys and fits the keyboard; everywhere else the
 * Physical layouts selection applies as it always has. The shipped documents come from the app's
 * assets; Colemak and Dvorak among them are for the on-screen keyboard only, so a copy of Colemak
 * marked for the physical keys stands for a chosen built-in that drives them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhysicalKeyResolverChoiceTest {

    private lateinit var classifierStatic: MockedStatic<KeyEventDeviceClassifier>
    private lateinit var switcherStatic: MockedStatic<KeyboardSwitcher>
    private lateinit var saved: PhysicalKeyResolver.Snapshot

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val registry by lazy { LetterLayoutRegistry(context) }

    /** The Physical layouts selection. */
    private var active: LetterLayout? = null

    /** Each language's choice. */
    private val chosen = HashMap<String, LetterLayout>()
    private val asked = ArrayList<String?>()

    private var locale: Locale? = Locale.ENGLISH
    private var keypad: String? = "qwerty"

    @Before
    fun setUp() {
        saved = PhysicalKeyResolver.Snapshot()
        PhysicalKeyResolver.chosenLayoutSource = { language -> asked += language; chosen[language] }
        PhysicalKeyResolver.userLayoutSource = { active }
        PhysicalKeyResolver.builtIns = PhysicalKeyResolver.RegistryBuiltIns(registry)
        PhysicalKeyResolver.localeSource = { locale }
        PhysicalKeyResolver.additionalLocalesSource = { null }
        PhysicalKeyResolver.keypadLayoutSource = { keypad }
        PhysicalKeyResolver.keypadTypeSource = { "4row" }
        PhysicalKeyResolver.layoutSetSource = { "qwerty" }
        PhysicalKeyResolver.alphabetSwitchSource = { true }
        PhysicalKeyResolver.currencySettingSource = { "" }
        PhysicalKeyResolver.zeroKeySource = { "0" }

        classifierStatic = mockStatic(KeyEventDeviceClassifier::class.java)
        val classifier = mock(KeyEventDeviceClassifier::class.java)
        `when`(classifier.isPhysicalKeyboardEvent(any())).thenReturn(true)
        classifierStatic.`when`<KeyEventDeviceClassifier> { KeyEventDeviceClassifier.getInstance() }.thenReturn(classifier)
        switcherStatic = mockStatic(KeyboardSwitcher::class.java)
        val switcher = mock(KeyboardSwitcher::class.java)
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

    private fun layout(id: String, keys: String, languages: String = "", keypad: String = "any", targets: String = "pkb") =
        LetterLayoutXml.parse(
            """<letter-layout version="1" id="$id" name="$id" targets="$targets">
                <bind languages="$languages" keypad="$keypad"/>
                $keys
            </letter-layout>"""
        )

    private val swap get() = layout("swap", """<key code="KEYCODE_Q" keySpec="w"/><key code="KEYCODE_W" keySpec="q"/>""")
    private val colemak get() = registry.builtIn("colemak")!!

    /**
     * The shipped Colemak marked for the physical keys: what a chosen built-in that drives them
     * would be. The shipped one is for the on-screen keyboard only.
     */
    private val colemakForKeypad
        get() = colemak.let { LetterLayout(it.id, it.name, it.origin, setOf(LetterLayout.Target.PKB), it.binding, it.rowsTemplate, it.keys) }

    private fun letter(keyCode: Int, meta: Int = 0): String? =
        PhysicalKeyResolver.letter(KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, keyCode, 0, meta, 1, 0), meta)
            .takeIf { it != 0 }?.let { String(Character.toChars(it)) }

    private fun row(keys: String): String =
        keys.map { letter(KeyEvent.keyCodeFromString("KEYCODE_$it")) ?: "·" }.joinToString("")

    @Test
    fun aLanguagesChoiceWinsOverThePhysicalLayoutsSelection_forThatLanguageOnly() {
        active = swap
        chosen["en"] = colemakForKeypad
        // English follows Colemak, letters and all; the swap is not consulted.
        assertEquals("qwfpgjluy;", row("QWERTYUIOP"))
        assertEquals("arstdhneio", row("ASDFGHJKL") + letter(KeyEvent.KEYCODE_SEMICOLON))
        assertEquals("zxcvbkm", row("ZXCVBNM"))
        assertEquals("F", letter(KeyEvent.KEYCODE_E, KeyEvent.META_SHIFT_ON))
        // German has no choice of its own: the Physical layouts selection, exactly as before.
        locale = Locale.GERMAN
        assertEquals("w", letter(KeyEvent.KEYCODE_Q))
        assertEquals("q", letter(KeyEvent.KEYCODE_W))
        assertNull("the swap names no E: the key map's", letter(KeyEvent.KEYCODE_E))
        assertEquals("the choice is asked per language", listOf("en", "de"), asked.distinct())
    }

    @Test
    fun aChosenBuiltInLayoutTypesItsLatinLettersItself() {
        // As a language's own document, a Latin letter is the key map's to type (rule 2); chosen,
        // Colemak is the user's layout and its letters apply.
        chosen["en"] = colemakForKeypad
        assertEquals("f", letter(KeyEvent.KEYCODE_E))
        assertSame(PhysicalKeyResolver.userLayout(), PhysicalKeyResolver.userLayout())
        chosen.clear()
        assertNull(letter(KeyEvent.KEYCODE_E))
    }

    @Test
    fun aChoiceThatIsNotForThePhysicalKeysLeavesThemToThePhysicalLayoutsSelection() {
        active = swap
        // The shipped Colemak is for the on-screen keyboard only: the swap types, and E is the key map's.
        chosen["en"] = colemak
        assertEquals("w", letter(KeyEvent.KEYCODE_Q))
        assertNull(letter(KeyEvent.KEYCODE_E))
        chosen["en"] = layout("screen-only", """<key code="KEYCODE_Q" keySpec="z"/>""", targets = "vkb")
        assertEquals("w", letter(KeyEvent.KEYCODE_Q))
        chosen["en"] = layout("azerty-pad", """<key code="KEYCODE_Q" keySpec="z"/>""", keypad = "azerty")
        assertEquals("bound to another keypad: the swap types", "w", letter(KeyEvent.KEYCODE_Q))
        keypad = "azerty"
        assertEquals("z", letter(KeyEvent.KEYCODE_Q))
    }

    @Test
    fun withNoChoiceAndNoSelection_theBuiltInDocumentsAndTheKeyMapAnswer() {
        assertNull(PhysicalKeyResolver.userLayout())
        assertNull(letter(KeyEvent.KEYCODE_Q))
        locale = Locale("ru")
        PhysicalKeyResolver.layoutSetSource = { "east_slavic" }
        assertEquals("й", letter(KeyEvent.KEYCODE_Q))
    }

    @Test
    fun theRealSourceReadsTheLanguagesPreference() {
        PhysicalKeyResolver.init(context)
        PhysicalKeyResolver.localeSource = { locale }
        PhysicalKeyResolver.keypadLayoutSource = { keypad }
        assertNull(letter(KeyEvent.KEYCODE_E))
        LetterLayoutChoice.choose(context, "en", "dvorak")
        assertNull("Dvorak is for the on-screen keyboard only", letter(KeyEvent.KEYCODE_E))
        registry.save(layout("dot-on-e", """<key code="KEYCODE_E" keySpec="." characterMapKeys="&gt;"/>"""))
        LetterLayoutChoice.choose(context, "en", "dot-on-e")
        assertEquals(".", letter(KeyEvent.KEYCODE_E))
        assertEquals(">", letter(KeyEvent.KEYCODE_E, KeyEvent.META_SHIFT_ON))
        LetterLayoutChoice.choose(context, "en", "")
        assertNull(letter(KeyEvent.KEYCODE_E))
    }
}

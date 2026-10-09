package dev.bbkb.ime.core.keyevent

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

/**
 * The user tier of [HardwareScriptLayouts]: a switched-on letter map drives the physical keys
 * ahead of the script layouts, on Latin keyboards too, for the keys it names and only while it is
 * bound to the current keyboard and keypad; every other key falls through to what it did before.
 *
 * The keyboard state the tier reads (the active map, the current language, the keypad) comes in
 * through the object's three sources, replaced here; the physical-keyboard check is the one static
 * the tests mock, as `KeyIdentityCharacterisationTest` does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class HardwareScriptLayoutsUserTierTest {

    private lateinit var classifierStatic: MockedStatic<KeyEventDeviceClassifier>
    private lateinit var switcherStatic: MockedStatic<KeyboardSwitcher>
    private lateinit var savedMapSource: () -> UserLetterMap?
    private lateinit var savedLanguageSource: () -> String?
    private lateinit var savedKeypadSource: () -> String?

    private var activeMap: UserLetterMap? = null
    private var language: String? = "en"
    private var keypad: String? = "qwerty"

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        savedMapSource = HardwareScriptLayouts.userMapSource
        savedLanguageSource = HardwareScriptLayouts.languageSource
        savedKeypadSource = HardwareScriptLayouts.keypadLayoutSource
        HardwareScriptLayouts.userMapSource = { activeMap }
        HardwareScriptLayouts.languageSource = { language }
        HardwareScriptLayouts.keypadLayoutSource = { keypad }

        classifierStatic = mockStatic(KeyEventDeviceClassifier::class.java)
        val classifier = mock(KeyEventDeviceClassifier::class.java)
        `when`(classifier.isPhysicalKeyboardEvent(any())).thenReturn(true)
        classifierStatic.`when`<KeyEventDeviceClassifier> { KeyEventDeviceClassifier.getInstance() }
            .thenReturn(classifier)

        // The switcher singleton exists from class load but has no keyboard state outside a
        // running IME; a mock answers "no symbol page is mapping the keys".
        switcherStatic = mockStatic(KeyboardSwitcher::class.java)
        val switcher = mock(KeyboardSwitcher::class.java)
        `when`(switcher.isPhysicalSymbolMappingActive).thenReturn(false)
        switcherStatic.`when`<KeyboardSwitcher> { KeyboardSwitcher.getInstance() }.thenReturn(switcher)
    }

    @After
    fun tearDown() {
        HardwareScriptLayouts.userMapSource = savedMapSource
        HardwareScriptLayouts.languageSource = savedLanguageSource
        HardwareScriptLayouts.keypadLayoutSource = savedKeypadSource
        classifierStatic.close()
        switcherStatic.close()
        UserLetterMapRepository.invalidate()
        PrefsManager.getPrefs(context).edit().clear().commit()
        UserLetterMapRepository.directoryOf(context).deleteRecursively()
    }

    private fun map(
        id: String = "swap",
        locales: String = "\"en\"",
        keypadLayout: String = "qwerty",
        keys: String = SWAP_KEYS,
    ): UserLetterMap = UserLetterMap.parse(
        """{"format": "bbkb-layout", "version": 1, "kind": "pkb", "id": "$id", "name": "$id",
            "bind": {"locales": [$locales], "keypadLayout": "$keypadLayout"}, "keys": $keys}"""
    )

    private fun down(keyCode: Int, scanCode: Int = 0, meta: Int = 0) =
        KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, keyCode, 0, meta, 1, scanCode)

    private fun letter(keyCode: Int, scanCode: Int = 0, meta: Int = 0, interpretedMeta: Int = meta): String? =
        HardwareScriptLayouts.letterFor(down(keyCode, scanCode, meta), interpretedMeta)
            .takeIf { it != 0 }?.let { String(Character.toChars(it)) }

    // ── letters ──────────────────────────────────────────────────────────────

    @Test
    fun theMapTypesItsBaseAndShiftLettersOnALatinKeyboard() {
        activeMap = map()
        assertEquals("w", letter(KeyEvent.KEYCODE_Q))
        assertEquals("W", letter(KeyEvent.KEYCODE_Q, interpretedMeta = KeyEvent.META_SHIFT_ON))
        assertEquals("q", letter(KeyEvent.KEYCODE_W, scanCode = 17))
        // No shift legend: the upper case of base.
        assertEquals("Q", letter(KeyEvent.KEYCODE_W, scanCode = 17, interpretedMeta = KeyEvent.META_SHIFT_ON))
        // Holding for a capital gives the Shift letter.
        assertEquals('W'.code, HardwareScriptLayouts.heldLetterFor(down(KeyEvent.KEYCODE_Q)))
    }

    @Test
    fun theMapReachesPastTheLetterKeys() {
        activeMap = map(keys = """{"KEYCODE_PERIOD": {"base": "·"}, "KEYCODE_4": {"base": "€"}}""")
        assertEquals("·", letter(KeyEvent.KEYCODE_PERIOD))
        assertEquals("€", letter(KeyEvent.KEYCODE_4))
    }

    @Test
    fun keysTheMapDoesNotNameFallThroughToTheSystemKeyMap() {
        activeMap = map()
        // "en" has no script layout, so 0 is "ask the system key map", exactly as with no map.
        assertNull(letter(KeyEvent.KEYCODE_E))
        // KEYCODE_W is narrowed to scan code 17; another switch sending it is not the map's key.
        assertNull(letter(KeyEvent.KEYCODE_W, scanCode = 18))
        // Alt is the Alt tier's business, not the letter's.
        assertNull(letter(KeyEvent.KEYCODE_Q, meta = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON))
    }

    @Test
    fun theMapAppliesOnlyToTheKeyboardsAndKeypadItIsBoundTo() {
        activeMap = map(locales = "\"de\"")
        assertNull("bound to German, typing English", letter(KeyEvent.KEYCODE_Q))
        language = "de"
        assertEquals("w", letter(KeyEvent.KEYCODE_Q))
        keypad = "qwertz"
        assertNull("written for a QWERTY keypad", letter(KeyEvent.KEYCODE_Q))

        activeMap = map(locales = "")
        keypad = "qwerty"
        language = "ar"
        assertEquals("an unbound map applies on every keyboard", "w", letter(KeyEvent.KEYCODE_Q))
    }

    @Test
    fun noMapMeansNoChange() {
        activeMap = null
        assertNull(letter(KeyEvent.KEYCODE_Q))
        assertNull(HardwareScriptLayouts.userMap())
        assertEquals(0, HardwareScriptLayouts.userAltFor(KeyEvent.KEYCODE_Q, -1))
        assertNull(HardwareScriptLayouts.alternatesFor("w"))
        assertNull(HardwareScriptLayouts.userMoreKeysFor("w"))
    }

    // ── alt, multitap, long press ────────────────────────────────────────────

    @Test
    fun altComesFromTheMapForTheKeysItNames() {
        activeMap = map()
        assertEquals('#'.code, HardwareScriptLayouts.userAltFor(KeyEvent.KEYCODE_Q, -1))
        assertEquals(0, HardwareScriptLayouts.userAltFor(KeyEvent.KEYCODE_E, -1))
        val altQ = down(KeyEvent.KEYCODE_Q, meta = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON)
        assertEquals('#'.code, HardwareScriptLayouts.userAltForEvent(altQ, altQ.metaState))
        assertEquals("not without Alt", 0, HardwareScriptLayouts.userAltForEvent(down(KeyEvent.KEYCODE_Q), 0))
    }

    @Test
    fun multitapAndLongPressComeFromTheMap() {
        activeMap = map()
        assertArrayEquals(arrayOf("ŵ", "ẁ"), HardwareScriptLayouts.alternatesFor("w"))
        assertArrayEquals(arrayOf("ŵ", "ẃ"), HardwareScriptLayouts.userMoreKeysFor("w"))
        // A letter the map has no sequence for falls through (no script layout for "en": null).
        assertNull(HardwareScriptLayouts.alternatesFor("e"))
    }

    // ── the swipe question ───────────────────────────────────────────────────

    @Test
    fun swipeTypingIsBlockedOnlyWhileTheMapChangesALetter() {
        activeMap = map()
        assertTrue(HardwareScriptLayouts.userMapChangesLetters())

        activeMap = map(keys = """{"KEYCODE_Q": {"base": "q", "alt": "#"}, "KEYCODE_PERIOD": {"base": "·"}}""")
        assertFalse("alt and punctuation changes leave the letters alone", HardwareScriptLayouts.userMapChangesLetters())

        activeMap = map(locales = "\"fr\"")
        assertFalse("not bound to this keyboard", HardwareScriptLayouts.userMapChangesLetters())

        activeMap = null
        assertFalse(HardwareScriptLayouts.userMapChangesLetters())
    }

    // ── switching maps ───────────────────────────────────────────────────────

    @Test
    fun theTypingPathFollowsTheActiveMapAndItsFile() {
        HardwareScriptLayouts.userMapSource = { UserLetterMapRepository.activeForTyping(context) }
        val repository = UserLetterMapRepository(context)
        repository.save(map(id = "first"))
        repository.save(map(id = "second", keys = """{"KEYCODE_Q": {"base": "ж"}}"""))

        assertNull("nothing switched on yet", letter(KeyEvent.KEYCODE_Q))

        repository.activeId = "first"
        assertEquals("w", letter(KeyEvent.KEYCODE_Q))

        repository.activeId = "second"
        assertEquals("ж", letter(KeyEvent.KEYCODE_Q))

        // Re-importing under the same id replaces what the keys type, though the id is unchanged.
        repository.save(map(id = "second", keys = """{"KEYCODE_Q": {"base": "з"}}"""))
        assertEquals("з", letter(KeyEvent.KEYCODE_Q))

        // So does a write to the preference that did not come through the repository.
        PrefsManager.getPrefs(context).edit()
            .putString(UserLetterMapRepository.PREF_ACTIVE_LETTER_MAP, "first").commit()
        assertEquals("w", letter(KeyEvent.KEYCODE_Q))

        // Deleting the active map switches it off.
        assertTrue(repository.delete("first"))
        assertEquals("", repository.activeId)
        assertNull(letter(KeyEvent.KEYCODE_Q))
        assertEquals(listOf("second"), repository.list().map { it.id })
    }

    @Test
    fun anUnreadableActiveFileMeansNoMap() {
        HardwareScriptLayouts.userMapSource = { UserLetterMapRepository.activeForTyping(context) }
        val repository = UserLetterMapRepository(context)
        repository.save(map(id = "broken"))
        repository.fileFor("broken").writeText("{ not json")
        repository.activeId = "broken"
        assertNull(letter(KeyEvent.KEYCODE_Q))
        assertNotNull("the directory listing skips it rather than failing", repository.list())
        assertTrue(repository.list().isEmpty())
    }

    private companion object {
        const val SWAP_KEYS = """{
            "KEYCODE_Q": {"base": "w", "shift": "W", "alt": "#", "multitap": ["ŵ", "ẁ"], "moreKeys": ["ŵ", "ẃ"]},
            "KEYCODE_W": {"scanCode": 17, "base": "q"}
        }"""
    }
}

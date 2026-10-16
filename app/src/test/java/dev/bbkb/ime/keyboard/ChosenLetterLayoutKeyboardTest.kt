package dev.bbkb.ime.keyboard

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodSubtype
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.layout.LetterLayout
import dev.bbkb.ime.core.layout.LetterLayoutChoice
import dev.bbkb.ime.core.layout.LetterLayoutRegistry
import dev.bbkb.ime.core.locale.ResourceLocaleUtils
import dev.bbkb.ime.core.locale.RichInputMethodManager
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.keyboard.inputboard.BoardKeyboardFactory
import dev.bbkb.ime.keyboard.inputboard.emoji.EmojiTestEnv
import dev.bbkb.ime.keyboard.internal.KeyboardId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The on-screen keyboard built from the letter layout chosen for its language
 * ([KeyboardBuilder.Builder.useChosenLetterLayout], [LetterLayoutChoice.keyboardLayoutSet]): the
 * real keyboard XML parsed headless, so the drawn rows are what the generated `rowkeys_*` files and
 * the hand-kept `rows_*` wrappers produce.
 *
 * An English keyboard with Colemak chosen draws Colemak, ten keys in the middle row within the
 * keyboard's width; with Dvorak, the punctuation keys first and q and z on the bottom row (the
 * shared comma and period keys switch on the layout set the keyboard is built from). Without a
 * choice, or with a custom layout, it draws its own QWERTY. The keyboard's identity carries the
 * layout set, so the cache never hands back the other layout's keyboard.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChosenLetterLayoutKeyboardTest {

    private val app: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        ResourceLocaleUtils.reinit(app)
        RichInputMethodManager.init(app)
        EmojiTestEnv.useSettings(EmojiTestEnv.settings())
        KeyboardBuilder.clearKeyboardCache()
    }

    @After
    fun tearDown() {
        KeyboardBuilder.clearKeyboardCache()
        PrefsManager.getPrefs(app).edit().clear().commit()
        LetterLayoutRegistry.directoryOf(app).deleteRecursively()
        LetterLayoutRegistry.invalidate()
    }

    private val english: InputMethodSubtype = InputMethodSubtype.InputMethodSubtypeBuilder()
        .setSubtypeLocale("en_US")
        .setSubtypeMode("keyboard")
        .setSubtypeExtraValue("KeyboardLayoutSet=qwerty")
        .setIsAsciiCapable(true)
        .build()

    private fun alphabet(): Keyboard {
        val editor = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }
        return KeyboardBuilder.Builder(BoardKeyboardFactory.themedContext(app), editor)
            .setKeyboardGeometry(1080, 900)
            .setSubtype(english)
            .useChosenLetterLayout()
            .build()
            .getKeyboard(0)
    }

    /** The keys of each row, top to bottom, left to right, by label; keys without a label skipped. */
    private fun rows(keyboard: Keyboard): List<String> =
        keyboard.keys.filter { !it.label.isNullOrEmpty() && it.label.length == 1 }
            .groupBy { it.y }.toSortedMap().values
            .map { row -> row.sortedBy { it.x }.joinToString("") { it.label } }

    @Test
    fun withNoChoice_theKeyboardIsItsOwnQwerty() {
        val keyboard = alphabet()
        assertEquals("qwerty", keyboard.mId.keyboardLayoutSet())
        assertTrue(rows(keyboard).contains("qwertyuiop"))
        assertTrue(rows(keyboard).contains("asdfghjkl"))
    }

    @Test
    fun colemakChosen_drawsColemakWithTenKeysInTheMiddleRow() {
        LetterLayoutChoice.choose(app, "en", LetterLayout.Target.VKB, "colemak")
        val keyboard = alphabet()
        assertEquals("colemak", keyboard.mId.keyboardLayoutSet())
        val rows = rows(keyboard)
        assertTrue(rows.toString(), rows.contains("qwfpgjluy;"))
        assertTrue(rows.toString(), rows.contains("arstdhneio"))
        assertTrue(rows.toString(), rows.any { it.startsWith("zxcvbkm") })
        val middle = keyboard.keys.filter { it.label == "a" || it.label == "o" }
        assertTrue("the middle row fits the keyboard", middle.all { it.x + it.width <= keyboard.mOccupiedWidth })
    }

    @Test
    fun dvorakChosen_drawsItsPunctuationAndPutsQAndZOnTheBottomRow() {
        LetterLayoutChoice.choose(app, "en", LetterLayout.Target.VKB, "dvorak")
        val keyboard = alphabet()
        val rows = rows(keyboard)
        assertTrue(rows.toString(), rows.contains("',.pyfgcrl"))
        assertTrue(rows.toString(), rows.contains("aoeuidhtns"))
        assertTrue(rows.toString(), rows.any { it.startsWith("jkxbmwv") })
        assertTrue("q and z on the bottom row: $rows", rows.any { it.contains("q") && it.contains("z") })
    }

    @Test
    fun aCustomLayoutLeavesTheOnScreenKeyboardAlone() {
        LetterLayoutRegistry(app).save(
            dev.bbkb.ime.core.layout.LetterLayoutXml.parse(
                """<letter-layout version="1" id="mine" name="Mine" targets="pkb,vkb"><bind/><rows template="qwerty"/>
                    <key code="KEYCODE_Q" keySpec="w"/></letter-layout>"""
            )
        )
        LetterLayoutChoice.choose(app, "en", LetterLayout.Target.VKB, "mine")
        assertEquals("qwerty", alphabet().mId.keyboardLayoutSet())
    }

    @Test
    fun theLayoutSetIsPartOfTheKeyboardsIdentity() {
        val qwerty = alphabet()
        LetterLayoutChoice.choose(app, "en", LetterLayout.Target.VKB, "colemak")
        val colemak = alphabet()
        assertNotEquals("not the cached QWERTY keyboard", qwerty.mId, colemak.mId)
        assertTrue(rows(colemak).contains("qwfpgjluy;"))
        LetterLayoutChoice.choose(app, "en", LetterLayout.Target.VKB, "")
        assertEquals(qwerty.mId, alphabet().mId)
    }

    @Test
    fun aKeyboardIdWithoutALayoutSetFallsBackToItsSubtypes() {
        val params = KeyboardBuilder.Params()
        params.mEditorInfo = EditorInfo()
        params.mSubtype = english
        assertEquals("qwerty", KeyboardId(0, params).keyboardLayoutSet())
    }
}

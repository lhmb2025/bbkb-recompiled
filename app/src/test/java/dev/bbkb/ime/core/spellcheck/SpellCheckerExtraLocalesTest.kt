package dev.bbkb.ime.core.spellcheck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Which other languages a spell-check session also accepts words from, given the keyboard the user
 * is typing on. It used to be the keyboard's extras only when the session's language was the
 * keyboard's layout language; a session in one of the extras was checked against that language
 * alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SpellCheckerExtraLocalesTest {

    private val en = Locale("en", "US")
    private val de = Locale("de")
    private val fr = Locale("fr")
    private val it = Locale("it")

    private fun extras(session: Locale, primary: Locale, additional: Set<Locale>?) =
        AndroidSpellCheckerService.extraLocalesForSession(session, primary, additional)?.toList()

    @Test
    fun aSessionInTheLayoutLanguage_getsTheExtras() {
        assertEquals(listOf(de, fr), extras(en, en, linkedSetOf(de, fr)))
    }

    @Test
    fun aSessionInAnExtraLanguage_getsTheLayoutLanguageAndTheOtherExtras() {
        assertEquals(listOf(en, fr), extras(de, en, linkedSetOf(de, fr)))
        assertEquals(listOf(en, de), extras(fr, en, linkedSetOf(de, fr)))
    }

    @Test
    fun theKeyboardsOrderIsKept() {
        assertEquals(listOf(en, it, de), extras(fr, en, linkedSetOf(it, fr, de)))
    }

    @Test
    fun aSessionInALanguageTheKeyboardDoesNotHave_getsNothing() {
        assertNull(extras(it, en, linkedSetOf(de, fr)))
    }

    @Test
    fun aKeyboardWithoutExtras_givesNothing() {
        assertNull(extras(en, en, null))
        assertNull(extras(en, en, emptySet()))
    }
}

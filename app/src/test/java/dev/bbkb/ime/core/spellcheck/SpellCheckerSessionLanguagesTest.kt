package dev.bbkb.ime.core.spellcheck

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Which languages a spell-check session checks words against, given the keyboard the user is
 * typing on and the installed packs, and which the engine keeps loaded across sessions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SpellCheckerSessionLanguagesTest {

    private val en = Locale("en", "US")
    private val de = Locale("de")
    private val fr = Locale("fr")
    private val it = Locale("it")
    private val es = Locale("es")

    private fun languages(session: Locale, primary: Locale, additional: Set<Locale>?, installed: Set<Locale> = setOf(en, de, fr, it, es)) =
        AndroidSpellCheckerService.languagesForSession(session, primary, additional) { it in installed }

    @Test
    fun aSessionInTheLayoutLanguage_getsTheExtras() {
        assertEquals(listOf(en, de, fr), languages(en, en, linkedSetOf(de, fr)))
    }

    @Test
    fun aSessionInAnExtraLanguage_comesFirst_thenTheLayoutLanguageAndTheOtherExtras() {
        assertEquals(listOf(de, en, fr), languages(de, en, linkedSetOf(de, fr)))
        assertEquals(listOf(fr, en, de), languages(fr, en, linkedSetOf(de, fr)))
    }

    @Test
    fun theKeyboardsOrderIsKept() {
        assertEquals(listOf(fr, en, it, de), languages(fr, en, linkedSetOf(it, fr, de)))
    }

    @Test
    fun aSessionInALanguageTheKeyboardDoesNotHave_getsItsOwnOnly() {
        assertEquals(listOf(it), languages(it, en, linkedSetOf(de, fr)))
    }

    @Test
    fun aKeyboardWithoutExtras_givesTheSessionsLanguageOnly() {
        assertEquals(listOf(en), languages(en, en, null))
        assertEquals(listOf(en), languages(en, en, emptySet()))
    }

    @Test
    fun aSessionWhoseLanguageIsNotInstalled_getsNothing() {
        assertEquals(emptyList<Locale>(), languages(it, en, linkedSetOf(it), installed = setOf(en)))
    }

    @Test
    fun keyboardLanguagesWithoutAPack_areLeftOut() {
        assertEquals(listOf(en, fr), languages(en, en, linkedSetOf(de, fr), installed = setOf(en, fr)))
    }

    @Test
    fun merging_putsTheWantedFirst_andKeepsWhatWasLoaded() {
        assertEquals(listOf(fr, en, de), AndroidSpellCheckerService.mergeLanguages(listOf(en, de), listOf(fr), 4))
    }

    @Test
    fun merging_aWantedLanguageAlreadyLoaded_isNotRepeated() {
        assertEquals(listOf(de, en), AndroidSpellCheckerService.mergeLanguages(listOf(en, de), listOf(de), 4))
    }

    @Test
    fun merging_dropsTheOldestPastTheLimit() {
        assertEquals(listOf(es, en, de, fr), AndroidSpellCheckerService.mergeLanguages(listOf(en, de, fr, it), listOf(es), 4))
    }

    @Test
    fun merging_neverDropsWhatTheRequestNeeds() {
        assertEquals(listOf(en, de, fr, it, es), AndroidSpellCheckerService.mergeLanguages(listOf(en), listOf(en, de, fr, it, es), 4))
    }

    @Test
    fun merging_withNothingLoaded_isTheWantedList() {
        assertEquals(listOf(de, en), AndroidSpellCheckerService.mergeLanguages(null, listOf(de, en), 4))
    }
}

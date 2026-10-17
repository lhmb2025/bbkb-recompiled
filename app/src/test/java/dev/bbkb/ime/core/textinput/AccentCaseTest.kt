package dev.bbkb.ime.core.textinput

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** The accent bar's pick keeps the case of the letter it replaces ([InputLogic.matchCase]). */
class AccentCaseTest {

    @Test
    fun aCapitalKeepsItsCase() {
        assertEquals("Қ", InputLogic.matchCase("К", "қ", Locale("kk")))
        assertEquals("É", InputLogic.matchCase("E", "é", Locale.ENGLISH))
        assertEquals("İ", InputLogic.matchCase("I", "i", Locale("tr")))
    }

    @Test
    fun aSmallLetterStaysSmall() {
        assertEquals("қ", InputLogic.matchCase("к", "қ", Locale("kk")))
        assertEquals("é", InputLogic.matchCase("e", "é", Locale.ENGLISH))
    }

    @Test
    fun theBarsListFollowsTheTypedLettersCase() {
        assertEquals(listOf("Қ", "€"), InputLogic.matchCase("К", listOf("қ", "€"), Locale("kk")))
        assertEquals(listOf("қ"), InputLogic.matchCase("к", listOf("қ"), Locale("kk")))
        assertEquals(listOf("қ"), InputLogic.matchCase("", listOf("қ"), Locale("kk")))
        assertEquals(listOf("қ"), InputLogic.matchCase(null, listOf("қ"), Locale("kk")))
    }

    @Test
    fun whatIsNotALetterIsLeftAlone() {
        assertEquals("€", InputLogic.matchCase("\$", "€", Locale.ENGLISH))
        assertEquals("€", InputLogic.matchCase("E", "€", Locale.ENGLISH))
        assertEquals("ß", InputLogic.matchCase("S", "ß", Locale.GERMAN))
        assertEquals("é", InputLogic.matchCase("", "é", Locale.ENGLISH))
    }
}

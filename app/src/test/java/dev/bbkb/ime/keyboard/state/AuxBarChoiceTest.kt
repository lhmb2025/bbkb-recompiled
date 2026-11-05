package dev.bbkb.ime.keyboard.state

import dev.bbkb.ime.keyboard.state.AuxBarChoice.FieldBar
import dev.bbkb.ime.keyboard.state.AuxBarChoice.Shows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The whole of [AuxBarChoice]: what the bar shows for every combination of its inputs. */
class AuxBarChoiceTest {

    @Test
    fun withNothingChosenAndNoBoardTheBarIsTheFieldsOwn() {
        assertEquals(Shows.STRIP, AuxBarChoice.shows(false, FieldBar.STRIP, false, true))
        assertEquals(Shows.MENU, AuxBarChoice.shows(false, FieldBar.MENU, false, true))
        assertEquals(Shows.NONE, AuxBarChoice.shows(false, FieldBar.NONE, false, true))
    }

    @Test
    fun aChosenMenuShowsWhateverTheFieldsBar() {
        for (field in FieldBar.values()) {
            assertEquals("field $field", Shows.MENU, AuxBarChoice.shows(true, field, false, true))
        }
    }

    @Test
    fun anOpenBoardBringsTheMenuWhenTheMenuIsEnabled() {
        for (field in FieldBar.values()) {
            for (chosen in listOf(false, true)) {
                assertEquals("field $field chosen $chosen", Shows.MENU,
                    AuxBarChoice.shows(chosen, field, true, true))
            }
        }
    }

    /** With the menu off, a board opens under whatever the bar shows (the menu-off route). */
    @Test
    fun anOpenBoardWithTheMenuOffLeavesTheFieldsBar() {
        assertEquals(Shows.STRIP, AuxBarChoice.shows(false, FieldBar.STRIP, true, false))
        assertEquals(Shows.NONE, AuxBarChoice.shows(false, FieldBar.NONE, true, false))
    }

    /** The show-menu key can raise the menu over a disabled menu; the choice keeps it up. */
    @Test
    fun aChosenMenuShowsEvenWithTheMenuSettingOff() {
        assertEquals(Shows.MENU, AuxBarChoice.shows(true, FieldBar.STRIP, false, false))
    }

    @Test
    fun choosingTheMenuAndPuttingItAwayFlipTheFlag() {
        val choice = AuxBarChoice()
        assertFalse(choice.isMenuChosen)

        choice.chooseMenu()
        assertTrue(choice.isMenuChosen)
        assertEquals(Shows.MENU, choice.shows(FieldBar.STRIP, false, true))

        choice.chooseFieldBar()
        assertFalse(choice.isMenuChosen)
        assertEquals(Shows.STRIP, choice.shows(FieldBar.STRIP, false, true))
    }
}

package dev.bbkb.ime.core.settings.screens

import android.content.Context
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.R
import dev.bbkb.ime.core.layout.LetterLayout
import dev.bbkb.ime.core.layout.LetterLayoutChoice
import dev.bbkb.ime.core.layout.LetterLayoutRegistry
import dev.bbkb.ime.core.layout.LetterLayoutXml
import dev.bbkb.ime.core.settings.ui.BlackBerryTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A keyboard's Layout on the Language screen: what each row of the list says
 * ([layoutChoiceRows]) on a phone with physical keys and on a touch-only one, and the row and
 * dialog as they render ([KeyboardLayoutRow], [KeyboardLayoutDialog]): the chosen layout's name
 * under "Layout", one radio row per layout with its reach, the chosen one selected, a tap choosing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class KeyboardLayoutChoiceTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun s(id: Int) = context.getString(id)

    private val texts get() = LayoutChoiceTexts(
        ownTitle = "QWERTY",
        ownSummary = s(R.string.settings_pkb_layouts_none_summary),
        both = s(R.string.languages_hub_layout_both),
        onScreen = s(R.string.settings_keyboard_title),
        physical = s(R.string.languages_hub_layout_physical),
    )

    private val registry by lazy { LetterLayoutRegistry(context) }

    private fun custom(targets: String) = LetterLayoutXml.parse(
        """<letter-layout version="1" id="mine" name="Mine" targets="$targets"><bind/>
            <key code="KEYCODE_Q" keySpec="w"/></letter-layout>"""
    )

    private fun options(vararg layouts: LetterLayout) =
        listOf(LetterLayoutChoice.Option("", null)) + layouts.map { LetterLayoutChoice.Option(it.id, it) }

    private val drawsOnScreen = { layout: LetterLayout -> LetterLayoutChoice.drawsOnScreen(context, layout) }

    @Test
    fun eachRowSaysWhichKeyboardsTheLayoutChanges() {
        val colemak = registry.builtIn("colemak")!!
        // The shipped Colemak marked for the physical keys too: what a built-in for both would read.
        val both = LetterLayout(
            colemak.id, colemak.name, colemak.origin, setOf(LetterLayout.Target.PKB, LetterLayout.Target.VKB),
            colemak.binding, colemak.rowsTemplate, colemak.keys,
        )
        val rows = layoutChoiceRows(options(colemak, both, custom("pkb"), custom("pkb,vkb")), texts, physicalKeys = true, drawsOnScreen)
        assertEquals(
            listOf(
                LayoutChoiceRow("", "QWERTY", "Uses the keyboard's own layout"),
                // The shipped Colemak and Dvorak are for the on-screen keyboard only.
                LayoutChoiceRow("colemak", "Colemak", "On-screen keyboard"),
                LayoutChoiceRow("colemak", "Colemak", "On-screen keyboard and physical keys"),
                LayoutChoiceRow("mine", "Mine", "Physical keys only"),
                // A custom layout for the screen has no rows of its own yet: the keys only.
                LayoutChoiceRow("mine", "Mine", "Physical keys only"),
            ),
            rows,
        )
        val touchOnly = layoutChoiceRows(options(colemak, both), texts, physicalKeys = false, drawsOnScreen)
        assertEquals("On-screen keyboard", touchOnly[1].summary)
        assertEquals("On-screen keyboard", touchOnly[2].summary)
    }

    @Test
    fun theRowShowsTheChosenLayoutUnderLayout_andOpensTheList() {
        var opened = 0
        composeRule.setContent {
            BlackBerryTheme {
                KeyboardLayoutRow(current = LayoutChoiceRow("colemak", "Colemak", texts.both), onClick = { opened++ })
            }
        }
        composeRule.onNodeWithText(s(R.string.languages_hub_layout_title)).assertExists()
        composeRule.onNodeWithText("Colemak").assertExists().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun theDialogListsEveryLayoutWithItsReach_andATapChoosesOne() {
        val rows = listOf(
            LayoutChoiceRow("", "QWERTY", texts.ownSummary),
            LayoutChoiceRow("colemak", "Colemak", texts.both),
            LayoutChoiceRow("mine", "Mine", texts.physical),
        )
        var chosen: String? = null
        composeRule.setContent {
            BlackBerryTheme {
                KeyboardLayoutDialog(rows = rows, selectedId = "", onSelect = { chosen = it }, onDismiss = {})
            }
        }
        composeRule.onNodeWithText(s(R.string.languages_hub_layout_title)).assertExists()
        composeRule.onNodeWithText("Uses the keyboard's own layout", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("On-screen keyboard and physical keys", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Physical keys only", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText(s(R.string.cancel)).assertExists()
        assertNull(chosen)
        composeRule.onNodeWithText("Mine").performClick()
        assertEquals("mine", chosen)
    }

    @Test
    fun theChosenRowIsSelected() {
        val rows = listOf(
            LayoutChoiceRow("", "QWERTY", texts.ownSummary),
            LayoutChoiceRow("dvorak", "Dvorak", texts.both),
        )
        composeRule.setContent {
            BlackBerryTheme {
                KeyboardLayoutDialog(rows = rows, selectedId = "dvorak", onSelect = {}, onDismiss = {})
            }
        }
        composeRule.onNode(hasText("Dvorak"), useUnmergedTree = false).assertIsSelected()
        composeRule.onNode(hasText("QWERTY"), useUnmergedTree = false).assertIsNotSelected()
    }
}

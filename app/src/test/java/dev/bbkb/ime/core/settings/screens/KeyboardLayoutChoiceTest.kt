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
 * A keyboard's layout lists on the Language screen: what each row says ([layoutChoiceRows]), and
 * the row and dialog as they render ([KeyboardLayoutRow], [KeyboardLayoutDialog]): the chosen
 * layout's name under its keyboard, one radio row per layout, the chosen one selected, a tap
 * choosing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class KeyboardLayoutChoiceTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun s(id: Int) = context.getString(id)

    private val ownSummary get() = s(R.string.settings_pkb_layouts_none_summary)

    private val registry by lazy { LetterLayoutRegistry(context) }

    private fun custom(targets: String) = LetterLayoutXml.parse(
        """<letter-layout version="1" id="mine" name="Mine" targets="$targets"><bind/>
            <key code="KEYCODE_Q" keySpec="w"/></letter-layout>"""
    )

    private fun options(vararg layouts: LetterLayout) =
        listOf(LetterLayoutChoice.Option("", null)) + layouts.map { LetterLayoutChoice.Option(it.id, it) }

    @Test
    fun theDefaultRowSaysSo_andEveryOtherRowIsTheLayoutsName() {
        val colemak = registry.builtIn("colemak")!!
        val rows = layoutChoiceRows(options(colemak, custom("pkb")), ownTitle = "QWERTY", ownSummary = ownSummary)
        assertEquals(
            listOf(
                LayoutChoiceRow("", "QWERTY", "Uses the keyboard's own layout"),
                LayoutChoiceRow("colemak", "Colemak"),
                LayoutChoiceRow("mine", "Mine"),
            ),
            rows,
        )
    }

    @Test
    fun theRowShowsTheChosenLayoutUnderItsKeyboard_andOpensTheList() {
        var opened = 0
        composeRule.setContent {
            BlackBerryTheme {
                KeyboardLayoutRow(title = s(R.string.settings_keyboard_title), current = LayoutChoiceRow("colemak", "Colemak"), onClick = { opened++ })
            }
        }
        composeRule.onNodeWithText(s(R.string.settings_keyboard_title)).assertExists()
        composeRule.onNodeWithText("Colemak").assertExists().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun theDialogListsEveryLayout_andATapChoosesOne() {
        val rows = listOf(
            LayoutChoiceRow("", "QWERTY", ownSummary),
            LayoutChoiceRow("colemak", "Colemak"),
            LayoutChoiceRow("mine", "Mine"),
        )
        var chosen: String? = null
        composeRule.setContent {
            BlackBerryTheme {
                KeyboardLayoutDialog(title = s(R.string.settings_physical_keyboard_title), rows = rows, selectedId = "", onSelect = { chosen = it }, onDismiss = {})
            }
        }
        composeRule.onNodeWithText(s(R.string.settings_physical_keyboard_title)).assertExists()
        composeRule.onNodeWithText("Uses the keyboard's own layout", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Colemak", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText(s(R.string.cancel)).assertExists()
        assertNull(chosen)
        composeRule.onNodeWithText("Mine").performClick()
        assertEquals("mine", chosen)
    }

    @Test
    fun theChosenRowIsSelected() {
        val rows = listOf(
            LayoutChoiceRow("", "QWERTY", ownSummary),
            LayoutChoiceRow("dvorak", "Dvorak"),
        )
        composeRule.setContent {
            BlackBerryTheme {
                KeyboardLayoutDialog(title = s(R.string.settings_keyboard_title), rows = rows, selectedId = "dvorak", onSelect = {}, onDismiss = {})
            }
        }
        composeRule.onNode(hasText("Dvorak"), useUnmergedTree = false).assertIsSelected()
        composeRule.onNode(hasText("QWERTY"), useUnmergedTree = false).assertIsNotSelected()
    }
}

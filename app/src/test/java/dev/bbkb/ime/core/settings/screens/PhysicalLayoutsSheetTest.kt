package dev.bbkb.ime.core.settings.screens

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.layout.LetterLayout
import dev.bbkb.ime.core.layout.LetterLayoutRegistry
import dev.bbkb.ime.core.layout.LetterLayoutXml
import dev.bbkb.ime.core.settings.search.LocalSettingsHighlight
import dev.bbkb.ime.core.settings.search.SettingsHighlightController
import dev.bbkb.ime.core.settings.ui.BlackBerryTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.InputStream

/**
 * [PhysicalLayoutsScreen]'s sheet: tapping a row opens it with the layout's name and its keypad
 * drawn, and the actions the layout allows — Save as a file for a built-in; Share, Rename and
 * Delete for one of the user's — with Delete going through its confirmation to the store.
 *
 * The Rename dialog itself is not driven: a Material 3 text field in an `AlertDialog` never idles
 * under this Robolectric/Compose combination (see `CustomMacrosScreenTest`). What it stores is
 * pinned by `PhysicalLayoutsScreenTest` through [renameLayout].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class PhysicalLayoutsSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var registry: LetterLayoutRegistry

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        registry = LetterLayoutRegistry(context, OneBuiltIn)
        registry.directory.deleteRecursively()
    }

    /** The render test expects a fresh install's empty custom list; share its sandbox cleanly. */
    @After
    fun tearDown() {
        registry.directory.deleteRecursively()
    }

    private fun show() {
        composeRule.setContent {
            BlackBerryTheme {
                CompositionLocalProvider(LocalSettingsHighlight provides SettingsHighlightController()) {
                    PhysicalLayoutsScreen({}, builtInDocuments = OneBuiltIn)
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Lets the sheet finish sliding in, so a click lands on the row it names, not where the row was. */
    private fun settle() {
        composeRule.mainClock.advanceTimeBy(2_000)
        composeRule.waitForIdle()
    }

    @Test
    fun aBuiltInsSheetDrawsItsKeysAndOffersSaveOnly() {
        show()
        waitForText("Built-in sample")
        composeRule.onNodeWithText("Built-in sample").performClick()
        settle()

        composeRule.onNodeWithText("Save as a file").assertIsDisplayed()
        // The Q key types Ж; its cap shows the Shift legend, and the Alt character in the corner.
        composeRule.onNodeWithText("Ж").assertExists()
        composeRule.onNodeWithText("7").assertExists()
        assertEquals(0, composeRule.onAllNodes(hasText("Rename")).fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodes(hasText("Delete")).fetchSemanticsNodes().size)
    }

    @Test
    fun aCustomLayoutsSheetOffersShareRenameAndDeleteAndDeleteRemovesIt() {
        registry.save(
            LetterLayoutXml.parse(
                """<letter-layout version="1" id="mine" name="My layout"><bind/>
                    <key code="KEYCODE_Y" keySpec="z"/><key code="KEYCODE_Z" keySpec="y"/></letter-layout>""",
                LetterLayout.Origin.USER,
            )
        )
        show()
        waitForText("My layout")
        composeRule.onNodeWithText("My layout").performClick()
        settle()

        composeRule.onNodeWithText("Share").assertIsDisplayed()
        composeRule.onNodeWithText("Rename").assertIsDisplayed()
        composeRule.onNodeWithText("Z").assertExists()
        assertEquals(0, composeRule.onAllNodes(hasText("Save as a file")).fetchSemanticsNodes().size)

        // By its click action rather than a synthesised touch: under Robolectric the sheet's
        // swipe-to-dismiss handling eats an injected tap on its rows.
        composeRule.onNodeWithText("Delete").performSemanticsAction(SemanticsActions.OnClick)
        settle()
        // The dialog's title and its confirm button both read "Delete layout".
        composeRule.onNode(hasText("Delete layout") and hasClickAction()).performClick()
        composeRule.waitUntil(5_000) { registry.list().isEmpty() }
        assertEquals(emptyList<String>(), registry.list().map { it.id })
    }

    /**
     * The magnifier searches both lists by name. While a search is on only the matches are listed:
     * no Add row, no notes, and a group with no match loses its header.
     */
    @Test
    fun searchNarrowsBothListsToTheMatchingLayouts() {
        registry.save(
            LetterLayoutXml.parse(
                """<letter-layout version="1" id="mine" name="My layout"><bind/>
                    <key code="KEYCODE_Y" keySpec="z"/></letter-layout>""",
                LetterLayout.Origin.USER,
            )
        )
        show()
        waitForText("My layout")
        waitForText("Built-in sample")

        composeRule.onNodeWithContentDescription("Search").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("sample")
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Built-in sample").assertExists()
        composeRule.onNodeWithText("My layout").assertDoesNotExist()
        composeRule.onNodeWithText("Add layout").assertDoesNotExist()
        composeRule.onNodeWithText("Custom").assertDoesNotExist()

        composeRule.onNode(hasSetTextAction()).performTextReplacement("zzz")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("No matches found").assertExists()
        composeRule.onNodeWithText("Built-in sample").assertDoesNotExist()
    }

    private object OneBuiltIn : LetterLayoutRegistry.BuiltInDocuments {
        private const val ID = "sample"
        private const val DOCUMENT = """<letter-layout version="1" id="sample" name="Built-in sample" targets="pkb">
            <bind/>
            <key code="KEYCODE_Q" keySpec="ж" alt="7"/>
            <key code="KEYCODE_A" keySpec="q"/>
        </letter-layout>"""

        override fun ids(): List<String> = listOf(ID)

        override fun open(id: String): InputStream? = if (id == ID) DOCUMENT.byteInputStream() else null
    }
}

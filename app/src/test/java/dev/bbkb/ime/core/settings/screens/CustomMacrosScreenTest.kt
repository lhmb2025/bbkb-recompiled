package dev.bbkb.ime.core.settings.screens

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.settings.search.LocalSettingsHighlight
import dev.bbkb.ime.core.settings.search.SettingsHighlightController
import dev.bbkb.ime.core.settings.ui.BlackBerryTheme
import dev.bbkb.ime.personaldictionary.macro.CustomMacroRepository
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins [CustomMacrosScreen] with seeded `custom_macros` prefs: the populated list and the per-row
 * delete path (menu, confirmation, store write). The add/edit dialog is not driven: a Material 3
 * text field in an `AlertDialog` never idles under Robolectric; `CustomMacroStoreTest` pins its rules.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class CustomMacrosScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        macroPrefs().edit().clear().commit()
        // The repository memoises the parsed list process-wide.
        CustomMacroRepository.invalidateCache()
    }

    /**
     * Robolectric shares statics and prefs between test classes with the same `@Config`, and
     * `SettingsScreenRenderTest` expects this screen empty.
     */
    @After
    fun tearDown() {
        macroPrefs().edit().clear().commit()
        CustomMacroRepository.invalidateCache()
    }

    private fun macroPrefs() =
        context.getSharedPreferences("custom_macros", Context.MODE_PRIVATE)

    /** Writes the store directly, in the exact shape `CustomMacroRepository` writes. */
    private fun seed(vararg macros: Triple<String, String, String>) {
        val array = JSONArray()
        macros.forEachIndexed { i, (tag, name, value) ->
            array.put(
                JSONObject().put("tag", tag).put("name", name)
                    .put("value", value).put("createdAt", 1_000L + i)
            )
        }
        macroPrefs().edit().putString("macros", array.toString()).commit()
        CustomMacroRepository.invalidateCache()
    }

    private fun storedTags(): List<String> {
        val json = macroPrefs().getString("macros", null) ?: return emptyList()
        val array = JSONArray(json)
        return (0 until array.length()).map { array.getJSONObject(it).getString("tag") }
    }

    private fun render() {
        composeRule.setContent {
            BlackBerryTheme {
                CompositionLocalProvider(
                    LocalSettingsHighlight provides SettingsHighlightController()
                ) {
                    CustomMacrosScreen(onNavigateBack = {})
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun everyStoredMacroIsListedWithItsTagNameAndValue() {
        seed(
            Triple("P", "Phone Number", "+1-555-123-4567"),
            Triple("A", "Address", "1 Main Street")
        )
        render()

        composeRule.onNodeWithText("%P").assertIsDisplayed()
        composeRule.onNodeWithText("Phone Number").assertIsDisplayed()
        composeRule.onNodeWithText("+1-555-123-4567").assertIsDisplayed()
        composeRule.onNodeWithText("%A").assertIsDisplayed()
        composeRule.onNodeWithText("Address").assertIsDisplayed()
        composeRule.onNodeWithText("1 Main Street").assertIsDisplayed()
    }

    @Test
    fun macrosAreListedInStoredOrder() {
        seed(
            Triple("P", "Phone Number", "+1-555-123-4567"),
            Triple("A", "Address", "1 Main Street")
        )
        render()

        // Each macro is one merged row of tag + name + value; the info card sits above them all.
        val rows = composeRule.onAllNodesWithText("%", substring = true).fetchSemanticsNodes()
            .sortedBy { it.boundsInRoot.top }
            .map { node -> node.config[SemanticsProperties.Text].joinToString("") { it.text } }
        assertEquals(
            listOf(
                "Define your own %x placeholders for use in text shortcuts",
                "Reserved tags: %D %T %d %t %n %w %y %b",
                "%PPhone Number+1-555-123-4567",
                "%AAddress1 Main Street",
            ),
            rows
        )
    }

    @Test
    fun aStoredValueIsListedWithItsNewlinesShownAsAReturnArrow() {
        seed(Triple("S", "Signature", "Best regards\nBrian"))
        render()

        composeRule.onNodeWithText("Best regards↵Brian").assertIsDisplayed()
    }

    @Test
    fun theInfoCardAndItsReservedTagListAreShownAboveThePopulatedList() {
        seed(Triple("P", "Phone Number", "+1-555-123-4567"))
        render()

        composeRule
            .onNodeWithText("Define your own %x placeholders for use in text shortcuts")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Reserved tags: %D %T %d %t %n %w %y %b").assertIsDisplayed()
    }

    @Test
    fun deletingAMacroAsksFirstAndThenRemovesOnlyThatOneFromTheStore() {
        seed(
            Triple("A", "Address", "1 Main Street"),
            Triple("P", "Phone Number", "+1-555-123-4567")
        )
        render()

        composeRule.onAllNodesWithContentDescription("Options")[1].performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.waitForIdle()

        // Nothing is gone until the confirmation is accepted, and the question names the macro.
        composeRule.onNodeWithText("Delete macro?").assertIsDisplayed()
        composeRule
            .onNodeWithText("Are you sure you want to delete the macro %P (Phone Number)?")
            .assertIsDisplayed()
        assertEquals(listOf("A", "P"), storedTags())

        composeRule.onAllNodesWithText("Delete").onFirst().performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("A"), storedTags())
    }

    @Test
    fun theDeletedMacroDisappearsFromTheListWithoutReopeningTheScreen() {
        seed(
            Triple("A", "Address", "1 Main Street"),
            Triple("P", "Phone Number", "+1-555-123-4567")
        )
        render()

        composeRule.onAllNodesWithContentDescription("Options")[0].performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Delete").onFirst().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Phone Number").assertIsDisplayed()
        composeRule.onAllNodesWithText("Address").assertCountEquals(0)
    }

    @Test
    fun cancellingTheDeleteConfirmationKeepsTheMacro() {
        seed(Triple("P", "Phone Number", "+1-555-123-4567"))
        render()

        composeRule.onAllNodesWithContentDescription("Options").onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("P"), storedTags())
        composeRule.onNodeWithText("Phone Number").assertIsDisplayed()
    }
}

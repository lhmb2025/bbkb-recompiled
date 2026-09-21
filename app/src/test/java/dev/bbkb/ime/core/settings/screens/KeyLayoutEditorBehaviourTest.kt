package dev.bbkb.ime.core.settings.screens

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.settings.search.LocalSettingsHighlight
import dev.bbkb.ime.core.settings.search.SettingsHighlightController
import dev.bbkb.ime.core.settings.ui.BlackBerryTheme
import dev.bbkb.ime.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CHARACTERISATION TEST — the two tap-to-swap key-layout grid editors,
 * [CustomSymbolPageScreen] (PKB and VKB) and [CustomizeSlideBoardScreen].
 *
 * `SettingsScreenRenderTest` pins what these screens *draw* on a fresh install. It does not touch
 * them, so nothing pinned what they *do*: selecting a cell, swapping two cells, clearing one,
 * applying a reset preset, and — the part that matters most — the byte layout each editor writes
 * to SharedPreferences.
 *
 * That last one is the sharp edge. Both editors sit behind a permutation between the position a
 * key occupies on screen and the index it occupies in storage, because `KeyboardBuilder` reads the
 * stored list in an order its key sorting produces rather than in visual order:
 *
 *  * the VKB symbol page permutes in `CustomSymbolRepository.load/saveLayout`,
 *  * the PKB symbol page does not permute at all,
 *  * the slideboard numpad permutes in `CustomizeSlideBoardScreen`'s own `NUMPAD_KEYBOARD_READ_ORDER`.
 *
 * Get any of those backwards and every existing user's customised layout is silently scrambled,
 * with no crash and no failing render test. So each editor is pinned from both directions:
 * [slideboardRendersStoredSymbolsInKeyboardReadOrder] and its two siblings seed storage with
 * distinguishable tokens and assert the on-screen order, and the swap/clear/reset cases assert the
 * exact list written back.
 *
 * Everything here asserts through the public composable, so it survives the screens being merged
 * into one renderer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class KeyLayoutEditorBehaviourTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** `CustomSymbolRepository.DELIMITER` / `SettingsManager`'s list separator. */
    private val delimiter = "\u0378"

    /** `CustomSymbolRepository.NULL_MARKER` — an empty slot. */
    private val nul = "\u0000"

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /**
     * The screens read through [PrefsManager], whose instance is a process-wide singleton bound to
     * whichever context first asked for it — Robolectric hands each test method a fresh
     * `Application`, but the singleton outlives them, so going through `PreferenceManager` here
     * would address a *different* file from the one under test. Ask the singleton instead, and
     * clear it per method so nothing leaks between cases.
     */
    private val prefs get() = PrefsManager.getPrefs(context)

    @Before
    fun clearPreferences() {
        prefs.edit().clear().commit()
    }

    /**
     * That same singleton outlives this class, and `SettingsScreenRenderTest` records every screen
     * in the state a fresh install produces — so a layout left behind here reappears there as a
     * grid full of `t0`…`t27`. Leave the file the way it was found.
     */
    @After
    fun clearPreferencesAgain() {
        prefs.edit().clear().commit()
    }

    // ========================================================================
    // Storage helpers
    // ========================================================================

    private fun storedList(key: String): List<String> =
        prefs.getString(key, null)?.split(delimiter)?.let {
            // `SettingsManager.setStringListPref` appends a trailing delimiter; `split` on the raw
            // string keeps the empty tail it produces, which `getStringListPref` drops.
            if (it.isNotEmpty() && it.last().isEmpty()) it.dropLast(1) else it
        } ?: emptyList()

    private fun seed(key: String, values: List<String>) {
        prefs.edit().putString(key, values.joinToString(delimiter)).commit()
    }

    private fun tokens(n: Int) = List(n) { "t$it" }

    private fun setContent(content: @androidx.compose.runtime.Composable () -> Unit) {
        composeRule.setContent {
            BlackBerryTheme {
                CompositionLocalProvider(LocalSettingsHighlight provides SettingsHighlightController()) {
                    content()
                }
            }
        }
        composeRule.waitForIdle()
    }

    /**
     * The rendered tokens in reading order (top to bottom, then left to right). Only nodes whose
     * text is one of [expected] are considered, so tab titles and the mock keyboard's fixed keys
     * cannot perturb the sequence.
     */
    private fun renderedTokenOrder(expected: Set<String>): List<String> =
        composeRule
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .mapNotNull { node ->
                val text = node.config[SemanticsProperties.Text].joinToString("") { it.text }
                if (text in expected && node.boundsInRoot.height > 0f) {
                    Triple(text, node.boundsInRoot.top, node.boundsInRoot.left)
                } else {
                    null
                }
            }
            .sortedWith(compareBy({ Math.round(it.second) }, { it.third }))
            .map { it.first }

    // ========================================================================
    // The persisted layout format — storage order, read back on screen
    // ========================================================================

    @Test
    fun pkbSymbolPageRendersStoredSymbolsInStorageOrder() {
        // The PKB layout is stored in visual order: no permutation on either side.
        val stored = tokens(28)
        seed("pref_pkb_symbol_page_layout", stored)

        setContent { CustomSymbolPageScreen(isPkb = true, onBack = {}) }

        assertEquals(stored, renderedTokenOrder(stored.toSet()))
    }

    @Test
    fun vkbSymbolPageRendersStoredSymbolsInKeyboardReadOrder() {
        // CustomSymbolRepository.loadLayout un-permutes the VKB list: editor position i shows
        // storage[keyboardReadOrder[i]]. 26 editable slots read out of a 28-slot store.
        val readOrder = intArrayOf(
            6, 7, 8, 9, 10, 11, 12, 13, 14, 5,
            25, 21, 20, 19, 22, 23, 24, 18, 17,
            1, 3, 4, 15, 0, 16, 2,
        )
        val stored = tokens(28)
        seed("pref_vkb_symbol_page_layout", stored)

        setContent { CustomSymbolPageScreen(isPkb = false, onBack = {}) }

        assertEquals(
            readOrder.map { stored[it] },
            renderedTokenOrder(readOrder.map { stored[it] }.toSet()),
        )
    }

    @Test
    fun slideboardRendersStoredSymbolsInKeyboardReadOrder() {
        // NUMPAD_KEYBOARD_READ_ORDER: editor position i shows storage[readOrder[i]].
        val readOrder = intArrayOf(
            15, 16, 17, 4, 5,
            12, 13, 14, 2, 3,
            9, 10, 11, 19, 1,
            8, 7, 6, 18, 0,
        )
        val stored = tokens(20)
        seed("custom_slideboard_symbols", stored)

        setContent { CustomizeSlideBoardScreen({}) }

        assertEquals(readOrder.map { stored[it] }, renderedTokenOrder(stored.toSet()))
    }

    // ========================================================================
    // Swapping two cells — and what that writes
    // ========================================================================

    @Test
    fun pkbSwapWritesTheSwappedPairInStorageOrder() {
        val stored = tokens(28)
        seed("pref_pkb_symbol_page_layout", stored)
        setContent { CustomSymbolPageScreen(isPkb = true, onBack = {}) }

        composeRule.onNodeWithText("t0").performClick()
        composeRule.onNodeWithText("t5").performClick()
        composeRule.waitForIdle()

        val expected = stored.toMutableList().also { it[0] = "t5"; it[5] = "t0" }
        assertEquals(expected, storedList("pref_pkb_symbol_page_layout"))
    }

    @Test
    fun vkbSwapWritesThroughTheKeyboardReadOrderPermutation() {
        val stored = tokens(28)
        seed("pref_vkb_symbol_page_layout", stored)
        setContent { CustomSymbolPageScreen(isPkb = false, onBack = {}) }

        // Editor slot 0 shows storage[6]; editor slot 1 shows storage[7].
        composeRule.onNodeWithText("t6").performClick()
        composeRule.onNodeWithText("t7").performClick()
        composeRule.waitForIdle()

        // Only the 26 slots the read order covers are written; storage 26 and 27 fall to the marker.
        val readOrder = intArrayOf(
            6, 7, 8, 9, 10, 11, 12, 13, 14, 5,
            25, 21, 20, 19, 22, 23, 24, 18, 17,
            1, 3, 4, 15, 0, 16, 2,
        )
        val editor = readOrder.map { stored[it] }.toMutableList().also { it[0] = "t7"; it[1] = "t6" }
        val expected = MutableList(28) { nul }
        readOrder.forEachIndexed { editorPos, storagePos -> expected[storagePos] = editor[editorPos] }
        assertEquals(expected, storedList("pref_vkb_symbol_page_layout"))
    }

    @Test
    fun slideboardSwapWritesThroughTheNumpadReadOrder() {
        val stored = tokens(20)
        seed("custom_slideboard_symbols", stored)
        setContent { CustomizeSlideBoardScreen({}) }

        // Editor slot 0 shows storage[15]; editor slot 1 shows storage[16].
        composeRule.onNodeWithText("t15").performClick()
        composeRule.onNodeWithText("t16").performClick()
        composeRule.waitForIdle()

        val expected = stored.toMutableList().also { it[15] = "t16"; it[16] = "t15" }
        assertEquals(expected, storedList("custom_slideboard_symbols"))
    }

    // ========================================================================
    // Selecting, deselecting, clearing
    // ========================================================================

    @Test
    fun tappingAKeyTwiceDeselectsItAndWritesNothing() {
        seed("pref_pkb_symbol_page_layout", tokens(28))
        setContent { CustomSymbolPageScreen(isPkb = true, onBack = {}) }

        // The editor holds the layout in memory from here on, so taking the key away lets the
        // assertion below distinguish "wrote the same bytes back" from "did not write at all".
        // Selecting and then deselecting a key is not an edit and must persist nothing: a
        // self-swap would round-trip to an identical string and slip past a value comparison.
        prefs.edit().remove("pref_pkb_symbol_page_layout").commit()

        composeRule.onNodeWithText("t0").performClick()
        composeRule.waitForIdle()
        // Selecting a key reveals the "clear" action in the app bar.
        composeRule.onNodeWithContentDescription("Clear selected key").assertIsDisplayed()

        composeRule.onNodeWithText("t0").performClick()
        composeRule.waitForIdle()
        assertTrue(
            "deselecting a key should take the clear action away again",
            nodesDescribed("Clear selected key").isEmpty(),
        )
        assertEquals(
            "selecting then deselecting a key wrote to storage",
            null, prefs.getString("pref_pkb_symbol_page_layout", null),
        )
    }

    @Test
    fun tappingASlideboardKeyTwiceDeselectsItAndWritesNothing() {
        seed("custom_slideboard_symbols", tokens(20))
        setContent { CustomizeSlideBoardScreen({}) }

        prefs.edit().remove("custom_slideboard_symbols").commit()

        composeRule.onNodeWithText("t15").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Clear selected key").assertIsDisplayed()

        composeRule.onNodeWithText("t15").performClick()
        composeRule.waitForIdle()
        assertTrue(
            "deselecting a key should take the clear action away again",
            nodesDescribed("Clear selected key").isEmpty(),
        )
        assertEquals(
            "selecting then deselecting a key wrote to storage",
            null, prefs.getString("custom_slideboard_symbols", null),
        )
    }

    @Test
    fun clearingASelectedKeyWritesTheNullMarkerAtThatSlot() {
        val stored = tokens(28)
        seed("pref_pkb_symbol_page_layout", stored)
        setContent { CustomSymbolPageScreen(isPkb = true, onBack = {}) }

        composeRule.onNodeWithText("t3").performClick()
        composeRule.onNodeWithContentDescription("Clear selected key").performClick()
        composeRule.waitForIdle()

        assertEquals(stored.toMutableList().also { it[3] = nul }, storedList("pref_pkb_symbol_page_layout"))
    }

    @Test
    fun clearingASelectedSlideboardKeyWritesThroughTheNumpadReadOrder() {
        val stored = tokens(20)
        seed("custom_slideboard_symbols", stored)
        setContent { CustomizeSlideBoardScreen({}) }

        // Editor slot 3 shows storage[4].
        composeRule.onNodeWithText("t4").performClick()
        composeRule.onNodeWithContentDescription("Clear selected key").performClick()
        composeRule.waitForIdle()

        assertEquals(stored.toMutableList().also { it[4] = nul }, storedList("custom_slideboard_symbols"))
    }

    // ========================================================================
    // Assigning a symbol from the palette
    // ========================================================================

    @Test
    fun tappingAPaletteSymbolThenAKeyAssignsIt() {
        val stored = tokens(28)
        seed("pref_pkb_symbol_page_layout", stored)
        setContent { CustomSymbolPageScreen(isPkb = true, onBack = {}) }

        // The Alphanumeric palette is generated in Kotlin (a-z, A-Z, 0-9), so unlike the emoji tab
        // it is populated under Robolectric. "a" appears nowhere in a token layout.
        composeRule.onNodeWithText(context.getString(R.string.symbol_list_category_alphanumeric_title))
            .performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("a").performClick()
        composeRule.onNodeWithText("t2").performClick()
        composeRule.waitForIdle()

        assertEquals(stored.toMutableList().also { it[2] = "a" }, storedList("pref_pkb_symbol_page_layout"))
    }

    @Test
    fun tappingAKeyThenAPaletteSymbolAssignsIt() {
        val stored = tokens(28)
        seed("pref_pkb_symbol_page_layout", stored)
        setContent { CustomSymbolPageScreen(isPkb = true, onBack = {}) }

        composeRule.onNodeWithText(context.getString(R.string.symbol_list_category_alphanumeric_title))
            .performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("t2").performClick()
        composeRule.onNodeWithText("b").performClick()
        composeRule.waitForIdle()

        assertEquals(stored.toMutableList().also { it[2] = "b" }, storedList("pref_pkb_symbol_page_layout"))
    }

    // ========================================================================
    // Reset presets
    // ========================================================================

    @Test
    fun resetToPageTwoWritesThePageTwoDefaults() {
        seed("pref_pkb_symbol_page_layout", tokens(28))
        setContent { CustomSymbolPageScreen(isPkb = true, onBack = {}) }

        composeRule.onNodeWithContentDescription("Menu").performClick()
        composeRule.onNodeWithText(context.getString(R.string.customized_symbols_page_reset_to_page_2))
            .performClick()
        composeRule.waitForIdle()

        // pkb_page_2 is 27 entries against a 28-slot editor, so the last slot keeps what it had.
        val page2 = context.resources.getStringArray(R.array.pkb_page_2).toList()
        val expected = tokens(28).toMutableList()
        page2.forEachIndexed { i, s -> expected[i] = s }
        assertEquals(expected, storedList("pref_pkb_symbol_page_layout"))
    }

    @Test
    fun resetToBlankWritesTheNullMarkerEverywhere() {
        seed("pref_pkb_symbol_page_layout", tokens(28))
        setContent { CustomSymbolPageScreen(isPkb = true, onBack = {}) }

        composeRule.onNodeWithContentDescription("Menu").performClick()
        composeRule.onNodeWithText(context.getString(R.string.customized_symbols_page_reset_to_blank))
            .performClick()
        composeRule.waitForIdle()

        assertEquals(List(28) { nul }, storedList("pref_pkb_symbol_page_layout"))
    }

    @Test
    fun resetToUmlautWritesTheUmlautLayout() {
        seed("pref_pkb_symbol_page_layout", tokens(28))
        setContent { CustomSymbolPageScreen(isPkb = true, onBack = {}) }

        composeRule.onNodeWithContentDescription("Menu").performClick()
        composeRule.onNodeWithText(context.getString(R.string.customized_symbols_page_reset_umlaut_layout))
            .performClick()
        composeRule.waitForIdle()

        val umlaut = context.resources.getStringArray(R.array.pkb_umlaut_layout).toList()
        val expected = tokens(28).toMutableList()
        umlaut.forEachIndexed { i, s -> expected[i] = s }
        assertEquals(expected, storedList("pref_pkb_symbol_page_layout"))
    }

    @Test
    fun slideboardResetToDefaultWritesTheDefaultsThroughTheNumpadReadOrder() {
        seed("custom_slideboard_symbols", tokens(20))
        setContent { CustomizeSlideBoardScreen({}) }

        composeRule.onNodeWithContentDescription("Menu").performClick()
        composeRule.onNodeWithText(context.getString(R.string.customize_slideboard_reset_to_default))
            .performClick()
        composeRule.waitForIdle()

        val readOrder = intArrayOf(
            15, 16, 17, 4, 5,
            12, 13, 14, 2, 3,
            9, 10, 11, 19, 1,
            8, 7, 6, 18, 0,
        )
        val defaults = context.resources.getStringArray(R.array.slideboard_numpad_symbols)
        val expected = MutableList(20) { nul }
        readOrder.forEachIndexed { editorPos, storagePos -> expected[storagePos] = defaults[editorPos] }
        assertEquals(expected, storedList("custom_slideboard_symbols"))
    }

    @Test
    fun slideboardClearKeypadWritesTheNullMarkerEverywhere() {
        seed("custom_slideboard_symbols", tokens(20))
        setContent { CustomizeSlideBoardScreen({}) }

        composeRule.onNodeWithContentDescription("Menu").performClick()
        composeRule.onNodeWithText(context.getString(R.string.customize_slideboard_clear_keypad))
            .performClick()
        composeRule.waitForIdle()

        assertEquals(List(20) { nul }, storedList("custom_slideboard_symbols"))
    }

    // ========================================================================
    // Defaults on a fresh install
    // ========================================================================

    @Test
    fun aFreshSlideboardShowsTheDefaultNumpadInVisualOrder() {
        setContent { CustomizeSlideBoardScreen({}) }

        val defaults = context.resources.getStringArray(R.array.slideboard_numpad_symbols).toList()
        assertEquals(defaults, renderedTokenOrder(defaults.toSet()))
    }

    @Test
    fun aFreshPkbSymbolPageShowsPageOneInVisualOrder() {
        setContent { CustomSymbolPageScreen(isPkb = true, onBack = {}) }

        val page1 = context.resources.getStringArray(R.array.pkb_page_1).toList()
        assertEquals(page1, renderedTokenOrder(page1.toSet()))
    }

    /** How many nodes currently carry this content description; 0 when the action is gone. */
    private fun nodesDescribed(description: String) = composeRule.onAllNodes(
        SemanticsMatcher("ContentDescription = '$description'") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true
        },
        useUnmergedTree = true,
    ).fetchSemanticsNodes()
}

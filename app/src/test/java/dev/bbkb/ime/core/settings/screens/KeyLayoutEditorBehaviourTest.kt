package dev.bbkb.ime.core.settings.screens

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.settings.search.LocalSettingsHighlight
import dev.bbkb.ime.core.settings.search.SettingsHighlightController
import dev.bbkb.ime.core.settings.screens.keyeditor.KeyEditorPreviewView
import dev.bbkb.ime.core.settings.screens.keyeditor.PkbSymbolPageSpec
import dev.bbkb.ime.core.settings.screens.keyeditor.VkbSymbolPageSpec
import dev.bbkb.ime.core.locale.SubtypeManager
import dev.bbkb.ime.keyboard.KeyboardBuilder
import android.view.ContextThemeWrapper
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
 * Pins what the two tap-to-swap grid editors, [CustomSymbolPageScreen] (PKB and VKB) and
 * [CustomizeSlideBoardScreen], do: select, swap, clear, reset, and the exact list each writes.
 * Storage order differs from screen order (VKB permutes in `CustomSymbolRepository`, PKB not at
 * all, the numpad via `NUMPAD_KEYBOARD_READ_ORDER`); getting one backwards silently scrambles
 * every user's layout, so each is pinned from storage to screen and back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class KeyLayoutEditorBehaviourTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** `CustomSymbolRepository.DELIMITER` / `SettingsManager`'s list separator. */
    private val delimiter = "\u0378"

    /** `CustomSymbolRepository.NULL_MARKER`: an empty slot. */
    private val nul = "\u0000"

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /**
     * The screens read through the [PrefsManager] singleton, bound to whichever context asked first
     * and outliving Robolectric's per-test `Application`, so ask it rather than `PreferenceManager`.
     */
    private val prefs get() = PrefsManager.getPrefs(context)

    @Before
    fun clearPreferences() {
        prefs.edit().clear().commit()
    }

    /**
     * Leave prefs as found: the singleton outlives this class, and a leftover layout would show up
     * in `SettingsScreenRenderTest`'s fresh-install cases.
     */
    @After
    fun clearPreferencesAgain() {
        prefs.edit().clear().commit()
    }

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

    /** The board is the keyboard's own view, not Compose text, and appears once built off the main thread. */
    private fun preview(): KeyEditorPreviewView {
        var found: KeyEditorPreviewView? = null
        composeRule.waitUntil(10_000) {
            found = findPreview(composeRule.activity.window.decorView)
            found?.slotLabels()?.isNotEmpty() == true
        }
        composeRule.waitForIdle()
        return found!!
    }

    private fun findPreview(view: View): KeyEditorPreviewView? {
        if (view is KeyEditorPreviewView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findPreview(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    /** What the board's editable keys show, in reading order. */
    private fun keyLabels(): List<String> = preview().slotLabels()

    private fun tapKey(label: String) {
        val board = preview()
        val slot = board.slotLabels().indexOf(label)
        assertTrue("no key shows $label: ${board.slotLabels()}", slot >= 0)
        board.tapSlot(slot)
        composeRule.waitForIdle()
    }

    @Test
    fun pkbSymbolPageRendersStoredSymbolsInStorageOrder() {
        // The PKB layout is stored in visual order: no permutation on either side.
        val stored = tokens(27)
        seed("pref_pkb_symbol_page_layout", stored)

        setContent { CustomSymbolPageScreen(isPkb = true, onBack = {}) }

        assertEquals(stored, keyLabels())
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
            keyLabels(),
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

        assertEquals(readOrder.map { stored[it] }, keyLabels())
    }

    /** The custom symbol page as the keyboard builds it from storage: its editable keys, in reading order. */
    private fun keyboardCustomPage(pkb: Boolean): List<String> {
        SubtypeManager.ensureInitialized(context)
        KeyboardBuilder.onKeyboardThemeChanged()
        val keyboard = KeyboardBuilder.Builder(ContextThemeWrapper(context, R.style.KeyboardTheme_LXX), null)
            .setKeyboardGeometry(1080, 700)
            .setSubtype(SubtypeManager.getInstance().currentSubtype)
            .build()
            .getKeyboardInternal(8, pkb)
        return keyboard.keys.filter { it.outputText == "customSymbolKeyStyle" }.map { it.label.orEmpty() }
    }

    /** The editor and the keyboard read the stored on-screen page through one order table. */
    @Test
    fun theKeyboardDrawsTheOnScreenPageAsTheEditorSavedIt() {
        val page = List(26) { "s$it" }
        VkbSymbolPageSpec.save(context, page)

        assertEquals(page, keyboardCustomPage(pkb = false))
    }

    @Test
    fun theKeyboardDrawsThePhysicalPageAsTheEditorSavedIt() {
        val page = List(27) { "s$it" }
        PkbSymbolPageSpec.save(context, page)

        assertEquals(page, keyboardCustomPage(pkb = true))
    }

    @Test
    fun pkbSwapWritesTheSwappedPairInStorageOrder() {
        val stored = tokens(27)
        seed("pref_pkb_symbol_page_layout", stored)
        setContent { CustomSymbolPageScreen(isPkb = true, onBack = {}) }

        tapKey("t0")
        tapKey("t5")
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
        tapKey("t6")
        tapKey("t7")
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
        tapKey("t15")
        tapKey("t16")
        composeRule.waitForIdle()

        val expected = stored.toMutableList().also { it[15] = "t16"; it[16] = "t15" }
        assertEquals(expected, storedList("custom_slideboard_symbols"))
    }

    @Test
    fun tappingAKeyTwiceDeselectsItAndWritesNothing() {
        seed("pref_pkb_symbol_page_layout", tokens(27))
        setContent { CustomSymbolPageScreen(isPkb = true, onBack = {}) }

        // The editor holds the layout in memory from here on, so taking the key away lets the
        // assertion below distinguish "wrote the same bytes back" from "did not write at all".
        // Selecting and then deselecting a key is not an edit and must persist nothing: a
        // self-swap would round-trip to an identical string and slip past a value comparison.
        prefs.edit().remove("pref_pkb_symbol_page_layout").commit()

        tapKey("t0")
        composeRule.waitForIdle()
        // Selecting a key reveals the "clear" action in the app bar.
        composeRule.onNodeWithContentDescription("Clear selected key").assertIsDisplayed()

        tapKey("t0")
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

        tapKey("t15")
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Clear selected key").assertIsDisplayed()

        tapKey("t15")
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
        val stored = tokens(27)
        seed("pref_pkb_symbol_page_layout", stored)
        setContent { CustomSymbolPageScreen(isPkb = true, onBack = {}) }

        tapKey("t3")
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
        tapKey("t4")
        composeRule.onNodeWithContentDescription("Clear selected key").performClick()
        composeRule.waitForIdle()

        assertEquals(stored.toMutableList().also { it[4] = nul }, storedList("custom_slideboard_symbols"))
    }

    @Test
    fun tappingAPaletteSymbolThenAKeyAssignsIt() {
        val stored = tokens(27)
        seed("pref_pkb_symbol_page_layout", stored)
        setContent { CustomSymbolPageScreen(isPkb = true, onBack = {}) }

        // The Alphanumeric palette is generated in Kotlin (a-z, A-Z, 0-9), so unlike the emoji tab
        // it is populated under Robolectric. "a" appears nowhere in a token layout.
        composeRule.onNodeWithText(context.getString(R.string.symbol_list_category_alphanumeric_title))
            .performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("a").performClick()
        tapKey("t2")
        composeRule.waitForIdle()

        assertEquals(stored.toMutableList().also { it[2] = "a" }, storedList("pref_pkb_symbol_page_layout"))
    }

    @Test
    fun tappingAKeyThenAPaletteSymbolAssignsIt() {
        val stored = tokens(27)
        seed("pref_pkb_symbol_page_layout", stored)
        setContent { CustomSymbolPageScreen(isPkb = true, onBack = {}) }

        composeRule.onNodeWithText(context.getString(R.string.symbol_list_category_alphanumeric_title))
            .performClick()
        composeRule.waitForIdle()
        tapKey("t2")
        composeRule.onNodeWithText("b").performClick()
        composeRule.waitForIdle()

        assertEquals(stored.toMutableList().also { it[2] = "b" }, storedList("pref_pkb_symbol_page_layout"))
    }

    @Test
    fun resetToPageTwoWritesThePageTwoDefaults() {
        seed("pref_pkb_symbol_page_layout", tokens(27))
        setContent { CustomSymbolPageScreen(isPkb = true, onBack = {}) }

        composeRule.onNodeWithContentDescription("Menu").performClick()
        composeRule.onNodeWithText(context.getString(R.string.customized_symbols_page_reset_to_page_2))
            .performClick()
        composeRule.waitForIdle()

        // pkb_page_2 is 27 entries, one per editor slot.
        val page2 = context.resources.getStringArray(R.array.pkb_page_2).toList()
        assertEquals(page2, storedList("pref_pkb_symbol_page_layout"))
    }

    @Test
    fun resetToBlankWritesTheNullMarkerEverywhere() {
        seed("pref_pkb_symbol_page_layout", tokens(27))
        setContent { CustomSymbolPageScreen(isPkb = true, onBack = {}) }

        composeRule.onNodeWithContentDescription("Menu").performClick()
        composeRule.onNodeWithText(context.getString(R.string.customized_symbols_page_reset_to_blank))
            .performClick()
        composeRule.waitForIdle()

        assertEquals(List(27) { nul }, storedList("pref_pkb_symbol_page_layout"))
    }

    @Test
    fun resetToUmlautWritesTheUmlautLayout() {
        seed("pref_pkb_symbol_page_layout", tokens(27))
        setContent { CustomSymbolPageScreen(isPkb = true, onBack = {}) }

        composeRule.onNodeWithContentDescription("Menu").performClick()
        composeRule.onNodeWithText(context.getString(R.string.customized_symbols_page_reset_umlaut_layout))
            .performClick()
        composeRule.waitForIdle()

        val umlaut = context.resources.getStringArray(R.array.pkb_umlaut_layout).toList()
        assertEquals(umlaut, storedList("pref_pkb_symbol_page_layout"))
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

    @Test
    fun aFreshSlideboardShowsTheDefaultNumpadInVisualOrder() {
        setContent { CustomizeSlideBoardScreen({}) }

        val defaults = context.resources.getStringArray(R.array.slideboard_numpad_symbols).toList()
        assertEquals(defaults, keyLabels())
    }

    @Test
    fun aFreshPkbSymbolPageShowsPageOneInVisualOrder() {
        setContent { CustomSymbolPageScreen(isPkb = true, onBack = {}) }

        val page1 = context.resources.getStringArray(R.array.pkb_page_1).toList()
        assertEquals(page1, keyLabels())
    }

    /** How many nodes currently carry this content description; 0 when the action is gone. */
    private fun nodesDescribed(description: String) = composeRule.onAllNodes(
        SemanticsMatcher("ContentDescription = '$description'") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true
        },
        useUnmergedTree = true,
    ).fetchSemanticsNodes()
}

package dev.bbkb.ime.core.settings.screens

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.settings.data.DictionaryEntry
import dev.bbkb.ime.core.settings.data.DictionaryEntryStore
import dev.bbkb.ime.core.settings.data.DictionaryRepository
import dev.bbkb.ime.core.settings.search.LocalSettingsHighlight
import dev.bbkb.ime.core.settings.search.SettingsHighlightController
import dev.bbkb.ime.core.settings.ui.BlackBerryTheme
import dev.bbkb.ime.personaldictionary.macro.DynamicMacroType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CHARACTERISATION TEST — [TextShortcutsScreen] and [UserDictionaryScreen] with entries in them.
 *
 * These two screens are the same screen written twice, and Wave 3 collapses their shared body into
 * one. `SettingsScreenRenderTest` renders both, but only ever in the state a fresh install produces,
 * which for these two is the loading spinner: `DictionaryRepository` reaches
 * `DictionaryManager.getInstance()`, which reaches the Nuance SDK, which is not there in a unit
 * test, so `initialize()` never completes and no list is ever drawn. Everything below the top bar
 * was therefore unobserved.
 *
 * The `storeFactory` seam exists for exactly this: composed against a stand-in store, the screens
 * reach their loaded state and every difference between them becomes assertable — the counter
 * wording, the search predicate, the language headers, the row composition, the markers, the
 * de-duplication key, the sort order, and the two empty states. Those are precisely the things a
 * shared body has to keep telling apart, so they are what is pinned here. Since the 2026-11
 * alignment with Learned words it also drives what the shared body does: the sheet a tap opens,
 * delete with Undo, and selection.
 *
 * The assertions are whole-screen: every laid-out string, top to bottom, in the order the user
 * reads it. A row that appears, disappears, moves or changes its wording fails the case, which is
 * what makes this a safety net for a rewrite rather than a spot check.
 *
 * **Not covered:** the add/edit dialog and form, on either screen. Under this Robolectric/Compose
 * combination a Material 3 text field inside a dialog never reaches an idle composition — it
 * reproduces with entirely stock components — so no dialog in this codebase can be driven from a
 * unit test. That is why both screens keep their own hand-written one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class DictionaryEditorScreensTest {

    @get:Rule
    val composeRule = createComposeRule()

    // ------------------------------------------------------------------ the stand-in store

    /**
     * A store that answers from a fixed table. Deliberately synchronous: the real repository hops
     * to `Dispatchers.IO` and polls, which a composition test cannot wait out.
     */
    private class FakeStore(
        private val locales: List<String>,
        private val entriesByLocale: Map<String, List<DictionaryEntry>> = emptyMap(),
        private val initResult: DictionaryRepository.InitResult =
            DictionaryRepository.InitResult.Success,
        private val loadThrows: Boolean = false,
    ) : DictionaryEntryStore {

        val added = mutableListOf<DictionaryEntry>()
        val updated = mutableListOf<Pair<DictionaryEntry, DictionaryEntry>>()
        val deleted = mutableListOf<DictionaryEntry>()

        override suspend fun initialize(): DictionaryRepository.InitResult = initResult

        override suspend fun getLocales(): List<String> = locales

        override suspend fun getAllEntries(locale: String): List<DictionaryEntry> {
            if (loadThrows) throw IllegalStateException("store unavailable")
            return entriesByLocale[locale].orEmpty()
        }

        override suspend fun addEntry(entry: DictionaryEntry): Result<Unit> {
            added += entry
            return Result.success(Unit)
        }

        override suspend fun updateEntry(
            oldEntry: DictionaryEntry,
            newEntry: DictionaryEntry
        ): Result<Unit> {
            updated += oldEntry to newEntry
            return Result.success(Unit)
        }

        override suspend fun deleteEntry(entry: DictionaryEntry): Result<Unit> {
            deleted += entry
            return Result.success(Unit)
        }
    }

    private fun shortcut(shortcut: String, word: String, locale: String, fixedCase: Boolean = false) =
        DictionaryEntry(word = word, shortcut = shortcut, locale = locale, fixedCase = fixedCase)

    private fun word(word: String, locale: String) =
        DictionaryEntry(word = word, shortcut = null, locale = locale)

    // ----------------------------------------------------------------------- rendering

    private fun render(content: @Composable () -> Unit) {
        composeRule.setContent {
            BlackBerryTheme {
                CompositionLocalProvider(
                    LocalSettingsHighlight provides SettingsHighlightController()
                ) { content() }
            }
        }
        composeRule.waitForIdle()
    }

    private fun renderShortcuts(store: FakeStore, onNavigateToCustomMacros: () -> Unit = {}) = render {
        TextShortcutsScreen(onNavigateToCustomMacros = onNavigateToCustomMacros, onNavigateBack = {}, storeFactory = { store })
    }

    private fun renderDictionary(store: FakeStore) = render {
        UserDictionaryScreen(onNavigateBack = {}, storeFactory = { store })
    }

    /** Every laid-out string, top to bottom, as the user reads down the screen. */
    private fun screen(): List<String> =
        composeRule
            .onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.Text),
                useUnmergedTree = true
            )
            .fetchSemanticsNodes()
            .filter { it.boundsInRoot.height > 0f }
            .sortedWith(compareBy({ it.boundsInRoot.top }, { it.boundsInRoot.left }))
            .map { node -> node.config[SemanticsProperties.Text].joinToString("") { it.text } }

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun search(query: String) {
        composeRule.onNodeWithContentDescription("Search").performClick()
        composeRule.waitForIdle()
        composeRule.onNode(hasSetTextAction()).performTextInput(query)
        composeRule.waitForIdle()
    }

    /**
     * Clicks through the semantics action rather than a touch: a bottom sheet's window is not laid
     * out where a touch would land under Robolectric, so a touch on its rows hits nothing.
     */
    private fun tap(text: String) {
        composeRule.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
    }

    private val dictionaryTitle get() = context.getString(dev.bbkb.ime.R.string.settings_user_dictionary_screen_title)

    // ============================================================ text shortcuts

    @Test
    fun shortcutsInOneLanguageAreOneGroupUnderTheirCount() {
        renderShortcuts(
            FakeStore(
                locales = listOf("en_US"),
                entriesByLocale = mapOf(
                    "en_US" to listOf(
                        shortcut("sig", "Best regards", "en_US"),
                        shortcut("addr", "1 Main Street", "en_US"),
                    )
                )
            )
        )

        assertEquals(
            listOf(
                "Text shortcuts",
                "2 shortcuts",
                // One language: no header, as on Learned words. Sorted by shortcut, lower-cased.
                "addr", "1 Main Street",
                "sig", "Best regards",
                "Add shortcut",
            ),
            screen()
        )
    }

    @Test
    fun macrosAndFixedCaseAreMarkedWithIconsAndTheMacrosStandOutInThePhrase() {
        renderShortcuts(
            FakeStore(
                locales = listOf("en_US"),
                entriesByLocale = mapOf(
                    "en_US" to listOf(
                        shortcut("sig", "Best regards,%n%D", "en_US"),
                        shortcut("iph", "iPhone", "en_US", fixedCase = true),
                        shortcut("both", "%D", "en_US", fixedCase = true),
                    )
                )
            )
        )

        assertEquals(
            listOf(
                "Text shortcuts",
                "3 shortcuts",
                "both", "%D",
                "iph", "iPhone",
                // A new line shows as ↵ on the one-line row.
                "sig", "Best regards,↵%D",
                "Add shortcut",
            ),
            screen()
        )
        composeRule.onAllNodesWithContentDescription("Changes each time").assertCountEquals(2)
        composeRule.onAllNodesWithContentDescription("Fixed case").assertCountEquals(2)
    }

    @Test
    fun onlySubstitutionsAppearInTheShortcutsEditor() {
        renderShortcuts(
            FakeStore(
                locales = listOf("en_US"),
                entriesByLocale = mapOf(
                    "en_US" to listOf(
                        shortcut("sig", "Best regards", "en_US"),
                        word("Landrum", "en_US"),
                        // A personal word as the repository actually stores it: shortcut == word.
                        shortcut("Kaptur", "Kaptur", "en_US"),
                    )
                )
            )
        )

        assertEquals(
            listOf(
                "Text shortcuts",
                "1 shortcut",
                "sig", "Best regards",
                "Add shortcut",
            ),
            screen()
        )
    }

    @Test
    fun eachLocaleGetsItsOwnHeaderWhenThereIsMoreThanOne() {
        renderShortcuts(
            FakeStore(
                locales = listOf("fr_FR", "en_US"),
                entriesByLocale = mapOf(
                    "en_US" to listOf(shortcut("sig", "Best regards", "en_US")),
                    "fr_FR" to listOf(shortcut("cdt", "Cordialement", "fr_FR")),
                )
            )
        )

        assertEquals(
            listOf(
                "Text shortcuts",
                "2 shortcuts",
                // Locales sort before shortcuts; the headers no longer count their rows.
                "English (United States)",
                "sig", "Best regards",
                "French (France)",
                "cdt", "Cordialement",
                "Add shortcut",
            ),
            screen()
        )
    }

    @Test
    fun theSameShortcutInTwoLocalesIsKeptButADuplicateWithinALocaleIsDropped() {
        renderShortcuts(
            FakeStore(
                locales = listOf("en_US", "fr_FR"),
                entriesByLocale = mapOf(
                    "en_US" to listOf(
                        shortcut("sig", "Best regards", "en_US"),
                        shortcut("sig", "Kind regards", "en_US"),
                    ),
                    "fr_FR" to listOf(shortcut("sig", "Cordialement", "fr_FR")),
                )
            )
        )

        assertEquals(
            listOf(
                "Text shortcuts",
                "2 shortcuts",
                "English (United States)",
                "sig", "Best regards",
                "French (France)",
                "sig", "Cordialement",
                "Add shortcut",
            ),
            screen()
        )
    }

    @Test
    fun theShortcutsSearchMatchesEitherTheShortcutOrThePhrase() {
        renderShortcuts(
            FakeStore(
                locales = listOf("en_US"),
                entriesByLocale = mapOf(
                    "en_US" to listOf(
                        shortcut("sig", "Best regards", "en_US"),
                        shortcut("addr", "1 Main Street", "en_US"),
                    )
                )
            )
        )

        search("regards")

        assertEquals(
            listOf(
                // The field takes the top bar and the title folds away; the count line says how
                // many of the whole list the query kept.
                "1 of 2 shortcuts",
                "sig", "Best regards",
                "Add shortcut",
            ),
            screen()
        )
    }

    @Test
    fun aShortcutsSearchThatMatchesNothingShowsTheNoMatchesState() {
        renderShortcuts(
            FakeStore(
                locales = listOf("en_US"),
                entriesByLocale = mapOf("en_US" to listOf(shortcut("sig", "Best regards", "en_US")))
            )
        )

        search("zzz")

        assertEquals(
            listOf(
                "No matches found",
                "Try a different search term",
                "Add shortcut",
            ),
            screen()
        )
    }

    @Test
    fun closingTheShortcutsSearchClearsTheQueryAndRestoresTheList() {
        renderShortcuts(
            FakeStore(
                locales = listOf("en_US"),
                entriesByLocale = mapOf("en_US" to listOf(shortcut("sig", "Best regards", "en_US")))
            )
        )

        search("zzz")
        composeRule.onNodeWithContentDescription("Close search").performClick()
        composeRule.waitForIdle()

        assertEquals(
            listOf(
                "Text shortcuts",
                "1 shortcut",
                "sig", "Best regards",
                "Add shortcut",
            ),
            screen()
        )
    }

    @Test
    fun anEmptyShortcutStoreShowsItsOwnEmptyState() {
        renderShortcuts(FakeStore(locales = listOf("en_US")))

        assertEquals(
            listOf(
                "Text shortcuts",
                "No text shortcuts",
                "Tap Add shortcut to create shortcuts that expand to phrases",
                "Add shortcut",
            ),
            screen()
        )
    }

    @Test
    fun theMoreMenuOpensTheDynamicContentSheetWithEveryBuiltInMacro() {
        renderShortcuts(FakeStore(locales = listOf("en_US")))

        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.waitForIdle()
        tap("About dynamic content")

        composeRule.onNodeWithText("Dynamic content").assertExists()
        DynamicMacroType.entries.forEach { composeRule.onNodeWithText(it.tag).assertExists() }
    }

    @Test
    fun theMoreMenuLeadsToCustomMacros() {
        var opened = false
        renderShortcuts(FakeStore(locales = listOf("en_US")), onNavigateToCustomMacros = { opened = true })

        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.waitForIdle()
        tap("Custom macros")

        assertEquals(true, opened)
    }

    /** The form's chips and the help sheet name exactly the macros the expansion knows. */
    @Test
    fun theNamedMacrosAreTheOnesTheKeyboardExpands() {
        assertEquals(
            DynamicMacroType.entries.map { it.tag }.toSet(),
            BuiltInMacros.map { it.first }.toSet()
        )
    }

    @Test
    fun tappingAShortcutOpensItsSheetWithTheWholePhraseAndItsActions() {
        renderShortcuts(
            FakeStore(
                locales = listOf("en_US"),
                entriesByLocale = mapOf("en_US" to listOf(shortcut("sig", "Best regards", "en_US", fixedCase = true)))
            )
        )

        tap("sig")

        composeRule.onNodeWithText("English (United States) · Fixed case").assertExists()
        composeRule.onNodeWithText("Expands to").assertExists()
        composeRule.onNodeWithText("Edit shortcut").assertExists()
        composeRule.onNodeWithText("Delete").assertExists()
    }

    @Test
    fun deletingFromTheSheetDoesNotAskAndUndoPutsTheShortcutBack() {
        val sig = shortcut("sig", "Best regards", "en_US", fixedCase = true)
        val store = FakeStore(locales = listOf("en_US"), entriesByLocale = mapOf("en_US" to listOf(sig)))
        renderShortcuts(store)

        tap("sig")
        tap("Delete")

        assertEquals(listOf(sig), store.deleted)
        composeRule.onNodeWithText("Deleted 1 shortcut").assertExists()
        tap("Undo")
        // Back exactly as it was, fixed case and all.
        assertEquals(listOf(sig), store.added)
    }

    @Test
    fun aLongPressSelectsAndTheBarDeletesTheSelection() {
        val sig = shortcut("sig", "Best regards", "en_US")
        val addr = shortcut("addr", "1 Main Street", "en_US")
        val store = FakeStore(locales = listOf("en_US"), entriesByLocale = mapOf("en_US" to listOf(sig, addr)))
        renderShortcuts(store)

        composeRule.onNodeWithText("sig").performTouchInput { longClick() }
        composeRule.waitForIdle()
        tap("addr")

        assertEquals(
            listOf(
                "2 selected",
                "2 shortcuts",
                "addr", "1 Main Street",
                "sig", "Best regards",
            ),
            screen()
        )

        composeRule.onNodeWithContentDescription("Delete selected").performClick()
        composeRule.waitForIdle()

        assertEquals(setOf(sig, addr), store.deleted.toSet())
        composeRule.onNodeWithText("Deleted 2 shortcuts").assertExists()
    }

    @Test
    fun aShortcutsLoadFailureIsReportedAndLeavesTheListEmpty() {
        renderShortcuts(FakeStore(locales = listOf("en_US"), loadThrows = true))

        assertEquals(
            listOf(
                "Text shortcuts",
                "No text shortcuts",
                "Tap Add shortcut to create shortcuts that expand to phrases",
                "Failed to load shortcuts: store unavailable",
                "Add shortcut",
            ),
            screen()
        )
    }

    @Test
    fun anInitialisationFailureIsReportedVerbatim() {
        renderShortcuts(
            FakeStore(
                locales = listOf("en_US"),
                initResult = DictionaryRepository.InitResult.Failure("Initialization timeout")
            )
        )

        assertEquals(
            listOf(
                "Text shortcuts",
                "No text shortcuts",
                "Tap Add shortcut to create shortcuts that expand to phrases",
                "Initialization timeout",
                "Add shortcut",
            ),
            screen()
        )
    }

    /** The recovery notice is shown alongside the loaded list, not instead of it. */
    @Test
    fun theRecoveryNoticeIsShownWhileTheRecoveredEntriesLoad() {
        renderShortcuts(
            FakeStore(
                locales = listOf("en_US"),
                entriesByLocale = mapOf("en_US" to listOf(shortcut("sig", "Best regards", "en_US"))),
                initResult = DictionaryRepository.InitResult.SuccessAfterRecovery
            )
        )

        assertEquals(
            listOf(
                "Text shortcuts",
                "1 shortcut",
                "sig", "Best regards",
                "Dictionary was recovered from corrupted state",
                "Add shortcut",
            ),
            screen()
        )
    }

    // =========================================================== user dictionary

    @Test
    fun personalWordsInOneLanguageAreOneGroupUnderTheirCount() {
        renderDictionary(
            FakeStore(
                locales = listOf("en_US"),
                entriesByLocale = mapOf(
                    "en_US" to listOf(word("Landrum", "en_US"), word("Kaptur", "en_US"))
                )
            )
        )

        assertEquals(
            listOf(
                dictionaryTitle,
                "2 words",
                // Sorted by word, lower-cased.
                "Kaptur",
                "Landrum",
                "Add word",
            ),
            screen()
        )
    }

    @Test
    fun onlyPersonalWordsAppearInTheDictionaryEditor() {
        renderDictionary(
            FakeStore(
                locales = listOf("en_US"),
                entriesByLocale = mapOf(
                    "en_US" to listOf(
                        word("Landrum", "en_US"),
                        // Stored form of a personal word: shortcut == word.
                        shortcut("Kaptur", "Kaptur", "en_US"),
                        shortcut("sig", "Best regards", "en_US"),
                    )
                )
            )
        )

        assertEquals(
            listOf(
                dictionaryTitle,
                "2 words",
                "Kaptur",
                "Landrum",
                "Add word",
            ),
            screen()
        )
    }

    @Test
    fun theSameWordInTwoLocalesIsKeptButADuplicateWithinALocaleIsDropped() {
        renderDictionary(
            FakeStore(
                locales = listOf("en_US", "fr_FR"),
                entriesByLocale = mapOf(
                    "en_US" to listOf(word("Landrum", "en_US"), word("Landrum", "en_US")),
                    "fr_FR" to listOf(word("Landrum", "fr_FR")),
                )
            )
        )

        assertEquals(
            listOf(
                dictionaryTitle,
                "2 words",
                "English (United States)",
                "Landrum",
                "French (France)",
                "Landrum",
                "Add word",
            ),
            screen()
        )
    }

    @Test
    fun theDictionarySearchMatchesTheWord() {
        renderDictionary(
            FakeStore(
                locales = listOf("en_US"),
                entriesByLocale = mapOf(
                    "en_US" to listOf(word("Landrum", "en_US"), word("Kaptur", "en_US"))
                )
            )
        )

        search("land")

        assertEquals(
            listOf(
                "1 of 2 words",
                "Landrum",
                "Add word",
            ),
            screen()
        )
    }

    @Test
    fun anEmptyDictionaryShowsItsOwnEmptyState() {
        renderDictionary(FakeStore(locales = listOf("en_US")))

        assertEquals(
            listOf(
                dictionaryTitle,
                "No personal words",
                "Tap Add word to add custom words to your dictionary",
                "Add word",
            ),
            screen()
        )
    }

    @Test
    fun aDictionaryLoadFailureIsReportedWithItsOwnWording() {
        renderDictionary(FakeStore(locales = listOf("en_US"), loadThrows = true))

        assertEquals(
            listOf(
                dictionaryTitle,
                "No personal words",
                "Tap Add word to add custom words to your dictionary",
                "Failed to load dictionary: store unavailable",
                "Add word",
            ),
            screen()
        )
    }

    @Test
    fun theAllLanguagesBucketGetsItsOwnHeader() {
        // getLocales() puts "" first when more than one locale exists; it means "all languages".
        renderDictionary(
            FakeStore(
                locales = listOf("", "en_US"),
                entriesByLocale = mapOf(
                    "" to listOf(word("Landrum", "")),
                    "en_US" to listOf(word("Kaptur", "en_US")),
                )
            )
        )

        assertEquals(
            listOf(
                dictionaryTitle,
                "2 words",
                context.getString(dev.bbkb.ime.R.string.user_dict_settings_all_languages),
                "Landrum",
                "English (United States)",
                "Kaptur",
                "Add word",
            ),
            screen()
        )
    }

    @Test
    fun tappingAWordOpensItsSheetNamingItsLanguage() {
        renderDictionary(
            FakeStore(
                locales = listOf("", "en_US"),
                entriesByLocale = mapOf("" to listOf(word("Landrum", "")))
            )
        )

        tap("Landrum")

        composeRule.onNodeWithText(context.getString(dev.bbkb.ime.R.string.user_dict_settings_all_languages)).assertExists()
        composeRule.onNodeWithText("Edit word").assertExists()
        composeRule.onNodeWithText("Delete").assertExists()
    }
}

package dev.bbkb.ime.core.settings.screens

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.settings.data.DictionaryEntry
import dev.bbkb.ime.core.settings.data.DictionaryEntryStore
import dev.bbkb.ime.core.settings.data.DictionaryRepository
import dev.bbkb.ime.core.settings.search.LocalSettingsHighlight
import dev.bbkb.ime.core.settings.search.SettingsHighlightController
import dev.bbkb.ime.core.settings.ui.BlackBerryTheme
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
 * wording, the search placeholder and predicate, the language headers, the row composition, the
 * chips, the de-duplication key, the sort order, and the two empty states. Those are precisely the
 * things a shared body has to keep telling apart, so they are what is pinned here.
 *
 * The assertions are whole-screen: every laid-out string, top to bottom, in the order the user
 * reads it. A row that appears, disappears, moves or changes its wording fails the case, which is
 * what makes this a safety net for a rewrite rather than a spot check.
 *
 * **Not covered:** the add/edit dialogs, on either screen. Under this Robolectric/Compose
 * combination a Material 3 text field inside an `AlertDialog` never reaches an idle composition —
 * it reproduces with entirely stock components — so no dialog in this codebase can be driven from a
 * unit test. That is why both screens keep their own hand-written dialog.
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

    private fun renderShortcuts(store: FakeStore) = render {
        TextShortcutsScreen(onNavigateToCustomMacros = {}, onNavigateBack = {}, storeFactory = { store })
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

    // ============================================================ text shortcuts

    @Test
    fun shortcutsAreGroupedUnderALanguageHeaderThatCountsThem() {
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
                "Text Shortcuts",
                "2 shortcuts",
                "ENGLISH (UNITED STATES) (2)",
                // Sorted by shortcut, lower-cased: "addr" before "sig".
                "addr", "1 Main Street",
                "sig", "Best regards",
                "Add shortcut",
            ),
            screen()
        )
    }

    @Test
    fun aShortcutContainingAMacroIsMarkedDynamicAndAFixedCaseOneIsMarkedTooAndBothCanApply() {
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
                "Text Shortcuts",
                "3 shortcuts",
                "ENGLISH (UNITED STATES) (3)",
                // A row is a PreferenceItem now, so its texts lay out title / trailing markers /
                // summary rather than headline / supporting content: the shortcut, then its
                // markers, then the phrase it expands to.
                "both", "Dynamic", "Fixed case", "%D",
                "iph", "Fixed case", "iPhone",
                "sig", "Dynamic", "Best regards,%n%D",
                "Add shortcut",
            ),
            screen()
        )
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
                "Text Shortcuts",
                "1 shortcuts",
                "ENGLISH (UNITED STATES) (1)",
                "sig", "Best regards",
                "Add shortcut",
            ),
            screen()
        )
    }

    @Test
    fun eachLocaleGetsItsOwnHeaderAndLocalesSortBeforeShortcuts() {
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
                "Text Shortcuts",
                "2 shortcuts",
                "ENGLISH (UNITED STATES) (1)",
                "sig", "Best regards",
                "FRENCH (FRANCE) (1)",
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
                "Text Shortcuts",
                "2 shortcuts",
                "ENGLISH (UNITED STATES) (1)",
                "sig", "Best regards",
                "FRENCH (FRANCE) (1)",
                "sig", "Cordialement",
                "Add shortcut",
            ),
            screen()
        )
    }

    @Test
    fun theShortcutsSearchMatchesEitherTheShortcutOrThePhrase() {
        val store = FakeStore(
            locales = listOf("en_US"),
            entriesByLocale = mapOf(
                "en_US" to listOf(
                    shortcut("sig", "Best regards", "en_US"),
                    shortcut("addr", "1 Main Street", "en_US"),
                )
            )
        )
        renderShortcuts(store)

        composeRule.onNodeWithContentDescription("Search").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Search shortcuts...").performTextInput("regards")
        composeRule.waitForIdle()

        assertEquals(
            listOf(
                "Text Shortcuts",
                // While searching, the counter reports the filtered rows it sits above.
                "1 shortcuts",
                "ENGLISH (UNITED STATES) (1)",
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

        composeRule.onNodeWithContentDescription("Search").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Search shortcuts...").performTextInput("zzz")
        composeRule.waitForIdle()

        assertEquals(
            listOf(
                "Text Shortcuts",
                "0 shortcuts",
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

        composeRule.onNodeWithContentDescription("Search").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Search shortcuts...").performTextInput("zzz")
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Close search").performClick()
        composeRule.waitForIdle()

        assertEquals(
            listOf(
                "Text Shortcuts",
                "1 shortcuts",
                "ENGLISH (UNITED STATES) (1)",
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
                "Text Shortcuts",
                "0 shortcuts",
                "No text shortcuts",
                "Tap Add shortcut to create shortcuts that expand to phrases",
                "Add shortcut",
            ),
            screen()
        )
    }

    @Test
    fun theMacroHelpDialogListsEveryBuiltInMacro() {
        renderShortcuts(FakeStore(locales = listOf("en_US")))

        composeRule.onNodeWithContentDescription("Macro help").performClick()
        composeRule.waitForIdle()

        // The set of macros this dialog documents is the set DynamicContentHandler expands.
        listOf("%D", "%T", "%d", "%t", "%n", "%w", "%y", "%b").forEach {
            composeRule.onNodeWithText(it).assertIsDisplayed()
        }
        composeRule.onNodeWithText("Dynamic Macros").assertIsDisplayed()
        composeRule.onNodeWithText("Got it").assertIsDisplayed()
    }

    @Test
    fun aShortcutsLoadFailureIsReportedAndLeavesTheListEmpty() {
        renderShortcuts(FakeStore(locales = listOf("en_US"), loadThrows = true))

        assertEquals(
            listOf(
                "Text Shortcuts",
                "0 shortcuts",
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
                "Text Shortcuts",
                "0 shortcuts",
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
                "Text Shortcuts",
                "1 shortcuts",
                "ENGLISH (UNITED STATES) (1)",
                "sig", "Best regards",
                "Dictionary was recovered from corrupted state",
                "Add shortcut",
            ),
            screen()
        )
    }

    // =========================================================== user dictionary

    @Test
    fun personalWordsAreGroupedUnderALanguageHeaderThatCountsThem() {
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
                context.getString(dev.bbkb.ime.R.string.settings_user_dictionary_screen_title),
                "2 words",
                "ENGLISH (UNITED STATES) (2)",
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
                context.getString(dev.bbkb.ime.R.string.settings_user_dictionary_screen_title),
                "2 words",
                "ENGLISH (UNITED STATES) (2)",
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
                context.getString(dev.bbkb.ime.R.string.settings_user_dictionary_screen_title),
                "2 words",
                "ENGLISH (UNITED STATES) (1)",
                "Landrum",
                "FRENCH (FRANCE) (1)",
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

        composeRule.onNodeWithContentDescription("Search").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Search words...").performTextInput("land")
        composeRule.waitForIdle()

        assertEquals(
            listOf(
                context.getString(dev.bbkb.ime.R.string.settings_user_dictionary_screen_title),
                "1 words",
                "ENGLISH (UNITED STATES) (1)",
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
                context.getString(dev.bbkb.ime.R.string.settings_user_dictionary_screen_title),
                "0 words",
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
                context.getString(dev.bbkb.ime.R.string.settings_user_dictionary_screen_title),
                "0 words",
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
                context.getString(dev.bbkb.ime.R.string.settings_user_dictionary_screen_title),
                "2 words",
                // Upper-cased like every other subhead: PreferenceCategory draws it now.
                context.getString(dev.bbkb.ime.R.string.user_dict_settings_all_languages)
                    .uppercase() + " (1)",
                "Landrum",
                "ENGLISH (UNITED STATES) (1)",
                "Kaptur",
                "Add word",
            ),
            screen()
        )
    }
}

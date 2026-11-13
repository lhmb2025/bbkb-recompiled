package dev.bbkb.ime.core.settings.screens

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.settings.search.LocalSettingsHighlight
import dev.bbkb.ime.core.settings.search.SettingsHighlightController
import dev.bbkb.ime.core.settings.ui.BlackBerryTheme
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins which `quick_phrase_N` key each quick-phrase row reads (the slideboard reads the same keys)
 * and the keyboard fallback phrases shown for unset keys. The write path is not covered: its
 * dialog's Material 3 text field never idles under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class QuickPhrasesScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Read and write through the same accessor the screen uses: PrefsManager memoises the
        // SharedPreferences instance process-wide, so a separately obtained one can be stale.
        prefs = PrefsManager.getPrefs(context)
        prefs.edit().clear().commit()
    }

    /**
     * Robolectric shares `PrefsManager`'s memoised prefs between test classes with the same
     * `@Config`, and `SettingsScreenRenderTest` renders this and `SlideboardLayoutScreen` fresh.
     */
    @After
    fun tearDown() {
        prefs.edit().clear().commit()
    }

    private fun render() {
        composeRule.setContent {
            BlackBerryTheme {
                CompositionLocalProvider(
                    LocalSettingsHighlight provides SettingsHighlightController()
                ) {
                    QuickPhrasesScreen(onNavigateBack = {})
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** Every rendered string, top to bottom, as the user reads down the screen. */
    private fun renderedTopToBottom(): List<String> =
        composeRule
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .filter { it.boundsInRoot.height > 0f }
            .sortedBy { it.boundsInRoot.top }
            .map { node -> node.config[SemanticsProperties.Text].joinToString("") { it.text } }

    @Test
    fun eachRowShowsTheValueStoredUnderItsOwnPrefKey() {
        prefs.edit()
            .putString("quick_phrase_1", "phrase one")
            .putString("quick_phrase_2", "phrase two")
            .putString("quick_phrase_3", "phrase three")
            .putString("quick_phrase_4", "phrase four")
            .putString("quick_phrase_5", "phrase five")
            .commit()

        render()

        assertEquals(
            listOf(
                "Quick phrases",
                "phrase one", "Quick phrase 1",
                "phrase two", "Quick phrase 2",
                "phrase three", "Quick phrase 3",
                "phrase four", "Quick phrase 4",
                "phrase five", "Quick phrase 5",
            ),
            renderedTopToBottom()
        )
    }

    @Test
    fun anUnsetPhraseFallsBackToTheDefaultTheKeyboardInserts() {
        // The keyboard's own fallback (R.string.pref_quick_phrase_N_default) is shown.
        render()

        assertEquals(
            listOf(
                "Quick phrases",
                "How are you?", "Quick phrase 1",
                "On my way", "Quick phrase 2",
                "That's great!", "Quick phrase 3",
                "Thank you", "Quick phrase 4",
                "I'm at work", "Quick phrase 5",
            ),
            renderedTopToBottom()
        )
    }

    @Test
    fun oneStoredPhraseDoesNotDisplaceTheDefaultsOfTheOthers() {
        prefs.edit().putString("quick_phrase_3", "Running late").commit()

        render()

        assertEquals(
            listOf(
                "Quick phrases",
                "How are you?", "Quick phrase 1",
                "On my way", "Quick phrase 2",
                "Running late", "Quick phrase 3",
                "Thank you", "Quick phrase 4",
                "I'm at work", "Quick phrase 5",
            ),
            renderedTopToBottom()
        )
    }

    @Test
    fun renderingTheScreenWritesNothing() {
        // The screen is a reader until the user edits something; a rewrite that seeded defaults
        // into the store would change what the slideboard falls back to for everyone.
        render()

        assertEquals(emptyMap<String, Any?>(), prefs.all)
    }
}

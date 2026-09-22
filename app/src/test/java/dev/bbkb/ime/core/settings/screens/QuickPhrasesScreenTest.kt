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
 * CHARACTERISATION TEST — which preference key each quick-phrase row is bound to.
 *
 * The five phrases are persisted user state (`quick_phrase_1` … `quick_phrase_5` in the default
 * preferences file) and the slideboard reads them back by those exact keys. The screen was five
 * copy-pasted blocks, so the one thing that could silently break when they became a loop is the
 * row-to-key binding: row 3 must go on showing, and writing, `quick_phrase_3`. These cases pin that
 * by giving every key a distinguishable value and reading the rendered rows back in order.
 *
 * They also pin the defaults the screen shows for an unset key: the keyboard's own fallback phrases.
 *
 * **Not covered:** the write path. Editing a phrase goes through `EditTextPreference`'s dialog, and
 * a Material 3 text field inside an `AlertDialog` never reaches an idle composition under this
 * Robolectric/Compose combination (it reproduces with entirely stock components), so the dialog
 * cannot be driven from a unit test at all.
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
     * Robolectric shares one sandbox — and therefore `PrefsManager`'s memoised SharedPreferences —
     * between test classes with the same `@Config`. `SettingsScreenRenderTest` renders both this
     * screen and `SlideboardLayoutScreen` in the fresh-install state, and both read these keys.
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
                "Quick phrase 1", "phrase one",
                "Quick phrase 2", "phrase two",
                "Quick phrase 3", "phrase three",
                "Quick phrase 4", "phrase four",
                "Quick phrase 5", "phrase five",
            ),
            renderedTopToBottom()
        )
    }

    @Test
    fun anUnsetPhraseFallsBackToTheDefaultTheKeyboardInserts() {
        // The keyboard's own fallback (SettingsValues → R.string.pref_quick_phrase_N_default) is now shown.
        render()

        assertEquals(
            listOf(
                "Quick phrases",
                "Quick phrase 1", "How are you?",
                "Quick phrase 2", "On my way",
                "Quick phrase 3", "That's great!",
                "Quick phrase 4", "Thank you",
                "Quick phrase 5", "I'm at work",
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
                "Quick phrase 1", "How are you?",
                "Quick phrase 2", "On my way",
                "Quick phrase 3", "Running late",
                "Quick phrase 4", "Thank you",
                "Quick phrase 5", "I'm at work",
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

package dev.bbkb.ime.core.settings.screens

import android.content.Context
import android.os.Build
import android.speech.RecognitionSupport
import android.speech.SpeechRecognizer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.settings.search.LocalSettingsHighlight
import dev.bbkb.ime.core.settings.search.SettingsHighlightController
import dev.bbkb.ime.core.settings.ui.BlackBerryTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSpeechRecognizer

/**
 * CHARACTERISATION TEST — [VoiceInputSettingsScreen] and [VoiceLanguageSelectionScreen].
 *
 * `SettingsScreenRenderTest` renders both in the state a fresh install produces, which for the
 * language picker means the "Loading available languages…" spinner and nothing else. So neither
 * the list it eventually shows nor anything either screen writes was covered.
 *
 * The picker discovers languages three ways and lays them out two ways, and the layout is what a
 * consolidation touches: with offline languages known it splits the list into an "Offline Ready"
 * section and a "Requires Network" section; without, it shows one flat list with no headers and
 * no offline icons. Both are pinned here — the flat one through the pre-API-33 ordered-broadcast
 * path with no receiver to answer it, which falls back to the built-in language list, and the
 * sectioned one by answering API 33's `checkRecognitionSupport` through Robolectric's shadow.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class VoiceScreensBehaviourTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** See `KeyLayoutEditorBehaviourTest`: the screens read through this process-wide singleton. */
    private val prefs get() = PrefsManager.getPrefs(context)

    @Before
    fun clearPreferences() {
        prefs.edit().clear().commit()
    }

    @After
    fun clearPreferencesAgain() {
        prefs.edit().clear().commit()
        ShadowSpeechRecognizer.reset()
    }

    private fun setContent(content: @Composable () -> Unit) {
        composeRule.setContent {
            BlackBerryTheme {
                CompositionLocalProvider(LocalSettingsHighlight provides SettingsHighlightController()) {
                    content()
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** Every rendered string, in reading order (top to bottom, then left to right). */
    private fun renderedInReadingOrder(): List<String> = composeRule
        .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
        .fetchSemanticsNodes()
        .filter { it.boundsInRoot.height > 0f }
        .sortedWith(compareBy({ Math.round(it.boundsInRoot.top) }, { it.boundsInRoot.left }))
        .map { node -> node.config[SemanticsProperties.Text].joinToString("") { it.text } }

    private fun rendered(): Set<String> = renderedInReadingOrder().toSet()

    private fun described(description: String) = composeRule.onAllNodes(
        SemanticsMatcher("ContentDescription = '$description'") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true
        },
        useUnmergedTree = true,
    ).fetchSemanticsNodes()

    // ========================================================================
    // The language picker, legacy discovery path (pre-API 33)
    // ========================================================================

    /**
     * No package answers `ACTION_GET_LANGUAGE_DETAILS`, so the ordered broadcast comes back with
     * nothing and the screen falls back to its built-in list. Offline support is unknown on this
     * path, so there are no sections and no offline icons.
     */
    @Test
    @Config(sdk = [32])
    fun theLegacyPathShowsOneUnsectionedListOfTheFallbackLanguages() {
        setContent { VoiceLanguageSelectionScreen({}) }

        val shown = rendered()
        assertFalse("an unsectioned list must not draw section headers", "OFFLINE READY" in shown)
        assertFalse("an unsectioned list must not draw section headers", "REQUIRES NETWORK" in shown)
        assertFalse("discovery finished, so the spinner is gone", "Loading available languages..." in shown)
        assertTrue("no offline state is known, so no offline icons", described("Offline ready").isEmpty())

        assertEquals(
            "languages are listed in sorted code order",
            listOf("ar-EG", "ar-SA", "de-AT"),
            renderedInReadingOrder().filter { it.matches(Regex("""[a-z]{2}-[A-Z]{2}""")) }.take(3),
        )

        // Only the visible window is composed, so reach for the rest. Both ends of the built-in
        // list are present, and each row carries the locale's own display name above its code.
        scrollTo("en-US")
        assertTrue("display names missing", "English (United States)" in rendered())
        scrollTo("zh-TW")
    }

    /** Brings a row of the language list into view; fails if the list does not contain it. */
    private fun scrollTo(text: String) {
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
        composeRule.waitForIdle()
        composeRule.onNodeWithText(text).assertIsDisplayed()
    }

    @Test
    @Config(sdk = [32])
    fun pickingALanguageStoresItAndLeavesTheScreen() {
        var backs = 0
        setContent { VoiceLanguageSelectionScreen({ backs++ }) }

        scrollTo("fr-CA")
        composeRule.onNodeWithText("fr-CA").performClick()
        composeRule.waitForIdle()

        assertEquals("fr-CA", prefs.getString("voice_input_language_list", null))
        assertEquals("picking a language returns to the previous screen", 1, backs)
    }

    @Test
    @Config(sdk = [32])
    fun theStoredLanguageIsTheCheckedOne() {
        prefs.edit().putString("voice_input_language_list", "de-DE").commit()
        setContent { VoiceLanguageSelectionScreen({}) }

        assertEquals("exactly one row is marked selected", 1, described("Selected").size)
    }

    // ========================================================================
    // The language picker, API 33 discovery path
    // ========================================================================

    /**
     * `checkRecognitionSupport` reports which languages are installed on the device and which are
     * merely supported; the screen unions them for the list and uses the installed set to split it
     * in two. Installed languages come first under "Offline Ready" and carry an offline icon.
     *
     * The headers are asserted upper-cased because they are drawn by `PreferenceCategory`, which
     * upper-cases every section subhead in settings; this screen used to style its own.
     */
    @Test
    @Config(sdk = [34])
    fun theModernPathSplitsInstalledLanguagesIntoTheirOwnSection() {
        setContent { VoiceLanguageSelectionScreen({}) }
        answerRecognitionSupport(installed = listOf("de-DE"), supported = listOf("en-US", "fr-FR"))

        val order = renderedInReadingOrder()
        assertEquals(
            "installed languages lead, under their own header, with the rest below",
            listOf("OFFLINE READY", "de-DE", "REQUIRES NETWORK", "en-US", "fr-FR"),
            order.filter { it in setOf("OFFLINE READY", "REQUIRES NETWORK", "de-DE", "en-US", "fr-FR") },
        )
        assertEquals("only the installed language is marked offline", 1, described("Offline ready").size)
    }

    /** With nothing installed there is no split to make, so the sections collapse to one list. */
    @Test
    @Config(sdk = [34])
    fun theModernPathShowsNoSectionsWhenNothingIsInstalledOffline() {
        setContent { VoiceLanguageSelectionScreen({}) }
        answerRecognitionSupport(installed = emptyList(), supported = listOf("en-US", "fr-FR"))

        val shown = rendered()
        assertFalse("no offline languages means no sections", "OFFLINE READY" in shown)
        assertFalse("no offline languages means no sections", "REQUIRES NETWORK" in shown)
        assertTrue("en-US missing", "en-US" in shown)
        assertTrue("no offline state is known, so no offline icons", described("Offline ready").isEmpty())
    }

    /**
     * With every available language installed there is nothing left for the second section, and
     * an empty section draws no header.
     */
    @Test
    @Config(sdk = [34])
    fun theCloudSectionIsOmittedWhenEverySupportedLanguageIsInstalled() {
        setContent { VoiceLanguageSelectionScreen({}) }
        answerRecognitionSupport(installed = listOf("de-DE", "en-US"), supported = listOf("de-DE"))

        val shown = rendered()
        assertTrue("the installed section is still headed", "OFFLINE READY" in shown)
        assertFalse("an empty section must not draw its header", "REQUIRES NETWORK" in shown)
        assertEquals("both languages are offline-ready", 2, described("Offline ready").size)
    }

    /** An empty support result is treated as a failed discovery and falls back to the built-in list. */
    @Test
    @Config(sdk = [34])
    fun anEmptySupportResultFallsBackToTheBuiltInList() {
        setContent { VoiceLanguageSelectionScreen({}) }
        answerRecognitionSupport(installed = emptyList(), supported = emptyList())

        assertFalse("the fallback list carries no offline information", "OFFLINE READY" in rendered())
        scrollTo("en-US")
        scrollTo("zh-TW")
    }

    /**
     * Hands the screen's [android.speech.RecognitionSupportCallback] a result. The callback runs on
     * the screen's shared executor and hops back to the main looper, so the composition is idled
     * until the list appears.
     */
    @Config(sdk = [34])
    private fun answerRecognitionSupport(installed: List<String>, supported: List<String>) {
        val recognizer = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
        assertTrue("the screen never created a SpeechRecognizer", recognizer != null)
        val support = RecognitionSupport.Builder()
            .setInstalledOnDeviceLanguages(installed)
            .setSupportedOnDeviceLanguages(supported)
            .setPendingOnDeviceLanguages(emptyList())
            .setOnlineLanguages(emptyList())
            .build()
        shadowOf(recognizer as SpeechRecognizer).triggerSupportResult(support)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            "Loading available languages..." !in rendered()
        }
    }

    // ========================================================================
    // Voice input settings
    // ========================================================================

    @Test
    @Config(sdk = [34])
    fun eachSwitchWritesItsOwnPreference() {
        setContent { VoiceInputSettingsScreen({}, {}) }

        // All four default to on, so one tap each turns them off.
        val rows = mapOf(
            "Enable built-in voice input" to "voice_input_enabled",
            "Auto-start listening" to "voice_input_auto_start",
            "Use keyboard language" to "voice_input_use_input_language",
            "Prefer offline recognition" to "voice_input_prefer_offline",
        )
        for ((title, key) in rows) {
            assertTrue("$title is not on screen", title in rendered())
        }
        // Turn the dependent rows off first: switching the master off disables them.
        for (title in listOf("Prefer offline recognition", "Use keyboard language", "Auto-start listening")) {
            composeRule.onNodeWithText(title).performClick()
            composeRule.waitForIdle()
        }
        composeRule.onNodeWithText("Enable built-in voice input").performClick()
        composeRule.waitForIdle()

        for ((title, key) in rows) {
            assertFalse("$title did not write $key", prefs.getBoolean(key, true))
        }
    }

    @Test
    @Config(sdk = [34])
    fun theDependentRowsFollowTheBuiltInVoiceSwitch() {
        prefs.edit().putBoolean("voice_input_enabled", false).commit()
        setContent { VoiceInputSettingsScreen({}, {}) }

        assertTrue("the master switch stays operable", isOperable("Enable built-in voice input"))
        assertFalse("Auto-start listening should be inert", isOperable("Auto-start listening"))
        assertFalse("Use keyboard language should be inert", isOperable("Use keyboard language"))
        assertFalse("Prefer offline recognition should be inert", isOperable("Prefer offline recognition"))

        // And with the master back on, all three come back.
        composeRule.onNodeWithText("Enable built-in voice input").performClick()
        composeRule.waitForIdle()
        assertTrue(isOperable("Auto-start listening"))
        assertTrue(isOperable("Use keyboard language"))
        assertTrue(isOperable("Prefer offline recognition"))
    }

    /**
     * A disabled preference row is given no `onClick` at all rather than a rejecting one, so
     * "operable" is simply "has a click action".
     */
    private fun isOperable(title: String) = composeRule
        .onAllNodes(hasClickAction() and hasText(title))
        .fetchSemanticsNodes().isNotEmpty()

    /** The language row exists only while voice input is on and is not following the keyboard. */
    @Test
    @Config(sdk = [34])
    fun theLanguageRowAppearsOnlyWhenTheKeyboardLanguageIsNotFollowed() {
        prefs.edit()
            .putBoolean("voice_input_use_input_language", false)
            .putString("voice_input_language_list", "fr-FR")
            .commit()
        var navigations = 0
        setContent { VoiceInputSettingsScreen({ navigations++ }, {}) }

        composeRule.onNodeWithText("Voice input language").assertIsDisplayed()
        // Its summary is the language's own display name, not the raw code.
        assertTrue("the row should summarise the stored language", "Français (France)" in rendered())
        assertFalse("the raw code is not shown here", "fr-FR" in rendered())

        composeRule.onNodeWithText("Voice input language").performClick()
        assertEquals(1, navigations)

        composeRule.onNodeWithText("Use keyboard language").performClick()
        composeRule.waitForIdle()
        assertFalse(
            "following the keyboard language takes the row away",
            "Voice input language" in rendered(),
        )
    }

    @Test
    @Config(sdk = [34])
    fun theLanguageRowIsHiddenWhileVoiceInputIsOff() {
        prefs.edit()
            .putBoolean("voice_input_enabled", false)
            .putBoolean("voice_input_use_input_language", false)
            .commit()
        setContent { VoiceInputSettingsScreen({}, {}) }

        assertFalse("Voice input language" in rendered())
    }

    /** Guards the `Build.VERSION` fork the two discovery paths hang off. */
    @Test
    @Config(sdk = [32])
    fun theLegacyPathIsTheOneBelowTiramisu() {
        assertTrue(Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
    }
}

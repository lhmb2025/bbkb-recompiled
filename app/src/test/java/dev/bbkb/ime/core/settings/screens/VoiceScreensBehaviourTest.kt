package dev.bbkb.ime.core.settings.screens

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Build
import android.provider.Settings
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.SpeechRecognizer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performScrollTo
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
 * Pins what [VoiceInputSettingsScreen] and [VoiceLanguageSelectionScreen] list and write. The
 * picker lists the chosen and keyboard languages first, then all by name, with a Voice chip on the
 * installed ones; both discovery paths are covered: pre-API-33 ordered broadcast with no receiver
 * (built-in list, offline unknown) and API 33 `checkRecognitionSupport` via Robolectric's shadow.
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
        Settings.Secure.putString(context.contentResolver, "voice_recognition_service", null)
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

    // The language picker, legacy discovery path (pre-API 33)

    private val voiceChip = "Voice input works offline"
    private val german = "Deutsch (Deutschland)"
    private val english = "English (United States)"
    private val french = "Français (France)"

    private fun selectedRows() = composeRule.onAllNodes(
        SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)
    ).fetchSemanticsNodes()

    /**
     * No package answers `ACTION_GET_LANGUAGE_DETAILS`, so the ordered broadcast comes back with
     * nothing and the screen falls back to its built-in list. Offline support is unknown on this
     * path, so no row carries the Voice chip. Rows show names, never the recogniser's raw tags.
     */
    @Test
    @Config(sdk = [32])
    fun theLegacyPathListsTheFallbackLanguagesByNameWithNoVoiceChips() {
        setContent { VoiceLanguageSelectionScreen({}) }

        val shown = rendered()
        assertFalse("discovery finished, so the spinner is gone", "Loading available languages…" in shown)
        assertTrue("the whole list is headed", "All languages" in shown)
        assertTrue("no offline state is known, so no Voice chips", described(voiceChip).isEmpty())
        assertFalse(
            "a row shows names, not the recogniser's tag",
            shown.any { it.matches(Regex("""[a-z]{2}-[A-Z]{2}""")) },
        )

        // Only the visible window is composed, so reach for the rest. A language is listed under
        // its own name, with its name in the phone's language beneath when the two differ.
        scrollTo(english)
        scrollTo(french)
        assertTrue("the phone-language name is the second line", "French (France)" in rendered())
    }

    /** Brings a row of the language list into view; fails if the list does not contain it. */
    private fun scrollTo(text: String) {
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText(text).onFirst().assertIsDisplayed()
    }

    @Test
    @Config(sdk = [32])
    fun pickingALanguageStoresItAndLeavesTheScreen() {
        var backs = 0
        setContent { VoiceLanguageSelectionScreen({ backs++ }) }

        scrollTo("Français (Canada)")
        composeRule.onNodeWithText("Français (Canada)").performClick()
        composeRule.waitForIdle()

        assertEquals("fr-CA", prefs.getString("voice_input_language_list", null))
        assertEquals("picking a language returns to the previous screen", 1, backs)
    }

    /** The stored language is repeated first, under "Your languages", and is the selected row. */
    @Test
    @Config(sdk = [32])
    fun theStoredLanguageIsPinnedFirstAndIsTheSelectedOne() {
        prefs.edit().putString("voice_input_language_list", "de-DE").commit()
        setContent { VoiceLanguageSelectionScreen({}) }

        val order = renderedInReadingOrder()
        assertEquals(
            "the chosen language leads, under its own header",
            listOf("Your languages", german),
            order.filter { it in setOf("Your languages", "All languages", german) }.take(2),
        )
        val selected = selectedRows()
        assertTrue("the chosen language is marked selected", selected.isNotEmpty())
        assertTrue(
            "and nothing else is",
            selected.all { node ->
                composeRule.onAllNodes(hasText(german)).fetchSemanticsNodes().any { it.id == node.id }
            },
        )
    }

    // The language picker, API 33 discovery path

    /**
     * `checkRecognitionSupport` reports which languages are installed on the device and which are
     * merely supported; the screen unions them into one list in alphabetical order of the names
     * shown, repeats the chosen language first, and gives the installed ones the Voice chip.
     */
    @Test
    @Config(sdk = [34])
    fun theModernPathListsEveryLanguageByNameWithTheChosenOneFirst() {
        setContent { VoiceLanguageSelectionScreen({}) }
        answerRecognitionSupport(installed = listOf("de-DE"), supported = listOf("fr-FR", "en-US"))

        val order = renderedInReadingOrder()
        assertEquals(
            "the chosen language is pinned, then every language in name order",
            listOf("Your languages", english, "All languages", german, english, french),
            order.filter { it in setOf("Your languages", "All languages", german, english, french) },
        )
        assertEquals("only the installed language carries the Voice chip", 1, described(voiceChip).size)
    }

    /** With nothing installed no row has the chip; the list itself is the same. */
    @Test
    @Config(sdk = [34])
    fun theModernPathShowsNoVoiceChipWhenNothingIsInstalledOffline() {
        setContent { VoiceLanguageSelectionScreen({}) }
        answerRecognitionSupport(installed = emptyList(), supported = listOf("en-US", "fr-FR"))

        val shown = rendered()
        assertTrue("English missing", english in shown)
        assertTrue("French missing", french in shown)
        assertTrue("nothing is installed, so no Voice chips", described(voiceChip).isEmpty())
    }

    /** A pinned row is the same language as its row in the full list, so it carries the chip too. */
    @Test
    @Config(sdk = [34])
    fun everyInstalledLanguageCarriesTheVoiceChipWhereverItIsListed() {
        setContent { VoiceLanguageSelectionScreen({}) }
        answerRecognitionSupport(installed = listOf("de-DE", "en-US"), supported = listOf("de-DE"))

        assertEquals(
            "English twice (pinned and listed) and German once",
            3, described(voiceChip).size,
        )
    }

    /**
     * Languages the service recognises over the network are listed too, without the chip, and a
     * language reported twice is listed once.
     */
    @Test
    @Config(sdk = [34])
    fun theModernPathListsOnlineLanguagesOnceAndWithoutAChip() {
        setContent { VoiceLanguageSelectionScreen({}) }
        answerRecognitionSupport(
            installed = listOf("de-DE"),
            supported = listOf("en-US"),
            online = listOf("en-US", "pt-BR"),
        )

        val order = renderedInReadingOrder()
        val portuguese = order.filter { it.startsWith("Português") }
        assertEquals("the online-only language is listed, once", 1, portuguese.size)
        assertEquals(
            "English is reported twice and listed once, after its pin",
            2, order.count { it == english },
        )
        assertEquals("only the installed language carries the Voice chip", 1, described(voiceChip).size)
    }

    /** An empty support result is treated as a failed discovery and falls back to the built-in list. */
    @Test
    @Config(sdk = [34])
    fun anEmptySupportResultFallsBackToTheBuiltInList() {
        setContent { VoiceLanguageSelectionScreen({}) }
        answerRecognitionSupport(installed = emptyList(), supported = emptyList())

        assertTrue("the fallback list carries no offline information", described(voiceChip).isEmpty())
        scrollTo(english)
        scrollTo(french)
    }

    // The language picker: search

    /**
     * The magnifier turns the top bar into a field. A language is found by its own name or by its
     * name in the phone's language; while a search is on there are no headers and no pinned repeat.
     */
    @Test
    @Config(sdk = [34])
    fun searchFindsALanguageByItsNameInThePhonesLanguage() {
        setContent { VoiceLanguageSelectionScreen({}) }
        answerRecognitionSupport(installed = emptyList(), supported = listOf("de-DE", "en-US", "fr-FR"))

        composeRule.onNodeWithContentDescription("Search").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("germ")
        composeRule.waitForIdle()

        val shown = rendered()
        assertTrue("German is found by its English name", german in shown)
        assertFalse("French does not match", french in shown)
        assertFalse("no headers while searching", "All languages" in shown || "Your languages" in shown)

        composeRule.onNode(hasSetTextAction()).performTextReplacement("zzz")
        composeRule.waitForIdle()
        assertTrue("nothing matches, and the screen says so", "No matches found" in rendered())
    }

    /** The pins: the chosen language, then the recogniser's language for each of the user's keyboards. */
    @Test
    @Config(sdk = [34])
    fun thePinnedLanguagesAreTheChosenOneThenTheKeyboards() {
        assertEquals(
            listOf("en-US", "fr-FR", "de-DE"),
            pinnedVoiceLanguages(
                selected = "en_US",
                keyboardTags = listOf("fr", "de", "en-US", "xx"),
                available = listOf("de-DE", "en-US", "fr-CA", "fr-FR"),
            ),
        )
    }

    /** A second line is only worth drawing when it says something the title does not. */
    @Test
    @Config(sdk = [34])
    fun thePhoneLanguageNameIsLeftOutWhenItIsTheSameAsTheLanguagesOwn() {
        assertEquals("German (Germany)", voiceLanguageLocalName("de-DE", german, java.util.Locale.US))
        assertEquals(null, voiceLanguageLocalName("en-US", english, java.util.Locale.US))
    }

    /**
     * Hands the screen's [android.speech.RecognitionSupportCallback] a result. The callback runs on
     * the screen's shared executor and hops back to the main looper, so the composition is idled
     * until the list appears.
     */
    @Config(sdk = [34])
    private fun answerRecognitionSupport(
        installed: List<String>,
        supported: List<String>,
        online: List<String> = emptyList(),
    ) {
        val recognizer = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
        assertTrue("the screen never created a SpeechRecognizer", recognizer != null)
        val support = RecognitionSupport.Builder()
            .setInstalledOnDeviceLanguages(installed)
            .setSupportedOnDeviceLanguages(supported)
            .setPendingOnDeviceLanguages(emptyList())
            .setOnlineLanguages(online)
            .build()
        shadowOf(recognizer as SpeechRecognizer).triggerSupportResult(support)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            "Loading available languages…" !in rendered()
        }
    }

    // Voice input settings

    @Test
    @Config(sdk = [34])
    fun eachSwitchWritesItsOwnPreference() {
        setContent { VoiceInputSettingsScreen({}, {}) }

        // All five default to on, so one tap each turns them off.
        val rows = mapOf(
            "Enable built-in voice input" to "voice_input_enabled",
            "Auto-start listening" to "voice_input_auto_start",
            "Use keyboard language" to "voice_input_use_input_language",
            "Prefer offline recognition" to "voice_input_prefer_offline",
            "Show words as you speak" to "voice_input_show_partial_results",
        )
        for ((title, key) in rows) {
            // The screen is five groups long, so a row may be below the fold.
            composeRule.onNodeWithText(title).assertExists("$title is not on screen")
        }
        // Turn the dependent rows off first: switching the master off disables them.
        for (title in listOf("Show words as you speak", "Prefer offline recognition", "Use keyboard language", "Auto-start listening")) {
            composeRule.onNodeWithText(title).performScrollTo().performClick()
            composeRule.waitForIdle()
        }
        composeRule.onNodeWithText("Enable built-in voice input").performScrollTo().performClick()
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
        assertFalse("Show words as you speak should be inert", isOperable("Show words as you speak"))
        assertFalse("Speech recognizer should be inert", isOperable("Speech recognizer"))

        // And with the master back on, they all come back.
        composeRule.onNodeWithText("Enable built-in voice input").performClick()
        composeRule.waitForIdle()
        assertTrue(isOperable("Speech recognizer"))
        assertTrue(isOperable("Auto-start listening"))
        assertTrue(isOperable("Use keyboard language"))
        assertTrue(isOperable("Prefer offline recognition"))
        assertTrue(isOperable("Show words as you speak"))
    }

    /**
     * A disabled preference row is given no `onClick` at all rather than a rejecting one, so
     * "operable" is simply "has a click action".
     */
    private fun isOperable(title: String) = composeRule
        .onAllNodes(hasClickAction() and hasText(title))
        .fetchSemanticsNodes().isNotEmpty()

    /**
     * The language row is always there. It opens the picker only while voice input is on and is
     * not following the keyboard; otherwise it is greyed out, not taken away.
     */
    @Test
    @Config(sdk = [34])
    fun theLanguageRowOpensOnlyWhenTheKeyboardLanguageIsNotFollowed() {
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
        assertTrue(
            "following the keyboard language greys the row out; it does not take it away",
            "Voice input language" in rendered(),
        )
        composeRule.onNodeWithText("Voice input language").performClick()
        assertEquals("a greyed-out row opens nothing", 1, navigations)
    }

    @Test
    @Config(sdk = [34])
    fun theLanguageRowIsGreyedOutWhileVoiceInputIsOff() {
        prefs.edit()
            .putBoolean("voice_input_enabled", false)
            .putBoolean("voice_input_use_input_language", false)
            .commit()
        var navigations = 0
        setContent { VoiceInputSettingsScreen({ navigations++ }, {}) }

        assertTrue("Voice input language" in rendered())
        composeRule.onNodeWithText("Voice input language").performClick()
        assertEquals(0, navigations)
    }

    // Speech recognizer

    private val googleApp = ComponentName(
        "com.google.android.googlequicksearchbox",
        "com.google.android.voicesearch.serviceapi.GoogleRecognitionService",
    )
    private val claude = ComponentName("com.anthropic.claude", "com.anthropic.claude.voice.RecognitionService")

    /** A recognition app the package query can see, labelled as its launcher would show it. */
    private fun installRecognizer(component: ComponentName, label: String) {
        val app = ApplicationInfo().apply {
            packageName = component.packageName
            nonLocalizedLabel = label
        }
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = component.packageName
            applicationInfo = app
        })
        shadowOf(context.packageManager).addResolveInfoForIntent(
            Intent(RecognitionService.SERVICE_INTERFACE),
            ResolveInfo().apply {
                serviceInfo = ServiceInfo().apply {
                    packageName = component.packageName
                    name = component.className
                    applicationInfo = app
                }
            })
    }

    private fun selectSystemService(component: ComponentName?) {
        Settings.Secure.putString(context.contentResolver, "voice_recognition_service", component?.flattenToString())
    }

    /**
     * The Google app as the phone's default and Claude installed besides: the row names the
     * default's app, the dialog lists the default then each app by label, and a pick stores its component.
     */
    @Test
    @Config(sdk = [34])
    fun theSpeechRecognizerRowListsTheInstalledAppsAndStoresTheChoice() {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(false)
        installRecognizer(googleApp, "Google")
        installRecognizer(claude, "Claude")
        selectSystemService(googleApp)
        setContent { VoiceInputSettingsScreen({}, {}) }

        assertTrue("the default is named after the app it is", "System default (Google)" in rendered())

        composeRule.onNodeWithText("Speech recognizer").performClick()
        composeRule.waitForIdle()
        assertEquals(
            "the system default leads, then each installed app by label",
            listOf("Speech recognizer", "System default (Google)", "Claude", "Google", "Cancel"),
            dialogTextInOrder(),
        )

        composeRule.onNodeWithText("Claude").performClick()
        composeRule.waitForIdle()

        assertEquals(claude.flattenToString(), prefs.getString("voice_input_recognizer", null))
        assertTrue("the row now shows the choice", "Claude" in rendered())
        assertFalse("System default (Google)" in rendered())
    }

    @Test
    @Config(sdk = [34])
    fun theOnDeviceRecognizerIsOfferedWhereThePhoneHasOne() {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        installRecognizer(claude, "Claude")
        setContent { VoiceInputSettingsScreen({}, {}) }

        composeRule.onNodeWithText("Speech recognizer").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("On-device recognizer").performClick()
        composeRule.waitForIdle()

        assertEquals("ondevice", prefs.getString("voice_input_recognizer", null))
    }

    /** No recognition app at all: the summary says so and names apps to install, as plain text. */
    @Test
    @Config(sdk = [34])
    fun withNoRecognitionAppTheRowSaysSoAndSuggestsSome() {
        setContent { VoiceInputSettingsScreen({}, {}) }

        val summary = rendered().single { it.startsWith("No speech recognition app is installed") }
        for (app in listOf("Sayboard", "WhisperIME", "Transcribro")) {
            assertTrue("$app missing from: $summary", app in summary)
        }
    }

    /** What dictation will actually use: an app uninstalled since it was chosen reads as the default. */
    @Test
    @Config(sdk = [34])
    fun aChosenAppThatIsGoneReadsAsTheSystemDefault() {
        installRecognizer(claude, "Claude")
        prefs.edit().putString("voice_input_recognizer", "com.gone/com.gone.Recognizer").commit()
        setContent { VoiceInputSettingsScreen({}, {}) }

        assertTrue("System default" in rendered())
    }

    /**
     * The language picker asks the chosen recogniser, and remembers its answer under that
     * recogniser's own key, leaving the system default's list alone.
     */
    @Test
    @Config(sdk = [34])
    fun theLanguagePickerAsksTheChosenRecognizerAndKeepsItsListSeparate() {
        installRecognizer(claude, "Claude")
        prefs.edit()
            .putString("voice_input_recognizer", claude.flattenToString())
            .putString("voice_input_language_cache", "xx-XX")
            .commit()
        setContent { VoiceLanguageSelectionScreen({}) }

        val recognizer = ShadowSpeechRecognizer.getLatestSpeechRecognizer()!!
        assertEquals("built for the chosen app, not the system default", claude, boundComponent(recognizer))
        answerRecognitionSupport(installed = listOf("de-DE"), supported = listOf("en-US"))

        assertEquals("de-DE,en-US", prefs.getString("voice_input_language_cache_" + claude.flattenToString(), null))
        assertEquals("the default's list is untouched", "xx-XX", prefs.getString("voice_input_language_cache", null))
    }

    /** When the chosen recogniser cannot be asked, the default's remembered list must not stand in. */
    @Test
    @Config(sdk = [34])
    fun aFailedDiscoveryNeverShowsAnotherRecognizersRememberedList() {
        installRecognizer(claude, "Claude")
        prefs.edit()
            .putString("voice_input_recognizer", claude.flattenToString())
            .putString("voice_input_language_cache", "xx-XX")
            .commit()
        setContent { VoiceLanguageSelectionScreen({}) }

        shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer()!!).triggerSupportError(SpeechRecognizer.ERROR_SERVER)
        composeRule.waitUntil(timeoutMillis = 5_000) { "Loading available languages…" !in rendered() }

        assertFalse(rendered().any { "XX" in it })
        scrollTo(english)
    }

    /** The open dialog's text, top to bottom. */
    private fun dialogTextInOrder(): List<String> = composeRule
        .onAllNodes(hasAnyAncestor(isDialog()) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
        .fetchSemanticsNodes()
        .sortedWith(compareBy({ Math.round(it.boundsInRoot.top) }, { it.boundsInRoot.left }))
        .map { node -> node.config[SemanticsProperties.Text].joinToString("") { it.text } }

    private fun boundComponent(recognizer: SpeechRecognizer): ComponentName? =
        SpeechRecognizer::class.java.getDeclaredField("mServiceComponent")
            .apply { isAccessible = true }.get(recognizer) as ComponentName?

    /** Guards the `Build.VERSION` fork the two discovery paths hang off. */
    @Test
    @Config(sdk = [32])
    fun theLegacyPathIsTheOneBelowTiramisu() {
        assertTrue(Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
    }
}

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
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
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

    /**
     * The languages the service recognises over the network are listed too. They used to be left
     * out, so a recogniser with few on-device models offered only those. Not installed, so they
     * sit under "Requires Network" with the other cloud languages.
     */
    @Test
    @Config(sdk = [34])
    fun theModernPathListsOnlineLanguagesUnderRequiresNetwork() {
        setContent { VoiceLanguageSelectionScreen({}) }
        answerRecognitionSupport(
            installed = listOf("de-DE"),
            supported = listOf("en-US"),
            online = listOf("en-US", "pt-BR"),
        )

        val order = renderedInReadingOrder()
        assertEquals(
            "online languages join the network section, without duplicates",
            listOf("OFFLINE READY", "de-DE", "REQUIRES NETWORK", "en-US", "pt-BR"),
            order.filter { it in setOf("OFFLINE READY", "REQUIRES NETWORK", "de-DE", "en-US", "pt-BR") },
        )
        assertEquals("only the installed language is marked offline", 1, described("Offline ready").size)
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

        // All five default to on, so one tap each turns them off.
        val rows = mapOf(
            "Enable built-in voice input" to "voice_input_enabled",
            "Auto-start listening" to "voice_input_auto_start",
            "Use keyboard language" to "voice_input_use_input_language",
            "Prefer offline recognition" to "voice_input_prefer_offline",
            "Show words as you speak" to "voice_input_show_partial_results",
        )
        for ((title, key) in rows) {
            assertTrue("$title is not on screen", title in rendered())
        }
        // Turn the dependent rows off first: switching the master off disables them.
        for (title in listOf("Show words as you speak", "Prefer offline recognition", "Use keyboard language", "Auto-start listening")) {
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

    // ========================================================================
    // Speech recognizer
    // ========================================================================

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
     * The KEY2's shape: the Google app as the phone's default and Claude installed besides. The row
     * says which app the default is, the dialog lists the default, then each app by its label, and
     * picking one stores its component.
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
        composeRule.waitUntil(timeoutMillis = 5_000) { "Loading available languages..." !in rendered() }

        assertFalse("xx-XX" in rendered())
        scrollTo("en-US")
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

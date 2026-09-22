package dev.bbkb.ime.core.settings.screens

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.device.profile.DeviceProfile
import dev.bbkb.ime.core.distribution.DistributionConfig
import dev.bbkb.ime.core.distribution.LocalHttpServer
import dev.bbkb.ime.core.distribution.ManifestSource
import dev.bbkb.ime.core.locale.ResourceLocaleUtils
import dev.bbkb.ime.core.locale.RichInputMethodManager
import dev.bbkb.ime.core.locale.multilanguage.MultiLanguageRepository
import dev.bbkb.ime.core.settings.PrefsManager
import dev.bbkb.ime.core.settings.SettingsRoute
import dev.bbkb.ime.core.settings.search.LocalSettingsHighlight
import dev.bbkb.ime.core.settings.search.SearchDeviceCapabilities
import dev.bbkb.ime.core.settings.search.SettingsSearchIndex
import dev.bbkb.ime.core.settings.search.SettingsHighlightController
import dev.bbkb.ime.core.settings.ui.BlackBerryTheme
import dev.bbkb.ime.R
import org.junit.Assert.assertEquals
import org.junit.Assume
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * CHARACTERISATION TEST — renders every Compose settings screen and pins what it puts on screen.
 *
 * The plan's Wave 2 line asks for "one Compose test rendering every `ScreenSpec`". `ScreenSpec`
 * does not exist: it is the type Wave 3 proposes to introduce when the 39 hand-written screens
 * under `core/settings/screens/` are collapsed into a declarative spec table plus one renderer.
 * So this is the characterisation form of that test — it renders the 39 screens as they are today
 * and records their output, which is exactly the safety net the Wave 3 rewrite needs: point
 * [contentFor] at the new renderer, change nothing else, and every row must still come out.
 *
 * Per screen it asserts three things:
 *
 *  1. it composes and lays out without throwing;
 *  2. its top-app-bar title is the topmost laid-out text on screen;
 *  3. the **exact** set of text it renders, split into
 *     - `resources`: the canonical `R.string` name for each rendered string. Several resources can
 *       share a value (four of them are literally "BBKB"); the name recorded is the
 *       lexicographically first, so the mapping is deterministic. Recording names rather than the
 *       English copy means a wording change does not fail this test — only a row appearing,
 *       disappearing, or switching to a different resource does.
 *     - `literals`: text the screen hardcodes in Kotlin rather than reading from a resource. These
 *       are recorded verbatim, and there are more of them than you would hope — see the report.
 *
 * Values shorter than four characters are not resolved to resource names (a screen full of "3",
 * "(" and "@" would otherwise match a dozen unrelated config strings), so single symbols land in
 * `literals`.
 *
 * The screens are rendered in the state a fresh install produces: no preferences written, an empty
 * user dictionary, no custom macros, no multi-language configurations. Two screens take a variant
 * argument and so appear twice (`CustomSymbolPageScreen` for PKB and VKB), which is why 39 files
 * give 40 cases.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class SettingsScreenRenderTest(private val screenName: String) {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * @param name    key into [contentFor]; usually the composable's file name.
     * @param route   the [SettingsRoute] the settings NavHost reaches this screen by. Not asserted
     *                here — it is what lets a future test line the screens up against
     *                `SettingsSearchIndex`, and it documents which route each file serves.
     * @param title   the top-app-bar title: the topmost text that is actually laid out.
     * @param resources canonical `R.string` names for every resource-backed string rendered.
     * @param literals  every rendered string that is not a string resource, verbatim.
     */
    private data class Case(
        val name: String,
        val route: String,
        val title: String,
        val resources: Set<String>,
        val literals: Set<String>,
        /**
         * Hardcoded text a screen renders only after a load on `Dispatchers.IO` finishes.
         * `waitForIdle()` cannot see that work, so whether these are on screen at snapshot time is
         * a race (machine load decides it). They may be present; nothing outside `literals` and
         * `lateLiterals` may be.
         */
        val lateLiterals: Set<String> = emptySet(),
        /**
         * The same tolerance for resource-backed text: `R.string` names a screen renders only
         * once an IO load has finished, and therefore may or may not be on screen when the
         * snapshot is taken.
         *
         * Added for `LanguagePacksScreen`, whose downloadable-catalogue section reports whatever
         * the fetch came back with. Under Robolectric that is always a failure (there is no
         * network and no cached manifest), but *which* failure and whether it has landed yet are
         * not things this test should pin. Everything a screen renders unconditionally still
         * belongs in `resources`, which stays exact.
         */
        val lateResources: Set<String> = emptySet(),
    )

    /**
     * Version/build text moves on every release; it is the one thing not pinned. The optional
     * " · <build type>" tail is the Updates screen's version row, which names the build type
     * after the version for the same reason the channel row exists: a debug build and a release
     * build are different things to be looking at an update list from.
     */
    private val volatileText = Regex("""^Version .*\(Build \d+\)( · \w+)?$""")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ResourceLocaleUtils.reinit(context)
        DeviceProfile.initialize(context)
        registerThisImeWithTheFramework(context)
        keepTheCatalogueFetchOffTheRealNetwork(context)
    }

    /**
     * `LanguagePacksScreen` asks for the published pack catalogue when it opens, and a unit test
     * must never reach the internet to answer that.
     *
     * Robolectric reports no active network, so `ManifestSource` already fails with
     * `OfflineException` without opening a socket — but that is a default of the shadow, not a
     * promise. The debug-only manifest-URL override (inert on release builds) pins it to a port
     * with nothing listening, so the fetch fails locally whatever the shadow decides. Either way
     * the screen renders its "no catalogue" state, which is what
     * [Case.lateResources] tolerates.
     */
    private fun keepTheCatalogueFetchOffTheRealNetwork(context: Context) {
        PrefsManager.getPrefs(context).edit()
            .putString(
                DistributionConfig.PREF_MANIFEST_URL,
                "http://127.0.0.1:${LocalHttpServer.closedPort()}/manifest.json",
            )
            .apply()
        ManifestSource(context).clearCache()
    }

    /**
     * Puts the framework in the state a user who has actually enabled this keyboard is in: our IME
     * registered, with the real 89 subtypes built from the manifest service. `MultiLanguageWizard`
     * reads that list to populate its language pickers, so without it the wizard renders its
     * empty state and the recorded rows below would describe a state no ordinary user is in.
     *
     * This used to be a **workaround**, not a fixture: `MultiLanguageRepository` dereferenced the
     * null `InputMethodInfo` that `RichInputMethodManager.getInputMethodInfoOfThisIme()` returns
     * when the framework does not know us, so the wizard crashed before rendering and could not be
     * tested at all without it. Wave 2.5 defect 1 fixed that in production; what remains here is
     * only the "IME is enabled" setup, and the null state now has its own case —
     * [rendersWithoutCrashingWhenThisImeIsNotRegisteredWithTheFramework].
     *
     * The two singleton resets are unrelated to either: both are process-wide, and both would
     * otherwise leak whatever the previous parameterised run left behind.
     */
    private fun registerThisImeWithTheFramework(context: Context) {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val infos = context.packageManager
            .queryIntentServices(Intent("android.view.InputMethod"), PackageManager.GET_META_DATA)
            .map { InputMethodInfo(context, it) }
        assertEquals("the IME service is no longer declared in the manifest", 1, infos.size)
        assertEquals("method.xml subtype count changed", 89, infos[0].subtypeCount)
        Shadows.shadowOf(imm).setInputMethodInfoList(infos)
        Shadows.shadowOf(imm).setEnabledInputMethodInfoList(infos)
        MultiLanguageRepository.clearInstance()
        RichInputMethodManager.init(context)
    }

    /**
     * The inverse of [registerThisImeWithTheFramework]: the framework has no `InputMethodInfo` for
     * us at all, so `getInputMethodInfoOfThisIme()` returns null. On a device that is the state
     * between installing the keyboard and enabling it in system settings, and again for the moment
     * a package update is in flight.
     */
    private fun deregisterThisImeFromTheFramework(context: Context) {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        Shadows.shadowOf(imm).setInputMethodInfoList(emptyList())
        Shadows.shadowOf(imm).setEnabledInputMethodInfoList(emptyList())
        // Both singletons cached the registered state in setUp; drop it.
        RichInputMethodManager.getInstance().clearSubtypeCaches()
        MultiLanguageRepository.clearInstance()
    }

    /**
     * The single point Wave 3 re-aims. Today each entry calls the hand-written composable; after
     * the rewrite each should call the one renderer with that screen's spec, and every assertion
     * below stays as it is.
     */
    private fun contentFor(name: String): @Composable () -> Unit = when (name) {
        "AdvancedGestureParametersScreen" -> ({ AdvancedGestureParametersScreen({}) })
        "AdvancedSettingsScreen" -> ({ AdvancedSettingsScreen({}, {}, {}, {}) })
        "AnimationParametersScreen" -> ({ AnimationParametersScreen({}) })
        "AppearanceLayoutScreen" -> ({ AppearanceLayoutScreen({}, {}, {}) })
        "AutoCorrectionScreen" -> ({ AutoCorrectionScreen({}) })
        "CkbGesturesScreen" -> ({ CkbGesturesScreen({}) })
        "CorrectionLearningScreen" -> ({ CorrectionLearningScreen({}, {}, {}, {}) })
        "CreditsScreen" -> ({ CreditsScreen({}, {}) })
        "CustomMacrosScreen" -> ({ CustomMacrosScreen({}) })
        "CustomSymbolPageScreen_PKB" -> ({ CustomSymbolPageScreen(isPkb = true, onBack = {}) })
        "CustomSymbolPageScreen_VKB" -> ({ CustomSymbolPageScreen(isPkb = false, onBack = {}) })
        "CustomizeMenuScreen" -> ({ CustomizeMenuScreen({}) })
        "CustomizeSlideBoardScreen" -> ({ CustomizeSlideBoardScreen({}) })
        "DebugSettingsScreen" -> ({ DebugSettingsScreen({}, {}, {}, {}) })
        "DeviceCompatibilityScreen" -> ({ DeviceCompatibilityScreen({}, {}, {}) })
        "DeviceConfigurationScreen" -> ({ DeviceConfigurationScreen({}, {}) })
        "DictionariesLearningScreen" -> ({ DictionariesLearningScreen({}, {}, {}, {}) })
        "GestureLabScreen" -> ({ GestureLabScreen({}) })
        "KeyPressFeedbackScreen" -> ({ KeyPressFeedbackScreen({}) })
        "KeyboardHelperScreen" -> ({ KeyboardHelperScreen({}) })
        "LanguagePacksScreen" -> ({ LanguagePacksScreen({}) })
        "LanguageSwitchingScreen" -> ({ LanguageSwitchingScreen({}) })
        "LanguagesInputScreen" -> ({ LanguagesInputScreen({}, {}, {}, {}) })
        "MainSettingsScreen" -> ({ MainSettingsScreen({}, {}) })
        "MultiLanguageKeyboardsScreen" -> ({ MultiLanguageKeyboardsScreen({}, {}, {}) })
        "MultiLanguageWizardScreen_ADD" -> ({ MultiLanguageWizardScreen(WizardMode.ADD, null, {}, {}) })
        "TouchScreenKeyboardScreen" -> ({ TouchScreenKeyboardScreen({}, {}, {}) })
        "PhysicalKeyboardScreen" -> ({ PhysicalKeyboardScreen({}, {}) })
        "PredictionsSuggestionsScreen" -> ({ PredictionsSuggestionsScreen({}) })
        "QuickPhrasesScreen" -> ({ QuickPhrasesScreen({}) })
        "ShakeGesturesScreen" -> ({ ShakeGesturesScreen({}) })
        "SlideboardLayoutScreen" -> ({ SlideboardLayoutScreen({}, {}, {}) })
        "SpellCheckerSettingsScreen" -> ({ SpellCheckerSettingsScreen({}) })
        "SymbolCustomizationScreen" -> ({ SymbolCustomizationScreen({}, {}, {}) })
        "TextShortcutsScreen" -> ({ TextShortcutsScreen({}, {}) })
        "CustomizationScreen" -> ({ CustomizationScreen({}, {}, {}, {}, {}) })
        "UpdatesScreen" -> ({ UpdatesScreen({}) })
        "UserDictionaryScreen" -> ({ UserDictionaryScreen({}) })
        "VoiceInputSettingsScreen" -> ({ VoiceInputSettingsScreen({}, {}) })
        "VoiceLanguageSelectionScreen" -> ({ VoiceLanguageSelectionScreen({}) })
        "WordListEditorScreen" -> ({ WordListEditorScreen(false, {}) })
        else -> throw IllegalArgumentException("no composable registered for '$name'")
    }

    @Test
    fun rendersItsTitleAndExactlyTheRowsItRenderedBefore() {
        val case = CASES.single { it.name == screenName }
        val context = ApplicationProvider.getApplicationContext<Context>()

        val content = contentFor(case.name)
        composeRule.setContent {
            BlackBerryTheme {
                CompositionLocalProvider(LocalSettingsHighlight provides SettingsHighlightController()) {
                    content()
                }
            }
        }
        composeRule.waitForIdle()

        val index = resourceNameIndex(context)
        fun textNodes() = composeRule
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes()
        fun renderedText(nodes: List<SemanticsNode>) = nodes
            .flatMap { node -> node.config[SemanticsProperties.Text].map { it.text } }
            .filterNot { volatileText.matches(it) }
            .toSet()


        val nodes = textNodes()
        assertTrue("${case.name} rendered no text at all", nodes.isNotEmpty())

        // 2 — the app-bar title is the topmost text that actually got laid out.
        val topmost = nodes.filter { it.boundsInRoot.height > 0f }.minByOrNull { it.boundsInRoot.top }
        assertTrue("${case.name} laid out no text", topmost != null)
        assertEquals(
            "${case.name} top-app-bar title",
            case.title,
            topmost!!.config[SemanticsProperties.Text].joinToString("") { it.text }
        )

        // 3 — exactly the same rows as before, by resource name and by hardcoded literal.
        val rendered = renderedText(nodes)
        val actualResources = rendered.mapNotNull { index[it] }.toSet()
        val actualLiterals = rendered.filter { index[it] == null }.toSet()

        val settledResources = actualResources - case.lateResources
        assertEquals(
            "${case.name}: the set of string resources it renders changed\n" +
                "  gained: ${(settledResources - case.resources).sorted()}\n" +
                "  lost:   ${(case.resources - settledResources).sorted()}",
            case.resources, settledResources
        )
        val settledLiterals = actualLiterals - case.lateLiterals
        assertEquals(
            "${case.name}: the set of hardcoded (non-resource) strings it renders changed\n" +
                "  gained: ${(settledLiterals - case.literals).sorted()}\n" +
                "  lost:   ${(case.literals - settledLiterals).sorted()}",
            case.literals, settledLiterals
        )
    }

    /**
     * Wave 2.5 defect 1 — every screen must render when the framework has no `InputMethodInfo`
     * for this IME.
     *
     * `RichInputMethodManager.getInputMethodInfoOfThisIme()` deliberately returns null in that
     * state rather than propagating the cache supplier's `RuntimeException`, and callers used to
     * dereference it: `MultiLanguageRepository.loadAvailableLocales()` NPE'd on
     * `getSubtypeCount()`, which took `MultiLanguageWizardScreen` out before it drew anything.
     * A user hits this by opening settings between installing the keyboard and enabling it — the
     * one moment they are most likely to be in settings.
     *
     * Correct behaviour: an IME the framework does not know about has no subtypes, so the screen
     * renders with an empty language list. Asserted for every screen because any of them may grow
     * a call into [RichInputMethodManager]; the content assertions in
     * [rendersItsTitleAndExactlyTheRowsItRenderedBefore] stay pinned to the enabled state, which
     * is what an ordinary user sees.
     */
    @Test
    fun rendersWithoutCrashingWhenThisImeIsNotRegisteredWithTheFramework() {
        val case = CASES.single { it.name == screenName }
        val context = ApplicationProvider.getApplicationContext<Context>()
        deregisterThisImeFromTheFramework(context)

        // The production null: this is the value the fixed callers now have to cope with. Its
        // contract is deliberately unchanged — two callers branch on it — so the fix is in the
        // callers, and these assert each of them degrades instead of dereferencing.
        val richImm = RichInputMethodManager.getInstance()
        assertEquals(
            "getInputMethodInfoOfThisIme() no longer returns null for an unregistered IME — " +
                "this case is asserting nothing",
            null, richImm.inputMethodInfoOfThisIme
        )
        assertEquals("getInputMethodIdOfThisIme()", null, richImm.inputMethodIdOfThisIme)
        assertTrue("getEnabledSubtypesOfThisIme()", richImm.enabledSubtypesOfThisIme.isEmpty())
        assertTrue(
            "getMyEnabledInputMethodSubtypeList()",
            richImm.getMyEnabledInputMethodSubtypeList(true).isEmpty()
        )
        assertTrue(
            "hasMultipleEnabledSubtypesInThisIme()",
            !richImm.hasMultipleEnabledSubtypesInThisIme(true)
        )
        assertTrue(
            "MultiLanguageRepository should report no locales when this IME is not registered",
            MultiLanguageRepository.getInstance(context).availableLocales.isEmpty()
        )

        val content = contentFor(case.name)
        composeRule.setContent {
            BlackBerryTheme {
                CompositionLocalProvider(LocalSettingsHighlight provides SettingsHighlightController()) {
                    content()
                }
            }
        }
        composeRule.waitForIdle()

        val nodes = composeRule
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertTrue("${case.name} rendered no text with no InputMethodInfo for this IME", nodes.isNotEmpty())

        // The title still comes out, so this is a real render and not a stray error surface.
        val topmost = nodes.filter { it.boundsInRoot.height > 0f }.minByOrNull { it.boundsInRoot.top }
        assertTrue("${case.name} laid out no text", topmost != null)
        assertEquals(
            "${case.name} top-app-bar title with no InputMethodInfo for this IME",
            case.title,
            topmost!!.config[SemanticsProperties.Text].joinToString("") { it.text }
        )
    }

    /**
     * Search-anchor cross-check — the structural guard for defect 16.
     *
     * [SettingsSearchIndex] is hand-maintained and each entry carries both a `route` and an
     * `anchor`; `SettingsSearchIndexTest` already proves every anchor string exists in *some*
     * screen source, but not that it exists in the screen the route leads to. A search result that
     * navigates to the right screen and then highlights nothing is exactly what that gap allows,
     * and it is invisible until someone tries it.
     *
     * The probe is the production mechanism itself: `Modifier.settingsSearchAnchor` calls
     * `SettingsHighlightController.consume()` once it has scrolled its row into view, so an anchor
     * that is still pending after the composition settles is an anchor that is not on this screen.
     *
     * The assertion is an **equality**, and both halves matter:
     *
     *  - an entry search offers (`requires` satisfied) whose row does not render is the defect —
     *    the user taps a result and lands somewhere it is not;
     *  - an entry search hides (`requires` unsatisfied) whose row *does* render is the inverse —
     *    a real, reachable setting that has quietly fallen out of search.
     *
     * So: the anchors this screen actually claims must be exactly the anchors the index routes
     * here and marks visible on this device.
     *
     * Screens the index claims no anchors for are skipped rather than asserted vacuously.
     *
     * Caveat, for Wave 3: `DeviceProfile` is a process-wide singleton, so this runs as one device
     * shape only — the touch-only one. It pins the touch half of every gated pair; the
     * physical-keyboard half is checked without rendering, by
     * `SettingsSearchIndexTest.deviceGatesFollowTheDeviceShape`.
     */
    @Test
    fun searchAnchorsClaimedByTheIndexAreReachableOnThisScreen() {
        val case = CASES.single { it.name == screenName }
        val routed = SettingsSearchIndex.entries
            .filter { it.route == case.route && it.anchor != null }
        val anchors = routed.mapNotNull { it.anchor }.distinct()
        Assume.assumeTrue("SettingsSearchIndex claims no anchors for ${case.route}", anchors.isNotEmpty())

        val capabilities = SearchDeviceCapabilities.current()
        val offeredBySearch = routed
            .filter { it.requires.isMetBy(capabilities) }
            .mapNotNull { it.anchor }
            .toSet()

        val controller = SettingsHighlightController()
        val content = contentFor(case.name)
        composeRule.setContent {
            BlackBerryTheme {
                CompositionLocalProvider(LocalSettingsHighlight provides controller) { content() }
            }
        }
        composeRule.waitForIdle()

        val claimedByTheScreen = anchors.filter { anchor ->
            controller.request(anchor)
            composeRule.waitForIdle()
            composeRule.mainClock.advanceTimeBy(4_000)
            composeRule.waitForIdle()
            val stillPending = controller.pendingAnchor != null
            controller.consume()
            !stillPending
        }.toSet()

        assertEquals(
            "${case.name} (route '${case.route}'): search offers a different set of anchors than " +
                "this screen renders on this device ($capabilities).\n" +
                "  offered but not rendered (a result that highlights nothing): " +
                "${(offeredBySearch - claimedByTheScreen).sorted()}\n" +
                "  rendered but not offered (a real setting search cannot reach): " +
                "${(claimedByTheScreen - offeredBySearch).sorted()}\n" +
                "Fix the entry's `requires` in SettingsSearchIndex so it mirrors the DeviceProfile " +
                "gate around the row.",
            offeredBySearch, claimedByTheScreen
        )
    }

    /**
     * Rendered text -> canonical `R.string` name. Built by reflection so it needs no upkeep, and
     * deliberately keeps only values of four characters or more: shorter ones ("1", "@", "ON")
     * collide with unrelated `config_*` strings and would make the mapping meaningless.
     */
    private fun resourceNameIndex(context: Context): Map<String, String> {
        val byValue = HashMap<String, String>()
        for (field in R.string::class.java.fields) {
            val id = try { field.getInt(null) } catch (t: Throwable) { continue }
            val value = try { context.getString(id) } catch (t: Throwable) { continue }
            if (value.length < 4) continue
            val existing = byValue[value]
            if (existing == null || field.name < existing) byValue[value] = field.name
        }
        return byValue
    }

    companion object {

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun screens(): List<Array<Any>> = SCREEN_NAMES.map { arrayOf<Any>(it) }

        /**
         * Declared as plain strings, not as [Case] objects: `@Parameters` runs outside
         * Robolectric's sandbox classloader, so anything richer would be built against the wrong
         * copy of the app's classes. The cases themselves are looked up inside the test.
         */
        private val SCREEN_NAMES = listOf(
            "AdvancedGestureParametersScreen", "AdvancedSettingsScreen", "AnimationParametersScreen",
            "AppearanceLayoutScreen", "AutoCorrectionScreen", "CkbGesturesScreen",
            "CorrectionLearningScreen", "CreditsScreen", "CustomMacrosScreen",
            "CustomSymbolPageScreen_PKB", "CustomSymbolPageScreen_VKB", "CustomizationScreen",
            "CustomizeMenuScreen", "CustomizeSlideBoardScreen", "DebugSettingsScreen",
            "DeviceCompatibilityScreen", "DeviceConfigurationScreen", "DictionariesLearningScreen",
            "GestureLabScreen", "KeyPressFeedbackScreen", "KeyboardHelperScreen",
            "LanguagePacksScreen", "LanguageSwitchingScreen", "LanguagesInputScreen",
            "MainSettingsScreen", "MultiLanguageKeyboardsScreen", "MultiLanguageWizardScreen_ADD",
            "PhysicalKeyboardScreen", "PredictionsSuggestionsScreen", "QuickPhrasesScreen",
            "ShakeGesturesScreen", "SlideboardLayoutScreen", "SpellCheckerSettingsScreen",
            "SymbolCustomizationScreen", "TextShortcutsScreen", "TouchScreenKeyboardScreen",
            "UpdatesScreen", "UserDictionaryScreen", "VoiceInputSettingsScreen",
            "VoiceLanguageSelectionScreen",
            "WordListEditorScreen",
        )
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════
    // The recorded output of every screen. Generated by rendering them, not written by hand.
    // ══════════════════════════════════════════════════════════════════════════════════════════

    private val CASES = listOf(
        Case(
            name = "AdvancedGestureParametersScreen",
            route = SettingsRoute.AdvancedGestureParameters.route,
            title = "Advanced gesture parameters",
            resources = setOf(
                "settings_advanced_gesture_params_title", "settings_gesture_accent_max_speed_summary",
                "settings_gesture_accent_max_speed_title", "settings_gesture_accent_suppression_summary",
                "settings_gesture_accent_suppression_title", "settings_gesture_cursor_max_speed_summary",
                "settings_gesture_cursor_max_speed_title", "settings_gesture_cursor_vel_max_speed_summary",
                "settings_gesture_cursor_vel_max_speed_title", "settings_gesture_doubletap_suppression_summary",
                "settings_gesture_doubletap_suppression_title", "settings_gesture_fast_horiz_min_x_summary",
                "settings_gesture_fast_horiz_min_x_title", "settings_gesture_fast_horiz_vel_summary",
                "settings_gesture_fast_horiz_vel_title", "settings_gesture_fast_vert_min_y_summary",
                "settings_gesture_fast_vert_min_y_title", "settings_gesture_fast_vert_vel_summary",
                "settings_gesture_fast_vert_vel_title", "settings_gesture_horiz_tap_height_summary",
                "settings_gesture_horiz_tap_height_title", "settings_gesture_horiz_tap_width_summary",
                "settings_gesture_horiz_tap_width_title", "settings_gesture_horiz_theta_summary",
                "settings_gesture_horiz_theta_title", "settings_gesture_scroll_horiz_accent_summary",
                "settings_gesture_scroll_horiz_accent_title", "settings_gesture_scroll_horiz_cursor_summary",
                "settings_gesture_scroll_horiz_cursor_title", "settings_gesture_scroll_single_line_summary",
                "settings_gesture_scroll_single_line_title", "settings_gesture_scroll_vert_cursor_summary",
                "settings_gesture_scroll_vert_cursor_title", "settings_gesture_slow_horiz_min_x_summary",
                "settings_gesture_slow_horiz_min_x_title", "settings_gesture_slow_horiz_vel_summary",
                "settings_gesture_slow_horiz_vel_title", "settings_gesture_slow_vert_min_y_summary",
                "settings_gesture_slow_vert_min_y_title", "settings_gesture_slow_vert_vel_summary",
                "settings_gesture_slow_vert_vel_title", "settings_gesture_swipe_suppression_end_summary",
                "settings_gesture_swipe_suppression_end_title", "settings_gesture_swipe_timeout_summary",
                "settings_gesture_swipe_timeout_title", "settings_gesture_swipe_word_dist_summary",
                "settings_gesture_swipe_word_dist_title", "settings_gesture_vert_tap_height_summary",
                "settings_gesture_vert_tap_height_title", "settings_gesture_vert_tap_width_summary",
                "settings_gesture_vert_tap_width_title", "settings_gesture_vert_theta_summary",
                "settings_gesture_vert_theta_title", "settings_gesture_vkb_suppression_summary",
                "settings_gesture_vkb_suppression_title"
            ),
            literals = setOf(
                "GESTURE TIMING",
                "SWIPE ANGLES",
                "SWIPE DISTANCE THRESHOLDS",
                "SWIPE VELOCITY THRESHOLDS",
                "CURSOR TAP REGIONS",
                "SCROLL DISTANCES",
                "SPEED MULTIPLIERS",
                "WORD DETECTION",
            ),
        ),
        Case(
            name = "AdvancedSettingsScreen",
            route = SettingsRoute.Advanced.route,
            title = "Advanced",
            resources = setOf(
                "settings_about_summary", "settings_about_title", "settings_advanced_title",
                "settings_clear_settings_summary", "settings_clear_settings_title", "settings_debug_summary",
                "settings_debug_title", "settings_device_compatibility_summary",
                "settings_device_compatibility_title"
            ),
            literals = emptySet(),
        ),
        Case(
            name = "AnimationParametersScreen",
            route = SettingsRoute.AnimationParameters.route,
            title = "Animation parameters",
            resources = setOf(
                "settings_anim_custom_preview_summary", "settings_anim_custom_preview_title",
                "settings_anim_dismiss_duration_summary", "settings_anim_dismiss_duration_title",
                "settings_anim_dismiss_end_x_summary", "settings_anim_dismiss_end_x_title",
                "settings_anim_dismiss_end_y_summary", "settings_anim_dismiss_end_y_title",
                "settings_anim_show_duration_summary", "settings_anim_show_duration_title",
                "settings_anim_show_start_x_summary", "settings_anim_show_start_x_title",
                "settings_anim_show_start_y_summary", "settings_anim_show_start_y_title",
                "settings_animation_params_title"
            ),
            literals = setOf(
                "SCALE PARAMETERS",
                "TIMING PARAMETERS",
            ),
        ),
        Case(
            name = "AppearanceLayoutScreen",
            route = SettingsRoute.AppearanceLayout.route,
            title = "Personalization",
            resources = setOf(
                "settings_appearance_auto", "settings_appearance_layout_title", "settings_appearance_modern",
                "settings_color_scheme_title", "settings_keyboard_height_regular",
                "settings_keyboard_height_title", "settings_keyboard_theme_title",
                "settings_slideboard_hub_summary", "settings_slideboard_settings_title",
                "settings_symbol_customization_summary", "settings_symbol_customization_title",
                "settings_use_system_colors_summary", "settings_use_system_colors_title"
            ),
            literals = emptySet(),
        ),
        Case(
            name = "AutoCorrectionScreen",
            route = SettingsRoute.Correction.route,
            title = "Correction",
            resources = setOf(
                "settings_auto_correction_title", "settings_autocorrect_auto_cap_summary",
                "settings_autocorrect_auto_cap_title", "settings_autocorrect_double_space_summary",
                "settings_autocorrect_double_space_title", "settings_autocorrect_mode_aggressive",
                "settings_autocorrect_vkb_title"
            ),
            literals = emptySet(),
            // The physical-keyboard auto-correction row sits inside `if (hasPhysicalKeyboard)`
            // (AutoCorrectionScreen.kt:102). On a device with no physical keyboard - which is
            // what Robolectric reports - searching for it lands on this screen and highlights
            // nothing. Recorded, not fixed.
        ),
        Case(
            name = "CkbGesturesScreen",
            route = SettingsRoute.CkbGestures.route,
            title = "CKB Gestures",
            resources = setOf(
                // The DEVELOPER category is NOT here — neither its subhead
                // (`ckb_gestures_developer_category`, already uppercase, so it matches its own
                // resource) nor the CKB geometry export's two rows
                // (`ckb_gestures_export_title` / `_summary`). They compose only when the debug
                // flag `pref_show_ckb_developer_settings` is on, and this case renders a fresh
                // install. The slots and the gesture-timing slider are unaffected.
                "ckb_action_cursor_mode", "ckb_action_cycle_symbols", "ckb_action_delete_word", "ckb_action_none",
                "ckb_gesture_slot_double_tap", "ckb_gesture_slot_flick_left", "ckb_gesture_slot_flick_right",
                "ckb_gesture_slot_hold", "ckb_gesture_slot_swipe_down", "ckb_gesture_slot_swipe_left",
                "ckb_gesture_slot_swipe_right", "ckb_gestures_reserved_note", "ckb_gestures_screen_title",
                "prefs_ckb_gesture_activation_delay_summary", "prefs_ckb_gesture_activation_delay_title"
            ),
            literals = setOf(
                // Same gate: the sensor visualizer's switch and the sensor-map export — four
                // hardcoded strings — went with the rest of the DEVELOPER category. "GESTURE
                // TIMING" is the timing subhead, which is not gated (PreferenceCategory
                // uppercases, so it never matches its own resource).
                "GESTURE TIMING",
            ),
        ),
        Case(
            name = "CorrectionLearningScreen",
            route = SettingsRoute.SuggestionCorrection.route,
            title = "Assistance",
            resources = setOf(
                "settings_auto_correction_title", "settings_correction_summary",
                "settings_dictionaries_learning_title", "settings_dictionary_learning_summary",
                "settings_spell_checker_summary", "settings_spell_checker_title",
                "settings_suggestion_correction_title", "settings_suggestion_summary", "settings_suggestion_title"
            ),
            literals = emptySet(),
        ),
        Case(
            name = "CreditsScreen",
            route = SettingsRoute.About.route,
            title = "About",
            resources = setOf(
                "english_ime_name", "settings_about_title",
                // The Updates row: About is where a user looks for "what version am I on".
                "settings_updates_summary", "settings_updates_title"
            ),
            literals = setOf(
                "CREDITS",
                "Emojibase",
                "Miles Johnson • MIT License",
                "Kotlin Coroutines",
                "JetBrains • Apache License 2.0",
                "Timber",
                "Jake Wharton • Apache License 2.0",
                "Apache Commons IO",
                "Apache Software Foundation • Apache License 2.0",
                "Jetpack Compose",
                "Google • Apache License 2.0",
                "Material Components",
                "AndroidX",
                "Google / AOSP • Apache License 2.0",
                "Gson",
                "jsoup",
                "Jonathan Hedley • MIT License",
                "AndroidX Emoji2",
            ),
        ),
        Case(
            name = "CustomMacrosScreen",
            route = SettingsRoute.CustomMacros.route,
            title = "Custom macros",
            resources = setOf(
                "settings_custom_macros_title"
            ),
            literals = setOf(
                "Define your own %x placeholders for use in text shortcuts.",
                "Reserved tags: %D %T %d %t %n %w %y %b",
                "No custom macros",
                // The extended FAB names the action, so the hint can name the button rather than
                // a "+" that is no longer on screen. "Add macro" is that FAB's label.
                "Add macro",
                "Tap Add macro to create your first one",
            ),
        ),
        Case(
            name = "CustomSymbolPageScreen_PKB",
            route = SettingsRoute.CustomSymbolPagePkb.route,
            title = "Customize layout",
            resources = setOf(
                "customize_symbol_page_title", "symbol_list_category_accents_title",
                "symbol_list_category_alphanumeric_title", "symbol_list_category_arrows_title",
                "symbol_list_category_brackets_title", "symbol_list_category_currency_title",
                "symbol_list_category_custom_title", "symbol_list_category_emoji_title",
                "symbol_list_category_math_title", "symbol_list_category_punctuation_title"
            ),
            literals = setOf(
                // An empty slot in the symbol grid renders an empty Text node
                // (CustomSymbolPageScreen.kt: `if (text == "\u0000" || text == "null") ""`).
                "",
                "#",
                "1",
                "2",
                "3",
                "(",
                ")",
                "_",
                "-",
                "+",
                "@",
                "*",
                "4",
                "5",
                "6",
                "/",
                ":",
                ";",
                "'",
                "\"",
                "alt",
                "7",
                "8",
                "9",
                "?",
                "!",
                ",",
                ".",
                "\$",
            ),
        ),
        Case(
            name = "CustomSymbolPageScreen_VKB",
            route = SettingsRoute.CustomSymbolPageVkb.route,
            title = "Customize layout",
            resources = setOf(
                "customize_symbol_page_title", "symbol_list_category_accents_title",
                "symbol_list_category_alphanumeric_title", "symbol_list_category_arrows_title",
                "symbol_list_category_brackets_title", "symbol_list_category_currency_title",
                "symbol_list_category_custom_title", "symbol_list_category_emoji_title",
                "symbol_list_category_math_title", "symbol_list_category_punctuation_title"
            ),
            literals = setOf(
                "1",
                "2",
                "3",
                "4",
                "5",
                "6",
                "7",
                "8",
                "9",
                "0",
                "=",
                "-",
                "+",
                "*",
                "/",
                ":",
                ";",
                "'",
                "\"",
                "1/2",
                "#",
                "(",
                ")",
                "?",
                "!",
                "@",
                "\$",
                "?123",
                "...",
            ),
        ),
        Case(
            name = "CustomizationScreen",
            route = SettingsRoute.Preferences.route,
            title = "Input",
            resources = setOf(
                "prefs_category_shake_gestures", "settings_keyboard_summary", "settings_keyboard_title",
                "settings_pkb_multifunction_action_voice", "settings_preferences_title", "settings_shake_summary",
                "settings_voice_input_summary"
            ),
            literals = emptySet(),
        ),
        Case(
            name = "CustomizeMenuScreen",
            route = SettingsRoute.CustomizeMenu.route,
            title = "Customize menu",
            resources = setOf(
                "settings_customize_menu_hidden_header", "settings_customize_menu_instructions",
                "settings_customize_menu_title", "settings_pkb_multifunction_action_clipboard",
                "settings_pkb_multifunction_action_emoji", "settings_pkb_multifunction_action_voice",
                "settings_uim_toggle_fcc", "settings_uim_toggle_numpad"
            ),
            literals = setOf(
                "1",
                "2",
                "3",
                "4",
            ),
        ),
        Case(
            name = "CustomizeSlideBoardScreen",
            route = SettingsRoute.CustomizeSlideBoard.route,
            title = "Customize the number pad",
            resources = setOf(
                "customize_slideboard_enable", "symbol_list_category_accents_title",
                "symbol_list_category_alphanumeric_title", "symbol_list_category_arrows_title",
                "symbol_list_category_brackets_title", "symbol_list_category_currency_title",
                "symbol_list_category_custom_title", "symbol_list_category_emoji_title",
                "symbol_list_category_math_title", "symbol_list_category_punctuation_title"
            ),
            literals = setOf(
                "Loading symbols...",
                "7",
                "8",
                "9",
                "&",
                "*",
                "4",
                "5",
                "6",
                "\$",
                "%",
                "1",
                "2",
                "3",
                "@",
                "#",
                "0",
                ".",
                ",",
                "?",
                "!",
            ),
        ),
        Case(
            name = "DebugSettingsScreen",
            route = SettingsRoute.Debug.route,
            title = "Debug Settings",
            resources = setOf(
                "prefs_acceleration_threshold_summary", "prefs_acceleration_threshold_title",
                "prefs_advanced_gesture_thresholds_summary", "prefs_advanced_gesture_thresholds_title",
                "prefs_advanced_settings_title", "prefs_autofill_debug_logging_summary",
                "prefs_autofill_debug_logging_title", "prefs_autofill_event_toasts_summary",
                "prefs_autofill_event_toasts_title", "prefs_batch_cursor_movements_summary",
                "prefs_batch_cursor_movements_title", "prefs_cross_field_boundaries_summary",
                "prefs_cross_field_boundaries_title", "prefs_disable_auto_scroll_summary",
                "prefs_disable_auto_scroll_title", "prefs_double_consonant_delay_summary",
                "prefs_double_consonant_delay_title", "prefs_double_consonant_timing_summary",
                "prefs_double_consonant_timing_title", "prefs_fallback_trigger_count_summary",
                "prefs_fallback_trigger_count_title", "prefs_flow_mode_min_velocity_summary",
                "prefs_flow_mode_min_velocity_title", "prefs_flow_mode_min_y_summary",
                "prefs_flow_mode_min_y_title", "prefs_force_vkb_mode_summary_off", "prefs_force_vkb_mode_title",
                "prefs_key_longpress_delay_summary", "prefs_key_longpress_delay_title",
                "prefs_key_preview_animations_summary", "prefs_key_preview_animations_title",
                "prefs_mic_key_triggers_voice_assistant_summary", "prefs_mic_key_triggers_voice_assistant_title",
                "prefs_on_axis_trigger_count_summary", "prefs_on_axis_trigger_count_title",
                "prefs_persistent_symbol_page_hint_summary", "prefs_persistent_symbol_page_hint_title",
                "prefs_pkb_debug_logging_summary", "prefs_pkb_debug_logging_title",
                "prefs_pkb_special_key_handling_summary", "prefs_pkb_special_key_handling_title",
                "prefs_reset_time_summary", "prefs_reset_time_title", "prefs_slide_to_type_preview_summary",
                "prefs_slide_to_type_preview_title", "prefs_slideboard_longpress_delay_summary",
                "prefs_slideboard_longpress_delay_title", "prefs_slop_time_summary", "prefs_slop_time_title",
                "prefs_show_ckb_developer_settings_summary", "prefs_show_ckb_developer_settings_title",
                "prefs_show_device_profile_builder_summary", "prefs_show_device_profile_builder_title",
                "prefs_typed_word_accept_button_summary", "prefs_typed_word_accept_button_title",
                "prefs_virtual_keyboard_visibility_title", "prefs_vkb_gesture_activation_delay_summary",
                "prefs_vkb_gesture_activation_delay_title", "settings_category_gestures"
            ),
            literals = setOf(
                "TIMING & RESPONSIVENESS",
                "SWIPE & FLOW TYPING",
                "Gesture Lab (prototype)",
                "Visualize swipes and see the new gesture classifier's verdict; tune thresholds live",
                "VISUAL FEEDBACK",
                "PHYSICAL KEYBOARD",
                "When to show VKB on physical keyboard devices: Always",
                "CURSOR CONTROL",
                "SLIDEBOARD",
                "KOREAN INPUT",
                "SHAKE GESTURES",
                "TESTING & DIAGNOSTICS",
            ),
        ),
        Case(
            name = "DeviceCompatibilityScreen",
            route = SettingsRoute.DeviceCompatibility.route,
            title = "Device compatibility",
            // "key_interceptor_service_label" for the same reason as KeyboardHelperScreen below:
            // three resources now share the value "BBKB Helper" and the first name wins.
            resources = setOf(
                "key_interceptor_service_label", "pref_override_device_meta_state",
                "pref_override_device_meta_state_summary", "settings_device_compatibility_title",
                "settings_device_configuration_summary", "settings_device_configuration_title",
                "settings_pkb_keyboard_helper_summary"
            ),
            literals = emptySet(),
        ),
        Case(
            name = "DeviceConfigurationScreen",
            route = SettingsRoute.DeviceConfiguration.route,
            title = "Device configuration",
            resources = setOf(
                // The profile builder's entry row is NOT here: the builder is unfinished, so the
                // row and its "THIS DEVICE" subhead only compose when the debug flag
                // `pref_show_device_profile_builder` is on, and this case renders a fresh install.
                "settings_device_configuration_title"
            ),
            literals = setOf(
                // The importer moved from a bare + in the app bar to an extended FAB, which
                // renders its label as text rather than only as a content description.
                "Import configuration",
                // PreferenceCategory uppercases, so a subhead never matches its own resource.
                "STANDARD",
                "Default (System Auto-detection)",
                "System auto-detection",
                "PRELOADED",
                "BlackBerry Key2 (Athena)",
                "BlackBerry PKB Devices",
                "Minimal Phone (MP01)",
                "W2 Emulator Replay (virtual KEY2)",
                "Zinwa Q25",
            ),
        ),
        Case(
            name = "DictionariesLearningScreen",
            route = SettingsRoute.Learning.route,
            title = "Learning",
            resources = setOf(
                "settings_dict_dynamic_learning_summary", "settings_dict_dynamic_learning_title",
                "settings_dict_learned_words_summary", "settings_dict_learned_words_title",
                "settings_dict_text_shortcuts_summary", "settings_dict_text_shortcuts_title",
                "settings_dict_user_dictionary_summary", "settings_dict_user_dictionary_title",
                "settings_dictionaries_learning_title"
            ),
            literals = emptySet(),
        ),
        Case(
            name = "GestureLabScreen",
            route = SettingsRoute.GestureLab.route,
            title = "Gesture Lab",
            resources = emptySet(),
            literals = setOf(
                "Test",
                "Tuning",
                "Drag on the surface to draw a gesture. Hold still for tap/hold; two quick taps for double-tap. Switch to Tuning to adjust thresholds.",
                "Verdict",
                "—",
                "Draw a gesture above",
                "Trace features",
                "Gesture Lab",
            ),
        ),
        Case(
            name = "KeyPressFeedbackScreen",
            route = SettingsRoute.TouchFeedback.route,
            title = "Key press feedback",
            resources = setOf(
                "key_preview_popup_dismiss_default_delay", "key_preview_popup_dismiss_delay", "popup_on_keypress",
                "popup_on_keypress_summary", "prefs_keypress_sound_volume_settings",
                "prefs_keypress_vibration_duration_settings", "settings_category_sound",
                "settings_category_vibrate", "settings_key_press_feedback_title", "sound_on_keypress_summary",
                "vibrate_on_keypress_summary"
            ),
            literals = setOf(
                "VIBRATE",
                "System default",
                "SOUND",
                "KEY POPUP",
            ),
        ),
        Case(
            name = "KeyboardHelperScreen",
            route = SettingsRoute.KeyboardHelper.route,
            title = "BBKB Helper",
            // "key_interceptor_service_label", not "keyboard_helper_title": both resources now
            // read "BBKB Helper" (the rename, 2026-09-21) and the recorder keeps the
            // lexicographically first name of a shared value. Same row, same screen.
            resources = setOf(
                "key_interceptor_service_label", "pref_accessibility_service_disabled",
                "pref_key_interceptor_enabled", "pref_key_interceptor_enabled_summary",
                "pref_manage_accessibility_service",
                "pref_preprocess_all_keys", "pref_preprocess_all_keys_summary"
            ),
            literals = setOf(
                "Unified Key Mapping",
                "Route special keys via XML config instead of hardcoded logic (experimental)",
            ),
        ),
        Case(
            name = "LanguagePacksScreen",
            route = SettingsRoute.LanguagePacks.route,
            title = "Language packs",
            resources = setOf(
                "settings_language_packs_title"
            ),
            // The extended FAB renders before the IO load finishes, so its label is an immediate
            // literal. The info banner and the per-row "Version: … • Preinstalled" line are gone
            // (Material 3 redesign, 2026-09-16): rows are titles only.
            //
            // The two section headers are literals because PreferenceCategory upper-cases its
            // title, so the rendered text is not the resource's own value. They render
            // unconditionally - the list is always there, with a spinner in whichever half is
            // still loading - which is what keeps them out of `lateLiterals`.
            literals = setOf("Add dictionary", "INSTALLED", "AVAILABLE TO DOWNLOAD"),
            // Everything else arrives with the IO load: one row per shipped pack, each with its
            // two-letter code badge (too short to resolve to a resource name) and the
            // "Preinstalled" tag. Recorded as late so the race decides nothing.
            lateLiterals = setOf(
                "Chinese", "English", "French", "German", "Italian", "Russian", "Spanish", "Ukrainian",
                "ZH", "EN", "FR", "DE", "IT", "RU", "ES", "UK",
                "Preinstalled",
            ),
            // Whatever the catalogue fetch came back with. Under Robolectric there is no network
            // and no cached manifest, so it is always one of the failure messages plus its Try
            // again button; the rest are listed because they are the other answers the same row
            // can give, and none of them is worth pinning a race on.
            lateResources = setOf(
                "language_packs_catalog_offline", "language_packs_download",
                "language_packs_error_app_too_old", "language_packs_error_network",
                "language_packs_error_offline", "language_packs_error_verification",
                "language_packs_retry", "language_packs_variant_summary",
            ),
        ),
        Case(
            name = "LanguageSwitchingScreen",
            route = SettingsRoute.LanguageSwitching.route,
            title = "Language switching",
            resources = setOf(
                "settings_lang_include_other_summary", "settings_lang_include_other_title",
                "settings_lang_quick_switch_summary", "settings_lang_quick_switch_title",
                "settings_lang_spacebar_switch_summary", "settings_lang_spacebar_switch_title",
                "settings_lang_switch_key_summary", "settings_lang_switch_key_title",
                "settings_language_switching_title"
            ),
            literals = emptySet(),
        ),
        Case(
            name = "LanguagesInputScreen",
            route = SettingsRoute.Languages.route,
            title = "Language",
            resources = setOf(
                "multi_language_input_settings_screen_title",
                "multi_language_input_support_language_spinner_dialog_title", "settings_language_packs_summary",
                "settings_language_packs_title", "settings_language_switching_summary",
                "settings_language_switching_title", "settings_multi_language_keyboards_summary"
            ),
            literals = emptySet(),
        ),
        Case(
            name = "MainSettingsScreen",
            route = SettingsRoute.Main.route,
            // Stale golden: 43f690e7 titled the screen "BBKB Settings".
            title = "BBKB Settings",
            resources = setOf(
                "english_ime_settings", "multi_language_input_support_language_spinner_dialog_title",
                "settings_advanced_summary", "settings_advanced_title", "settings_appearance_layout_summary",
                "settings_appearance_layout_title", "settings_languages_summary", "settings_preferences_summary",
                "settings_preferences_title", "settings_search_hint", "settings_suggestion_correction_summary",
                "settings_suggestion_correction_title"
            ),
            literals = emptySet(),
        ),
        Case(
            name = "MultiLanguageKeyboardsScreen",
            route = SettingsRoute.MultiLanguageKeyboards.route,
            title = "Multi-language keyboards",
            resources = setOf(
                // multi_language_input_add_keyboard is new: the + in the app bar became an
                // extended FAB, which renders its label.
                "multi_language_input_add_keyboard",
                "multi_language_input_empty_screen", "multi_language_input_settings_screen_title"
            ),
            literals = emptySet(),
        ),
        Case(
            name = "MultiLanguageWizardScreen_ADD",
            route = SettingsRoute.MultiLanguageWizardAdd.route,
            title = "Add multi-language keyboard",
            resources = setOf(
                "multi_language_input_invalid_selection_toast", "multi_language_input_primary_language_title",
                "multi_language_input_setup_explanation",
                "multi_language_input_setup_primary_language_explanation",
                "multi_language_input_setup_supportive_language_explanation",
                "multi_language_input_subtype_select_language", "multi_language_input_subtype_wizard_title"
            ),
            literals = setOf(
                "PRIMARY LANGUAGE",
                "SUPPORTING LANGUAGES",
            ),
        ),
        Case(
            name = "PhysicalKeyboardScreen",
            route = SettingsRoute.PhysicalKeyboard.route,
            title = "Physical keyboard",
            resources = setOf(
                "control_key_setting_right_shift", "pref_alt_double_tap_lock", "pref_alt_double_tap_lock_summary",
                "pref_shift_double_tap_lock", "pref_shift_double_tap_lock_summary",
                "pref_show_pkb_modifier_status_icon", "pref_show_pkb_modifier_status_icon_summary",
                "settings_category_behavior", "settings_category_modifiers", "settings_physical_keyboard_title",
                "settings_pkb_alt_sym_shortcut_disabled_summary", "settings_pkb_alt_sym_shortcut_title",
                "settings_pkb_ctrl_key_behavior_title", "settings_pkb_dictation_key_summary_on",
                "settings_pkb_dictation_key_title", "settings_pkb_hold_action_off_summary",
                "settings_pkb_hold_action_title", "settings_pkb_symbol_auto_close_summary_off",
                "settings_pkb_symbol_auto_close_title"
            ),
            literals = emptySet(),
            // The multifunction-key row sits inside `if (multifunctionKeyMapping != null)`
            // (PhysicalKeyboardScreen.kt:163), which is null when no physical keyboard is
            // present: the same one-way trip from search as above.
        ),
        Case(
            name = "PredictionsSuggestionsScreen",
            route = SettingsRoute.Suggestion.route,
            title = "Suggestions",
            resources = setOf(
                // "Block offensive words" is no longer here: dictation masking is its only
                // effect, so the row moved to VoiceInputSettingsScreen (same preference key).
                "pref_emoji_dynamic_search_summary_off", "pref_emoji_dynamic_search_title",
                "settings_pred_contacts_permission_summary", "settings_pred_contacts_permission_title",
                "settings_pred_contacts_summary_disabled", "settings_pred_contacts_title",
                "settings_pred_emoji_summary", "settings_pred_emoji_title",
                "settings_pred_flick_animation_summary", "settings_pred_flick_animation_title",
                "settings_pred_force_suggestions_summary", "settings_pred_force_suggestions_title",
                "settings_pred_grant_permission", "settings_pred_inline_autofill_summary",
                "settings_pred_inline_autofill_title", "settings_pred_next_word_summary",
                "settings_pred_next_word_title", "settings_pred_on_key_summary", "settings_pred_on_key_title",
                "settings_pred_personalized_summary", "settings_pred_personalized_title",
                "settings_pred_show_predictions_summary", "settings_pred_show_predictions_title",
                "settings_suggestion_title"
            ),
            literals = emptySet(),
        ),
        Case(
            name = "QuickPhrasesScreen",
            route = SettingsRoute.QuickPhrases.route,
            title = "Quick phrases",
            // The unset phrases are now the keyboard's own fallback resources; they used to be four
            // hardcoded literals plus one ("On my way") that happened to equal a resource.
            resources = setOf(
                "pref_quick_phrase_1_default", "pref_quick_phrase_2_default", "pref_quick_phrase_3_default",
                "pref_quick_phrase_4_default", "pref_quick_phrase_5_default",
                "settings_quick_phrase_1", "settings_quick_phrase_2",
                "settings_quick_phrase_3", "settings_quick_phrase_4", "settings_quick_phrase_5",
                "settings_quick_phrases_screen_title"
            ),
            literals = emptySet(),
        ),
        Case(
            name = "ShakeGesturesScreen",
            route = SettingsRoute.Shake.route,
            title = "Shake Gestures",
            resources = setOf(
                "prefs_category_shake_gestures", "settings_shake_fallback_title", "settings_shake_info_summary",
                "settings_shake_x_axis_title", "settings_shake_y_axis_title", "settings_shake_z_axis_title"
            ),
            literals = setOf(
                // Defect 10 fixed: the screen intro used to be a `PreferenceItem(title = "")`,
                // which laid out a blank title line above the summary and showed up here as the
                // "" literal. It is now a plain Text, so the only literal left is the shake
                // action label.
                "No action",
            ),
        ),
        Case(
            name = "SlideboardLayoutScreen",
            route = SettingsRoute.SlideboardSettings.route,
            title = "Slideboard settings",
            resources = setOf(
                "settings_quick_phrases_screen_title", "settings_slideboard_settings_title",
                "settings_vkb_customize_numberpad_summary", "settings_vkb_customize_numberpad_title",
                "settings_vkb_slideboard_enable_summary", "settings_vkb_slideboard_enable_title",
                "settings_vkb_slideboard_swap_summary_default", "settings_vkb_slideboard_swap_title"
            ),
            literals = setOf(
                // Was "Thanks, See you soon, On my way, ...": now the phrases the keyboard inserts.
                "How are you?, On my way, That's great!, ...",
            ),
        ),
        Case(
            name = "SpellCheckerSettingsScreen",
            // Not in the settings NavHost at all: it is hosted by SpellCheckerComposeActivity,
            // which is why SettingsSearchIndex routes its two entries at the hub row instead.
            route = "(SpellCheckerComposeActivity)",
            title = "Spell checker settings",
            resources = setOf(
                "settings_pred_contacts_permission_title", "settings_pred_grant_permission",
                "settings_spell_checker_screen_title", "settings_spellcheck_sensitivity_balanced",
                "settings_spellcheck_sensitivity_title", "settings_spellcheck_use_contacts_summary_disabled",
                "settings_spellcheck_use_contacts_title"
            ),
            literals = setOf(
                // "About Spell Checker" is gone: it was the heading of an info card, one line
                // under an app bar already reading "Spell checker settings". The sentence it
                // introduced is still here, now as a plain PreferenceInfo paragraph.
                "The BBKB spell checker provides intelligent spell checking across all apps that support spell checking. It uses advanced language models to detect typos and suggest corrections.",
                "Grant contacts permission to recognize contact names as valid words during spell checking.",
            ),
        ),
        Case(
            name = "SymbolCustomizationScreen",
            route = SettingsRoute.SymbolCustomization.route,
            title = "Symbol customization",
            resources = setOf(
                "settings_default_currency_title", "settings_symbol_custom_page_first_summary",
                "settings_symbol_custom_page_first_title", "settings_symbol_customization_title",
                "settings_symbol_customize_page_summary", "settings_symbol_customize_page_title",
                "settings_symbol_enable_customization_summary", "settings_symbol_enable_customization_title"
            ),
            literals = setOf(
                // Was "Selected: $": the screen claimed a dollar default the runtime never applied.
                "Selected: Keyboard default",
            ),
        ),
        Case(
            name = "TextShortcutsScreen",
            route = SettingsRoute.TextShortcuts.route,
            title = "Text Shortcuts",
            resources = emptySet(),
            literals = setOf(
                "Text Shortcuts",
                "0 shortcuts",
                // The round FAB became an extended one, so its label is rendered text rather
                // than only a content description.
                "Add shortcut",
            ),
            // "Initialization timeout" is the store failing to come up under Robolectric -- an
            // environment artefact, pinned only so a new late string is still caught.
            lateLiterals = setOf(
                // The hint names that button now that the button says what it does.
                "No text shortcuts", "Tap Add shortcut to create shortcuts that expand to phrases", "Initialization timeout",
            ),
        ),
        Case(
            name = "TouchScreenKeyboardScreen",
            route = SettingsRoute.OnScreenKeyboard.route,
            title = "On-screen keyboard",
            resources = setOf(
                "settings_category_behavior", "settings_category_gestures", "settings_customize_menu_summary",
                "settings_customize_menu_title", "settings_keyboard_title", "settings_screen_type_by_swiping",
                "settings_touch_feedback_summary", "settings_touch_feedback_title",
                "settings_uim_enable_summary_on", "settings_uim_enable_title",
                "settings_vkb_swipe_down_dismiss_summary", "settings_vkb_swipe_down_dismiss_title",
                "settings_vkb_swipe_gestures_summary", "settings_vkb_swipe_gestures_title",
                "settings_vkb_type_by_swiping_summary", "vkb_control_key_summary", "vkb_control_key_title"
            ),
            literals = emptySet(),
        ),
        Case(
            name = "UpdatesScreen",
            route = SettingsRoute.Updates.route,
            title = "Updates",
            resources = setOf(
                // "Never" - the last-checked row on a fresh install. Several resources share that
                // value and the index records the lexicographically first, which is this one.
                "prefs_vkb_visibility_status_never",
                "settings_update_background_check_summary", "settings_update_background_check_title",
                // The tests run against the debug BuildConfig, so the channel row honestly reads
                // "no update channel for debug builds" and there is no card to draw.
                "settings_update_channel_none", "settings_update_channel_title",
                "settings_update_check_now", "settings_update_installed_version_title",
                "settings_update_last_checked_title", "settings_updates_title"
            ),
            literals = setOf(
                "UPDATES"
            ),
        ),
        Case(
            name = "UserDictionaryScreen",
            route = SettingsRoute.UserDictionary.route,
            title = "User dictionary",
            resources = setOf(
                "settings_dict_user_dictionary_title"
            ),
            literals = setOf(
                "0 words",
                // As above: the extended FAB renders its label.
                "Add word",
            ),
            lateLiterals = setOf(
                "No personal words", "Tap Add word to add custom words to your dictionary", "Initialization timeout",
            ),
        ),
        Case(
            name = "VoiceInputSettingsScreen",
            route = SettingsRoute.VoiceInput.route,
            title = "Voice input",
            resources = setOf(
                // The two `settings_pred_block_offensive_*` names are the "Block offensive words"
                // row, moved here from Suggestions: it only ever masked dictation results.
                "settings_pkb_multifunction_action_voice", "settings_pred_block_offensive_summary",
                "settings_pred_block_offensive_title", "settings_voice_auto_start_summary_on",
                "settings_voice_auto_start_title", "settings_voice_builtin_summary",
                "settings_voice_builtin_title", "settings_voice_prefer_offline_summary",
                "settings_voice_prefer_offline_title", "settings_voice_use_keyboard_lang_summary",
                "settings_voice_use_keyboard_lang_title"
            ),
            literals = emptySet(),
        ),
        Case(
            name = "VoiceLanguageSelectionScreen",
            route = SettingsRoute.VoiceLanguageSelection.route,
            title = "Voice input language",
            resources = setOf(
                "settings_voice_input_lang_title"
            ),
            literals = setOf(
                "Loading available languages...",
            ),
        ),
        Case(
            name = "WordListEditorScreen",
            route = SettingsRoute.PersonalLearnedWords.route,
            title = "Personal Learned Words",
            resources = emptySet(),
            literals = setOf(
                "Personal Learned Words",
            ),
            lateLiterals = setOf(
                "No learned words",
                "Words you type will be learned and appear here",
            ),
        ),
    )
}

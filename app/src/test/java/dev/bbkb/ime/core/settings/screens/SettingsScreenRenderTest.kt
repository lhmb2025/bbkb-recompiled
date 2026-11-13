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
import dev.bbkb.ime.core.layout.LetterLayoutRegistry
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
import java.io.InputStream

/**
 * Renders every Compose settings screen and pins what it shows: it composes, its app-bar title is
 * the topmost laid-out text, and its exact text as `resources` (the lexicographically first
 * `R.string` name per value, so wording changes do not fail) and `literals` (hardcoded text).
 * Values under four characters are never resolved to names, so symbols land in `literals`.
 * Screens render in fresh-install state; `CustomSymbolPageScreen` appears twice (PKB and VKB).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class SettingsScreenRenderTest(private val screenName: String) {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * @param name key into [contentFor]
     * @param route the [SettingsRoute] the NavHost reaches this screen by; documentation, not asserted
     */
    private data class Case(
        val name: String,
        val route: String,
        val title: String,
        val resources: Set<String>,
        val literals: Set<String>,
        /**
         * Hardcoded text that appears only after a `Dispatchers.IO` load, which `waitForIdle()`
         * cannot see, so it may or may not be on screen at snapshot time.
         */
        val lateLiterals: Set<String> = emptySet(),
        /** As [lateLiterals], for resource-backed text (e.g. the language pack catalogue's fetch result). */
        val lateResources: Set<String> = emptySet(),
    )

    /** Version text changes every release, so it is the one thing not pinned. */
    private val volatileText = Regex("""^Version \d\S*( \(Build \d+\))?$""")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ResourceLocaleUtils.reinit(context)
        DeviceProfile.initialize(context)
        registerThisImeWithTheFramework(context)
        keepTheCatalogueFetchOffTheRealNetwork(context)
    }

    /**
     * Points the debug manifest-URL override at a port with nothing listening, so the language pack
     * catalogue fetch fails locally even if Robolectric's shadow ever reports a network.
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
     * Registers this IME with its real manifest subtypes, as on a device where the keyboard is
     * enabled; `LanguagesHubScreen` reads that list. The singleton resets stop one parameterised
     * run leaking into the next.
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

    /** No `InputMethodInfo` for us at all, as between installing the keyboard and enabling it. */
    private fun deregisterThisImeFromTheFramework(context: Context) {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        Shadows.shadowOf(imm).setInputMethodInfoList(emptyList())
        Shadows.shadowOf(imm).setEnabledInputMethodInfoList(emptyList())
        // Both singletons cached the registered state in setUp; drop it.
        RichInputMethodManager.getInstance().clearSubtypeCaches()
        MultiLanguageRepository.clearInstance()
    }

    /** Maps a case name to its screen composable. */
    private fun contentFor(name: String): @Composable () -> Unit = when (name) {
        "AdvancedGestureParametersScreen" -> ({ AdvancedGestureParametersScreen({}) })
        "AdvancedSettingsScreen" -> ({ AdvancedSettingsScreen({}, {}, {}) })
        "AnimationParametersScreen" -> ({ AnimationParametersScreen({}) })
        "AppearanceLayoutScreen" -> ({ AppearanceLayoutScreen({}, {}) })
        "AutoCorrectionScreen" -> ({ AutoCorrectionScreen({}) })
        "BackupRestoreScreen" -> ({ BackupRestoreScreen({}) })
        "CkbGesturesScreen" -> ({ CkbGesturesScreen({}) })
        "CorrectionLearningScreen" -> ({ CorrectionLearningScreen({}, {}, {}, {}) })
        "CreditsScreen" -> ({ CreditsScreen({}, {}, {}) })
        "OpenSourceLicensesScreen" -> ({ OpenSourceLicensesScreen({}) })
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
        "MainSettingsScreen" -> ({ MainSettingsScreen({}, {}) })
        "TouchScreenKeyboardScreen" -> ({ TouchScreenKeyboardScreen({}, {}, {}) })
        "AuxiliaryInputScreen" -> ({ AuxiliaryInputScreen({}, {}) })
        "PhysicalKeyboardScreen" -> ({ PhysicalKeyboardScreen({}, {}) })
        "PhysicalLayoutsScreen" -> ({ PhysicalLayoutsScreen({}, builtInDocuments = OneBuiltInLayout) })
        "PredictionsSuggestionsScreen" -> ({ PredictionsSuggestionsScreen({}) })
        "QuickPhrasesScreen" -> ({ QuickPhrasesScreen({}) })
        "ShakeTuningScreen" -> ({ ShakeTuningScreen({}) })
        "SlideboardLayoutScreen" -> ({ SlideboardLayoutScreen({}, {}, {}) })
        "SpellCheckerSettingsScreen" -> ({ SpellCheckerSettingsScreen({}) })
        "SymbolCustomizationScreen" -> ({ SymbolCustomizationScreen({}, {}, onNavigateBack = {}) })
        "TextShortcutsScreen" -> ({ TextShortcutsScreen({}, {}) })
        "TouchSurfaceHelperScreen" -> ({ TouchSurfaceHelperScreen({}) })
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

        // The app-bar title is the topmost text that actually got laid out.
        val topmost = nodes.filter { it.boundsInRoot.height > 0f }.minByOrNull { it.boundsInRoot.top }
        assertTrue("${case.name} laid out no text", topmost != null)
        assertEquals(
            "${case.name} top-app-bar title",
            case.title,
            topmost!!.config[SemanticsProperties.Text].joinToString("") { it.text }
        )

        // Exactly the recorded rows, by resource name and by hardcoded literal.
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
     * Every screen must render when the framework has no `InputMethodInfo` for this IME (settings
     * opened between install and enable): an unknown IME has no subtypes, so language lists are empty.
     */
    @Test
    fun rendersWithoutCrashingWhenThisImeIsNotRegisteredWithTheFramework() {
        val case = CASES.single { it.name == screenName }
        val context = ApplicationProvider.getApplicationContext<Context>()
        deregisterThisImeFromTheFramework(context)

        // The production null. Its contract is unchanged (two callers branch on it); these assert
        // each caller degrades instead of dereferencing.
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
     * The anchors a screen claims must equal the [SettingsSearchIndex] anchors routed here and
     * visible on this device: a missing one highlights nothing, an extra one has fallen out of
     * search. Probed via `SettingsHighlightController.consume()`, which `Modifier.settingsSearchAnchor`
     * calls once its row is in view. Touch-only device shape only (`DeviceProfile` is a singleton);
     * `SettingsSearchIndexTest.deviceGatesFollowTheDeviceShape` covers the keyboard half.
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
     * Rendered text to canonical `R.string` name, by reflection. Values under four characters are
     * skipped: they collide with unrelated `config_*` strings.
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

    /** One built-in layout, so the Built-in section has a row and the case ignores generated assets. */
    private object OneBuiltInLayout : LetterLayoutRegistry.BuiltInDocuments {
        private const val ID = "sample"
        private const val DOCUMENT = """<letter-layout version="1" id="sample" name="Sample layout">
            <bind/>
            <key code="KEYCODE_Q" keySpec="a"/>
            <key code="KEYCODE_A" keySpec="q"/>
        </letter-layout>"""

        override fun ids(): List<String> = listOf(ID)

        override fun open(id: String): InputStream? = if (id == ID) DOCUMENT.byteInputStream() else null
    }

    companion object {

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun screens(): List<Array<Any>> = SCREEN_NAMES.map { arrayOf<Any>(it) }

        /**
         * Plain strings, not [Case]s: `@Parameters` runs outside Robolectric's sandbox classloader,
         * so richer objects would be built against the wrong copy of the app's classes.
         */
        private val SCREEN_NAMES = listOf(
            "AdvancedGestureParametersScreen", "AdvancedSettingsScreen", "AnimationParametersScreen",
            "AppearanceLayoutScreen", "AutoCorrectionScreen", "AuxiliaryInputScreen", "BackupRestoreScreen", "CkbGesturesScreen",
            "CorrectionLearningScreen", "CreditsScreen", "CustomMacrosScreen",
            "CustomSymbolPageScreen_PKB", "CustomSymbolPageScreen_VKB", "CustomizationScreen",
            "CustomizeMenuScreen", "CustomizeSlideBoardScreen", "DebugSettingsScreen",
            "DeviceCompatibilityScreen", "DeviceConfigurationScreen", "DictionariesLearningScreen",
            "GestureLabScreen", "KeyPressFeedbackScreen", "KeyboardHelperScreen",
            "LanguagePacksScreen", "LanguageSwitchingScreen",
            "MainSettingsScreen", "OpenSourceLicensesScreen",
            "PhysicalKeyboardScreen", "PhysicalLayoutsScreen", "PredictionsSuggestionsScreen", "QuickPhrasesScreen",
            "ShakeTuningScreen", "SlideboardLayoutScreen", "SpellCheckerSettingsScreen",
            "SymbolCustomizationScreen", "TextShortcutsScreen", "TouchScreenKeyboardScreen",
            "TouchSurfaceHelperScreen",
            "UpdatesScreen", "UserDictionaryScreen", "VoiceInputSettingsScreen",
            "VoiceLanguageSelectionScreen",
            "WordListEditorScreen",
        )
    }

    // The recorded output of every screen, generated by rendering them.

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
                "settings_gesture_vkb_suppression_title", "settings_gesture_category_angles", "settings_gesture_category_distance", "settings_gesture_category_scroll", "settings_gesture_category_speed", "settings_gesture_category_tap_regions", "settings_gesture_category_timing", "settings_gesture_category_velocity", "settings_gesture_category_word_detection"
            ),
            literals = emptySet(),
        ),
        Case(
            name = "AdvancedSettingsScreen",
            route = SettingsRoute.Advanced.route,
            title = "Advanced",
            resources = setOf(
                "settings_category_device",
                "settings_advanced_title",
                "settings_backup_restore_summary", "settings_backup_restore_title",
                "settings_debug_summary", "prefs_advanced_settings_title", "settings_device_compatibility_summary",
                "settings_device_compatibility_title",
                // "Reset" resolves to the first resource with that value; the row's own is
                // settings_reset_title.
                "reset_dialog_confirm", "settings_reset_summary", "settings_category_manage_data",
            ),
            literals = emptySet(),
        ),
        Case(
            name = "BackupRestoreScreen",
            route = SettingsRoute.BackupRestore.route,
            title = "Backup and restore",
            resources = setOf(
                "settings_backup_restore_title",
                "backup_part_settings", "backup_part_settings_summary",
                "backup_part_layouts", "backup_part_layouts_summary",
                "backup_part_words", "backup_part_words_summary",
                "backup_save_title", "backup_save_summary",
                "backup_restore_file_title", "backup_restore_file_summary",
                "backup_phone_include_title", "backup_phone_include_summary",
                "backup_phone_last_title", "backup_phone_last_never",
                "backup_phone_settings_title", "backup_phone_settings_summary", "backup_category_back_up", "backup_category_phone", "backup_category_restore",
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
                "settings_animation_params_title", "settings_anim_category_scale", "settings_anim_category_timing"
            ),
            literals = emptySet(),
        ),
        Case(
            name = "AppearanceLayoutScreen",
            route = SettingsRoute.AppearanceLayout.route,
            title = "Personalization",
            resources = setOf(
                "settings_category_appearance", "settings_category_symbols",
                "settings_appearance_auto", "settings_appearance_layout_title", "settings_appearance_modern",
                "settings_color_scheme_title", "settings_keyboard_height_regular",
                "settings_keyboard_height_title", "settings_keyboard_theme_title",
                "settings_default_currency_summary", "settings_default_currency_title",
                "settings_symbol_customization_summary", "settings_symbol_customization_title",
                "settings_use_system_colors_summary", "settings_use_system_colors_title"
            ),
            literals = setOf(
                // The default currency row's icon: the generic currency sign while unset.
                "¤",
            ),
        ),
        Case(
            name = "AuxiliaryInputScreen",
            route = SettingsRoute.AuxiliaryInput.route,
            title = "Auxiliary input",
            resources = setOf(
                "settings_auxiliary_input_title", "settings_category_input_menu",
                "settings_uim_enable_summary_on",
                "settings_customize_menu_summary", "settings_customize_menu_title",
                "settings_shake_title", "settings_shake_fallback_title",
                "settings_shake_x_axis_title", "settings_shake_y_axis_title", "settings_shake_z_axis_title",
                // The clipboard group's "Clipboard" header is a category, which the recorder
                // does not count.
                "clipboard_history_title", "clipboard_history_summary",
                "clipboard_retention_title", "clipboard_retention_1h",
                "clipboard_link_previews_title", "clipboard_link_previews_summary", "settings_pkb_multifunction_action_clipboard"
            ),
            // The four shake rows' summary: the chosen action's label, from a string array.
            literals = setOf("No action"),
            // The clipboard group's header reuses the "Clipboard" string upper-cased, which the
            // recorder sees as a literal.
        ),
        Case(
            name = "AutoCorrectionScreen",
            route = SettingsRoute.Correction.route,
            title = "Correction",
            resources = setOf(
                "settings_auto_correction_title", "settings_autocorrect_auto_cap_summary",
                "settings_autocorrect_auto_cap_title", "settings_autocorrect_double_space_summary",
                "settings_autocorrect_double_space_title", "settings_autocorrect_enable_summary",
                "settings_autocorrect_enable_title", "settings_autocorrect_mode_aggressive",
                "settings_autocorrect_vkb_title"
            ),
            literals = emptySet(),
            // The physical-keyboard auto-correction row needs a physical keyboard, which Robolectric
            // lacks, so its search entry lands here and highlights nothing. Recorded, not fixed.
        ),
        Case(
            name = "CkbGesturesScreen",
            route = SettingsRoute.CkbGestures.route,
            title = "Capacitive gestures",
            resources = setOf(
                // The developer category is absent: it composes only with the debug flag
                // `pref_show_ckb_developer_settings`, off on a fresh install.
                "ckb_action_cursor_mode", "ckb_action_cycle_symbols", "ckb_action_delete_word", "ckb_action_none",
                "ckb_gesture_slot_double_tap", "ckb_gesture_slot_flick_left", "ckb_gesture_slot_flick_right",
                "ckb_gesture_slot_hold", "ckb_gesture_slot_swipe_down", "ckb_gesture_slot_swipe_left",
                "ckb_gesture_slot_swipe_right", "ckb_gestures_reserved_note", "ckb_gestures_enable_title",
                "prefs_ckb_gesture_activation_delay_title", "settings_gesture_category_timing"
            ),
            literals = setOf(
                "350 ms",
                // The developer gate also hides the sensor visualizer and sensor-map export. "GESTURE
                // TIMING" is ungated but upper-cased by PreferenceCategory, so it never matches.
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
            // No "About" title over the app's own icon and name: the topmost text is the name.
            title = "BBKB",
            resources = setOf(
                "english_ime_name",
                // The Updates row says what the last check found; a debug build has no channel.
                "settings_updates_title", "settings_update_channel_none",
                "settings_about_source_code", "settings_about_source_code_summary",
                "settings_about_report_problem", "settings_about_discord_summary",
                "settings_about_licenses_title",
            ),
            literals = emptySet(),
        ),
        Case(
            name = "OpenSourceLicensesScreen",
            route = SettingsRoute.Licenses.route,
            title = "Open-source licenses",
            resources = setOf("settings_about_licenses_title", "settings_about_licenses_intro"),
            literals = setOf(
                "AndroidX",
                "AndroidX Emoji2",
                "Google / AOSP • Apache License 2.0",
                "Emojibase",
                "Miles Johnson • MIT License",
                "Jetpack Compose",
                "Google • Apache License 2.0",
                "jsoup",
                "Jonathan Hedley • MIT License",
                "Kotlin Coroutines",
                "JetBrains • Apache License 2.0",
                "Material Components",
                "Shizuku API",
                "Rikka • MIT License",
            ),
        ),
        Case(
            name = "CustomMacrosScreen",
            route = SettingsRoute.CustomMacros.route,
            title = "Custom macros",
            resources = setOf(
                "settings_custom_macros_title", "settings_custom_macros_intro",
                // "Add macro" is the extended FAB's label, which the empty hint names.
                "settings_macros_add", "settings_macros_empty_hint", "settings_macros_empty_title",
                "settings_macros_reserved_tags",
            ),
            literals = emptySet(),
        ),
        Case(
            name = "CustomSymbolPageScreen_PKB",
            route = SettingsRoute.CustomSymbolPagePkb.route,
            title = "Keypad symbol page",
            resources = setOf(
                "settings_keyeditor_hint", "settings_keyeditor_title_pkb", "symbol_list_category_accents_title",
                "symbol_list_category_alphanumeric_title", "symbol_list_category_arrows_title",
                "symbol_list_category_brackets_title", "symbol_list_category_currency_title",
                "settings_device_config_category_custom", "symbol_list_category_emoji_title",
                "symbol_list_category_math_title", "symbol_list_category_punctuation_title"
            ),
            // The board is drawn by the keyboard's own view, not as text nodes.
            literals = emptySet(),
            // The palette loads off the main thread; the placeholder may still be up.
            lateResources = setOf("settings_keyeditor_loading"),
        ),
        Case(
            name = "CustomSymbolPageScreen_VKB",
            route = SettingsRoute.CustomSymbolPageVkb.route,
            title = "On-screen symbol page",
            resources = setOf(
                "settings_keyeditor_hint", "settings_keyeditor_title_vkb", "symbol_list_category_accents_title",
                "symbol_list_category_alphanumeric_title", "symbol_list_category_arrows_title",
                "symbol_list_category_brackets_title", "symbol_list_category_currency_title",
                "settings_device_config_category_custom", "symbol_list_category_emoji_title",
                "symbol_list_category_math_title", "symbol_list_category_punctuation_title"
            ),
            // The board is drawn by the keyboard's own view, not as text nodes.
            literals = emptySet(),
            // The palette loads off the main thread; the placeholder may still be up.
            lateResources = setOf("settings_keyeditor_loading"),
        ),
        Case(
            name = "CustomizationScreen",
            route = SettingsRoute.Preferences.route,
            title = "Input",
            resources = setOf(
                "settings_keyboard_summary", "settings_preferences_title",
                "settings_auxiliary_input_summary", "settings_auxiliary_input_title", "settings_category_on_screen_keyboard",
                // "settings_uim_toggle_voice": that resource also reads "Voice input" and sorts first.
                "settings_uim_toggle_voice", "settings_voice_input_summary",
            ),
            literals = emptySet(),
        ),
        Case(
            name = "CustomizeMenuScreen",
            route = SettingsRoute.CustomizeMenu.route,
            title = "Customize input menu",
            resources = setOf(
                "settings_customize_menu_hidden_header", "settings_customize_menu_instructions",
                "settings_customize_menu_title", "settings_pkb_multifunction_action_clipboard",
                "settings_pkb_multifunction_action_emoji", "settings_pkb_multifunction_action_fcc",
                "settings_pkb_multifunction_action_number_pad", "settings_uim_toggle_voice"
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
            title = "Customize number pad",
            resources = setOf(
                "settings_keyeditor_hint", "settings_vkb_customize_numberpad_title", "symbol_list_category_accents_title",
                "symbol_list_category_alphanumeric_title", "symbol_list_category_arrows_title",
                "symbol_list_category_brackets_title", "symbol_list_category_currency_title",
                "settings_device_config_category_custom", "symbol_list_category_emoji_title",
                "symbol_list_category_math_title", "symbol_list_category_punctuation_title",
            ),
            // The board is drawn by the keyboard's own view, not as text nodes.
            literals = emptySet(),
            // The palette loads off the main thread; the placeholder may still be up.
            lateResources = setOf("settings_keyeditor_loading"),
        ),
        Case(
            name = "DebugSettingsScreen",
            route = SettingsRoute.Debug.route,
            title = "Debug settings",
            resources = setOf(
                // The "DEVELOPER" header resolves to the CKB screen's resource of the same value.
                "prefs_advanced_gesture_thresholds_summary", "prefs_advanced_gesture_thresholds_title",
                "prefs_advanced_settings_title", "prefs_autofill_debug_logging_summary",
                "prefs_autofill_debug_logging_title", "prefs_category_diagnostics", "prefs_category_tuning",
                "prefs_disable_auto_scroll_summary", "prefs_disable_auto_scroll_title",
                "prefs_force_vkb_mode_summary_off", "prefs_force_vkb_mode_title",
                "prefs_key_preview_animations_summary", "prefs_key_preview_animations_title",
                "prefs_shake_tuning_summary", "prefs_shake_tuning_title",
                "prefs_show_ckb_developer_settings_summary", "prefs_show_ckb_developer_settings_title",
                "prefs_show_device_profile_builder_summary", "prefs_show_device_profile_builder_title", "prefs_category_developer"
            ),
            literals = setOf(
                "Gesture Lab (prototype)",
                "Visualize swipes and see the new gesture classifier's verdict; tune thresholds live",
            ),
        ),
        Case(
            name = "DeviceCompatibilityScreen",
            route = SettingsRoute.DeviceCompatibility.route,
            title = "Device compatibility",
            // The row reads "BBKB helper", a value no other resource shares, so it resolves to its
            // own name; the screen it opens is titled "BBKB Helper".
            resources = setOf(
                "keyboard_helper_title", "pref_override_device_meta_state",
                "pref_override_device_meta_state_summary", "settings_device_compatibility_title",
                "settings_device_configuration_summary", "settings_device_configuration_title",
                "settings_pkb_keyboard_helper_summary",
                // The Touch surface helper row is drawn on every phone, greyed out where the
                // profile declares no pad (this JVM's shape), with that state as its summary.
                            ),
            literals = emptySet(),
        ),
        Case(
            name = "DeviceConfigurationScreen",
            route = SettingsRoute.DeviceConfiguration.route,
            title = "Device configuration",
            resources = setOf(
                // The profile builder's row and "THIS DEVICE" subhead compose only with the debug
                // flag `pref_show_device_profile_builder`, off on a fresh install.
                "settings_device_configuration_title", "settings_device_config_category_preloaded", "settings_device_config_category_standard",
                // The importer is an extended FAB, whose label renders as text.
                "device_config_import_button",
            ),
            // The device list is a lazy column; under the 179dp header the last two rows fall
            // below the 891dp test viewport and are not composed, so they are not recorded.
            literals = setOf(
                // PreferenceCategory uppercases, so a subhead never matches its own resource.
                "Detect automatically",
                "BlackBerry KEY2",
                "Other BlackBerry keyboard phones",
                "Minimal Phone (MP01)",
                "Unihertz Titan",
                "Unihertz Titan 2",
                "Unihertz Titan 2 Elite",
                "Unihertz Titan Pocket",
                "Unihertz Titan Slim",
            ),
        ),
        Case(
            name = "DictionariesLearningScreen",
            route = SettingsRoute.Learning.route,
            title = "Learning",
            resources = setOf(
                "settings_dict_add_reverted_words_summary", "settings_dict_add_reverted_words_title",
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
            resources = setOf("prefs_category_tuning"),
            literals = setOf(
                "Test",
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
            title = "Touch feedback",
            resources = setOf(
                "key_preview_popup_dismiss_default_delay", "key_preview_popup_dismiss_delay", "popup_on_keypress",
                "popup_on_keypress_summary", "prefs_keypress_sound_volume_settings",
                "prefs_keypress_vibration_duration_settings", "settings_category_sound",
                "settings_category_vibrate", "settings_touch_feedback_title", "sound_on_keypress_summary",
                "vibrate_on_keypress_summary",
                // The voice screen's Speech recognizer resource shares the "System default" value,
                // so the recorder names it.
                "settings_voice_recognizer_system_default", "vibrate_on_keypress"
            ),
            literals = emptySet(),
        ),
        Case(
            name = "KeyboardHelperScreen",
            route = SettingsRoute.KeyboardHelper.route,
            title = "BBKB helper",
            // The title "BBKB helper" does not share a value with the accessibility service's
            // label "BBKB Helper".
            resources = setOf(
                "keyboard_helper_title", "pref_accessibility_service_disabled",
                "pref_key_interceptor_enabled", "pref_key_interceptor_enabled_summary",
                "pref_manage_accessibility_service",
                "pref_preprocess_all_keys", "pref_preprocess_all_keys_summary",
            ),
            literals = emptySet(),
        ),
        Case(
            name = "LanguagePacksScreen",
            route = SettingsRoute.LanguagePacks.route,
            title = "Language packs",
            resources = setOf(
                // The extended FAB's label, which renders before the IO load finishes.
                "language_packs_load_ldb",
                "settings_language_packs_title", "language_packs_available_header", "language_packs_installed_header"
            ),
            literals = emptySet(),
            // The two section headers are literals (PreferenceCategory upper-cases) and render
            // unconditionally. The rest arrives with the IO load: a row per shipped pack with its
            // two-letter code badge and "Preinstalled" tag.
            lateLiterals = setOf(
                "Chinese", "English", "French", "German", "Italian", "Russian", "Spanish", "Ukrainian",
                "ZH", "EN", "FR", "DE", "IT", "RU", "ES", "UK",
            ),
            // Whatever the catalogue fetch returned: under Robolectric always a failure plus Try
            // again; the other answers are listed so no race is pinned.
            lateResources = setOf(
                "language_packs_download",
                "language_packs_error_app_too_old", "language_packs_error_network",
                "language_packs_error_offline", "language_packs_error_verification",
                "language_packs_retry", "language_packs_variant_summary",
                // The "Preinstalled" tag on each shipped pack's row.
                "language_packs_preinstalled",
            ),
        ),
        Case(
            name = "LanguageSwitchingScreen",
            route = SettingsRoute.LanguageSwitching.route,
            title = "Language switching",
            resources = setOf(
                "settings_category_on_screen_keyboard",
                "settings_lang_quick_switch_summary", "settings_lang_quick_switch_title",
                "settings_lang_spacebar_switch_summary", "settings_lang_spacebar_switch_title",
                "settings_language_switching_title"
            ),
            literals = emptySet(),
        ),
        Case(
            name = "MainSettingsScreen",
            route = SettingsRoute.Main.route,
            title = "BBKB Settings",
            resources = setOf(
                "english_ime_settings",
                "settings_about_title",
                "settings_advanced_summary", "settings_advanced_title", "settings_appearance_layout_summary",
                "settings_appearance_layout_title", "settings_languages_summary", "settings_languages_title",
                "settings_preferences_summary",
                "settings_preferences_title", "settings_search_hint", "settings_suggestion_correction_summary",
                "settings_suggestion_correction_title"
            ),
            literals = emptySet(),
        ),
        Case(
            name = "PhysicalKeyboardScreen",
            route = SettingsRoute.PhysicalKeyboard.route,
            title = "Physical keyboard",
            resources = setOf(
                "pref_lock_row_ctrl", "pref_lock_row_shift", "settings_category_keys", "settings_category_press_twice_lock",
                "ckb_action_none",                                                 "pref_show_pkb_modifier_status_icon", "pref_show_pkb_modifier_status_icon_summary",
                "settings_category_modifiers", 
                "settings_pkb_alt_sym_shortcut_disabled", "settings_pkb_alt_sym_shortcut_title",
                "settings_pkb_ctrl_key_behavior_title", "settings_pkb_dictation_key_summary_on",
                "settings_pkb_dictation_key_title", "settings_pkb_hold_action_off_summary",
                "settings_pkb_hold_action_title", "settings_category_physical_keyboard",
                // "Enable emoji search" for the physical keyboard.
                "pref_emoji_search_title", "pref_emoji_search_pkb_summary"
            ),
            literals = setOf("Alt"),
            // The multifunction-key row needs a physical keyboard too, so its search entry also
            // highlights nothing here.
        ),
        Case(
            name = "PhysicalLayoutsScreen",
            route = SettingsRoute.PhysicalLayouts.route,
            title = "Custom keypad layouts",
            resources = setOf(
                "settings_pkb_layouts_title", "settings_pkb_layouts_add",
                // The Custom header, upper-cased as every category is; "CUSTOM" is also the symbol
                // list's category title, the first resource name carrying that value.
                "settings_pkb_layouts_builtin_note", "settings_device_config_category_custom", "settings_pkb_layouts_category_builtin",
            ),
            literals = setOf(
                // The Built-in header: no resource holds the upper-cased value.
            ),
            // Both lists load on Dispatchers.IO: the empty custom list's empty-state line, and the
            // one [OneBuiltInLayout] document, which names no language and two keys.
            lateLiterals = setOf("Sample layout", "All keyboards · 2 keys"),
            lateResources = setOf("settings_pkb_layouts_empty"),
        ),
        Case(
            name = "PredictionsSuggestionsScreen",
            route = SettingsRoute.Suggestion.route,
            title = "Suggestions",
            resources = setOf(

                "settings_pred_contacts_summary_disabled", "settings_pred_contacts_title",
                "settings_pred_emoji_summary", "settings_pred_emoji_title",
                "settings_category_display", "settings_category_sources",
                "settings_pred_force_suggestions_summary", "settings_pred_force_suggestions_title",
                "settings_pred_inline_autofill_summary",
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
            // The unset phrases are the keyboard's own fallback resources.
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
            name = "ShakeTuningScreen",
            route = SettingsRoute.ShakeTuning.route,
            title = "Shake gesture tuning",
            resources = setOf(
                "prefs_acceleration_threshold_summary", "prefs_acceleration_threshold_title",
                "prefs_fallback_trigger_count_summary", "prefs_fallback_trigger_count_title",
                "prefs_on_axis_trigger_count_summary", "prefs_on_axis_trigger_count_title",
                "prefs_reset_time_summary", "prefs_reset_time_title",
                "prefs_shake_tuning_title", "prefs_slop_time_summary", "prefs_slop_time_title"
            ),
            literals = emptySet(),
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
                // The phrases the keyboard inserts, via settings_vkb_quick_phrases_summary.
                "How are you?, On my way, That's great!…",
            ),
        ),
        Case(
            name = "SpellCheckerSettingsScreen",
            // Not in the settings NavHost at all: it is hosted by SpellCheckerComposeActivity,
            // which is why SettingsSearchIndex routes its entry at the hub row instead.
            route = "(SpellCheckerComposeActivity)",
            title = "Spell checking",
            resources = setOf(
                
                "settings_spell_checker_title", "settings_pred_contacts_summary_disabled",
                "settings_spellcheck_enable_title", "settings_spellcheck_enable_summary",
                "settings_spellcheck_use_contacts_title", "settings_spellcheck_accept_learned_title",
                "settings_spellcheck_accept_learned_summary",
                "settings_spellcheck_intro",
            ),
            literals = emptySet(),
        ),
        Case(
            name = "SymbolCustomizationScreen",
            route = SettingsRoute.SymbolCustomization.route,
            title = "Customize symbols",
            resources = setOf(
                "settings_vkb_customize_numberpad_summary", "settings_vkb_customize_numberpad_title",
                "settings_category_on_screen_keyboard",
                "settings_symbol_custom_page_first_summary",
                "settings_symbol_custom_page_first_title", "settings_symbol_customization_title",
                "settings_symbol_customize_page_summary", "settings_symbol_customize_page_title",
                "settings_symbol_enable_customization_summary", "settings_symbol_enable_customization_title",
                // A link to Slideboard settings.
                "settings_category_slideboard",                             ),
            literals = emptySet(),
        ),
        Case(
            name = "TextShortcutsScreen",
            route = SettingsRoute.TextShortcuts.route,
            title = "Text shortcuts",
            // The title is the Learning screen row's string, as User dictionary's is.
            resources = setOf(
                "settings_dict_text_shortcuts_title",
                // The extended FAB's label renders as text.
                "settings_shortcuts_add",
            ),
            literals = emptySet(),
            // "Initialization timeout" is the store failing to start under Robolectric, an
            // environment artefact, pinned only so a new late string is still caught.
            lateResources = setOf(
                // The empty hint names that button.
                "settings_shortcuts_empty_title", "settings_shortcuts_empty_hint", "settings_dict_init_timeout",
            ),
        ),
        Case(
            name = "TouchScreenKeyboardScreen",
            route = SettingsRoute.OnScreenKeyboard.route,
            title = "On-screen keyboard",
            resources = setOf(
                "settings_category_behavior_vkb", "settings_category_feedback", "settings_category_slideboard",
                "settings_category_gestures",
                 "settings_screen_type_by_swiping",
                "settings_slideboard_hub_summary", "settings_slideboard_settings_title",
                "settings_touch_feedback_summary", "settings_touch_feedback_title",
                "settings_vkb_swipe_down_dismiss_summary", "settings_vkb_swipe_down_dismiss_title",
                "settings_vkb_swipe_gestures_summary", "settings_vkb_swipe_gestures_title",
                "settings_vkb_type_by_swiping_summary", "vkb_control_key_summary", "settings_pkb_multifunction_action_ctrl",
                "settings_pred_flick_animation_summary", "settings_pred_flick_animation_title",
                "prefs_key_longpress_delay_title", "settings_category_on_screen_keyboard",
                // The "Emoji" header's text is also ckb_action_emoji's.
                "ckb_action_emoji", "pref_emoji_search_title", "pref_emoji_search_vkb_summary",
                "pref_emoji_search_replace_text_summary_on", "pref_emoji_search_replace_text_title"
            ),
            literals = setOf("400 ms"),
        ),
        Case(
            name = "TouchSurfaceHelperScreen",
            route = SettingsRoute.TouchSurfaceHelper.route,
            title = "Touch surface helper",
            // The touch-only test device declares no touch surface: the header says so, and
            // neither the Built-in nor the Shizuku section is drawn. Its states on a Titan are
            // pinned by TouchSurfaceHelperScreenTest.
            resources = setOf("touch_surface_helper_title", "settings_status_not_required"),
            literals = emptySet(),
        ),
        Case(
            name = "UpdatesScreen",
            route = SettingsRoute.Updates.route,
            title = "Updates",
            resources = setOf(
                "settings_update_background_check_summary", "settings_update_background_check_title",
                // The tests run against the debug BuildConfig, so the status card's headline and
                // the channel row both honestly read "no update channel for debug builds", and
                // the card offers no Check now button.
                "settings_update_channel_none", "settings_update_channel_title",
                "settings_update_installed_version_title", "settings_update_version_details",
                "settings_updates_title"
            ),
            literals = emptySet(),
        ),
        Case(
            name = "UserDictionaryScreen",
            route = SettingsRoute.UserDictionary.route,
            title = "User dictionary",
            resources = setOf(
                "settings_dict_user_dictionary_title",
                // As above: the extended FAB renders its label.
                "settings_dict_add_word",
            ),
            literals = emptySet(),
            lateResources = setOf(
                "settings_dict_empty_title", "settings_dict_empty_hint", "settings_dict_init_timeout",
            ),
        ),
        Case(
            name = "VoiceInputSettingsScreen",
            route = SettingsRoute.VoiceInput.route,
            title = "Voice input",
            resources = setOf(
                // The "Block offensive words" row lives here: it only ever masked dictation results.
                "settings_pred_block_offensive_summary", "settings_uim_toggle_voice",
                "settings_pred_block_offensive_title", "settings_voice_auto_start_summary_on",
                "settings_voice_auto_start_title", "settings_voice_builtin_summary",
                "settings_voice_builtin_title", "settings_voice_prefer_offline_summary",
                "settings_voice_prefer_offline_title", "settings_voice_use_keyboard_lang_summary",
                // Always drawn: greyed out, not hidden, while the keyboard's language is followed.
                "settings_voice_input_lang_title",
                "settings_voice_use_keyboard_lang_title", "settings_voice_show_partial_results_summary",
                "settings_voice_show_partial_results_title",
                "settings_voice_stop_on_pause_summary_on", "settings_voice_stop_on_pause_title",
                // The "Speech recognizer" row. Robolectric has no recognition app installed, so its
                // summary is the one that says so and suggests apps to install.
                "settings_voice_recognizer_title", "settings_voice_recognizer_none_installed",
                // The four group headings (Language reuses the main menu's title), and the
                // "Download voice languages" switch. The "Offline voice languages" link is not
                // drawn here: Robolectric has no recogniser to declare a settings screen.
                "settings_voice_group_recognition", "settings_languages_title",
                "settings_voice_group_listening", "settings_voice_group_text",
                "settings_voice_download_languages_title", "settings_voice_download_languages_summary",
            ),
            // The Voice input language row's summary: the stored language's display name, built
            // from the locale, so not a resource.
            literals = setOf("English (United States)"),
        ),
        Case(
            name = "VoiceLanguageSelectionScreen",
            route = SettingsRoute.VoiceLanguageSelection.route,
            title = "Voice input language",
            resources = setOf(
                "settings_voice_input_lang_title", "settings_voice_lang_loading",
            ),
            literals = emptySet(),
        ),
        Case(
            name = "WordListEditorScreen",
            route = SettingsRoute.PersonalLearnedWords.route,
            title = "Learned words",
            resources = setOf("settings_dict_learned_words_title"),
            literals = emptySet(),
            lateResources = setOf(
                "settings_learned_empty_title",
                "settings_learned_empty_hint",
            ),
        ),
    )
}

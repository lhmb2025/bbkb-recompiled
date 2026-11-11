package dev.bbkb.ime.core.settings.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import dev.bbkb.ime.R
import dev.bbkb.ime.core.settings.SettingsRoute
import org.robolectric.annotation.Config
import java.io.File

/**
 * Sync tests for the hand-maintained [SettingsSearchIndex]. The index is a flat list kept
 * alongside the Compose settings screens; every entry's `anchor` must match a
 * `settingsSearchAnchor("...")` call in some screen so search navigation can highlight the row.
 * These tests scan the screen sources so a renamed/removed/forgotten anchor fails CI instead of
 * silently breaking search highlighting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SettingsSearchIndexTest {

    private val anchorCallRegex = Regex("settingsSearchAnchor\\(\"([^\"]+)\"\\)")

    /** `app/src/main`, however the test happens to be launched (cwd is usually `app/`). */
    private fun mainDir(): File {
        val dir = sequenceOf("src/main", "app/src/main", "../src/main", "../app/src/main", "../../app/src/main")
            .map { File(it).canonicalFile }
            .firstOrNull { it.isDirectory }
        assertTrue("app/src/main not found from ${File(".").canonicalPath}", dir != null)
        return dir!!
    }

    private fun screensDir() = File(mainDir(), "java/dev/bbkb/ime/core/settings/screens")

    /** Anchors declared by the screens, scanned from source. */
    private fun screenAnchors(): Set<String> =
        screensDir().walkTopDown()
            .filter { it.extension == "kt" }
            .flatMap { f -> anchorCallRegex.findAll(f.readText()).map { it.groupValues[1] } }
            .toSet()

    @Test
    fun indexIsNotEmpty() {
        assertTrue(SettingsSearchIndex.entries.size > 40)
    }

    @Test
    fun everyIndexAnchorExistsInAScreen() {
        val declared = screenAnchors()
        val missing = SettingsSearchIndex.entries
            .mapNotNull { it.anchor }
            .filter { it !in declared }
        assertTrue(
            "Index anchors with no settingsSearchAnchor(...) in any screen (renamed or removed?): $missing",
            missing.isEmpty()
        )
    }

    @Test
    fun everyScreenAnchorIsSearchable() {
        val indexed = SettingsSearchIndex.entries.mapNotNull { it.anchor }.toSet()
        val unindexed = screenAnchors().filter { it !in indexed }
        assertTrue(
            "Screen anchors missing from SettingsSearchIndex (new setting not added to search?): $unindexed",
            unindexed.isEmpty()
        )
    }

    @Test
    fun noDuplicateRouteAnchorPairs() {
        val dupes = SettingsSearchIndex.entries
            .filter { it.anchor != null }
            .groupBy { it.route to it.anchor }
            .filterValues { it.size > 1 }
            .keys
        assertTrue("Duplicate (route, anchor) entries in index: $dupes", dupes.isEmpty())
    }

    /**
     * A route is one screen, and search groups its results under the hub the screen hangs off, so
     * every entry pointing at the same screen must name the same category. Two entries that
     * disagree put one screen's rows under two headings, which is what happens when a leaf screen
     * is re-parented — "Slideboard settings" moving from Personalization to On-Screen Keyboard —
     * and only some of its entries are moved with it.
     */
    @Test
    fun everyRouteHasOneCategory() {
        val split = SettingsSearchIndex.entries
            .groupBy { it.route }
            .filterValues { it.map { entry -> entry.categoryTitleRes }.distinct().size > 1 }
            .keys
        assertTrue("Routes indexed under more than one category: $split", split.isEmpty())
    }

    @Test
    fun everyEntryHasKeywordsAndRoute() {
        val bad = SettingsSearchIndex.entries.filter { it.keywords.isBlank() || it.route.isBlank() }
        assertTrue("Entries with blank keywords/route: $bad", bad.isEmpty())
    }

    // ── index ↔ owner (§5.8 item 4) ──────────────────────────────────────────

    /**
     * Every string in `app/src/main` that could be a preference key being *read*: all Java/Kotlin
     * source with the `settingsSearchAnchor("…")` calls stripped out (so a screen that only
     * decorates a row does not count as owning the key), plus the values of `strings.xml`, because
     * a dozen keys are declared there as `<string name="pref_x_key">pref_x</string>` and read via
     * `context.getString(R.string.pref_x_key)` rather than as an inline literal.
     */
    private fun ownerCorpus(): String {
        val code = File(mainDir(), "java").walkTopDown()
            .filter { it.extension == "kt" || it.extension == "java" }
            .filter { it.name != "SettingsSearchIndex.kt" }
            .joinToString("\n") { anchorCallRegex.replace(it.readText(), "") }
        val strings = File(mainDir(), "res/values/strings.xml").let {
            if (it.isFile) it.readText() else ""
        }
        return code + "\n" + strings
    }

    /**
     * The direction the plan called out as the one that silently rots (§3.5, §5.8 item 4): the
     * existing tests guard index ↔ screen, so a renamed anchor fails CI — but an anchor that
     * matches a live `settingsSearchAnchor` call and yet names a preference key nobody reads any
     * more passes both of them. Search then finds the setting, navigates to it, highlights a row,
     * and the value goes nowhere.
     *
     * Note the plan's own wording ("a key SettingsManager knows about") is too narrow to assert:
     * 22 of the 68 anchors are legitimately owned outside SettingsManager/SettingsValues — the CKB
     * gesture assignments by CkbGestureBridge, the theme keys by PrefsManager.Keys, the unified
     * key-mapping and symbol-auto-close flags by the key-event path. What every anchor must have is
     * *an* owner that reads it.
     */
    @Test
    fun everyIndexAnchorIsReadByAnOwner() {
        val corpus = ownerCorpus()
        val unowned = SettingsSearchIndex.entries
            .mapNotNull { it.anchor }
            .distinct()
            .filter { "\"$it\"" !in corpus && ">$it<" !in corpus }
        assertTrue(
            "Index anchors that no code reads as a preference key — search would navigate to a " +
                "row whose value is never consumed: $unowned",
            unowned.isEmpty()
        )
    }

    // ── device gating (defect 16) ────────────────────────────────────────────

    /** The KEY2 shape: a physical keyboard with capacitive keys and a mic key. */
    private val pkbDevice = SearchDeviceCapabilities(hasPhysicalKeyboard = true, hasMultifunctionKey = true, hasTouchKeypad = true)
    private val touchDevice = SearchDeviceCapabilities(hasPhysicalKeyboard = false, hasMultifunctionKey = false)

    private fun anchorsOn(capabilities: SearchDeviceCapabilities): Set<String> =
        SettingsSearchIndex.entriesFor(capabilities).mapNotNull { it.anchor }.toSet()

    /**
     * The half of the guard that cannot be rendered. `SettingsScreenRenderTest` probes the real
     * composition, but `DeviceProfile` is a process-wide singleton, so it only ever runs as the
     * touch-only device — the KEY2 rendering of the gated screens is not pinned anywhere.
     *
     * This asserts the filter itself flips the right way for both shapes, which is what makes the
     * touch-side render assertion meaningful in the other direction too.
     */
    @Test
    fun deviceGatesFollowTheDeviceShape() {
        val onPkb = anchorsOn(pkbDevice)
        val onTouch = anchorsOn(touchDevice)

        // Physical-keyboard-only rows: offered on the KEY2, hidden on a touch-only phone.
        for (anchor in listOf("auto_correction_mode_PKB", "pkb_custom_page_first")) {
            assertTrue("$anchor should be searchable on a physical-keyboard device", anchor in onPkb)
            assertFalse("$anchor is not rendered on a touch-only device, so search must not offer it", anchor in onTouch)
        }

        // The mic key only exists where the device mapping declares one.
        assertTrue("pref_multifunction_key_action" in onPkb)
        assertFalse(
            "pref_multifunction_key_action is hidden unless the device mapping declares a " +
                "MULTIFUNCTION key, so search must not offer it either",
            "pref_multifunction_key_action" in onTouch
        )

        // The on-screen symbol page section is drawn on every device (a KEY2 has the on-screen
        // keyboard too), so its toggle is searchable on both shapes.
        assertTrue("vkb_custom_page_first" in onTouch)
        assertTrue("vkb_custom_page_first" in onPkb)

        // Rows on a screen the device never shows the way into go with it: the Physical keyboard
        // screen's rows on a touchscreen phone, the CKB gesture rows without capacitive keys.
        assertTrue("pref_shift_double_tap_lock" in onPkb)
        assertFalse("the Physical keyboard screen is not reachable on a touch-only phone", "pref_shift_double_tap_lock" in onTouch)
        assertTrue("ckb_gesture_hold" in onPkb)
        assertFalse(
            "the CKB gesture screen is not reachable without capacitive keys",
            "ckb_gesture_hold" in anchorsOn(pkbDevice.copy(hasTouchKeypad = false))
        )

        // Everything else is device-independent and must not have been swept up by the gating.
        // The BBKB helper's entries count here: both shapes are phones whose profile keeps the
        // helper offered (keyboardHelperEntriesFollowTheProfilesHelperSwitch covers the switch),
        // and so does Dynamic color: both run Android 12 or later by default.
        val shapeIndependent = setOf(
            DeviceRequirement.ANY,
            DeviceRequirement.ACCESSIBILITY_HELPER_AVAILABLE,
            DeviceRequirement.DYNAMIC_COLOR,
            DeviceRequirement.VOICE_LANGUAGE_DOWNLOADS,
        )
        assertEquals(
            "gating changed the entries that are NOT device-specific",
            SettingsSearchIndex.entries
                .filter {
                    it.requires in shapeIndependent &&
                        SettingsSearchIndex.screenRequirement(it.route) in shapeIndependent
                }
                .mapNotNull { it.anchor }
                .toSet(),
            onPkb intersect onTouch
        )
    }

    /**
     * The Touch surface helper row is drawn on every phone (greyed out without a declared pad), so
     * its entry is offered everywhere; its Shizuku section only where the profile declares a pad
     * and the selector gives it to the Shizuku reader — not on a Titan 2 on Android 16, whose
     * built-in route works.
     */
    /** The BBKB helper's settings are not offered where its row is greyed out (BlackBerry phones). */
    @Test
    fun keyboardHelperEntriesFollowTheProfilesHelperSwitch() {
        val helperAnchors = setOf("pref_key_interceptor_enabled", "pref_preprocess_all_key_events")
        assertTrue(helperAnchors.all { it in anchorsOn(pkbDevice) })
        val blackBerry = pkbDevice.copy(accessibilityHelperAvailable = false)
        assertTrue(helperAnchors.none { it in anchorsOn(blackBerry) })
    }

    @Test
    fun touchSurfaceEntriesFollowTheProfilesPadAndRoute() {
        val titanOnShizuku = pkbDevice.copy(declaresTouchKeypad = true, touchKeypadUsesShizuku = true)
        val titanBuiltIn = pkbDevice.copy(declaresTouchKeypad = true, touchKeypadUsesShizuku = false)

        assertTrue("touch_surface_helper" in anchorsOn(titanOnShizuku))
        assertTrue("touch_surface_shizuku" in anchorsOn(titanOnShizuku))
        assertTrue("touch_surface_helper" in anchorsOn(titanBuiltIn))
        assertFalse(
            "the Shizuku section is not drawn on the built-in route, so search must not offer it",
            "touch_surface_shizuku" in anchorsOn(titanBuiltIn)
        )
        for (device in listOf(pkbDevice, touchDevice)) {
            assertTrue("the row is drawn, greyed out, on every phone", "touch_surface_helper" in anchorsOn(device))
            assertFalse("touch_surface_shizuku" in anchorsOn(device))
        }
        // Reading the selector alone is not enough: no declared pad, no Shizuku section.
        assertFalse("touch_surface_shizuku" in anchorsOn(pkbDevice.copy(touchKeypadUsesShizuku = true)))
    }

    /**
     * "Show or hide the input menu" is an action of the multifunction key and of the Alt+Sym
     * shortcut, and the way back to the menu once "Show the suggestion bar" has hidden it: a
     * search for the menu finds both settings that can be set to it, as well as that toggle.
     * Matched as MainSettingsScreen matches, by substring of the keywords.
     */
    @Test
    fun searchingForTheInputMenuFindsTheShortcutsThatShowOrHideIt() {
        val found = SettingsSearchIndex.entriesFor(pkbDevice)
            .filter { it.keywords.contains("input menu") }
            .mapNotNull { it.anchor }
            .toSet()

        for (anchor in listOf(
            "pref_multifunction_key_action", "pref_alt_sym_shortcut_action", "pref_uim_enabled",
        )) {
            assertTrue("a search for the input menu should offer $anchor", anchor in found)
        }
    }

    /**
     * The gating is only worth anything if the search UI consumes the filtered view. This is a
     * source check rather than a behavioural one because `MainSettingsScreen` is a composable with
     * no seam to observe its result list from here; the render test covers reachability, this
     * covers the one line that decides what gets offered in the first place.
     */
    @Test
    fun theSearchUiQueriesTheDeviceFilteredView() {
        val offenders = File(mainDir(), "java/dev/bbkb/ime/core/settings")
            .walkTopDown()
            .filter { it.extension == "kt" && it.name != "SettingsSearchIndex.kt" }
            // \b so this does not match `entriesFor(...)`, which is the correct call.
            .filter { Regex("""SettingsSearchIndex\.entries\b""").containsMatchIn(it.readText()) }
            .map { it.name }
            .toList()
        assertTrue(
            "These read SettingsSearchIndex.entries directly instead of entriesFor(...), so they " +
                "would offer settings whose rows this device does not render: $offenders",
            offenders.isEmpty()
        )
    }

    // ── screens ──────────────────────────────────────────────────────────────

    /** The routes the settings NavHost registers, as `SettingsRoute` object names. */
    private fun navHostRouteObjects(): Set<String> {
        val activity = File(mainDir(), "java/dev/bbkb/ime/core/settings/ComposeSettingsActivity.kt").readText()
        return Regex("""composable\(SettingsRoute\.(\w+)\.route\)""").findAll(activity)
            .map { it.groupValues[1] }
            .toSet()
    }

    @Test
    fun everyScreenRouteIsRegisteredInTheNavHost() {
        val registered = navHostRouteObjects()
        assertTrue("found no composable(...) routes in ComposeSettingsActivity", registered.size > 20)
        val unregistered = SettingsSearchIndex.screens.filter { screen ->
            val route = SettingsRoute.fromRoute(screen.route)
            route == null || route.javaClass.simpleName !in registered
        }.map { it.route }
        assertTrue("Screen index routes the settings NavHost does not register: $unregistered", unregistered.isEmpty())
    }

    @Test
    fun everyScreenTitleAndPathResolves() {
        val context = RuntimeEnvironment.getApplication()
        val categories = SettingsSearchIndex.screens.filter { it.parentPath.isEmpty() }.map { it.titleRes }.toSet()
        // The five categories plus About, which sits on its own below them.
        assertEquals("the six rows on the settings root", 6, categories.size)
        for (screen in SettingsSearchIndex.screens) {
            assertTrue("blank title for ${screen.route}", context.getString(screen.titleRes).isNotBlank())
            assertTrue("blank keywords for ${screen.route}", screen.keywords.isNotBlank())
            screen.parentPath.forEach {
                assertTrue("blank path title for ${screen.route}", context.getString(it).isNotBlank())
            }
            if (screen.parentPath.isNotEmpty()) {
                assertTrue("${screen.route}'s path must start at a category", screen.parentPath.first() in categories)
            }
        }
        // One entry per page (Spell checking opens its own activity from the Assistance hub's route).
        val dupes = SettingsSearchIndex.screens.filter { it.activity == null }
            .groupBy { it.route }.filterValues { it.size > 1 }.keys
        assertTrue("screens indexed twice: $dupes", dupes.isEmpty())
    }

    private fun search(query: String, capabilities: SearchDeviceCapabilities): List<SettingsSearchResult> {
        val context = RuntimeEnvironment.getApplication()
        val screens = SettingsSearchIndex.screensFor(capabilities)
        val settings = SettingsSearchIndex.entriesFor(capabilities)
        return SettingsSearchIndex.search(
            query,
            screens, screens.map { context.getString(it.titleRes).lowercase() },
            settings, settings.map { context.getString(it.titleRes).lowercase() },
        )
    }

    private fun SettingsSearchResult.route() = when (this) {
        is SettingsSearchResult.Screen -> screen.route
        is SettingsSearchResult.Setting -> setting.route
    }

    @Test
    fun aScreenWhoseTitleMatchesIsListedBeforeTheRows() {
        val physical = search("Physical keyboard", pkbDevice)
        val first = physical.first()
        assertTrue("expected the Physical keyboard screen first, got $first", first is SettingsSearchResult.Screen)
        assertEquals(SettingsRoute.PhysicalKeyboard.route, first.route())
        assertTrue("rows still follow", physical.any { it is SettingsSearchResult.Setting })
        val firstRow = physical.indexOfFirst { it is SettingsSearchResult.Setting }
        val titleMatches = physical.take(firstRow)
        assertTrue(titleMatches.all { it is SettingsSearchResult.Screen })

        assertEquals(SettingsRoute.PersonalLearnedWords.route, search("learned words", pkbDevice).first().route())
        assertEquals(SettingsRoute.LanguagesHub.route, search("lang", touchDevice).first().route())
        assertEquals(SettingsRoute.SuggestionCorrection.route, search("assistance", touchDevice).first().route())
    }

    @Test
    fun aScreenMatchedOnlyByKeywordComesAfterTheRows() {
        val results = search("dictation", touchDevice)
        val voice = results.indexOfFirst { it is SettingsSearchResult.Screen && it.screen.route == SettingsRoute.VoiceInput.route }
        assertTrue("Voice input is found by its keywords", voice >= 0)
        assertTrue("and listed after the matching rows", results.take(voice).isNotEmpty() && results.take(voice).all { it is SettingsSearchResult.Setting })
    }

    @Test
    fun aRowTitledAsAListedScreenIsLeftOut() {
        val results = search("backup and restore", touchDevice)
        assertEquals(SettingsRoute.BackupRestore.route, results.first().route())
        assertFalse(
            "the Advanced row of the same name would list the page twice",
            results.any { it is SettingsSearchResult.Setting && it.setting.titleRes == R.string.settings_backup_restore_title }
        )
        assertTrue(search("", touchDevice).isEmpty())
        assertTrue(search("   ", touchDevice).isEmpty())
    }

    @Test
    fun screensAndRowsTheDeviceDoesNotShowAreNotOffered() {
        fun routes(c: SearchDeviceCapabilities) = SettingsSearchIndex.screensFor(c).map { it.route }.toSet()

        assertFalse(SettingsRoute.PhysicalKeyboard.route in routes(touchDevice))
        assertTrue(SettingsRoute.PhysicalKeyboard.route in routes(pkbDevice))
        assertFalse(SettingsRoute.PhysicalLayouts.route in routes(touchDevice))
        assertFalse(SettingsRoute.CkbGestures.route in routes(pkbDevice.copy(hasTouchKeypad = false)))
        assertFalse(SettingsRoute.TouchSurfaceHelper.route in routes(pkbDevice))
        assertTrue(SettingsRoute.TouchSurfaceHelper.route in routes(pkbDevice.copy(declaresTouchKeypad = true)))
        assertFalse(SettingsRoute.KeyboardHelper.route in routes(pkbDevice.copy(accessibilityHelperAvailable = false)))

        // Debug settings: a release build draws no row for them.
        assertFalse(SettingsRoute.Debug.route in routes(touchDevice))
        assertTrue(SettingsRoute.Debug.route in routes(touchDevice.copy(isDebugBuild = true)))
        assertTrue(search("debug", touchDevice).isEmpty())

        // Dynamic color: Android 12 and later only.
        assertTrue("pref_keyboard_use_system_colors" in anchorsOn(touchDevice))
        assertFalse("pref_keyboard_use_system_colors" in anchorsOn(touchDevice.copy(supportsDynamicColor = false)))
    }

    /** The UI must offer the device-filtered screens, as it does the rows. */
    @Test
    fun theSearchUiQueriesTheDeviceFilteredScreens() {
        val offenders = File(mainDir(), "java/dev/bbkb/ime/core/settings")
            .walkTopDown()
            .filter { it.extension == "kt" && it.name != "SettingsSearchIndex.kt" }
            .filter { Regex("""SettingsSearchIndex\.screens\b""").containsMatchIn(it.readText()) }
            .map { it.name }
            .toList()
        assertTrue("These read SettingsSearchIndex.screens instead of screensFor(...): $offenders", offenders.isEmpty())
    }
}

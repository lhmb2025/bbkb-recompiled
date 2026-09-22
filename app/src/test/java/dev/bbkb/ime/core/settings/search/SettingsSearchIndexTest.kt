package dev.bbkb.ime.core.settings.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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

    private val pkbDevice = SearchDeviceCapabilities(hasPhysicalKeyboard = true, hasMultifunctionKey = true)
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

        // ...and the mirror image: the on-screen symbol page is the branch a KEY2 never draws.
        assertTrue("vkb_custom_page_first" in onTouch)
        assertFalse(
            "vkb_custom_page_first sits in the `else` of SymbolCustomizationScreen's " +
                "hasPhysicalKeyboard gate, so a physical-keyboard device never renders it",
            "vkb_custom_page_first" in onPkb
        )

        // Everything else is device-independent and must not have been swept up by the gating.
        assertEquals(
            "gating changed the entries that are NOT device-specific",
            SettingsSearchIndex.entries
                .filter { it.requires == DeviceRequirement.ANY }
                .mapNotNull { it.anchor }
                .toSet(),
            onPkb intersect onTouch
        )
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
}

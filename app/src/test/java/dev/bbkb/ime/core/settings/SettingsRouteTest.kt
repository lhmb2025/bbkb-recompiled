package dev.bbkb.ime.core.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * §5.8 item 6: [SettingsRoute] carried twelve duplicate-identity objects — `Keyboard` and
 * `TouchScreenKeyboard` for the same page as `OnScreenKeyboard`, `Credits` for `About`, and eight
 * more. Nothing outside the file referenced them, so they were deleted; the *route strings* are the
 * part that has to survive, because they are what a deep link and the `"screen"` intent extra
 * carry into [SettingsRoute.fromRoute], which validates the start destination for
 * `ComposeSettingsActivity`.
 *
 * The list below is the exact set `fromRoute` accepted before the tidy. It is written out by hand
 * rather than derived, because deriving it from the class under test would make the test agree with
 * any future deletion.
 *
 * Contract: every string here must still resolve, and must resolve to a destination the NavHost
 * actually registers. Six of them ("personalization", "learned_words", "feedback_haptics",
 * "personal_dictionary", "word_substitutions", "unified_dictionary") used to resolve to objects
 * with no registration, which crashed the activity; they now resolve to the page that replaced
 * each one, so an old intent or shortcut still lands somewhere sensible.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SettingsRouteTest {

    private val acceptedBeforeTheTidy = listOf(
        "main",
        // Primary destinations, plus the four legacy spellings that never had an object.
        "languages", "languages_input",
        "suggestion_correction", "prediction_correction", "correction_learning",
        "preferences", "customization",
        "appearance_layout",
        "advanced",
        // Typing and input.
        "on_screen_keyboard", "physical_keyboard", "voice_input_main",
        "personalization", "touch_feedback", "key_press_feedback",
        // Suggestion and correction.
        "suggestion", "correction", "learning",
        // Advanced.
        "device_compatibility", "device_configuration", "debug",
        "advanced_gesture_parameters", "gesture_lab", "ckb_gestures", "animation_parameters",
        // Languages.
        "language_switching", "multi_language_keyboards", "multi_language_wizard_add",
        "language_packs",
        // Dictionaries.
        "learned_words", "personal_learned_words", "personal_dictionary",
        "word_substitutions", "unified_dictionary", "user_dictionary",
        // Text and symbols.
        "text_shortcuts", "custom_macros", "symbol_customization",
        "custom_symbol_page_pkb", "custom_symbol_page_vkb",
        // Boards and menus.
        "quick_phrases", "shake", "voice_input", "voice_language_selection",
        "customize_slideboard", "slideboard_settings", "customize_menu",
        "feedback_haptics",
        // About.
        "about", "keyboard_helper",
    )

    @Test
    fun everyRouteStringThatUsedToResolveStillResolves() {
        val broken = acceptedBeforeTheTidy.filter { SettingsRoute.fromRoute(it) == null }
        assertTrue(
            "Route strings that no longer resolve — a deep link or a \"screen\" extra using one of " +
                "these now falls back to the main menu: $broken",
            broken.isEmpty(),
        )
    }

    @Test
    fun aResolvedRouteResolvesToItselfOrToItsCanonicalDestination() {
        // An alias string resolves to the destination it aliases, whose own route must resolve too.
        acceptedBeforeTheTidy.forEach { s ->
            val destination = SettingsRoute.fromRoute(s)
            assertNotNull("\"$s\" did not resolve", destination)
            assertNotNull(
                "\"$s\" resolved to ${destination!!.route}, which does not itself resolve",
                SettingsRoute.fromRoute(destination.route),
            )
        }
    }

    private fun sourceFile(path: String): String {
        val mainJava = sequenceOf("src/main/java", "app/src/main/java", "../app/src/main/java")
            .map { File(it).canonicalFile }
            .firstOrNull { it.isDirectory }
        assertNotNull("app/src/main/java not found from ${File(".").canonicalPath}", mainJava)
        return File(mainJava, path).readText()
    }

    @Test
    fun everyResolvedRouteHasANavHostDestination() {
        // fromRoute's result becomes the NavHost start destination; one with no composable{}
        // registration makes navigation-compose throw as the settings activity opens.
        val activity = sourceFile("dev/bbkb/ime/core/settings/ComposeSettingsActivity.kt")
        val registered = Regex("""composable\(\s*SettingsRoute\.(\w+)\.route""")
            .findAll(activity).map { it.groupValues[1] }.toSet()
        val unregistered = acceptedBeforeTheTidy
            .mapNotNull { s -> SettingsRoute.fromRoute(s)?.let { s to it::class.simpleName } }
            .filter { (_, name) -> name !in registered }
        assertTrue("Route strings resolving to a destination the NavHost lacks: $unregistered", unregistered.isEmpty())
    }

    @Test
    fun legacyRoutesWithoutAScreenLandOnTheScreenThatReplacedThem() {
        assertEquals(SettingsRoute.AppearanceLayout, SettingsRoute.fromRoute("personalization"))
        assertEquals(SettingsRoute.PersonalLearnedWords, SettingsRoute.fromRoute("learned_words"))
        assertEquals(SettingsRoute.TouchFeedback, SettingsRoute.fromRoute("feedback_haptics"))
        assertEquals(SettingsRoute.UserDictionary, SettingsRoute.fromRoute("personal_dictionary"))
        assertEquals(SettingsRoute.TextShortcuts, SettingsRoute.fromRoute("word_substitutions"))
        assertEquals(SettingsRoute.Learning, SettingsRoute.fromRoute("unified_dictionary"))
    }

    @Test
    fun unknownRoutesResolveToNull() {
        // This is what stops an arbitrary "screen" extra reaching navigation-compose.
        assertEquals(null, SettingsRoute.fromRoute("no_such_screen"))
        assertEquals(null, SettingsRoute.fromRoute(""))
        assertEquals(null, SettingsRoute.fromRoute(null))
    }

    @Test
    fun theDeletedAliasObjectsAreStillUnreferenced() {
        // If one of these names comes back as a call site, it is a duplicate identity for a page
        // that already has one — resolve it to the canonical object instead.
        val deleted = listOf(
            "Keyboard", "TouchScreenKeyboard", "Credits", "LanguagesInput", "Customization",
            "CorrectionLearning", "PredictionCorrection", "Prediction", "PredictionsSuggestions",
            "DictionaryLearning", "DictionariesLearning", "AutoCorrection",
            // Accepted by fromRoute but never registered in the NavHost; their strings now alias
            // the page that replaced each one.
            "Personalization", "LearnedWords", "FeedbackHaptics", "PersonalDictionary",
            "WordSubstitutions", "UnifiedDictionary",
        )
        val mainJava = sequenceOf("src/main/java", "app/src/main/java", "../app/src/main/java")
            .map { File(it).canonicalFile }
            .firstOrNull { it.isDirectory }
        assertNotNull("app/src/main/java not found from ${File(".").canonicalPath}", mainJava)
        val sources = mainJava!!.walkTopDown()
            .filter { it.extension == "kt" || it.extension == "java" }
            .joinToString("\n") { it.readText() }
        // \b on the trailing side, or "Keyboard" matches the live "KeyboardHelper".
        val resurrected = deleted.filter { Regex("SettingsRoute\\.$it\\b").containsMatchIn(sources) }
        assertTrue("Deleted SettingsRoute aliases referenced again: $resurrected", resurrected.isEmpty())
    }
}

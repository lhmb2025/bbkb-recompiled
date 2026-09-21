package dev.bbkb.ime.personaldictionary.macro

import android.content.Context
import dev.bbkb.ime.personaldictionary.model.WordSubstitution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowSettings
import java.util.Locale

/**
 * Regression tests for the two macro literals a past global identifier rename
 * corrupted (audit findings PD-4 and PD-11).
 *
 * Before the fix:
 *  * `%T` resolved to the pattern `"hh:mm:ss SuggestedWordInfo"` in any 12-hour
 *    locale, and `SimpleDateFormat` throws `IllegalArgumentException` on the
 *    illegal pattern letter `e` — so the macro blew up uncaught on the
 *    word-substitution commit path, invisibly on a 24-hour test device.
 *  * the battery macro's tag was the identifier `%TextChangeType`, which
 *    `DynamicMacroType.TIME` (`%T`) consumed as a prefix before `BATTERY` was ever
 *    reached, and which `CustomMacro.isTagValid`'s `length == 1` check could never
 *    reserve.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class DynamicMacroTypeTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun ws(word: String) = WordSubstitution(
        Locale.US.toString(), "k", word, WordSubstitution.Type.USER, false
    )

    private fun expand(word: String, locale: Locale = Locale.US): String =
        DynamicContentHandler.handleDynamicContent(context, ws(word), locale)

    private fun set24Hour(on: Boolean) = ShadowSettings.set24HourTimeFormat(on)

    // ---- PD-4: the %T pattern ------------------------------------------------

    @Test
    fun timeMacro_12HourPatternIsAValidAmPmPattern() {
        assertEquals("hh:mm:ss a", DynamicMacroType.TIME_FORMAT_12HR)
        assertEquals("HH:mm:ss", DynamicMacroType.TIME_FORMAT_24HR)
    }

    @Test
    fun timeMacro_expandsIn12HourLocaleWithoutThrowing() {
        set24Hour(false)
        val out = expand("%T")
        // hh:mm:ss + a locale-specific AM/PM marker.
        assertTrue(
            "12-hour %T expanded to '$out'",
            Regex("""^\d{2}:\d{2}:\d{2}\s+\S+$""").matches(out)
        )
    }

    @Test
    fun timeMacro_expandsIn24HourLocale() {
        set24Hour(true)
        val out = expand("%T")
        assertTrue("24-hour %T expanded to '$out'", Regex("""^\d{2}:\d{2}:\d{2}$""").matches(out))
    }

    @Test
    fun timeMacro_expandsInsideSurroundingText() {
        set24Hour(true)
        val out = expand("at %T sharp")
        assertTrue("expanded to '$out'", Regex("""^at \d{2}:\d{2}:\d{2} sharp$""").matches(out))
    }

    // ---- PD-10: %D is a skeleton, not a literal pattern ----------------------

    @Test
    fun dateMacro_isResolvedThroughGetBestDateTimePattern() {
        val out = expand("%D")
        // The raw skeleton "EEEEddMMMMyyyy" would render as e.g. "Saturday06September2026";
        // a resolved pattern separates the fields.
        assertFalse("%D still rendered from the raw skeleton: '$out'", Regex("""^\p{L}+\d{2}\p{L}+\d{4}$""").matches(out))
        assertTrue("%D expanded to '$out'", out.contains(" "))
    }

    @Test
    fun isoMacros_keepTheirLiteralPatterns() {
        assertTrue(Regex("""^\d{4}-\d{2}-\d{2}$""").matches(expand("%d")))
        assertTrue(Regex("""^\d{4}$""").matches(expand("%y")))
        assertTrue(Regex("""^\d{2}:\d{2}$""").matches(expand("%t")))
    }

    // ---- PD-11: the battery macro tag ---------------------------------------

    @Test
    fun batteryMacro_tagIsASingleCharacter() {
        val tag = DynamicMacroType.BATTERY.tag
        assertEquals("%b", tag)
        assertEquals(2, tag.length)
    }

    @Test
    fun batteryMacro_tagIsReservedAgainstCustomMacros() {
        val letter = DynamicMacroType.BATTERY.tag.removePrefix("%")
        assertTrue("battery letter '$letter' is not reserved", letter in CustomMacro.RESERVED_TAGS)
        assertFalse(CustomMacro.isTagValid(letter))
        // and every other built-in tag is reserved too
        for (type in DynamicMacroType.entries) {
            assertTrue(
                "built-in tag ${type.tag} is not reserved",
                type.tag.removePrefix("%") in CustomMacro.RESERVED_TAGS
            )
        }
    }

    @Test
    fun batteryMacro_expandsToAPercentage() {
        val out = expand("%b")
        assertTrue("battery macro expanded to '$out'", Regex("""^(\d{1,3}|\?)%$""").matches(out))
    }

    @Test
    fun batteryMacro_isNotSwallowedByTheTimeMacro() {
        set24Hour(true)
        // The old "%TextChangeType" tag started with "%T", so TIME consumed it first.
        val out = expand("%b")
        assertFalse("battery tag was consumed by %T: '$out'", out.contains(":"))
    }

    // ---- macro tags do not collide -----------------------------------------

    @Test
    fun builtInTagsAreUniqueAndSingleLetter() {
        val tags = DynamicMacroType.entries.map { it.tag }
        assertEquals(tags.size, tags.toSet().size)
        for (tag in tags) {
            assertTrue("tag '$tag' is not %<single char>", tag.length == 2 && tag[0] == '%')
        }
    }
}

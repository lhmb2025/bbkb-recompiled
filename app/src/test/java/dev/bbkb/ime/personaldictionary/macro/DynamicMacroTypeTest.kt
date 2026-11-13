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
 * The built-in macro tags and patterns: `%T` is a valid pattern in 12- and 24-hour locales,
 * `%D` is resolved from a skeleton, and every tag is a single reserved letter, so no tag is
 * consumed as a prefix of another.
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
        // A tag starting with "%T" would be consumed by TIME first.
        val out = expand("%b")
        assertFalse("battery tag was consumed by %T: '$out'", out.contains(":"))
    }

    @Test
    fun builtInTagsAreUniqueAndSingleLetter() {
        val tags = DynamicMacroType.entries.map { it.tag }
        assertEquals(tags.size, tags.toSet().size)
        for (tag in tags) {
            assertTrue("tag '$tag' is not %<single char>", tag.length == 2 && tag[0] == '%')
        }
    }
}

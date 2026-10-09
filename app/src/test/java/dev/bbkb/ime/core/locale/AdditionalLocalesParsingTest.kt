package dev.bbkb.ime.core.locale

import android.view.inputmethod.InputMethodSubtype
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * A multi-language keyboard carries its extra prediction languages as one slash-joined extra
 * value ("de/es", "fr_CA"). The IME's pack loader used to build a single Locale from the whole
 * string, so a keyboard with two extras or a regional one asked the engine for a language such
 * as "de/es" and the engine refused the entire set. Both callers now share this parser.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AdditionalLocalesParsingTest {

    private fun subtype(extras: String?): InputMethodSubtype =
        InputMethodSubtype.InputMethodSubtypeBuilder()
            .setSubtypeLocale("en_US")
            .setSubtypeMode("keyboard")
            .setSubtypeExtraValue(if (extras == null) "KeyboardLayoutSet=qwerty" else "KeyboardLayoutSet=qwerty,AdditionalLocales=$extras")
            .build()

    @Test
    fun twoExtrasBecomeTwoLocales() {
        assertEquals(setOf(Locale("de"), Locale("es")), ResourceLocaleUtils.getAdditionalLocales(subtype("de/es")))
    }

    @Test
    fun aRegionalExtraKeepsItsCountry() {
        assertEquals(setOf(Locale("fr", "CA")), ResourceLocaleUtils.getAdditionalLocales(subtype("fr_CA")))
        assertEquals(setOf(Locale("de"), Locale("fr", "CA")), ResourceLocaleUtils.getAdditionalLocales(subtype("de/fr_CA")))
    }

    @Test
    fun noExtrasMeansNull() {
        assertNull(ResourceLocaleUtils.getAdditionalLocales(subtype(null)))
        assertNull(ResourceLocaleUtils.getAdditionalLocales(null))
    }

    /**
     * The extras come back in the order the extra value lists them. They used to go through a
     * TreeSet sorted by Locale.toString(), so "fr/de/es" reached the engine as de, es, fr.
     */
    @Test
    fun extrasKeepTheOrderTheExtraValueListsThem() {
        assertEquals(
            listOf(Locale("fr"), Locale("de"), Locale("es")),
            ResourceLocaleUtils.getAdditionalLocales(subtype("fr/de/es"))!!.toList(),
        )
        assertEquals(
            listOf(Locale("fr", "CA"), Locale("de")),
            ResourceLocaleUtils.getAdditionalLocales(subtype("fr_CA/de"))!!.toList(),
        )
    }

    @Test
    fun aRepeatedExtraIsListedOnce_whereItFirstAppears() {
        assertEquals(
            listOf(Locale("es"), Locale("de")),
            ResourceLocaleUtils.getAdditionalLocales(subtype("es/de/es"))!!.toList(),
        )
    }

    /** What the personal dictionary, the language packs and the spell checker are handed. */
    @Test
    fun keyboardLocales_areThePrimaryThenTheExtrasInOrder_eachOnce() {
        val extras = ResourceLocaleUtils.getAdditionalLocales(subtype("fr/de"))
        assertEquals(listOf(Locale.US, Locale("fr"), Locale("de")), ResourceLocaleUtils.getKeyboardLocales(Locale.US, extras))
        assertEquals(listOf(Locale.US), ResourceLocaleUtils.getKeyboardLocales(Locale.US, null))
        assertEquals(
            listOf(Locale("de"), Locale("fr")),
            ResourceLocaleUtils.getKeyboardLocales(Locale("de"), linkedSetOf(Locale("de"), Locale("fr"))),
        )
    }
}

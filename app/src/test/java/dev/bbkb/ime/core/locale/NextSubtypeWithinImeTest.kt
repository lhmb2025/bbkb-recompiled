package dev.bbkb.ime.core.locale

import android.view.inputmethod.InputMethodSubtype
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Where a "switch language" press lands when it must stay within this IME.
 *
 * Owner report 2026-09-26: with one enabled keyboard ("Match phone languages" on), the
 * multifunction key handed the whole IME over to AOSP LatinIME, and BBKB's suggestion strip
 * "disappeared". The rotation now never leaves this IME; these pin what it does instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class NextSubtypeWithinImeTest {

    private fun subtype(locale: String) = InputMethodSubtype.InputMethodSubtypeBuilder()
        .setSubtypeLocale(locale)
        .setSubtypeMode("keyboard")
        .setSubtypeExtraValue("EmojiCapable")
        .build()

    private val english = subtype("en_US")
    private val french = subtype("fr")
    private val korean = subtype("ko")

    @Test
    fun rotatesToTheNextEnabledSubtypeAndWrapsAround() {
        val enabled = listOf(english, french, korean)
        assertEquals(french, RichInputMethodManager.nextSubtypeWithinIme(english, enabled))
        assertEquals(korean, RichInputMethodManager.nextSubtypeWithinIme(french, enabled))
        assertEquals(english, RichInputMethodManager.nextSubtypeWithinIme(korean, enabled))
    }

    @Test
    fun aSingleEnabledSubtypeHasNowhereToGo() {
        // The reported case. Null means "do nothing" - never "switch to another IME".
        assertNull(RichInputMethodManager.nextSubtypeWithinIme(english, listOf(english)))
    }

    @Test
    fun aCurrentSubtypeMissingFromTheListLandsOnTheFirstEnabledOne() {
        // The enabled list changed under the running keyboard (the Language screen edited it).
        assertEquals(english, RichInputMethodManager.nextSubtypeWithinIme(korean, listOf(english, french)))
        assertEquals(english, RichInputMethodManager.nextSubtypeWithinIme(null, listOf(english, french)))
        assertEquals(english, RichInputMethodManager.nextSubtypeWithinIme(korean, listOf(english)))
    }

    /**
     * Android 15 does not decline a rotation from a subtype it no longer lists: it falls back to
     * the most recently used item, which can be another input method. Switching from a removed
     * Indonesian keyboard handed the KEY2 to AOSP LatinIME (owner report 2026-10-01), so from
     * that state the framework is never asked.
     */
    @Test
    fun aRemovedOrUnknownCurrentSubtypeIsRotatedHereAndNotByTheFramework() {
        assertTrue(RichInputMethodManager.mustRotateWithinIme(korean, listOf(english, french)))
        assertTrue(RichInputMethodManager.mustRotateWithinIme(null, listOf(english, french)))
        assertFalse(RichInputMethodManager.mustRotateWithinIme(english, listOf(english, french)))
        // Nothing enabled (not registered yet): leave it to the normal path, which does nothing.
        assertFalse(RichInputMethodManager.mustRotateWithinIme(english, emptyList()))
        assertFalse(RichInputMethodManager.mustRotateWithinIme(english, null))
    }

    @Test
    fun anEmptyListIsNull() {
        assertNull(RichInputMethodManager.nextSubtypeWithinIme(english, emptyList()))
        assertNull(RichInputMethodManager.nextSubtypeWithinIme(english, null))
    }
}

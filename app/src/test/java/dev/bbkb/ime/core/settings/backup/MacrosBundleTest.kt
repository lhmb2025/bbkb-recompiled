package dev.bbkb.ime.core.settings.backup

import dev.bbkb.ime.personaldictionary.macro.CustomMacro
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The macros document: the round trip and the shapes it refuses. Robolectric for Android's `org.json`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MacrosBundleTest {

    @Test
    fun roundTripKeepsEveryFieldInTagOrder() {
        val macros = listOf(
            CustomMacro("sig", "Signature", "— Brian\nSent from my KEY2", 2L),
            CustomMacro("p", "Phone", "+1 555 0100", 1L),
        )
        val parsed = MacrosBundle.parse(MacrosBundle.serialize(macros)).getOrThrow()
        assertEquals(listOf("p", "sig"), parsed.map { it.tag })
        assertEquals("— Brian\nSent from my KEY2", parsed.first { it.tag == "sig" }.value)
        assertEquals(1L, parsed.first { it.tag == "p" }.createdAt)
    }

    @Test
    fun anEmptySetRoundTrips() {
        assertEquals(emptyList<CustomMacro>(), MacrosBundle.parse(MacrosBundle.serialize(emptyList())).getOrThrow())
    }

    @Test
    fun badDocumentsAreRefused() {
        fun message(json: String) = MacrosBundle.parse(json).exceptionOrNull()!!.message!!
        assertTrue(message("""{"format":"bbkb-words","version":1,"macros":[]}""").contains("format"))
        assertTrue(message("""{"format":"bbkb-macros","version":2,"macros":[]}""").contains("version"))
        assertTrue(message("""{"format":"bbkb-macros","version":1}""").contains("macros"))
        assertTrue(message("""{"format":"bbkb-macros","version":1,"macros":[{"tag":"p","name":"A","value":"x"},{"tag":"p","name":"B","value":"y"}]}""").contains("two entries"))
        assertTrue(message("""{"format":"bbkb-macros","version":1,"macros":[{"tag":"%p","name":"A","value":"x"}]}""").contains("tag"))
        assertTrue(message("""{"format":"bbkb-macros","version":1,"macros":[{"tag":"p","name":"","value":"x"}]}""").contains("name"))
        assertTrue(message("""{"format":"bbkb-macros","version":1,"macros":[{"tag":"p","name":"A","value":"a\u0007b"}]}""").contains("control"))
    }
}

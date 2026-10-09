package dev.bbkb.ime.core.keyevent

import android.content.Context
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.device.config.model.AltMappingsTable
import dev.bbkb.ime.core.device.config.parser.AltMappingsParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Titans' Alt tables, through the resolver that reads them (tier 1, above the firmware key
 * map). Every Titan ROM gives Alt+Space a private-use code point (U+EE01 / U+EF01) that would
 * land in the editor as a box; each table turns it into a plain space.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class TitanAltTablesTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AltMappingsParser.clearMappingsCache()
    }

    @After
    fun tearDown() {
        AltMappingsParser.clearMappingsCache()
    }

    private fun table(name: String): AltMappingsTable {
        val t = AltMappingsParser.getCachedMappings(context, name)
        assertNotNull("$name did not load", t)
        return t!!
    }

    private fun resolverFor(name: String) =
        AuxCharacterResolver.Builder().withAltMappingsTable(table(name)).build()

    /** Alt+[keys] read in order, as one string. */
    private fun row(resolver: AuxCharacterResolver, keys: String): String =
        keys.map { ch ->
            val keyCode = KeyEvent.keyCodeFromString("KEYCODE_$ch")
            resolver.resolve(keyCode).text()
        }.joinToString("")

    @Test
    fun everyTitanTable_mapsAltSpaceToAPlainSpace() {
        for (name in listOf("device_alt_mappings_titan2", "device_alt_mappings_titan2_elite",
                "device_alt_mappings_titan", "device_alt_mappings_titan_pocket")) {
            val r = resolverFor(name).resolve(KeyEvent.KEYCODE_SPACE)
            assertEquals("$name: Alt+Space", " ", r.text())
            assertEquals(' '.code, r.codePoint)
        }
    }

    @Test
    fun titan2() {
        val r = resolverFor("device_alt_mappings_titan2")
        assertEquals("0123()-_/:", row(r, "QWERTYUIOP"))
        assertEquals("@456*#+\"'", row(r, "ASDFGHJKL"))
        assertEquals("!789.,?", row(r, "ZXCVBNM"))
    }

    @Test
    fun titan2Elite() {
        val r = resolverFor("device_alt_mappings_titan2_elite")
        assertEquals("0123()_-+@", row(r, "QWERTYUIOP"))
        assertEquals("*456/:#'\"", row(r, "ASDFGHJKL"))
        assertEquals("789?!,.", row(r, "ZXCVBNM"))
    }

    @Test
    fun titan2019() {
        val r = resolverFor("device_alt_mappings_titan")
        assertEquals(":/_-()1230", row(r, "QWERTYUIOP"))
        assertEquals("@'\"+*#456", row(r, "ASDFGHJKL"))
        assertEquals("!?,.789", row(r, "ZXCVBNM"))
    }

    @Test
    fun pocketAndSlim_phonePadDigits_restLeftToTheFirmware() {
        val r = resolverFor("device_alt_mappings_titan_pocket")
        assertEquals("0", row(r, "Q"))
        assertEquals("123", row(r, "WER"))
        assertEquals("456", row(r, "SDF"))
        assertEquals("789", row(r, "XCV"))
        for (unknown in "ATYUIOPGHJKLZBNM") {
            assertFalse("Alt+$unknown is the firmware's to answer",
                r.resolve(KeyEvent.keyCodeFromString("KEYCODE_$unknown")).hasCharacter())
        }
    }
}

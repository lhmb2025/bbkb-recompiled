package dev.bbkb.ime.core.keyevent

import android.view.KeyEvent
import dev.bbkb.ime.core.device.config.model.AltMappingsTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the tier chain of [AuxCharacterResolver] — and, above all, pins that there is no tier
 * below the two tables.
 *
 * There used to be one: a hardcoded AZERTY/QWERTZ table keyed off the keypad-layout name, which
 * fired on any device whose config declared no `<layout-alt-overrides>`. The KEY2 (athena) is
 * exactly such a device, and its firmware already ships a correct per-layout KeyCharacterMap
 * (`/vendor/usr/keychars/stmpe{,_azerty,_qwertz}.kcm`), so that table did nothing but *displace*
 * the right answer with a wrong one: its AZERTY half turned Alt+P from `@` into `*`, Alt+V from
 * `?` into `7` and Alt+Y from `)` into `1`, and its QWERTZ half simply restated the QWERTY
 * values. The keycodes it claimed are the ones asserted dead below.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AuxCharacterResolverTest {

    private fun tableOf(vararg pairs: Pair<Int, Char>) = AltMappingsTable("test").apply {
        pairs.forEach { (keyCode, c) -> addMapping(keyCode, c) }
    }

    /** Every keycode the retired hardcoded AZERTY/QWERTZ table used to claim. */
    private val formerlyHardcoded = intArrayOf(
        KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_P, KeyEvent.KEYCODE_V,
        KeyEvent.KEYCODE_Y, KeyEvent.KEYCODE_Z
    )

    @Test
    fun noTables_resolvesNothing_soTheKcmDecides() {
        val resolver = AuxCharacterResolver.Builder().build()
        for (keyCode in formerlyHardcoded) {
            val result = resolver.resolve(keyCode)
            assertFalse(
                "keyCode $keyCode must fall through to the KCM, not to a built-in table",
                result.hasCharacter()
            )
        }
    }

    @Test
    fun keyEventWithNoTables_neverReturnsARetiredHardcodedValue() {
        val resolver = AuxCharacterResolver.Builder().build()
        // The four AZERTY entries that used to displace the firmware's own answer.
        for ((keyCode, retired) in listOf(
            KeyEvent.KEYCODE_A to '#', KeyEvent.KEYCODE_P to '*',
            KeyEvent.KEYCODE_V to '7', KeyEvent.KEYCODE_Y to '1'
        )) {
            val result = resolver.resolve(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            assertNotEquals(
                "keyCode $keyCode resolved to the retired hardcoded AZERTY value",
                "layout_override", result.source
            )
            if (result.hasCharacter()) {
                assertEquals("keyCode $keyCode must come from the KCM", "kcm", result.source)
                assertNotEquals(retired, result.character)
            }
        }
    }

    @Test
    fun layoutOverridesTable_stillResolves_whenAConfigDeclaresOne() {
        val resolver = AuxCharacterResolver.Builder()
            .withLayoutOverridesTable(tableOf(KeyEvent.KEYCODE_Z to ')'))
            .build()
        val result = resolver.resolve(KeyEvent.KEYCODE_Z)
        assertEquals(')', result.character)
        assertEquals("layout_override", result.source)
    }

    @Test
    fun altMappingsTable_outranksLayoutOverrides() {
        val resolver = AuxCharacterResolver.Builder()
            .withAltMappingsTable(tableOf(KeyEvent.KEYCODE_Z to '7'))
            .withLayoutOverridesTable(tableOf(KeyEvent.KEYCODE_Z to ')'))
            .build()
        val result = resolver.resolve(KeyEvent.KEYCODE_Z)
        assertEquals('7', result.character)
        assertEquals("xml", result.source)
    }
}

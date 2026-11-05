package dev.bbkb.ime.core.device.config.resolver

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.device.config.parser.AltMappingsParser
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Android reports the built-in keyboard as "changed" on every language switch. That event used
 * to drop the parsed Alt tables with everything else, so the next physical key on a Minimal
 * Phone or Titan parsed its table again on the main thread. A table is read from an XML
 * resource, which no device event changes: only choosing another device configuration drops it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AltTablesSurviveDeviceEventsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        DeviceInputResolver.resetConfig()
    }

    @Test
    fun aDeviceEventKeepsTheParsedAltTable() {
        val table = AltMappingsParser.getCachedMappings(context, TABLE)
        assertNotNull("the shipped table parses", table)

        DeviceInputResolver.resetAndResolve()

        assertSame("not parsed again", table, AltMappingsParser.getCachedMappings(context, TABLE))
    }

    @Test
    fun choosingAnotherConfigurationStillDropsIt() {
        val table = AltMappingsParser.getCachedMappings(context, TABLE)
        assertNotNull(table)

        DeviceInputResolver.resetConfig()

        assertNotSame(table, AltMappingsParser.getCachedMappings(context, TABLE))
    }

    private companion object {
        /** The Minimal Phone's table. */
        const val TABLE = "device_alt_mappings_aw9523b"
    }
}

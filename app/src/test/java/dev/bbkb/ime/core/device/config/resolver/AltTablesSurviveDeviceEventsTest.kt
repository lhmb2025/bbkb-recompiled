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
 * Parsed Alt tables survive device events (Android reports the built-in keyboard as "changed" on
 * every language switch), so no key reparses one on the main thread. Only choosing another device
 * configuration drops them.
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

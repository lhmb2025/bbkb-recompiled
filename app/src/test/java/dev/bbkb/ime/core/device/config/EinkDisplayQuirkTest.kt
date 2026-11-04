package dev.bbkb.ime.core.device.config

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.R
import dev.bbkb.ime.core.device.config.model.DeviceQuirk
import dev.bbkb.ime.core.device.config.parser.DeviceInputMappingParser
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * D-3: the e-ink flag (DeviceProfile.isEinkDisplay) comes from the `eink-display` quirk, and only
 * the Minimal Phone's config declares it, so the keyboard keeps its press highlights, fades and
 * gesture trail on every other device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class EinkDisplayQuirkTest {

    @Test
    fun onlyTheMinimalPhoneIsEink() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val eink = R.xml::class.java.fields
            .filter { it.name.startsWith("device_config_") }
            .filter { field ->
                DeviceInputMappingParser.parseConfigFromXmlResource(context, field.getInt(null)).mappings
                    .any { it.hasQuirk(DeviceQuirk.EINK_DISPLAY) }
            }
            .map { it.name }
            .toSet()
        assertEquals(setOf("device_config_minimal"), eink)
    }

    @Test
    fun theTagParses() {
        assertEquals(DeviceQuirk.EINK_DISPLAY, DeviceQuirk.of("eink-display"))
    }
}

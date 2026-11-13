package dev.bbkb.ime.core.device.config.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A truncated device-config document (an imported user file, parsed at IME startup) must end the
 * parse: `KXmlParser.next()` returns END_DOCUMENT forever at EOF and element loops exit only on END_TAG.
 * The JUnit timeout does not stop the spinning thread, so a regression shows as a hung build, not a
 * red test; if this suite stops finishing, look at [DeviceInputMappingParser.children] first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class TruncatedConfigTerminatesTest {

    private fun parse(xml: String) =
        DeviceInputMappingParser.parseConfigFromStream(xml.byteInputStream())

    @Test(timeout = 2_000)
    fun documentEndingInsideDeviceMappingsTerminates() {
        // <device-input-config> and <device> never close.
        val config = parse(
            """<device-input-config><device><match><device-name exact="athena"/></match>"""
        )
        assertNotNull("a truncated document must still yield the partial config", config)
    }

    @Test(timeout = 2_000)
    fun documentTruncatedAfterAWellFormedDeviceKeepsThatDevice() {
        // The first <device> closes cleanly; the document then stops mid-element. The callers'
        // contract is that whatever parsed before the malformed tail survives.
        val config = parse(
            """
            <device-input-config>
              <device><match><device-name exact="athena"/></match></device>
              <device><match><device-name exact="q25"
            """.trimIndent()
        )
        assertNotNull(config)
        assertEquals(
            "the device that parsed cleanly before the truncation should survive",
            1, config.mappings.size
        )
    }

    @Test(timeout = 2_000)
    fun documentTruncatedInsideANestedElementTerminates() {
        // Truncation inside a nested child, which is a different `children()` frame.
        assertNotNull(
            parse(
                """
                <device-input-config>
                  <device>
                    <match>
                """.trimIndent()
            )
        )
    }

    @Test(timeout = 2_000)
    fun emptyDocumentTerminates() {
        assertNotNull(parse(""))
    }
}

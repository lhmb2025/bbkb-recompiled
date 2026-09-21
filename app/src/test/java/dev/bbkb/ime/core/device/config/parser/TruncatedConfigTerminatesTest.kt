package dev.bbkb.ime.core.device.config.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A truncated device-config document must terminate the parse, not spin.
 *
 * Found during W3-D: every element loop in [DeviceInputMappingParser] terminates only on its own
 * END_TAG, and Android's `KXmlParser` keeps returning END_DOCUMENT from `next()` at EOF instead of
 * throwing. A document that ends with an element still open therefore looped at 100% CPU forever.
 *
 * That is reachable from a user-supplied file: `CustomDeviceConfigManager.importCustomConfig`
 * accepts any URI and the imported config is parsed on IME startup through
 * `DeviceProfile.initialize`, so a truncated XML file produced a keyboard that never started.
 *
 * The mechanism, probed directly: past the end of a truncated document `next()` returns
 * END_DOCUMENT over and over without ever throwing, and this loop exits only on its own END_TAG.
 *
 * Each case carries a JUnit `timeout`, but **do not rely on it to fail fast**. JUnit fails the
 * timed-out test on a watchdog thread while the spinning thread keeps running, so the Gradle worker
 * never exits and the task hangs anyway. Measured: with the guard, this class finishes in ~4s;
 * with the guard removed, `testDebugUnitTest` was still running at 150s and wrote no result file.
 * So a regression here presents as a hung build, not a red test — if this suite ever stops
 * finishing, look at [DeviceInputMappingParser.children] first.
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

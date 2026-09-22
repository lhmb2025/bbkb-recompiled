package dev.bbkb.ime.core.textinput.controller

import dev.bbkb.ime.core.keyevent.InputEventContext
import dev.bbkb.ime.core.settings.util.SettingsValues
import dev.bbkb.ime.core.textinput.connection.RichInputConnection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [PunctuationController]'s last-space timestamp state — the eligibility
 * window behind double-space-to-period substitution. The controller was extracted from
 * InputLogic and owns this state exclusively.
 *
 * SettingsValues can't be constructed on the JVM (it reads prefs/resources), so a Mockito
 * mock carries the public `doubleSpacePeriodTimeoutMs` field, assigned directly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.blackberry.nuanceshim"])
class PunctuationControllerTest {

    private lateinit var controller: PunctuationController
    private lateinit var settings: SettingsValues

    @Before
    fun setUp() {
        controller = PunctuationController(mock(RichInputConnection::class.java)) { _, _, _ -> }
        settings = mock(SettingsValues::class.java)
        // The timeout is a final field; set it reflectively on the mock instance.
        SettingsValues::class.java.getDeclaredField("doubleSpacePeriodTimeoutMs").apply {
            isAccessible = true
            setInt(settings, 500)
        }
    }

    private fun contextAt(timestamp: Long) =
        InputEventContext(settings, null, timestamp, 0, 0)

    @Test
    fun secondSpaceInsideWindow_isEligible() {
        controller.recordSpaceTimestamp(contextAt(10_000))
        assertTrue(controller.isDoubleSpacePeriodTimeout(contextAt(10_400)))
    }

    @Test
    fun secondSpaceAfterWindow_isNotEligible() {
        controller.recordSpaceTimestamp(contextAt(10_000))
        assertFalse(controller.isDoubleSpacePeriodTimeout(contextAt(10_500)))
        assertFalse(controller.isDoubleSpacePeriodTimeout(contextAt(20_000)))
    }

    @Test
    fun reset_disablesEligibility() {
        controller.recordSpaceTimestamp(contextAt(10_000))
        controller.resetSpaceTimestamp()
        assertFalse(controller.isDoubleSpacePeriodTimeout(contextAt(10_100)))
    }

    @Test
    fun newerSpaceReplacesOlderTimestamp() {
        controller.recordSpaceTimestamp(contextAt(10_000))
        controller.recordSpaceTimestamp(contextAt(20_000))
        assertTrue(controller.isDoubleSpacePeriodTimeout(contextAt(20_400)))
    }
}

package dev.bbkb.ime.core.device.config.parser

import dev.bbkb.ime.core.device.config.model.DeviceInputMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** `<accessibility-helper>`: whether a phone needs the BBKB helper at all. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class DeviceInputMappingParserAccessibilityHelperTest {

    private fun parseSingle(deviceBody: String): DeviceInputMapping {
        val config = DeviceInputMappingParser.parseConfigFromStream(
            """<?xml version="1.0" encoding="utf-8"?>
            <device-input-config><device><match><device-name exact="d"/></match>$deviceBody</device></device-input-config>"""
                .byteInputStream()
        )
        assertEquals("expected exactly one parsed device", 1, config.mappings.size)
        return config.mappings[0]
    }

    @Test
    fun offSwitchesTheHelperOff() {
        assertTrue(parseSingle("<accessibility-helper>off</accessibility-helper>").accessibilityHelperOff)
        assertTrue(parseSingle("<accessibility-helper> OFF </accessibility-helper>").accessibilityHelperOff)
    }

    @Test
    fun availableOrAbsentLeavesItOffered() {
        assertFalse(parseSingle("<accessibility-helper>available</accessibility-helper>").accessibilityHelperOff)
        assertFalse(parseSingle("").accessibilityHelperOff)
    }

    @Test
    fun anUnknownValueIsIgnoredRatherThanSwitchingTheHelperOff() {
        assertFalse(parseSingle("<accessibility-helper>maybe</accessibility-helper>").accessibilityHelperOff)
    }
}

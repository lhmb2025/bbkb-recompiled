package dev.bbkb.ime.core.device.config.resolver

import dev.bbkb.ime.core.device.config.model.DeviceInputMapping
import dev.bbkb.ime.core.device.config.model.ScancodeMapping

/**
 * For tests of the key path that care what role a key has, not which key carries it: gives the
 * phone a profile in which every key has the same role. Undo with
 * [DeviceInputResolver.resetConfig].
 */
object KeyRoleTestSupport {

    /** Every key on the phone gets [mapping]; null means no key has a role. */
    @JvmStatic
    fun everyKeyIs(mapping: ScancodeMapping?) {
        DeviceInputResolver.installPhoneProfileForTest(
            if (mapping == null) null else object : DeviceInputMapping() {
                override fun roleFor(scanCode: Int, keyCode: Int): ScancodeMapping = mapping
            })
    }
}

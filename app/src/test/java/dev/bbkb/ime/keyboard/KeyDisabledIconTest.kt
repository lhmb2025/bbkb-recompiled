package dev.bbkb.ime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins that `Key.LongPressKeyData.create()` stores the active and disabled icon ids in their own
 * fields (`getIcon()` draws `disabledIconId` for an inactive key). This covers `create()` only,
 * not `Key`'s call site, which needs a parsed XML attribute set. Reflective: the class is private.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class KeyDisabledIconTest {

    private val ACTIVE = 11
    private val DISABLED = 22

    private val cls: Class<*> = Class.forName("dev.bbkb.ime.keyboard.Key\$LongPressKeyData")

    private fun create(activeIconId: Int, disabledIconId: Int): Any {
        val m = cls.declaredMethods.first { it.name == "create" }.apply { isAccessible = true }
        val hintPos = m.parameterTypes[1]
        val hidden = hintPos.enumConstants.first { (it as Enum<*>).name == "HIDDEN" }
        // create(spec, hintPosition, longPressCode, activeIconId, disabledIconId, bgIds, insL, insR)
        return m.invoke(null, null, hidden, -21, activeIconId, disabledIconId, IntArray(10), 0, 0)
    }

    private fun field(o: Any, name: String): Int =
        cls.getDeclaredField(name).apply { isAccessible = true }.getInt(o)

    @Test
    fun createStoresActiveAndDisabledIconsInTheirOwnFields() {
        val d = create(ACTIVE, DISABLED)
        assertEquals("activeIconId must hold the ACTIVE icon", ACTIVE, field(d, "activeIconId"))
        assertEquals("disabledIconId must hold the DISABLED icon", DISABLED, field(d, "disabledIconId"))
    }

    @Test
    fun theTwoIconIdsAreNotInterchangeable() {
        // If create() coerced both to one value, a swap would be invisible to the test above.
        val d = create(ACTIVE, DISABLED)
        assertNotEquals(field(d, "activeIconId"), field(d, "disabledIconId"))
    }
}

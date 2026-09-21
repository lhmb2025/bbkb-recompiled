package dev.bbkb.ime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the icon-id wiring in `Key.LongPressKeyData`.
 *
 * `Key`'s constructor parsed `keyIconDisabled` and `keyIconActive` into two locals and then passed
 * them to `create(..., activeIconId, disabledIconId, ...)` in the WRONG ORDER, so `activeIconId`
 * held the disabled icon and `disabledIconId` held the active one. `getIcon()` draws
 * `disabledIconId` when a key is inactive — so a DISABLED key rendered its ACTIVE icon. It looked
 * normal, or brighter than normal, while being untappable.
 *
 * Device symptom that produced this: in a browser URL bar the emoji and voice keys are disabled by
 * design (`isTextOrImMode` / `isMicrophoneAllowed`), but rendered as though enabled, so taps looked
 * ignored rather than refused. Months of "taps don't register" were partly this.
 *
 * COVERAGE LIMIT, stated because it is easy to over-read these two tests: they pin `create()`'s
 * CONTRACT, not the call site. Re-introducing the swap in `Key`'s constructor leaves both tests
 * green — verified by mutation. Building a `Key` needs a parsed XML attribute set, which this
 * suite cannot construct. The actual protection against a repeat is that the constructor's locals
 * are now NAMED `activeIconId`/`disabledIconId` instead of `iM7456d`/`iM7456d2`, so a swapped
 * argument is visible at the call site rather than invisible.
 *
 * `LongPressKeyData` is private, so everything here is reflective.
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
        // Guards the test above: if create() ever coerced both to one value, the swap this test
        // exists to catch would become invisible.
        val d = create(ACTIVE, DISABLED)
        assertNotEquals(field(d, "activeIconId"), field(d, "disabledIconId"))
    }
}

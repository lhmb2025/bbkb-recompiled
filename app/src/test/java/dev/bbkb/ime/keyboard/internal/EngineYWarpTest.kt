package dev.bbkb.ime.keyboard.internal

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the **unit conversion** applied to the Y coordinate that the CKB gesture path hands the
 * native engine — the `scaleEngineY(i2)` in every `NuanceSDKManager.touchStart/touchMove/touchEnd`
 * call in [GestureEventProcessor] (onDownEvent, onMoveEvent, onUpEvent).
 *
 * Why this is worth a test rather than a comment: the Y argument of the touch ABI is the single
 * most confusable value in the whole pipeline. Three different spaces are in play — raw capacitive
 * sensor Y, the KDB's authored Y, and the on-screen view Y — and the mapping between the first two
 * lives HERE, in Java, for the CKB, while for the VKB it lives in the engine
 * (`SetKeyboardSize` stretch). Applying both is the documented "double warp" that turned a
 * known-good replayed trace into a word one row up. The X coordinate is passed through untouched.
 *
 * COVERAGE LIMIT, stated plainly: this pins `warpY`'s ARITHMETIC, not that the three call sites
 * use it. `warpY`/`scaleEngineY` are private, so the test reaches them reflectively; deleting the
 * `scaleEngineY(...)` wrapper from one of the call sites would leave this green. The protection
 * against that is the call sites reading `scaleEngineY(i2)` next to a bare `i` for X.
 *
 * See docs/2026-09_kdb-touch-abi_audit.md §1 (TouchStart/TouchMove/TouchEnd — units).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class EngineYWarpTest {

    private fun warp(y: Int): Int {
        val m = GestureEventProcessor::class.java.getDeclaredMethod("warpY", Int::class.java)
        m.isAccessible = true
        return m.invoke(null, y) as Int
    }

    private fun setSpec(spec: String?) = GestureEventProcessor.setEngineYWarp(spec)

    @After
    fun clearWarp() = setSpec(null)

    @Test
    fun `no spec is the identity map`() {
        setSpec(null)
        for (y in intArrayOf(-50, 0, 1, 123, 450, 524, 10_000)) {
            assertEquals("y=$y must pass through unwarped", y, warp(y))
        }
    }

    @Test
    fun `an empty spec clears a previously set warp`() {
        setSpec("0:0,450:324")
        assertEquals(162, warp(225))
        setSpec("   ")
        assertEquals("a cleared warp is the identity", 225, warp(225))
    }

    @Test
    fun `a two-point spec is a linear rescale between the breakpoints`() {
        // The whole-sensor case: 0..450 sensor maps onto 0..324 authored.
        setSpec("0:0,450:324")
        assertEquals(0, warp(0))
        assertEquals(162, warp(225))
        assertEquals(324, warp(450))
    }

    @Test
    fun `uneven bands map each sensor band onto its own authored row`() {
        // The athena shape: a 90px first row and two 180px rows, onto three even 108px rows.
        setSpec("0:0,90:108,270:216,450:324")
        assertEquals("band 1 midpoint", 54, warp(45))
        assertEquals("band 1 top edge", 108, warp(90))
        assertEquals("band 2 midpoint", 162, warp(180))
        assertEquals("band 2 top edge", 216, warp(270))
        assertEquals("band 3 midpoint", 270, warp(360))
        assertEquals("band 3 top edge", 324, warp(450))
    }

    @Test
    fun `outside the breakpoints the warp TRANSLATES rather than extrapolating`() {
        setSpec("100:0,400:300")
        // Below the first breakpoint: engine[0] + (y - sensor[0]).
        assertEquals(-10, warp(90))
        assertEquals(0, warp(100))
        // Above the last: engine[last] + (y - sensor[last]). Deliberately NOT a scaled
        // extrapolation — a touch below the letter grid must land off the grid, not be
        // squeezed back onto the bottom row.
        assertEquals(300, warp(400))
        assertEquals(350, warp(450))
    }

    @Test
    fun `a malformed spec disables the warp instead of half-applying it`() {
        setSpec("0:0,450:324")
        assertEquals(162, warp(225))
        setSpec("this is not a warp")
        assertEquals("a bad spec must fall back to identity", 225, warp(225))
    }

    @Test
    fun `a non-ascending spec is rejected`() {
        setSpec("0:0,450:324")
        setSpec("400:0,100:300")
        assertEquals("non-ascending breakpoints must fall back to identity", 225, warp(225))
    }

    @Test
    fun `a single-point spec is treated as no warp`() {
        // Fewer than two breakpoints cannot define a segment, so warpY returns y unchanged.
        setSpec("0:50")
        assertEquals(225, warp(225))
    }
}

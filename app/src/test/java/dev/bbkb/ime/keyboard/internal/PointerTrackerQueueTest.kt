package dev.bbkb.ime.keyboard.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterises [PointerTrackerQueue], the FIFO of live pointers shared by `PointerTracker` (VKB)
 * and `GestureEventProcessor` (CKB). Every public operation is pinned, including the duplicate
 * handling (a pointer re-added without an intervening remove) and what an `onPhantomUpEvent`
 * callback observes while a release scan is still running — `PointerTracker.onPhantomUpEvent`
 * reaches the keyboard action listener, which reads `size()` through
 * `getActivePointerTrackerCount()`.
 */
class PointerTrackerQueueTest {

    private val log = mutableListOf<String>()

    private inner class E(
        val name: String,
        var modifier: Boolean = false,
        var onShift: Boolean = false,
        var sliding: Boolean = false,
    ) : PointerTrackerQueue.Element {
        var onPhantom: ((Long) -> Unit)? = null
        override fun onPhantomUpEvent(j: Long) {
            log += "$name.phantom($j)"
            onPhantom?.invoke(j)
        }
        override fun isModifier() = modifier
        override fun isOnShiftKey() = onShift
        override fun dispatchShiftKeyTap() { log += "$name.shiftTap" }
        override fun isInSlidingKeyInput() = sliding
        override fun cancelTrackingForAction() { log += "$name.cancel" }
        override fun toString() = name
    }

    private fun queueOf(vararg es: E) = PointerTrackerQueue().apply { es.forEach { add(it) } }

    // ---- add / size / remove / toString ------------------------------------------------------

    @Test
    fun emptyQueue() {
        val q = PointerTrackerQueue()
        assertEquals(0, q.size())
        assertEquals("[]", q.toString())
        assertFalse(q.isAnyInSlidingKeyInput())
        assertFalse(q.hasModifierKeyOlderThan(E("x")))
        q.cancelAllPointerTrackers()
        q.dispatchHeldShiftKey()
        q.releaseAllPointers(1)
        assertEquals(emptyList<String>(), log)
    }

    @Test
    fun addKeepsInsertionOrder() {
        val a = E("a"); val b = E("b"); val c = E("c")
        val q = queueOf(a, b, c)
        assertEquals(3, q.size())
        assertEquals("[a b c]", q.toString())
    }

    @Test
    fun removeMiddleKeepsOrder() {
        val a = E("a"); val b = E("b"); val c = E("c")
        val q = queueOf(a, b, c)
        q.remove(b)
        assertEquals(2, q.size())
        assertEquals("[a c]", q.toString())
        q.add(b)
        assertEquals("[a c b]", q.toString())
    }

    @Test
    fun removeAbsentIsNoOp() {
        val a = E("a"); val b = E("b")
        val q = queueOf(a)
        q.remove(b)
        assertEquals("[a]", q.toString())
    }

    @Test
    fun removeDropsEveryDuplicate() {
        val a = E("a"); val b = E("b")
        val q = queueOf(a, b, a, a)
        q.remove(a)
        assertEquals(1, q.size())
        assertEquals("[b]", q.toString())
    }

    @Test
    fun addAfterShrinkReusesSlotsInOrder() {
        val a = E("a"); val b = E("b"); val c = E("c"); val d = E("d")
        val q = queueOf(a, b, c)
        q.remove(a); q.remove(b)
        q.add(d); q.add(a)
        assertEquals(3, q.size())
        assertEquals("[c d a]", q.toString())
    }

    // ---- releaseAllPointersExcept / releaseAllPointers ----------------------------------------

    @Test
    fun releaseAllPointersPhantomsEveryoneInOrder() {
        val a = E("a"); val b = E("b", modifier = true); val c = E("c")
        val q = queueOf(a, b, c)
        q.releaseAllPointers(7)
        assertEquals(listOf("a.phantom(7)", "b.phantom(7)", "c.phantom(7)"), log)
        assertEquals(0, q.size())
        assertEquals("[]", q.toString())
    }

    @Test
    fun releaseAllPointersExceptKeepsOnlyTheSurvivor() {
        val a = E("a"); val k = E("k"); val b = E("b", modifier = true)
        val q = queueOf(a, k, b)
        q.releaseAllPointersExcept(k, 9)
        assertEquals(listOf("a.phantom(9)", "b.phantom(9)"), log)
        assertEquals("[k]", q.toString())
    }

    @Test
    fun releaseAllPointersExceptKeepsSurvivorDuplicatesInPlace() {
        val a = E("a"); val k = E("k"); val b = E("b")
        val q = queueOf(k, a, k, b)
        q.releaseAllPointersExcept(k, 2)
        assertEquals(listOf("a.phantom(2)", "b.phantom(2)"), log)
        assertEquals("[k k]", q.toString())
    }

    @Test
    fun releaseAllPointersExceptAbsentSurvivorReleasesAll() {
        val a = E("a"); val b = E("b")
        val q = queueOf(a, b)
        q.releaseAllPointersExcept(E("z"), 3)
        assertEquals(listOf("a.phantom(3)", "b.phantom(3)"), log)
        assertEquals(0, q.size())
    }

    @Test
    fun releaseAllPointersExceptDuplicatedVictimIsPhantomedTwice() {
        val a = E("a"); val k = E("k")
        val q = queueOf(a, k, a)
        q.releaseAllPointersExcept(k, 4)
        assertEquals(listOf("a.phantom(4)", "a.phantom(4)"), log)
        assertEquals("[k]", q.toString())
    }

    // ---- releaseAllPointersOlderThan ---------------------------------------------------------

    @Test
    fun releaseOlderThanPhantomsOnlyOlderNonModifiers() {
        val a = E("a"); val m = E("m", modifier = true); val b = E("b")
        val p = E("p"); val c = E("c")
        val q = queueOf(a, m, b, p, c)
        q.releaseAllPointersOlderThan(p, 5)
        assertEquals(listOf("a.phantom(5)", "b.phantom(5)"), log)
        assertEquals("[m p c]", q.toString())
    }

    @Test
    fun releaseOlderThanStopsAtFirstPivotOccurrence() {
        val a = E("a"); val p = E("p"); val b = E("b")
        val q = queueOf(a, p, b, a, p)
        q.releaseAllPointersOlderThan(p, 6)
        assertEquals(listOf("a.phantom(6)"), log)
        assertEquals("[p b a p]", q.toString())
    }

    @Test
    fun releaseOlderThanPivotFirstIsNoOp() {
        val p = E("p"); val a = E("a")
        val q = queueOf(p, a)
        q.releaseAllPointersOlderThan(p, 1)
        assertEquals(emptyList<String>(), log)
        assertEquals("[p a]", q.toString())
    }

    @Test
    fun releaseOlderThanAbsentPivotReleasesAllNonModifiers() {
        val a = E("a"); val m = E("m", modifier = true); val b = E("b")
        val q = queueOf(a, m, b)
        q.releaseAllPointersOlderThan(E("z"), 8)
        assertEquals(listOf("a.phantom(8)", "b.phantom(8)"), log)
        assertEquals("[m]", q.toString())
    }

    @Test
    fun releaseOlderThanReadsModifierStateAtScanTime() {
        val a = E("a"); val m = E("m"); val p = E("p")
        val q = queueOf(a, m, p)
        // a's phantom-up turns m into a modifier before the scan reaches it.
        a.onPhantom = { m.modifier = true }
        q.releaseAllPointersOlderThan(p, 1)
        assertEquals(listOf("a.phantom(1)"), log)
        assertEquals("[m p]", q.toString())
    }

    // ---- predicates / broadcast --------------------------------------------------------------

    @Test
    fun hasModifierKeyOlderThan() {
        val a = E("a"); val m = E("m", modifier = true); val p = E("p"); val m2 = E("m2", modifier = true)
        assertTrue(queueOf(a, m, p).hasModifierKeyOlderThan(p))
        assertFalse(queueOf(a, p, m2).hasModifierKeyOlderThan(p))
        assertFalse(queueOf(p, m).hasModifierKeyOlderThan(p))
        assertTrue(queueOf(a, m).hasModifierKeyOlderThan(E("z")))
        assertFalse(queueOf(a).hasModifierKeyOlderThan(E("z")))
        // The pivot itself being a modifier stops the scan before it is counted.
        val pm = E("pm", modifier = true)
        assertFalse(queueOf(pm, m).hasModifierKeyOlderThan(pm))
    }

    @Test
    fun isAnyInSlidingKeyInput() {
        val a = E("a"); val s = E("s", sliding = true)
        assertFalse(queueOf(a).isAnyInSlidingKeyInput())
        assertTrue(queueOf(a, s).isAnyInSlidingKeyInput())
        val q = queueOf(a, s)
        q.remove(s)
        assertFalse("a removed slot must not be seen", q.isAnyInSlidingKeyInput())
    }

    @Test
    fun cancelAllPointerTrackersVisitsEveryEntryInOrderWithoutRemoving() {
        val a = E("a"); val b = E("b")
        val q = queueOf(a, b, a)
        q.cancelAllPointerTrackers()
        assertEquals(listOf("a.cancel", "b.cancel", "a.cancel"), log)
        assertEquals(3, q.size())
    }

    @Test
    fun cancelAllPointerTrackersIgnoresRemovedSlots() {
        val a = E("a"); val b = E("b")
        val q = queueOf(a, b)
        q.remove(b)
        q.cancelAllPointerTrackers()
        assertEquals(listOf("a.cancel"), log)
    }

    @Test
    fun dispatchHeldShiftKeyTapsOnlyTheFirstShift() {
        val a = E("a"); val s1 = E("s1", onShift = true); val s2 = E("s2", onShift = true)
        val q = queueOf(a, s1, s2)
        q.dispatchHeldShiftKey()
        assertEquals(listOf("s1.shiftTap"), log)
        assertEquals(3, q.size())
    }

    @Test
    fun dispatchHeldShiftKeyNoShiftIsNoOp() {
        queueOf(E("a")).dispatchHeldShiftKey()
        assertEquals(emptyList<String>(), log)
    }

    @Test
    fun dispatchHeldShiftKeyToleratesTheTapRemovingItself() {
        // PointerTracker.dispatchShiftKeyTap -> cancelTrackingAndReleaseKey -> queue.remove(this)
        val q = PointerTrackerQueue()
        val a = E("a"); val s = E("s", onShift = true); val b = E("b", onShift = true)
        val self = object : PointerTrackerQueue.Element by s {
            override fun dispatchShiftKeyTap() { log += "s.shiftTap"; q.remove(this) }
            override fun toString() = "s"
        }
        q.add(a); q.add(self); q.add(b)
        q.dispatchHeldShiftKey()
        assertEquals(listOf("s.shiftTap"), log)
        assertEquals("[a b]", q.toString())
    }

    // ---- what a phantom-up callback observes mid-scan ----------------------------------------

    @Test
    fun phantomCallbackSeesPreReleaseSizeDuringExceptScan() {
        val a = E("a"); val k = E("k"); val b = E("b")
        val q = queueOf(a, k, b)
        val seen = mutableListOf<Int>()
        a.onPhantom = { seen += q.size() }
        b.onPhantom = { seen += q.size() }
        q.releaseAllPointersExcept(k, 1)
        assertEquals(listOf(3, 3), seen)
        assertEquals(1, q.size())
    }

    @Test
    fun phantomCallbackSeesPreReleaseSizeDuringOlderThanScan() {
        val a = E("a"); val b = E("b"); val p = E("p")
        val q = queueOf(a, b, p)
        val seen = mutableListOf<Int>()
        a.onPhantom = { seen += q.size() }
        b.onPhantom = { seen += q.size() }
        q.releaseAllPointersOlderThan(p, 1)
        assertEquals(listOf(3, 3), seen)
        assertEquals(1, q.size())
    }

    @Test
    fun phantomCallbackStillSeesTheVictimAsSliding() {
        // The released pointer is not dropped from the queue until the whole scan finishes.
        val a = E("a", sliding = true); val k = E("k")
        val q = queueOf(a, k)
        var seenSliding: Boolean? = null
        a.onPhantom = { seenSliding = q.isAnyInSlidingKeyInput() }
        q.releaseAllPointersExcept(k, 1)
        assertEquals(true, seenSliding)
    }

    @Test
    fun phantomCallbackCancelAllVisitsTheUncompactedView() {
        val a = E("a"); val k = E("k"); val b = E("b")
        val q = queueOf(a, k, b)
        b.onPhantom = { q.cancelAllPointerTrackers() }
        q.releaseAllPointersExcept(k, 1)
        // At b's phantom-up, slot 0 already holds the compacted survivor k, slot 1 still k.
        assertEquals(listOf("a.phantom(1)", "b.phantom(1)", "k.cancel", "k.cancel", "b.cancel"), log)
        assertEquals("[k]", q.toString())
    }

    @Test
    fun elementAddedByPhantomCallbackIsDiscardedByTheScan() {
        val a = E("a"); val k = E("k"); val n = E("n")
        val q = queueOf(a, k)
        a.onPhantom = { q.add(n) }
        q.releaseAllPointersExcept(k, 1)
        assertEquals("[k]", q.toString())
        q.add(a)
        assertEquals("[k a]", q.toString())
    }
}

package dev.bbkb.ime.core.shared

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The instrumentation contract the startup work is read through.
 *
 * The occurrence counter is the part that earns a test: "`SettingsManager.initialize` runs twice"
 * is the kind of claim a pair of logcat lines cannot settle — two lines can as easily be two IME
 * starts — so the `(#n)` suffix has to be right for the numbers the KEY2 pass reports to mean
 * anything.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class StartupTimingTest {

    @Before
    fun setUp() {
        StartupTiming.resetForTest()
    }

    @Test
    fun eachClosedPhaseAdvancesItsOwnCounter() {
        assertEquals(0, StartupTiming.occurrences("phase.a"))

        StartupTiming.end("phase.a", StartupTiming.begin())
        assertEquals(1, StartupTiming.occurrences("phase.a"))
        assertEquals(0, StartupTiming.occurrences("phase.b"))

        StartupTiming.end("phase.a", StartupTiming.begin())
        StartupTiming.end("phase.b", StartupTiming.begin())
        assertEquals(2, StartupTiming.occurrences("phase.a"))
        assertEquals(1, StartupTiming.occurrences("phase.b"))
    }

    @Test
    fun aPhaseTooFastToLogStillCounts() {
        // endIfOver stays silent under the threshold, but the numbering has to stay truthful or
        // a later "(#3)" would be unreadable.
        StartupTiming.endIfOver("phase.quiet", StartupTiming.begin(), 10_000L)
        StartupTiming.endIfOver("phase.quiet", StartupTiming.begin(), 10_000L)
        assertEquals(2, StartupTiming.occurrences("phase.quiet"))
    }

    @Test
    fun marksCountToo() {
        StartupTiming.anchor("test")
        StartupTiming.mark("phase.marked")
        assertEquals(1, StartupTiming.occurrences("phase.marked"))
    }

    @Test
    fun resetClearsEveryCounter() {
        StartupTiming.end("phase.a", StartupTiming.begin())
        StartupTiming.resetForTest()
        assertEquals(0, StartupTiming.occurrences("phase.a"))
    }
}

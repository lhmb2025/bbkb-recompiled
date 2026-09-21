package dev.bbkb.ime.core.engine

import com.blackberry.nuanceshim.Xt9KdbVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Audit L10 (docs/2026-09_kdb-touch-abi_audit.md §5): `ET9KDB_TouchEnd` cannot report that
 * recognition was unavailable.
 *
 * It must not: the blob's JNI shim does `cmp w0,#0 ; cset w0,eq`, so any non-zero `ET9STATUS`
 * becomes `false` in Java, and the blob's own dispatcher hardcodes 0 — so the owned module
 * deliberately always answers success. The cost is that "the recognizer could not run at all" had
 * no channel out except logcat, which is how a KEY2 build ran in September 2026 with swipe dead
 * for the life of every process (libkb.so loaded before the blob, the weak `ET9KDB_ProcessTrace`
 * reference resolved to NULL, and nothing noticed).
 *
 * The channel is now a pair of monotonic native counters surfaced through [Xt9KdbVariant], and
 * one consumer: [NuanceSDKManager.noteGestureDeposit] warns once per process. The interesting
 * part is not the warning but **when it must stay silent** — the counters read -1 in any build
 * without the owned library, and treating that as a fault would put a scary line in the log of
 * every stock and DIFF build.
 */
class NuanceSdkManagerRecognizerHealthTest {

    @Before
    fun resetProcessFlag() {
        NuanceSDKManager.warnedRecognizerDead = false
    }

    @Test
    fun noOwnedLibraryIsNotAFault() {
        // -1 = "this build has no owned KDB library", the normal state of a stock or DIFF build.
        NuanceSDKManager.noteRecognizerHealth(unavailable = -1, seq = 7)
        assertFalse(
            "a build without the owned library must not report the recognizer as dead",
            NuanceSDKManager.warnedRecognizerDead,
        )
    }

    @Test
    fun aHealthyRecognizerIsSilent() {
        NuanceSDKManager.noteRecognizerHealth(unavailable = 0, seq = 7)
        assertFalse(
            "zero failed deposits is the healthy state and must not warn",
            NuanceSDKManager.warnedRecognizerDead,
        )
    }

    @Test
    fun aDeadRecognizerIsReportedOnceForTheProcess() {
        NuanceSDKManager.noteRecognizerHealth(unavailable = 1, seq = 0)
        assertTrue(
            "a positive unavailability count must be reported",
            NuanceSDKManager.warnedRecognizerDead,
        )
        // The condition is fatal for the whole process, so every later swipe hits it too. One
        // line is the signal; a line per swipe would bury it under the swipes that follow.
        NuanceSDKManager.noteRecognizerHealth(unavailable = 2, seq = 0)
        NuanceSDKManager.noteRecognizerHealth(unavailable = 3, seq = 0)
        assertTrue(NuanceSDKManager.warnedRecognizerDead)
    }

    /**
     * The end-to-end shape on the JVM, where no native library loads: an accepted gesture must
     * neither advance the deposit sequence nor cry wolf. This is the path
     * `GestureEventProcessor.onUpEvent` and `PointerTracker.onUpEvent` take on every real swipe.
     */
    @Test
    fun noteGestureDepositIsInertWithoutTheNativeLibrary() {
        assertEquals(
            "the counter must read -1, not 0, when the owned library is absent",
            -1,
            Xt9KdbVariant.recognizerUnavailable(),
        )
        NuanceSDKManager.noteGestureDeposit()
        assertFalse(
            "a deposit on a build with no owned library must not warn",
            NuanceSDKManager.warnedRecognizerDead,
        )
    }
}

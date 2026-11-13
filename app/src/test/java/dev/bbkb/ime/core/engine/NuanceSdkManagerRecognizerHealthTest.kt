package dev.bbkb.ime.core.engine

import com.blackberry.nuanceshim.Xt9KdbVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `ET9KDB_TouchEnd` always answers success (the blob's JNI shim maps any non-zero `ET9STATUS` to
 * false and its dispatcher hardcodes 0), so recognizer health comes from monotonic native counters
 * via [Xt9KdbVariant]; [NuanceSDKManager.noteGestureDeposit] warns once per process. Counters read
 * -1 in builds without the owned library, and that must stay silent.
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
        // The condition is process-wide, so every later swipe hits it; one line, not one per swipe.
        NuanceSDKManager.noteRecognizerHealth(unavailable = 2, seq = 0)
        NuanceSDKManager.noteRecognizerHealth(unavailable = 3, seq = 0)
        assertTrue(NuanceSDKManager.warnedRecognizerDead)
    }

    /**
     * On the JVM (no native library) an accepted gesture neither advances the deposit sequence nor
     * warns. This is the path `GestureEventProcessor.onUpEvent` and `PointerTracker.onUpEvent` take.
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

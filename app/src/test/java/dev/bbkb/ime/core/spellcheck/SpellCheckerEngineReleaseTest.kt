package dev.bbkb.ime.core.spellcheck

import android.content.Intent
import android.os.SystemClock
import com.blackberry.nuanceshim.NuanceSDK
import dev.bbkb.ime.core.contacts.ContactsLearningManager
import dev.bbkb.ime.core.engine.NuanceSDKManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The spell checker's dictionary is released on unbind or after
 * [AndroidSpellCheckerService.ENGINE_IDLE_RELEASE_MS] idle, never mid-request, and each release
 * bumps the engine generation cached verdicts key on. The engine is never disposed. Built without
 * onCreate, so there is no idle handler and releaseEngineIfIdle drives the release.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.blackberry.nuanceshim"])
class SpellCheckerEngineReleaseTest {

    private lateinit var sdkStatic: MockedStatic<NuanceSDKManager>
    private lateinit var service: AndroidSpellCheckerService
    private val idle = AndroidSpellCheckerService.ENGINE_IDLE_RELEASE_MS

    @Before
    fun setUp() {
        sdkStatic = mockStatic(NuanceSDKManager::class.java)
        sdkStatic.`when`<NuanceSDK> { NuanceSDKManager.peekSecondary() }.thenReturn(mock(NuanceSDK::class.java))
        service = AndroidSpellCheckerService()
        AndroidSpellCheckerService::class.java.getDeclaredField("mContactsManager")
            .apply { isAccessible = true }.set(service, mock(ContactsLearningManager::class.java))
    }

    @After
    fun tearDown() {
        sdkStatic.close()
    }

    private fun aRequestEnds(): Long {
        service.beginRequest()
        service.endRequest()
        return SystemClock.uptimeMillis()
    }

    @Test
    fun theEngineStaysWhileRequestsAreRecent() {
        val end = aRequestEnds()
        assertEquals("asks again when the rest of the wait is up", 1L, service.releaseEngineIfIdle(end + idle - 1))
        assertEquals(0, service.engineGeneration)
    }

    @Test
    fun theDictionaryIsReleasedAfterTheIdleTime() {
        val end = aRequestEnds()
        assertEquals(0L, service.releaseEngineIfIdle(end + idle))
        assertEquals("the sessions' cached verdicts go with it", 1, service.engineGeneration)
    }

    @Test
    fun aRunningRequestKeepsTheEngine() {
        aRequestEnds()
        service.beginRequest()
        assertEquals("its end restarts the countdown", 0L, service.releaseEngineIfIdle(Long.MAX_VALUE / 2))
        assertEquals(0, service.engineGeneration)
        service.endRequest()
    }

    @Test
    fun unbindReleasesTheDictionary() {
        service.onUnbind(Intent())
        assertEquals(1, service.engineGeneration)
    }

    @Test
    fun unbindDuringARunningRequestLeavesTheDictionaryToTheIdleRelease() {
        service.beginRequest()
        service.onUnbind(Intent())
        service.endRequest()
        assertEquals(0L, service.releaseEngineIfIdle(SystemClock.uptimeMillis() + idle))
        assertEquals(1, service.engineGeneration)
    }
}

package dev.bbkb.ime.core.textinput

import com.blackberry.nuanceshim.NuanceSDK
import dev.bbkb.ime.core.BlackBerryIME
import dev.bbkb.ime.core.engine.DictionaryLoader
import dev.bbkb.ime.core.engine.EngineThreads
import dev.bbkb.ime.core.engine.NuanceSDKManager
import dev.bbkb.ime.keyboard.auxbar.suggestions.SuggestionStripListener
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.MockedStatic
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Audit P-6: [InputLogic] is built by BlackBerryIME's field initialiser, before onCreate, and its
 * constructor used to call [NuanceSDKManager.getInstance], which loads the native library and runs
 * the engine's init on the main thread. Constructing it must not reach the engine at all; the
 * engine is resolved at its first real use.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.blackberry.nuanceshim"])
class InputLogicLazyEngineTest {

    private lateinit var sdkStatic: MockedStatic<NuanceSDKManager>
    private lateinit var engine: NuanceSDK

    @Before
    fun setUp() {
        engine = mock(NuanceSDK::class.java)
        sdkStatic = mockStatic(NuanceSDKManager::class.java)
        sdkStatic.`when`<NuanceSDK> { NuanceSDKManager.getInstance() }.thenReturn(engine)
        // The tracker's engine calls are posted ops (Wave 3 Phase A) that resolve the engine
        // inside the op; run them inline so the resolution is observable here.
        EngineThreads.setInlineForTests(true)
    }

    @After
    fun tearDown() {
        EngineThreads.setInlineForTests(false)
        sdkStatic.close()
    }

    private fun newInputLogic() = InputLogic(
        mock(BlackBerryIME::class.java),
        mock(SuggestionStripListener::class.java),
        mock(DictionaryLoader::class.java),
    )

    @Test
    fun constructingInputLogicDoesNotCreateTheEngine() {
        newInputLogic()
        sdkStatic.verify({ NuanceSDKManager.getInstance() }, never())
        sdkStatic.verify({ NuanceSDKManager.getSecondary() }, never())
    }

    @Test
    fun theAutoCommitHandlerIsHandedToTheManagerNotTheEngine() {
        val logic = newInputLogic()
        sdkStatic.verify { NuanceSDKManager.setPrimaryAutoCommitHandler(logic) }
        verify(engine, never()).setAutoCommitCallbackHandler(org.mockito.ArgumentMatchers.any())
    }

    @Test
    fun theTrackerResolvesTheEngineAtFirstUseAndKeepsIt() {
        val logic = newInputLogic()
        logic.mComposingTracker.setShiftState(1)
        logic.mComposingTracker.setShiftState(0)
        sdkStatic.verify({ NuanceSDKManager.getInstance() }, times(1))
        verify(engine, times(2)).setShiftState(anyInt())
    }

    @Test
    fun anUnavailableEngineIsAskedForAgainNextTime() {
        sdkStatic.`when`<NuanceSDK> { NuanceSDKManager.getInstance() }.thenReturn(null, engine)
        val logic = newInputLogic()
        logic.mComposingTracker.setShiftState(1)   // engine unavailable: skipped, not remembered
        logic.mComposingTracker.setShiftState(0)
        sdkStatic.verify({ NuanceSDKManager.getInstance() }, times(2))
        verify(engine, times(1)).setShiftState(0)
    }
}

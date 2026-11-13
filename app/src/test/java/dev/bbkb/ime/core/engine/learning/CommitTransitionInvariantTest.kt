package dev.bbkb.ime.core.engine.learning

import dev.bbkb.ime.core.engine.NuanceSDKManager
import dev.bbkb.ime.core.engine.Dictionary
import dev.bbkb.ime.core.suggestion.SuggestedWords
import dev.bbkb.ime.personaldictionary.DictionaryManager
import com.blackberry.nuanceshim.NuanceSDK
import com.blackberry.nuanceshim.WordInfo
import java.util.concurrent.Executor
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedStatic
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A commit must always move the engine out of the just-typed word (`selectionListSelectWord` or
 * `clear()`), even with no active learner, or the next prediction scores against it. Also pins
 * that the engine learner and the personal dictionary are separate stores.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.blackberry.nuanceshim"])
class CommitTransitionInvariantTest {

    private lateinit var sdkStatic: MockedStatic<NuanceSDKManager>
    private lateinit var engine: NuanceSDK
    private lateinit var manager: DynamicLearningManager

    @Before
    fun setUp() {
        // The commit's clear is an engine op: inline on the test thread, where the mock is visible.
        dev.bbkb.ime.core.engine.EngineThreads.setInlineForTests(true)
        engine = mock(NuanceSDK::class.java)
        sdkStatic = mockStatic(NuanceSDKManager::class.java)
        sdkStatic.`when`<NuanceSDK> { NuanceSDKManager.getInstance() }.thenReturn(engine)
        sdkStatic.`when`<NuanceSDK> { NuanceSDKManager.sdkOrWarn(org.mockito.ArgumentMatchers.anyString()) }.thenReturn(engine)

        // The real ctor registers receivers and touches Context; allocate the shell instead.
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe = unsafeClass.getDeclaredField("theUnsafe")
            .apply { isAccessible = true }.get(null)
        manager = unsafeClass.getMethod("allocateInstance", Class::class.java)
            .invoke(theUnsafe, DynamicLearningManager::class.java) as DynamicLearningManager
        DynamicLearningManager::class.java.getDeclaredField("dynamicLearningEnabled")
            .apply { isAccessible = true }.setBoolean(manager, true)
    }

    @After fun tearDown() {
        sdkStatic.close()
        dev.bbkb.ime.core.engine.EngineThreads.setInlineForTests(false)
    }

    private fun suggestion() = SuggestedWords.SuggestedWordInfo(
        "hello", 100, 0, Dictionary.DICTIONARY_USER_TYPED, -1, -1,
        WordInfo().apply { word = "hello"; spell = "hello" },
    )

    @Test
    fun noActiveLearner_stillEndsTheWordInTheEngine() {
        // No learner is registered, so getActiveLearner() returns null; the commit must still clear.
        manager.learn(suggestion())

        verify(engine).clear()
    }

    @Test
    fun nullWordInfo_stillEndsTheWordInTheEngine() {
        // Pinned so the no-learner else cannot be "simplified" into breaking this branch.
        manager.learn(null)

        verify(engine).clear()
    }

    /**
     * The engine learner has no store to flush (the native DLM has no save entry point). A `save()`
     * here would mean flushing the engine learner writes the BASL personal dictionary again.
     */
    @Test
    fun engineLearnerDeclaresNoSaveOfItsOwn() {
        val offenders = BaseLearningModel::class.java.declaredMethods.filter { it.name == "save" }
        assertTrue(
            "BaseLearningModel must not own a save(): the DLM is written natively, so the only " +
                "thing such a method can flush is the unrelated personal-dictionary store. " +
                "Found $offenders",
            offenders.isEmpty(),
        )
    }

    /** The personal dictionary is flushed whether or not an engine learner is active. */
    @Test
    fun personalDictionaryFlushIsNotGatedOnAnActiveEngineLearner() {
        // The Unsafe-allocated shell has no learner at all.
        assertNull(
            "precondition: no learner is active on the shell",
            DynamicLearningManager::class.java.getDeclaredField("personalLearner")
                .apply { isAccessible = true }.get(manager),
        )
        // Run the deferred work inline so the static mock (which is thread-scoped) still applies.
        DynamicLearningManager::class.java.getDeclaredField("backgroundExecutor")
            .apply { isAccessible = true }
            .set(manager, Executor { it.run() })

        val store = mock(DictionaryManager::class.java)
        mockStatic(DictionaryManager::class.java).use { dictStatic ->
            dictStatic.`when`<DictionaryManager> { DictionaryManager.getInstance() }.thenReturn(store)

            manager.savePersonalDictionary()

            verify(store).save()
        }
    }
}

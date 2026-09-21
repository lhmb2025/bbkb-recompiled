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
 * Executable proof for `EB-6` (docs/2026-07_pipeline-audit-findings_reference.md).
 *
 * `CommitController` documents an invariant at :312-318 — a commit must ALWAYS transition the
 * engine out of the just-typed word, via either `selectionListSelectWord` or `clear()`. With no
 * active learner, `learnWithActiveLearner` returned having done neither, leaving the engine still
 * holding the committed word so the next prediction request could score against it.
 *
 * That state is reachable when the first DLM load fails: the retry check latched
 * "initialised" *before* attempting the load, so activation was never retried for the process
 * lifetime. Both halves are fixed; this pins the invariant half.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.blackberry.nuanceshim"])
class CommitTransitionInvariantTest {

    private lateinit var sdkStatic: MockedStatic<NuanceSDKManager>
    private lateinit var engine: NuanceSDK
    private lateinit var manager: DynamicLearningManager

    @Before
    fun setUp() {
        engine = mock(NuanceSDK::class.java)
        sdkStatic = mockStatic(NuanceSDKManager::class.java)
        sdkStatic.`when`<NuanceSDK> { NuanceSDKManager.getInstance() }.thenReturn(engine)

        // The real ctor registers receivers and touches Context; allocate the shell instead.
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe = unsafeClass.getDeclaredField("theUnsafe")
            .apply { isAccessible = true }.get(null)
        manager = unsafeClass.getMethod("allocateInstance", Class::class.java)
            .invoke(theUnsafe, DynamicLearningManager::class.java) as DynamicLearningManager
        DynamicLearningManager::class.java.getDeclaredField("dynamicLearningEnabled")
            .apply { isAccessible = true }.setBoolean(manager, true)
    }

    @After fun tearDown() = sdkStatic.close()

    private fun suggestion() = SuggestedWords.SuggestedWordInfo(
        "hello", 100, 0, Dictionary.DICTIONARY_USER_TYPED, -1, -1,
        WordInfo().apply { word = "hello"; spell = "hello" },
    )

    @Test
    fun noActiveLearner_stillEndsTheWordInTheEngine() {
        // No learner is registered, so getActiveLearner() returns null. The commit must still
        // transition the engine out of the word — previously this did nothing at all.
        manager.learn(suggestion())

        verify(engine).clear()
    }

    @Test
    fun nullWordInfo_stillEndsTheWordInTheEngine() {
        // The pre-existing branch, pinned so the new else cannot be "simplified" into breaking it.
        manager.learn(null)

        verify(engine).clear()
    }

    // ---- §5.5 step 10: the engine learner and the personal dictionary are separate stores ----

    /**
     * The engine learner has no store of its own to flush — everything it learns lives in the
     * native dynamic model, which NuanceSDK exposes no save entry point for. `save()` existed only
     * to call `DictionaryManager.save()`, i.e. flushing the engine learner wrote the BASL personal
     * dictionary. If that method comes back, the cross-store call has come back with it.
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

    /**
     * The flush used to sit behind `getActiveLearner() != null`, so a user's word substitutions and
     * personal words were written only if the engine's DLM happened to have loaded and activated a
     * learner. That precondition has nothing to do with the personal dictionary; with no learner
     * active the store must still be flushed.
     */
    @Test
    fun personalDictionaryFlushIsNotGatedOnAnActiveEngineLearner() {
        // The Unsafe-allocated shell has no learner at all, which is the old early-return case.
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

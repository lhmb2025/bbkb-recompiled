package dev.bbkb.ime.core.engine.learning

import dev.bbkb.ime.core.contacts.ContactsDataProvider.ContactsType
import dev.bbkb.ime.core.engine.Dictionary
import dev.bbkb.ime.core.engine.NuanceSDKManager
import dev.bbkb.ime.core.suggestion.SuggestedWords
import dev.bbkb.ime.personaldictionary.DictionaryManager
import com.blackberry.nuanceshim.NuanceSDK
import com.blackberry.nuanceshim.WordInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.MockedStatic
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

/**
 * CHARACTERISATION of [DynamicLearningManager] through its REAL constructor — Wave 3b package G1.
 *
 * Complements [CommitTransitionInvariantTest] / [LearnStaleSelectionListTest] (which work on
 * Unsafe-allocated shells). This pins the lifecycle those cannot see: when a learner becomes
 * active (only `onManagedModeChanged(false)`), the dynamic-learning switch and its engine flag,
 * `destroy()` ordering, and the contacts bulk-add threshold. Learning writes to the engine's DLM
 * (alphadlm.bin) — WHEN it happens is the contract here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.blackberry.nuanceshim"])
class DynamicLearningManagerTest {

    private lateinit var sdkStatic: MockedStatic<NuanceSDKManager>
    private lateinit var engine: NuanceSDK
    private lateinit var manager: DynamicLearningManager
    private val deferred = ArrayList<Runnable>()

    @Before
    fun setUp() {
        engine = mock(NuanceSDK::class.java)
        sdkStatic = mockStatic(NuanceSDKManager::class.java)
        sdkStatic.`when`<NuanceSDK> { NuanceSDKManager.getInstance() }.thenReturn(engine)
        manager = DynamicLearningManager(RuntimeEnvironment.getApplication())
        manager.backgroundExecutor = Executor { deferred.add(it) }
    }

    @After fun tearDown() = sdkStatic.close()

    private fun freshSuggestion(index: Int = 2) = SuggestedWords.SuggestedWordInfo(
        "hello", 100, 0, Dictionary.DICTIONARY_USER_TYPED, -1, -1,
        WordInfo().apply {
            word = "hello"; spell = "hello"
            selectionListIndex = index
            selectionListGeneration = NuanceSDK.getSelectionListGeneration()
        },
    )

    @Test
    fun constructor_registersItselfAsTheManagedModeCallback() {
        verify(engine).setCallbackHandler(manager)
    }

    @Test
    fun beforeTheDlmReportsUnmanaged_noLearnerIsActive_andCommitsOnlyClear() {
        manager.learn(freshSuggestion())
        verify(engine).clear()
        verify(engine, never()).selectionListSelectWord(anyInt(), anyBoolean(), anyString())
        verify(engine, never()).addWord(anyString())
    }

    @Test
    fun managedModeTrue_doesNotActivate() {
        manager.onManagedModeChanged(true)
        manager.learn(freshSuggestion())
        verify(engine).clear()
        verify(engine, never()).selectionListSelectWord(anyInt(), anyBoolean(), anyString())
    }

    @Test
    fun unmanaged_activatesTheLearner_andCommitsSelectByIndexWithoutClear() {
        manager.onManagedModeChanged(false)
        manager.learn(freshSuggestion(4))
        verify(engine).selectionListSelectWord(4, true, "hello")
        verify(engine, never()).clear()
    }

    @Test
    fun activeLearner_nullNuanceWordInfo_endsTheWordWithoutLearning() {
        // EB-6: with an active learner, a suggestion lacking nuanceWordInfo still clears, learning nothing.
        manager.onManagedModeChanged(false)
        manager.learn(SuggestedWords.SuggestedWordInfo("x", 1, 0, Dictionary.DICTIONARY_USER_TYPED, -1, -1, null))
        verify(engine).clear()
        verify(engine, never()).selectionListSelectWord(anyInt(), anyBoolean(), anyString())
        verify(engine, never()).addWord(anyString())
    }

    @Test
    fun activationIsSticky_managedTrueLaterDoesNotDeactivate() {
        manager.onManagedModeChanged(false)
        manager.onManagedModeChanged(true)
        manager.learn(freshSuggestion())
        verify(engine).selectionListSelectWord(2, true, "hello")
    }

    @Test
    fun learningDisabled_clearsUnlessForced() {
        manager.onManagedModeChanged(false)
        manager.setDynamicLearningEnabled(false)
        verify(engine).setIsExplicitLearning(true)

        manager.learn(freshSuggestion())
        manager.learn(freshSuggestion(), false)
        verify(engine, org.mockito.Mockito.times(2)).clear()
        verify(engine, never()).selectionListSelectWord(anyInt(), anyBoolean(), anyString())

        manager.learn(freshSuggestion(5), true)
        verify(engine).selectionListSelectWord(5, true, "hello")
    }

    @Test
    fun forcedLearn_nullInfo_stillClears() {
        manager.onManagedModeChanged(false)
        manager.learn(null, true)
        verify(engine).clear()
    }

    @Test
    fun enablingLearning_setsExplicitLearningFalse() {
        manager.setDynamicLearningEnabled(true)
        verify(engine).setIsExplicitLearning(false)
    }

    @Test
    fun destroy_unregistersThenQueuesTheFlushThenDeactivates() {
        manager.onManagedModeChanged(false)
        val store = mock(DictionaryManager::class.java)
        mockStatic(DictionaryManager::class.java).use { dict ->
            dict.`when`<DictionaryManager> { DictionaryManager.getInstance() }.thenReturn(store)

            manager.destroy()

            val order = inOrder(engine)
            order.verify(engine).setCallbackHandler(manager)
            order.verify(engine).setCallbackHandler(null)
            // The flush is deferred to the executor, not run inline.
            verifyNoInteractions(store)
            assertEquals(1, deferred.size)
            deferred.single().run()
            verify(store).save()
        }
        // Deactivated: a later commit only clears.
        manager.learn(freshSuggestion())
        verify(engine).clear()
        verify(engine, never()).selectionListSelectWord(anyInt(), anyBoolean(), anyString())
    }

    @Test
    fun contacts_disabled_doNothing() {
        manager.setDynamicLearningEnabled(false)
        manager.addContactsWords(Array(600) { "w$it" }, ContactsType.ALL)
        manager.addContactsWords(arrayOf("a"), ContactsType.ALL)
        verify(engine, never()).addContactsWords(org.mockito.ArgumentMatchers.any())
        assertEquals(0, deferred.size)
    }

    @Test
    fun contacts_upTo500_areAddedInline() {
        val words = Array(500) { "w$it" }
        manager.addContactsWords(words, ContactsType.ALL)
        verify(engine).addContactsWords(words)
        assertEquals(0, deferred.size)
    }

    @Test
    fun contacts_over500_areDeferred() {
        val words = Array(501) { "w$it" }
        manager.addContactsWords(words, ContactsType.WORK)
        verify(engine, never()).addContactsWords(words, true)
        assertEquals(1, deferred.size)
        deferred.single().run()
        verify(engine).addContactsWords(words, true)
    }

    @Test
    fun contacts_typeRouting() {
        val a = arrayOf("a")
        manager.addContactsWordsInternal(a, ContactsType.ALL)
        verify(engine).addContactsWords(a)
        manager.addContactsWordsInternal(a, ContactsType.WORK)
        verify(engine).addContactsWords(a, true)
        manager.addContactsWordsInternal(a, ContactsType.PERSONAL)
        verify(engine).addContactsWords(a, false)
    }
}

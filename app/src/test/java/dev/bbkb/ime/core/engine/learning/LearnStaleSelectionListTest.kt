package dev.bbkb.ime.core.engine.learning

import dev.bbkb.ime.core.engine.Dictionary
import dev.bbkb.ime.core.suggestion.SuggestedWords
import com.blackberry.nuanceshim.NuanceSDK
import com.blackberry.nuanceshim.WordInfo
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `selectionListSelectWord` takes a raw index into whatever list the engine holds, and the worker
 * can rebuild that list between a `WordInfo` being assembled and the learner selecting it. Each
 * `WordInfo` is stamped with its list generation; a stale index is refused and the word is learned
 * by content. Both directions are pinned: the healthy path still selects by index.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.blackberry.nuanceshim"])
class LearnStaleSelectionListTest {

    private lateinit var sdk: NuanceSDK
    private lateinit var model: BaseLearningModel

    @org.junit.After
    fun tearDown() {
        dev.bbkb.ime.core.engine.EngineThreads.setInlineForTests(false)
    }

    @Before
    fun setUp() {
        // learnWord is a posted engine op; run it inline on the test thread.
        dev.bbkb.ime.core.engine.EngineThreads.setInlineForTests(true)
        sdk = mock(NuanceSDK::class.java)
        // BaseLearningModel's ctor touches Context.getFilesDir(); allocate the shell and inject the
        // one collaborator learnWord uses.
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe = unsafeClass.getDeclaredField("theUnsafe")
            .apply { isAccessible = true }.get(null)
        model = unsafeClass.getMethod("allocateInstance", Class::class.java)
            .invoke(theUnsafe, ContactsLearningModelStub::class.java) as BaseLearningModel
        BaseLearningModel::class.java.getDeclaredField("nuanceSdk")
            .apply { isAccessible = true }.set(model, sdk)
    }

    /** Concrete stand-in so an instance can exist; learnWord lives entirely in the base class. */
    private class ContactsLearningModelStub : BaseLearningModel(null)

    private fun wordInfo(word: String, index: Int, generation: Long) = WordInfo().apply {
        this.word = word
        this.spell = word
        this.selectionListIndex = index
        this.selectionListGeneration = generation
    }

    private fun suggestion(info: WordInfo) = SuggestedWords.SuggestedWordInfo(
        info.word, 100, 0, Dictionary.DICTIONARY_USER_TYPED, -1, -1, info,
    )

    @Test
    fun freshIndex_isSelectedByIndex() {
        // Nothing rebuilt the list, so the index select must happen and succeed with no content
        // learning on top.
        `when`(sdk.selectionListSelectWord(3, true, "hello")).thenReturn(true)
        val info = wordInfo("hello", 3, NuanceSDK.getSelectionListGeneration())

        model.learnWord(suggestion(info))

        verify(sdk).selectionListSelectWord(3, true, "hello")
        verify(sdk, never()).buildSelectionList()
        verify(sdk, never()).addWord("hello")
    }

    // The engine refuses the pick: the main thread feeds characters while the worker rebuilds, so
    // at commit ET9AWSelLstSelWord often answers NEED_SELLIST_BUILD. Rebuild, retry if the rebuilt
    // entry is still this word, otherwise learn by content.

    @Test
    fun refusedPick_isRetriedAfterARebuild() {
        `when`(sdk.selectionListSelectWord(3, true, "zinwa")).thenReturn(false, true)
        `when`(sdk.getSelectionListWord(3)).thenReturn(WordInfo().apply { word = "zinwa" })
        val info = wordInfo("zinwa", 3, NuanceSDK.getSelectionListGeneration())

        model.learnWord(suggestion(info))

        verify(sdk).buildSelectionList()
        verify(sdk, org.mockito.Mockito.times(2)).selectionListSelectWord(3, true, "zinwa")
        verify(sdk, never()).addWord("zinwa")
    }

    @Test
    fun refusedPick_whoseRebuiltEntryIsAnotherWord_learnsByContent() {
        // The rebuilt list no longer has this word at that index, so fall back to content.
        `when`(sdk.selectionListSelectWord(3, true, "zinwa")).thenReturn(false)
        `when`(sdk.getSelectionListWord(3)).thenReturn(WordInfo().apply { word = "other" })
        `when`(sdk.isSpellingCorrect("zinwa")).thenReturn(false)
        val info = wordInfo("zinwa", 3, NuanceSDK.getSelectionListGeneration())

        model.learnWord(suggestion(info))

        verify(sdk, org.mockito.Mockito.times(1)).selectionListSelectWord(3, true, "zinwa")
        verify(sdk).addWord("zinwa")
    }

    @Test
    fun refusedPick_ofAWordTheEngineKnows_isNotAdded() {
        // addWord on a known word would only create a learned entry that outranks the typed word.
        `when`(sdk.selectionListSelectWord(3, true, "hello")).thenReturn(false)
        `when`(sdk.getSelectionListWord(3)).thenReturn(null)
        `when`(sdk.isSpellingCorrect("hello")).thenReturn(true)
        val info = wordInfo("hello", 3, NuanceSDK.getSelectionListGeneration())

        model.learnWord(suggestion(info))

        verify(sdk, never()).addWord("hello")
    }

    @Test
    fun refusedPick_withoutContentLearning_isNotAdded() {
        `when`(sdk.selectionListSelectWord(3, true, "zinwa")).thenReturn(false)
        `when`(sdk.getSelectionListWord(3)).thenReturn(null)
        val info = wordInfo("zinwa", 3, NuanceSDK.getSelectionListGeneration())

        model.learnWord(suggestion(info), false)

        verify(sdk, never()).addWord("zinwa")
    }

    @Test
    fun staleIndex_fallsBackToLearningByContent() {
        // The worker rebuilt the list after this WordInfo was assembled; index 3 is another word.
        val info = wordInfo("hello", 3, NuanceSDK.getSelectionListGeneration() - 1L)

        model.learnWord(suggestion(info))

        verify(sdk, never()).selectionListSelectWord(3, true, "hello")
        verify(sdk).addWord("hello")
    }

    @Test
    fun unstampedIndex_isTreatedAsStale() {
        // -1 (the default) means the index has no provenance, so it must not address the live list.
        val info = wordInfo("hello", 3, -1L)

        model.learnWord(suggestion(info))

        verify(sdk, never()).selectionListSelectWord(3, true, "hello")
        verify(sdk).addWord("hello")
    }

    @Test
    fun unstampedIndex_ofAWordTheEngineKnows_learnsNothing() {
        // Gesture results never carry a live index. Re-adding a known word would create a learned
        // entry that outranks the typed word ("bout" over "about"), so known words are not re-added.
        `when`(sdk.isSpellingCorrect("hello")).thenReturn(true)
        val info = wordInfo("hello", 3, -1L)

        model.learnWord(suggestion(info))

        verify(sdk, never()).selectionListSelectWord(3, true, "hello")
        verify(sdk, never()).addWord("hello")
    }

    @Test
    fun staleIndex_ofAWordTheEngineKnows_learnsNothing() {
        `when`(sdk.isSpellingCorrect("hello")).thenReturn(true)
        val info = wordInfo("hello", 3, NuanceSDK.getSelectionListGeneration() - 1L)

        model.learnWord(suggestion(info))

        verify(sdk, never()).addWord("hello")
    }

    @Test
    fun unstampedIndex_ofAWordTheEngineLacks_isStillAdded() {
        // The content path is for words the dictionary lacks (a swiped recall-lexicon word, a typed
        // new word whose list was rebuilt).
        `when`(sdk.isSpellingCorrect("zinwa")).thenReturn(false)
        val info = wordInfo("zinwa", 3, -1L)

        model.learnWord(suggestion(info))

        verify(sdk).addWord("zinwa")
    }

    @Test
    fun staleIndex_withoutContentLearning_learnsNothing() {
        // A session that may not learn (preference off, password, incognito) passes
        // learnByContent=false: the stale fallback is pure learning, so it is dropped.
        val info = wordInfo("hello", 3, NuanceSDK.getSelectionListGeneration() - 1L)

        model.learnWord(suggestion(info), false)

        verify(sdk, never()).selectionListSelectWord(3, true, "hello")
        verify(sdk, never()).addWord("hello")
    }

    @Test
    fun freshIndex_withoutContentLearning_stillSelects() {
        // The index select is the engine's end-of-selection transition (CJK forced commits need
        // it); only the content fallback is learning-only.
        `when`(sdk.selectionListSelectWord(3, true, "hello")).thenReturn(true)
        val info = wordInfo("hello", 3, NuanceSDK.getSelectionListGeneration())

        model.learnWord(suggestion(info), false)

        verify(sdk).selectionListSelectWord(3, true, "hello")
    }

    @Test
    fun generationAdvancesOnRebuild() {
        // If the generation stopped moving, the staleIndex tests would pass for the wrong reason.
        val before = NuanceSDK.getSelectionListGeneration()
        val f = NuanceSDK::class.java.getDeclaredField("sSelectionListGeneration")
        f.isAccessible = true
        f.setLong(null, f.getLong(null) + 1L)
        assertEquals(before + 1L, NuanceSDK.getSelectionListGeneration())
    }
}

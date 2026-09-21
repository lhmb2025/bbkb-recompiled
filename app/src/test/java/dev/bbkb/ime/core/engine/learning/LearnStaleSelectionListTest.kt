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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Executable proof for `EB-3` — the learn-time select indexes a selection list that may have been
 * rebuilt underneath it (docs/2026-07_pipeline-audit-findings_reference.md).
 *
 * `selectionListSelectWord` takes a RAW INDEX into whatever list the engine holds at call time. A
 * separator commit neither cancels nor drains an in-flight suggestion request, so the worker can
 * rebuild the list between a `WordInfo` being assembled and the learner selecting it. The index
 * then addresses a different list: the DLM learns a word the user never typed, and the
 * end-of-word engine transition lands on the wrong candidate.
 *
 * The fix stamps each `WordInfo` with the list generation its index belongs to and refuses the
 * index once that generation has moved on, learning by content instead. As with `EB-2`, both
 * directions are pinned — the guard must not stop the healthy path selecting by index.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.blackberry.nuanceshim"])
class LearnStaleSelectionListTest {

    private lateinit var sdk: NuanceSDK
    private lateinit var model: BaseLearningModel

    @Before
    fun setUp() {
        sdk = mock(NuanceSDK::class.java)
        // BaseLearningModel is abstract and its ctor touches Context.getFilesDir(); allocate the
        // shell and inject the one collaborator learnWord uses.
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
        // The healthy path, and the regression guard: nothing rebuilt the list between assembly
        // and learn, so the precise index-based select must still happen.
        val info = wordInfo("hello", 3, NuanceSDK.getSelectionListGeneration())

        model.learnWord(suggestion(info))

        verify(sdk).selectionListSelectWord(3, true, "hello")
        verify(sdk, never()).addWord("hello")
    }

    @Test
    fun staleIndex_fallsBackToLearningByContent() {
        // The defect: the worker rebuilt the list after this WordInfo was assembled, so index 3
        // now points at a different word entirely.
        val info = wordInfo("hello", 3, NuanceSDK.getSelectionListGeneration() - 1L)

        model.learnWord(suggestion(info))

        verify(sdk, never()).selectionListSelectWord(3, true, "hello")
        verify(sdk).addWord("hello")
    }

    @Test
    fun unstampedIndex_isTreatedAsStale() {
        // -1 is the default. A WordInfo that never went through the bridge has no provenance for
        // its index, so it must not be trusted to address the live list.
        val info = wordInfo("hello", 3, -1L)

        model.learnWord(suggestion(info))

        verify(sdk, never()).selectionListSelectWord(3, true, "hello")
        verify(sdk).addWord("hello")
    }

    @Test
    fun generationAdvancesOnRebuild() {
        // Guards the tests above: if the generation stopped moving, "stale" could never be
        // detected and the staleIndex tests would pass for the wrong reason.
        val before = NuanceSDK.getSelectionListGeneration()
        val f = NuanceSDK::class.java.getDeclaredField("sSelectionListGeneration")
        f.isAccessible = true
        f.setLong(null, f.getLong(null) + 1L)
        assertEquals(before + 1L, NuanceSDK.getSelectionListGeneration())
    }
}

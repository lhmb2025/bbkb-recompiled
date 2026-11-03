package dev.bbkb.ime.core.suggestion

import dev.bbkb.ime.core.engine.DictionaryLoader
import dev.bbkb.ime.core.engine.NuanceSDKDictionaryBridge
import dev.bbkb.ime.core.textinput.composing.ComposingTextTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.ArrayList
import java.util.Locale

/**
 * "Suggest learned words" (2026-11-03): off, the words the engine knows only because the keyboard
 * learned them are left out of corrections and predictions. The setting had no effect at all
 * before: the preference was read into SettingsValues and consumed nowhere.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.blackberry.nuanceshim"])
class SuggestionEngineLearnedWordsTest {

    private lateinit var loader: DictionaryLoader
    private lateinit var bridge: NuanceSDKDictionaryBridge
    private lateinit var engine: SuggestionEngine

    private val KIND_TYPED = 0
    private val KIND_COMPLETION = 1
    private val KIND_CORRECTION = 2

    @Before
    fun setUp() {
        loader = mock(DictionaryLoader::class.java)
        bridge = mock(NuanceSDKDictionaryBridge::class.java)
        `when`(loader.isDictionaryReady()).thenReturn(true)
        `when`(loader.getMainDictionary()).thenReturn(bridge)
        `when`(bridge.shouldAutoCorrect(any(), any(), any())).thenReturn(false)
        `when`(bridge.learnedOnlyWords()).thenReturn(setOf("beezone", "Quintel"))
        engine = SuggestionEngine(loader)
        engine.setAutoCorrectMode(0)
        engine.setAutoCorrectThreshold(3)
    }

    private fun tracker(typed: String, predicting: Boolean = false): ComposingTextTracker {
        val t = mock(ComposingTextTracker::class.java)
        `when`(t.getComposingText()).thenReturn(typed)
        `when`(t.isComposing()).thenReturn(!predicting)
        `when`(t.isPredictionMode()).thenReturn(predicting)
        `when`(t.isStartOfSentence()).thenReturn(false)
        `when`(t.isAllCaps()).thenReturn(false)
        `when`(t.shouldCapitalizeFirstLetter()).thenReturn(false)
        `when`(t.hasDigits()).thenReturn(false)
        `when`(t.hasMultipleUpperCase()).thenReturn(false)
        `when`(t.isGestureInput()).thenReturn(false)
        `when`(t.looksLikeURL()).thenReturn(false)
        `when`(t.looksLikeEmail()).thenReturn(false)
        return t
    }

    private fun engineReturns(vararg candidates: Pair<String, Int>) {
        val result = SuggestionResult(Locale.US, 18, false)
        val list = ArrayList<SuggestedWords.SuggestedWordInfo>()
        for ((i, c) in candidates.withIndex()) {
            list.add(SuggestedWords.SuggestedWordInfo(c.first, 100 - i, c.second, null, -1, -1, null))
        }
        result.addSuggestions(list)
        `when`(loader.generateSuggestions(any(), any(), any(), any(), anyInt())).thenReturn(result)
    }

    private fun run(typed: String, predicting: Boolean = false): List<String> {
        var out: SuggestedWords? = null
        engine.getSuggestedWords(
            org.robolectric.RuntimeEnvironment.getApplication(),
            tracker(typed, predicting),
            PrevWordsInfo.EMPTY_PREV_WORDS_INFO,
            null,
            null,
            true,
            0,
        ) { out = it }
        val words = out!!
        return (0 until words.size()).map { words.getWord(it) }
    }

    @Test
    fun on_learnedWordsAreOffered() {
        engineReturns("beez" to KIND_TYPED, "beezone" to KIND_COMPLETION, "bees" to KIND_CORRECTION)

        assertEquals(listOf("beez", "beezone", "bees"), run("beez"))
        verify(bridge, never()).learnedOnlyWords()
    }

    @Test
    fun off_learnedWordsAreLeftOut() {
        engine.setSuggestLearnedWords(false)
        engineReturns("beez" to KIND_TYPED, "beezone" to KIND_COMPLETION, "bees" to KIND_CORRECTION)

        assertEquals(listOf("beez", "bees"), run("beez"))
    }

    @Test
    fun off_aCapitalisedFormOfALearnedWordIsLeftOutToo() {
        engine.setSuggestLearnedWords(false)
        engineReturns("Beez" to KIND_TYPED, "Beezone" to KIND_COMPLETION, "Bees" to KIND_CORRECTION)

        assertEquals(listOf("Beez", "Bees"), run("Beez"))
    }

    @Test
    fun off_theTypedWordStaysEvenWhenItIsLearned() {
        engine.setSuggestLearnedWords(false)
        // The bridge duplicates the typed word at index 1 when the engine's default is the typed
        // word itself; the heuristic relies on that duplicate.
        engineReturns("beezone" to KIND_TYPED, "beezone" to KIND_CORRECTION, "bezoar" to KIND_CORRECTION)

        assertEquals(listOf("beezone", "beezone", "bezoar"), run("beezone"))
    }

    @Test
    fun off_predictionsAreFilteredToo() {
        engine.setSuggestLearnedWords(false)
        engineReturns("Quintel" to KIND_COMPLETION, "quite" to KIND_COMPLETION, "beezone" to KIND_COMPLETION)

        assertEquals(listOf("quite"), run("", predicting = true))
    }

    @Test
    fun off_nothingChangesWhenTheModelCannotBeRead() {
        engine.setSuggestLearnedWords(false)
        `when`(bridge.learnedOnlyWords()).thenReturn(null)
        engineReturns("beez" to KIND_TYPED, "beezone" to KIND_COMPLETION)

        assertEquals(listOf("beez", "beezone"), run("beez"))
    }

    @Test
    fun off_theAutoCorrectionIsChosenFromWhatIsLeft() {
        engine.setSuggestLearnedWords(false)
        // Without the filter "beezone" at index 1 would be the correction candidate.
        engineReturns("beez" to KIND_TYPED, "beezone" to KIND_CORRECTION, "bees" to KIND_CORRECTION)
        var out: SuggestedWords? = null
        engine.getSuggestedWords(
            org.robolectric.RuntimeEnvironment.getApplication(), tracker("beez"),
            PrevWordsInfo.EMPTY_PREV_WORDS_INFO, null, null, true, 0,
        ) { out = it }

        assertEquals("bees", out!!.getWord(1))
        assertFalse(out!!.mTypedWordValid)
    }
}

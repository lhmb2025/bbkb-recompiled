package dev.bbkb.ime.core.suggestion

import com.blackberry.nuanceshim.WordInfo
import dev.bbkb.ime.core.engine.DictionaryLoader
import dev.bbkb.ime.core.engine.NuanceSDKDictionaryBridge
import dev.bbkb.ime.core.textinput.composing.ComposingSnapshot
import dev.bbkb.ime.core.textinput.composing.ComposingTextTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.ArrayList
import java.util.Locale

/**
 * "Suggest learned words" (2026-11-03): off, the candidates the engine took from its learned model
 * are left out of corrections and predictions, except the personal dictionary's words. The source
 * comes with the engine's list (WordInfo.fromLearnedModel), so nothing is read per request.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.blackberry.nuanceshim"])
class SuggestionEngineLearnedWordsTest {

    private lateinit var loader: DictionaryLoader
    private lateinit var bridge: NuanceSDKDictionaryBridge
    private lateinit var engine: SuggestionEngine

    /** Words the engine reports from its learned model: learned, and one added on purpose. */
    private val LEARNED_MODEL = setOf("beezone", "quintel", "frobnicate")

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
        engine = SuggestionEngine(loader)
        engine.setPersonalWordLookupForTest { it == "frobnicate" }
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
            val info = WordInfo().apply { word = c.first; fromLearnedModel = c.first.lowercase() in LEARNED_MODEL }
            list.add(SuggestedWords.SuggestedWordInfo(c.first, 100 - i, c.second, null, -1, -1, info))
        }
        result.addSuggestions(list)
        `when`(loader.generateSuggestions(any(ComposingSnapshot::class.java), any(), any(), any(), anyInt())).thenReturn(result)
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
    fun off_personalDictionaryWordsStay() {
        engine.setSuggestLearnedWords(false)
        engineReturns("frob" to KIND_TYPED, "frobnicate" to KIND_COMPLETION, "Frobnicate" to KIND_COMPLETION)

        assertEquals(listOf("frob", "frobnicate", "Frobnicate"), run("frob"))
    }

    @Test
    fun off_wordsWithoutASourceAreKept() {
        engine.setSuggestLearnedWords(false)
        // Candidates the bridge made itself (no engine word behind them) cannot be from the model.
        val result = SuggestionResult(Locale.US, 18, false)
        result.addSuggestions(arrayListOf(
            SuggestedWords.SuggestedWordInfo("beez", 100, KIND_TYPED, null, -1, -1, null),
            SuggestedWords.SuggestedWordInfo("beezone", 99, KIND_COMPLETION, null, -1, -1, null),
        ))
        `when`(loader.generateSuggestions(any(ComposingSnapshot::class.java), any(), any(), any(), anyInt())).thenReturn(result)

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

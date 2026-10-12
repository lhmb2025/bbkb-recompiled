package dev.bbkb.ime.core.suggestion

import dev.bbkb.ime.core.engine.DictionaryLoader
import dev.bbkb.ime.core.engine.NuanceSDKDictionaryBridge
import dev.bbkb.ime.core.textinput.composing.ComposingTextTracker
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.ArrayList
import java.util.Locale

/**
 * The typed-word guard inside `SuggestionEngine.shouldAutoCorrect` (2026-10-12): a word the
 * dictionary knows is not replaced on space, whatever the engine ranked first.
 *
 * Background: the only protection a valid typed word had was the engine's default-word index —
 * when it is 0 the bridge duplicates the typed word into `sugg[1]` and the heuristic stands
 * down. Once the learned model boosts a neighbour the engine's default moves ("about" → "bout",
 * "this" → "his" on the owner's KEY2), the duplicate is gone, and the kind-2 correction at
 * `sugg[1]` committed at any length. The guard asks the dictionary about the typed word itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.blackberry.nuanceshim"])
class SuggestionEngineTypedWordGuardTest {

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
        engine = SuggestionEngine(loader)
        // "Modest" correction with the completion threshold the settings use: a completion of a
        // four-letter-or-longer word may commit on space.
        engine.setAutoCorrectMode(0)
        engine.setAutoCorrectThreshold(3)
        TypedWordGuard.setFrequencyForTest { mapOf("well" to 58, "we'll" to 54, "wed" to 31, "we'd" to 55)[it] ?: 0 }
    }

    @After
    fun tearDown() = TypedWordGuard.setFrequencyForTest(null)

    private fun tracker(typed: String): ComposingTextTracker {
        val t = mock(ComposingTextTracker::class.java)
        `when`(t.getComposingText()).thenReturn(typed)
        `when`(t.isComposing()).thenReturn(true)
        `when`(t.isPredictionMode()).thenReturn(false)
        `when`(t.isAllCaps()).thenReturn(false)
        `when`(t.shouldCapitalizeFirstLetter()).thenReturn(false)
        `when`(t.hasDigits()).thenReturn(false)
        `when`(t.hasMultipleUpperCase()).thenReturn(false)
        `when`(t.isGestureInput()).thenReturn(false)
        `when`(t.looksLikeURL()).thenReturn(false)
        `when`(t.looksLikeEmail()).thenReturn(false)
        return t
    }

    /** The engine's list as (word, kind) pairs, index 0 being the typed word. */
    private fun engineReturns(vararg candidates: Pair<String, Int>) {
        val result = SuggestionResult(Locale.US, 18, false)
        val list = ArrayList<SuggestedWords.SuggestedWordInfo>()
        for ((i, c) in candidates.withIndex()) {
            list.add(SuggestedWords.SuggestedWordInfo(c.first, 100 - i, c.second, null, -1, -1, null))
        }
        result.addSuggestions(list)
        `when`(loader.generateSuggestions(any(), any(), any(), any(), anyInt())).thenReturn(result)
    }

    private fun dictionaryKnows(word: String) {
        `when`(loader.isWordValid(word, true)).thenReturn(true)
    }

    private fun run(typed: String, autoCorrectEnabled: Boolean = true): SuggestedWords {
        var out: SuggestedWords? = null
        engine.getSuggestedWords(
            org.robolectric.RuntimeEnvironment.getApplication(),
            tracker(typed),
            PrevWordsInfo.EMPTY_PREV_WORDS_INFO,
            null,
            null,
            autoCorrectEnabled,
            0,
        ) { out = it }
        return out!!
    }

    @Test
    fun aDictionaryWord_isNotReplacedByTheEnginesCorrection() {
        // The KEY2 report: the engine ranked "bout" first, no duplicate of the typed word.
        dictionaryKnows("about")
        engineReturns("about" to KIND_TYPED, "bout" to KIND_CORRECTION, "abort" to KIND_CORRECTION)

        val words = run("about")

        assertFalse(words.mWillAutoCorrect)
        assertTrue("the typed word becomes the default candidate", words.mTypedWordValid)
    }

    @Test
    fun aMisspelling_isStillCorrected() {
        engineReturns("teh" to KIND_TYPED, "the" to KIND_CORRECTION)

        assertTrue(run("teh").mWillAutoCorrect)
    }

    @Test
    fun aDictionaryWord_stillTakesItsDiacritics() {
        dictionaryKnows("cafe")
        engineReturns("cafe" to KIND_TYPED, "café" to KIND_CORRECTION)

        assertTrue(run("cafe").mWillAutoCorrect)
    }

    @Test
    fun aDictionaryWord_takesTheCommonerContraction() {
        dictionaryKnows("wed")
        engineReturns("wed" to KIND_TYPED, "we'd" to KIND_CORRECTION)

        assertTrue(run("wed").mWillAutoCorrect)
    }

    @Test
    fun aDictionaryWord_keepsItselfOverARarerContraction() {
        dictionaryKnows("well")
        engineReturns("well" to KIND_TYPED, "we'll" to KIND_CORRECTION)

        assertFalse(run("well").mWillAutoCorrect)
    }

    @Test
    fun aDictionaryWord_isNotCompletedOnSpaceEither() {
        dictionaryKnows("thin")
        engineReturns("thin" to KIND_TYPED, "thing" to KIND_COMPLETION)

        assertFalse(run("thin").mWillAutoCorrect)
    }

    @Test
    fun aPrefixThatIsNotAWord_isStillCompleted() {
        // The control for the test above: the completion gate itself is untouched.
        engineReturns("tomorr" to KIND_TYPED, "tomorrow" to KIND_COMPLETION)

        assertTrue(run("tomorr").mWillAutoCorrect)
    }

    @Test
    fun theEnginesOwnDuplicate_stillProtects() {
        // Default index 0: the bridge's duplicate trips typedEqualsCorrection as before, with no
        // dictionary lookup needed to reach that answer.
        engineReturns("about" to KIND_TYPED, "about" to KIND_TYPED, "bout" to KIND_CORRECTION)

        assertFalse(run("about").mWillAutoCorrect)
    }

    @Test
    fun theDictionaryIsNotAsked_whenAutoCorrectIsOff() {
        engineReturns("about" to KIND_TYPED, "bout" to KIND_CORRECTION)

        run("about", autoCorrectEnabled = false)

        verify(loader, never()).isWordValid(anyString(), anyBoolean())
    }

    @Test
    fun theDictionaryIsAskedBeforeTheListIsBuilt() {
        // The lookup runs before generateSuggestions so the bridge's clear-and-replay undoes
        // anything it did to the engine's buffer.
        dictionaryKnows("about")
        engineReturns("about" to KIND_TYPED, "bout" to KIND_CORRECTION)

        run("about")

        val order = org.mockito.Mockito.inOrder(loader)
        order.verify(loader).isWordValid("about", true)
        order.verify(loader).generateSuggestions(any(), any(), any(), any(), anyInt())
    }
}

package dev.bbkb.ime.core.suggestion

import dev.bbkb.ime.core.engine.DictionaryLoader
import dev.bbkb.ime.core.engine.NuanceSDKDictionaryBridge
import dev.bbkb.ime.core.textinput.composing.ComposingSnapshot
import dev.bbkb.ime.core.textinput.composing.ComposingTextTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
 * The personal-dictionary substitution (macro) lookup runs regardless of the auto-correct setting
 * and ORs into `willAutoCorrect`; the setting guards only the spelling heuristic. The heuristic is
 * computed before the substitution is inserted, so a kind-7 entry never feeds it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.blackberry.nuanceshim"])
class SuggestionEngineMacroLookupTest {

    private lateinit var loader: DictionaryLoader
    private lateinit var bridge: NuanceSDKDictionaryBridge
    private lateinit var engine: SuggestionEngine

    /** Kind 7: a personal-dictionary substitution (macro). */
    private val KIND_SUBSTITUTION = 7

    @Before
    fun setUp() {
        loader = mock(DictionaryLoader::class.java)
        bridge = mock(NuanceSDKDictionaryBridge::class.java)
        `when`(loader.isDictionaryReady()).thenReturn(true)
        `when`(loader.getMainDictionary()).thenReturn(bridge)
        engine = SuggestionEngine(loader)
    }

    /** A composing tracker holding [typed], no caps, not predicting; every field the engine reads is stubbed. */
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

    /** Stub engine output as kind-0 entries, so `willAutoCorrect` can only come from the macro channel. */
    private fun engineReturns(vararg words: String) {
        val result = SuggestionResult(Locale.US, 18, false)
        val list = ArrayList<SuggestedWords.SuggestedWordInfo>()
        for ((i, w) in words.withIndex()) {
            list.add(SuggestedWords.SuggestedWordInfo(w, 100 - i, 0, null, -1, -1, null))
        }
        result.addSuggestions(list)
        `when`(loader.generateSuggestions(any(ComposingSnapshot::class.java), any(), any(), any(), anyInt())).thenReturn(result)
    }

    /** Make the bridge behave like a real macro hit: insert the expansion at index 1, return true. */
    private fun bridgeSubstitutes(expansion: String) {
        `when`(bridge.shouldAutoCorrect(any(), any(), any())).thenAnswer { inv ->
            @Suppress("UNCHECKED_CAST")
            val list = inv.arguments[1] as ArrayList<SuggestedWords.SuggestedWordInfo>
            list.add(
                1,
                SuggestedWords.SuggestedWordInfo(
                    expansion, list.size, KIND_SUBSTITUTION, null, -1, Int.MAX_VALUE, null,
                ),
            )
            true
        }
    }

    private fun run(typed: String, autoCorrectEnabled: Boolean): SuggestedWords {
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
    fun macroIsLookedUpAndOffered_withAutoCorrectOff() {
        // With `autoCorrectEnabled &&` in front, the dictionary was never asked and `bb` produced
        // no kind-7 candidate.
        engineReturns("bb")
        bridgeSubstitutes("BlackBerry")

        val words = run("bb", autoCorrectEnabled = false)

        verify(bridge).shouldAutoCorrect(any(), any(), any())
        assertTrue("macro must still auto-correct with the setting off", words.mWillAutoCorrect)
        val acIndex = SuggestedWords.getMinSuggestionsIndex()
        assertEquals("BlackBerry", words.getWord(acIndex))
        assertEquals(KIND_SUBSTITUTION, words.getWordInfo(acIndex).kind)
    }

    @Test
    fun macroIsLookedUpAndOffered_withAutoCorrectOn() {
        engineReturns("bb")
        bridgeSubstitutes("BlackBerry")

        val words = run("bb", autoCorrectEnabled = true)

        assertTrue(words.mWillAutoCorrect)
        assertEquals("BlackBerry", words.getWord(SuggestedWords.getMinSuggestionsIndex()))
    }

    @Test
    fun noMacroHit_withAutoCorrectOff_doesNotAutoCorrect() {
        // Asking the dictionary is not auto-correcting: no substitution and kind-0 candidates stay put.
        engineReturns("teh", "the")
        `when`(bridge.shouldAutoCorrect(any(), any(), any())).thenReturn(false)

        val words = run("teh", autoCorrectEnabled = false)

        assertFalse(words.mWillAutoCorrect)
    }

    @Test
    fun spellingHeuristicStillObeysTheSetting_whenOff() {
        // Index 1 is a kind-2 CORRECTION; with the setting off the heuristic must still reject it.
        val result = SuggestionResult(Locale.US, 18, false)
        val list = ArrayList<SuggestedWords.SuggestedWordInfo>()
        list.add(SuggestedWords.SuggestedWordInfo("teh", 100, 0, null, -1, -1, null))
        list.add(SuggestedWords.SuggestedWordInfo("the", 99, 2, null, -1, -1, null))
        result.addSuggestions(list)
        `when`(loader.generateSuggestions(any(ComposingSnapshot::class.java), any(), any(), any(), anyInt())).thenReturn(result)
        `when`(bridge.shouldAutoCorrect(any(), any(), any())).thenReturn(false)

        assertFalse(run("teh", autoCorrectEnabled = false).mWillAutoCorrect)
        assertTrue(run("teh", autoCorrectEnabled = true).mWillAutoCorrect)
    }

    @Test
    fun macroLookupRunsAfterTheSpellingHeuristic() {
        // The heuristic runs before the substitution is inserted, otherwise it would judge the kind-7
        // entry at index 1. The list the bridge was handed must not contain the expansion yet.
        engineReturns("bb", "bbc")
        var sizeSeenByBridge = -1
        var wordsSeenByBridge: List<String> = emptyList()
        `when`(bridge.shouldAutoCorrect(any(), any(), any())).thenAnswer { inv ->
            @Suppress("UNCHECKED_CAST")
            val list = inv.arguments[1] as ArrayList<SuggestedWords.SuggestedWordInfo>
            sizeSeenByBridge = list.size
            wordsSeenByBridge = list.map { it.word }
            list.add(
                1,
                SuggestedWords.SuggestedWordInfo("BlackBerry", 2, KIND_SUBSTITUTION, null, -1, Int.MAX_VALUE, null),
            )
            true
        }

        run("bb", autoCorrectEnabled = true)

        assertEquals(2, sizeSeenByBridge)
        assertEquals(listOf("bb", "bbc"), wordsSeenByBridge)
    }

    @Test
    fun skipNuanceCheck_stillSuppressesTheLookupEntirely() {
        // `setSkipNuanceCheck(true)` is the gesture/batch path's own suppression, not the user
        // setting, and must keep working.
        engineReturns("bb")
        bridgeSubstitutes("BlackBerry")
        engine.setSkipNuanceCheck(true)

        val words = run("bb", autoCorrectEnabled = false)

        verify(bridge, never()).shouldAutoCorrect(any(), any(), any())
        assertFalse(words.mWillAutoCorrect)
    }
}

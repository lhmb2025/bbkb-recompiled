package dev.bbkb.ime.core.suggestion

import dev.bbkb.ime.core.engine.DictionaryLoader
import dev.bbkb.ime.core.engine.NuanceSDKDictionaryBridge
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
 * FIX-MACRO / divergence D-4 — the substitution LOOKUP was gated on the auto-correct setting.
 *
 * The macro channel is separate from the spelling heuristic. The original asks the personal
 * dictionary for a substitution unconditionally and ORs the answer into `willAutoCorrect`:
 *
 * ```
 * // sources/dev/bbkb/ime/core/ab.java:108-119 (smali-shaped jadx dump)
 * boolean r9 = r0.f;                 if (r9 != 0) goto L7E;   // "suggestions suppressed" flag
 * ...  boolean r9 = r9.d();          if (r9 == 0) goto L7E;   // dictionary ready
 * ...  boolean r11 = r9 instanceof dev.bbkb.ime.core.r;
 * boolean r7 = r9.a(r7, r10, r1);    r7 = r7 | r8;            // <- no autoCorrectEnabled term
 * ```
 *
 * The `autoCorrectEnabled` parameter (`r24`) appears only at `ab.java:84`, guarding the
 * spelling heuristic. We had added `autoCorrectEnabled &&` in front of the bridge call under an
 * "Issue 1 fix" comment, which compounded D-3: with auto-correct off, the substitution was never
 * even looked up, so no kind-7 candidate reached the commit path at all.
 *
 * Ordering, verified against the original and pinned by
 * [macroLookupRunsAfterTheSpellingHeuristic]: `ab.java:81` computes the heuristic BEFORE
 * `ab.java:118` inserts the substitution, so a kind-7 entry never feeds the sugg[1] heuristic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.blackberry.nuanceshim"])
class SuggestionEngineMacroLookupTest {

    private lateinit var loader: DictionaryLoader
    private lateinit var bridge: NuanceSDKDictionaryBridge
    private lateinit var engine: SuggestionEngine

    /** Kind 7 — a personal-dictionary substitution (macro). */
    private val KIND_SUBSTITUTION = 7

    @Before
    fun setUp() {
        loader = mock(DictionaryLoader::class.java)
        bridge = mock(NuanceSDKDictionaryBridge::class.java)
        `when`(loader.isDictionaryReady()).thenReturn(true)
        `when`(loader.getMainDictionary()).thenReturn(bridge)
        engine = SuggestionEngine(loader)
    }

    /**
     * A composing tracker holding [typed], with no caps and not in prediction mode — the plain
     * "user is typing a word" state. Allocated rather than driven so the test stays about the
     * gate; every field the engine reads is stubbed.
     */
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

    /**
     * Stub `generateSuggestions` to return [words] as kind-0 entries — i.e. the engine produced
     * nothing the spelling heuristic would auto-correct on its own, so `willAutoCorrect` can only
     * become true through the macro channel.
     */
    private fun engineReturns(vararg words: String) {
        val result = SuggestionResult(Locale.US, 18, false)
        val list = ArrayList<SuggestedWords.SuggestedWordInfo>()
        for ((i, w) in words.withIndex()) {
            list.add(SuggestedWords.SuggestedWordInfo(w, 100 - i, 0, null, -1, -1, null))
        }
        result.addSuggestions(list)
        `when`(loader.generateSuggestions(any(), any(), any(), any(), anyInt())).thenReturn(result)
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

    // ── D-4: the lookup must not be gated on the setting ────────────────────────

    @Test
    fun macroIsLookedUpAndOffered_withAutoCorrectOff() {
        // The bug: `autoCorrectEnabled && bridge.shouldAutoCorrect(...)` short-circuited, so the
        // personal dictionary was never asked and `bb` produced no kind-7 candidate.
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
        // The guard against over-correcting the fix: asking the dictionary is not the same as
        // auto-correcting. A word with no substitution and a kind-0 candidate list stays put.
        engineReturns("teh", "the")
        `when`(bridge.shouldAutoCorrect(any(), any(), any())).thenReturn(false)

        val words = run("teh", autoCorrectEnabled = false)

        assertFalse(words.mWillAutoCorrect)
    }

    @Test
    fun spellingHeuristicStillObeysTheSetting_whenOff() {
        // Ordinary correction: index 1 is a kind-2 CORRECTION, which the heuristic would accept.
        // With the setting off it must still be rejected — `autoCorrectEnabled` keeps guarding
        // the heuristic (ab.java:84), only the macro lookup is ungated.
        val result = SuggestionResult(Locale.US, 18, false)
        val list = ArrayList<SuggestedWords.SuggestedWordInfo>()
        list.add(SuggestedWords.SuggestedWordInfo("teh", 100, 0, null, -1, -1, null))
        list.add(SuggestedWords.SuggestedWordInfo("the", 99, 2, null, -1, -1, null))
        result.addSuggestions(list)
        `when`(loader.generateSuggestions(any(), any(), any(), any(), anyInt())).thenReturn(result)
        `when`(bridge.shouldAutoCorrect(any(), any(), any())).thenReturn(false)

        assertFalse(run("teh", autoCorrectEnabled = false).mWillAutoCorrect)
        assertTrue(run("teh", autoCorrectEnabled = true).mWillAutoCorrect)
    }

    @Test
    fun macroLookupRunsAfterTheSpellingHeuristic() {
        // ab.java:81 runs the heuristic; ab.java:118 inserts the substitution. If the order were
        // reversed the kind-7 entry would land at index 1 and the heuristic would judge IT rather
        // than the engine's own candidate. Proven by capturing the list the bridge was handed:
        // it must not already contain the expansion the bridge is about to insert.
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
        // `setSkipNuanceCheck(true)` is the gesture/batch path's own suppression and is NOT the
        // user setting — it must keep working after the D-4 ungating.
        engineReturns("bb")
        bridgeSubstitutes("BlackBerry")
        engine.setSkipNuanceCheck(true)

        val words = run("bb", autoCorrectEnabled = false)

        verify(bridge, never()).shouldAutoCorrect(any(), any(), any())
        assertFalse(words.mWillAutoCorrect)
    }
}

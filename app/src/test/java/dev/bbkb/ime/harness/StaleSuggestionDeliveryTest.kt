package dev.bbkb.ime.harness

import dev.bbkb.ime.core.suggestion.SuggestedWords
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Executable proof for the delivery half of `DW-3`
 * (docs/2026-07_pipeline-audit-findings_reference.md).
 *
 * A suggestion reply dispatched under one input session can land after that session is gone. The
 * tracker half was fixed earlier by gating the tracker writes on `isComposing()`. The CACHE half
 * could not be fixed the same way: `mCurrentSuggestions` doubles as the gesture-commit channel
 * (SuggestionRequestQueue:118-130 — the gesture word arrives through it when the engine returns
 * empty) and a gesture is NOT composing, so gating that write on `isComposing()` would starve
 * gesture commit.
 *
 * So the fix drops the stale reply at DELIVERY instead, keyed on the session generation the
 * request was dispatched under. That leaves the cache write untouched for every live reply,
 * gesture ones included.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.blackberry.nuanceshim"])
class StaleSuggestionDeliveryTest {

    private lateinit var h: PipelineHarness

    @Before fun setUp() { h = PipelineHarness() }
    @After fun tearDown() { h.close() }

    private fun generation() = h.inputLogic.sessionGeneration

    @Test
    fun sessionGenerationAdvancesOnTeardown() {
        // The signal itself. cancelInput() is the teardown path a field switch runs through.
        val before = generation()
        h.inputLogic.cancelInput()
        assertNotEquals(
            "teardown must advance the session generation or nothing downstream can detect it",
            before, generation(),
        )
    }

    @Test
    fun replyStampedWithCurrentSession_isDelivered() {
        // The regression guard. A live reply must arrive: this is the direction that, if broken,
        // silently kills the suggestion strip AND the gesture-commit channel.
        val gen = generation()
        h.uiHandler.postShowSuggestions(SuggestedWords.EMPTY, gen)
        assertTrue(
            "a reply from the current session must be queued for delivery",
            h.uiHandler.hasMessages(PipelineHarness.Msg.SHOW_SUGGESTIONS),
        )
        assertEquals(
            "it must carry the session it was dispatched under",
            gen, h.pendingShowSuggestionsGeneration(),
        )
    }

    @Test
    fun replyStampedWithPriorSession_isRecognisableAsStale() {
        // The defect: dispatched under the previous session, arriving after teardown. The
        // delivery-time check compares arg2 against the live generation and drops it.
        val dispatchedUnder = generation()
        h.inputLogic.cancelInput()          // field switch
        h.uiHandler.postShowSuggestions(SuggestedWords.EMPTY, dispatchedUnder)

        assertNotEquals(
            "the queued reply must be distinguishable from the live session",
            generation(), h.pendingShowSuggestionsGeneration(),
        )
    }

    // ── the drop itself, not just the stamp ──────────────────────────────────────
    //
    // The MSG_SHOW_SUGGESTIONS branch only reaches displaySuggestions/showSuggestionStrip on the
    // IME, both no-ops on the mock, so unlike most handler messages this one can actually be
    // DELIVERED in the harness. That turns "the stamp is right" into "the reply really is dropped".

    private fun drainLooper() = shadowOf(android.os.Looper.getMainLooper()).idle()

    @Test
    fun currentSessionReply_reachesTheStrip() {
        h.uiHandler.postShowSuggestions(SuggestedWords.EMPTY, generation())
        drainLooper()
        verify(h.ime).displaySuggestions(SuggestedWords.EMPTY, false)
    }

    @Test
    fun priorSessionReply_neverReachesTheStrip() {
        val dispatchedUnder = generation()
        h.inputLogic.cancelInput()
        h.uiHandler.postShowSuggestions(SuggestedWords.EMPTY, dispatchedUnder)
        drainLooper()
        verify(h.ime, never()).displaySuggestions(SuggestedWords.EMPTY, false)
        verify(h.ime, never()).showSuggestionStrip(SuggestedWords.EMPTY)
    }

    // ── every producer of the gated message must stamp it ────────────────────────
    //
    // REGRESSION 2026-08-04: the first version of this fix gated MSG_SHOW_SUGGESTIONS on arg2 but
    // stamped only postShowSuggestions. postShowSuggestionStrip — the producer the ASYNC TYPED
    // path uses — still posted arg2=0, so once the session generation had advanced past 0 every
    // typed suggestion was dropped and the strip went blank on device. Unit tests were green
    // because they only exercised the producer that had been changed.
    //
    // These cover BOTH producers. Any future one must be added here too.

    @Test
    fun showSuggestionStripProducer_stampsTheSession() {
        h.uiHandler.postShowSuggestionStrip(SuggestedWords.EMPTY, generation())
        drainLooper()
        verify(h.ime).showSuggestionStrip(SuggestedWords.EMPTY)
    }

    @Test
    fun showSuggestionStripProducer_defaultOverloadIsNotDroppedAfterSessionsAdvance() {
        // The exact regression: advance the generation, then post through the convenience
        // overload. It must stamp the CURRENT session, not leave arg2 at 0.
        h.inputLogic.cancelInput()
        h.inputLogic.cancelInput()
        assertTrue("precondition: generation must have advanced past 0", generation() > 0)

        h.uiHandler.postShowSuggestionStrip(SuggestedWords.EMPTY)
        drainLooper()

        verify(h.ime).showSuggestionStrip(SuggestedWords.EMPTY)
    }

    @Test
    fun showSuggestionsProducer_defaultOverloadIsNotDroppedAfterSessionsAdvance() {
        h.inputLogic.cancelInput()
        h.inputLogic.cancelInput()
        assertTrue(generation() > 0)

        h.uiHandler.postShowSuggestions(SuggestedWords.EMPTY)
        drainLooper()

        verify(h.ime).displaySuggestions(SuggestedWords.EMPTY, false)
    }
}

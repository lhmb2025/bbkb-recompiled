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
 * Pins that a suggestion reply from a finished session is dropped at delivery, keyed on the
 * session generation in arg2. It cannot be gated on `isComposing()`: `mCurrentSuggestions` also
 * carries the gesture commit, and a gesture is not composing.
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
        // cancelInput() is the teardown path a field switch runs through.
        val before = generation()
        h.inputLogic.cancelInput()
        assertNotEquals(
            "teardown must advance the session generation or nothing downstream can detect it",
            before, generation(),
        )
    }

    @Test
    fun replyStampedWithCurrentSession_isDelivered() {
        // A live reply must arrive, or the strip and the gesture-commit channel both go silent.
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
        // Dispatched under the previous session, arriving after teardown: arg2 differs from live.
        val dispatchedUnder = generation()
        h.inputLogic.cancelInput()          // field switch
        h.uiHandler.postShowSuggestions(SuggestedWords.EMPTY, dispatchedUnder)

        assertNotEquals(
            "the queued reply must be distinguishable from the live session",
            generation(), h.pendingShowSuggestionsGeneration(),
        )
    }

    // MSG_SHOW_SUGGESTIONS only reaches no-op mock methods, so unlike most messages it can be
    // delivered here, proving the drop itself rather than just the stamp.

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

    // Every producer of MSG_SHOW_SUGGESTIONS must stamp the session, or once the generation
    // passes 0 its replies are all dropped. Add any new producer here.

    @Test
    fun showSuggestionStripProducer_stampsTheSession() {
        h.uiHandler.postShowSuggestionStrip(SuggestedWords.EMPTY, generation())
        drainLooper()
        verify(h.ime).showSuggestionStrip(SuggestedWords.EMPTY)
    }

    @Test
    fun showSuggestionStripProducer_defaultOverloadIsNotDroppedAfterSessionsAdvance() {
        // The convenience overload must stamp the current session, not leave arg2 at 0.
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

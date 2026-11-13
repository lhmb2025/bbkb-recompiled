package dev.bbkb.ime.core.textinput

import dev.bbkb.ime.core.engine.Dictionary
import dev.bbkb.ime.core.shared.AsyncResultHolder
import dev.bbkb.ime.core.suggestion.SuggestedWords
import dev.bbkb.ime.harness.PipelineHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins that a space right after typing `bb` commits the `BlackBerry` shortcut even when the queued
 * suggestion update is already with the worker: [SuggestionCoordinator.flushPendingSuggestions]
 * waits for the reply in flight, or makes a synchronous request when there is none.
 * With `shouldShowLxxButton` off, a synchronous request shows [SuggestedWords.EMPTY] on the strip
 * listener; that call is the trace of the flush having made one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.blackberry.nuanceshim"])
class SeparatorFlushRaceTest {

    private lateinit var h: PipelineHarness

    @Before
    fun setUp() {
        h = PipelineHarness()
        h.startSession()
    }

    @After
    fun tearDown() {
        h.close()
    }

    private val tracker get() = h.inputLogic.mComposingTracker

    private fun flush() = h.inputLogic.flushPendingSuggestions(h.settings, h.uiHandler)

    private fun words(vararg w: String, willAutoCorrect: Boolean = false, editCount: Int = -1): SuggestedWords {
        val list = ArrayList<SuggestedWords.SuggestedWordInfo>()
        for ((i, word) in w.withIndex()) {
            list.add(SuggestedWords.SuggestedWordInfo(word, 100 - i, 7, Dictionary.DICTIONARY_USER_TYPED, -1, -1, null))
        }
        return SuggestedWords(list, false, willAutoCorrect, 0).apply { mRequestEditCount = editCount }
    }

    /** Pretends [updateSuggestionsAsync] handed a request for the current text to the worker. */
    private fun putRequestInFlight(reply: SuggestedWords?): AsyncResultHolder<SuggestedWords> {
        val holder = AsyncResultHolder<SuggestedWords>()
        if (reply != null) holder.set(reply)
        val coordinator = InputLogic::class.java.getDeclaredField("mSuggestionCoordinator")
            .apply { isAccessible = true }.get(h.inputLogic)
        SuggestionCoordinator::class.java.getDeclaredField("mInFlightResult")
            .apply { isAccessible = true }.set(coordinator, holder)
        SuggestionCoordinator::class.java.getDeclaredField("mInFlightEditCount")
            .apply { isAccessible = true }.setInt(coordinator, tracker.editCount)
        return holder
    }

    @Test
    fun aReplyForThePreviousText_doesNotCountAsCurrent() {
        h.seedComposing("alo")
        val requestedAt = tracker.editCount
        tracker.insertCodePointAtCursor('t'.code)

        h.inputLogic.onSuggestionsReceived(words("alo", "alot", editCount = requestedAt), h.settings, h.uiHandler)

        assertFalse(tracker.hasAutoCorrectionForComposingText())
    }

    @Test
    fun aReplyInFlightForTheCurrentText_isWaitedForAndApplied() {
        h.seedComposing("bb")
        val reply = words("bb", "BlackBerry", willAutoCorrect = true, editCount = tracker.editCount)
        putRequestInFlight(reply)

        flush()

        verify(h.suggestionStripListener).showSuggestionStrip(reply)
        verify(h.suggestionStripListener, never()).showSuggestionStrip(SuggestedWords.EMPTY)
    }

    @Test
    fun aReplyInFlightThatNeverArrives_commitsAsTyped_withoutASecondRequest() {
        h.seedComposing("bb")
        putRequestInFlight(null)

        flush()

        verify(h.suggestionStripListener, never()).showSuggestionStrip(SuggestedWords.EMPTY)
        assertEquals(null, tracker.autoCorrection)
    }

    @Test
    fun nothingInFlightForTheCurrentText_makesASynchronousRequest() {
        h.seedComposing("bb")
        assertFalse(h.uiHandler.hasPendingSuggestionUpdate())
        assertFalse(tracker.hasAutoCorrectionForComposingText())

        flush()

        verify(h.suggestionStripListener).showSuggestionStrip(SuggestedWords.EMPTY)
    }

    @Test
    fun aRequestInFlightForAnOlderText_isNotWaitedFor() {
        h.seedComposing("b")
        putRequestInFlight(words("b", "be", editCount = tracker.editCount))
        tracker.insertCodePointAtCursor('b'.code)

        flush()

        verify(h.suggestionStripListener).showSuggestionStrip(SuggestedWords.EMPTY)
    }

    @Test
    fun aResultAlreadyDeliveredForTheCurrentText_isUsedAsIs() {
        h.seedComposing("bb")
        h.deliverSuggestions(listOf("bb", "BlackBerry"), willAutoCorrect = true)
        assertTrue(tracker.hasAutoCorrectionForComposingText())
        clearInvocations(h.suggestionStripListener)

        flush()

        verify(h.suggestionStripListener, never()).showSuggestionStrip(SuggestedWords.EMPTY)
    }

    @Test
    fun nothingComposing_doesNotRefresh() {
        flush()

        verify(h.suggestionStripListener, never()).showSuggestionStrip(SuggestedWords.EMPTY)
    }
}

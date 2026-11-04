package dev.bbkb.ime.core.textinput

import dev.bbkb.ime.core.settings.util.SettingsManager
import dev.bbkb.ime.core.settings.util.SettingsValues
import dev.bbkb.ime.core.suggestion.PrevWordsInfo
import dev.bbkb.ime.core.suggestion.SuggestionEngine
import dev.bbkb.ime.harness.PipelineHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A suggestion request reads the editor context it was SENT with, captured on the main thread,
 * not whatever RichInputConnection's before-cursor buffer holds when the worker gets to it. The
 * worker used to read that StringBuilder (and KeyboardSwitcher, through the shift state) while the
 * main thread was editing it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.blackberry.nuanceshim"])
class RequestContextSnapshotTest {

    private lateinit var h: PipelineHarness
    private var seenByEngine: PrevWordsInfo? = null
    private var originalManagerValues: SettingsValues? = null

    /** Stops the request once the engine has its inputs; what follows is not under test. */
    private class Stop : RuntimeException()

    @Before
    fun setUp() {
        h = PipelineHarness()
        originalManagerValues = managerValuesField().get(SettingsManager.getInstance()) as SettingsValues?
        `when`(
            h.dictionaryLoader.generateSuggestions(
                anyArg(), anyArg(), anyArg(), anyArg(), anyInt(),
            ),
        ).thenAnswer {
            seenByEngine = it.arguments[1] as PrevWordsInfo
            throw Stop()
        }
    }

    @After
    fun tearDown() {
        setManagerValues(originalManagerValues)
        h.close()
    }

    @Test
    fun theWorkerReadsTheContextTakenWhenTheRequestWasSent() {
        h.startSession("the quick brown ")
        val callback = InputLogic.ContextCarryingCallback(
            h.inputLogic.captureRequestContext(h.settings, 0),
            SuggestionEngine.SuggestionCallback { },
        )
        // The main thread goes on typing before the worker runs the request.
        h.inputLogic.mRichInputConnection.commitText("fox ", 1)

        runRequest(callback)

        assertEquals("the quick brown ", seenByEngine!!.contextBefore)
    }

    @Test
    fun aRequestWithoutASnapshotStillReadsAtRunTime() {
        h.startSession("the quick brown ")
        h.inputLogic.mRichInputConnection.commitText("fox ", 1)

        runRequest(SuggestionEngine.SuggestionCallback { })

        assertEquals("the quick brown fox ", seenByEngine!!.contextBefore)
    }

    @Test
    fun theSnapshotCopiesOnlyTheContextTheEngineTakes() {
        val text = "word ".repeat(100)
        h.startSession(text)

        val snapshot = h.inputLogic.captureRequestContext(h.settings, 0)

        // The engine's context is the last 146 characters, not MAX_CONTEXT_LENGTH (1024).
        assertEquals(text.takeLast(146), snapshot.prevWordsInfo.contextBefore)
    }

    @Test
    fun aRequestSentOnTheMainThreadCarriesItsSnapshot() {
        setManagerValues(h.settings)
        h.startSession("hello ")
        val callback = SuggestionEngine.SuggestionCallback { }

        val sent = h.inputLogic.withRequestContext(callback)

        assertTrue(sent is InputLogic.ContextCarryingCallback)
        sent as InputLogic.ContextCarryingCallback
        assertSame(callback, sent.delegate)
        assertNotNull(sent.context)
        assertEquals("hello ", sent.context.prevWordsInfo.contextBefore)
        // Already carrying one: not wrapped twice.
        assertSame(sent, h.inputLogic.withRequestContext(sent))
    }

    private fun runRequest(callback: SuggestionEngine.SuggestionCallback) {
        try {
            h.inputLogic.requestSuggestionsWithContext(
                RuntimeEnvironment.getApplication(), h.settings, null, 0, 0, callback,
            )
        } catch (expected: Stop) {
        }
        assertNotNull("the request never reached the engine", seenByEngine)
    }

    private fun managerValuesField() =
        SettingsManager::class.java.getDeclaredField("settingsValues").apply { isAccessible = true }

    private fun setManagerValues(values: SettingsValues?) {
        managerValuesField().set(SettingsManager.getInstance(), values)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyArg(): T = ArgumentMatchers.any<T>() as T
}

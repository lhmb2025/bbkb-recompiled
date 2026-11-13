package dev.bbkb.ime.core.textinput

import dev.bbkb.ime.core.engine.Dictionary
import dev.bbkb.ime.core.locale.SubtypeManager
import dev.bbkb.ime.core.suggestion.SuggestedWords
import dev.bbkb.ime.harness.PipelineHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedStatic
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * `InputLogic.onSuggestionsReceived` re-sends the composing text only when the auto-correct
 * indicator's state changes, not on every reply (an extra `setComposingText` IPC per keystroke).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.blackberry.nuanceshim"])
class InputLogicSuggestionResendTest {

    private lateinit var h: PipelineHarness
    private lateinit var subtypeStatic: MockedStatic<SubtypeManager>

    @Before
    fun setUp() {
        h = PipelineHarness()
        subtypeStatic = mockStatic(SubtypeManager::class.java)
        val subtypes = mock(SubtypeManager::class.java)
        `when`(subtypes.currentSubtypeLocale).thenReturn(Locale.US)
        subtypeStatic.`when`<SubtypeManager> { SubtypeManager.getInstance() }.thenReturn(subtypes)
    }

    @After
    fun tearDown() {
        subtypeStatic.close()
        h.close()
    }

    private fun words(willAutoCorrect: Boolean): SuggestedWords {
        val list = ArrayList<SuggestedWords.SuggestedWordInfo>()
        list.add(SuggestedWords.SuggestedWordInfo("hel", 2, 0, Dictionary.DICTIONARY_APPLICATION_DEFINED, -1, Int.MAX_VALUE, null))
        list.add(SuggestedWords.SuggestedWordInfo("help", 1, 1, Dictionary.DICTIONARY_APPLICATION_DEFINED, -1, Int.MAX_VALUE, null))
        return SuggestedWords(list, "help", false, willAutoCorrect, 0)
    }

    /** Puts "hel" in composition at the start of [textAfter], the way the typing path would. */
    private fun composeHelBefore(textAfter: String) {
        h.editor.appSetText(textAfter, 0, 0)
        h.editor.dropPendingSelectionEvents()
        check(h.inputLogic.mRichInputConnection.resetConnection(0, 0, false))
        h.seedComposing("hel")
        h.inputLogic.mRichInputConnection.setComposingText("hel", 1)
        h.editor.callLog.clear()
    }

    private fun composingSends(): Int = h.editor.callLog.count { it.startsWith("setComposingText(") }

    @Test
    fun aReplyThatLeavesTheIndicatorOffDoesNotResendTheComposingText() {
        composeHelBefore("")
        h.inputLogic.onSuggestionsReceived(words(willAutoCorrect = false), h.settings, h.uiHandler)
        assertEquals(0, composingSends())
    }

    @Test
    fun theComposingTextIsResentOnlyWhenTheIndicatorStateChanges() {
        composeHelBefore("")
        h.inputLogic.onSuggestionsReceived(words(willAutoCorrect = true), h.settings, h.uiHandler)
        assertEquals("indicator turned on", 1, composingSends())
        h.inputLogic.onSuggestionsReceived(words(willAutoCorrect = true), h.settings, h.uiHandler)
        assertEquals("indicator unchanged", 1, composingSends())
        h.inputLogic.onSuggestionsReceived(words(willAutoCorrect = false), h.settings, h.uiHandler)
        assertEquals("indicator turned off", 2, composingSends())
    }

    @Test
    fun aWordAfterTheCursorKeepsTheIndicatorOffSoNothingIsResent() {
        composeHelBefore("xyz")
        h.inputLogic.onSuggestionsReceived(words(willAutoCorrect = true), h.settings, h.uiHandler)
        assertEquals(0, composingSends())
    }
}

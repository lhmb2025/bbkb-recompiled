package dev.bbkb.ime.core.textinput

import dev.bbkb.ime.core.locale.SubtypeManager
import dev.bbkb.ime.core.settings.util.SettingsManager
import dev.bbkb.ime.core.settings.util.SettingsValues
import dev.bbkb.ime.harness.PipelineHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
 * P-17: a keypad cursor drag is a run of `setSelection` moves, and each used to re-read 1024 chars
 * on both sides of the cursor from the editor (two synchronous round-trips per step). A move that
 * stays inside the cached window now shifts the cached text across the cursor; only a move that
 * leaves it reloads. Asserted through the fake editor: its text-read counters, its selection, and
 * that the connection's caches hold exactly what a reload would have returned.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, instrumentedPackages = ["com.blackberry.nuanceshim"])
class CursorDragTextCacheTest {

    private lateinit var h: PipelineHarness
    private lateinit var subtypeStatic: MockedStatic<SubtypeManager>
    private lateinit var settingsStatic: MockedStatic<SettingsManager>
    private lateinit var batched: SettingsValues

    @Before
    fun setUp() {
        h = PipelineHarness()
        subtypeStatic = mockStatic(SubtypeManager::class.java)
        val subtypes = mock(SubtypeManager::class.java)
        `when`(subtypes.currentSubtypeLocale).thenReturn(Locale.US)
        subtypeStatic.`when`<SubtypeManager> { SubtypeManager.getInstance() }.thenReturn(subtypes)
        val sm = mock(SettingsManager::class.java)
        `when`(sm.getSettingsValues()).thenReturn(h.settings)
        settingsStatic = mockStatic(SettingsManager::class.java)
        settingsStatic.`when`<SettingsManager> { SettingsManager.getInstance() }.thenReturn(sm)
        // The default: one setSelection per move.
        batched = h.settingsWith("allowBatchedCursorMove" to true)
    }

    @After
    fun tearDown() {
        settingsStatic.close()
        subtypeStatic.close()
        h.close()
    }

    private val ric get() = h.inputLogic.mRichInputConnection

    private fun arm(text: String, cursor: Int) {
        h.startSession(text, cursor)
        h.editor.getTextBeforeCursorCalls = 0
        h.editor.getTextAfterCursorCalls = 0
    }

    private fun textReads() = h.editor.getTextBeforeCursorCalls + h.editor.getTextAfterCursorCalls

    private fun cache(name: String): String =
        ric.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(ric).toString()

    /**
     * The caches are the editor's text on either side of the cursor: at most the 1024 chars a
     * reload reads, and at least the 512-char floor (or all of it, nearer an end of the text).
     */
    private fun assertCachesMatchTheEditor() {
        val text = h.editor.text
        val cursor = h.editor.selStart
        assertEquals(cursor, ric.cursorStart)
        assertEquals(cursor, ric.cursorEnd)
        val before = cache("mTextBeforeCursor")
        val after = cache("mTextAfterCursor")
        assertEquals(text.substring(cursor - before.length, cursor), before)
        assertEquals(text.substring(cursor, cursor + after.length), after)
        assertTrue(before.length in minOf(cursor, 512)..1024)
        assertTrue(after.length in minOf(text.length - cursor, 512)..1024)
    }

    @Test
    fun aDragInsideTheWindow_readsNoText() {
        arm("The quick brown fox jumps over the lazy dog.", 4)
        repeat(10) {
            h.inputLogic.moveCursorRight(batched, 1, false)
            h.deliverSelectionEvent()
        }
        repeat(6) {
            h.inputLogic.moveCursorLeft(batched, 1, false)
            h.deliverSelectionEvent()
        }
        assertEquals(8, h.editor.selStart)
        assertEquals(0, textReads())
        assertCachesMatchTheEditor()
    }

    @Test
    fun multiStepMovesInsideTheWindow_readNoText() {
        arm("The quick brown fox jumps over the lazy dog.", 0)
        h.inputLogic.moveCursorRight(batched, 9, false)
        h.inputLogic.moveCursorRight(batched, 7, false)
        h.inputLogic.moveCursorLeft(batched, 3, false)
        while (h.editor.hasPendingSelectionEvents()) h.deliverSelectionEvent()
        assertEquals(13, h.editor.selStart)
        assertEquals(0, textReads())
        assertCachesMatchTheEditor()
    }

    @Test
    fun aReversalWhoseReportsTrailTheMoves_isNotTakenForAnOutsideMove() {
        // A drag sends moves faster than the editor reports them. On a reversal the trailing
        // report points back the way the drag came; it used to read as an outside move: a reset
        // to that stale position, a reload and an engine reset.
        arm("The quick brown fox", 4)
        h.inputLogic.moveCursorRight(batched, 1, false) // 5
        h.inputLogic.moveCursorRight(batched, 1, false) // 6
        h.inputLogic.moveCursorRight(batched, 1, false) // 7
        h.inputLogic.moveCursorLeft(batched, 1, false)  // 6
        var reports = 0
        while (h.editor.hasPendingSelectionEvents()) {
            assertFalse("report ${reports++} is the editor catching up", h.deliverSelectionEvent()!!)
            assertEquals(6, ric.cursorStart)
        }
        assertEquals(4, reports)
        assertEquals(0, textReads())
        assertCachesMatchTheEditor()
    }

    private val longText = buildString { while (length < 3000) append("word${length % 97} ") }

    @Test
    fun aMoveThatLeavesTheWindow_reloadsOnce() {
        // Mid-text a reload caches 1024 chars each side. Ten-step moves (a fast drag's frame)
        // to the right use up the after side 10 chars at a time.
        arm(longText, 1500)
        repeat(51) { h.inputLogic.moveCursorRight(batched, 10, false) }
        assertEquals("514 still cached after the cursor", 0, textReads())
        assertCachesMatchTheEditor()
        // The next would leave 504, under the floor: one reload, one read each side.
        h.inputLogic.moveCursorRight(batched, 10, false)
        assertEquals(1, h.editor.getTextBeforeCursorCalls)
        assertEquals(1, h.editor.getTextAfterCursorCalls)
        assertEquals(2020, h.editor.selStart)
        assertCachesMatchTheEditor()
        assertEquals("a fresh read: all the text to the end", longText.length - 2020, cache("mTextAfterCursor").length)
    }

    @Test
    fun leftwardMovesShrinkTheWindowDownToTheFloor_thenReload() {
        arm(longText, 2000)
        repeat(51) { h.inputLogic.moveCursorLeft(batched, 10, false) }
        assertEquals(0, textReads())
        assertEquals(1490, h.editor.selStart)
        assertCachesMatchTheEditor()
        h.inputLogic.moveCursorLeft(batched, 10, false)
        assertEquals(1, h.editor.getTextBeforeCursorCalls)
        assertEquals(1, h.editor.getTextAfterCursorCalls)
        assertCachesMatchTheEditor()
        assertEquals(1024, cache("mTextBeforeCursor").length)
    }

    @Test
    fun aMoveThatGoesNowhere_reloads() {
        // A drag pressed against the end of the text: the reload is what notices text an app
        // added there since.
        arm("hello", 5)
        h.inputLogic.moveCursorRight(batched, 1, false)
        assertEquals(1, h.editor.getTextBeforeCursorCalls)
        assertEquals(1, h.editor.getTextAfterCursorCalls)
        assertCachesMatchTheEditor()
    }

    @Test
    fun theStepWiseSetting_alsoShiftsLocally() {
        arm("hello world", 0)
        h.inputLogic.moveCursorRight(h.settings, 5, false)
        assertEquals(5, h.editor.selStart)
        assertEquals(0, textReads())
        assertCachesMatchTheEditor()
    }
}

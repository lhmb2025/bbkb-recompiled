package dev.bbkb.ime.core.textinput.connection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.inputmethodservice.InputMethodService;

import dev.bbkb.ime.core.suggestion.PrevWordsInfo;
import android.view.KeyEvent;
import android.view.inputmethod.InputConnection;

import org.junit.Before;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Unit tests for {@link RichInputConnection}'s text model in the cursor-inside-composing
 * state (docs/archived/2026-06_fable-audits-and-gesture-rebuild/2026-06_composition-pipeline_audit.md F5).
 *
 * <p>The editor is a Mockito-scripted {@link InputConnection} whose before/after text is
 * held in mutable fields, updated between steps exactly as a real editor would after each
 * IME call. The test replays the real mid-word-insert flow from
 * {@code InputLogic.handleCharacterInput}: tap inside the word (resetConnection), insert a
 * character (setComposingText with the full new word), then reposition the cursor inside
 * the composing region.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, instrumentedPackages = {"com.blackberry.nuanceshim"})
public class RichInputConnectionTest {

    private InputMethodService mImeService;
    private InputConnection mIc;
    private RichInputConnection mRic;

    /** What the editor reports before/after the cursor; updated by the test between steps. */
    private String mEditorBefore = "";
    private String mEditorAfter = "";

    @Before
    public void setUp() {
        mImeService = mock(InputMethodService.class);
        mIc = mock(InputConnection.class);
        when(mImeService.getCurrentInputConnection()).thenReturn(mIc);
        when(mIc.setSelection(anyInt(), anyInt())).thenReturn(true);
        when(mIc.getTextBeforeCursor(anyInt(), anyInt())).thenAnswer(inv -> {
            int n = inv.getArgument(0);
            int len = mEditorBefore.length();
            return mEditorBefore.substring(Math.max(0, len - n));
        });
        when(mIc.getTextAfterCursor(anyInt(), anyInt())).thenAnswer(inv -> {
            int n = inv.getArgument(0);
            return mEditorAfter.substring(0, Math.min(n, mEditorAfter.length()));
        });
        mRic = new RichInputConnection(mImeService);
    }

    /**
     * Replays: document "Hello Testin" with composing region "Testin" at [6,12); the user
     * taps between 'e' and 's' (cursor 8), then types 'a' → word becomes "Teastin" and the
     * cursor must sit at 9, inside the region.
     */
    private void runMidWordInsertFlow() {
        // Tap at cursor 8 → onUpdateSelection's commit path calls resetConnection(8, 8, false).
        mEditorBefore = "Hello Te";
        mEditorAfter = "stin";
        assertTrue(mRic.resetConnection(8, 8, false));

        // InputLogic pushes the full new word into the editor: region [6,12) → "Teastin",
        // editor cursor lands after the region (newCursorPosition = 1).
        mRic.setComposingText("Teastin", 1);
        mEditorBefore = "Hello Teastin";
        mEditorAfter = "";

        // InputLogic repositions the cursor inside the region: composingStart 6 + offset 3.
        mEditorBefore = "Hello Tea";
        mEditorAfter = "stin";
        assertTrue(mRic.setSelectionWithinComposing(9, 6));
    }

    @Test
    public void midWordInsertKeepsTextModelConsistent() {
        runMidWordInsertFlow();

        // Committed text + the full composing word, with no double-counted prefix.
        // Before the fix this returned "Hello TeaTeastin".
        assertEquals("Hello Teastin", mRic.getTextBeforeCursor(100, 0).toString());
        assertEquals(9, mRic.getCursorStart());
    }

    @Test
    public void midWordInsertKeepsCodePointBeforeCursorSemantics() {
        runMidWordInsertFlow();

        // Same semantics as the cursor-at-end composing state: the last *committed*
        // character before the composing word. Before the fix this returned 'a' (a
        // character from inside the word).
        assertEquals(' ', mRic.getCodePointBeforeCursor());
    }

    // --- H3: synthesized backspace must delete a whole code point from the caches ---

    @Test
    public void synthesizedBackspaceDeletesWholeSurrogatePairFromComposingCache() {
        mEditorBefore = "";
        mEditorAfter = "";
        assertTrue(mRic.resetConnection(0, 0, false));
        mRic.setComposingText("ab😀", 1); // "ab😀"

        mRic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));

        // Before the fix the cache kept a lone high surrogate ("ab\uD83D").
        assertEquals("ab", mRic.getTextBeforeCursor(100, 0).toString());
    }

    @Test
    public void selectionAtComposingStartTrimsNothing() {
        mEditorBefore = "Hello Te";
        mEditorAfter = "stin";
        assertTrue(mRic.resetConnection(8, 8, false));
        mRic.setComposingText("Testin", 1);

        // Cursor moved to the very start of the region: prefix length 0, no trim.
        mEditorBefore = "Hello ";
        mEditorAfter = "Testin";
        assertTrue(mRic.setSelectionWithinComposing(6, 6));

        assertEquals("Hello Testin", mRic.getTextBeforeCursor(100, 0).toString());
        assertEquals(' ', mRic.getCodePointBeforeCursor());
    }

    // -- audit regressions ---------------------------------------------------

    /**
     * Audit TI-2: {@code getPrevWordsInfo} used to write the current context into
     * {@code PrevWordsInfo.EMPTY_PREV_WORDS_INFO} - a {@code public static final} singleton - and
     * return it. There was therefore exactly one PrevWordsInfo in the process: every
     * "context at commit time" snapshot held by a CommitEventRecord aliased it and was rewritten
     * by the next keystroke.
     */
    @Test
    public void getPrevWordsInfoReturnsAFreshInstanceEachCall() {
        mEditorBefore = "Hello ";
        mEditorAfter = "";
        assertTrue(mRic.resetConnection(6, 6, false));
        PrevWordsInfo first = mRic.getPrevWordsInfo(null, 1);

        // The next keystroke's context.
        mEditorBefore = "Hello world ";
        mEditorAfter = "";
        assertTrue(mRic.resetConnection(12, 12, false));
        PrevWordsInfo second = mRic.getPrevWordsInfo(null, 1);

        assertNotSame(first, second);
        assertNotSame(PrevWordsInfo.EMPTY_PREV_WORDS_INFO, first);
        assertNotSame(PrevWordsInfo.EMPTY_PREV_WORDS_INFO, second);
        // The first snapshot must still describe the context it was taken in.
        assertEquals("Hello ", first.getContextBefore());
        assertEquals("Hello world ", second.getContextBefore());
        // ...and the shared singleton must be untouched.
        assertEquals("", PrevWordsInfo.EMPTY_PREV_WORDS_INFO.getContextBefore());
    }

    /**
     * Audit TI-35: the guard was {@code TextUtils.isEmpty(t) || ' ' != t.charAt(1)}, which only
     * rules out length 0 while indexing position 1. With the cursor one character into the field
     * this threw StringIndexOutOfBoundsException off the backspace path.
     */
    @Test
    public void revertSwapPunctuationWithOneCharacterBeforeCursorDoesNotThrow() {
        mEditorBefore = "a";
        mEditorAfter = "";
        assertTrue(mRic.resetConnection(1, 1, false));

        assertFalse(mRic.revertSwapPunctuation());
    }

    /**
     * Audit TI-22: {@code getTextBeforeCursor} now builds only the requested suffix instead of
     * copying both buffers in full and then deleting the front. Pin the boundaries - a request
     * that lands inside the committed text, exactly on the composing boundary, and inside the
     * composing text.
     */
    @Test
    public void getTextBeforeCursorReturnsTheRequestedSuffixAcrossTheComposingBoundary() {
        mEditorBefore = "Hello ";
        mEditorAfter = "";
        assertTrue(mRic.resetConnection(6, 6, false));
        mRic.setComposingText("wor", 1);

        assertEquals("r", mRic.getTextBeforeCursor(1, 0).toString());
        assertEquals("or", mRic.getTextBeforeCursor(2, 0).toString());
        assertEquals("wor", mRic.getTextBeforeCursor(3, 0).toString());
        assertEquals(" wor", mRic.getTextBeforeCursor(4, 0).toString());
        assertEquals("Hello wor", mRic.getTextBeforeCursor(9, 0).toString());
        assertEquals("Hello wor", mRic.getTextBeforeCursor(100, 0).toString());
    }

    // ── isSelectionUnchanged with the cursor unknown ─────────────────────────

    @Test
    public void isSelectionUnchanged_cursorUnknown_aRealJumpIsAChange() {
        mRic.resetConnection(5, 5, false);
        mRic.invalidateConnection();
        assertEquals(-1, mRic.getCursorStart());
        // Argument order is (old start, new start, old end, new end): [5,5] -> [0,0].
        assertFalse("the app emptying the field must not be read as no change",
                mRic.isSelectionUnchanged(5, 0, 5, 0));
    }

    @Test
    public void isSelectionUnchanged_cursorUnknown_aRedundantReportIsStillNoChange() {
        mRic.resetConnection(5, 5, false);
        mRic.invalidateConnection();
        assertTrue(mRic.isSelectionUnchanged(5, 5, 5, 5));
    }


    // ── the add-to-dictionary highlight and the editor's spell check ────────

    @Test
    public void clearingAFinishedHighlight_asksTheEditorToSpellCheck() {
        mRic.resetConnection(0, 0, false);
        mRic.commitTextWithHighlight("asdfgh", 1, 0x664eb848, 6);
        mRic.clearBackgroundSpans();
        // The highlight was committed as a composing-flagged span, which the editor's own
        // spell-check parser skipped; once finished, the check has to be requested.
        verify(mIc).finishComposingText();
        verify(mIc).performSpellCheck();
    }

    @Test
    public void clearingWithNoHighlight_requestsNothing() {
        mRic.resetConnection(0, 0, false);
        mRic.commitText("hello", 1);
        mRic.clearBackgroundSpans();
        verify(mIc, never()).performSpellCheck();
    }

    // ── the cached text before the cursor stays bounded (audit B-10) ─────────

    /**
     * Commits append to the cached text before the cursor and nothing shortened it, so a long
     * session with no cursor move made every whole-buffer reader slower by the character. Past
     * twice the load size the front is dropped; the tail stays exact, and a request for more
     * than the cache holds goes to the editor, as it does right after a load.
     */
    @Test
    public void aLongRunOfCommitsKeepsTheCachedTextBounded() {
        mEditorBefore = "";
        mEditorAfter = "";
        assertTrue(mRic.resetConnection(0, 0, false));
        StringBuilder typed = new StringBuilder();
        for (int n = 0; n < 700; n++) {
            String word = "word" + n + " ";
            mRic.commitText(word, 1);
            typed.append(word);
        }
        mEditorBefore = typed.toString();
        assertTrue(typed.length() > RichInputConnection.MAX_TEXT_BEFORE_CURSOR);

        // The tail is served from the cache and is exact.
        String tail = typed.substring(typed.length() - 200);
        assertEquals(tail, mRic.getTextBeforeCursor(200, 0).toString());
        verify(mIc, never()).getTextBeforeCursor(200, 0);

        // More than the bound cannot come from the cache any more.
        int wanted = RichInputConnection.MAX_TEXT_BEFORE_CURSOR + 1;
        assertEquals(typed.substring(typed.length() - wanted), mRic.getTextBeforeCursor(wanted, 0).toString());
        verify(mIc).getTextBeforeCursor(wanted, 0);
    }

    @Test
    public void trimmingNeverLeavesHalfAnEmojiAtTheFront() {
        mEditorBefore = "";
        mEditorAfter = "";
        assertTrue(mRic.resetConnection(0, 0, false));
        // One char, then two-char emoji: every odd index is a low surrogate, so a cut that lands
        // on one must move past it.
        mRic.commitText("a", 1);
        String emoji = new String(Character.toChars(0x1F600));
        for (int n = 0; n < RichInputConnection.MAX_TEXT_BEFORE_CURSOR; n++) {
            mRic.commitText(emoji, 1);
        }
        for (int ask = 1000; ask <= 1024; ask++) {
            CharSequence text = mRic.getTextBeforeCursor(ask, 0);
            if (text.length() == ask) {
                continue;   // a request inside the cache may start mid-pair; the cache itself may not
            }
            assertFalse("cache starts with half a pair", Character.isLowSurrogate(text.charAt(0)));
        }
    }

    /** The word check reads the last one or two code points in place; same answers as before. */
    @Test
    public void hasWordBeforeCursorReadsTheEndOfTheCachedText() {
        dev.bbkb.ime.core.settings.util.SpacingAndPunctuation rules =
                mock(dev.bbkb.ime.core.settings.util.SpacingAndPunctuation.class);
        when(rules.isWordSeparator(' ')).thenReturn(true);
        when(rules.isWordSeparator(-1)).thenReturn(false);
        when(rules.isWordConnector('\'')).thenReturn(true);
        mEditorAfter = "";

        mEditorBefore = "";
        mRic.resetConnection(0, 0, false);
        assertFalse(mRic.hasWordBeforeCursor(rules));

        mRic.commitText("hello", 1);
        assertTrue(mRic.hasWordBeforeCursor(rules));

        mRic.commitText(" ", 1);
        assertFalse(mRic.hasWordBeforeCursor(rules));

        mRic.commitText("don'", 1);          // a connector after a letter: still in a word
        assertTrue(mRic.hasWordBeforeCursor(rules));

        mRic.commitText(" '", 1);            // a connector after a space: not in a word
        assertFalse(mRic.hasWordBeforeCursor(rules));

        mRic.commitText(new String(Character.toChars(0x1F600)), 1);   // a two-char code point
        assertTrue(mRic.hasWordBeforeCursor(rules));
    }
}

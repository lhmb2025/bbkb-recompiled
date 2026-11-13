package dev.bbkb.ime.core.textinput.composing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyChar;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mockito.InOrder;

import dev.bbkb.ime.core.keyevent.InputEvent;
import com.blackberry.nuanceshim.NuanceSDK;

import java.util.Locale;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * {@link ComposingTextTracker}'s own string and cursor arithmetic. Robolectric for the
 * SpannableStringBuilder buffer; {@code instrumentedPackages} makes NuanceSDK's static
 * {@code System.loadLibrary} a no-op so the class loads, and the instance is a Mockito mock.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, instrumentedPackages = {"com.blackberry.nuanceshim"})
public class ComposingTextTrackerTest {

    private NuanceSDK mNuance;
    private ComposingTextTracker mTracker;

    @org.junit.After
    public void tearDown() {
        dev.bbkb.ime.core.engine.EngineThreads.setInlineForTests(false);
    }

    @Before
    public void setUp() {
        // The tracker's engine calls are posted ops; run them inline on the test thread.
        dev.bbkb.ime.core.engine.EngineThreads.setInlineForTests(true);
        mNuance = mock(NuanceSDK.class);
        when(mNuance.isChineseStrokeMode()).thenReturn(false);
        // The append path resolves the engine's primary language; without this the mock
        // returns null and TextProcessor throws an NPE. Latin is sufficient for these tests.
        when(mNuance.getPrimaryLanguage()).thenReturn(Locale.ENGLISH);
        mTracker = new ComposingTextTracker(mNuance);
    }

    /** Seeds the composing buffer by appending each code point of {@code word} as a touch event. */
    private void seed(String word) {
        for (int i = 0; i < word.length(); ) {
            int cp = word.codePointAt(i);
            mTracker.processInputEvent(InputEvent.createTouchEvent(cp, 0, 0, false));
            i += Character.charCount(cp);
        }
    }

    @Test
    public void seededWordIsComposingWithCursorAtEnd() {
        seed("Testin");
        assertEquals("Testin", mTracker.getComposingText());
        assertTrue(mTracker.isComposing());
        assertFalse("cursor should sit at the end after typing", mTracker.isCursorMoved());
        assertEquals(6, mTracker.getCodePointCount());
        assertEquals(6, mTracker.getComposingCursorPos());
    }

    @Test
    public void insertInMiddlePreservesTrailingChars() {
        seed("Testin");
        // Move the cursor to between 'e' and 's' (code-point position 2).
        mTracker.moveCursorByCharCount(-4);
        assertTrue(mTracker.isCursorMoved());
        assertEquals(2, mTracker.getComposingCursorPos());

        mTracker.insertCodePointAtCursor('a');

        // Trailing "stin" must survive.
        assertEquals("Teastin", mTracker.getComposingText());
        assertEquals(3, mTracker.getComposingCursorPos());
        assertEquals(7, mTracker.getCodePointCount());
    }

    @Test
    public void insertAtStartOfComposing() {
        seed("est");
        assertTrue(mTracker.moveCursorByCharCount(-3));
        assertEquals(0, mTracker.getComposingCursorPos());

        mTracker.insertCodePointAtCursor('T');

        assertEquals("Test", mTracker.getComposingText());
        assertEquals(1, mTracker.getComposingCursorPos());
    }

    @Test
    public void deleteBeforeCursorRemovesPrecedingChar() {
        seed("Testin");
        mTracker.moveCursorByCharCount(-4); // cursor between 'e' and 's' (position 2)

        mTracker.deleteCodePointBeforeCursor();

        // 'e' (immediately before the cursor) is removed; trailing "stin" survives.
        assertEquals("Tstin", mTracker.getComposingText());
        assertEquals(1, mTracker.getComposingCursorPos());
    }

    @Test
    public void deleteBeforeCursorAtStartIsNoOp() {
        seed("Test");
        mTracker.moveCursorByCharCount(-4); // cursor at position 0
        assertEquals(0, mTracker.getComposingCursorPos());

        mTracker.deleteCodePointBeforeCursor();

        assertEquals("Test", mTracker.getComposingText());
        assertEquals(0, mTracker.getComposingCursorPos());
    }

    @Test
    public void insertSupplementaryCodePointAtCursor() {
        seed("ab");
        mTracker.moveCursorByCharCount(-1); // between 'a' and 'b' (position 1)

        int emoji = 0x1F600; // 😀, a surrogate pair (two UTF-16 chars, one code point)
        mTracker.insertCodePointAtCursor(emoji);

        String expected = "a" + new String(Character.toChars(emoji)) + "b";
        assertEquals(expected, mTracker.getComposingText());
        assertEquals(2, mTracker.getComposingCursorPos());
        assertEquals(3, mTracker.getCodePointCount());
    }

    @Test
    public void insertDigitMidWordUpdatesStats() {
        seed("12");
        mTracker.moveCursorByCharCount(-1); // cursor between '1' and '2'

        mTracker.insertCodePointAtCursor('3');

        assertEquals("132", mTracker.getComposingText());
        assertTrue("digit count must include the inserted digit", mTracker.hasDigits());
        assertTrue("an all-digit word must stay all-digit after a mid-word digit insert",
                mTracker.isAllDigits());
    }

    @Test
    public void insertUppercaseMidWordUpdatesStats() {
        seed("Ab");
        mTracker.moveCursorByCharCount(-1); // cursor between 'A' and 'b'

        mTracker.insertCodePointAtCursor('C');

        assertEquals("ACb", mTracker.getComposingText());
        assertTrue("uppercase count must include the inserted capital",
                mTracker.hasMultipleUpperCase());
    }

    @Test
    public void insertClearsPendingAutoCorrection() {
        seed("helo");
        mTracker.setAutoCorrection("hello", 5);
        mTracker.moveCursorByCharCount(-2);

        mTracker.insertCodePointAtCursor('x');

        assertNull("pre-edit AC candidate must not survive a mid-word insert",
                mTracker.getAutoCorrection());
        assertEquals(0, mTracker.getAutoCorrectionScore());
    }

    @Test
    public void deleteClearsPendingAutoCorrection() {
        seed("helo");
        mTracker.setAutoCorrection("hello", 5);
        mTracker.moveCursorByCharCount(-2);

        mTracker.deleteCodePointBeforeCursor();

        assertNull("pre-edit AC candidate must not survive a mid-word delete",
                mTracker.getAutoCorrection());
        assertEquals(0, mTracker.getAutoCorrectionScore());
    }

    @Test
    public void autoCorrectionResultIsTrackedPerComposingText() {
        seed("bb");
        assertFalse("no result has arrived for the seeded text",
                mTracker.hasAutoCorrectionForComposingText());

        mTracker.setAutoCorrection("BlackBerry", 7);
        assertTrue(mTracker.hasAutoCorrectionForComposingText());

        mTracker.insertCodePointAtCursor('x');
        assertFalse("an edit makes the stored result stale",
                mTracker.hasAutoCorrectionForComposingText());

        mTracker.setAutoCorrection("bbx", 0);
        mTracker.deleteCodePointBeforeCursor();
        assertFalse(mTracker.hasAutoCorrectionForComposingText());
    }

    @Test
    public void autoCorrectionFromARequestMadeBeforeTheLatestEditIsStale() {
        seed("alo");
        int requestedAt = mTracker.getEditCount();
        mTracker.insertCodePointAtCursor('t');

        mTracker.setAutoCorrection("alo", 0, requestedAt);
        assertFalse(mTracker.hasAutoCorrectionForComposingText());

        mTracker.setAutoCorrection("a lot", 7, mTracker.getEditCount());
        assertTrue(mTracker.hasAutoCorrectionForComposingText());
    }

    @Test
    public void setShiftStateIfNotComposingPropagatesToEngine() {
        mTracker.setShiftStateIfNotComposing(1); // manual shift → engine SHIFT (1)
        verify(mNuance).setShiftState(1);
    }

    @Test
    public void setShiftStateIfNotComposingIsNoOpWhileComposing() {
        seed("a");
        clearInvocations(mNuance);
        mTracker.setShiftStateIfNotComposing(1);
        verify(mNuance, never()).setShiftState(1);
    }

    @Test
    public void moveCursorToStartReportsSuccess() {
        seed("Test");
        // A false here makes onUpdateSelection treat the move as failed and commit the word.
        assertTrue(mTracker.moveCursorByCharCount(-4));
        assertEquals(0, mTracker.getComposingCursorPos());
        assertTrue(mTracker.isComposing());
        assertTrue(mTracker.isCursorMoved());
    }

    @Test
    public void moveCursorPastStartStillFails() {
        seed("Test");
        assertFalse("moving beyond the start of composing is not satisfiable",
                mTracker.moveCursorByCharCount(-5));
    }

    @Test
    public void insertRebuildsEngineByClearAndRefeed() {
        seed("ab");
        mTracker.moveCursorByCharCount(-1);
        clearInvocations(mNuance);

        mTracker.insertCodePointAtCursor('c');

        InOrder order = inOrder(mNuance);
        order.verify(mNuance).clear();
        order.verify(mNuance).processKeyBySymbol('a');
        order.verify(mNuance).processKeyBySymbol('c');
        order.verify(mNuance).processKeyBySymbol('b');
    }

    // clearAll() is not covered: it calls the NuanceSDKManager singleton instead of the injected
    // NuanceSDK, so it NPEs here without static mocking.

    // Hangul and Telex feed the engine jamo, or base letters plus accents, never composed
    // characters, so a mid-word rebuild must re-feed decomposed or the next jamo cannot attach.

    @Test
    public void rebuildWithTheHangulConverterFeedsJamoNotSyllables() {
        seed("한");
        mTracker.setConverterByName("HangulConverter");
        when(mNuance.decodeHangul("한", false)).thenReturn("\u1112\u1161\u11ab");
        when(mNuance.compatibilityJamoToJamoTransform('\u3134')).thenReturn('\u11ab');
        clearInvocations(mNuance);

        mTracker.insertCodePointAtCursor('\u3134');   // ㄴ, a compatibility jamo

        verify(mNuance).clear();
        verify(mNuance).decodeHangul("한", false);
        verify(mNuance).processKeyBySymbol('\u1112');
        verify(mNuance).processKeyBySymbol('\u1161');
        verify(mNuance, never()).processKeyBySymbol('한');
        verify(mNuance, never()).processKeyBySymbol('\u3134');
    }

    @Test
    public void rebuildWithTheTelexConverterAddsComposedLettersExplicitly() {
        seed("vi");
        mTracker.setConverterByName("VietnameseTelexConverter");
        clearInvocations(mNuance);

        mTracker.insertCodePointAtCursor('\u1ec7');   // ệ, a Telex-composable letter

        verify(mNuance).clear();
        verify(mNuance).processKeyBySymbol('v');
        verify(mNuance).processKeyBySymbol('i');
        verify(mNuance).addExplicitSymb('\u1ec7');
        verify(mNuance, never()).processKeyBySymbol('\u1ec7');
    }

    @Test
    public void rebuildWithoutAConverterReplaysPlainSymbols() {
        seed("ab");
        clearInvocations(mNuance);

        mTracker.insertCodePointAtCursor('c');

        verify(mNuance).clear();
        verify(mNuance).processKeyBySymbol('a');
        verify(mNuance).processKeyBySymbol('b');
        verify(mNuance).processKeyBySymbol('c');
        verify(mNuance, never()).addExplicitSymb(anyChar());
    }
}

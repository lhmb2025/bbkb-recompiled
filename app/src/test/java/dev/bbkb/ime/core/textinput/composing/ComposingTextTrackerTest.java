package dev.bbkb.ime.core.textinput.composing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
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
 * Unit tests for {@link ComposingTextTracker}, the single source of truth for the
 * in-progress composing word.
 *
 * <p>The tracker's backing buffer is an {@code android.text.SpannableStringBuilder}, so the
 * tests run under Robolectric (which provides real Android class implementations on the JVM).
 * The tracker's hard dependency, {@code NuanceSDK}, loads a native library in a static
 * initializer; Robolectric instruments {@code com.blackberry.nuanceshim} (see
 * {@code instrumentedPackages} below) so that {@code System.loadLibrary} and native calls
 * become no-ops, allowing the class to load. The instance itself is a Mockito mock, so no
 * native engine is exercised — these tests cover the tracker's own string/cursor arithmetic.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, instrumentedPackages = {"com.blackberry.nuanceshim"})
public class ComposingTextTrackerTest {

    private NuanceSDK mNuance;
    private ComposingTextTracker mTracker;

    @Before
    public void setUp() {
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

        // Regression guard for docs/archived/2026-05_composing-and-ckb-gestures/2026-05_composing-spec-and-tests_reference.md #6: trailing "stin" must survive.
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

        int emoji = 0x1F600; // 😀 — a surrogate pair (two UTF-16 chars, one code point)
        mTracker.insertCodePointAtCursor(emoji);

        String expected = "a" + new String(Character.toChars(emoji)) + "b";
        assertEquals(expected, mTracker.getComposingText());
        assertEquals(2, mTracker.getComposingCursorPos());
        assertEquals(3, mTracker.getCodePointCount());
    }

    // --- F2: mid-word insert must recount digit/uppercase stats ---

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

    // --- F3: mid-word edits must clear the pending auto-correction ---

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

    // --- H1: shift state set while idle must reach the engine ---

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

    // --- F4: a successful cursor move that lands on position 0 must report success ---

    @Test
    public void moveCursorToStartReportsSuccess() {
        seed("Test");
        // Previously returned false at position 0, which made onUpdateSelection treat the
        // move as a failure and commit the word. See docs/archived/2026-06_fable-audits-and-gesture-rebuild/2026-06_composition-pipeline_audit.md F4.
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

    // --- H2: mid-word edits rebuild the engine buffer via clear + ordered re-feed ---

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

    // Note: clearAll() is intentionally not covered here. It reaches into the NuanceSDKManager
    // static singleton (NuanceSDKManager.getInstance().setShiftState(0)) instead of the injected
    // NuanceSDK, so it NPEs under test without static mocking. That global-state coupling is a
    // candidate for the W7 dependency-injection cleanup; until then it isn't unit-testable here.
}

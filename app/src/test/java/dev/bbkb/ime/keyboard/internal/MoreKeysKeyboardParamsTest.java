package dev.bbkb.ime.keyboard.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * Characterisation table for {@link MoreKeysKeyboard.MoreKeysKeyboardParams} — the column/row solver behind the
 * more-keys (long-press alternates) popup.
 *
 * <p>WAVE 2 CONTRACT: this pins what the code does TODAY, bugs included. Wave 3 may restructure
 * {@code setParameters} / {@code getColumnPos} / {@code getX} freely as long as every row below
 * still holds. Nothing here asserts what the geometry "ought" to be.
 *
 * <p>Why it matters: an off-by-one in this solver is invisible until a 9-alternate popup opens on
 * the leftmost key of the keyboard — the popup then hangs off the screen edge or overlaps the
 * parent key. The three parent-key positions in the table (@5 = leftmost key centre, @55 = middle,
 * @95 = rightmost key centre, on a 100px-wide keyboard of 10px keys) are exactly the clamp cases
 * {@code maxLeftKeys}/{@code maxRightKeys} exist for.
 *
 * <h2>The table</h2>
 * One row per input combination, in the form
 * <pre>
 *   numKeys/maxColumns @coordXInParent C&lt;isFixedColumn&gt; O&lt;isFixedOrder&gt; d&lt;dividerWidth&gt;
 *     =&gt; r&lt;mNumRows&gt; c&lt;mNumColumns&gt; t&lt;mTopKeys&gt; l&lt;mLeftKeys&gt; R&lt;mRightKeys&gt;
 *        a&lt;mTopRowAdjustment&gt; w&lt;mOccupiedWidth&gt; h&lt;mOccupiedHeight&gt; X&lt;getDefaultKeyCoordX()&gt;
 *        | getX,getY per key index 0..numKeys-1
 * </pre>
 * Every row is the whole geometry of one popup, so a single-cell regression names itself in the
 * failure diff. Key width is 10, row height 20, keyboard width 100 throughout; all paddings and
 * the vertical gap are 0 (their defaults), which makes {@code getY} and {@code getDefaultKeyCoordX}
 * read as pure multiples.
 *
 * <p>Coverage: numKeys ∈ {1,2,3,5,6,7,9} at maxColumns 5, plus 9/3 (three rows) and 10/4 (three
 * rows, two top keys) — 7/5 and 10/4 are the counts where {@code getOptimizedColumns} actually
 * changes strategy and drops a column. Crossed with three parent-key positions, both
 * {@code isMoreKeysFixedColumn} values, both {@code isMoreKeysFixedOrder} values, and divider
 * widths 0 and 2.
 *
 * <p>The expectations were produced by an independent transcription of the algorithm (not by
 * running this class), and the two non-trivial cases {@code 9/5 @55 C1 O1 d0} and
 * {@code 9/5 @55 C1 O0 d0} were additionally derived by hand.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class MoreKeysKeyboardParamsTest {

    private static final int KEY_WIDTH = 10;
    private static final int ROW_HEIGHT = 20;
    private static final int KEYBOARD_WIDTH = 100;

    private static final String[] TABLE = {
        "1/5 @5 C1 O1 d0 => r1 c1 t1 l0 R1 a0 w10 h20 X0 | 0,0",
        "1/5 @5 C1 O1 d2 => r1 c1 t1 l0 R1 a0 w10 h20 X0 | 0,0",
        "1/5 @5 C1 O0 d0 => r1 c1 t1 l0 R1 a0 w10 h20 X0 | 0,0",
        "1/5 @5 C1 O0 d2 => r1 c1 t1 l0 R1 a0 w10 h20 X0 | 0,0",
        "1/5 @5 C0 O1 d0 => r1 c1 t1 l0 R1 a0 w10 h20 X0 | 0,0",
        "1/5 @5 C0 O1 d2 => r1 c1 t1 l0 R1 a0 w10 h20 X0 | 0,0",
        "1/5 @5 C0 O0 d0 => r1 c1 t1 l0 R1 a0 w10 h20 X0 | 0,0",
        "1/5 @5 C0 O0 d2 => r1 c1 t1 l0 R1 a0 w10 h20 X0 | 0,0",
        "1/5 @55 C1 O1 d0 => r1 c1 t1 l0 R1 a0 w10 h20 X0 | 0,0",
        "1/5 @55 C1 O1 d2 => r1 c1 t1 l0 R1 a0 w10 h20 X0 | 0,0",
        "1/5 @55 C1 O0 d0 => r1 c1 t1 l0 R1 a0 w10 h20 X0 | 0,0",
        "1/5 @55 C1 O0 d2 => r1 c1 t1 l0 R1 a0 w10 h20 X0 | 0,0",
        "1/5 @55 C0 O1 d0 => r1 c1 t1 l0 R1 a0 w10 h20 X0 | 0,0",
        "1/5 @55 C0 O1 d2 => r1 c1 t1 l0 R1 a0 w10 h20 X0 | 0,0",
        "1/5 @55 C0 O0 d0 => r1 c1 t1 l0 R1 a0 w10 h20 X0 | 0,0",
        "1/5 @55 C0 O0 d2 => r1 c1 t1 l0 R1 a0 w10 h20 X0 | 0,0",
        "1/5 @95 C1 O1 d0 => r1 c1 t1 l0 R1 a0 w10 h20 X0 | 0,0",
        "1/5 @95 C1 O1 d2 => r1 c1 t1 l0 R1 a0 w10 h20 X0 | 0,0",
        "1/5 @95 C1 O0 d0 => r1 c1 t1 l0 R1 a0 w10 h20 X0 | 0,0",
        "1/5 @95 C1 O0 d2 => r1 c1 t1 l0 R1 a0 w10 h20 X0 | 0,0",
        "1/5 @95 C0 O1 d0 => r1 c1 t1 l0 R1 a0 w10 h20 X0 | 0,0",
        "1/5 @95 C0 O1 d2 => r1 c1 t1 l0 R1 a0 w10 h20 X0 | 0,0",
        "1/5 @95 C0 O0 d0 => r1 c1 t1 l0 R1 a0 w10 h20 X0 | 0,0",
        "1/5 @95 C0 O0 d2 => r1 c1 t1 l0 R1 a0 w10 h20 X0 | 0,0",
        "2/5 @5 C1 O1 d0 => r1 c2 t2 l0 R2 a0 w20 h20 X0 | 0,0 10,0",
        "2/5 @5 C1 O1 d2 => r1 c2 t2 l0 R2 a0 w22 h20 X0 | 0,0 12,0",
        "2/5 @5 C1 O0 d0 => r1 c2 t2 l0 R2 a0 w20 h20 X0 | 0,0 10,0",
        "2/5 @5 C1 O0 d2 => r1 c2 t2 l0 R2 a0 w22 h20 X0 | 0,0 12,0",
        "2/5 @5 C0 O1 d0 => r1 c2 t2 l0 R2 a0 w20 h20 X0 | 0,0 10,0",
        "2/5 @5 C0 O1 d2 => r1 c2 t2 l0 R2 a0 w22 h20 X0 | 0,0 12,0",
        "2/5 @5 C0 O0 d0 => r1 c2 t2 l0 R2 a0 w20 h20 X0 | 0,0 10,0",
        "2/5 @5 C0 O0 d2 => r1 c2 t2 l0 R2 a0 w22 h20 X0 | 0,0 12,0",
        "2/5 @55 C1 O1 d0 => r1 c2 t2 l0 R2 a0 w20 h20 X0 | 0,0 10,0",
        "2/5 @55 C1 O1 d2 => r1 c2 t2 l0 R2 a0 w22 h20 X0 | 0,0 12,0",
        "2/5 @55 C1 O0 d0 => r1 c2 t2 l0 R2 a0 w20 h20 X0 | 0,0 10,0",
        "2/5 @55 C1 O0 d2 => r1 c2 t2 l0 R2 a0 w22 h20 X0 | 0,0 12,0",
        "2/5 @55 C0 O1 d0 => r1 c2 t2 l0 R2 a0 w20 h20 X0 | 0,0 10,0",
        "2/5 @55 C0 O1 d2 => r1 c2 t2 l0 R2 a0 w22 h20 X0 | 0,0 12,0",
        "2/5 @55 C0 O0 d0 => r1 c2 t2 l0 R2 a0 w20 h20 X0 | 0,0 10,0",
        "2/5 @55 C0 O0 d2 => r1 c2 t2 l0 R2 a0 w22 h20 X0 | 0,0 12,0",
        "2/5 @95 C1 O1 d0 => r1 c2 t2 l1 R1 a0 w20 h20 X10 | 0,0 10,0",
        "2/5 @95 C1 O1 d2 => r1 c2 t2 l1 R1 a0 w22 h20 X12 | 0,0 12,0",
        "2/5 @95 C1 O0 d0 => r1 c2 t2 l1 R1 a0 w20 h20 X10 | 10,0 0,0",
        "2/5 @95 C1 O0 d2 => r1 c2 t2 l1 R1 a0 w22 h20 X12 | 12,0 0,0",
        "2/5 @95 C0 O1 d0 => r1 c2 t2 l1 R1 a0 w20 h20 X10 | 0,0 10,0",
        "2/5 @95 C0 O1 d2 => r1 c2 t2 l1 R1 a0 w22 h20 X12 | 0,0 12,0",
        "2/5 @95 C0 O0 d0 => r1 c2 t2 l1 R1 a0 w20 h20 X10 | 10,0 0,0",
        "2/5 @95 C0 O0 d2 => r1 c2 t2 l1 R1 a0 w22 h20 X12 | 12,0 0,0",
        "3/5 @5 C1 O1 d0 => r1 c3 t3 l0 R3 a0 w30 h20 X0 | 0,0 10,0 20,0",
        "3/5 @5 C1 O1 d2 => r1 c3 t3 l0 R3 a0 w34 h20 X0 | 0,0 12,0 24,0",
        "3/5 @5 C1 O0 d0 => r1 c3 t3 l0 R3 a0 w30 h20 X0 | 0,0 10,0 20,0",
        "3/5 @5 C1 O0 d2 => r1 c3 t3 l0 R3 a0 w34 h20 X0 | 0,0 12,0 24,0",
        "3/5 @5 C0 O1 d0 => r1 c3 t3 l0 R3 a0 w30 h20 X0 | 0,0 10,0 20,0",
        "3/5 @5 C0 O1 d2 => r1 c3 t3 l0 R3 a0 w34 h20 X0 | 0,0 12,0 24,0",
        "3/5 @5 C0 O0 d0 => r1 c3 t3 l0 R3 a0 w30 h20 X0 | 0,0 10,0 20,0",
        "3/5 @5 C0 O0 d2 => r1 c3 t3 l0 R3 a0 w34 h20 X0 | 0,0 12,0 24,0",
        "3/5 @55 C1 O1 d0 => r1 c3 t3 l1 R2 a0 w30 h20 X10 | 0,0 10,0 20,0",
        "3/5 @55 C1 O1 d2 => r1 c3 t3 l1 R2 a0 w34 h20 X12 | 0,0 12,0 24,0",
        "3/5 @55 C1 O0 d0 => r1 c3 t3 l1 R2 a0 w30 h20 X10 | 10,0 20,0 0,0",
        "3/5 @55 C1 O0 d2 => r1 c3 t3 l1 R2 a0 w34 h20 X12 | 12,0 24,0 0,0",
        "3/5 @55 C0 O1 d0 => r1 c3 t3 l1 R2 a0 w30 h20 X10 | 0,0 10,0 20,0",
        "3/5 @55 C0 O1 d2 => r1 c3 t3 l1 R2 a0 w34 h20 X12 | 0,0 12,0 24,0",
        "3/5 @55 C0 O0 d0 => r1 c3 t3 l1 R2 a0 w30 h20 X10 | 10,0 20,0 0,0",
        "3/5 @55 C0 O0 d2 => r1 c3 t3 l1 R2 a0 w34 h20 X12 | 12,0 24,0 0,0",
        "3/5 @95 C1 O1 d0 => r1 c3 t3 l2 R1 a0 w30 h20 X20 | 0,0 10,0 20,0",
        "3/5 @95 C1 O1 d2 => r1 c3 t3 l2 R1 a0 w34 h20 X24 | 0,0 12,0 24,0",
        "3/5 @95 C1 O0 d0 => r1 c3 t3 l2 R1 a0 w30 h20 X20 | 20,0 10,0 0,0",
        "3/5 @95 C1 O0 d2 => r1 c3 t3 l2 R1 a0 w34 h20 X24 | 24,0 12,0 0,0",
        "3/5 @95 C0 O1 d0 => r1 c3 t3 l2 R1 a0 w30 h20 X20 | 0,0 10,0 20,0",
        "3/5 @95 C0 O1 d2 => r1 c3 t3 l2 R1 a0 w34 h20 X24 | 0,0 12,0 24,0",
        "3/5 @95 C0 O0 d0 => r1 c3 t3 l2 R1 a0 w30 h20 X20 | 20,0 10,0 0,0",
        "3/5 @95 C0 O0 d2 => r1 c3 t3 l2 R1 a0 w34 h20 X24 | 24,0 12,0 0,0",
        "5/5 @5 C1 O1 d0 => r1 c5 t5 l0 R5 a0 w50 h20 X0 | 0,0 10,0 20,0 30,0 40,0",
        "5/5 @5 C1 O1 d2 => r1 c5 t5 l0 R5 a0 w58 h20 X0 | 0,0 12,0 24,0 36,0 48,0",
        "5/5 @5 C1 O0 d0 => r1 c5 t5 l0 R5 a0 w50 h20 X0 | 0,0 10,0 20,0 30,0 40,0",
        "5/5 @5 C1 O0 d2 => r1 c5 t5 l0 R5 a0 w58 h20 X0 | 0,0 12,0 24,0 36,0 48,0",
        "5/5 @5 C0 O1 d0 => r1 c5 t5 l0 R5 a0 w50 h20 X0 | 0,0 10,0 20,0 30,0 40,0",
        "5/5 @5 C0 O1 d2 => r1 c5 t5 l0 R5 a0 w58 h20 X0 | 0,0 12,0 24,0 36,0 48,0",
        "5/5 @5 C0 O0 d0 => r1 c5 t5 l0 R5 a0 w50 h20 X0 | 0,0 10,0 20,0 30,0 40,0",
        "5/5 @5 C0 O0 d2 => r1 c5 t5 l0 R5 a0 w58 h20 X0 | 0,0 12,0 24,0 36,0 48,0",
        "5/5 @55 C1 O1 d0 => r1 c5 t5 l2 R3 a0 w50 h20 X20 | 0,0 10,0 20,0 30,0 40,0",
        "5/5 @55 C1 O1 d2 => r1 c5 t5 l2 R3 a0 w58 h20 X24 | 0,0 12,0 24,0 36,0 48,0",
        "5/5 @55 C1 O0 d0 => r1 c5 t5 l2 R3 a0 w50 h20 X20 | 20,0 30,0 10,0 40,0 0,0",
        "5/5 @55 C1 O0 d2 => r1 c5 t5 l2 R3 a0 w58 h20 X24 | 24,0 36,0 12,0 48,0 0,0",
        "5/5 @55 C0 O1 d0 => r1 c5 t5 l2 R3 a0 w50 h20 X20 | 0,0 10,0 20,0 30,0 40,0",
        "5/5 @55 C0 O1 d2 => r1 c5 t5 l2 R3 a0 w58 h20 X24 | 0,0 12,0 24,0 36,0 48,0",
        "5/5 @55 C0 O0 d0 => r1 c5 t5 l2 R3 a0 w50 h20 X20 | 20,0 30,0 10,0 40,0 0,0",
        "5/5 @55 C0 O0 d2 => r1 c5 t5 l2 R3 a0 w58 h20 X24 | 24,0 36,0 12,0 48,0 0,0",
        "5/5 @95 C1 O1 d0 => r1 c5 t5 l4 R1 a0 w50 h20 X40 | 0,0 10,0 20,0 30,0 40,0",
        "5/5 @95 C1 O1 d2 => r1 c5 t5 l4 R1 a0 w58 h20 X48 | 0,0 12,0 24,0 36,0 48,0",
        "5/5 @95 C1 O0 d0 => r1 c5 t5 l4 R1 a0 w50 h20 X40 | 40,0 30,0 20,0 10,0 0,0",
        "5/5 @95 C1 O0 d2 => r1 c5 t5 l4 R1 a0 w58 h20 X48 | 48,0 36,0 24,0 12,0 0,0",
        "5/5 @95 C0 O1 d0 => r1 c5 t5 l4 R1 a0 w50 h20 X40 | 0,0 10,0 20,0 30,0 40,0",
        "5/5 @95 C0 O1 d2 => r1 c5 t5 l4 R1 a0 w58 h20 X48 | 0,0 12,0 24,0 36,0 48,0",
        "5/5 @95 C0 O0 d0 => r1 c5 t5 l4 R1 a0 w50 h20 X40 | 40,0 30,0 20,0 10,0 0,0",
        "5/5 @95 C0 O0 d2 => r1 c5 t5 l4 R1 a0 w58 h20 X48 | 48,0 36,0 24,0 12,0 0,0",
        "6/5 @5 C1 O1 d0 => r2 c5 t1 l0 R5 a0 w50 h40 X0 | 0,20 10,20 20,20 30,20 40,20 0,0",
        "6/5 @5 C1 O1 d2 => r2 c5 t1 l0 R5 a0 w58 h40 X0 | 0,20 12,20 24,20 36,20 48,20 0,0",
        "6/5 @5 C1 O0 d0 => r2 c5 t1 l0 R5 a0 w50 h40 X0 | 0,20 10,20 20,20 30,20 40,20 0,0",
        "6/5 @5 C1 O0 d2 => r2 c5 t1 l0 R5 a0 w58 h40 X0 | 0,20 12,20 24,20 36,20 48,20 0,0",
        "6/5 @5 C0 O1 d0 => r2 c3 t3 l0 R3 a0 w30 h40 X0 | 0,20 10,20 20,20 0,0 10,0 20,0",
        "6/5 @5 C0 O1 d2 => r2 c3 t3 l0 R3 a0 w34 h40 X0 | 0,20 12,20 24,20 0,0 12,0 24,0",
        "6/5 @5 C0 O0 d0 => r2 c3 t3 l0 R3 a0 w30 h40 X0 | 0,20 10,20 20,20 0,0 10,0 20,0",
        "6/5 @5 C0 O0 d2 => r2 c3 t3 l0 R3 a0 w34 h40 X0 | 0,20 12,20 24,20 0,0 12,0 24,0",
        "6/5 @55 C1 O1 d0 => r2 c5 t1 l2 R3 a0 w50 h40 X20 | 0,20 10,20 20,20 30,20 40,20 20,0",
        "6/5 @55 C1 O1 d2 => r2 c5 t1 l2 R3 a0 w58 h40 X24 | 0,20 12,20 24,20 36,20 48,20 24,0",
        "6/5 @55 C1 O0 d0 => r2 c5 t1 l2 R3 a0 w50 h40 X20 | 20,20 30,20 10,20 40,20 0,20 20,0",
        "6/5 @55 C1 O0 d2 => r2 c5 t1 l2 R3 a0 w58 h40 X24 | 24,20 36,20 12,20 48,20 0,20 24,0",
        "6/5 @55 C0 O1 d0 => r2 c3 t3 l1 R2 a0 w30 h40 X10 | 0,20 10,20 20,20 0,0 10,0 20,0",
        "6/5 @55 C0 O1 d2 => r2 c3 t3 l1 R2 a0 w34 h40 X12 | 0,20 12,20 24,20 0,0 12,0 24,0",
        "6/5 @55 C0 O0 d0 => r2 c3 t3 l1 R2 a0 w30 h40 X10 | 10,20 20,20 0,20 10,0 20,0 0,0",
        "6/5 @55 C0 O0 d2 => r2 c3 t3 l1 R2 a0 w34 h40 X12 | 12,20 24,20 0,20 12,0 24,0 0,0",
        "6/5 @95 C1 O1 d0 => r2 c5 t1 l4 R1 a0 w50 h40 X40 | 0,20 10,20 20,20 30,20 40,20 40,0",
        "6/5 @95 C1 O1 d2 => r2 c5 t1 l4 R1 a0 w58 h40 X48 | 0,20 12,20 24,20 36,20 48,20 48,0",
        "6/5 @95 C1 O0 d0 => r2 c5 t1 l4 R1 a0 w50 h40 X40 | 40,20 30,20 20,20 10,20 0,20 40,0",
        "6/5 @95 C1 O0 d2 => r2 c5 t1 l4 R1 a0 w58 h40 X48 | 48,20 36,20 24,20 12,20 0,20 48,0",
        "6/5 @95 C0 O1 d0 => r2 c3 t3 l2 R1 a0 w30 h40 X20 | 0,20 10,20 20,20 0,0 10,0 20,0",
        "6/5 @95 C0 O1 d2 => r2 c3 t3 l2 R1 a0 w34 h40 X24 | 0,20 12,20 24,20 0,0 12,0 24,0",
        "6/5 @95 C0 O0 d0 => r2 c3 t3 l2 R1 a0 w30 h40 X20 | 20,20 10,20 0,20 20,0 10,0 0,0",
        "6/5 @95 C0 O0 d2 => r2 c3 t3 l2 R1 a0 w34 h40 X24 | 24,20 12,20 0,20 24,0 12,0 0,0",
        "7/5 @5 C1 O1 d0 => r2 c5 t2 l0 R5 a0 w50 h40 X0 | 0,20 10,20 20,20 30,20 40,20 0,0 10,0",
        "7/5 @5 C1 O1 d2 => r2 c5 t2 l0 R5 a0 w58 h40 X0 | 0,20 12,20 24,20 36,20 48,20 0,0 12,0",
        "7/5 @5 C1 O0 d0 => r2 c5 t2 l0 R5 a0 w50 h40 X0 | 0,20 10,20 20,20 30,20 40,20 0,0 10,0",
        "7/5 @5 C1 O0 d2 => r2 c5 t2 l0 R5 a0 w58 h40 X0 | 0,20 12,20 24,20 36,20 48,20 0,0 12,0",
        "7/5 @5 C0 O1 d0 => r2 c4 t3 l0 R4 a0 w40 h40 X0 | 0,20 10,20 20,20 30,20 0,0 10,0 20,0",
        "7/5 @5 C0 O1 d2 => r2 c4 t3 l0 R4 a0 w46 h40 X0 | 0,20 12,20 24,20 36,20 0,0 12,0 24,0",
        "7/5 @5 C0 O0 d0 => r2 c4 t3 l0 R4 a0 w40 h40 X0 | 0,20 10,20 20,20 30,20 0,0 10,0 20,0",
        "7/5 @5 C0 O0 d2 => r2 c4 t3 l0 R4 a0 w46 h40 X0 | 0,20 12,20 24,20 36,20 0,0 12,0 24,0",
        "7/5 @55 C1 O1 d0 => r2 c5 t2 l2 R3 a-1 w50 h40 X20 | 0,20 10,20 20,20 30,20 40,20 15,0 25,0",
        "7/5 @55 C1 O1 d2 => r2 c5 t2 l2 R3 a-1 w58 h40 X24 | 0,20 12,20 24,20 36,20 48,20 18,0 30,0",
        "7/5 @55 C1 O0 d0 => r2 c5 t2 l2 R3 a-1 w50 h40 X20 | 20,20 30,20 10,20 40,20 0,20 15,0 25,0",
        "7/5 @55 C1 O0 d2 => r2 c5 t2 l2 R3 a-1 w58 h40 X24 | 24,20 36,20 12,20 48,20 0,20 18,0 30,0",
        "7/5 @55 C0 O1 d0 => r2 c4 t3 l1 R3 a0 w40 h40 X10 | 0,20 10,20 20,20 30,20 0,0 10,0 20,0",
        "7/5 @55 C0 O1 d2 => r2 c4 t3 l1 R3 a0 w46 h40 X12 | 0,20 12,20 24,20 36,20 0,0 12,0 24,0",
        "7/5 @55 C0 O0 d0 => r2 c4 t3 l1 R3 a-1 w40 h40 X10 | 10,20 20,20 0,20 30,20 5,0 15,0 25,0",
        "7/5 @55 C0 O0 d2 => r2 c4 t3 l1 R3 a-1 w46 h40 X12 | 12,20 24,20 0,20 36,20 6,0 18,0 30,0",
        "7/5 @95 C1 O1 d0 => r2 c5 t2 l4 R1 a0 w50 h40 X40 | 0,20 10,20 20,20 30,20 40,20 30,0 40,0",
        "7/5 @95 C1 O1 d2 => r2 c5 t2 l4 R1 a0 w58 h40 X48 | 0,20 12,20 24,20 36,20 48,20 36,0 48,0",
        "7/5 @95 C1 O0 d0 => r2 c5 t2 l4 R1 a0 w50 h40 X40 | 40,20 30,20 20,20 10,20 0,20 40,0 30,0",
        "7/5 @95 C1 O0 d2 => r2 c5 t2 l4 R1 a0 w58 h40 X48 | 48,20 36,20 24,20 12,20 0,20 48,0 36,0",
        "7/5 @95 C0 O1 d0 => r2 c4 t3 l3 R1 a0 w40 h40 X30 | 0,20 10,20 20,20 30,20 10,0 20,0 30,0",
        "7/5 @95 C0 O1 d2 => r2 c4 t3 l3 R1 a0 w46 h40 X36 | 0,20 12,20 24,20 36,20 12,0 24,0 36,0",
        "7/5 @95 C0 O0 d0 => r2 c4 t3 l3 R1 a0 w40 h40 X30 | 30,20 20,20 10,20 0,20 30,0 20,0 10,0",
        "7/5 @95 C0 O0 d2 => r2 c4 t3 l3 R1 a0 w46 h40 X36 | 36,20 24,20 12,20 0,20 36,0 24,0 12,0",
        "9/5 @5 C1 O1 d0 => r2 c5 t4 l0 R5 a0 w50 h40 X0 | 0,20 10,20 20,20 30,20 40,20 0,0 10,0 20,0 30,0",
        "9/5 @5 C1 O1 d2 => r2 c5 t4 l0 R5 a0 w58 h40 X0 | 0,20 12,20 24,20 36,20 48,20 0,0 12,0 24,0 36,0",
        "9/5 @5 C1 O0 d0 => r2 c5 t4 l0 R5 a0 w50 h40 X0 | 0,20 10,20 20,20 30,20 40,20 0,0 10,0 20,0 30,0",
        "9/5 @5 C1 O0 d2 => r2 c5 t4 l0 R5 a0 w58 h40 X0 | 0,20 12,20 24,20 36,20 48,20 0,0 12,0 24,0 36,0",
        "9/5 @5 C0 O1 d0 => r2 c5 t4 l0 R5 a0 w50 h40 X0 | 0,20 10,20 20,20 30,20 40,20 0,0 10,0 20,0 30,0",
        "9/5 @5 C0 O1 d2 => r2 c5 t4 l0 R5 a0 w58 h40 X0 | 0,20 12,20 24,20 36,20 48,20 0,0 12,0 24,0 36,0",
        "9/5 @5 C0 O0 d0 => r2 c5 t4 l0 R5 a0 w50 h40 X0 | 0,20 10,20 20,20 30,20 40,20 0,0 10,0 20,0 30,0",
        "9/5 @5 C0 O0 d2 => r2 c5 t4 l0 R5 a0 w58 h40 X0 | 0,20 12,20 24,20 36,20 48,20 0,0 12,0 24,0 36,0",
        "9/5 @55 C1 O1 d0 => r2 c5 t4 l2 R3 a-1 w50 h40 X20 | 0,20 10,20 20,20 30,20 40,20 5,0 15,0 25,0 35,0",
        "9/5 @55 C1 O1 d2 => r2 c5 t4 l2 R3 a-1 w58 h40 X24 | 0,20 12,20 24,20 36,20 48,20 6,0 18,0 30,0 42,0",
        "9/5 @55 C1 O0 d0 => r2 c5 t4 l2 R3 a-1 w50 h40 X20 | 20,20 30,20 10,20 40,20 0,20 15,0 25,0 5,0 35,0",
        "9/5 @55 C1 O0 d2 => r2 c5 t4 l2 R3 a-1 w58 h40 X24 | 24,20 36,20 12,20 48,20 0,20 18,0 30,0 6,0 42,0",
        "9/5 @55 C0 O1 d0 => r2 c5 t4 l2 R3 a-1 w50 h40 X20 | 0,20 10,20 20,20 30,20 40,20 5,0 15,0 25,0 35,0",
        "9/5 @55 C0 O1 d2 => r2 c5 t4 l2 R3 a-1 w58 h40 X24 | 0,20 12,20 24,20 36,20 48,20 6,0 18,0 30,0 42,0",
        "9/5 @55 C0 O0 d0 => r2 c5 t4 l2 R3 a-1 w50 h40 X20 | 20,20 30,20 10,20 40,20 0,20 15,0 25,0 5,0 35,0",
        "9/5 @55 C0 O0 d2 => r2 c5 t4 l2 R3 a-1 w58 h40 X24 | 24,20 36,20 12,20 48,20 0,20 18,0 30,0 6,0 42,0",
        "9/5 @95 C1 O1 d0 => r2 c5 t4 l4 R1 a0 w50 h40 X40 | 0,20 10,20 20,20 30,20 40,20 10,0 20,0 30,0 40,0",
        "9/5 @95 C1 O1 d2 => r2 c5 t4 l4 R1 a0 w58 h40 X48 | 0,20 12,20 24,20 36,20 48,20 12,0 24,0 36,0 48,0",
        "9/5 @95 C1 O0 d0 => r2 c5 t4 l4 R1 a0 w50 h40 X40 | 40,20 30,20 20,20 10,20 0,20 40,0 30,0 20,0 10,0",
        "9/5 @95 C1 O0 d2 => r2 c5 t4 l4 R1 a0 w58 h40 X48 | 48,20 36,20 24,20 12,20 0,20 48,0 36,0 24,0 12,0",
        "9/5 @95 C0 O1 d0 => r2 c5 t4 l4 R1 a0 w50 h40 X40 | 0,20 10,20 20,20 30,20 40,20 10,0 20,0 30,0 40,0",
        "9/5 @95 C0 O1 d2 => r2 c5 t4 l4 R1 a0 w58 h40 X48 | 0,20 12,20 24,20 36,20 48,20 12,0 24,0 36,0 48,0",
        "9/5 @95 C0 O0 d0 => r2 c5 t4 l4 R1 a0 w50 h40 X40 | 40,20 30,20 20,20 10,20 0,20 40,0 30,0 20,0 10,0",
        "9/5 @95 C0 O0 d2 => r2 c5 t4 l4 R1 a0 w58 h40 X48 | 48,20 36,20 24,20 12,20 0,20 48,0 36,0 24,0 12,0",
        "9/3 @5 C1 O1 d0 => r3 c3 t3 l0 R3 a0 w30 h60 X0 | 0,40 10,40 20,40 0,20 10,20 20,20 0,0 10,0 20,0",
        "9/3 @5 C1 O1 d2 => r3 c3 t3 l0 R3 a0 w34 h60 X0 | 0,40 12,40 24,40 0,20 12,20 24,20 0,0 12,0 24,0",
        "9/3 @5 C1 O0 d0 => r3 c3 t3 l0 R3 a0 w30 h60 X0 | 0,40 10,40 20,40 0,20 10,20 20,20 0,0 10,0 20,0",
        "9/3 @5 C1 O0 d2 => r3 c3 t3 l0 R3 a0 w34 h60 X0 | 0,40 12,40 24,40 0,20 12,20 24,20 0,0 12,0 24,0",
        "9/3 @5 C0 O1 d0 => r3 c3 t3 l0 R3 a0 w30 h60 X0 | 0,40 10,40 20,40 0,20 10,20 20,20 0,0 10,0 20,0",
        "9/3 @5 C0 O1 d2 => r3 c3 t3 l0 R3 a0 w34 h60 X0 | 0,40 12,40 24,40 0,20 12,20 24,20 0,0 12,0 24,0",
        "9/3 @5 C0 O0 d0 => r3 c3 t3 l0 R3 a0 w30 h60 X0 | 0,40 10,40 20,40 0,20 10,20 20,20 0,0 10,0 20,0",
        "9/3 @5 C0 O0 d2 => r3 c3 t3 l0 R3 a0 w34 h60 X0 | 0,40 12,40 24,40 0,20 12,20 24,20 0,0 12,0 24,0",
        "9/3 @55 C1 O1 d0 => r3 c3 t3 l1 R2 a0 w30 h60 X10 | 0,40 10,40 20,40 0,20 10,20 20,20 0,0 10,0 20,0",
        "9/3 @55 C1 O1 d2 => r3 c3 t3 l1 R2 a0 w34 h60 X12 | 0,40 12,40 24,40 0,20 12,20 24,20 0,0 12,0 24,0",
        "9/3 @55 C1 O0 d0 => r3 c3 t3 l1 R2 a0 w30 h60 X10 | 10,40 20,40 0,40 10,20 20,20 0,20 10,0 20,0 0,0",
        "9/3 @55 C1 O0 d2 => r3 c3 t3 l1 R2 a0 w34 h60 X12 | 12,40 24,40 0,40 12,20 24,20 0,20 12,0 24,0 0,0",
        "9/3 @55 C0 O1 d0 => r3 c3 t3 l1 R2 a0 w30 h60 X10 | 0,40 10,40 20,40 0,20 10,20 20,20 0,0 10,0 20,0",
        "9/3 @55 C0 O1 d2 => r3 c3 t3 l1 R2 a0 w34 h60 X12 | 0,40 12,40 24,40 0,20 12,20 24,20 0,0 12,0 24,0",
        "9/3 @55 C0 O0 d0 => r3 c3 t3 l1 R2 a0 w30 h60 X10 | 10,40 20,40 0,40 10,20 20,20 0,20 10,0 20,0 0,0",
        "9/3 @55 C0 O0 d2 => r3 c3 t3 l1 R2 a0 w34 h60 X12 | 12,40 24,40 0,40 12,20 24,20 0,20 12,0 24,0 0,0",
        "9/3 @95 C1 O1 d0 => r3 c3 t3 l2 R1 a0 w30 h60 X20 | 0,40 10,40 20,40 0,20 10,20 20,20 0,0 10,0 20,0",
        "9/3 @95 C1 O1 d2 => r3 c3 t3 l2 R1 a0 w34 h60 X24 | 0,40 12,40 24,40 0,20 12,20 24,20 0,0 12,0 24,0",
        "9/3 @95 C1 O0 d0 => r3 c3 t3 l2 R1 a0 w30 h60 X20 | 20,40 10,40 0,40 20,20 10,20 0,20 20,0 10,0 0,0",
        "9/3 @95 C1 O0 d2 => r3 c3 t3 l2 R1 a0 w34 h60 X24 | 24,40 12,40 0,40 24,20 12,20 0,20 24,0 12,0 0,0",
        "9/3 @95 C0 O1 d0 => r3 c3 t3 l2 R1 a0 w30 h60 X20 | 0,40 10,40 20,40 0,20 10,20 20,20 0,0 10,0 20,0",
        "9/3 @95 C0 O1 d2 => r3 c3 t3 l2 R1 a0 w34 h60 X24 | 0,40 12,40 24,40 0,20 12,20 24,20 0,0 12,0 24,0",
        "9/3 @95 C0 O0 d0 => r3 c3 t3 l2 R1 a0 w30 h60 X20 | 20,40 10,40 0,40 20,20 10,20 0,20 20,0 10,0 0,0",
        "9/3 @95 C0 O0 d2 => r3 c3 t3 l2 R1 a0 w34 h60 X24 | 24,40 12,40 0,40 24,20 12,20 0,20 24,0 12,0 0,0",
        "10/4 @5 C1 O1 d0 => r3 c4 t2 l0 R4 a0 w40 h60 X0 | 0,40 10,40 20,40 30,40 0,20 10,20 20,20 30,20 0,0 10,0",
        "10/4 @5 C1 O1 d2 => r3 c4 t2 l0 R4 a0 w46 h60 X0 | 0,40 12,40 24,40 36,40 0,20 12,20 24,20 36,20 0,0 12,0",
        "10/4 @5 C1 O0 d0 => r3 c4 t2 l0 R4 a0 w40 h60 X0 | 0,40 10,40 20,40 30,40 0,20 10,20 20,20 30,20 0,0 10,0",
        "10/4 @5 C1 O0 d2 => r3 c4 t2 l0 R4 a0 w46 h60 X0 | 0,40 12,40 24,40 36,40 0,20 12,20 24,20 36,20 0,0 12,0",
        "10/4 @5 C0 O1 d0 => r3 c4 t2 l0 R4 a0 w40 h60 X0 | 0,40 10,40 20,40 30,40 0,20 10,20 20,20 30,20 0,0 10,0",
        "10/4 @5 C0 O1 d2 => r3 c4 t2 l0 R4 a0 w46 h60 X0 | 0,40 12,40 24,40 36,40 0,20 12,20 24,20 36,20 0,0 12,0",
        "10/4 @5 C0 O0 d0 => r3 c4 t2 l0 R4 a0 w40 h60 X0 | 0,40 10,40 20,40 30,40 0,20 10,20 20,20 30,20 0,0 10,0",
        "10/4 @5 C0 O0 d2 => r3 c4 t2 l0 R4 a0 w46 h60 X0 | 0,40 12,40 24,40 36,40 0,20 12,20 24,20 36,20 0,0 12,0",
        "10/4 @55 C1 O1 d0 => r3 c4 t2 l1 R3 a-1 w40 h60 X10 | 0,40 10,40 20,40 30,40 0,20 10,20 20,20 30,20 5,0 15,0",
        "10/4 @55 C1 O1 d2 => r3 c4 t2 l1 R3 a-1 w46 h60 X12 | 0,40 12,40 24,40 36,40 0,20 12,20 24,20 36,20 6,0 18,0",
        "10/4 @55 C1 O0 d0 => r3 c4 t2 l1 R3 a0 w40 h60 X10 | 10,40 20,40 0,40 30,40 10,20 20,20 0,20 30,20 10,0 20,0",
        "10/4 @55 C1 O0 d2 => r3 c4 t2 l1 R3 a0 w46 h60 X12 | 12,40 24,40 0,40 36,40 12,20 24,20 0,20 36,20 12,0 24,0",
        "10/4 @55 C0 O1 d0 => r3 c4 t2 l1 R3 a-1 w40 h60 X10 | 0,40 10,40 20,40 30,40 0,20 10,20 20,20 30,20 5,0 15,0",
        "10/4 @55 C0 O1 d2 => r3 c4 t2 l1 R3 a-1 w46 h60 X12 | 0,40 12,40 24,40 36,40 0,20 12,20 24,20 36,20 6,0 18,0",
        "10/4 @55 C0 O0 d0 => r3 c4 t2 l1 R3 a0 w40 h60 X10 | 10,40 20,40 0,40 30,40 10,20 20,20 0,20 30,20 10,0 20,0",
        "10/4 @55 C0 O0 d2 => r3 c4 t2 l1 R3 a0 w46 h60 X12 | 12,40 24,40 0,40 36,40 12,20 24,20 0,20 36,20 12,0 24,0",
        "10/4 @95 C1 O1 d0 => r3 c4 t2 l3 R1 a0 w40 h60 X30 | 0,40 10,40 20,40 30,40 0,20 10,20 20,20 30,20 20,0 30,0",
        "10/4 @95 C1 O1 d2 => r3 c4 t2 l3 R1 a0 w46 h60 X36 | 0,40 12,40 24,40 36,40 0,20 12,20 24,20 36,20 24,0 36,0",
        "10/4 @95 C1 O0 d0 => r3 c4 t2 l3 R1 a0 w40 h60 X30 | 30,40 20,40 10,40 0,40 30,20 20,20 10,20 0,20 30,0 20,0",
        "10/4 @95 C1 O0 d2 => r3 c4 t2 l3 R1 a0 w46 h60 X36 | 36,40 24,40 12,40 0,40 36,20 24,20 12,20 0,20 36,0 24,0",
        "10/4 @95 C0 O1 d0 => r3 c4 t2 l3 R1 a0 w40 h60 X30 | 0,40 10,40 20,40 30,40 0,20 10,20 20,20 30,20 20,0 30,0",
        "10/4 @95 C0 O1 d2 => r3 c4 t2 l3 R1 a0 w46 h60 X36 | 0,40 12,40 24,40 36,40 0,20 12,20 24,20 36,20 24,0 36,0",
        "10/4 @95 C0 O0 d0 => r3 c4 t2 l3 R1 a0 w40 h60 X30 | 30,40 20,40 10,40 0,40 30,20 20,20 10,20 0,20 30,0 20,0",
        "10/4 @95 C0 O0 d2 => r3 c4 t2 l3 R1 a0 w46 h60 X36 | 36,40 24,40 12,40 0,40 36,20 24,20 12,20 0,20 36,0 24,0",
    };

    /** Builds the params for one table row's input half. */
    private static MoreKeysKeyboard.MoreKeysKeyboardParams build(String spec) {
        // "9/5 @55 C1 O1 d0"
        String[] parts = spec.trim().split(" ");
        String[] counts = parts[0].split("/");
        int numKeys = Integer.parseInt(counts[0]);
        int maxColumns = Integer.parseInt(counts[1]);
        int coordXInParent = Integer.parseInt(parts[1].substring(1));
        boolean fixedColumn = parts[2].charAt(1) == '1';
        boolean fixedOrder = parts[3].charAt(1) == '1';
        int dividerWidth = Integer.parseInt(parts[4].substring(1));
        MoreKeysKeyboard.MoreKeysKeyboardParams params = new MoreKeysKeyboard.MoreKeysKeyboardParams();
        params.setParameters(numKeys, maxColumns, KEY_WIDTH, ROW_HEIGHT, coordXInParent,
                KEYBOARD_WIDTH, fixedColumn, fixedOrder, dividerWidth);
        return params;
    }

    /** Renders the output half of a table row for the params built from {@code spec}. */
    private static String render(String spec, MoreKeysKeyboard.MoreKeysKeyboardParams p) {
        int numKeys = Integer.parseInt(spec.trim().split(" ")[0].split("/")[0]);
        StringBuilder sb = new StringBuilder();
        sb.append("r").append(p.mNumRows)
          .append(" c").append(p.mNumColumns)
          .append(" t").append(p.mTopKeys)
          .append(" l").append(p.mLeftKeys)
          .append(" R").append(p.mRightKeys)
          .append(" a").append(p.mTopRowAdjustment)
          .append(" w").append(p.mOccupiedWidth)
          .append(" h").append(p.mOccupiedHeight)
          .append(" X").append(p.getDefaultKeyCoordX())
          .append(" |");
        for (int i = 0; i < numKeys; i++) {
            int row = i / p.mNumColumns;
            sb.append(" ").append(p.getX(i, row)).append(",").append(p.getY(row));
        }
        return sb.toString();
    }

    /**
     * The whole table in one test: every mismatch is collected and reported together, so a
     * rewrite that shifts one column shows every row it touched rather than the first.
     */
    @Test
    public void geometryTable() {
        List<String> mismatches = new ArrayList<>();
        for (String row : TABLE) {
            int split = row.indexOf(" => ");
            String spec = row.substring(0, split);
            String expected = row.substring(split + 4);
            String actual = render(spec, build(spec));
            if (!expected.equals(actual)) {
                mismatches.add(spec + "\n    expected: " + expected + "\n    actual:   " + actual);
            }
        }
        if (!mismatches.isEmpty()) {
            fail(mismatches.size() + " of " + TABLE.length + " geometry rows changed:\n"
                    + String.join("\n", mismatches));
        }
    }

    /** Guards the table itself: a table that silently shrank would make geometryTable vacuous. */
    @Test
    public void tableCoversEveryInputCombination() {
        assertEquals(216, TABLE.length);
    }

    /**
     * {@code mOccupiedWidth} is {@code numColumns * (keyWidth + dividerWidth) - dividerWidth}: the
     * dividers sit BETWEEN columns, so the trailing one is subtracted back off.
     */
    @Test
    public void dividerWidthWidensEveryColumnButNotTheTrailingEdge() {
        MoreKeysKeyboard.MoreKeysKeyboardParams noDivider = build("5/5 @55 C1 O1 d0");
        assertEquals(0, noDivider.mDividerWidth);
        assertEquals(KEY_WIDTH, noDivider.mColumnWidth);
        assertEquals(5 * KEY_WIDTH, noDivider.mOccupiedWidth);

        MoreKeysKeyboard.MoreKeysKeyboardParams withDivider = build("5/5 @55 C1 O1 d2");
        assertEquals(2, withDivider.mDividerWidth);
        assertEquals(KEY_WIDTH + 2, withDivider.mColumnWidth);
        assertEquals(5 * (KEY_WIDTH + 2) - 2, withDivider.mOccupiedWidth);
    }

    /**
     * The strategy switch: with {@code isMoreKeysFixedColumn} the column count is
     * {@code min(numKeys, maxColumns)}; without it {@code getOptimizedColumns} drops columns while
     * the top row would be left with {@code >= mNumRows} empty slots. Seven keys in at most five
     * columns is the smallest case in the table where the two disagree (5 columns vs 4).
     */
    @Test
    public void autoColumnDropsAColumnRatherThanLeaveTheTopRowSparse() {
        assertEquals(5, build("7/5 @55 C1 O1 d0").mNumColumns);
        assertEquals(4, build("7/5 @55 C0 O1 d0").mNumColumns);
        // mNumRows is computed from maxColumns BEFORE the column count is optimised, so it stays 2
        // even though 7 keys in 4 columns needs 2 rows anyway.
        assertEquals(2, build("7/5 @55 C0 O1 d0").mNumRows);
    }

    /** {@code isMoreKeysFixedOrder} is stored verbatim and is what {@code getColumnPos} switches on. */
    @Test
    public void fixedOrderFlagIsRecorded() {
        assertTrue(build("5/5 @55 C1 O1 d0").mIsMoreKeysFixedOrder);
        assertTrue(!build("5/5 @55 C1 O0 d0").mIsMoreKeysFixedOrder);
    }

    /**
     * A single row never gets a top-row adjustment and {@code isTopRow} is false for row 0, so
     * {@code getY(0)} is {@code mTopPadding} (0 here) for every one-row popup.
     */
    @Test
    public void singleRowPopupSitsAtTheTop() {
        MoreKeysKeyboard.MoreKeysKeyboardParams p = build("3/5 @55 C1 O1 d0");
        assertEquals(1, p.mNumRows);
        assertEquals(0, p.mTopRowAdjustment);
        assertEquals(0, p.getY(0));
    }

    /**
     * The guard: a keyboard too narrow to hold {@code min(numKeys, maxColumns)} columns throws
     * rather than laying out off-screen. 40px of keyboard holds 4 of the 5 columns nine keys want.
     */
    @Test
    public void keyboardTooNarrowForTheColumnsThrows() {
        MoreKeysKeyboard.MoreKeysKeyboardParams p = new MoreKeysKeyboard.MoreKeysKeyboardParams();
        try {
            p.setParameters(9, 5, KEY_WIDTH, ROW_HEIGHT, 20, 40, true, true, 0);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertEquals("Keyboard is too small to hold more keys: 40 10 9 5", expected.getMessage());
        }
    }

    /**
     * The boundary of that guard: exactly wide enough is allowed. 50px holds all 5 columns.
     */
    @Test
    public void keyboardExactlyWideEnoughIsAccepted() {
        MoreKeysKeyboard.MoreKeysKeyboardParams p = new MoreKeysKeyboard.MoreKeysKeyboardParams();
        p.setParameters(9, 5, KEY_WIDTH, ROW_HEIGHT, 20, 50, true, true, 0);
        assertEquals(5, p.mNumColumns);
    }
}

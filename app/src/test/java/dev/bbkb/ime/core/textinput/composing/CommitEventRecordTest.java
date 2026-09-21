package dev.bbkb.ime.core.textinput.composing;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import dev.bbkb.ime.core.textinput.composing.TouchPointerCoordTracker;

import org.junit.Test;

import java.lang.reflect.Field;

/**
 * Regression tests for audit findings TI-21 and TI-29 on {@link CommitEventRecord}.
 */
public class CommitEventRecordTest {

    private static boolean revertEligibleOf(CommitEventRecord record) throws Exception {
        Field f = CommitEventRecord.class.getDeclaredField("revertEligible");
        f.setAccessible(true);
        return f.getBoolean(record);
    }

    /**
     * TI-29: {@link CommitEventRecord#IDLE} is a process-wide shared singleton, and
     * {@code InputLogic} assigns it to {@code mEventDispatcher} and then calls
     * {@code disableRevert()} on it — permanently mutating shared state. Benign only because
     * {@code isRevertEligible()} already short-circuits on the empty committed word.
     */
    @Test
    public void disableRevertDoesNotMutateTheIdleSingleton() throws Exception {
        assertTrue(revertEligibleOf(CommitEventRecord.IDLE));

        CommitEventRecord.IDLE.disableRevert();

        assertTrue("IDLE is shared across the process and must stay immutable",
                revertEligibleOf(CommitEventRecord.IDLE));
    }

    @Test
    public void disableRevertStillWorksOnOrdinaryRecords() throws Exception {
        CommitEventRecord record = new CommitEventRecord(
                null, "typed", "committed", " ", null, 0, CommitEventRecord.CommitType.DECIDED_WORD, false);
        assertTrue(revertEligibleOf(record));

        record.disableRevert();

        assertFalse(revertEligibleOf(record));
    }

    /**
     * TI-21: every record used to allocate a {@code TouchPointerCoordTracker(48)} — six
     * {@code IntArrayList(48)} backing arrays, ~1.1 KB — on every word commit, including when
     * there was no source tracker to copy from and nothing was ever written into it.
     */
    @Test
    public void coordinateTrackerIsOnlyAllocatedWhenThereIsSomethingToCopy() {
        assertNull(CommitEventRecord.IDLE.coordinateTracker);

        CommitEventRecord withoutSource = new CommitEventRecord(
                null, "typed", "committed", " ", null, 0, CommitEventRecord.CommitType.DECIDED_WORD, false);
        assertNull(withoutSource.coordinateTracker);

        CommitEventRecord withSource = new CommitEventRecord(
                new TouchPointerCoordTracker(4), "typed", "committed", " ", null, 0,
                CommitEventRecord.CommitType.DECIDED_WORD, false);
        assertNotNull(withSource.coordinateTracker);
    }
}

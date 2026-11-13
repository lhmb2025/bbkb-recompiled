package dev.bbkb.ime.core.textinput.composing;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import dev.bbkb.ime.core.textinput.composing.TouchPointerCoordTracker;

import org.junit.Test;

import java.lang.reflect.Field;

/** {@link CommitEventRecord}: the shared IDLE record stays immutable; the coordinate tracker is lazy. */
public class CommitEventRecordTest {

    private static boolean revertEligibleOf(CommitEventRecord record) throws Exception {
        Field f = CommitEventRecord.class.getDeclaredField("revertEligible");
        f.setAccessible(true);
        return f.getBoolean(record);
    }

    /** {@code InputLogic} calls {@code disableRevert()} on the shared {@link CommitEventRecord#IDLE}. */
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

    /** A {@code TouchPointerCoordTracker(48)} is ~1.1 KB, so a record allocates one only to copy into. */
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

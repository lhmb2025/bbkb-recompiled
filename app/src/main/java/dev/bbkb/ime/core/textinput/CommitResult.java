package dev.bbkb.ime.core.textinput;

/**
 * What {@link CommitController#execute} did. The separator append is conditional, so
 * {@link #separatorCommitted} is the only way a caller holding the payload knows whether it
 * was already written.
 */
final class CommitResult {

    /** Characters written to the editor: payload length plus the separator, if it was appended. */
    final int lengthWritten;

    /** {@code true} when the pipeline wrote {@code trailingSeparator} itself. */
    final boolean separatorCommitted;

    /**
     * The inverse of the event record's {@code skipLearning}. Only the prediction-word protocol
     * computes it; every other protocol reports {@code true}.
     */
    final boolean shouldRecordPick;

    /** Nothing was written (an empty payload, or no composing word to resolve). */
    static final CommitResult NOTHING = new CommitResult(0, false, true);

    CommitResult(int lengthWritten, boolean separatorCommitted, boolean shouldRecordPick) {
        this.lengthWritten = lengthWritten;
        this.separatorCommitted = separatorCommitted;
        this.shouldRecordPick = shouldRecordPick;
    }

    @Override
    public String toString() {
        return "CommitResult{len=" + lengthWritten + " sepCommitted=" + separatorCommitted
                + " recordPick=" + shouldRecordPick + "}";
    }
}

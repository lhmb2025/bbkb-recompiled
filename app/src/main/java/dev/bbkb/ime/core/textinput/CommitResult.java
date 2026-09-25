package dev.bbkb.ime.core.textinput;

/**
 * What {@link CommitController#execute} actually did — the answers callers used to have to infer,
 * or could not get at all.
 *
 * <p>The three fields are the three return values the ten old entry points variously produced:
 * {@code commitWordExtended} returned a length, {@code autoCorrectAndCommit} returned the
 * separator flag, {@code commitPredictionWord} returned the learning flag. One call now reports
 * all three, so an adapter reads the one it needs instead of a different method returning a
 * differently-typed answer.
 *
 * <p>{@link #separatorCommitted} is audit SS-1. The separator append is conditional — single
 * code point, separator-class (or forced), no pending auto-space — and a caller that also holds
 * the payload has no other way to know whether the pipeline already wrote it.
 * {@code commitVoiceInput} could not tell and committed a palette emoji twice.
 */
final class CommitResult {

    /** Characters written to the editor: payload length plus the separator, if it was appended. */
    final int lengthWritten;

    /** Audit SS-1: {@code true} when the pipeline wrote {@code trailingSeparator} itself. */
    final boolean separatorCommitted;

    /**
     * {@code true} when the caller should treat this as a pick worth recording — the inverse of
     * the event record's {@code skipLearning}. Only the prediction-word protocol computes it;
     * every other protocol reports {@code true}, which is what the old call sites assumed.
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

package dev.bbkb.ime.keyboard.internal;



public final class TypingTimeRecorder {

    private final int mStaticTimeThresholdAfterFastTyping;

    private final int mSuppressKeyPreviewAfterBatchInputDuration;

    private long mLastTypingTime;

    private long mLastLetterTypingTime;

    private long mLastBatchInputTime;

    public TypingTimeRecorder(int i, int i2) {
        this.mStaticTimeThresholdAfterFastTyping = i;
        this.mSuppressKeyPreviewAfterBatchInputDuration = i2;
    }

    private boolean wasLastInputTyping() {
        return this.mLastTypingTime >= this.mLastBatchInputTime;
    }

    public void onCodeInput(int i, long j) {
        if (Character.isLetter(i)) {
            if (wasLastInputTyping() || j - this.mLastTypingTime < this.mStaticTimeThresholdAfterFastTyping) {
                this.mLastLetterTypingTime = j;
            }
        } else if (j - this.mLastLetterTypingTime < this.mStaticTimeThresholdAfterFastTyping) {
            this.mLastLetterTypingTime = j;
        }
        this.mLastTypingTime = j;
    }

    public void onEndBatchInput(long j) {
        this.mLastBatchInputTime = j;
    }

    public long getLastLetterTypingTime() {
        return this.mLastLetterTypingTime;
    }

    public boolean needsUpdateBatchInput(long j) {
        return !wasLastInputTyping() && j - this.mLastBatchInputTime < ((long) this.mSuppressKeyPreviewAfterBatchInputDuration);
    }
}

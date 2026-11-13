package dev.bbkb.ime.keyboard.internal;



public final class TypingTimeRecorder {

    private final int mStaticTimeThresholdAfterFastTyping;

    private final int mSuppressKeyPreviewAfterBatchInputDuration;

    private long mLastTypingTime;

    private long mLastLetterTypingTime;

    private long mLastBatchInputTime;

    public TypingTimeRecorder(int staticTimeThresholdAfterFastTyping, int suppressKeyPreviewAfterBatchInputDuration) {
        this.mStaticTimeThresholdAfterFastTyping = staticTimeThresholdAfterFastTyping;
        this.mSuppressKeyPreviewAfterBatchInputDuration = suppressKeyPreviewAfterBatchInputDuration;
    }

    private boolean wasLastInputTyping() {
        return this.mLastTypingTime >= this.mLastBatchInputTime;
    }

    public void onCodeInput(int code, long keyEventTime) {
        if (Character.isLetter(code)) {
            if (wasLastInputTyping() || keyEventTime - this.mLastTypingTime < this.mStaticTimeThresholdAfterFastTyping) {
                this.mLastLetterTypingTime = keyEventTime;
            }
        } else if (keyEventTime - this.mLastLetterTypingTime < this.mStaticTimeThresholdAfterFastTyping) {
            this.mLastLetterTypingTime = keyEventTime;
        }
        this.mLastTypingTime = keyEventTime;
    }

    public void onEndBatchInput(long batchInputTime) {
        this.mLastBatchInputTime = batchInputTime;
    }

    public long getLastLetterTypingTime() {
        return this.mLastLetterTypingTime;
    }

    public boolean needsUpdateBatchInput(long keyEventTime) {
        return !wasLastInputTyping() && keyEventTime - this.mLastBatchInputTime < ((long) this.mSuppressKeyPreviewAfterBatchInputDuration);
    }
}

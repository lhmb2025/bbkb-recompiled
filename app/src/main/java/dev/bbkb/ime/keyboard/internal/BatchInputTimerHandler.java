package dev.bbkb.ime.keyboard.internal;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;


/**
 * Repeating batch-input update timer for swipe typing: while a gesture is in progress it re-posts
 * itself every {@code mUpdateBatchInputTimerDelay} ms and calls
 * {@link AbstractDrawingHandler#updateBatchInputTimer}.
 */
public final class BatchInputTimerHandler extends Handler implements TimerCallback {

    private final int mUpdateBatchInputTimerDelay;

    /** @param i update delay in ms; 0 or less disables the timer */
    public BatchInputTimerHandler(int i) {
        // Explicit main Looper: the implicit one is deprecated and throws on a thread without one.
        super(Looper.getMainLooper());
        this.mUpdateBatchInputTimerDelay = i;
    }

    @Override
    public void handleMessage(Message message) {
        AbstractDrawingHandler abstractC1072n = (AbstractDrawingHandler) message.obj;
        if (message.what != 1) {
            return;
        }
        abstractC1072n.updateBatchInputTimer(SystemClock.uptimeMillis());
        startUpdateBatchInputTimer(abstractC1072n);
    }

    @Override
    public void startUpdateBatchInputTimer(AbstractDrawingHandler abstractC1072n) {
        if (this.mUpdateBatchInputTimerDelay <= 0) {
            return;
        }
        removeMessages(1, abstractC1072n);
        sendMessageDelayed(obtainMessage(1, abstractC1072n), this.mUpdateBatchInputTimerDelay);
    }

    @Override
    public void cancelUpdateBatchInputTimer(AbstractDrawingHandler abstractC1072n) {
        removeMessages(1, abstractC1072n);
    }

    @Override
    public void cancelAllUpdateBatchInputTimers() {
        removeMessages(1);
    }
}

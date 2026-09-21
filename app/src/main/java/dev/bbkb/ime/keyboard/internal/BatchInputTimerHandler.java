package dev.bbkb.ime.keyboard.internal;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;


/**
 * Drives the repeating batch-input update timer for gesture (swipe) typing: while a gesture is in
 * progress it re-posts itself every {@code mUpdateBatchInputTimerDelay} ms and calls
 * {@link AbstractDrawingHandler#updateBatchInputTimer}.
 *
 * <p>Named {@code KeyPreviewHandler} until 2026-09-16, which was doubly wrong: it has nothing to
 * do with key previews, and AOSP's {@code KeyPreviewHandler} is a different class. AOSP folds this
 * timer into {@link TimerHandler}, which also exists here - the two were never merged, so this
 * keeps a truthful name of its own rather than squatting on one that means something else.
 */
public final class BatchInputTimerHandler extends Handler implements TimerCallback {

    private final int mUpdateBatchInputTimerDelay;

    public BatchInputTimerHandler(int i) {
        // Explicit main Looper: the implicit super() binds to the constructing thread's Looper
        // (deprecated since API 30) and throws outright when that thread has none. Both call
        // sites are View constructors, so this is the Looper they were already getting.
        super(Looper.getMainLooper());
        this.mUpdateBatchInputTimerDelay = i;
    }

    @Override // android.os.Handler
    public void handleMessage(Message message) {
        AbstractDrawingHandler abstractC1072n = (AbstractDrawingHandler) message.obj;
        if (message.what != 1) {
            return;
        }
        abstractC1072n.updateBatchInputTimer(SystemClock.uptimeMillis());
        startUpdateBatchInputTimer(abstractC1072n);
    }

    @Override // dev.bbkb.ime.keyboard.internal.TimerCallback
    public void startUpdateBatchInputTimer(AbstractDrawingHandler abstractC1072n) {
        if (this.mUpdateBatchInputTimerDelay <= 0) {
            return;
        }
        removeMessages(1, abstractC1072n);
        sendMessageDelayed(obtainMessage(1, abstractC1072n), this.mUpdateBatchInputTimerDelay);
    }

    @Override // dev.bbkb.ime.keyboard.internal.TimerCallback
    public void cancelUpdateBatchInputTimer(AbstractDrawingHandler abstractC1072n) {
        removeMessages(1, abstractC1072n);
    }

    @Override // dev.bbkb.ime.keyboard.internal.TimerCallback
    public void cancelAllUpdateBatchInputTimers() {
        removeMessages(1);
    }
}

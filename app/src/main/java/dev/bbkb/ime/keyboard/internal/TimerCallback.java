package dev.bbkb.ime.keyboard.internal;



public interface TimerCallback {
    void cancelAllUpdateBatchInputTimers();

    void startUpdateBatchInputTimer(AbstractDrawingHandler abstractC1072n);

    void cancelUpdateBatchInputTimer(AbstractDrawingHandler abstractC1072n);
}

package dev.bbkb.ime.keyboard.internal;



public interface TimerCallback {
    void cancelAllUpdateBatchInputTimers();

    void startUpdateBatchInputTimer(AbstractDrawingHandler abstractDrawingHandler);

    void cancelUpdateBatchInputTimer(AbstractDrawingHandler abstractDrawingHandler);
}

package dev.bbkb.ime.keyboard.internal;

import dev.bbkb.ime.keyboard.Key;


public interface TimerProxy {
    void startTypingStateTimer(Key key);

    void cancelKeyRepeatTimer(PointerTracker pointerTracker);

    void startLongPressTimer(PointerTracker pointerTracker, int delay);

    void startKeyRepeatTimer(PointerTracker pointerTracker, int repeatCount, int delay);

    void cancelLongPressTimers(PointerTracker pointerTracker);

    void startLockFocusTimer(PointerTracker pointerTracker, int delay);

    void cancelLongPressShiftKeyTimer();

    void cancelKeyTimers(PointerTracker pointerTracker);

    boolean isTypingState();
}

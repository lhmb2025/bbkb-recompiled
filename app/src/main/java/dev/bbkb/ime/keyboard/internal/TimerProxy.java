package dev.bbkb.ime.keyboard.internal;

import dev.bbkb.ime.keyboard.Key;


public interface TimerProxy {
    void startTypingStateTimer(Key key);

    void cancelKeyRepeatTimer(PointerTracker c1084t);

    void startLongPressTimer(PointerTracker c1084t, int i);

    void startKeyRepeatTimer(PointerTracker c1084t, int i, int i2);

    void cancelLongPressTimers(PointerTracker c1084t);

    void startLockFocusTimer(PointerTracker c1084t, int i);

    void cancelLongPressShiftKeyTimer();

    void cancelKeyTimers(PointerTracker c1084t);

    boolean isTypingState();
}

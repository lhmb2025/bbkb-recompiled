package dev.bbkb.ime.keyboard.internal;

import android.os.Message;
import android.view.ViewConfiguration;

import dev.bbkb.ime.core.shared.WeakOwnerHandler;
import dev.bbkb.ime.keyboard.Key;


public final class TimerHandler extends WeakOwnerHandler<TimerHandler.Callbacks> implements TimerProxy {

    private final int mTypingStateTimeout;

    
    public interface Callbacks {
        void onLongPress(PointerTracker pointerTracker);

        void startWhileTypingFadeinAnimation();

        void startWhileTypingFadeoutAnimation();
    }

    public TimerHandler(Callbacks callbacks, int typingStateTimeout) {
        super(callbacks);
        this.mTypingStateTimeout = typingStateTimeout;
    }

    @Override // android.os.Handler
    public void handleMessage(Message message) {
        Callbacks aVarV = getOwner();
        if (aVarV == null) {
            return;
        }
        PointerTracker c1084t = (PointerTracker) message.obj;
        int i = message.what;
        if (i != 5) {
            switch (i) {
                case 0:
                    aVarV.startWhileTypingFadeinAnimation();
                    break;
                case 1:
                    c1084t.onKeyRepeat(message.arg1, message.arg2);
                    break;
                case 2:
                case 3:
                    cancelAllLongPressTimers();
                    aVarV.onLongPress(c1084t);
                    break;
            }
            return;
        }
        c1084t.onLockFocusTimerExpired();
    }

    @Override // dev.bbkb.ime.keyboard.internal.TimerProxy
    public void startKeyRepeatTimer(PointerTracker pointerTracker, int repeatCount, int delay) {
        Key keyM7652o = pointerTracker.getKey();
        if (keyM7652o == null || delay == 0) {
            return;
        }
        sendMessageDelayed(obtainMessage(1, keyM7652o.getCode(), repeatCount, pointerTracker), delay);
    }

    @Override // dev.bbkb.ime.keyboard.internal.TimerProxy
    public void cancelKeyRepeatTimer(PointerTracker pointerTracker) {
        removeMessages(1, pointerTracker);
    }

    private void cancelAllKeyRepeatTimers() {
        removeMessages(1);
    }

    @Override // dev.bbkb.ime.keyboard.internal.TimerProxy
    public void startLongPressTimer(PointerTracker pointerTracker, int delay) {
        Key keyM7652o = pointerTracker.getKey();
        if (keyM7652o == null) {
            return;
        }
        sendMessageDelayed(obtainMessage(keyM7652o.getCode() == -1 ? 3 : 2, pointerTracker), delay);
    }

    @Override // dev.bbkb.ime.keyboard.internal.TimerProxy
    public void cancelLongPressTimers(PointerTracker pointerTracker) {
        removeMessages(2, pointerTracker);
        removeMessages(3, pointerTracker);
    }

    @Override // dev.bbkb.ime.keyboard.internal.TimerProxy
    public void startLockFocusTimer(PointerTracker pointerTracker, int delay) {
        sendMessageDelayed(obtainMessage(5, pointerTracker), delay);
    }

    private void cancelLockFocusTimer(PointerTracker pointerTracker) {
        removeMessages(5, pointerTracker);
    }

    @Override // dev.bbkb.ime.keyboard.internal.TimerProxy
    public void cancelLongPressShiftKeyTimer() {
        removeMessages(3);
    }

    public void cancelAllLongPressTimers() {
        removeMessages(2);
        removeMessages(3);
    }

    @Override // dev.bbkb.ime.keyboard.internal.TimerProxy
    public void startTypingStateTimer(Key key) {
        if (key.isModifierKey() || key.isAltCodeWhileTyping()) {
            return;
        }
        boolean zMo7114e = isTypingState();
        removeMessages(0);
        Callbacks aVarV = getOwner();
        if (aVarV == null) {
            return;
        }
        int iM6232c = key.getCode();
        if (iM6232c == 32 || iM6232c == 10) {
            if (zMo7114e) {
                aVarV.startWhileTypingFadeinAnimation();
            }
        } else {
            sendMessageDelayed(obtainMessage(0), this.mTypingStateTimeout);
            if (zMo7114e) {
                return;
            }
            aVarV.startWhileTypingFadeoutAnimation();
        }
    }

    @Override // dev.bbkb.ime.keyboard.internal.TimerProxy
    public boolean isTypingState() {
        return hasMessages(0);
    }

    public void startDoubleTapShiftKeyTimer() {
        sendMessageDelayed(obtainMessage(4), ViewConfiguration.getDoubleTapTimeout());
    }

    public void cancelDoubleTapShiftKeyTimer() {
        removeMessages(4);
    }

    public boolean isInDoubleTapShiftKeyTimeout() {
        return hasMessages(4);
    }

    @Override // dev.bbkb.ime.keyboard.internal.TimerProxy
    public void cancelKeyTimers(PointerTracker pointerTracker) {
        cancelKeyRepeatTimer(pointerTracker);
        cancelLongPressTimers(pointerTracker);
        cancelLockFocusTimer(pointerTracker);
    }

    private void cancelKeyAndLongPressTimers() {
        cancelAllKeyRepeatTimers();
        cancelAllLongPressTimers();
    }

    public void cancelAllTimers() {
        cancelKeyAndLongPressTimers();
    }
}

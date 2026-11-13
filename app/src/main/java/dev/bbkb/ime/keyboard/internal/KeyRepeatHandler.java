package dev.bbkb.ime.keyboard.internal;

import android.os.Message;
import android.view.KeyEvent;

import dev.bbkb.ime.core.device.state.MetaKeyStateTracker;
import dev.bbkb.ime.core.shared.WeakOwnerHandler;



public class KeyRepeatHandler extends WeakOwnerHandler<KeyRepeatHandler.Callback> {

    private ModifierListener mModifierListener;

    
    public interface Callback {
        /** @param i key code */
        void onKeyRepeat(MetaKeyStateTracker abstractC0918e, int i);
    }

    
    public interface ModifierListener {
        void onModifierKeyRepeatEnd();

        void onModifierKeyRepeatStart();
    }

    /** @param abstractC0918e the owner, held weakly */
    public KeyRepeatHandler(MetaKeyStateTracker abstractC0918e) {
        super(abstractC0918e);
    }

    @Override
    public void handleMessage(Message message) {
        Callback aVarV = getOwner();
        if (aVarV == null) {
            return;
        }
        MetaKeyStateTracker abstractC0918e = (MetaKeyStateTracker) message.obj;
        int i = message.arg1;
        if (message.what != 1) {
            return;
        }
        aVarV.onKeyRepeat(abstractC0918e, i);
        if (isShiftKeyCode(i) && this.mModifierListener != null) {
            this.mModifierListener.onModifierKeyRepeatEnd();
        }
    }

    private static boolean isShiftKeyCode(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_SHIFT_LEFT || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT;
    }

    /**
     * @param abstractC0918e the tracker to call back
     * @param i key code
     * @param i2 delay in ms
     */
    public void startKeyRepeatTimer(MetaKeyStateTracker abstractC0918e, int i, int i2) {
        sendMessageDelayed(obtainMessage(1, i, 0, abstractC0918e), i2);
        if (isShiftKeyCode(i) && this.mModifierListener != null) {
            this.mModifierListener.onModifierKeyRepeatStart();
        }
    }

    public void cancelKeyRepeatTimer() {
        removeMessages(1);
    }

    public void startDoubleTapTimer() {
        sendMessageDelayed(obtainMessage(2), MetaKeyStateTracker.getDoubleTapTimeout());
    }

    public void cancelDoubleTapTimer() {
        removeMessages(2);
    }

    public boolean isInDoubleTapTimeout() {
        return hasMessages(2);
    }

    public void setModifierListener(ModifierListener bVar) {
        this.mModifierListener = bVar;
    }
}

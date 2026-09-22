package dev.bbkb.ime.keyboard.internal;

import android.os.Message;
import android.view.KeyEvent;

import dev.bbkb.ime.core.device.state.MetaKeyStateTracker;
import dev.bbkb.ime.core.shared.WeakOwnerHandler;



public class KeyRepeatHandler extends WeakOwnerHandler<KeyRepeatHandler.Callback> {

    private ModifierListener mModifierListener;

    
    public interface Callback {
        void onKeyRepeat(MetaKeyStateTracker abstractC0918e, int i);
    }

    
    public interface ModifierListener {
        void onModifierKeyRepeatEnd();

        void onModifierKeyRepeatStart();
    }

    public KeyRepeatHandler(MetaKeyStateTracker abstractC0918e) {
        super(abstractC0918e);
    }

    @Override // android.os.Handler
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

    /** {@code mModifierListener} is installed by the optional {@link #setModifierListener}. */
    private static boolean isShiftKeyCode(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_SHIFT_LEFT || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT;
    }

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

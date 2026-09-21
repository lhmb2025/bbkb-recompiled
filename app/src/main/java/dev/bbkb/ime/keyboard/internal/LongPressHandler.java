package dev.bbkb.ime.keyboard.internal;

import android.os.Message;

import dev.bbkb.ime.core.shared.WeakOwnerHandler;
import dev.bbkb.ime.keyboard.Key;



public class LongPressHandler extends WeakOwnerHandler<LongPressHandler.KeyPreviewDismisser> {

    
    /** What the handler's owner must be able to do when a long press fires. */
    public interface KeyPreviewDismisser {
        void dismissKeyPreviewWithoutDelay(Key key);

        void dismissAllKeyPreviews();
    }

    public LongPressHandler(KeyPreviewDismisser aVar) {
        super(aVar);
    }

    @Override // android.os.Handler
    public void handleMessage(Message message) {
        KeyPreviewDismisser aVarV = getOwner();
        if (aVarV != null && message.what == 0) {
            aVarV.dismissKeyPreviewWithoutDelay((Key) message.obj);
        }
    }

    public void postKeyPreviewDismiss(long j, Key key) {
        sendMessageDelayed(obtainMessage(0, key), j);
    }

    private void handleDismiss() {
        removeMessages(0);
        KeyPreviewDismisser aVarV = getOwner();
        if (aVarV == null) {
            return;
        }
        aVarV.dismissAllKeyPreviews();
    }

    public void cancelDismiss() {
        handleDismiss();
    }
}

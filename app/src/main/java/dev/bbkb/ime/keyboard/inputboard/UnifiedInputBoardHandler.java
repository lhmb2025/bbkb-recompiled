package dev.bbkb.ime.keyboard.inputboard;

import android.os.Message;

import dev.bbkb.ime.core.shared.WeakOwnerHandler;



public class UnifiedInputBoardHandler extends WeakOwnerHandler<UnifiedInputBoardHandler.Callback> {

    private long mDelay;

    // Keep the callback weak only: a strong field would let a queued message pin the manager and
    // the IME for the delay. Its owner (UnifiedInputBoardManager) holds it strongly.

    public interface Callback {
        void disableAllKeys();

        void restoreKeyStates();
    }

    /**
     * @param aVar the callback, held weakly
     * @param j delay in ms before key states are restored
     */
    public UnifiedInputBoardHandler(Callback aVar, long j) {
        super(aVar);
        this.mDelay = j;
    }

    @Override
    public void handleMessage(Message message) {
        Callback aVar = getOwner();
        if (aVar == null) {
            // The manager was collected while this message was queued: no keys left to restore.
            return;
        }
        switch (message.what) {
            case 0:
                aVar.disableAllKeys();
                break;
            case 1:
                aVar.restoreKeyStates();
                break;
        }
    }

    public void scheduleKeyStateRestore() {
        if (hasMessages(1)) {
            removeMessages(1);
        } else {
            sendMessage(obtainMessage(0));
        }
        sendMessageDelayed(obtainMessage(1), this.mDelay);
    }

    /** Cancel pending disable (0) and restore (1) messages so a board switch gets no stale restore. */
    public void cancelPendingMessages() {
        removeMessages(0);
        removeMessages(1);
    }
}

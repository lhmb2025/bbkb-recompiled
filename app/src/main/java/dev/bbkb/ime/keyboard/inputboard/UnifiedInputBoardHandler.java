package dev.bbkb.ime.keyboard.inputboard;

import android.os.Message;

import dev.bbkb.ime.core.shared.WeakOwnerHandler;



public class UnifiedInputBoardHandler extends WeakOwnerHandler<UnifiedInputBoardHandler.Callback> {

    private long mDelay;

    // No strong callback field. The original APK (k.java) kept one next to the WeakOwnerHandler
    // reference, which defeated it: a queued message -> this handler -> callback (an inner class of
    // UnifiedInputBoardManager) -> manager -> key view -> IME, pinned for the typing delay. The
    // callback is held strongly by its owner (UnifiedInputBoardManager.uimHandlerCallback) and only
    // weakly here, so it lives exactly as long as the manager that has keys to restore.

    public interface Callback {
        void disableAllKeys();

        void restoreKeyStates();
    }

    public UnifiedInputBoardHandler(Callback aVar, long j) {
        super(aVar);
        this.mDelay = j;
    }

    @Override // android.os.Handler
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

    /**
     * Cancel all pending save (0) and restore (1) messages.
     * Called when a board switch occurs to prevent stale restores from
     * overwriting the new board's key states (UIM-03 fix).
     */
    public void cancelPendingMessages() {
        removeMessages(0);
        removeMessages(1);
    }
}

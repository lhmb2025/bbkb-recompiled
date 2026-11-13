package dev.bbkb.ime.core.keyevent;

import android.util.Log;
import android.util.SparseArray;
import android.view.KeyEvent;

import dev.bbkb.ime.core.shared.Logger;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/** Single source of physical-key hold state; notifies registered callbacks of hold start, continue and end. */
public class KeyHoldHandler {
    
    private static final String TAG = "KeyHoldHandler";

    public interface KeyHoldCallback {
        /** First repeat (repeatCount == 1). */
        void onKeyHoldStart(KeyEvent event, int repeatCount);

        /** Later repeats (repeatCount > 1). */
        void onKeyHoldContinue(KeyEvent event, int repeatCount);

        void onKeyHoldEnd(KeyEvent event);
    }

    private static class KeyHoldState {
        final int keyCode;
        final long downTime;
        boolean holdTriggered;

        KeyHoldState(int keyCode, long downTime) {
            this.keyCode = keyCode;
            this.downTime = downTime;
            this.holdTriggered = false;
        }
    }
    
    private static KeyHoldHandler instance;
    
    private final dev.bbkb.ime.core.device.detection.KeyEventDeviceClassifier deviceManager;

    private final SparseArray<KeyHoldState> activeHolds = new SparseArray<>();

    /**
     * Weak: this is a process-lifetime singleton and AuxBarManager registers on every input-view setup
     * without unregistering, so strong refs would leak each old instance (and its Context).
     */
    private final List<WeakReference<KeyHoldCallback>> callbacks = new ArrayList<>();
    
    private KeyHoldHandler() {
        this.deviceManager = dev.bbkb.ime.core.device.detection.KeyEventDeviceClassifier.getInstance();
    }
    
    public static synchronized KeyHoldHandler getInstance() {
        if (instance == null) {
            instance = new KeyHoldHandler();
        }
        return instance;
    }

    /** Held weakly, see {@link #callbacks}. */
    public void addCallback(KeyHoldCallback callback) {
        if (callback == null) {
            return;
        }
        for (int i = callbacks.size() - 1; i >= 0; i--) {
            KeyHoldCallback existing = callbacks.get(i).get();
            if (existing == null) {
                callbacks.remove(i);
            } else if (existing == callback) {
                return;
            }
        }
        callbacks.add(new WeakReference<>(callback));
    }

    public void removeCallback(KeyHoldCallback callback) {
        for (int i = callbacks.size() - 1; i >= 0; i--) {
            KeyHoldCallback existing = callbacks.get(i).get();
            if (existing == null || existing == callback) {
                callbacks.remove(i);
            }
        }
    }

    /** Call from onKeyDown before other processing. */
    public void processKeyDown(KeyEvent event) {
        if (!deviceManager.isPhysicalKeyboardEvent(event)) {
            return;
        }

        int keyCode = event.getKeyCode();
        int repeatCount = event.getRepeatCount();
        long downTime = event.getDownTime();

        if (repeatCount == 0) {
            activeHolds.put(keyCode, new KeyHoldState(keyCode, downTime));
            // Guarded so the message is not concatenated on every key down.
            if (Logger.isLoggable(TAG, Log.DEBUG)) Logger.debug(TAG, "Key down start: keyCode=" + keyCode);
            return;
        }

        KeyHoldState state = activeHolds.get(keyCode);
        if (state == null || state.downTime != downTime) {
            state = new KeyHoldState(keyCode, downTime);
            activeHolds.put(keyCode, state);
        }

        if (repeatCount == 1 && !state.holdTriggered) {
            state.holdTriggered = true;
            notifyHoldStart(event, repeatCount);
            if (Logger.isLoggable(TAG, Log.DEBUG)) Logger.debug(TAG, "Hold triggered: keyCode=" + keyCode);
        } else if (repeatCount > 1 && state.holdTriggered) {
            notifyHoldContinue(event, repeatCount);
        }
    }

    public void processKeyUp(KeyEvent event) {
        if (!deviceManager.isPhysicalKeyboardEvent(event)) {
            return;
        }

        int keyCode = event.getKeyCode();
        KeyHoldState state = activeHolds.get(keyCode);
        activeHolds.remove(keyCode);

        if (state != null && state.holdTriggered) {
            notifyHoldEnd(event);
            if (Logger.isLoggable(TAG, Log.DEBUG)) Logger.debug(TAG, "Hold ended: keyCode=" + keyCode);
        }
    }

    /** What to invoke on each live callback; also prunes entries whose referent has gone. */
    private interface Delivery {
        void deliver(KeyHoldCallback callback);
    }

    private void notifyAll(String what, Delivery delivery) {
        for (int i = callbacks.size() - 1; i >= 0; i--) {
            KeyHoldCallback callback = callbacks.get(i).get();
            if (callback == null) {
                callbacks.remove(i);
                continue;
            }
            try {
                delivery.deliver(callback);
            } catch (Exception e) {
                Logger.error(TAG, "Error in " + what + " callback: " + e.getMessage());
            }
        }
    }

    private void notifyHoldStart(KeyEvent event, int repeatCount) {
        notifyAll("onKeyHoldStart", callback -> callback.onKeyHoldStart(event, repeatCount));
    }

    private void notifyHoldContinue(KeyEvent event, int repeatCount) {
        notifyAll("onKeyHoldContinue", callback -> callback.onKeyHoldContinue(event, repeatCount));
    }

    private void notifyHoldEnd(KeyEvent event) {
        notifyAll("onKeyHoldEnd", callback -> callback.onKeyHoldEnd(event));
    }
}

package dev.bbkb.ime.core.keyevent;

import android.util.Log;
import android.util.SparseArray;
import android.view.KeyEvent;

import dev.bbkb.ime.core.shared.Logger;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * Unified key hold detection handler.
 * 
 * Consolidates all long-press/hold detection logic that was previously scattered across:
 * - AccentBarController.handlePrintingKey() (repeat count checks)
 * - MultitapEventHandler.onInputEvent() (multitap advance)
 * - SoftwareMultitapHandler.onInputEvent() (more keys cycling)
 * - KeyEventConverter.convertKeyEvent() (repeat count for special keys)
 * 
 * This provides a single source of truth for key hold state and notifies
 * registered callbacks when hold events occur.
 */
public class KeyHoldHandler {
    
    private static final String TAG = "KeyHoldHandler";
    
    /**
     * Callback interface for key hold events.
     */
    public interface KeyHoldCallback {
        /**
         * Called when a key hold is first detected (repeatCount == 1).
         * @param event The KeyEvent that triggered the hold
         * @param repeatCount The repeat count (always 1 for hold start)
         */
        void onKeyHoldStart(KeyEvent event, int repeatCount);
        
        /**
         * Called while a key continues to be held (repeatCount > 1).
         * @param event The KeyEvent
         * @param repeatCount The current repeat count
         */
        void onKeyHoldContinue(KeyEvent event, int repeatCount);
        
        /**
         * Called when a held key is released.
         * @param event The key up event
         */
        void onKeyHoldEnd(KeyEvent event);
    }
    
    /**
     * Internal state for tracking active key holds.
     */
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

    /**
     * Audit DK-19: was a {@code HashMap<Integer, KeyHoldState>} on a path that runs for every
     * physical key down and up — every access autoboxed the keycode and hashed it. A
     * {@link SparseArray} keyed by the int keycode is the right shape here.
     */
    private final SparseArray<KeyHoldState> activeHolds = new SparseArray<>();

    /**
     * Audit DK-2: this handler is a process-lifetime singleton, and
     * {@code AuxBarManager.registerKeyHoldCallback()} is called from
     * {@code InputViewCoordinator} on every input-view setup while its unregister counterpart has
     * no callers repo-wide — so every recreation (rotation, theme change, IME restart) leaked an
     * AuxBarManager (and through it the IME Context), and every leaked instance kept receiving
     * hold callbacks for live key events. The {@code contains} guard in {@link #addCallback} does
     * not help: each recreation is a new, non-equal AuxBarManager.
     *
     * <p>Holding the callbacks weakly makes a stale registration collectable and stops delivery to
     * it. Callers that need deterministic removal should still call {@link #removeCallback}.
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
    
    /**
     * Register a callback to receive key hold events. The reference is held weakly (see the
     * {@link #callbacks} field comment).
     */
    public void addCallback(KeyHoldCallback callback) {
        if (callback == null) {
            return;
        }
        for (int i = callbacks.size() - 1; i >= 0; i--) {
            KeyHoldCallback existing = callbacks.get(i).get();
            if (existing == null) {
                callbacks.remove(i);           // collected — drop the dead entry
            } else if (existing == callback) {
                return;                        // already registered
            }
        }
        callbacks.add(new WeakReference<>(callback));
    }

    /**
     * Unregister a callback.
     */
    public void removeCallback(KeyHoldCallback callback) {
        for (int i = callbacks.size() - 1; i >= 0; i--) {
            KeyHoldCallback existing = callbacks.get(i).get();
            if (existing == null || existing == callback) {
                callbacks.remove(i);
            }
        }
    }
    
    /**
     * Process a key down event for hold detection.
     * Should be called from BlackBerryIME.onKeyDown() BEFORE other processing.
     *
     * @param event The KeyEvent to process
     */
    public void processKeyDown(KeyEvent event) {
        if (!deviceManager.isPhysicalKeyboardEvent(event)) {
            return;
        }

        int keyCode = event.getKeyCode();
        int repeatCount = event.getRepeatCount();
        long downTime = event.getDownTime();

        if (repeatCount == 0) {
            // New key press - start tracking
            activeHolds.put(keyCode, new KeyHoldState(keyCode, downTime));
            // Audit DK-3: the message is concatenated at the CALL SITE, before Logger.debug can
            // reject it, so this was one guaranteed StringBuilder+String allocation per physical
            // key down on the KEY2 typing path. Same shape at the two sites below.
            if (Logger.isLoggable(TAG, Log.DEBUG)) Logger.debug(TAG, "Key down start: keyCode=" + keyCode);
            return;
        }

        KeyHoldState state = activeHolds.get(keyCode);
        if (state == null || state.downTime != downTime) {
            // Orphan repeat event - create new state
            state = new KeyHoldState(keyCode, downTime);
            activeHolds.put(keyCode, state);
        }

        if (repeatCount == 1 && !state.holdTriggered) {
            // First repeat - this is the "hold" trigger point
            state.holdTriggered = true;
            notifyHoldStart(event, repeatCount);
            if (Logger.isLoggable(TAG, Log.DEBUG)) Logger.debug(TAG, "Hold triggered: keyCode=" + keyCode);
        } else if (repeatCount > 1 && state.holdTriggered) {
            // Continued hold
            notifyHoldContinue(event, repeatCount);
        }
    }
    
    /**
     * Process a key up event for hold detection.
     * Should be called from BlackBerryIME.onKeyUp().
     *
     * @param event The KeyEvent to process
     */
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

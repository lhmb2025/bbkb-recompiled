package dev.bbkb.ime.core.shared;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Simple in-app event bus to replace deprecated LocalBroadcastManager.
 * Provides thread-safe event publishing and subscription with automatic
 * main thread delivery for UI updates.
 * 
 * Usage:
 * - Subscribe: InAppEventBus.getInstance().subscribe("action", listener)
 * - Unsubscribe: InAppEventBus.getInstance().unsubscribe("action", listener)
 * - Post: InAppEventBus.getInstance().post("action", extras)
 */
public final class InAppEventBus {

    private static volatile InAppEventBus instance;
    
    private final Map<String, Set<EventListener>> listeners = new ConcurrentHashMap<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * Listener interface for receiving events.
     */
    public interface EventListener {
        /**
         * Called when an event is received.
         * Always called on the main thread.
         * @param action The action/event name
         * @param extras Optional extras bundle (may be null)
         */
        void onEvent(String action, Bundle extras);
    }

    private InAppEventBus() {
        // Private constructor for singleton
    }

    /**
     * Gets the singleton instance of the event bus.
     */
    public static InAppEventBus getInstance() {
        if (instance == null) {
            synchronized (InAppEventBus.class) {
                if (instance == null) {
                    instance = new InAppEventBus();
                }
            }
        }
        return instance;
    }

    /**
     * Subscribe to events with the specified action.
     * @param action The action to listen for
     * @param listener The listener to receive events
     */
    public void subscribe(String action, EventListener listener) {
        if (action == null || listener == null) {
            return;
        }
        // Map.computeIfAbsent is API 24 and minSdk is 23, with no core-library desugaring
        // configured, so it throws NoSuchMethodError on an API-23 device. putIfAbsent is API 1 on
        // ConcurrentMap. Same rule the shim records at LanguagePackStatus.java:39.
        Set<EventListener> set = listeners.get(action);
        if (set == null) {
            final Set<EventListener> created = new CopyOnWriteArraySet<>();
            final Set<EventListener> prior = listeners.putIfAbsent(action, created);
            set = prior != null ? prior : created;
        }
        set.add(listener);
    }

    /**
     * Unsubscribe a listener from the specified action.
     * @param action The action to stop listening for
     * @param listener The listener to remove
     */
    public void unsubscribe(String action, EventListener listener) {
        if (action == null || listener == null) {
            return;
        }
        Set<EventListener> actionListeners = listeners.get(action);
        if (actionListeners != null) {
            actionListeners.remove(listener);
            if (actionListeners.isEmpty()) {
                listeners.remove(action);
            }
        }
    }



    /**
     * Post an event with extras.
     * Listeners are notified on the main thread.
     * @param action The action/event name
     * @param extras Optional extras bundle
     */
    public void post(String action, Bundle extras) {
        if (action == null) {
            return;
        }
        Set<EventListener> actionListeners = listeners.get(action);
        if (actionListeners == null || actionListeners.isEmpty()) {
            return;
        }
        
        // Deliver on main thread
        mainHandler.post(() -> {
            for (EventListener listener : actionListeners) {
                try {
                    listener.onEvent(action, extras);
                } catch (Exception e) {
                    Logger.errorWithException("InAppEventBus", e, 
                            "Error delivering event: " + action);
                }
            }
        });
    }

}

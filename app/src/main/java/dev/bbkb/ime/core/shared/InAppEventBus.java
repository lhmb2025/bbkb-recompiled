package dev.bbkb.ime.core.shared;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/** Thread-safe in-app event bus (in place of LocalBroadcastManager); delivers on the main thread. */
public final class InAppEventBus {

    private static volatile InAppEventBus instance;
    
    private final Map<String, Set<EventListener>> listeners = new ConcurrentHashMap<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface EventListener {
        /** Called on the main thread; {@code extras} may be null. */
        void onEvent(String action, Bundle extras);
    }

    private InAppEventBus() {
        // Singleton.
    }

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

    public void subscribe(String action, EventListener listener) {
        if (action == null || listener == null) {
            return;
        }
        // get, then putIfAbsent: when two subscribers race, the set that lands first is kept.
        Set<EventListener> set = listeners.get(action);
        if (set == null) {
            final Set<EventListener> created = new CopyOnWriteArraySet<>();
            final Set<EventListener> prior = listeners.putIfAbsent(action, created);
            set = prior != null ? prior : created;
        }
        set.add(listener);
    }

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


    /** Posts an event; listeners are notified on the main thread. */
    public void post(String action, Bundle extras) {
        if (action == null) {
            return;
        }
        Set<EventListener> actionListeners = listeners.get(action);
        if (actionListeners == null || actionListeners.isEmpty()) {
            return;
        }

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

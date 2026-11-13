package dev.bbkb.ime.core.device.touch;

import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import dev.bbkb.ime.core.shared.Logger;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Keypad touch source for firmware that hands the pad to the IME (Titan 2 on Android 16 with Scroll
 * assistant on): events reach only a focused IME decor view with an {@link View.OnGenericMotionListener},
 * not {@code onGenericMotionEvent} alone. {@link #attach()} makes the decor view focusable, focuses it
 * and forwards into the IME's pipeline; {@link #detach()} undoes it. Both routes can deliver the same
 * event, so {@link #duplicateResult} / {@link #recordResult} drop the second. Focusing a view inside
 * the IME window does not move window focus, so the app's editor binding is unchanged.
 */
public final class NativeTouchSource implements KeypadTouchSource {

    private static final String TAG = "NativeTouchSource";

    public interface Host {
        /** Null while the window does not exist. */
        @Nullable
        View decorView();

        /** {@code BlackBerryIME.onGenericMotionEvent}. */
        boolean onGenericMotionEvent(@NonNull MotionEvent event);

        /** Asked on each attach. */
        @NonNull
        TouchSourceSelector.Selection selection();
    }

    private final Host host;
    private final MotionEventDeduper deduper = new MotionEventDeduper();
    private final CopyOnWriteArrayList<StatusListener> listeners = new CopyOnWriteArrayList<>();

    private final View.OnGenericMotionListener listener;

    private boolean started = true;
    @Nullable private View attachedView;
    private boolean savedFocusable;
    private boolean savedFocusableInTouchMode;
    @Nullable private TouchSourceStatus status;

    public NativeTouchSource(@NonNull Host host) {
        this.host = host;
        this.listener = (v, event) -> host.onGenericMotionEvent(event);
    }

    @Override
    public void start() {
        started = true;
        publish(host.selection().nativeStatus());
    }

    @Override
    public void stop() {
        started = false;
        detachView();
        publish(TouchSourceStatus.of(TouchSourceStatus.State.IDLE, TouchSourceStatus.Reason.STOPPED));
    }

    /** On input view start / window shown: attach if the selector picks this source, else leave the window alone. */
    public void attach() {
        if (!started) return;
        final TouchSourceSelector.Selection selection = host.selection();
        if (selection.choice != TouchSourceSelector.Choice.NATIVE) {
            detachView();
            publish(selection.nativeStatus());
            return;
        }
        final View decor = host.decorView();
        if (decor == null) {
            publish(selection.nativeStatus());
            return;
        }
        if (attachedView != decor) {
            detachView();
            savedFocusable = decor.isFocusable();
            savedFocusableInTouchMode = decor.isFocusableInTouchMode();
            attachedView = decor;
        }
        decor.setFocusable(true);
        decor.setFocusableInTouchMode(true);
        decor.setOnGenericMotionListener(listener);
        decor.requestFocus();
        // Attached either way so a pad enumerated mid-session is heard at once; ACTIVE only once
        // the OS has the pad.
        publish(selection.padEnumerated
                ? TouchSourceStatus.of(TouchSourceStatus.State.ACTIVE, TouchSourceStatus.Reason.OK)
                : selection.nativeStatus());
    }

    /** On window hidden: remove the listener and restore the decor view's focusability. */
    public void detach() {
        final boolean wasAttached = attachedView != null;
        detachView();
        if (wasAttached && started) {
            publish(host.selection().nativeStatus());
        }
    }

    private void detachView() {
        final View view = attachedView;
        if (view == null) return;
        attachedView = null;
        view.setOnGenericMotionListener(null);
        if (view.isFocused()) view.clearFocus();
        view.setFocusableInTouchMode(savedFocusableInTouchMode);
        view.setFocusable(savedFocusable);
        deduper.clear();
    }

    public boolean isAttached() {
        return attachedView != null;
    }

    /** The earlier answer if {@code event} already came by the other route, else null (always null unattached). */
    @Nullable
    public Boolean duplicateResult(@NonNull MotionEvent event) {
        if (attachedView == null) return null;
        return deduper.seen(event);
    }

    /** No-op while not attached. */
    public void recordResult(@NonNull MotionEvent event, boolean handled) {
        if (attachedView == null) return;
        deduper.record(event, handled);
    }

    @NonNull
    @Override
    public TouchSourceStatus status() {
        final TouchSourceStatus s = status;
        return s != null ? s : host.selection().nativeStatus();
    }

    @Override
    public void addStatusListener(@NonNull StatusListener l) {
        listeners.addIfAbsent(l);
    }

    @Override
    public void removeStatusListener(@NonNull StatusListener l) {
        listeners.remove(l);
    }

    private void publish(@NonNull TouchSourceStatus next) {
        final TouchSourceStatus previous = status;
        status = next;
        KeypadTouchSources.publishNativeStatus(next);
        if (next.equals(previous)) return;
        Logger.info(TAG, "status " + next);
        for (StatusListener l : listeners) {
            l.onStatusChanged(this, next);
        }
    }

    @VisibleForTesting
    @Nullable
    View attachedViewForTest() {
        return attachedView;
    }

    /**
     * Ring of recent events keyed by (device id, action, event time). A ring, not just the last
     * event, because the two routes need not interleave one-for-one.
     */
    static final class MotionEventDeduper {
        private static final int SIZE = 32;
        private final int[] deviceIds = new int[SIZE];
        private final int[] actions = new int[SIZE];
        private final long[] times = new long[SIZE];
        private final boolean[] results = new boolean[SIZE];
        private int count;
        private int next;

        @Nullable
        Boolean seen(@NonNull MotionEvent event) {
            final int deviceId = event.getDeviceId();
            final int action = event.getAction();
            final long time = event.getEventTime();
            for (int i = 0; i < count; i++) {
                if (deviceIds[i] == deviceId && actions[i] == action && times[i] == time) {
                    return results[i];
                }
            }
            return null;
        }

        void record(@NonNull MotionEvent event, boolean handled) {
            deviceIds[next] = event.getDeviceId();
            actions[next] = event.getAction();
            times[next] = event.getEventTime();
            results[next] = handled;
            next = (next + 1) % SIZE;
            if (count < SIZE) count++;
        }

        void clear() {
            count = 0;
            next = 0;
        }
    }
}

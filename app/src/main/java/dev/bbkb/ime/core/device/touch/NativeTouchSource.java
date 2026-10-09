package dev.bbkb.ime.core.device.touch;

import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import dev.bbkb.ime.core.shared.Logger;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The keypad touch source for firmware that hands the pad to the IME itself: on the Titan 2 and
 * Titan 2 Elite with Android 16 and the OEM Scroll assistant on, the pad's events reach a
 * <em>focused</em> IME window decor view that has an {@link View.OnGenericMotionListener}, while
 * {@code InputMethodService.onGenericMotionEvent} alone does not reliably see them.
 *
 * <p>When {@link TouchSourceSelector} picks {@link TouchSourceSelector.Choice#NATIVE} for the
 * active profile, {@link #attach()} (on {@code onStartInputView} and {@code onWindowShown}) makes
 * the IME window's decor view focusable in touch mode, focuses it, and installs a generic-motion
 * listener that forwards into the IME's own {@code onGenericMotionEvent} pipeline;
 * {@link #detach()} (on {@code onWindowHidden}) undoes all three. The IME keeps its own
 * {@code onGenericMotionEvent} entry as well, so the same event can arrive by both routes:
 * {@link #duplicateResult} / {@link #recordResult} drop the second arrival (same device id,
 * action and event time). On every other device — no {@code <touch-keypad>}, or one the selector
 * does not give to this source — none of this runs: the decor view is never touched and the dedupe
 * is a no-op, so the KEY2, the MP01 and the emulators are unchanged.
 *
 * <p>Focus: the decor view is focused inside the IME's own window. Window focus — which window
 * receives key events, and so which editor the IME's InputConnection is bound to — is the app's and
 * is not changed by a view focusing itself inside another window. That needs confirming on a Titan
 * 2 (typing into the editor while the source is attached).
 */
public final class NativeTouchSource implements KeypadTouchSource {

    private static final String TAG = "NativeTouchSource";

    /** What the source needs from the IME. */
    public interface Host {
        /** The IME window's decor view, or null while the window does not exist. */
        @Nullable
        View decorView();

        /** The IME's generic-motion entry ({@code BlackBerryIME.onGenericMotionEvent}). */
        boolean onGenericMotionEvent(@NonNull MotionEvent event);

        /** {@link TouchSourceSelector}'s answer for the active profile, asked on each attach. */
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

    // ── lifecycle ────────────────────────────────────────────────────────────

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

    /**
     * Input view started or window shown: attach if the selector gives the pad to this source.
     * Does nothing at all to the window when it does not.
     */
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
        // Attached either way, so a pad that is enumerated mid-session is heard at once; but it
        // is only ACTIVE once the OS has the pad (on the Titan 2, once Scroll assistant is on).
        publish(selection.padEnumerated
                ? TouchSourceStatus.of(TouchSourceStatus.State.ACTIVE, TouchSourceStatus.Reason.OK)
                : selection.nativeStatus());
    }

    /** Window hidden: remove the listener and give the decor view back as it was. */
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

    // ── dedupe (both routes call the IME's one entry) ────────────────────────

    /**
     * If {@code event} already went through the pipeline by the other route, the answer it got
     * then; otherwise null. Always null while not attached, so it costs nothing elsewhere.
     */
    @Nullable
    public Boolean duplicateResult(@NonNull MotionEvent event) {
        if (attachedView == null) return null;
        return deduper.seen(event);
    }

    /** Remember that {@code event} has been handled, with its answer. No-op while not attached. */
    public void recordResult(@NonNull MotionEvent event, boolean handled) {
        if (attachedView == null) return;
        deduper.record(event, handled);
    }

    // ── status ───────────────────────────────────────────────────────────────

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
     * The last few events through the pipeline, keyed by (device id, action, event time): the
     * same physical event delivered by the decor-view listener and by the IME's own entry.
     * A small ring rather than "the last event", because the two routes need not interleave
     * one-for-one.
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

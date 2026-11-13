package dev.bbkb.ime.core.device.touch;

import android.content.Context;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import dev.bbkb.ime.core.device.config.model.DeviceMatchCriteria;
import dev.bbkb.ime.core.device.config.model.TouchKeypadConfig;
import dev.bbkb.ime.core.device.detection.TouchKeypadInfo;
import dev.bbkb.ime.core.device.touch.shizuku.AxisRange;
import dev.bbkb.ime.core.device.touch.shizuku.DecodedTouch;
import dev.bbkb.ime.core.device.touch.shizuku.ShizukuTouchEngine;
import dev.bbkb.ime.core.device.touch.shizuku.ShizukuTouchErrors;
import dev.bbkb.ime.core.device.touch.shizuku.ShizukuTouchState;
import dev.bbkb.ime.core.device.touch.shizuku.ShizukuTouchStatus;
import dev.bbkb.ime.core.device.touch.shizuku.TouchAction;
import dev.bbkb.ime.core.device.touch.shizuku.TouchDeviceInfo;
import dev.bbkb.ime.core.device.touch.shizuku.TouchDeviceMatcher;
import dev.bbkb.ime.core.shared.Logger;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Keypad touch source for firmware that hides the pad from the IME: reads the pad's evdev node at
 * shell privilege through {@code ShizukuTouchEngine}. Started on the first {@link #onWindowShown()}
 * once {@link TouchSourceSelector} picks SHIZUKU, then runs for the IME's life. The grab follows the
 * window so the ROM keeps its own pad behaviour while BBKB is hidden. Touches become
 * {@code SOURCE_TOUCHPAD} MotionEvents in sensor coordinates with {@link #DEVICE_ID}, registered in
 * {@link SyntheticTouchSources} while streaming (that makes {@code hasTouchKeypad()} true).
 */
public final class ShizukuTouchSource implements KeypadTouchSource {

    private static final String TAG = "ShizukuTouchSource";

    /** Far above the small ids InputManager gives real devices, so it never collides. */
    public static final int DEVICE_ID = 0x5B4B0001;

    public interface Host {
        @NonNull
        Context context();

        /** {@code BlackBerryIME.onGenericMotionEvent}. */
        boolean onGenericMotionEvent(@NonNull MotionEvent event);

        /** Asked on each show. */
        @NonNull
        TouchSourceSelector.Selection selection();

        @Nullable
        TouchKeypadConfig touchKeypad();
    }

    private final Host host;
    private final ShizukuTouchEngineFacade engine;
    private final CopyOnWriteArrayList<StatusListener> listeners = new CopyOnWriteArrayList<>();
    private final ShizukuTouchEngine.TouchListener touches = this::onTouch;
    private final ShizukuTouchEngine.StatusListener statuses = this::onEngineStatus;

    private boolean started = true;
    private boolean running;
    /** {@link #DEVICE_ID} is registered. */
    private boolean streaming;
    /** A DOWN was delivered and its UP or CANCEL not yet. */
    private boolean contactOpen;
    private long contactDownTime;
    private float lastX;
    private float lastY;
    @Nullable private TouchSourceStatus status;

    public ShizukuTouchSource(@NonNull Host host, @NonNull ShizukuTouchEngineFacade engine) {
        this.host = host;
        this.engine = engine;
    }

    @Override
    public void start() {
        started = true;
        publish(currentStatus());
    }

    @Override
    public void stop() {
        started = false;
        stopEngine();
        publish(TouchSourceStatus.of(TouchSourceStatus.State.IDLE, TouchSourceStatus.Reason.STOPPED));
    }

    /**
     * Starts the engine the first time the selector picks this source, and takes the grab. Stops
     * it if the selection moved on (a pad the OS now enumerates goes to the native source).
     */
    public void onWindowShown() {
        if (!started) return;
        final TouchSourceSelector.Selection selection = host.selection();
        if (selection.choice != TouchSourceSelector.Choice.SHIZUKU) {
            if (running) {
                stopEngine();
                publish(currentStatus());
            }
            return;
        }
        if (running) {
            engine.setGrab(true);
            return;
        }
        final TouchDeviceMatcher matcher = matcherFor(host.touchKeypad());
        if (matcher == null) {
            publish(TouchSourceStatus.of(TouchSourceStatus.State.UNAVAILABLE,
                    TouchSourceStatus.Reason.PAD_NOT_NAMED));
            return;
        }
        Logger.info(TAG, "starting the reader on " + matcher);
        running = true;
        // The engine reports its status from inside start(); `running` is already true for it.
        engine.start(host.context(), matcher, true, touches, statuses);
    }

    /** Gives the pad back to the ROM; the stream keeps running. */
    public void onWindowHidden() {
        if (running) engine.setGrab(false);
    }

    private void stopEngine() {
        if (!running) return;
        running = false;
        engine.stop();
        endStream();
    }

    public boolean isRunning() {
        return running;
    }

    private void onEngineStatus(@NonNull ShizukuTouchStatus engineStatus) {
        if (!running) return;
        if (engineStatus.getState() == ShizukuTouchState.STREAMING) {
            beginStream(engineStatus.getDevice());
        } else {
            endStream();
        }
        publish(statusOf(engineStatus));
    }

    private void beginStream(@Nullable TouchDeviceInfo device) {
        final AxisRange x = device != null ? device.getXRange() : null;
        final AxisRange y = device != null ? device.getYRange() : null;
        SyntheticTouchSources.register(TouchKeypadInfo.measured(DEVICE_ID,
                x != null ? x.getMax() : 0f, y != null ? y.getMax() : 0f));
        streaming = true;
    }

    /**
     * Cancels an open contact while the id still counts as the pad, then unregisters it. A later
     * engine CANCEL for that contact finds none and is dropped.
     */
    private void endStream() {
        if (contactOpen) {
            contactOpen = false;
            dispatch(MotionEvent.ACTION_CANCEL, contactDownTime, SystemClock.uptimeMillis(),
                    lastX, lastY);
        }
        if (streaming) {
            streaming = false;
            SyntheticTouchSources.unregister(DEVICE_ID);
        }
    }

    private void onTouch(@NonNull DecodedTouch touch) {
        if (!streaming) return;
        final TouchAction action = touch.getAction();
        if (action == TouchAction.DOWN) {
            contactOpen = true;
            contactDownTime = touch.getDownTimeMs();
        } else if (!contactOpen) {
            return;
        } else if (action == TouchAction.UP || action == TouchAction.CANCEL) {
            contactOpen = false;
        }
        lastX = touch.getX();
        lastY = touch.getY();
        dispatch(action.getMotionEventAction(), touch.getDownTimeMs(), touch.getEventTimeMs(),
                touch.getX(), touch.getY());
    }

    private void dispatch(int action, long downTime, long eventTime, float x, float y) {
        final MotionEvent event = motionEvent(action, downTime, eventTime, x, y);
        try {
            host.onGenericMotionEvent(event);
        } finally {
            event.recycle();
        }
    }

    /** Same shape as KEY2 pad and replay events: pressure 1, size 0.1, {@code SOURCE_TOUCHPAD}. */
    @VisibleForTesting
    @NonNull
    static MotionEvent motionEvent(int action, long downTime, long eventTime, float x, float y) {
        final MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, x, y,
                1.0f, 0.1f, 0, 1f, 1f, DEVICE_ID, 0);
        event.setSource(InputDevice.SOURCE_TOUCHPAD);
        return event;
    }

    /**
     * Matcher for the profile's {@code <input-device>} rule, or null if none or bad. Anchored because
     * profile regexes match the whole name but the engine searches.
     */
    @VisibleForTesting
    @Nullable
    static TouchDeviceMatcher matcherFor(@Nullable TouchKeypadConfig pad) {
        final DeviceMatchCriteria.Rule rule = pad != null ? pad.inputDeviceName : null;
        if (rule == null || rule.pattern() == null || rule.pattern().isEmpty()) return null;
        try {
            return rule.isRegex()
                    ? TouchDeviceMatcher.regex("^(?:" + rule.pattern() + ")$")
                    : TouchDeviceMatcher.exact(rule.pattern());
        } catch (IllegalArgumentException badPattern) {
            Logger.warn(TAG, "unusable pad rule " + rule + ": " + badPattern.getMessage());
            return null;
        }
    }

    /** The engine's state as a {@link TouchSourceStatus}: ACTIVE streaming, IDLE ready, else UNAVAILABLE. */
    @NonNull
    public static TouchSourceStatus statusOf(@NonNull ShizukuTouchStatus engine) {
        switch (engine.getState()) {
            case STREAMING:
                return TouchSourceStatus.of(TouchSourceStatus.State.ACTIVE, TouchSourceStatus.Reason.OK);
            case UNSUPPORTED:
                return unavailable(TouchSourceStatus.Reason.SHIZUKU_OUTDATED);
            case NOT_INSTALLED:
                return unavailable(TouchSourceStatus.Reason.SHIZUKU_NOT_INSTALLED);
            case NOT_RUNNING:
                return unavailable(TouchSourceStatus.Reason.SHIZUKU_NOT_RUNNING);
            case NOT_GRANTED:
                return unavailable(engine.getPermissionDeniedForever()
                        ? TouchSourceStatus.Reason.SHIZUKU_DENIED
                        : TouchSourceStatus.Reason.SHIZUKU_NOT_GRANTED);
            case READY:
            case BOUND:
            default: {
                final String error = engine.getError();
                if (error == null) {
                    return TouchSourceStatus.of(TouchSourceStatus.State.IDLE, TouchSourceStatus.Reason.OK);
                }
                if (error.equals(ShizukuTouchErrors.NOT_FOUND)
                        || error.equals(ShizukuTouchErrors.DEVICE_GONE)) {
                    return unavailable(TouchSourceStatus.Reason.PAD_NOT_FOUND);
                }
                return unavailable(TouchSourceStatus.Reason.READER_FAILED);
            }
        }
    }

    private static TouchSourceStatus unavailable(@NonNull TouchSourceStatus.Reason reason) {
        return TouchSourceStatus.of(TouchSourceStatus.State.UNAVAILABLE, reason);
    }

    @NonNull
    private TouchSourceStatus currentStatus() {
        return host.selection().shizukuStatus(statusOf(engine.status()));
    }

    @NonNull
    @Override
    public TouchSourceStatus status() {
        final TouchSourceStatus s = status;
        return s != null ? s : currentStatus();
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
        if (next.equals(previous)) return;
        Logger.info(TAG, "status " + next);
        for (StatusListener l : listeners) {
            l.onStatusChanged(this, next);
        }
    }
}

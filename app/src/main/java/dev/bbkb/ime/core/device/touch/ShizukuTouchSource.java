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
 * The keypad touch source for firmware that keeps the pad from the IME (the Titan 2 on Android
 * 15, a Titan whose pad is not enumerated): {@link NativeTouchSource}'s twin, reading the pad's
 * evdev node at shell privilege through {@code ShizukuTouchEngine}.
 *
 * <p>When {@link TouchSourceSelector} picks {@link TouchSourceSelector.Choice#SHIZUKU}, the first
 * {@link #onWindowShown()} starts the engine on the profile's {@code <touch-keypad><input-device>}
 * rule and leaves it running for the life of the IME (no rebinding per field). The grab follows
 * the window: held while BBKB's window is shown, so the ROM stops seeing the pad, and released
 * when it hides, so the ROM's own pad behaviour works for every app while BBKB is down.
 * {@link #stop()} (the IME's {@code releaseResources}) ends it.
 *
 * <p>Each decoded touch becomes a {@code SOURCE_TOUCHPAD} MotionEvent in raw sensor coordinates,
 * with the decoder's down and event times and the synthetic {@link #DEVICE_ID}, delivered on the
 * main thread into the IME's own {@code onGenericMotionEvent} entry, so everything downstream
 * treats it exactly like a KEY2 pad event (and drops it, as it does those, while the input view
 * is hidden). While the engine streams, {@link #DEVICE_ID} is registered in
 * {@link SyntheticTouchSources} with the ranges the engine measured, which is what makes
 * {@code DeviceProfile.hasTouchKeypad()} true and gives {@link TouchKeypadGeometry} the real
 * frame; it is unregistered the moment the stream ends.
 *
 * <p>On every device whose profile does not select the reader — the KEY2, the MP01, the emulators,
 * a Titan 2 on Android 16 — none of this runs: the engine is never started.
 */
public final class ShizukuTouchSource implements KeypadTouchSource {

    private static final String TAG = "ShizukuTouchSource";

    /**
     * The device id on every MotionEvent this source makes. InputManager numbers real devices up
     * from a handful per boot, so an id this far out never meets one.
     */
    public static final int DEVICE_ID = 0x5B4B0001;

    /** What the source needs from the IME. */
    public interface Host {
        /** The IME's context, for the engine. */
        @NonNull
        Context context();

        /** The IME's generic-motion entry ({@code BlackBerryIME.onGenericMotionEvent}). */
        boolean onGenericMotionEvent(@NonNull MotionEvent event);

        /** {@link TouchSourceSelector}'s answer for the active profile, asked on each show. */
        @NonNull
        TouchSourceSelector.Selection selection();

        /** The active profile's {@code <touch-keypad>}, or null. */
        @Nullable
        TouchKeypadConfig touchKeypad();
    }

    private final Host host;
    private final ShizukuTouchEngineFacade engine;
    private final CopyOnWriteArrayList<StatusListener> listeners = new CopyOnWriteArrayList<>();
    private final ShizukuTouchEngine.TouchListener touches = this::onTouch;
    private final ShizukuTouchEngine.StatusListener statuses = this::onEngineStatus;

    private boolean started = true;
    /** The engine was started and not stopped since. */
    private boolean running;
    /** {@link #DEVICE_ID} is registered: the engine is streaming. */
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

    // ── lifecycle ────────────────────────────────────────────────────────────

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
     * The IME window is shown: start the engine the first time the selector gives this source
     * the pad, and take the grab. Stops the engine if the selection has moved on (a pad that the
     * OS has since enumerated goes to the native source). Where the reader is not chosen and was
     * never started (every device but a Titan without its native route) this does nothing at all.
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

    /** The IME window is hidden: give the pad back to the ROM. The stream itself keeps running. */
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

    // ── the engine's callbacks (main thread) ─────────────────────────────────

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
     * The stream ended (Shizuku died, the pad vanished, the source stopped): close a contact in
     * progress with a CANCEL while the id still counts as the pad, then unregister it. The
     * engine's own CANCEL for that contact, if it comes after, finds no contact and is dropped.
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
            // A MOVE, UP or CANCEL for a contact this source never opened, or already closed.
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

    /**
     * The MotionEvent for one touch: the same shape the KEY2's pad and the replay receiver
     * produce (pressure 1, size 0.1, {@code SOURCE_TOUCHPAD}), raw sensor coordinates, stamped
     * with {@link #DEVICE_ID}.
     */
    @VisibleForTesting
    @NonNull
    static MotionEvent motionEvent(int action, long downTime, long eventTime, float x, float y) {
        final MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, x, y,
                1.0f, 0.1f, 0, 1f, 1f, DEVICE_ID, 0);
        event.setSource(InputDevice.SOURCE_TOUCHPAD);
        return event;
    }

    // ── the profile's pad rule → the engine's matcher ────────────────────────

    /**
     * The engine's matcher for the profile's {@code <input-device>} rule, or null when there is
     * none (or its regex does not compile). A profile regex matches the whole name, as it does
     * for {@code device-name}; the engine searches, so the regex is anchored here.
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

    // ── status ───────────────────────────────────────────────────────────────

    /**
     * The engine's state in the form every touch source reports: ACTIVE while streaming, IDLE
     * when Shizuku is usable and the stream is not up yet, UNAVAILABLE with what is in the way
     * otherwise. The one mapping from the engine's state; the settings read it through
     * {@link KeypadTouchSources#shizukuStatus()}.
     */
    @NonNull
    public static TouchSourceStatus statusOf(@NonNull ShizukuTouchStatus engine) {
        switch (engine.getState()) {
            case STREAMING:
                return TouchSourceStatus.of(TouchSourceStatus.State.ACTIVE, TouchSourceStatus.Reason.OK);
            case UNSUPPORTED:
                return unavailable(ShizukuTouchErrors.SERVER_TOO_OLD.equals(engine.getError())
                        ? TouchSourceStatus.Reason.SHIZUKU_OUTDATED
                        : TouchSourceStatus.Reason.SHIZUKU_UNSUPPORTED);
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

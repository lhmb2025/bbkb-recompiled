package dev.bbkb.ime.core.device.config.builder;

import android.view.KeyEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The guided-capture state machine: walks {@link CaptureStep} in order, and for each one records
 * the raw {@code (scanCode, keyCode, deviceId)} of the key the user pressed plus the auto-repeat
 * cadence if they held it.
 *
 * <p>It is a plain object with no Android dependencies beyond {@link KeyEvent}'s constants, so the
 * whole capture sequence can be driven from a unit test with synthetic events — which is the only
 * way to test it at all, since the real inputs are a physical keyboard nobody has in CI.
 *
 * <p><b>Why a step does not end at the first key-down.</b> The cadence has to be measured while
 * the key is still held, so a step stays open from the first down until the matching up. If the up
 * never arrives — an accessibility service that consumed the down can easily swallow it, and on
 * the MP01 the Sym key's release is exactly such an event — then a <em>different</em> key's down
 * closes the open step and opens the next one, so the sequence walks forward on downs alone. A
 * repeated down of the same key is the auto-repeat and never advances anything.
 *
 * <p><b>Navigation keys are not capturable.</b> Back, Home, the app switcher, the volume rocker
 * and Power are refused outright: the user has to be able to leave the screen, and on every
 * handset with a physical keyboard at least one of those is a real hardware key that would
 * otherwise be recorded as "this is your Sym key".
 */
public final class GuidedCapture {

    /** Repeat gaps outside this range are dropped as pauses/noise rather than cadence. */
    private static final int MAX_PLAUSIBLE_REPEAT_MS = 2000;

    private final List<CaptureStep> steps;
    private final Map<CaptureStep, CapturedKey> results = new EnumMap<>(CaptureStep.class);

    private int index;

    // The step currently open: a key is down and its cadence is being measured.
    private boolean pending;
    private int pendingScanCode;
    private int pendingKeyCode;
    private int pendingDeviceId;
    private long lastRepeatTime;
    private long repeatTotalMs;
    private int repeatDeltas;
    private int repeatEvents;

    public GuidedCapture() {
        this(java.util.Arrays.asList(CaptureStep.values()));
    }

    public GuidedCapture(@NonNull List<CaptureStep> steps) {
        this.steps = Collections.unmodifiableList(new ArrayList<>(steps));
    }

    /** Every step, in the order they are asked for. */
    @NonNull
    public List<CaptureStep> steps() {
        return steps;
    }

    /** The step awaiting an answer, or null when the sequence is finished. */
    @Nullable
    public CaptureStep current() {
        return index < steps.size() ? steps.get(index) : null;
    }

    public boolean isComplete() {
        return index >= steps.size();
    }

    /** True while a key is held down for the current step. */
    public boolean isKeyHeld() {
        return pending;
    }

    /** How many steps have an answer (a capture or a skip). */
    public int answered() {
        return results.size();
    }

    @Nullable
    public CapturedKey resultFor(@NonNull CaptureStep step) {
        return results.get(step);
    }

    /** Answers so far, in step order. */
    @NonNull
    public List<CapturedKey> results() {
        final List<CapturedKey> out = new ArrayList<>(results.size());
        for (CaptureStep step : steps) {
            final CapturedKey key = results.get(step);
            if (key != null) out.add(key);
        }
        return out;
    }

    /**
     * The captures that can be written as {@code <key>} elements: skipped and unusable steps
     * dropped, and a step naming a key an earlier step already claimed dropped too. A duplicate is
     * the user pressing the same key twice — most often because the firmware gave two bezel keys
     * the same scancode, and emitting both would produce two contradictory roles for one key.
     */
    @NonNull
    public List<CapturedKey> usableResults() {
        final List<CapturedKey> out = new ArrayList<>();
        for (CapturedKey candidate : results()) {
            if (!candidate.isUsable()) continue;
            boolean duplicate = false;
            for (CapturedKey earlier : out) {
                if (earlier.sameKeyAs(candidate)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) out.add(candidate);
        }
        return out;
    }

    /** Steps dropped by {@link #usableResults()} because an earlier step claimed the same key. */
    @NonNull
    public List<CaptureStep> duplicateSteps() {
        final List<CaptureStep> dupes = new ArrayList<>();
        final List<CapturedKey> kept = usableResults();
        for (CapturedKey candidate : results()) {
            if (candidate.isUsable() && !kept.contains(candidate)) {
                dupes.add(candidate.step);
            }
        }
        return dupes;
    }

    // ── event intake ─────────────────────────────────────────────────────────

    /**
     * Feeds one hardware key-down.
     *
     * @param repeatCount the event's own repeat count; 0 is the initial press
     * @param eventTime   the event's timestamp in ms, used only for the cadence
     * @return true when the capture used the event, i.e. the caller should consume it
     */
    public boolean onKeyDown(int scanCode, int keyCode, int deviceId, int repeatCount,
                             long eventTime) {
        if (isComplete() || !isCapturable(keyCode)) {
            return false;
        }
        if (pending && matchesPending(scanCode, keyCode)) {
            if (repeatCount > 0) {
                recordRepeat(eventTime);
            }
            return true;
        }
        if (repeatCount > 0) {
            // An auto-repeat of a key we are not tracking: the tail of a press that closed the
            // previous step. Swallow it rather than letting it open a step of its own.
            return true;
        }
        if (pending) {
            // The previous key's up never arrived. Close it and let this press open the next step.
            commit();
            if (isComplete()) {
                return true;
            }
        }
        pending = true;
        pendingScanCode = scanCode;
        pendingKeyCode = keyCode;
        pendingDeviceId = deviceId;
        lastRepeatTime = 0;
        repeatTotalMs = 0;
        repeatDeltas = 0;
        repeatEvents = 0;
        return true;
    }

    /**
     * Feeds one hardware key-up. The matching up closes the open step; anything else is ignored.
     *
     * @return true when the capture used the event
     */
    public boolean onKeyUp(int scanCode, int keyCode, int deviceId) {
        if (isComplete() || !isCapturable(keyCode)) {
            return false;
        }
        if (pending && matchesPending(scanCode, keyCode)) {
            commit();
            return true;
        }
        // The up of a key whose down this capture already swallowed. Swallow the up too: the
        // system must not see the release of a press it never saw, which for a modifier is
        // exactly how its meta state gets stuck on.
        for (CapturedKey captured : results.values()) {
            if (!captured.skipped && captured.scanCode == scanCode
                    && captured.keyCode == keyCode) {
                return true;
            }
        }
        return false;
    }

    /** Records the current step as skipped and moves on. */
    public void skip() {
        final CaptureStep step = current();
        if (step == null) return;
        pending = false;
        results.put(step, CapturedKey.skipped(step));
        index++;
    }

    /** Drops the answer to the previous step and re-asks it. */
    public void back() {
        if (index == 0) return;
        pending = false;
        index--;
        results.remove(steps.get(index));
    }

    /** Back to the first step with nothing recorded. */
    public void reset() {
        pending = false;
        index = 0;
        results.clear();
    }

    // ── internals ────────────────────────────────────────────────────────────

    private boolean matchesPending(int scanCode, int keyCode) {
        return scanCode == pendingScanCode && keyCode == pendingKeyCode;
    }

    /**
     * The gap between the press and the <em>first</em> repeat is the ROM's initial repeat delay,
     * a different number from the cadence, so only gaps between consecutive repeats are averaged.
     */
    private void recordRepeat(long eventTime) {
        repeatEvents++;
        if (lastRepeatTime > 0) {
            final long delta = eventTime - lastRepeatTime;
            if (delta > 0 && delta <= MAX_PLAUSIBLE_REPEAT_MS) {
                repeatTotalMs += delta;
                repeatDeltas++;
            }
        }
        lastRepeatTime = eventTime;
    }

    private void commit() {
        final CaptureStep step = current();
        pending = false;
        if (step == null) return;
        final int interval = repeatDeltas > 0
                ? (int) (repeatTotalMs / repeatDeltas)
                : CapturedKey.NO_REPEAT;
        results.put(step, CapturedKey.of(step, pendingScanCode, pendingKeyCode, pendingDeviceId,
                interval, repeatEvents));
        index++;
    }

    /**
     * Keys the capture refuses to record. Back is the escape hatch off the screen; the rest are
     * system keys whose presses during capture are accidents.
     */
    private static boolean isCapturable(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_APP_SWITCH:
            case KeyEvent.KEYCODE_POWER:
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                return false;
            default:
                return true;
        }
    }
}

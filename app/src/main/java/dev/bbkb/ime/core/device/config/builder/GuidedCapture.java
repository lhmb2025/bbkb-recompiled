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
 * Guided-capture state machine: walks {@link CaptureStep} in order, recording each pressed key's
 * {@code (scanCode, keyCode, deviceId)} and auto-repeat cadence. Pure Java so tests can drive it.
 *
 * <p>A step stays open from down to matching up so the cadence can be measured. An up can be
 * swallowed (e.g. by an accessibility service), so a different key's down also closes the open
 * step. Navigation and system keys are refused so the user can always leave the screen.
 */
public final class GuidedCapture {

    /** Repeat gaps outside this range are dropped as pauses/noise rather than cadence. */
    private static final int MAX_PLAUSIBLE_REPEAT_MS = 2000;

    private final List<CaptureStep> steps;
    private final Map<CaptureStep, CapturedKey> results = new EnumMap<>(CaptureStep.class);

    private int index;

    // The open step: a key is down and its cadence is being measured.
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

    /** Steps with an answer (a capture or a skip). */
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
     * Captures writable as {@code <key>} elements: skipped, unusable and duplicate keys dropped
     * (firmware can give two bezel keys one scancode; two roles for one key would contradict).
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

    /**
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
            // Repeat tail of a press that closed the previous step: swallow, do not open a step.
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

    /** The matching up closes the open step. Returns true when the capture used the event. */
    public boolean onKeyUp(int scanCode, int keyCode, int deviceId) {
        if (isComplete() || !isCapturable(keyCode)) {
            return false;
        }
        if (pending && matchesPending(scanCode, keyCode)) {
            commit();
            return true;
        }
        // Swallow the up of a down already swallowed: an unseen press's release leaves a modifier's
        // meta state stuck.
        for (CapturedKey captured : results.values()) {
            if (!captured.skipped && captured.scanCode == scanCode
                    && captured.keyCode == keyCode) {
                return true;
            }
        }
        return false;
    }

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

    private boolean matchesPending(int scanCode, int keyCode) {
        return scanCode == pendingScanCode && keyCode == pendingKeyCode;
    }

    /** Averages gaps between consecutive repeats only; press to first repeat is the initial delay. */
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

    /** False for Back (the way off the screen) and system keys whose presses are accidents. */
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

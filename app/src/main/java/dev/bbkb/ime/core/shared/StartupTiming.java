package dev.bbkb.ime.core.shared;

import android.os.SystemClock;

import androidx.annotation.VisibleForTesting;

import dev.bbkb.ime.BuildConfig;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Debug-only phase timer for the two cold paths that matter: {@code BlackBerryIME.onCreate} and
 * the Settings activity's launch.
 *
 * <p>Every phase is one logcat line under the tag {@value #TAG}:
 *
 * <pre>
 *   ime.settings.load = 191 ms (#1)
 *   ime.settings.load = 188 ms (#2)
 * </pre>
 *
 * <p>The occurrence counter is the point of the {@code (#n)} suffix: "this phase ran twice on the
 * cold path" is exactly the kind of claim that is cheap to make and expensive to verify, so the
 * instrumentation counts instead of leaving it to be inferred from two log lines that might have
 * come from two different IME starts.
 *
 * <p><b>Zero cost in release.</b> {@link #ENABLED} is {@code BuildConfig.DEBUG}, a compile-time
 * constant, so every method body below is dead code R8 removes; {@link #begin()} folds to
 * {@code 0L} and the {@code end} calls disappear along with their string concatenations. Callers
 * must still not build an expensive phase name at the call site — pass a literal.
 *
 * <p>Read the timings with:
 * <pre>adb logcat -c &amp;&amp; adb logcat -s STARTUP_TIMING:I</pre>
 */
public final class StartupTiming {

    /** logcat tag. Filter with {@code adb logcat -s STARTUP_TIMING:I}. */
    public static final String TAG = "STARTUP_TIMING";

    private static final boolean ENABLED = BuildConfig.DEBUG;

    /** Per-phase occurrence counters, so a duplicated phase reports itself as such. */
    private static final Map<String, AtomicInteger> OCCURRENCES = new ConcurrentHashMap<>();

    /** Reference point for {@link #mark(String)}; re-armed by {@link #anchor(String)}. */
    private static volatile long sAnchor = ENABLED ? SystemClock.elapsedRealtime() : 0L;

    private StartupTiming() {
    }

    /**
     * Opens a phase. The return value is an opaque token for {@link #end(String, long)}; it is
     * {@code 0} in release, where {@code end} is a no-op anyway.
     */
    public static long begin() {
        return ENABLED ? SystemClock.elapsedRealtime() : 0L;
    }

    /**
     * Closes a phase opened by {@link #begin()} and logs one line with its duration and how many
     * times this phase has run in this process.
     *
     * @param phase dotted phase name, e.g. {@code "ime.onCreate.deviceProfile"}. Use a literal.
     * @param token the value {@link #begin()} returned.
     */
    public static void end(String phase, long token) {
        if (!ENABLED) {
            return;
        }
        final long elapsed = SystemClock.elapsedRealtime() - token;
        Logger.info(TAG, phase + " = " + elapsed + " ms (#" + bump(phase) + ")");
    }

    /**
     * As {@link #end(String, long)}, but silent when the phase came in under {@code minMs}. For
     * instrumenting a path with many small steps where only the slow ones are interesting; the
     * occurrence counter still advances so the {@code (#n)} numbering stays truthful.
     */
    public static void endIfOver(String phase, long token, long minMs) {
        if (!ENABLED) {
            return;
        }
        final long elapsed = SystemClock.elapsedRealtime() - token;
        final int n = bump(phase);
        if (elapsed >= minMs) {
            Logger.info(TAG, phase + " = " + elapsed + " ms (#" + n + ")");
        }
    }

    /** Logs a point in time relative to the current anchor, for phases with no natural end. */
    public static void mark(String phase) {
        if (!ENABLED) {
            return;
        }
        Logger.info(TAG, phase + " @ " + (SystemClock.elapsedRealtime() - sAnchor)
                + " ms after anchor (#" + bump(phase) + ")");
    }

    /**
     * Re-arms the reference point {@link #mark(String)} reports against, and logs the new anchor
     * so a reader can tell two cold starts apart in one logcat capture.
     */
    public static void anchor(String name) {
        if (!ENABLED) {
            return;
        }
        sAnchor = SystemClock.elapsedRealtime();
        Logger.info(TAG, "--- anchor: " + name + " ---");
    }

    /** How many times {@code phase} has been closed in this process. Test seam. */
    @VisibleForTesting
    public static int occurrences(String phase) {
        final AtomicInteger counter = OCCURRENCES.get(phase);
        return counter == null ? 0 : counter.get();
    }

    /** Clears the occurrence counters. Test seam; production never calls this. */
    @VisibleForTesting
    public static void resetForTest() {
        OCCURRENCES.clear();
    }

    private static int bump(String phase) {
        AtomicInteger counter = OCCURRENCES.get(phase);
        if (counter == null) {
            // putIfAbsent, not put: two startup threads can close the same phase concurrently
            // (the background DeviceProfile load and the main thread's join, for one).
            final AtomicInteger created = new AtomicInteger();
            counter = OCCURRENCES.putIfAbsent(phase, created);
            if (counter == null) {
                counter = created;
            }
        }
        return counter.incrementAndGet();
    }
}

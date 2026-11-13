package dev.bbkb.ime.core.shared;

import android.os.SystemClock;

import androidx.annotation.VisibleForTesting;

import dev.bbkb.ime.BuildConfig;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Debug-only phase timer for IME {@code onCreate} and the Settings launch. One logcat line per
 * phase, e.g. {@code ime.settings.load = 191 ms (#2)}, where {@code (#n)} counts runs in this
 * process so duplicated phases show. {@link #ENABLED} is a compile-time constant, so R8 strips it
 * all in release; pass literal phase names. Read with
 * {@code adb logcat -s STARTUP_TIMING:I}.
 */
public final class StartupTiming {
    public static final String TAG = "STARTUP_TIMING";

    private static final boolean ENABLED = BuildConfig.DEBUG;

    private static final Map<String, AtomicInteger> OCCURRENCES = new ConcurrentHashMap<>();

    /** Reference point for {@link #mark(String)}; re-armed by {@link #anchor(String)}. */
    private static volatile long sAnchor = ENABLED ? SystemClock.elapsedRealtime() : 0L;

    private StartupTiming() {
    }

    /** Opens a phase; returns a token for {@link #end(String, long)} (0 in release). */
    public static long begin() {
        return ENABLED ? SystemClock.elapsedRealtime() : 0L;
    }

    /** Closes a phase opened by {@link #begin()} and logs its duration and occurrence count. */
    public static void end(String phase, long token) {
        if (!ENABLED) {
            return;
        }
        final long elapsed = SystemClock.elapsedRealtime() - token;
        Logger.info(TAG, phase + " = " + elapsed + " ms (#" + bump(phase) + ")");
    }

    /** As {@link #end(String, long)}, but silent under {@code minMs}; the counter still advances. */
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

    /** Re-arms the {@link #mark(String)} reference point and logs it, to tell cold starts apart. */
    public static void anchor(String name) {
        if (!ENABLED) {
            return;
        }
        sAnchor = SystemClock.elapsedRealtime();
        Logger.info(TAG, "--- anchor: " + name + " ---");
    }

    /** Logs a point relative to process start, to show time spent before the IME service exists. */
    public static void markSinceProcessStart(String phase) {
        if (!ENABLED) {
            return;
        }
        final long start = android.os.Process.getStartElapsedRealtime();
        if (start <= 0L) {
            return;
        }
        Logger.info(TAG, phase + " @ " + (SystemClock.elapsedRealtime() - start)
                + " ms after process start (#" + bump(phase) + ")");
    }

    /** Test seam: how many times {@code phase} has been closed in this process. */
    @VisibleForTesting
    public static int occurrences(String phase) {
        final AtomicInteger counter = OCCURRENCES.get(phase);
        return counter == null ? 0 : counter.get();
    }

    /** Test seam: clears the occurrence counters. */
    @VisibleForTesting
    public static void resetForTest() {
        OCCURRENCES.clear();
    }

    private static int bump(String phase) {
        AtomicInteger counter = OCCURRENCES.get(phase);
        if (counter == null) {
            // putIfAbsent: two startup threads can close the same phase concurrently.
            final AtomicInteger created = new AtomicInteger();
            counter = OCCURRENCES.putIfAbsent(phase, created);
            if (counter == null) {
                counter = created;
            }
        }
        return counter.incrementAndGet();
    }
}

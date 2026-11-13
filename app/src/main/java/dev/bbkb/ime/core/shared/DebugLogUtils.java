package dev.bbkb.ime.core.shared;



public final class DebugLogUtils {

    private static final String TAG = "DebugLogUtils";

    private DebugLogUtils() {
    }

    private static final int DEFAULT_DEPTH = 32;

    public static String getStackTrace() {
        return getStackTrace(DEFAULT_DEPTH);
    }

    public static String getStackTrace(int depth) {
        final StringBuilder sb = new StringBuilder();
        // Frame 0 is Thread.getStackTrace, frame 1 is this method: start at the caller.
        final StackTraceElement[] frames = Thread.currentThread().getStackTrace();
        for (int i = 2; i < frames.length && i < depth + 2; i++) {
            sb.append(frames[i]).append('\n');
        }
        return sb.toString();
    }
}

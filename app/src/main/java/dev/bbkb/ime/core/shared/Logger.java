package dev.bbkb.ime.core.shared;

import android.os.Build;
import android.util.Log;

import dev.bbkb.ime.BuildConfig;



/**
 * BuildConfig.DEBUG-gated wrapper around {@link Log}. {@link #isLoggable} tests DEBUG first so R8
 * can delete every body in release. Arguments are still built at the call site, so hot callers
 * must guard with {@code if (BuildConfig.DEBUG)} or {@code Logger.isLoggable(TAG, Log.DEBUG)}.
 */
public final class Logger {

    private static final int minLogLevel;

    static {
        minLogLevel = ("eng".equals(Build.TYPE) || "userdebug".equals(Build.TYPE)) ? Log.VERBOSE : Log.INFO;
    }

    /**
     * @param tag log tag
     * @param level {@link Log} priority level
     */
    public static boolean isLoggable(String tag, int level) {
        if (!BuildConfig.DEBUG) {
            return false;
        }
        if (minLogLevel > level) {
            return false;
        }
        return Log.isLoggable(tag, level);
    }

    public static int verbose(String tag, String message) {
        if (!isLoggable(tag, Log.VERBOSE)) {
            return 0;
        }
        return Log.v(tag, message);
    }

    public static int debug(String tag, String message) {
        if (!isLoggable(tag, Log.DEBUG)) {
            return 0;
        }
        return Log.d(tag, message);
    }

    public static int debugWithException(String tag, Throwable exception, String message) {
        if (!isLoggable(tag, Log.DEBUG)) {
            return 0;
        }
        return Log.d(tag, message, exception);
    }

    public static int info(String tag, String message) {
        if (!isLoggable(tag, Log.INFO)) {
            return 0;
        }
        return Log.i(tag, message);
    }

    public static int warn(String tag, String message) {
        if (!isLoggable(tag, Log.WARN)) {
            return 0;
        }
        return Log.w(tag, message);
    }

    public static int error(String tag, String message) {
        if (!isLoggable(tag, Log.ERROR)) {
            return 0;
        }
        return Log.e(tag, message);
    }

    public static int errorWithException(String tag, Throwable exception, String message) {
        if (!isLoggable(tag, Log.ERROR)) {
            return 0;
        }
        return Log.e(tag, message, exception);
    }
}

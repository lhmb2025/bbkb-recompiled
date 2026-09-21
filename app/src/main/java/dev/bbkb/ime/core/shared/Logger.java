package dev.bbkb.ime.core.shared;

import android.os.Build;
import android.util.Log;

import dev.bbkb.ime.BuildConfig;



/**
 * BuildConfig.DEBUG-gated wrapper around {@link Log}.
 *
 * <p>GX-21: the gate order matters. {@link #isLoggable} tests {@code BuildConfig.DEBUG} FIRST, so
 * in a release build neither the {@code minLogLevel} compare nor {@code Log.isLoggable} (a real
 * system-property lookup) runs, and R8 can delete the whole body. Keep it that way. Every method
 * below is a plain {@code isLoggable}-then-log; the redundant inner {@code BuildConfig.DEBUG}
 * checks that used to sit inside each one are gone.
 *
 * <p>What this canNOT save is the argument: {@code Logger.debug(TAG, "x=" + x)} builds its string
 * at the CALL SITE before the method is entered. Hot call sites must guard themselves, either with
 * {@code if (BuildConfig.DEBUG)} or with {@code if (Logger.isLoggable(TAG, Log.DEBUG))}.
 */
public final class Logger {

    private static final int minLogLevel;

    static {
        // The old gate was HardwareProbe.isDalvikVM(); ART still reports "Dalvik" for
        // compatibility, so the "not a Dalvik VM -> silence everything" arm was unreachable on
        // any real device. Every log method below is BuildConfig.DEBUG-gated anyway.
        minLogLevel = ("eng".equals(Build.TYPE) || "userdebug".equals(Build.TYPE)) ? Log.VERBOSE : Log.INFO;
    }

    public static boolean isLoggable(String str, int i) {
        // Check the build type first: in release nothing below logs, so the Log.isLoggable()
        // system-property lookup and the level compare are pure waste.
        if (!BuildConfig.DEBUG) {
            return false;
        }
        if (minLogLevel > i) {
            return false;
        }
        return Log.isLoggable(str, i);
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

package com.blackberry.nuanceshim;

/**
 * Debug logcat dumps of engine internals via libxt9trace.so (see xt9kdb-scaffold/TRACE_RESULT_REGION.md).
 * {@link #dump} runs from NuanceSDK.touchEnd in debug builds. View: adb logcat -s XT9DUMP:I
 */
public final class Xt9Trace {
    static {
        try { System.loadLibrary("xt9trace"); }
        catch (Throwable t) { android.util.Log.e("XT9DUMP", "load failed", t); }
    }

    /** scribble: 0=read-only, 1=zero x/y, 2=zero keyId. */
    public static native void dump(long handle, String tag, int scribble);

    /** Dumps AW's committed symbol buffer (ET9WordSymbInfo @ ctx+0x52). View: adb logcat -s XT9SYMB:I */
    public static native void dumpSymb(long handle, String tag);

    /** Dumps the in-memory NKL (loaded KDB) at ctx+0x60, per-key smart-touch data. View: adb logcat -s XT9NKL:I */
    public static native void dumpNkl(long handle);

    /** Arms a HW write-watchpoint on NKL+off; trigger a loadKeyboardLayout re-parse from Java so the
     *  descriptor writer fires, then reportDescWatch() logs its PC/regs. View: adb logcat -s XT9WP:I */
    public static native void armDescWatch(long handle, int off, int fromBump);
    public static native void reportDescWatch(long handle);

    /** Dumps the blob's trace record (result block). Call before native touchEnd (in-progress record)
     *  and after (post-consumption), since touchEnd consumes and frees it. View: adb logcat -s XT9REC:I */
    public static native void dumpTraceRec(long handle, String phase);

    /** Hexdump len bytes of record[idx] (for the 30 KB internals, ReselectWord path). */
    public static native void dumpHex(long handle, int idx, int len);

    private Xt9Trace() {}
}

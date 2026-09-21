package com.blackberry.nuanceshim;

/**
 * In-app logcat dump of the ET9 trace-result region (libxt9trace.so) — the Frida-free,
 * root-free way to inspect what ProcessTrace writes. See
 * xt9kdb-scaffold/TRACE_RESULT_REGION.md.
 *
 * Called from NuanceSDK.touchEnd (debug builds) right after the native touchEnd returns,
 * with mNativeHandle. View:  adb logcat -s XT9DUMP:I
 */
public final class Xt9Trace {
    static {
        try { System.loadLibrary("xt9trace"); }
        catch (Throwable t) { android.util.Log.e("XT9DUMP", "load failed", t); }
    }

    /** scribble: 0=read-only, 1=zero x/y, 2=zero keyId. */
    public static native void dump(long handle, String tag, int scribble);

    /** W3b: dump AW's committed symbol buffer (ET9WordSymbInfo @ ctx+0x52). View: adb logcat -s XT9SYMB:I */
    public static native void dumpSymb(long handle, String tag);

    /** P3: dump the in-memory NKL (loaded KDB) at ctx+0x60 — per-key smart-touch data. View: adb logcat -s XT9NKL:I */
    public static native void dumpNkl(long handle);

    /** RE: arm a self-process HW write-watchpoint on NKL+off, then (from Java) trigger a real
     *  loadKeyboardLayout re-parse so the descriptor COMPUTER fires; reportDescWatch() logs its PC/regs.
     *  View: adb logcat -s XT9WP:I */
    public static native void armDescWatch(long handle, int off, int fromBump);
    public static native void reportDescWatch(long handle);

    /** S1: dump the blob's trace record (result block) to byte-diff vs the owned writer. Called both
     *  BEFORE native touchEnd (in-progress record, real tag/header) and AFTER (post-consumption state),
     *  since recognition consumes + frees the record inside touchEnd. View: adb logcat -s XT9REC:I */
    public static native void dumpTraceRec(long handle, String phase);

    /** Hexdump len bytes of record[idx] (for the 30 KB internals, ReselectWord path). */
    public static native void dumpHex(long handle, int idx, int len);

    private Xt9Trace() {}
}

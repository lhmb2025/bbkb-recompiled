package dev.bbkb.ime.core.device.touch.shizuku

/**
 * JNI into libbbkbevdev.so (app/src/main/cpp/bbkbevdev/bbkbevdev.c). See that file for the
 * handle lifetime contract.
 *
 * Touched ONLY from [EvdevUserService]'s process: the library is loaded on first use, and the
 * IME process never uses this object. Negative returns are negated errno values.
 */
internal object EvdevNative {

    init {
        System.loadLibrary("bbkbevdev")
    }

    /** sizeof(struct input_event) for this ABI: 24 on arm64. */
    @JvmStatic external fun nativeRecordSize(): Int

    /** EVIOCGNAME of [path], with [out] filled per [EvdevProbeLayout]; null if it can't be read. */
    @JvmStatic external fun nativeProbe(path: String, out: IntArray): String?

    /** A handle (> 0), or -errno; -ESTALE when the node's name is no longer [expectedName]. */
    @JvmStatic external fun nativeOpen(path: String, expectedName: String): Int

    /** Whether the open device stamps events with CLOCK_MONOTONIC (EVIOCSCLOCKID succeeded). */
    @JvmStatic external fun nativeIsMonotonic(handle: Int): Boolean

    /** 0 or -errno (-EBUSY: another client holds the grab). */
    @JvmStatic external fun nativeSetGrab(handle: Int, grab: Boolean): Int

    /** Blocks until records arrive: bytes read (> 0), 0 once woken, or -errno (-ENODEV: gone). */
    @JvmStatic external fun nativeRead(handle: Int, buffer: ByteArray): Int

    /** Makes the current and every later [nativeRead] on [handle] return 0. */
    @JvmStatic external fun nativeWake(handle: Int)

    /** Releases the grab and closes the device. Only after the reader has stopped reading. */
    @JvmStatic external fun nativeClose(handle: Int)
}

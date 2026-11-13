package dev.bbkb.ime.core.device.touch.shizuku

import android.content.Context
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import dev.bbkb.ime.core.shared.Logger
import java.io.File
import kotlin.system.exitProcess

/**
 * Shizuku UserService that opens one input device by name and streams its raw records over
 * [IEvdevCallback]. Runs as shell or root in its own `:evdev` app_process with none of the IME's
 * state: never touch an IME singleton or load libnative-lib.so (only libbbkbevdev.so). One stream
 * at a time; if the IME dies the reader stops and releases the grab. R8 keeps the name and both
 * constructors (proguard-rules.pro): Shizuku instantiates it reflectively.
 */
class EvdevUserService() : IEvdevService.Stub() {

    /** Shizuku v13+ calls this one when present. The context is a bare system-side context. */
    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context) : this()

    private val lock = Any()
    /** Guarded by [lock]. */
    private var session: ReaderSession? = null

    override fun destroy() {
        Logger.info(TAG, "destroy")
        close()
        exitProcess(0)
    }

    override fun listDevices(): List<EvdevDeviceInfo> = scanDevices().map { it.toParcel() }

    override fun open(pattern: String?, isRegex: Boolean, grab: Boolean, callback: IEvdevCallback?): EvdevOpenResult {
        synchronized(lock) {
            closeLocked()
            if (callback == null) return result(null, ShizukuTouchErrors.REMOTE_FAILED)
            val matcher = TouchDeviceMatcher.parse(pattern, isRegex)
                ?: return result(null, ShizukuTouchErrors.BAD_PATTERN)
            var failure = ShizukuTouchErrors.NOT_FOUND
            // The first match in node order; the next one only if that node will not open.
            for (device in scanDevices().filter { matcher.matches(it.name) }) {
                val handle = EvdevNative.nativeOpen(device.path, device.name)
                if (handle < 0) {
                    if (-handle != EvdevCodes.ESTALE) failure = "${ShizukuTouchErrors.OPEN_FAILED}:${-handle}"
                    continue
                }
                var warning: String? = null
                var grabbed = false
                if (grab) {
                    val rc = EvdevNative.nativeSetGrab(handle, true)
                    if (rc == 0) grabbed = true else warning = "${ShizukuTouchErrors.GRAB_FAILED}:${-rc}"
                }
                val opened = device.copy(grabbed = grabbed)
                val reader = ReaderSession(handle, opened, callback, EvdevNative.nativeIsMonotonic(handle))
                if (!reader.linkToClient()) {
                    // The IME died during this very call; nobody to stream to.
                    EvdevNative.nativeClose(handle)
                    return result(null, ShizukuTouchErrors.REMOTE_FAILED)
                }
                session = reader
                reader.start()
                Logger.info(TAG, "open ${opened.describe()} monotonic=${reader.monotonic}" +
                    (warning?.let { " warning=$it" } ?: ""))
                return result(opened.toParcel(), warning)
            }
            Logger.info(TAG, "open $matcher failed: $failure")
            return result(null, failure)
        }
    }

    override fun setGrab(grab: Boolean): Boolean = synchronized(lock) { session?.setGrab(grab) ?: false }

    override fun close() {
        synchronized(lock) { closeLocked() }
    }

    /** Stops the current reader and waits (bounded) until it has closed the device. */
    private fun closeLocked() {
        val reader = session ?: return
        session = null
        reader.stop(REASON_CLOSED)
        reader.awaitFinished(STOP_TIMEOUT_MS)
    }

    private fun result(device: EvdevDeviceInfo?, error: String?) = EvdevOpenResult().also {
        it.device = device
        it.error = error
    }

    /** Every event node this process can open, in node order. */
    private fun scanDevices(): List<TouchDeviceInfo> {
        val nodes = File(EvdevNodes.INPUT_DIR).list() ?: return emptyList()
        val probe = IntArray(EvdevProbeLayout.SIZE)
        return EvdevNodes.sortedEventNodes(nodes.asList()).mapNotNull { node ->
            val path = "${EvdevNodes.INPUT_DIR}/$node"
            EvdevProbeLayout.toDeviceInfo(path, EvdevNative.nativeProbe(path, probe), probe)
        }
    }

    /**
     * One open device and its reader thread, which alone reads and closes the handle (releasing the
     * grab) and reports onClosed. [closeLock] keeps [stop]/[setGrab] off a closed, reusable handle.
     */
    private class ReaderSession(
        private val handle: Int,
        val device: TouchDeviceInfo,
        private val callback: IEvdevCallback,
        val monotonic: Boolean,
    ) : IBinder.DeathRecipient {

        private val closeLock = Any()
        /** Guarded by [closeLock]: nativeClose has run. */
        private var closed = false
        /** Why the owner asked the loop to end; wins over what the loop itself saw. */
        @Volatile private var stopReason: String? = null
        private val thread = Thread(::readLoop, "bbkb-evdev-reader")

        fun linkToClient(): Boolean = try {
            callback.asBinder().linkToDeath(this, 0)
            true
        } catch (dead: RemoteException) {
            false
        }

        fun start() = thread.start()

        fun stop(reason: String) {
            synchronized(closeLock) {
                if (stopReason == null) stopReason = reason
                if (!closed) EvdevNative.nativeWake(handle)
            }
        }

        fun awaitFinished(timeoutMs: Long) {
            if (Thread.currentThread() !== thread) thread.join(timeoutMs)
        }

        fun setGrab(grab: Boolean): Boolean = synchronized(closeLock) {
            !closed && EvdevNative.nativeSetGrab(handle, grab) == 0
        }

        /** The IME process died: stop, which releases the grab. */
        override fun binderDied() {
            stop(REASON_CLIENT_DIED)
        }

        private fun readLoop() {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
            } catch (refused: RuntimeException) {
                // Not every shell context may raise its priority; the default still works.
            }
            val recordSize = EvdevNative.nativeRecordSize()
            val raw = ByteArray(recordSize * READ_RECORDS)
            val pending = PackedEventBuffer()
            var endReason: String? = null
            try {
                while (true) {
                    val n = EvdevNative.nativeRead(handle, raw)
                    if (n == 0) break
                    if (n < 0) {
                        endReason = if (-n == EvdevCodes.ENODEV) ShizukuTouchErrors.DEVICE_GONE
                        else "${ShizukuTouchErrors.READ_ERROR}:${-n}"
                        break
                    }
                    val offsetUs = if (monotonic) 0L
                    else EvdevRecords.realtimeToMonotonicOffsetUs(System.nanoTime(), System.currentTimeMillis())
                    EvdevRecords.parse(raw, n, recordSize, offsetUs, pending)
                    val batch = pending.takeCompleteFrames() ?: continue
                    if (!deliver(batch)) {
                        endReason = REASON_CLIENT_DIED
                        break
                    }
                }
            } catch (unexpected: RuntimeException) {
                Logger.errorWithException(TAG, unexpected, "reader failed on ${device.path}")
                endReason = "${ShizukuTouchErrors.READ_ERROR}:${unexpected.javaClass.simpleName}"
            } finally {
                synchronized(closeLock) {
                    closed = true
                    EvdevNative.nativeClose(handle)
                }
                try {
                    callback.asBinder().unlinkToDeath(this, 0)
                } catch (notLinked: RuntimeException) {
                    // Already unlinked by the death notification.
                }
                val reason = stopReason ?: endReason ?: REASON_CLOSED
                Logger.info(TAG, "closed ${device.path}: $reason")
                if (reason != REASON_CLIENT_DIED) {
                    try {
                        callback.onClosed(reason)
                    } catch (gone: RemoteException) {
                        // The IME is gone too; nothing left to tell.
                    }
                }
            }
        }

        /** False once the IME is gone. A merely full oneway buffer (IME stalled) drops one batch. */
        private fun deliver(batch: LongArray): Boolean = try {
            callback.onEvents(batch)
            true
        } catch (failed: RemoteException) {
            callback.asBinder().isBinderAlive
        }
    }

    companion object {
        private const val TAG = "ShizukuTouch"

        /** onClosed reasons beyond the [ShizukuTouchErrors] ones. */
        const val REASON_CLOSED = "closed"
        private const val REASON_CLIENT_DIED = "client_died"

        /** Records per read(). A frame is 2-8 records, so one read drains a long backlog. */
        private const val READ_RECORDS = 128
        private const val STOP_TIMEOUT_MS = 1000L
    }
}

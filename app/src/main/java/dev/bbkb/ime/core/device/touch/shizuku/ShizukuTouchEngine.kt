package dev.bbkb.ime.core.device.touch.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.os.SystemClock
import dev.bbkb.ime.core.shared.Logger
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The Shizuku evdev touch engine: reads one input device's raw single-contact touches at
 * shell (or root) privilege through Shizuku and hands the IME decoded DOWN/MOVE/UP/CANCEL
 * touches, optionally holding an exclusive grab so the system's own InputReader stops seeing the
 * device. This object is the whole integration surface; nothing else in this package is meant
 * to be called from outside it (apart from the pure model types it hands out).
 *
 * ```
 * ShizukuTouchEngine.start(context, "touch_keypad", grab = true,
 *     listener = { touch -> ... },            // main thread, raw device coordinates
 *     statusListener = { status -> ... })     // main thread
 * ...
 * ShizukuTouchEngine.stop()
 * ```
 *
 * Pieces: [ShizukuStatusTracker] follows Shizuku itself (UNSUPPORTED..READY) from its binder and
 * permission callbacks; the engine binds [EvdevUserService] (a Shizuku UserService in its own
 * `<applicationId>:evdev` process), asks it to [IEvdevService.open] the device by name, and
 * decodes the packed records it streams back with a [TouchStreamDecoder].
 *
 * Recovery, all with [RetryBackoff] (250 ms doubling to 30 s), none of it polling:
 *  - Shizuku restarts: its binder dies (status NOT_RUNNING; a contact in progress is CANCELled),
 *    and when the binder comes back (Shizuku's sticky binder-received listener) the service is
 *    re-bound and the device re-opened by name.
 *  - The device node vanishes (firmware re-enumeration, ENODEV): CANCEL, then discovery by name
 *    is retried until a device with that name is back, wherever its new node is.
 *  - The service process dies: re-bound, then re-opened.
 *  - No device has the name yet: retried (status BOUND, error not_found).
 *
 * Threading: call everything from the main thread (calls from elsewhere are posted to it).
 * Listeners run on the main thread, or on the handler given to [start] for touches; once
 * [stop] has run on the touch handler's thread, that listener is never called again. The
 * binder calls into the reader process run on a private background thread.
 *
 * Release builds carry this engine but nothing starts it until a caller does; the only debug
 * entry point is [ShizukuTouchProbeReceiver].
 */
object ShizukuTouchEngine {

    /** Passed to Shizuku's requestPermission and echoed in its result; any value would do. */
    const val PERMISSION_REQUEST_CODE = 0x5B4B

    private const val TAG = "ShizukuTouch"

    /** Shizuku accepted the bind; a reader process that never connects within this is given up. */
    private const val BIND_TIMEOUT_MS = 15_000L

    /** After the last user lets go, the reader process lingers this long for a quick restart. */
    private const val IDLE_UNBIND_MS = 60_000L

    /** Receives decoded touches. */
    fun interface TouchListener {
        fun onTouch(touch: DecodedTouch)
    }

    /** Receives every status change. */
    fun interface StatusListener {
        fun onStatus(status: ShizukuTouchStatus)
    }

    /** The current status; safe to read from any thread. */
    @JvmStatic
    @Volatile
    var status: ShizukuTouchStatus = ShizukuTouchStatus(ShizukuTouchState.NOT_RUNNING)
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private val io: Handler by lazy {
        Handler(HandlerThread("bbkb-shizuku-touch").apply { start() }.looper)
    }
    private val statusListeners = CopyOnWriteArraySet<StatusListener>()

    // ---- Main-thread state -------------------------------------------------------------------
    private var initialized = false
    private lateinit var facade: ShizukuFacade
    private lateinit var tracker: ShizukuStatusTracker

    /** What the caller asked for, kept across reconnects; null when stopped. */
    private var request: StreamRequest? = null
    /** bind() was called and unbind() not yet. */
    private var explicitBind = false
    private var service: IEvdevService? = null
    private var serviceBinder: IBinder? = null
    private var serviceDeath: IBinder.DeathRecipient? = null
    private var binding = false
    /** The running stream, once open() succeeded. */
    private var session: Session? = null
    /** The callback of an open() still in flight on [io]. */
    private var opening: StreamCallback? = null
    private var lastError: String? = null
    private var retryPending = false
    private val backoff = RetryBackoff()
    private val deviceQueries = ArrayList<(List<TouchDeviceInfo>?) -> Unit>()
    private var queryInFlight = false

    private val retryRunnable = Runnable {
        retryPending = false
        advance()
    }
    private val bindTimeout = Runnable {
        if (!binding) return@Runnable
        Logger.warn(TAG, "reader service did not connect within ${BIND_TIMEOUT_MS}ms")
        unbindQuietly()
        binding = false
        failService(ShizukuTouchErrors.BIND_TIMEOUT)
    }
    private val idleUnbind = Runnable {
        if (!wantsService()) unbindNow()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) = onConnected(binder)
        override fun onServiceDisconnected(name: ComponentName?) {
            // Shizuku reports this from its own death watch; ours ([serviceDeath]) knows which
            // binder died, so only act here if the current one really is dead.
            serviceBinder?.let { if (!it.isBinderAlive) onServiceDied(it) }
        }
    }

    // ---- Public API --------------------------------------------------------------------------

    /**
     * Streams the device named exactly [deviceName] (EVIOCGNAME, e.g. "touch_keypad" on the
     * KEY2, "touchPad" on the Titan 2). Replaces any stream already started. [grab] takes the
     * EVIOCGRAB exclusive grab: while held, the system stops receiving that device's events.
     */
    @JvmStatic
    @JvmOverloads
    fun start(
        context: Context,
        deviceName: String,
        grab: Boolean,
        listener: TouchListener,
        statusListener: StatusListener? = null,
    ) = start(context, TouchDeviceMatcher.exact(deviceName), grab, listener, statusListener, null)

    /** As [start] by name, for the first device (in node order) whose name [deviceName] finds. */
    @JvmStatic
    @JvmOverloads
    fun start(
        context: Context,
        deviceName: Regex,
        grab: Boolean,
        listener: TouchListener,
        statusListener: StatusListener? = null,
    ) = start(context, TouchDeviceMatcher.regex(deviceName), grab, listener, statusListener, null)

    /** The general form; [touchHandler] (default: main) is where [listener] runs. */
    @JvmStatic
    fun start(
        context: Context,
        matcher: TouchDeviceMatcher,
        grab: Boolean,
        listener: TouchListener,
        statusListener: StatusListener?,
        touchHandler: Handler?,
    ) = onMain {
        ensureInit(context)
        if (request != null) stopNow()
        val req = StreamRequest(matcher, grab, listener, statusListener, touchHandler ?: mainHandler)
        request = req
        lastError = null
        backoff.reset()
        cancelRetry()
        Logger.info(TAG, "start $matcher grab=$grab")
        if (!publish()) statusListener?.onStatus(status)
        advance()
    }

    /**
     * Stops the stream and releases the grab. No touch or status reaches the [start] listeners
     * afterwards (no CANCEL either: a caller stopping mid-gesture cleans up its own state). The
     * reader process lingers for a minute so a quick restart skips the process start.
     */
    @JvmStatic
    fun stop() = onMain {
        if (request != null) {
            Logger.info(TAG, "stop")
            stopNow()
        }
    }

    /** Takes or releases the grab on the running stream (and on every reconnect after it). */
    @JvmStatic
    fun setGrab(grab: Boolean) = onMain {
        val req = request ?: return@onMain
        if (req.grab == grab) return@onMain
        req.grab = grab
        applyGrab(grab)
    }

    /** Re-reads Shizuku's state; for a settings screen's onResume (an app install sends no event). */
    @JvmStatic
    fun refreshStatus(context: Context) = onMain {
        ensureInit(context)
        tracker.refresh()
        publish()
    }

    /**
     * Adds a listener for every status change and calls it at once with the current status.
     * Listeners added here outlive [stop]; remove them with [removeStatusListener].
     */
    @JvmStatic
    fun addStatusListener(context: Context, listener: StatusListener) = onMain {
        ensureInit(context)
        if (statusListeners.add(listener)) listener.onStatus(status)
    }

    @JvmStatic
    fun removeStatusListener(listener: StatusListener) {
        statusListeners.remove(listener)
    }

    /**
     * Asks Shizuku to show its permission dialog for this app. Returns false when there is
     * nothing to ask (not NOT_GRANTED) or Shizuku would not ask (denied forever: grant it in the
     * Shizuku app). The answer arrives as a status change.
     */
    @JvmStatic
    fun requestPermission(context: Context): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { requestPermission(context) }
            return status.state == ShizukuTouchState.NOT_GRANTED && !status.permissionDeniedForever
        }
        ensureInit(context)
        return tracker.requestPermission(PERMISSION_REQUEST_CODE)
    }

    /**
     * Binds the reader service ahead of need (status BOUND once connected), e.g. for a settings
     * screen's "connect" action. A running stream binds by itself.
     */
    @JvmStatic
    fun bind(context: Context) = onMain {
        ensureInit(context)
        explicitBind = true
        advance()
    }

    /**
     * Undoes [bind]. When no stream is running the reader process is stopped at once; a running
     * stream keeps it (use [stop] for that).
     */
    @JvmStatic
    fun unbind() = onMain {
        if (!initialized) return@onMain
        explicitBind = false
        if (!wantsService()) unbindNow()
    }

    /**
     * Lists every input device the reader can open, in node order, binding the reader if
     * needed; [callback] gets null when Shizuku is not usable or the reader failed. Main thread.
     */
    @JvmStatic
    fun listDevices(context: Context, callback: (List<TouchDeviceInfo>?) -> Unit) = onMain {
        ensureInit(context)
        if (!tracker.status.isAvailable) {
            callback(null)
            return@onMain
        }
        deviceQueries.add(callback)
        advance()
    }

    // ---- Engine ------------------------------------------------------------------------------

    private class StreamRequest(
        val matcher: TouchDeviceMatcher,
        @Volatile var grab: Boolean,
        val listener: TouchListener,
        val statusListener: StatusListener?,
        val touchHandler: Handler,
    ) {
        /** Cleared by stop(), on the main thread; touch deliveries check it before running. */
        @Volatile var active = true
        /** A failure retrying cannot fix (the pattern itself). */
        var failedForGood = false
    }

    private class Session(val callback: StreamCallback, @Volatile var device: TouchDeviceInfo)

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post { block() }
    }

    private fun ensureInit(context: Context) {
        if (initialized) return
        initialized = true
        facade = ShizukuFacade.create(context, mainHandler)
        tracker = ShizukuStatusTracker(facade) { onAvailabilityChanged(it) }
        tracker.onPermissionResult = { code, granted ->
            if (code == PERMISSION_REQUEST_CODE) Logger.info(TAG, "permission result granted=$granted")
        }
        tracker.attach()
        publish()
    }

    private fun wantsService(): Boolean =
        request != null || explicitBind || deviceQueries.isNotEmpty() || queryInFlight

    /** Moves one step towards what is wanted; every event funnels through here. */
    private fun advance() {
        if (!initialized) return
        if (!tracker.status.isAvailable || !wantsService()) {
            if (!wantsService()) scheduleIdleUnbind()
            publish()
            return
        }
        mainHandler.removeCallbacks(idleUnbind)
        val svc = service
        if (svc == null) {
            if (!binding && !retryPending) bind()
            publish()
            return
        }
        if (deviceQueries.isNotEmpty() && !queryInFlight) runDeviceQueries(svc)
        val req = request
        if (req != null && session == null && opening == null && !retryPending && !req.failedForGood) {
            open(req, svc)
        }
        publish()
    }

    private fun bind() {
        binding = true
        try {
            facade.bindUserService(connection)
        } catch (failed: RuntimeException) {
            Logger.warn(TAG, "bindUserService failed: $failed")
            binding = false
            failService(ShizukuTouchErrors.BIND_FAILED)
            return
        }
        mainHandler.postDelayed(bindTimeout, BIND_TIMEOUT_MS)
    }

    private fun onConnected(binder: IBinder?) {
        mainHandler.removeCallbacks(bindTimeout)
        if (binder == null || service != null && serviceBinder === binder) return
        if (!wantsService()) {
            // A late connection after everyone let go.
            binding = false
            unbindQuietly()
            return
        }
        binding = false
        val death = IBinder.DeathRecipient { mainHandler.post { onServiceDied(binder) } }
        try {
            binder.linkToDeath(death, 0)
        } catch (dead: RemoteException) {
            failService(ShizukuTouchErrors.SERVICE_DIED)
            return
        }
        dropService(null)
        service = IEvdevService.Stub.asInterface(binder)
        serviceBinder = binder
        serviceDeath = death
        cancelRetry()
        backoff.reset()
        if (lastError == ShizukuTouchErrors.BIND_FAILED || lastError == ShizukuTouchErrors.BIND_TIMEOUT ||
            lastError == ShizukuTouchErrors.SERVICE_DIED) lastError = null
        Logger.info(TAG, "reader service connected")
        advance()
    }

    private fun onServiceDied(binder: IBinder) {
        if (binder !== serviceBinder) return
        Logger.warn(TAG, "reader service died")
        dropService(ShizukuTouchErrors.SERVICE_DIED)
        if (wantsService()) scheduleRetry()
        publish()
    }

    /** A bind attempt failed: fail waiting queries, retry if something still wants the service. */
    private fun failService(error: String) {
        lastError = error
        failDeviceQueries()
        if (wantsService()) scheduleRetry()
        publish()
    }

    /** Forgets the service (and the stream on it, CANCELling a contact in progress). */
    private fun dropService(error: String?) {
        serviceBinder?.let { b -> serviceDeath?.let { d -> try { b.unlinkToDeath(d, 0) } catch (_: RuntimeException) {} } }
        service = null
        serviceBinder = null
        serviceDeath = null
        binding = false
        mainHandler.removeCallbacks(bindTimeout)
        endSession(error)
        opening?.closeQuietly()
        opening = null
    }

    private fun open(req: StreamRequest, svc: IEvdevService) {
        val grab = req.grab
        val callback = StreamCallback(req, grab)
        opening = callback
        val pattern = req.matcher.pattern
        val isRegex = req.matcher.isRegex
        io.post {
            val result = try {
                svc.open(pattern, isRegex, grab, callback)
            } catch (failed: RemoteException) {
                null
            } catch (failed: RuntimeException) {
                null
            }
            mainHandler.post { onOpenResult(req, svc, callback, result) }
        }
    }

    private fun onOpenResult(req: StreamRequest, svc: IEvdevService, callback: StreamCallback, result: EvdevOpenResult?) {
        if (opening === callback) opening = null
        if (request !== req || service !== svc) {
            // Stopped, replaced or reconnected meanwhile; whoever did that also closed it.
            callback.closeQuietly()
            return
        }
        val device = result?.device
        if (result == null || device == null) {
            callback.closeQuietly()
            val error = result?.error ?: ShizukuTouchErrors.REMOTE_FAILED
            if (error != lastError) Logger.info(TAG, "open ${req.matcher} failed: $error")
            lastError = error
            if (error == ShizukuTouchErrors.BAD_PATTERN) req.failedForGood = true else scheduleRetry()
            publish()
            return
        }
        val info = device.toTouchDeviceInfo()
        session = Session(callback, info)
        lastError = result.error
        backoff.reset()
        callback.attach(info)
        Logger.info(TAG, "streaming ${info.describe()}" + (result.error?.let { " warning=$it" } ?: ""))
        publish()
        // setGrab() while this open() was in flight: apply it now.
        if (req.grab != callback.grabRequested) applyGrab(req.grab)
    }

    /** Asks the reader to take or release the grab on the running stream. */
    private fun applyGrab(grab: Boolean) {
        val s = session ?: return
        val svc = service ?: return
        io.post {
            val ok = try {
                svc.setGrab(grab)
            } catch (failed: RemoteException) {
                false
            } catch (failed: RuntimeException) {
                false
            }
            mainHandler.post {
                if (session !== s) return@post
                if (ok) s.device = s.device.copy(grabbed = grab)
                lastError = if (ok) null else ShizukuTouchErrors.GRAB_FAILED
                publish()
            }
        }
    }

    /** The reader ended the stream (device gone, read error): CANCEL, then rediscover by name. */
    private fun onStreamClosed(callback: StreamCallback, reason: String) {
        val s = session
        if (s == null || s.callback !== callback) return
        Logger.info(TAG, "stream closed: $reason")
        endSession(reason)
        if (request != null) scheduleRetry()
        publish()
    }

    private fun endSession(error: String?) {
        val s = session ?: return
        session = null
        s.callback.cancelAndClose()
        if (error != null) lastError = error
    }

    private fun stopNow() {
        val req = request ?: return
        req.active = false
        request = null
        cancelRetry()
        backoff.reset()
        val s = session
        session = null
        s?.callback?.closeQuietly()
        val inFlight = opening
        opening = null
        inFlight?.closeQuietly()
        val svc = service
        if (svc != null && (s != null || inFlight != null)) {
            io.post {
                try {
                    svc.close()
                } catch (gone: RemoteException) {
                    // The reader died with it.
                } catch (gone: RuntimeException) {
                }
            }
        }
        lastError = null
        scheduleIdleUnbind()
        publish()
    }

    private fun onAvailabilityChanged(availability: ShizukuTouchStatus) {
        Logger.info(TAG, "shizuku ${availability.describe()}")
        if (!availability.isAvailable) {
            cancelRetry()
            backoff.reset()
            if (service != null || binding) {
                // Shizuku died or access was revoked. A reader process that outlived the server
                // would keep its grab, so tell it to close before forgetting it.
                val svc = service
                if (svc != null && serviceBinder?.isBinderAlive == true) {
                    io.post {
                        try {
                            svc.close()
                        } catch (gone: RemoteException) {
                        } catch (gone: RuntimeException) {
                        }
                    }
                }
                unbindQuietly()
                dropService(null)
            }
            failDeviceQueries()
            publish()
        } else {
            backoff.reset()
            advance()
        }
    }

    private fun runDeviceQueries(svc: IEvdevService) {
        val callbacks = ArrayList(deviceQueries)
        deviceQueries.clear()
        queryInFlight = true
        io.post {
            val devices = try {
                svc.listDevices()?.map { it.toTouchDeviceInfo() }
            } catch (failed: RemoteException) {
                null
            } catch (failed: RuntimeException) {
                null
            }
            mainHandler.post {
                queryInFlight = false
                callbacks.forEach { it(devices) }
                advance()
            }
        }
    }

    private fun failDeviceQueries() {
        if (deviceQueries.isEmpty()) return
        val callbacks = ArrayList(deviceQueries)
        deviceQueries.clear()
        callbacks.forEach { it(null) }
    }

    private fun scheduleRetry() {
        if (retryPending) return
        retryPending = true
        val delay = backoff.nextDelayMs()
        Logger.info(TAG, "retry in ${delay}ms (${lastError ?: "-"})")
        mainHandler.postDelayed(retryRunnable, delay)
    }

    private fun cancelRetry() {
        mainHandler.removeCallbacks(retryRunnable)
        retryPending = false
    }

    private fun scheduleIdleUnbind() {
        mainHandler.removeCallbacks(idleUnbind)
        if (!wantsService() && (service != null || binding)) mainHandler.postDelayed(idleUnbind, IDLE_UNBIND_MS)
    }

    private fun unbindNow() {
        mainHandler.removeCallbacks(idleUnbind)
        if (service == null && !binding) return
        Logger.info(TAG, "unbinding reader service")
        unbindQuietly()
        dropService(null)
        lastError = null
        publish()
    }

    /** remove = true: Shizuku calls destroy() and the reader process exits. */
    private fun unbindQuietly() {
        try {
            facade.unbindUserService(connection, true)
        } catch (gone: RuntimeException) {
            // Shizuku itself is gone; so is the service.
        }
    }

    private fun compose(): ShizukuTouchStatus {
        if (!initialized) return status
        val base = tracker.status
        if (!base.isAvailable) return base
        session?.let { return ShizukuTouchStatus(ShizukuTouchState.STREAMING, lastError, it.device) }
        return ShizukuTouchStatus(if (service != null) ShizukuTouchState.BOUND else ShizukuTouchState.READY, lastError)
    }

    /**
     * Reports the composed status if it changed; returns whether it did. A listener that calls
     * back into the engine can publish a newer status mid-loop, which then wins: the rest of
     * this loop is skipped rather than delivering the older one after it.
     */
    private fun publish(): Boolean {
        val next = compose()
        if (next == status) return false
        status = next
        for (listener in statusListeners) {
            if (status !== next) return true
            listener.onStatus(next)
        }
        if (status === next) request?.statusListener?.onStatus(next)
        return true
    }

    /**
     * The IME end of one open(): decodes on the binder thread that delivers the batch (oneway
     * calls on one binder object arrive one at a time, in order) and posts the touches to the
     * request's handler. Batches that arrive before open() has returned the device's axes wait.
     */
    private class StreamCallback(
        private val request: StreamRequest,
        /** The grab this open() asked for. */
        val grabRequested: Boolean,
    ) : IEvdevCallback.Stub() {

        private val lock = Any()
        private var decoder: TouchStreamDecoder? = null
        private var early: ArrayList<LongArray>? = ArrayList()
        private var closed = false

        override fun onEvents(packed: LongArray?) {
            if (packed == null) return
            synchronized(lock) {
                if (closed) return
                val d = decoder
                if (d == null) {
                    early?.let { if (it.size < MAX_EARLY_BATCHES) it.add(packed) }
                    return
                }
                decodeAndPost(d, packed)
            }
        }

        override fun onClosed(reason: String?) {
            mainHandler.post { onStreamClosed(this, reason ?: EvdevUserService.REASON_CLOSED) }
        }

        fun attach(device: TouchDeviceInfo) = synchronized(lock) {
            if (closed) return@synchronized
            val d = TouchStreamDecoder(device)
            decoder = d
            early?.forEach { decodeAndPost(d, it) }
            early = null
        }

        /** Drops everything from here on, silently. */
        fun closeQuietly() = synchronized(lock) {
            closed = true
            early = null
        }

        /** Ends the stream, delivering a CANCEL if a contact was in progress. */
        fun cancelAndClose() = synchronized(lock) {
            if (closed) return@synchronized
            closed = true
            early = null
            decoder?.cancel(SystemClock.uptimeMillis())?.let { post(listOf(it)) }
        }

        // Posting under [lock] keeps deliveries in decode order across binder threads.
        private fun decodeAndPost(d: TouchStreamDecoder, packed: LongArray) {
            var touches: ArrayList<DecodedTouch>? = null
            d.feed(packed) { touch -> (touches ?: ArrayList<DecodedTouch>(4).also { touches = it }).add(touch) }
            touches?.let { post(it) }
        }

        private fun post(touches: List<DecodedTouch>) {
            request.touchHandler.post {
                if (request.active) touches.forEach { request.listener.onTouch(it) }
            }
        }

        private companion object {
            const val MAX_EARLY_BATCHES = 64
        }
    }
}

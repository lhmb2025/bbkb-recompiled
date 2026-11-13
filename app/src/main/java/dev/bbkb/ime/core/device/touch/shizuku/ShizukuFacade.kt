package dev.bbkb.ime.core.device.touch.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import dev.bbkb.ime.BuildConfig
import rikka.shizuku.Shizuku

/**
 * The slice of Shizuku's static API the touch engine uses, behind an interface so the status
 * logic ([ShizukuStatusTracker]) runs against a fake on the JVM.
 */
interface ShizukuFacade {
    fun isManagerInstalled(): Boolean

    fun pingBinder(): Boolean

    /** A server too old for the permission API and user services. Binder must be alive. */
    fun isPreV11(): Boolean

    /** Binder must be alive. */
    fun isPermissionGranted(): Boolean

    /** Shizuku's shouldShowRequestPermissionRationale(): true after "deny and don't ask again". */
    fun isPermissionDeniedForever(): Boolean

    /** Shizuku shows its own dialog; the answer comes back through [Listener.onPermissionResult]. */
    fun requestPermission(requestCode: Int)

    /** Registers (non-null) or removes (null) the listeners; binder-received is sticky. */
    fun setListener(listener: Listener?)

    /** Shizuku.bindUserService for [EvdevUserService]; callbacks arrive on the main thread. */
    fun bindUserService(connection: ServiceConnection)

    /** [remove] = true also stops the service process. */
    fun unbindUserService(connection: ServiceConnection, remove: Boolean)

    interface Listener {
        fun onBinderReceived()
        fun onBinderDead()
        fun onPermissionResult(requestCode: Int, granted: Boolean)
    }

    companion object {
        const val MANAGER_PACKAGE = "moe.shizuku.privileged.api"

        /** Service process is `<applicationId>:evdev`. */
        const val PROCESS_SUFFIX = "evdev"

        /** Listener callbacks are posted to [handler]. */
        @JvmStatic
        fun create(context: Context, handler: Handler): ShizukuFacade =
            RealShizukuFacade(context.applicationContext, handler)
    }
}

/**
 * Shizuku's public static API only (no reflection, no newProcess). Shizuku throws
 * IllegalStateException when the binder is gone and wraps RemoteException in RuntimeException;
 * callers treat any RuntimeException as "not available right now".
 */
internal class RealShizukuFacade(
    private val context: Context,
    private val handler: Handler,
) : ShizukuFacade {

    private var listener: ShizukuFacade.Listener? = null

    private val binderReceived = Shizuku.OnBinderReceivedListener { listener?.onBinderReceived() }
    private val binderDead = Shizuku.OnBinderDeadListener { listener?.onBinderDead() }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        listener?.onPermissionResult(requestCode, grantResult == PackageManager.PERMISSION_GRANTED)
    }

    /**
     * Shizuku starts this class in a fresh app_process (R8 keeps it, see proguard-rules.pro).
     * daemon(false): dies with this process. version(): a new number restarts a running service,
     * so an upgraded APK never talks to a stale reader.
     */
    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(context.packageName, EvdevUserService::class.java.name))
            .daemon(false)
            .processNameSuffix(ShizukuFacade.PROCESS_SUFFIX)
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    override fun isManagerInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(ShizukuFacade.MANAGER_PACKAGE, 0)
        true
    } catch (missing: PackageManager.NameNotFoundException) {
        false
    }

    override fun pingBinder(): Boolean = Shizuku.pingBinder()
    override fun isPreV11(): Boolean = Shizuku.isPreV11()
    override fun isPermissionGranted(): Boolean =
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    override fun isPermissionDeniedForever(): Boolean = Shizuku.shouldShowRequestPermissionRationale()
    override fun requestPermission(requestCode: Int) = Shizuku.requestPermission(requestCode)

    override fun setListener(listener: ShizukuFacade.Listener?) {
        val wasRegistered = this.listener != null
        this.listener = listener
        if (listener != null && !wasRegistered) {
            Shizuku.addBinderReceivedListenerSticky(binderReceived, handler)
            Shizuku.addBinderDeadListener(binderDead, handler)
            Shizuku.addRequestPermissionResultListener(permissionResult, handler)
        } else if (listener == null && wasRegistered) {
            Shizuku.removeBinderReceivedListener(binderReceived)
            Shizuku.removeBinderDeadListener(binderDead)
            Shizuku.removeRequestPermissionResultListener(permissionResult)
        }
    }

    override fun bindUserService(connection: ServiceConnection) =
        Shizuku.bindUserService(userServiceArgs, connection)

    override fun unbindUserService(connection: ServiceConnection, remove: Boolean) =
        Shizuku.unbindUserService(userServiceArgs, connection, remove)
}

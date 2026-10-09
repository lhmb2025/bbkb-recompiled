package dev.bbkb.ime.core.device.touch.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import androidx.annotation.RequiresApi
import dev.bbkb.ime.BuildConfig
import rikka.shizuku.Shizuku

/**
 * The slice of Shizuku's static API the touch engine uses, behind an interface so the status
 * logic ([ShizukuStatusTracker]) runs against a fake on the JVM.
 *
 * Every method except [sdkInt] may only be called when `sdkInt >= MIN_SDK`: the real
 * implementation exists only from API 24 (the Shizuku api AAR's floor, overridden in the
 * manifest), and [create] hands out a do-nothing facade below it.
 */
interface ShizukuFacade {

    /** Build.VERSION.SDK_INT. */
    val sdkInt: Int

    /** Whether the Shizuku manager app (moe.shizuku.privileged.api) is installed. */
    fun isManagerInstalled(): Boolean

    /** Whether a live Shizuku (or Sui) binder has been received. */
    fun pingBinder(): Boolean

    /** A server too old for the permission API and user services. Binder must be alive. */
    fun isPreV11(): Boolean

    /** Binder must be alive. */
    fun isPermissionGranted(): Boolean

    /** Shizuku's shouldShowRequestPermissionRationale(): true after "deny and don't ask again". */
    fun isPermissionDeniedForever(): Boolean

    /** Shizuku shows its own dialog; the answer comes back through [Listener.onPermissionResult]. */
    fun requestPermission(requestCode: Int)

    /**
     * Registers (non-null) or removes (null) the three Shizuku listeners. The binder-received
     * one is sticky: it fires at once if the binder is already here.
     */
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
        const val MIN_SDK = 24
        const val MANAGER_PACKAGE = "moe.shizuku.privileged.api"

        /** Name suffix of the service process: `<applicationId>:evdev`. */
        const val PROCESS_SUFFIX = "evdev"

        /**
         * The facade for this device. Listener callbacks are posted to [handler]. Below API 24
         * this is [Unsupported], which never loads a Shizuku class.
         */
        @JvmStatic
        fun create(context: Context, handler: Handler): ShizukuFacade =
            if (Build.VERSION.SDK_INT >= MIN_SDK) RealShizukuFacade(context.applicationContext, handler)
            else Unsupported(Build.VERSION.SDK_INT)
    }

    /** Below API 24: reports the SDK level and does nothing else. */
    class Unsupported(override val sdkInt: Int) : ShizukuFacade {
        override fun isManagerInstalled() = false
        override fun pingBinder() = false
        override fun isPreV11() = false
        override fun isPermissionGranted() = false
        override fun isPermissionDeniedForever() = false
        override fun requestPermission(requestCode: Int) {}
        override fun setListener(listener: Listener?) {}
        override fun bindUserService(connection: ServiceConnection) {}
        override fun unbindUserService(connection: ServiceConnection, remove: Boolean) {}
    }
}

/**
 * The real thing: Shizuku's public static API, nothing private, no reflection, no
 * Shizuku.newProcess. Shizuku throws IllegalStateException when the binder is gone and wraps
 * RemoteException in RuntimeException; callers ([ShizukuStatusTracker], the engine) treat any
 * RuntimeException as "not available right now".
 */
@RequiresApi(ShizukuFacade.MIN_SDK)
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
     * Shizuku starts the class named here in a fresh app_process with this APK on its class path
     * (R8 keeps its name and constructors, see proguard-rules.pro). daemon(false): the service
     * dies with this process. version(): a different number restarts a running service, so an
     * upgraded APK never talks to a stale reader.
     */
    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(context.packageName, EvdevUserService::class.java.name))
            .daemon(false)
            .processNameSuffix(ShizukuFacade.PROCESS_SUFFIX)
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    override val sdkInt: Int get() = Build.VERSION.SDK_INT

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

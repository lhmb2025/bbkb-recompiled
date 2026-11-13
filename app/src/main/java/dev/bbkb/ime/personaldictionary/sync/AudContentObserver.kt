package dev.bbkb.ime.personaldictionary.sync

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.provider.UserDictionary
import androidx.annotation.VisibleForTesting
import dev.bbkb.ime.personaldictionary.util.CompletionListener
import dev.bbkb.ime.personaldictionary.util.LogUtil
import dev.bbkb.ime.personaldictionary.PersonalDictionaryUtil
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Observes the UserDictionary provider and schedules one debounced sync per burst of changes. */
@VisibleForTesting
class AudContentObserver(
    private val context: Context,
    private val pdu: PersonalDictionaryUtil,
    handler: Handler?
) : ContentObserver(handler) {

    private val isSyncTaskPending = AtomicBoolean(false)

    /** One scheduler for the observer's lifetime, shut down in [unregister]; the debounce is the only sync throttle. */
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    override fun deliverSelfNotifications(): Boolean {
        return false
    }

    fun register() {
        context.contentResolver.registerContentObserver(UserDictionary.Words.CONTENT_URI, true, this)
    }

    fun unregister() {
        context.contentResolver.unregisterContentObserver(this)
        scheduler.shutdownNow()
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        if (!isSyncTaskPending.compareAndSet(false, true)) {
            LogUtil.d(TAG, "Pending sync event - returning without scheduling sync task")
        } else {
            scheduleSyncTask()
        }
    }

    @VisibleForTesting
    protected fun scheduleSyncTask() {
        LogUtil.d(TAG, "Scheduling delayed sync task")
        try {
            scheduler.schedule(::runSync, DELAY_MS, TimeUnit.MILLISECONDS)
        } catch (e: RejectedExecutionException) {
            // Already unregistered; make sure the flag does not stay latched.
            isSyncTaskPending.set(false)
        }
    }

    private fun runSync() {
        LogUtil.d(TAG, "Starting sync after a delay of ${DELAY_MS}ms")
        isSyncTaskPending.set(false)
        try {
            val queued = AudSyncer.getInstance().syncDifferencesToBasl(
                context,
                pdu,
                object : CompletionListener {
                    override fun complete(success: Boolean) {
                        LogUtil.d(TAG, "Sync completed: $success")
                    }
                }
            )
            if (!queued) {
                LogUtil.w(TAG, "AUD sync queue is full")
            }
        } catch (e: Throwable) {
            LogUtil.e(TAG, "Sync failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "AudContentObserver"
        
        @VisibleForTesting
        const val DELAY_MS: Long = 2000
    }
}

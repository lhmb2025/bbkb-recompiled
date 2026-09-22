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

/**
 * Observes changes in Android's UserDictionary ContentProvider.
 *
 * Schedules delayed sync tasks when external changes are detected,
 * using an atomic flag to avoid redundant sync operations.
 */
@VisibleForTesting
class AudContentObserver(
    private val context: Context,
    private val pdu: PersonalDictionaryUtil,
    handler: Handler?
) : ContentObserver(handler) {

    private val isSyncTaskPending = AtomicBoolean(false)

    /**
     * One scheduler for the observer's whole lifetime, shut down in [unregister].
     *
     * Audit PD-16: a fresh java.util.Timer - i.e. a new non-daemon thread - used to be
     * created per onChange notification just to sleep 2 s, and a Throwable (rather than
     * an Exception) escaping the body skipped its timer.cancel() and leaked the thread
     * permanently. The debounce is the only throttle in front of AudSyncer's sync
     * launches, so a thread per AUD notification burst was exactly the wrong shape.
     */
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

package dev.bbkb.ime.core.settings.backup

import android.app.backup.BackupManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.bbkb.ime.core.engine.NuanceSDKManager
import dev.bbkb.ime.core.shared.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The phone-backup bundle `files/backup/auto.zip`, handed to Android by [dev.bbkb.ime.core.BackupAgent].
 * The keyboard keeps it fresh (daily and after a restore or reset) because the agent's process has
 * no engine and cannot read learned words; the agent only rebuilds a missing or days-old one. A
 * restored bundle becomes `pending-restore.zip`: settings and layouts apply on next start, words
 * wait as `pending-words.zip` until engine and dictionary are up. The on/off choice lives in
 * `backup_state`, a pref file outside any backup; off deletes the bundle.
 */
object AutoBackup {

    private const val TAG = "AutoBackup"

    /** Relative to `filesDir`, as `FileBackupHelper` names its files. */
    const val AUTO_BUNDLE_PATH: String = "backup/auto.zip"
    private const val PENDING_RESTORE_PATH = "backup/pending-restore.zip"
    private const val PENDING_WORDS_PATH = "backup/pending-words.zip"

    private const val STATE_PREFS = "backup_state"
    private const val KEY_INCLUDE = "include_in_phone_backup"
    private const val KEY_AUTO_WRITTEN_AT = "auto_written_at"
    private const val KEY_LAST_PHONE_BACKUP_AT = "last_phone_backup_at"

    /** Age at which the keyboard rewrites the bundle. */
    private const val REFRESH_INTERVAL_MILLIS = 20L * 60 * 60 * 1000
    /** Age at which the agent rewrites it itself, without learned words. */
    private const val AGENT_REWRITE_AFTER_MILLIS = 3L * 24 * 60 * 60 * 1000

    /**
     * Wait after the last input session before a routine refresh: capturing learned words holds
     * the engine lock 65 to 250 ms, which would stall the next field's first keys.
     */
    private const val DEFER_MILLIS = 10_000L
    /** Cap on how far repeated sessions push the refresh back. */
    private const val MAX_DEFER_MILLIS = 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshing = AtomicBoolean(false)

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val deferLock = Any()
    /** Application context (never a service) of the waiting refresh, or null. */
    private var deferredContext: Context? = null
    /** Uptime of the first request the waiting refresh covers. */
    private var deferredSince = 0L

    /** After the delay, waits for main-thread idle before starting. */
    private val deferredRefresh = Runnable {
        Looper.myQueue().addIdleHandler {
            val app = synchronized(deferLock) {
                deferredContext.also { deferredContext = null; deferredSince = 0L }
            }
            if (app != null) startRefresh(app, force = false)
            false
        }
    }

    private fun state(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)

    private fun file(context: Context, path: String): File = File(context.applicationContext.filesDir, path)

    @JvmStatic
    fun isIncludedInPhoneBackup(context: Context): Boolean = state(context).getBoolean(KEY_INCLUDE, true)

    @JvmStatic
    fun setIncludedInPhoneBackup(context: Context, included: Boolean) {
        state(context).edit().putBoolean(KEY_INCLUDE, included).apply()
        if (included) {
            refreshIfStale(context, force = true)
        } else {
            file(context, AUTO_BUNDLE_PATH).delete()
            state(context).edit().remove(KEY_AUTO_WRITTEN_AT).apply()
            BackupManager(context.applicationContext).dataChanged()
        }
    }

    /** Epoch millis of the keyboard's last bundle write, or 0. */
    @JvmStatic
    fun bundleWrittenAt(context: Context): Long = state(context).getLong(KEY_AUTO_WRITTEN_AT, 0L)

    /** Epoch millis of Android's last backup of the bundle, or 0. */
    @JvmStatic
    fun lastPhoneBackupAt(context: Context): Long = state(context).getLong(KEY_LAST_PHONE_BACKUP_AT, 0L)

    /**
     * Rewrites the bundle in the background when stale (or [force]) and notifies the backup service.
     * A routine call is debounced ([DEFER_MILLIS], capped at [MAX_DEFER_MILLIS]) and then waits for
     * main-thread idle so it misses the next field's start; [force] starts at once.
     */
    @JvmStatic
    fun refreshIfStale(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        if (force) {
            startRefresh(app, force = true)
            return
        }
        val delay = synchronized(deferLock) {
            val now = SystemClock.uptimeMillis()
            if (deferredContext == null) deferredSince = now
            deferredContext = app
            (deferredSince + MAX_DEFER_MILLIS - now).coerceIn(0L, DEFER_MILLIS)
        }
        mainHandler.removeCallbacks(deferredRefresh)
        mainHandler.postDelayed(deferredRefresh, delay)
    }

    private fun startRefresh(app: Context, force: Boolean) {
        if (!refreshing.compareAndSet(false, true)) return
        // The checks do disk I/O, so off the main thread.
        scope.launch {
            try {
                if (!isIncludedInPhoneBackup(app)) return@launch
                if (!force && !isStale(app, REFRESH_INTERVAL_MILLIS)) return@launch
                writeBundle(app)
            } catch (e: IOException) {
                Logger.warn(TAG, "phone-backup bundle not written: $e")
            } catch (e: RuntimeException) {
                Logger.warn(TAG, "phone-backup bundle not written: $e")
            } finally {
                refreshing.set(false)
            }
        }
    }

    /** After a restore or reset, when the bundle no longer describes the phone. */
    @JvmStatic
    fun noteDataChanged(context: Context) {
        refreshIfStale(context, force = true)
    }

    private fun isStale(context: Context, maxAgeMillis: Long): Boolean {
        val file = file(context, AUTO_BUNDLE_PATH)
        if (!file.isFile) return true
        val writtenAt = bundleWrittenAt(context)
        return writtenAt <= 0L || System.currentTimeMillis() - writtenAt > maxAgeMillis
    }

    private suspend fun writeBundle(context: Context) {
        val contents = BackupOperations.capture(context, BackupBundle.Selection.ALL)
        BackupOperations.writeTo(file(context, AUTO_BUNDLE_PATH), contents)
        state(context).edit().putLong(KEY_AUTO_WRITTEN_AT, System.currentTimeMillis()).apply()
        BackupManager(context).dataChanged()
        Logger.info(TAG, "phone-backup bundle written (engine ${if (NuanceSDKManager.facts().isDLMReady) "up" else "down"})")
    }

    /**
     * Agent backup thread, before the file is read: rebuild a missing or days-old bundle (without
     * learned words) when backup is on, delete it when off.
     */
    @JvmStatic
    fun prepareForPhoneBackup(context: Context) {
        val app = context.applicationContext
        if (!isIncludedInPhoneBackup(app)) {
            file(app, AUTO_BUNDLE_PATH).delete()
            return
        }
        if (!isStale(app, AGENT_REWRITE_AFTER_MILLIS)) return
        try {
            runBlocking { writeBundle(app) }
        } catch (e: IOException) {
            Logger.warn(TAG, "phone-backup bundle not rebuilt: $e")
        } catch (e: RuntimeException) {
            Logger.warn(TAG, "phone-backup bundle not rebuilt: $e")
        }
    }

    @JvmStatic
    fun onPhoneBackupWritten(context: Context) {
        if (file(context, AUTO_BUNDLE_PATH).isFile) {
            state(context).edit().putLong(KEY_LAST_PHONE_BACKUP_AT, System.currentTimeMillis()).apply()
        }
    }

    /** Queue the restored bundle (another phone's) to be applied once. */
    @JvmStatic
    fun onPhoneRestoreFinished(context: Context) {
        val restored = file(context, AUTO_BUNDLE_PATH)
        if (!restored.isFile) return
        val pending = file(context, PENDING_RESTORE_PATH)
        pending.delete()
        if (!restored.renameTo(pending)) {
            Logger.warn(TAG, "restored bundle could not be queued")
            restored.delete()
        }
    }

    /** App start: apply a restored bundle's settings and layouts in the background, queue its words. */
    @JvmStatic
    fun applyPendingRestore(context: Context) {
        val app = context.applicationContext
        val pending = file(app, PENDING_RESTORE_PATH)
        if (!pending.isFile) return
        scope.launch {
            try {
                val bundle = pending.inputStream().use { BackupBundle.read(it) }.getOrElse {
                    Logger.warn(TAG, "restored bundle refused: ${it.message}")
                    pending.delete()
                    return@launch
                }
                val report = BackupOperations.restore(app, bundle,
                    BackupBundle.Selection(settings = true, layouts = true, words = false))
                Logger.info(TAG, "phone restore applied: ${report.settings} settings, ${report.macros} macros, " +
                    "${report.deviceConfigs} device profiles, ${report.layoutSections} layout sections, " +
                    "${report.letterLayouts} letter layouts")
                if (bundle.hasWords) {
                    val words = file(app, PENDING_WORDS_PATH)
                    words.delete()
                    if (!pending.renameTo(words)) pending.delete()
                } else {
                    pending.delete()
                }
            } catch (e: IOException) {
                Logger.warn(TAG, "restored bundle not applied: $e")
                pending.delete()
            } catch (e: RuntimeException) {
                Logger.warn(TAG, "restored bundle not applied: $e")
                pending.delete()
            }
        }
    }

    /** Adds a waiting restored bundle's words once engine and dictionary are up; cheap otherwise. */
    @JvmStatic
    fun applyPendingWordsIfReady(context: Context) {
        val app = context.applicationContext
        val words = file(app, PENDING_WORDS_PATH)
        // Main thread: read the published fact, not the engine.
        if (!words.isFile || !NuanceSDKManager.facts().isDLMReady) return
        scope.launch {
            try {
                val bundle = words.inputStream().use { BackupBundle.read(it) }.getOrElse {
                    words.delete()
                    return@launch
                }
                val report = BackupOperations.restore(app, bundle,
                    BackupBundle.Selection(settings = false, layouts = false, words = true))
                Logger.info(TAG, "phone restore: ${report.dictionaryAdded} dictionary entries, ${report.learnedAdded} learned words")
                if (!report.learnedSkipped) words.delete()
            } catch (e: IOException) {
                Logger.warn(TAG, "restored words not applied: $e")
                words.delete()
            } catch (e: RuntimeException) {
                Logger.warn(TAG, "restored words not applied: $e")
                words.delete()
            }
        }
    }
}

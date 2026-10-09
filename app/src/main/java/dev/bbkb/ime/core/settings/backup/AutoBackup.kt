package dev.bbkb.ime.core.settings.backup

import android.app.backup.BackupManager
import android.content.Context
import android.content.SharedPreferences
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
 * BBKB's part in the phone's own backup (Settings > Google > Backup, or System > Backup): the
 * same bundle the Backup and restore screen writes, kept fresh in `files/backup/auto.zip` and
 * handed to Android's backup service by [dev.bbkb.ime.core.BackupAgent].
 *
 * ## Why a file, kept by the keyboard
 *
 * The backup agent runs whenever Android decides to back the app up, usually overnight, in a
 * process that may have been started for nothing else. The engine is not loaded there, so the
 * learned words cannot be read then. The keyboard therefore refreshes the bundle itself while it
 * is in use — once a day ([refreshIfStale]) and after a restore or reset ([noteDataChanged]) —
 * and tells the backup service that there is something new ([BackupManager.dataChanged]). The
 * agent only backs the file up; it rebuilds it when it is missing or days old, without the
 * learned words.
 *
 * ## Restore
 *
 * Android restores the file when BBKB is installed on a phone signed in to the same account,
 * before the app first runs. The agent renames it to `pending-restore.zip`; on the next start
 * [applyPendingRestore] puts the settings and layouts back at once and, if the bundle carries
 * words, keeps it as `pending-words.zip` until the keyboard has its engine and dictionary up
 * ([applyPendingWordsIfReady]) — the dictionary is written through its own manager and the
 * learned words through the engine's learn call, neither of which exists before then.
 *
 * ## Switching it off
 *
 * "Include in phone backup" is kept in `backup_state`, a preference file that is not itself in
 * any backup: it is this phone's choice. Off deletes the bundle, and the next backup pass drops
 * it from the backup set.
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

    /** How old the bundle may be before the keyboard rewrites it on its next chance. */
    private const val REFRESH_INTERVAL_MILLIS = 20L * 60 * 60 * 1000
    /** How old it may be before the agent rewrites it itself, without the learned words. */
    private const val AGENT_REWRITE_AFTER_MILLIS = 3L * 24 * 60 * 60 * 1000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshing = AtomicBoolean(false)

    private fun state(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)

    private fun file(context: Context, path: String): File = File(context.applicationContext.filesDir, path)

    // ── the user's choice ────────────────────────────────────────────────────

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

    /** When the keyboard last wrote the bundle, in epoch millis, or 0. */
    @JvmStatic
    fun bundleWrittenAt(context: Context): Long = state(context).getLong(KEY_AUTO_WRITTEN_AT, 0L)

    /** When Android last backed the bundle up, in epoch millis, or 0. */
    @JvmStatic
    fun lastPhoneBackupAt(context: Context): Long = state(context).getLong(KEY_LAST_PHONE_BACKUP_AT, 0L)

    // ── keeping the bundle fresh ─────────────────────────────────────────────

    /**
     * Rewrites the bundle in the background when it is older than a day (or [force]), and tells
     * the backup service. Cheap to call often: it returns at once when there is nothing to do.
     */
    @JvmStatic
    fun refreshIfStale(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        if (!refreshing.compareAndSet(false, true)) return
        // The checks read a preference file and stat the bundle: off the main thread, which is
        // where the keyboard calls this from after every input session.
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

    /** After a restore or a reset: the bundle no longer describes the phone. */
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
        Logger.info(TAG, "phone-backup bundle written (engine ${if (NuanceSDKManager.isDLMReady()) "up" else "down"})")
    }

    // ── the backup agent's side ──────────────────────────────────────────────

    /**
     * Called by the agent on its backup thread, before the file is read: makes sure there is a
     * bundle to back up when the user wants one (rebuilding a missing or days-old one, without
     * the learned words the keyboard would have added), and that there is none when they do not.
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

    /** The agent finished handing data to Android. */
    @JvmStatic
    fun onPhoneBackupWritten(context: Context) {
        if (file(context, AUTO_BUNDLE_PATH).isFile) {
            state(context).edit().putLong(KEY_LAST_PHONE_BACKUP_AT, System.currentTimeMillis()).apply()
        }
    }

    /** Android restored the file set: the bundle in it is another phone's, to be applied once. */
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

    // ── applying a restored bundle ───────────────────────────────────────────

    /**
     * App start: if Android restored a bundle, put its settings and layouts back now and queue
     * its words for [applyPendingWordsIfReady]. Returns at once; the work is in the background.
     */
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
                    "${report.letterMaps} letter maps")
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

    /**
     * The keyboard has its engine and dictionary up: if a restored bundle's words are waiting,
     * add them now. Cheap when nothing is waiting.
     */
    @JvmStatic
    fun applyPendingWordsIfReady(context: Context) {
        val app = context.applicationContext
        val words = file(app, PENDING_WORDS_PATH)
        if (!words.isFile || !NuanceSDKManager.isDLMReady()) return
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

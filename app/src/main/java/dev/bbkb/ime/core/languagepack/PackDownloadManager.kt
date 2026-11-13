package dev.bbkb.ime.core.languagepack

import android.content.Context
import dev.bbkb.ime.core.distribution.DistributionException
import dev.bbkb.ime.core.distribution.Downloader
import dev.bbkb.ime.core.distribution.PackEntry
import dev.bbkb.ime.core.distribution.Packs
import dev.bbkb.ime.core.distribution.TransportException
import dev.bbkb.ime.core.shared.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Process-wide language pack download queue, so a download survives rotation and screen changes
 * (screens observe [states]). A fair [Mutex] runs one download at a time in press order. Each one
 * downloads (size + SHA-256 verified), installs under the manifest's locale (never one parsed from
 * the file name), then deletes the download copy. Failures land in [PackState.Failed] with the
 * layer's exception ([DistributionException] or [PackInstallException]); a cancel just drops the entry.
 */
class PackDownloadManager internal constructor(
    private val context: Context,
    private val downloader: Downloader = Downloader(context),
    private val installer: PackInstallService = PackInstallService(context),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private val _states = MutableStateFlow<Map<String, PackState>>(emptyMap())

    /**
     * Live state per locale this manager has touched; an absent locale means the catalogue's own
     * answer stands. Merge through [PackCatalog.Companion.from], it is not the whole picture.
     */
    val states: StateFlow<Map<String, PackState>> = _states.asStateFlow()

    /** The queue, so a cancel can reach a download that has not started yet. */
    private val jobs = HashMap<String, Job>()

    private val queue = Mutex()

    /**
     * Queue a download and install of [entry] (no-op if already queued); watch [states]. [packs]
     * must be the [Packs] the entry came from, or the URL targets the wrong release. [turnOn] false
     * installs a dictionary wanted only as another keyboard's extra language.
     */
    fun download(packs: Packs, entry: PackEntry, turnOn: Boolean = true) {
        synchronized(jobs) {
            if (jobs.containsKey(entry.locale)) return
            // Show as queued at once, or the row reads as a button that did nothing.
            put(entry.locale, PackState.Downloading(0L, entry.size))
            jobs[entry.locale] = scope.launch {
                try {
                    queue.withLock { run(packs, entry, turnOn) }
                } finally {
                    synchronized(jobs) { jobs.remove(entry.locale) }
                }
            }
        }
    }

    /** Stop a queued or running download and forget it; the pack reads as available, not failed. */
    fun cancel(locale: String) {
        val job = synchronized(jobs) { jobs.remove(locale) }
        job?.cancel()
        clear(locale)
    }

    /** Dismiss a [PackState.Failed] so the row offers Download again. */
    fun clearFailure(locale: String) {
        if (_states.value[locale] is PackState.Failed) clear(locale)
    }

    fun isBusy(): Boolean = synchronized(jobs) { jobs.isNotEmpty() }

    private suspend fun run(packs: Packs, entry: PackEntry, turnOn: Boolean) {
        val destination = downloader.destinationFor(entry.file)
        val job = currentCoroutineContext()[Job]
        try {
            put(entry.locale, PackState.Downloading(0L, entry.size))
            val downloaded = downloader.download(
                url = entry.url(packs),
                expectedSha256 = entry.sha256,
                expectedSize = entry.size,
                destination = destination,
                onProgress = { bytes, total ->
                    // Not after a cancel: the callback can be mid-flight when cancel() clears
                    // the entry, and re-adding it would leave a row downloading forever.
                    if (job?.isActive != false) {
                        put(entry.locale, PackState.Downloading(bytes, if (total > 0L) total else entry.size))
                    }
                },
            ).getOrElse { failure ->
                fail(entry.locale, failure)
                return
            }

            val installed = installer.installFromFile(
                file = downloaded,
                locale = entry.locale,
                displayName = entry.name,
                version = packs.version,
                group = entry.group,
                turnOn = turnOn,
            ).getOrElse { failure ->
                fail(entry.locale, failure)
                return
            }

            put(
                entry.locale,
                PackState.Installed(
                    if (installed.isVariant) PackState.Origin.VARIANT_ACTIVE else PackState.Origin.CUSTOM
                )
            )
            Logger.info(TAG, "Installed ${entry.locale} from the catalogue")
        } catch (cancelled: CancellationException) {
            // Downloader already deleted the partial file; just make sure no state entry
            // survives, whether from cancel() or the scope going down.
            clear(entry.locale)
            throw cancelled
        } catch (unexpected: RuntimeException) {
            fail(entry.locale, TransportException("Could not install ${entry.locale}", unexpected))
        } finally {
            // Always a throwaway copy (the installer copied it into nuance/); several MB each.
            destination.delete()
            File(destination.parentFile, destination.name + ".tmp").delete()
        }
    }

    private fun fail(locale: String, error: Throwable) {
        Logger.warn(TAG, "Pack $locale failed: $error")
        put(locale, PackState.Failed(error))
    }

    private fun put(locale: String, state: PackState) {
        _states.value = _states.value + (locale to state)
    }

    private fun clear(locale: String) {
        _states.value = _states.value - locale
    }

    companion object {
        private const val TAG = "PackDownload"

        @Volatile
        private var instance: PackDownloadManager? = null

        @JvmStatic
        fun getInstance(context: Context): PackDownloadManager {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: PackDownloadManager(context.applicationContext).also { instance = it }
            }
        }

        /** Tests only: drop the singleton so one test's queue is not the next one's start state. */
        @JvmStatic
        fun resetForTests() {
            synchronized(this) { instance = null }
        }
    }
}

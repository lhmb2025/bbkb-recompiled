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
 * The download queue for language packs: one at a time, process-wide, and not owned by a screen.
 *
 * ### Why a singleton and not a ViewModel
 *
 * A pack is 0.2 MB (Welsh) to 7 MB (Chinese Taiwan) over whatever connection the phone has. That
 * is long enough for the user to rotate the phone, walk to another settings screen, or answer a
 * message — all of which destroy a composable. So the download lives here, in a process-wide
 * object holding a [StateFlow] the screen *observes*; the screen can be recreated as often as it
 * likes and re-attaches to a download already in flight. Nothing in this class touches a `View`,
 * a `Composable` or the main thread.
 *
 * ### One at a time
 *
 * Requests are serialised by a fair [Mutex], so pressing Download on six languages downloads them
 * in the order they were pressed rather than six at once on a phone connection. A queued pack is
 * reported as `Downloading(0, size)` from the moment it is requested — the user asked for it, so
 * it should look requested, and a progress bar at 0 % says "waiting" honestly enough.
 *
 * ### The shape of one download
 *
 *  1. [Downloader.download] into `cacheDir/downloads/<file>`, which verifies size and SHA-256
 *     before the file exists under that name at all.
 *  2. [PackInstallService.installFromFile] under the **manifest's** locale — never a locale
 *     parsed out of the file name.
 *  3. Delete the download file, whatever happened. It is a copy; the installed pack is elsewhere.
 *
 * Any failure in 1 or 2 leaves the pack in [PackState.Failed] carrying the exception the layer
 * below raised — a [DistributionException] for transport and integrity, a [PackInstallException]
 * for the install — so the UI can explain it without parsing message strings. Cancellation is not
 * a failure: a cancelled pack drops out of the map entirely and reads as available again.
 */
class PackDownloadManager internal constructor(
    private val context: Context,
    private val downloader: Downloader = Downloader(context),
    private val installer: PackInstallService = PackInstallService(context),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private val _states = MutableStateFlow<Map<String, PackState>>(emptyMap())

    /**
     * Live state per locale, for the locales this manager has been asked about. A locale that is
     * absent is a locale nothing has happened to — the catalogue's own answer (installed or
     * available) stands. Collect it and merge it through
     * [PackCatalog.Companion.from]; do not treat it as the whole picture.
     */
    val states: StateFlow<Map<String, PackState>> = _states.asStateFlow()

    /** The queue, so a cancel can reach a download that has not started yet. */
    private val jobs = HashMap<String, Job>()

    private val queue = Mutex()

    /**
     * Download and install [entry], or do nothing if it is already queued.
     *
     * Returns immediately; watch [states] for progress. [packs] supplies the base URL the entry
     * hangs off — pass the same [Packs] the entry came out of, never one from a different
     * manifest, or the URL will be built against the wrong release.
     */
    fun download(packs: Packs, entry: PackEntry) {
        synchronized(jobs) {
            if (jobs.containsKey(entry.locale)) return
            // Queued from this moment: the user pressed the button, and a row that snapped back
            // to "Download" until the mutex was free would read as a button that did nothing.
            put(entry.locale, PackState.Downloading(0L, entry.size))
            jobs[entry.locale] = scope.launch {
                try {
                    queue.withLock { run(packs, entry) }
                } finally {
                    synchronized(jobs) { jobs.remove(entry.locale) }
                }
            }
        }
    }

    /**
     * Stop a download, queued or in flight, and forget it. The pack reads as available again:
     * cancellation is the user changing their mind, not an error to report back to them.
     */
    fun cancel(locale: String) {
        val job = synchronized(jobs) { jobs.remove(locale) }
        job?.cancel()
        clear(locale)
    }

    /** Dismiss a [PackState.Failed] so the row offers Download again. */
    fun clearFailure(locale: String) {
        if (_states.value[locale] is PackState.Failed) clear(locale)
    }

    /** Whether anything is queued or downloading. */
    fun isBusy(): Boolean = synchronized(jobs) { jobs.isNotEmpty() }

    private suspend fun run(packs: Packs, entry: PackEntry) {
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
            // The partial download is already gone - Downloader deletes it on cancellation - so
            // all that is left is to make sure no state entry survives the cancel, whether it
            // came from cancel() or from the whole scope going down.
            clear(entry.locale)
            throw cancelled
        } catch (unexpected: RuntimeException) {
            fail(entry.locale, TransportException("Could not install ${entry.locale}", unexpected))
        } finally {
            // A copy either way: on success the installer already copied it into nuance/, and on
            // failure it is worthless. Leaving it would keep several megabytes per attempt in the
            // cache until Downloader.clearStale got round to it.
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

        /** The process-wide manager. */
        @JvmStatic
        fun getInstance(context: Context): PackDownloadManager {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: PackDownloadManager(context.applicationContext).also { instance = it }
            }
        }

        /**
         * Drop the singleton. For tests only: a process-wide object that survives a screen also
         * survives a test, and one test's queue must not be the next test's starting state.
         */
        @JvmStatic
        fun resetForTests() {
            synchronized(this) { instance = null }
        }
    }
}

package dev.bbkb.ime.keyboard.inputboard.clipboard

import android.content.Context
import android.util.Patterns
import dev.bbkb.ime.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Link previews for clipboard rows, keyed by [ClipEntry.id] rather than by view, so a recycled
 * holder never shows another row's preview. Each entry is fetched at most once (failures are
 * remembered too); nothing is fetched while [isEnabled] is false. [previewUrlFor] never yields a
 * URL for a masked row. Main thread only; the owner must call [release] when the board view goes.
 */
class ClipboardWebImageProvider internal constructor(
    private val loader: suspend (url: String) -> OpenGraphMetadata?,
    dispatcher: CoroutineDispatcher,
) {

    fun interface Listener {
        /** A preview for [entryId] is ready to be shown. */
        fun onPreviewReady(entryId: Long)
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + dispatcher)

    /** Finished fetches. A null value is a remembered failure: there is no preview to show. */
    private val results = HashMap<Long, OpenGraphMetadata?>()
    private val inFlight = HashMap<Long, Job>()
    private var released = false

    var listener: Listener? = null

    /** The link-previews setting. Turning it off cancels every fetch and forgets every result. */
    var isEnabled: Boolean = false
        set(value) {
            if (field && !value) forgetAll()
            field = value
        }

    /** The preview fetched for [entryId], or null if there is none (yet, or at all). */
    fun previewFor(entryId: Long): OpenGraphMetadata? = if (isEnabled) results[entryId] else null

    /** Whether [entryId] has been fetched, successfully or not. */
    fun hasResultFor(entryId: Long): Boolean = results.containsKey(entryId)

    fun isFetching(entryId: Long): Boolean = inFlight.containsKey(entryId)

    /**
     * Fetch [url] for [entryId], unless previews are off, this provider is released, or the entry
     * was already fetched or is being fetched.
     */
    fun request(entryId: Long, url: String) {
        if (!isEnabled || released || results.containsKey(entryId) || inFlight.containsKey(entryId)) {
            return
        }
        // LAZY so the job is in the map before its body runs: main-immediate runs an
        // unsuspending body inline, inside launch() itself.
        val fetch = scope.launch(start = CoroutineStart.LAZY) {
            val result = try {
                loader(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            inFlight.remove(entryId)
            results[entryId] = result
            if (result != null) listener?.onPreviewReady(entryId)
        }
        inFlight[entryId] = fetch
        fetch.start()
    }

    /** Drop the results and cancel the fetches of every entry not in [entryIds] (deleted rows). */
    fun retainOnly(entryIds: Set<Long>) {
        results.keys.retainAll(entryIds)
        val gone = inFlight.keys.filter { it !in entryIds }
        for (id in gone) inFlight.remove(id)?.cancel()
    }

    /** Cancel every fetch and drop every result; nothing more will be fetched. */
    fun release() {
        released = true
        listener = null
        forgetAll()
        scope.cancel()
    }

    private fun forgetAll() {
        for (fetch in inFlight.values) fetch.cancel()
        inFlight.clear()
        results.clear()
    }

    companion object {
        /** Real network fetches, results on the main thread. */
        @JvmStatic
        fun create(context: Context): ClipboardWebImageProvider {
            val resources = context.resources
            val width = resources.getDimension(R.dimen.config_clipboard_view_clip_data_image_width).toInt()
            val height = resources.getDimension(R.dimen.config_clipboard_view_clip_data_image_height).toInt()
            return ClipboardWebImageProvider(
                { url -> OpenGraphFetcher.fetchPreview(url, width, height) },
                Dispatchers.Main.immediate,
            )
        }

        /**
         * The URL to fetch if the text is exactly one https-fetchable web address (a bare host
         * counts); always null for a [masked] row, whose text must not leave the device.
         */
        @JvmStatic
        fun previewUrlFor(text: String, masked: Boolean): String? =
            if (masked) null else webUrlOrNull(text)?.let(::previewUrlForLink)

        /** [previewUrlFor] for a row already known to be a link ([webUrlOrNull]). */
        @JvmStatic
        fun previewUrlForLink(link: String): String? =
            if (UrlUtils.httpsUrlOrNull(link) != null) link else null

        /** Longer clips are plain text and are neither trimmed nor matched (this runs on every bind). */
        const val MAX_URL_CHARS = 2048

        /** Trimmed [text] when the whole of it is one web address (gets the link icon), else null. */
        @JvmStatic
        fun webUrlOrNull(text: String): String? {
            if (text.length > MAX_URL_CHARS) return null
            val trimmed = text.trim()
            return if (isWebUrl(trimmed)) trimmed else null
        }

        @JvmStatic
        fun isWebUrl(text: String): Boolean =
            text.isNotEmpty() && text.length <= MAX_URL_CHARS && Patterns.WEB_URL.matcher(text).matches()
    }
}

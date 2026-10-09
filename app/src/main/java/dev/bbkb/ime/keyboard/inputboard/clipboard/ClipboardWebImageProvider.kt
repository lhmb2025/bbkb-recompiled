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
 * Link previews for the clipboard board's rows, keyed by [ClipEntry.id].
 *
 * Results belong to an entry, not to a view: a finished fetch records its result under the entry
 * id and tells [listener], and the adapter finds that entry's current row (if it is on screen at
 * all) and rebinds it. The old provider handed each fetch the ViewHolder that asked for it, so a
 * recycled holder could be painted with another row's preview.
 *
 * Each entry is fetched at most once for the life of this provider: a failure is remembered as
 * "no preview" just like a success is remembered, so scrolling a dead link back into view does not
 * fetch it again. Nothing is fetched while [isEnabled] is false (the link-previews setting is off
 * by default), and turning it off forgets every result. Callers decide what may be fetched at all
 * through [previewUrlFor], which never yields a URL for a masked (Password Keeper) row.
 *
 * Main thread only. [release] cancels in-flight fetches; the owner must call it when the board
 * view is replaced or destroyed.
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
        // LAZY so the job is in the map before its body can run: the main-immediate dispatcher
        // runs an unsuspending body inline, inside launch() itself.
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

        /** The provider the board uses: real network fetches, results on the main thread. */
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
         * The URL to fetch a preview for, if the row's text is exactly one https-fetchable web
         * address; null for anything else, and always null for a [masked] row, whose text must not
         * leave the device. A bare host ("example.com") counts and is fetched over https.
         */
        @JvmStatic
        fun previewUrlFor(text: String, masked: Boolean): String? {
            if (masked) return null
            val trimmed = text.trim()
            if (!isWebUrl(trimmed)) return null
            return if (UrlUtils.httpsUrlOrNull(trimmed) != null) trimmed else null
        }

        /** Whether the whole of [text] is one web address (what gets the link icon). */
        @JvmStatic
        fun isWebUrl(text: String): Boolean =
            text.isNotEmpty() && Patterns.WEB_URL.matcher(text).matches()
    }
}

package dev.bbkb.ime.keyboard.inputboard.clipboard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.bbkb.ime.core.shared.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches a link preview — the page's Open Graph title and a scaled thumbnail — for one copied
 * URL.
 *
 * Everything it requests comes from untrusted text (what the user copied) or from what the
 * fetched page names, so every request is bounded:
 *  - **https only**, for the page and for the image ([UrlUtils.httpsUrlOrNull]);
 *  - **[TIMEOUT_MS] per request**: the page's whole connect-and-read, and the image's connect
 *    plus its body read, which [readCapped] stops at the deadline;
 *  - **[MAX_PAGE_BYTES]** of page (jsoup stops reading there; the Open Graph tags are in the
 *    head) and **[MAX_IMAGE_BYTES]** of image, beyond which the image is dropped rather than
 *    decoded;
 *  - the image is decoded subsampled to roughly the thumbnail size, so a small file that expands
 *    to an enormous bitmap cannot exhaust memory.
 */
object OpenGraphFetcher {

    private const val TAG = "OpenGraphFetcher"
    private const val USER_AGENT = "Mozilla/5.0 AppleWebKit/534.30 (KHTML, like Gecko) Chrome/12.0.742.122 Safari/534.30"

    const val TIMEOUT_MS = 5_000
    const val MAX_PAGE_BYTES = 1024 * 1024
    const val MAX_IMAGE_BYTES = 2 * 1024 * 1024

    /**
     * The preview for [url], or null when there is nothing to show (not https, unreachable, no
     * title and no image). Never throws; the blocking work runs on [Dispatchers.IO].
     */
    suspend fun fetchPreview(url: String, targetWidth: Int, targetHeight: Int): OpenGraphMetadata? =
        withContext(Dispatchers.IO) {
            val pageUrl = UrlUtils.httpsUrlOrNull(url) ?: return@withContext null
            val metadata = OpenGraphMetadata()
            metadata.url = url
            if (UrlUtils.isImageUrl(url)) {
                // The row IS the image: no page to read.
                metadata.imageUrl = pageUrl
            } else {
                fetchPage(pageUrl, metadata)
            }
            metadata.imageUrl
                ?.let { UrlUtils.httpsUrlOrNull(it) }
                ?.let { downloadImage(it, targetWidth, targetHeight) }
                ?.let { metadata.image = it }
            if (metadata.title == null && metadata.image == null) null else metadata
        }

    private fun fetchPage(url: String, metadata: OpenGraphMetadata) {
        try {
            val document = Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .timeout(TIMEOUT_MS)
                .maxBodySize(MAX_PAGE_BYTES)
                .get()
            metadata.parse(document)
        } catch (e: Exception) {
            Logger.debug(TAG, "No Open Graph metadata from $url: ${e.message}")
        }
    }

    private fun downloadImage(url: String, targetWidth: Int, targetHeight: Int): Bitmap? {
        val connection = try {
            URL(url).openConnection() as? HttpURLConnection ?: return null
        } catch (e: IOException) {
            return null
        }
        return try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (connection.responseCode !in 200..299 || connection.contentLength > MAX_IMAGE_BYTES) {
                return null
            }
            val bytes = connection.inputStream.use { readCapped(it, MAX_IMAGE_BYTES, TIMEOUT_MS.toLong()) }
                ?: return null
            decodeScaled(bytes, targetWidth, targetHeight)
        } catch (e: Exception) {
            Logger.debug(TAG, "No image from $url: ${e.message}")
            null
        } finally {
            connection.disconnect()
        }
    }

    /**
     * The whole of [input], or null if it is longer than [maxBytes] or is still arriving after
     * [timeoutMs]. A server that drips one byte just inside every read timeout is stopped here.
     */
    @JvmStatic
    fun readCapped(
        input: InputStream,
        maxBytes: Int,
        timeoutMs: Long,
        nanoTime: () -> Long = System::nanoTime,
    ): ByteArray? {
        val deadline = nanoTime() + timeoutMs * 1_000_000L
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return out.toByteArray()
            if (out.size() + read > maxBytes || nanoTime() > deadline) return null
            out.write(buffer, 0, read)
        }
    }

    /**
     * The largest power-of-two subsampling that still leaves the decoded image at least the target
     * size in both dimensions, as `BitmapFactory.Options.inSampleSize` wants it.
     */
    @JvmStatic
    fun sampleSizeFor(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int): Int {
        var sample = 1
        if (targetWidth <= 0 || targetHeight <= 0) return sample
        while (sourceWidth / (sample * 2) >= targetWidth && sourceHeight / (sample * 2) >= targetHeight) {
            sample *= 2
        }
        return sample
    }

    private fun decodeScaled(bytes: ByteArray, targetWidth: Int, targetHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, targetWidth, targetHeight)
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val scaled = Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true)
        if (scaled != decoded) decoded.recycle()
        return scaled
    }
}

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
 * Fetches a link preview (Open Graph title and scaled thumbnail) for one copied URL. All URLs are
 * untrusted, so requests are bounded: https only, [TIMEOUT_MS] per request (including the image
 * body via [readCapped]), [MAX_PAGE_BYTES] / [MAX_IMAGE_BYTES], and the image is decoded
 * subsampled so a small file cannot expand into an enormous bitmap.
 */
object OpenGraphFetcher {

    private const val TAG = "OpenGraphFetcher"
    private const val USER_AGENT = "Mozilla/5.0 AppleWebKit/534.30 (KHTML, like Gecko) Chrome/12.0.742.122 Safari/534.30"

    const val TIMEOUT_MS = 5_000
    const val MAX_PAGE_BYTES = 1024 * 1024
    const val MAX_IMAGE_BYTES = 2 * 1024 * 1024

    /** Null when there is nothing to show. Never throws; runs on [Dispatchers.IO]. */
    suspend fun fetchPreview(url: String, targetWidth: Int, targetHeight: Int): OpenGraphMetadata? =
        withContext(Dispatchers.IO) {
            val pageUrl = UrlUtils.httpsUrlOrNull(url) ?: return@withContext null
            val metadata = OpenGraphMetadata()
            metadata.url = url
            if (UrlUtils.isImageUrl(url)) {
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
     * The whole of [input], or null if over [maxBytes] or still arriving after [timeoutMs] (stops a
     * server that drips one byte inside every read timeout).
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

    /** The largest power-of-two `inSampleSize` that keeps the image at least the target size. */
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

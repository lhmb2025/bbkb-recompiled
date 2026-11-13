package dev.bbkb.ime.core.distribution

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Downloads one file (APK or language pack) to `<destination>.tmp`, hashing as it streams, checks
 * size and SHA-256 before renaming, so an existing destination is always complete and verified.
 * Redirects are followed by hand because `HttpURLConnection` drops cross-host ones (GitHub assets
 * 302 to another host). There is no signature: integrity is HTTPS from a named host plus the
 * manifest hash. Cancellation deletes the temp file and rethrows; it is never a `Result.failure`.
 */
class Downloader @JvmOverloads constructor(
    private val context: Context,
    private val connectTimeoutMs: Int = DistributionConfig.TIMEOUT_MS,
    private val readTimeoutMs: Int = DistributionConfig.TIMEOUT_MS,
) {

    /**
     * Fetch [url] into [destination], verifying [expectedSha256] (blank skips) and [expectedSize]
     * (`<= 0` skips; when positive it is also the progress total). [onProgress] runs on IO at most
     * every 100 ms plus once with the final count; total is `-1` when unknown.
     */
    @JvmOverloads
    suspend fun download(
        url: String,
        expectedSha256: String?,
        expectedSize: Long,
        destination: File,
        onProgress: (bytes: Long, total: Long) -> Unit = { _, _ -> },
    ): Result<File> = withContext(Dispatchers.IO) {
        val temp = File(destination.parentFile, destination.name + ".tmp")
        try {
            destination.parentFile?.mkdirs()
            temp.delete()
            val actualSha = transferWithRetries(url, expectedSize, temp, onProgress)

            val actualSize = temp.length()
            if (expectedSize > 0L && actualSize != expectedSize) {
                temp.delete()
                return@withContext Result.failure(
                    IntegrityException("size", expectedSize.toString(), actualSize.toString())
                )
            }
            if (!expectedSha256.isNullOrBlank() && !Sha256.matches(expectedSha256, actualSha)) {
                temp.delete()
                return@withContext Result.failure(
                    IntegrityException("sha256", expectedSha256.trim().lowercase(), actualSha)
                )
            }

            if (!replace(temp, destination)) {
                temp.delete()
                return@withContext Result.failure(
                    TransportException("Could not move download into place at $destination")
                )
            }
            Result.success(destination)
        } catch (cancelled: CancellationException) {
            temp.delete()
            throw cancelled
        } catch (failure: DistributionException) {
            temp.delete()
            Result.failure(failure)
        } catch (failure: IOException) {
            temp.delete()
            Result.failure(TransportException("Download of $url failed", failure))
        }
    }

    /**
     * Three attempts with 500/1500 ms backoff. Only transport failures retry; HTTP status, insecure
     * URL and redirect loops are definite answers. Returns the hex SHA-256 of [temp].
     */
    private suspend fun transferWithRetries(
        url: String,
        expectedSize: Long,
        temp: File,
        onProgress: (Long, Long) -> Unit,
    ): String {
        var lastFailure: IOException? = null
        for (attempt in 0..RETRIES) {
            if (attempt > 0) {
                delay(BACKOFF_MS[attempt - 1])
                temp.delete()
            }
            coroutineContext.ensureActive()
            try {
                return transfer(url, expectedSize, temp, onProgress)
            } catch (definite: HttpStatusException) {
                throw definite
            } catch (definite: InsecureUrlException) {
                throw definite
            } catch (definite: TooManyRedirectsException) {
                throw definite
            } catch (transport: IOException) {
                lastFailure = transport
            }
        }
        throw TransportException("Download of $url failed after ${RETRIES + 1} attempts", lastFailure)
    }

    private suspend fun transfer(
        url: String,
        expectedSize: Long,
        temp: File,
        onProgress: (Long, Long) -> Unit,
    ): String {
        val connection = RedirectingHttp.open(
            url = url,
            userAgent = DistributionConfig.userAgent(context),
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs,
        )
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                throw HttpStatusException(status, url)
            }
            val declared = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            val total = if (expectedSize > 0L) expectedSize else declared
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            // Throttled: each report recomposes the Language packs screen.
            var reported = -1L
            var reportedAt = System.nanoTime() - PROGRESS_INTERVAL_NANOS
            connection.inputStream.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        // Cancellation point; download() deletes the partial file.
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        written += read
                        val now = System.nanoTime()
                        if (now - reportedAt >= PROGRESS_INTERVAL_NANOS || written == total) {
                            reported = written
                            reportedAt = now
                            onProgress(written, total)
                        }
                    }
                    output.flush()
                }
            }
            if (written > 0L && reported != written) onProgress(written, total)
            return Sha256.hex(digest.digest())
        } finally {
            connection.disconnect()
        }
    }

    fun downloadDir(): File = DistributionConfig.downloadDir(context)

    fun destinationFor(name: String): File = File(downloadDir(), name)

    /** Delete files in [downloadDir] older than [maxAgeMs] (including orphaned `.tmp`); blocking I/O. */
    fun clearStale(maxAgeMs: Long): Int {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        val files = downloadDir().listFiles() ?: return 0
        var deleted = 0
        for (file in files) {
            if (file.isFile && file.lastModified() < cutoff && file.delete()) deleted++
        }
        return deleted
    }

    companion object {
        const val MAX_REDIRECTS: Int = 5

        /** Retries after the first attempt (three attempts in all). */
        const val RETRIES: Int = 2

        private val BACKOFF_MS = longArrayOf(500L, 1500L)

        private const val BUFFER_BYTES = 64 * 1024

        private const val PROGRESS_INTERVAL_NANOS = 100_000_000L

        /**
         * Cross-host hops are allowed (GitHub needs them). The target must be `http(s)` (no local
         * reads via `file:`/`content:`), and `https` may never downgrade to `http`.
         */
        @JvmStatic
        fun isRedirectAllowed(from: String, to: String): Boolean {
            val fromScheme = schemeOf(from) ?: return false
            val toScheme = schemeOf(to) ?: return false
            if (toScheme != "http" && toScheme != "https") return false
            return !(fromScheme == "https" && toScheme == "http")
        }

        private fun schemeOf(url: String): String? =
            url.substringBefore(':', missingDelimiterValue = "")
                .lowercase()
                .takeIf { it.isNotEmpty() && url.contains(':') }
    }

    /** Falls back to copy-and-delete when `renameTo` fails across filesystems. */
    private fun replace(temp: File, destination: File): Boolean {
        destination.delete()
        if (temp.renameTo(destination)) return true
        return try {
            temp.copyTo(destination, overwrite = true)
            temp.delete()
            true
        } catch (failed: IOException) {
            false
        }
    }
}


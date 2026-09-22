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
 * Downloads one file — an APK or a language pack — and refuses to call it a success until the
 * bytes on disk are the bytes the manifest promised.
 *
 * ### The shape of a download
 *
 * 1. Stream the body to `<destination>.tmp`, hashing as it goes. The real destination never
 *    exists in a partial state, so "the file is there" is always safe to read as "the file is
 *    complete and verified".
 * 2. Check the size, then the SHA-256, **before** the rename. A mismatch deletes the temp file
 *    and fails with [IntegrityException].
 * 3. Rename onto [destination].
 *
 * ### Redirects
 *
 * A GitHub release asset URL answers `302` to `objects.githubusercontent.com` — a *different
 * host*, and `HttpURLConnection` silently declines to follow those (it drops cross-host
 * redirects even with `instanceFollowRedirects` on, which shows up as a zero-byte "successful"
 * download). So redirects are followed by hand: `301/302/303/307/308`, at most
 * [MAX_REDIRECTS] hops, each checked by [isRedirectAllowed].
 *
 * ### Integrity
 *
 * There is no signature anywhere in this system. Integrity is exactly two things: the bytes came
 * over HTTPS from a host we named, and they hash to what the manifest said. [isRedirectAllowed]
 * protects the first (no HTTPS→cleartext downgrade, ever); step 2 above protects the second.
 * A caller that passes a blank hash is opting out of the second leg — the download still works,
 * which is what makes local debug servers usable, but nothing installable should ever be fetched
 * that way.
 *
 * ### Threading and cancellation
 *
 * [download] is a suspend function that does all of its work on [Dispatchers.IO]; call it from
 * anywhere. Cancelling the calling coroutine stops the transfer at the next chunk boundary,
 * deletes the partial temp file and rethrows [CancellationException] — cancellation is *not*
 * reported as a `Result.failure`, so a `Result` coming out of this function always describes a
 * download that actually ran to a conclusion.
 */
class Downloader @JvmOverloads constructor(
    private val context: Context,
    private val connectTimeoutMs: Int = DistributionConfig.TIMEOUT_MS,
    private val readTimeoutMs: Int = DistributionConfig.TIMEOUT_MS,
) {

    /**
     * Fetch [url] into [destination], verifying it against the manifest's promises.
     *
     * @param url absolute `http(s)` URL. In production always HTTPS; plain HTTP is accepted so
     *   a debug build can talk to a local server, and a request that *starts* on HTTPS can never
     *   be redirected down to it.
     * @param expectedSha256 lowercase (or any-case) hex SHA-256 the finished file must have.
     *   Blank or `null` skips the check — see the integrity note above.
     * @param expectedSize the size in bytes the finished file must have. `<= 0` skips the check.
     *   When positive it is also the denominator handed to [onProgress], which is why it is
     *   preferred over the server's `Content-Length`.
     * @param destination where the verified file ends up. Its parent directory is created if
     *   needed. Anything already there is replaced only on success.
     * @param onProgress called on the IO dispatcher as bytes arrive, with the running byte count
     *   and the total (`-1` when neither [expectedSize] nor `Content-Length` gave one). Called
     *   often; keep it cheap and marshal to the main thread yourself.
     *
     * @return the verified [destination] on success. On failure a [DistributionException]:
     *   [InsecureUrlException], [TooManyRedirectsException], [HttpStatusException],
     *   [IntegrityException], or a plain [DistributionException] wrapping the underlying
     *   [IOException].
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
            // Cancellation is not a failure: clean up the partial file and let it propagate.
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
     * The connection attempt, retried twice after a failure (three attempts in all) with a
     * 500 ms / 1500 ms backoff. Only transport-level failures are retried: an
     * [HttpStatusException], an [InsecureUrlException] or a redirect loop is a definite answer
     * and retrying it would only waste the user's battery.
     *
     * @return the lowercase hex SHA-256 of everything written to [temp].
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

    /** One full attempt: open (following redirects), stream, hash. */
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
            // Not contentLengthLong: that is API 24 and this app ships to API 23.
            val declared = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            val total = if (expectedSize > 0L) expectedSize else declared
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            connection.inputStream.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        // Cancellation lands here, between chunks: the partial file is deleted
                        // by download()'s CancellationException arm.
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        written += read
                        onProgress(written, total)
                    }
                    output.flush()
                }
            }
            return Sha256.hex(digest.digest())
        } finally {
            connection.disconnect()
        }
    }

    // ── Download directory housekeeping ───────────────────────────────────────────────────────

    /** `cacheDir/downloads/`, created if needed. */
    fun downloadDir(): File = DistributionConfig.downloadDir(context)

    /** A file in [downloadDir] named after [name], for callers that do not want to build paths. */
    fun destinationFor(name: String): File = File(downloadDir(), name)

    /**
     * Delete everything in [downloadDir] last modified more than [maxAgeMs] ago, including
     * orphaned `.tmp` files from a download that died with the process.
     *
     * Blocking file I/O — call it off the main thread.
     *
     * @return how many files were deleted.
     */
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
        /** Redirect hops followed before giving up and calling it a loop. */
        const val MAX_REDIRECTS: Int = 5

        /** Retries *after* the first attempt, so three connection attempts in all. */
        const val RETRIES: Int = 2

        private val BACKOFF_MS = longArrayOf(500L, 1500L)

        private const val BUFFER_BYTES = 64 * 1024

        /**
         * Whether a redirect from [from] to [to] may be followed.
         *
         * Two rules, and a cross-host hop breaks neither of them (GitHub's asset redirect is
         * exactly that, and refusing it would make every download fail):
         *
         *  - the target must be `http` or `https` — no `file:`, `ftp:`, `content:` or anything
         *    else that would turn a download into a local read;
         *  - an `https` request may never be redirected to `http`. Integrity rests on HTTPS plus
         *    the manifest hash, so a downgrade removes half of it silently. `http` → `https` is
         *    fine, and `http` → `http` is fine because the request was already cleartext (that
         *    is the debug/local-server case; production always starts at HTTPS).
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

    /** `renameTo` where it works, copy-and-delete across filesystems where it does not. */
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


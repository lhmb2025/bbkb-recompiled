package dev.bbkb.ime.core.distribution

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import kotlin.coroutines.coroutineContext

/**
 * Fetches the distribution manifest, with an on-disk cache in front of it.
 *
 * ### What `fetch` decides, in order
 *
 * 1. `force == false` and the cache is younger than [cacheMaxAgeMs] → **serve the cache**, no
 *    network call at all. This is what makes the once-a-day background check and an on-demand
 *    "check now" cheap to call from anywhere.
 * 2. No usable network → **serve the cache** at any age, or fail with [OfflineException] when
 *    there is nothing cached.
 * 3. Otherwise **go to the network**, sending `If-None-Match` when the last response carried an
 *    `ETag`:
 *     - `200` → parse, write the cache, return it as **fresh**.
 *     - `304` → the cached bytes are still current; return them, with the freshness window
 *       restarted.
 *     - anything else → fail with [HttpStatusException]. A cached manifest is *not* substituted
 *       for a server error: the caller asked to go to the network and deserves to hear that it
 *       did not work.
 *
 * ### Provenance is never hidden
 *
 * Every success carries [DistributionManifest.fromCache] and [DistributionManifest.fetchedAt].
 * `fromCache == true` means "these bytes came off disk" — including the `304` case, where the
 * content is known-current but was not re-transferred; `fetchedAt` is then the moment of that
 * re-validation, not the moment the bytes were first downloaded. A manifest is never returned
 * as fresh unless it was actually just parsed off the wire.
 *
 * ### The cache is keyed by URL
 *
 * The cache records which URL produced it. Flipping the debug
 * [DistributionConfig.PREF_MANIFEST_URL] override therefore invalidates it, so a local test
 * manifest can never be served as though it came from the production URL, or the reverse.
 *
 * ### Threading
 *
 * [fetch] is a suspend function that does all of its network and disk work on
 * [Dispatchers.IO]. Cancellation propagates as [CancellationException] and is never reported as
 * a `Result.failure`. A parse failure never overwrites a good cache.
 *
 * Instances are cheap and hold no state beyond their configuration; the state lives in
 * `files/distribution/`. Two concurrent `fetch` calls are safe in the sense that neither
 * corrupts the cache (writes go through a temp file and a rename), but they will both hit the
 * network — the caller owns any de-duplication.
 */
class ManifestSource @JvmOverloads constructor(
    private val context: Context,
    private val manifestUrlProvider: () -> String = { DistributionConfig.manifestUrl(context) },
    private val isOnline: () -> Boolean = { NetworkState.isOnline(context) },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val cacheMaxAgeMs: Long = DistributionConfig.CACHE_MAX_AGE_MS,
    private val connectTimeoutMs: Int = DistributionConfig.TIMEOUT_MS,
    private val readTimeoutMs: Int = DistributionConfig.TIMEOUT_MS,
) {

    /**
     * The manifest, from the cache or the network per the rules above.
     *
     * @param force skip the freshness shortcut and always ask the server (an `ETag` may still
     *   make that a cheap `304`). Use it for a user-initiated "check for updates"; leave it
     *   `false` for the daily background check.
     * @return on failure a [DistributionException]: [OfflineException], [HttpStatusException],
     *   [ManifestFormatException], [UnsupportedSchemaException], [InsecureUrlException],
     *   [TooManyRedirectsException] or [TransportException].
     */
    suspend fun fetch(force: Boolean): Result<DistributionManifest> = withContext(Dispatchers.IO) {
        val url = manifestUrlProvider()
        try {
            RedirectingHttp.requireSupportedScheme(url)
        } catch (bad: DistributionException) {
            return@withContext Result.failure(bad)
        }

        val meta = readMeta(url)
        val now = clock()

        if (!force && meta != null && now - meta.fetchedAt < cacheMaxAgeMs) {
            cachedManifest()?.let { return@withContext Result.success(it.withProvenance(true, meta.fetchedAt)) }
        }

        if (!isOnline()) {
            val cached = cachedManifest()
            return@withContext if (cached != null) {
                Result.success(cached.withProvenance(true, meta?.fetchedAt ?: 0L))
            } else {
                Result.failure(OfflineException())
            }
        }

        try {
            request(url, etag = meta?.etag)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: DistributionException) {
            Result.failure(failure)
        } catch (failure: IOException) {
            Result.failure(TransportException("Could not fetch manifest from $url", failure))
        }
    }

    private suspend fun request(url: String, etag: String?): Result<DistributionManifest> {
        val headers = if (etag.isNullOrBlank()) emptyMap() else mapOf("If-None-Match" to etag)
        val connection = RedirectingHttp.open(
            url = url,
            userAgent = DistributionConfig.userAgent(context),
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs,
            headers = headers,
        )
        val body: String
        val status: Int
        val responseEtag: String?
        try {
            status = connection.responseCode
            responseEtag = connection.getHeaderField("ETag")
            body = if (status in 200..299) {
                coroutineContext.ensureActive()
                connection.inputStream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
            } else {
                ""
            }
        } finally {
            connection.disconnect()
        }

        if (status == HTTP_NOT_MODIFIED) {
            val cached = cachedManifest()
            if (cached != null) {
                // Still current: restart the freshness window but keep the ETag, and be honest
                // that the bytes came off disk.
                val now = clock()
                writeMeta(Meta(url = url, etag = etag, fetchedAt = now))
                return Result.success(cached.withProvenance(true, now))
            }
            // The server says "unchanged" but our cache is gone or corrupt, so the ETag we sent
            // was stale state. Drop it and ask once more for the whole thing.
            clearCache()
            return request(url, etag = null)
        }

        if (status !in 200..299) {
            return Result.failure(HttpStatusException(status, url))
        }

        // Parse before writing: a malformed response must not replace a good cache.
        val manifest = DistributionManifestParser.parse(body)
        val now = clock()
        writeCache(body)
        writeMeta(Meta(url = url, etag = responseEtag, fetchedAt = now))
        return Result.success(manifest.withProvenance(fromCache = false, fetchedAt = now))
    }

    // ── Cache ─────────────────────────────────────────────────────────────────────────────────

    /**
     * The cached manifest without touching the network, or `null` when there is none, it does
     * not parse, or it belongs to a different manifest URL. Its
     * [DistributionManifest.fromCache] is `true` and its [DistributionManifest.fetchedAt] is
     * when it was fetched.
     *
     * Blocking disk I/O; safe to call from any thread but not from the main one.
     */
    fun cached(): DistributionManifest? {
        val meta = readMeta(manifestUrlProvider()) ?: return null
        return cachedManifest()?.withProvenance(true, meta.fetchedAt)
    }

    /** Whether [cached] would return something younger than [cacheMaxAgeMs]. */
    fun hasFreshCache(): Boolean {
        val meta = readMeta(manifestUrlProvider()) ?: return false
        return clock() - meta.fetchedAt < cacheMaxAgeMs && cacheFile().isFile
    }

    /** Delete the cached manifest and its metadata. */
    fun clearCache() {
        cacheFile().delete()
        metaFile().delete()
    }

    private fun cacheFile(): File = DistributionConfig.manifestCacheFile(context)

    private fun metaFile(): File = DistributionConfig.manifestMetaFile(context)

    /** The parsed cache, or `null` if it is missing or no longer readable by this build. */
    private fun cachedManifest(): DistributionManifest? {
        val file = cacheFile()
        if (!file.isFile) return null
        return try {
            DistributionManifestParser.parse(file.readText(StandardCharsets.UTF_8))
        } catch (unreadable: DistributionException) {
            // A cache this build cannot read is worthless — and if it is an
            // UnsupportedSchemaException, keeping it would make every offline call fail
            // forever. Drop it and behave as though there were no cache.
            null
        } catch (unreadable: IOException) {
            null
        }
    }

    private fun writeCache(body: String) {
        val file = cacheFile()
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        try {
            temp.writeText(body, StandardCharsets.UTF_8)
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
        } catch (failed: IOException) {
            // A cache we could not write is not a failed fetch: the caller still gets the
            // manifest, it just will not survive the process.
            temp.delete()
        }
    }

    /** What we remember about the last successful fetch. */
    private data class Meta(val url: String, val etag: String?, val fetchedAt: Long)

    /** `null` unless the stored metadata belongs to [url] and names a real cache file. */
    private fun readMeta(url: String): Meta? {
        val file = metaFile()
        if (!file.isFile || !cacheFile().isFile) return null
        return try {
            val obj = JsonParser.parseString(file.readText(StandardCharsets.UTF_8))
                ?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            val storedUrl = obj.get("url")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
            if (storedUrl != url) return null
            val fetchedAt = obj.get("fetchedAt")?.takeIf { it.isJsonPrimitive }?.asLong ?: return null
            val etag = obj.get("etag")?.takeIf { it.isJsonPrimitive }?.asString
            Meta(url = storedUrl, etag = etag, fetchedAt = fetchedAt)
        } catch (unreadable: RuntimeException) {
            null
        } catch (unreadable: IOException) {
            null
        }
    }

    private fun writeMeta(meta: Meta) {
        val obj = JsonObject().apply {
            addProperty("url", meta.url)
            addProperty("fetchedAt", meta.fetchedAt)
            meta.etag?.let { addProperty("etag", it) }
        }
        try {
            val file = metaFile()
            file.parentFile?.mkdirs()
            file.writeText(obj.toString(), StandardCharsets.UTF_8)
        } catch (failed: IOException) {
            // Same as writeCache: losing the metadata costs a re-fetch, nothing more.
        }
    }

    private companion object {
        const val HTTP_NOT_MODIFIED = 304
    }
}

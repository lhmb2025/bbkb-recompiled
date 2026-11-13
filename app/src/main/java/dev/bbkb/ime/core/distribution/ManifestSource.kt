package dev.bbkb.ime.core.distribution

import android.content.Context
import android.util.JsonWriter
import androidx.core.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import kotlin.coroutines.coroutineContext

/**
 * Fetches the manifest behind an on-disk cache keyed by URL. Order: a fresh cache (unless
 * `force`) is served with no network; offline serves the cache at any age or [OfflineException];
 * otherwise a conditional request: `200` parses and caches, `304` serves the cache with the window
 * restarted, anything else fails (no cache substitution for a server error). `fromCache` is true for
 * `304` too. A parse failure never overwrites a good cache; concurrent fetches are not de-duplicated.
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
    /** @param force skip the freshness shortcut (user "check now"); an ETag may still make it a `304` */
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
                // Restart the freshness window, keep the ETag.
                val now = clock()
                writeMeta(Meta(url = url, etag = etag, fetchedAt = now))
                return Result.success(cached.withProvenance(true, now))
            }
                // "Unchanged" but the cache is gone: the ETag was stale, so refetch in full.
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

    /** The cache for the current URL without network, or `null`; blocking disk I/O. */
    fun cached(): DistributionManifest? {
        val meta = readMeta(manifestUrlProvider()) ?: return null
        return cachedManifest()?.withProvenance(true, meta.fetchedAt)
    }

    fun hasFreshCache(): Boolean {
        val meta = readMeta(manifestUrlProvider()) ?: return false
        return clock() - meta.fetchedAt < cacheMaxAgeMs && cacheFile().isFile
    }

    fun clearCache() {
        // Also removes a `.new` left by a crash mid-write.
        AtomicFile(cacheFile()).delete()
        AtomicFile(metaFile()).delete()
    }

    private fun cacheFile(): File = DistributionConfig.manifestCacheFile(context)

    private fun metaFile(): File = DistributionConfig.manifestMetaFile(context)

    private fun cachedManifest(): DistributionManifest? {
        val file = cacheFile()
        if (!file.isFile) return null
        return try {
            DistributionManifestParser.parse(file.readText(StandardCharsets.UTF_8))
        } catch (unreadable: DistributionException) {
            // Unreadable here; keeping an unsupported-schema cache would fail every offline call.
            null
        } catch (unreadable: IOException) {
            null
        }
    }

    private fun writeCache(body: String) {
        val file = cacheFile()
        file.parentFile?.mkdirs()
        writeAtomically(file, body)
    }

    /** Write failures are swallowed: the manifest is still returned, it just costs a re-fetch later. */
    private fun writeAtomically(file: File, text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        val atomic = AtomicFile(file)
        var out: FileOutputStream? = null
        try {
            out = atomic.startWrite()
            out.write(bytes)
            atomic.finishWrite(out)
        } catch (failed: IOException) {
            atomic.failWrite(out)
        }
    }

    private data class Meta(val url: String, val etag: String?, val fetchedAt: Long)

    private fun readMeta(url: String): Meta? {
        val file = metaFile()
        if (!file.isFile || !cacheFile().isFile) return null
        return try {
            val obj = JSONObject(file.readText(StandardCharsets.UTF_8))
            val storedUrl = primitiveText(obj.opt("url")) ?: return null
            if (storedUrl != url) return null
            val fetchedAt = when (val value = obj.opt("fetchedAt")) {
                is Number -> value.toLong()
                is String -> value.toLongOrNull() ?: return null
                else -> return null
            }
            val etag = primitiveText(obj.opt("etag"))
            Meta(url = storedUrl, etag = etag, fetchedAt = fetchedAt)
        } catch (unreadable: JSONException) {
            null
        } catch (unreadable: RuntimeException) {
            null
        } catch (unreadable: IOException) {
            null
        }
    }

    private fun primitiveText(value: Any?): String? = when (value) {
        is String -> value
        is Number, is Boolean -> value.toString()
        else -> null
    }

    private fun writeMeta(meta: Meta) {
        // JsonWriter rather than JSONObject.toString, which escapes every '/' in the URL.
        val text = StringWriter().also { out ->
            JsonWriter(out).use { w ->
                w.beginObject()
                w.name("url").value(meta.url)
                w.name("fetchedAt").value(meta.fetchedAt)
                meta.etag?.let { w.name("etag").value(it) }
                w.endObject()
            }
        }.toString()
        val file = metaFile()
        file.parentFile?.mkdirs()
        writeAtomically(file, text)
    }

    private companion object {
        const val HTTP_NOT_MODIFIED = 304
    }
}

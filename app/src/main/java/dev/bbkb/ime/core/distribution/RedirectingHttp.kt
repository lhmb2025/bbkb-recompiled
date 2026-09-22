package dev.bbkb.ime.core.distribution

import java.net.HttpURLConnection
import java.net.URL

/**
 * `HttpURLConnection.openConnection` plus the redirect handling it does not do.
 *
 * `HttpURLConnection` follows a same-host redirect and silently *stops* at a cross-host one —
 * which is precisely the case that matters here, because every GitHub release asset answers
 * `302` to `objects.githubusercontent.com`. Relying on the built-in behaviour yields a
 * zero-byte body with a `302` status and no error, so both [ManifestSource] and [Downloader]
 * walk the chain themselves through this helper.
 *
 * Shared rather than duplicated so the security rule lives in exactly one place:
 * [Downloader.isRedirectAllowed] vets every hop, and a hop count above
 * [Downloader.MAX_REDIRECTS] is treated as a loop.
 */
internal object RedirectingHttp {

    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

    /**
     * A connected [HttpURLConnection] whose response is *not* a redirect. The caller owns it and
     * must `disconnect()` it.
     *
     * @throws InsecureUrlException on an unsupported scheme or an HTTPS→cleartext downgrade.
     * @throws TooManyRedirectsException past [Downloader.MAX_REDIRECTS] hops.
     * @throws HttpStatusException on a redirect status with no usable `Location`.
     */
    fun open(
        url: String,
        userAgent: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        headers: Map<String, String> = emptyMap(),
    ): HttpURLConnection {
        var current = url
        for (hop in 0..Downloader.MAX_REDIRECTS) {
            requireSupportedScheme(current)
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                requestMethod = "GET"
                setRequestProperty("User-Agent", userAgent)
                // Ask for the bytes as they are: a gzipped body would make Content-Length
                // disagree with the file size the manifest promises, and would have to be
                // decoded before hashing.
                setRequestProperty("Accept-Encoding", "identity")
                for ((name, value) in headers) setRequestProperty(name, value)
            }
            val status = connection.responseCode
            if (status !in REDIRECT_CODES) return connection
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            if (location.isNullOrBlank()) throw HttpStatusException(status, current)
            // A Location header may be relative; resolve it against the URL that produced it.
            val next = URL(URL(current), location).toString()
            if (!Downloader.isRedirectAllowed(current, next)) {
                throw InsecureUrlException(
                    next,
                    "redirect from $current would downgrade or leave http(s)"
                )
            }
            current = next
        }
        throw TooManyRedirectsException(url, Downloader.MAX_REDIRECTS)
    }

    fun requireSupportedScheme(url: String) {
        val scheme = url.substringBefore(':', missingDelimiterValue = "").lowercase()
        if (scheme != "http" && scheme != "https") {
            throw InsecureUrlException(url, "only http(s) URLs are supported")
        }
    }
}

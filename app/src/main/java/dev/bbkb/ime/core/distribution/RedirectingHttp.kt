package dev.bbkb.ime.core.distribution

import java.net.HttpURLConnection
import java.net.URL

/**
 * Opens HTTP connections and walks redirects by hand: `HttpURLConnection` silently stops at a
 * cross-host redirect (every GitHub asset 302s to another host), yielding an empty "success".
 * Every hop is vetted by [Downloader.isRedirectAllowed].
 */
internal object RedirectingHttp {

    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

    /** A connected response that is not a redirect; the caller must `disconnect()` it. */
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
                // Gzip would break the Content-Length/size check and the hash.
                setRequestProperty("Accept-Encoding", "identity")
                for ((name, value) in headers) setRequestProperty(name, value)
            }
            val status = connection.responseCode
            if (status !in REDIRECT_CODES) return connection
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            if (location.isNullOrBlank()) throw HttpStatusException(status, current)
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

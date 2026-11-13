package dev.bbkb.ime.core.distribution

import java.io.IOException

/**
 * Base for every failure this package returns in `Result.failure`. Extends [IOException] so
 * existing I/O arms catch it; subclasses carry the values a user message needs. Cancellation is
 * never wrapped in one of these.
 */
sealed class DistributionException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

/** Transport or local write error; the one failure worth offering "retry" for. */
class TransportException(message: String, cause: Throwable? = null) :
    DistributionException(message, cause)

/** The manifest was not valid JSON, or was missing something schema 1 requires. */
class ManifestFormatException(message: String, cause: Throwable? = null) :
    DistributionException(message, cause)

/** Schema other than [DistributionManifest.SCHEMA]: the app is too old, so the answer is a manual update, not retry. */
class UnsupportedSchemaException(val schema: Int) : DistributionException(
    "Manifest schema $schema is not supported by this build (expected ${DistributionManifest.SCHEMA}). " +
        "This app is too old to read the update manifest."
)

class HttpStatusException(val status: Int, val url: String) :
    DistributionException("HTTP $status for $url")

/** There is no usable network and no cached manifest to fall back on. */
class OfflineException(message: String = "No network connection and no cached manifest") :
    DistributionException(message)

/**
 * A finished download did not match the manifest; the file is already deleted.
 *
 * @property kind `"sha256"` or `"size"`
 */
class IntegrityException(val kind: String, val expected: String, val actual: String) :
    DistributionException("Download failed $kind check: expected $expected, got $actual")

/** More than [Downloader.MAX_REDIRECTS] hops, treated as a loop. */
class TooManyRedirectsException(val url: String, val hops: Int) :
    DistributionException("More than $hops redirects starting at $url")

/** Refused before transfer: unsupported scheme, or an HTTPS to cleartext redirect. */
class InsecureUrlException(val url: String, reason: String) :
    DistributionException("Refusing $url: $reason")

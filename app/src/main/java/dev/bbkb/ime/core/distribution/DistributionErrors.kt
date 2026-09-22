package dev.bbkb.ime.core.distribution

import java.io.IOException

/**
 * Base type for every failure this package puts inside a `Result.failure`.
 *
 * It extends [IOException] deliberately: everything here is an I/O-shaped problem (a bad
 * download, an unreachable host, a file that did not hash to what it should), so a caller that
 * already has an `IOException` arm does not need a new one. A caller that wants to explain the
 * failure to a user should `when`-match on the subclasses below; each carries the specific
 * values a message needs, so no consumer has to parse a message string.
 *
 * Note that a [kotlinx.coroutines.CancellationException] is *never* wrapped in one of these —
 * cancellation propagates out of the suspend functions in this package as cancellation, and is
 * never reported as a `Result.failure`.
 */
sealed class DistributionException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

/**
 * The catch-all: a transport error (connection refused, timeout, reset), or a local problem such
 * as a destination that could not be written. This is the one failure worth offering the user a
 * "retry" for.
 */
class TransportException(message: String, cause: Throwable? = null) :
    DistributionException(message, cause)

/** The manifest was not valid JSON, or was missing something schema 1 requires. */
class ManifestFormatException(message: String, cause: Throwable? = null) :
    DistributionException(message, cause)

/**
 * The manifest announced a schema this build does not understand ([schema] > [DistributionManifest.SCHEMA]),
 * or a nonsensical one. The app is too old; the user-facing answer is "update the app by hand",
 * never "retry".
 */
class UnsupportedSchemaException(val schema: Int) : DistributionException(
    "Manifest schema $schema is not supported by this build (expected ${DistributionManifest.SCHEMA}). " +
        "This app is too old to read the update manifest."
)

/** The server answered, but not with a success status. [status] is the HTTP code. */
class HttpStatusException(val status: Int, val url: String) :
    DistributionException("HTTP $status for $url")

/** There is no usable network and no cached manifest to fall back on. */
class OfflineException(message: String = "No network connection and no cached manifest") :
    DistributionException(message)

/**
 * A download completed but did not match what the manifest promised. The partial file has
 * already been deleted by the time this is thrown.
 *
 * @property kind which check failed — `"sha256"` or `"size"`.
 * @property expected what the manifest said.
 * @property actual what the bytes on disk actually were.
 */
class IntegrityException(val kind: String, val expected: String, val actual: String) :
    DistributionException("Download failed $kind check: expected $expected, got $actual")

/**
 * A redirect chain exceeded [Downloader.MAX_REDIRECTS] hops, which is treated as a loop rather
 * than as something to keep following.
 */
class TooManyRedirectsException(val url: String, val hops: Int) :
    DistributionException("More than $hops redirects starting at $url")

/**
 * A URL was refused before any bytes were transferred: an unsupported scheme, or a redirect
 * that would have downgraded an HTTPS request to cleartext. Integrity here rests entirely on
 * HTTPS plus the SHA-256 in the manifest, so a silent downgrade would quietly remove one of the
 * two legs.
 */
class InsecureUrlException(val url: String, reason: String) :
    DistributionException("Refusing $url: $reason")

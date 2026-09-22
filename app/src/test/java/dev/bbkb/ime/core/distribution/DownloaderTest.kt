package dev.bbkb.ime.core.distribution

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * [Downloader] against a real HTTP server on a real socket — see [LocalHttpServer] for why a
 * mock would not be worth writing.
 *
 * The invariant every case here defends: **a file that exists at the destination is a file that
 * was verified.** Nothing partial, nothing unhashed and nothing of the wrong length is ever left
 * where a caller could install it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class DownloaderTest {

    private lateinit var context: Context
    private lateinit var server: LocalHttpServer
    private lateinit var downloader: Downloader
    private lateinit var destination: File

    private val payload = ByteArray(40_000) { (it % 97).toByte() }
    private val payloadSha get() = Sha256.of(payload)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        server = LocalHttpServer()
        // Short timeouts: nothing here should ever wait on the network, and a hang would
        // otherwise show up as a ten-second pause rather than a failure.
        downloader = Downloader(context, connectTimeoutMs = 4_000, readTimeoutMs = 4_000)
        destination = File(downloader.downloadDir(), "artifact.bin")
        destination.delete()
        tempOf(destination).delete()
    }

    @After
    fun tearDown() {
        server.close()
        downloader.downloadDir().listFiles()?.forEach { it.delete() }
    }

    private fun tempOf(file: File) = File(file.parentFile, file.name + ".tmp")

    // ── Success ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun downloadsVerifiesAndRenamesIntoPlace() = runBlocking {
        server.respondWith { it.reply(200, payload) }

        val result = downloader.download(
            url = server.url("/artifact.bin"),
            expectedSha256 = payloadSha,
            expectedSize = payload.size.toLong(),
            destination = destination,
        )

        assertEquals(destination, result.getOrThrow())
        assertTrue(destination.isFile)
        assertArrayEquals(payload, destination.readBytes())
        assertFalse("the temp file must not survive a success", tempOf(destination).exists())
    }

    @Test
    fun acceptsAnUppercaseDigestFromTheManifest() = runBlocking {
        server.respondWith { it.reply(200, payload) }

        val result = downloader.download(
            server.url("/a"), payloadSha.uppercase(), payload.size.toLong(), destination
        )

        assertTrue(result.isSuccess)
    }

    @Test
    fun reportsProgressUpToTheExpectedTotal() = runBlocking {
        server.respondWith { it.reply(200, payload) }
        val seen = mutableListOf<Pair<Long, Long>>()

        downloader.download(
            server.url("/a"), payloadSha, payload.size.toLong(), destination
        ) { bytes, total -> seen.add(bytes to total) }.getOrThrow()

        assertTrue(seen.isNotEmpty())
        assertTrue("byte counts must only ever grow", seen.map { it.first }.zipWithNext().all { it.first < it.second })
        assertEquals(payload.size.toLong(), seen.last().first)
        assertTrue("the total must be the manifest's size", seen.all { it.second == payload.size.toLong() })
    }

    @Test
    fun fallsBackToContentLengthForTheProgressTotal() = runBlocking {
        server.respondWith { it.reply(200, payload) }
        var lastTotal = Long.MIN_VALUE

        downloader.download(server.url("/a"), payloadSha, expectedSize = 0L, destination = destination) { _, total ->
            lastTotal = total
        }.getOrThrow()

        assertEquals(payload.size.toLong(), lastTotal)
    }

    @Test
    fun replacesAnExistingFileOnlyAfterTheNewOneVerifies() = runBlocking {
        destination.writeText("previous content")
        server.respondWith { it.reply(200, payload) }

        downloader.download(server.url("/a"), payloadSha, payload.size.toLong(), destination).getOrThrow()

        assertArrayEquals(payload, destination.readBytes())
    }

    @Test
    fun sendsTheBbkbUserAgent() = runBlocking {
        server.respondWith { it.reply(200, payload) }

        downloader.download(server.url("/a"), payloadSha, payload.size.toLong(), destination).getOrThrow()

        val agent = server.userAgentHeaders().single()
        assertNotNull(agent)
        assertTrue("got $agent", agent!!.startsWith("BBKB/"))
        assertTrue("got $agent", agent.contains(context.packageName))
    }

    // ── Integrity ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun aShaMismatchFailsAndDeletesTheFile() = runBlocking {
        server.respondWith { it.reply(200, payload) }
        val wrong = "0".repeat(64)

        val failure = downloader.download(
            server.url("/a"), wrong, payload.size.toLong(), destination
        ).exceptionOrNull()

        assertTrue("got $failure", failure is IntegrityException)
        assertEquals("sha256", (failure as IntegrityException).kind)
        assertEquals(wrong, failure.expected)
        assertEquals(payloadSha, failure.actual)
        assertFalse("bytes that failed the hash must not be left anywhere", destination.exists())
        assertFalse(tempOf(destination).exists())
    }

    @Test
    fun aShaMismatchDoesNotDestroyThePreviousGoodFile() = runBlocking {
        // A failed re-download must not cost the user the copy they already had verified.
        destination.writeText("previously verified")
        server.respondWith { it.reply(200, payload) }

        val failure = downloader.download(
            server.url("/a"), "0".repeat(64), payload.size.toLong(), destination
        ).exceptionOrNull()

        assertTrue(failure is IntegrityException)
        assertEquals("previously verified", destination.readText())
    }

    @Test
    fun aSizeMismatchFailsBeforeTheHashIsEvenConsulted() = runBlocking {
        server.respondWith { it.reply(200, payload) }

        val failure = downloader.download(
            server.url("/a"), payloadSha, expectedSize = payload.size + 1L, destination = destination
        ).exceptionOrNull()

        assertTrue("got $failure", failure is IntegrityException)
        assertEquals("size", (failure as IntegrityException).kind)
        assertEquals((payload.size + 1).toString(), failure.expected)
        assertEquals(payload.size.toString(), failure.actual)
        assertFalse(destination.exists())
        assertFalse(tempOf(destination).exists())
    }

    @Test
    fun aTruncatedBodyFailsTheSizeCheck() = runBlocking {
        // The server promises the full length and sends less. Without the size check this would
        // land as a short file whose hash simply happened not to match.
        server.respondWith { it.reply(200, payload.copyOf(payload.size / 2)) }

        val failure = downloader.download(
            server.url("/a"), payloadSha, payload.size.toLong(), destination
        ).exceptionOrNull()

        assertTrue("got $failure", failure is IntegrityException)
        assertEquals("size", (failure as IntegrityException).kind)
        assertFalse(destination.exists())
    }

    @Test
    fun aBlankExpectedHashSkipsTheCheckButStillDownloads() = runBlocking {
        // The documented opt-out, used by local debug servers. Nothing installable may use it.
        server.respondWith { it.reply(200, payload) }

        val result = downloader.download(server.url("/a"), expectedSha256 = null, expectedSize = 0L, destination = destination)

        assertTrue(result.isSuccess)
        assertArrayEquals(payload, destination.readBytes())
    }

    // ── Redirects ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun followsARedirectChainAcrossHosts() = runBlocking {
        // The GitHub release-asset shape: the download URL bounces to another host, which
        // HttpURLConnection would silently refuse to follow.
        server.respondWith { exchange ->
            when (exchange.path) {
                "/start" -> exchange.redirectTo(302, server.crossHostUrl("/middle"))
                "/middle" -> exchange.redirectTo(307, server.url("/final"))
                else -> exchange.reply(200, payload)
            }
        }

        val result = downloader.download(
            server.url("/start"), payloadSha, payload.size.toLong(), destination
        )

        assertTrue(result.exceptionOrNull()?.toString() ?: "", result.isSuccess)
        assertArrayEquals(payload, destination.readBytes())
        assertEquals(listOf("/start", "/middle", "/final"), server.requestedPaths())
    }

    @Test
    fun followsEveryRedirectStatusTheSpecListsAndResolvesARelativeLocation() = runBlocking {
        for (status in listOf(301, 302, 303, 307, 308)) {
            destination.delete()
            server.resetCounters()
            server.respondWith { exchange ->
                if (exchange.path == "/start") {
                    exchange.redirectTo(status, "/final")  // relative, as servers are allowed to send
                } else {
                    exchange.reply(200, payload)
                }
            }

            val result = downloader.download(
                server.url("/start"), payloadSha, payload.size.toLong(), destination
            )

            assertTrue("$status should have been followed: ${result.exceptionOrNull()}", result.isSuccess)
            assertEquals(listOf("/start", "/final"), server.requestedPaths())
        }
    }

    @Test
    fun aRedirectLoopIsCutOffRatherThanFollowedForever() = runBlocking {
        server.respondWith { it.redirectTo(302, server.url("/loop")) }

        val failure = downloader.download(
            server.url("/loop"), payloadSha, payload.size.toLong(), destination
        ).exceptionOrNull()

        assertTrue("got $failure", failure is TooManyRedirectsException)
        assertEquals(Downloader.MAX_REDIRECTS, (failure as TooManyRedirectsException).hops)
        assertEquals(
            "one request per allowed hop and no more",
            Downloader.MAX_REDIRECTS + 1,
            server.requestCount
        )
        assertFalse(destination.exists())
    }

    @Test
    fun aRedirectOutOfHttpIsRefused() = runBlocking {
        server.respondWith { it.redirectTo(302, "ftp://example.test/artifact.bin") }

        val failure = downloader.download(
            server.url("/start"), payloadSha, payload.size.toLong(), destination
        ).exceptionOrNull()

        assertTrue("got $failure", failure is InsecureUrlException)
        assertFalse(destination.exists())
    }

    @Test
    fun aNonHttpUrlIsRefusedBeforeAnyRequest() = runBlocking {
        val failure = downloader.download(
            "file:///etc/passwd", null, 0L, destination
        ).exceptionOrNull()

        assertTrue("got $failure", failure is InsecureUrlException)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun redirectPolicyRefusesADowngradeAndAllowsACrossHostHop() {
        // The policy in isolation, including the https -> http case a loopback server cannot
        // stage. Integrity here is HTTPS plus the manifest hash, so a silent downgrade would
        // quietly remove one of the two.
        assertFalse(
            "https must never be redirected to cleartext",
            Downloader.isRedirectAllowed("https://a.test/x", "http://a.test/x")
        )
        assertTrue(
            "a cross-host https hop is exactly what GitHub assets do",
            Downloader.isRedirectAllowed("https://github.test/x", "https://objects.test/y")
        )
        assertTrue(Downloader.isRedirectAllowed("http://a.test/x", "https://a.test/x"))
        assertTrue(Downloader.isRedirectAllowed("http://a.test/x", "http://b.test/x"))
        assertFalse(Downloader.isRedirectAllowed("https://a.test/x", "ftp://a.test/x"))
        assertFalse(Downloader.isRedirectAllowed("https://a.test/x", "file:///etc/passwd"))
        assertFalse(Downloader.isRedirectAllowed("https://a.test/x", "/relative"))
    }

    // ── HTTP errors and retries ───────────────────────────────────────────────────────────────

    @Test
    fun anHttpErrorFailsWithItsStatusAndIsNotRetried() = runBlocking {
        // A 404 is a definite answer. Retrying it would only spend the user's battery.
        server.respondWith { it.replyText(404, "nope") }

        val failure = downloader.download(
            server.url("/missing"), payloadSha, payload.size.toLong(), destination
        ).exceptionOrNull()

        assertTrue("got $failure", failure is HttpStatusException)
        assertEquals(404, (failure as HttpStatusException).status)
        assertEquals("a definite status must be asked for exactly once", 1, server.requestCount)
        assertFalse(destination.exists())
    }

    @Test
    fun aServerErrorAlsoFailsWithoutRetrying() = runBlocking {
        server.respondWith { it.replyText(500, "boom") }

        val failure = downloader.download(server.url("/a"), payloadSha, 0L, destination).exceptionOrNull()

        assertTrue("got $failure", failure is HttpStatusException)
        assertEquals(500, (failure as HttpStatusException).status)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aRefusedConnectionIsRetriedTwiceWithBackoff() = runBlocking {
        val deadPort = LocalHttpServer.closedPort()
        val started = System.currentTimeMillis()

        val failure = downloader.download(
            "http://127.0.0.1:$deadPort/artifact.bin", payloadSha, payload.size.toLong(), destination
        ).exceptionOrNull()

        val elapsed = System.currentTimeMillis() - started
        assertTrue("got $failure", failure is TransportException)
        assertTrue(
            "three attempts means waiting out both backoffs (500 ms + 1500 ms), took $elapsed ms",
            elapsed >= 2_000
        )
        assertFalse(destination.exists())
        assertFalse(tempOf(destination).exists())
    }

    @Test
    fun aDefiniteFailureDoesNotWaitOutTheBackoffs() = runBlocking {
        // Negative control for the test above: if 404 were retried, this would take 2 s too.
        server.respondWith { it.replyText(404, "nope") }
        val started = System.currentTimeMillis()

        downloader.download(server.url("/missing"), payloadSha, 0L, destination)

        assertTrue(System.currentTimeMillis() - started < 2_000)
    }

    // ── Cancellation ──────────────────────────────────────────────────────────────────────────

    @Test
    fun cancellingMidStreamDeletesThePartialFileAndLeavesNothingBehind() = runBlocking {
        server.respondWith { it.replySlowly(total = 2_000_000, chunkSize = 4_096, pauseMs = 25L) }

        val job = launch(Dispatchers.IO) {
            downloader.download(server.url("/big.bin"), payloadSha, 2_000_000L, destination)
        }

        // Wait until bytes are actually landing, so this cancels a transfer in progress rather
        // than one that has not started.
        withTimeout(10_000) {
            while (tempOf(destination).length() <= 0L) delay(10)
        }
        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertFalse("the partial temp file must be gone", tempOf(destination).exists())
        assertFalse("a cancelled download must not leave a destination", destination.exists())
    }

    @Test
    fun cancellationIsNotReportedAsAFailedResult() = runBlocking {
        // A Result coming out of download() always describes a download that ran to a
        // conclusion; cancellation propagates as cancellation instead.
        server.respondWith { it.replySlowly(total = 2_000_000, chunkSize = 4_096, pauseMs = 25L) }
        var result: Result<File>? = null

        val job = launch(Dispatchers.IO) {
            result = downloader.download(server.url("/big.bin"), payloadSha, 2_000_000L, destination)
        }
        withTimeout(10_000) {
            while (tempOf(destination).length() <= 0L) delay(10)
        }
        job.cancel()
        job.join()

        assertEquals(null, result)
    }

    // ── Download directory housekeeping ───────────────────────────────────────────────────────

    @Test
    fun downloadsLandInTheCacheDownloadsDirectory() {
        assertEquals(File(context.cacheDir, "downloads"), downloader.downloadDir())
        assertEquals(
            File(context.cacheDir, "downloads/pack.ldb"),
            downloader.destinationFor("pack.ldb")
        )
        assertTrue(downloader.downloadDir().isDirectory)
    }

    @Test
    fun clearStaleDeletesOnlyWhatIsOlderThanTheCutoff() {
        val old = File(downloader.downloadDir(), "old.apk").apply { writeText("old") }
        val orphanedTemp = File(downloader.downloadDir(), "half.apk.tmp").apply { writeText("half") }
        val fresh = File(downloader.downloadDir(), "fresh.apk").apply { writeText("fresh") }
        val week = 7L * 24 * 60 * 60 * 1000
        old.setLastModified(System.currentTimeMillis() - week)
        orphanedTemp.setLastModified(System.currentTimeMillis() - week)

        val deleted = downloader.clearStale(maxAgeMs = 24L * 60 * 60 * 1000)

        assertEquals(2, deleted)
        assertFalse(old.exists())
        assertFalse("an orphaned temp file is exactly what this is for", orphanedTemp.exists())
        assertTrue("a recent download must survive", fresh.exists())
    }

    @Test
    fun clearStaleOnAnEmptyDirectoryDeletesNothing() {
        assertEquals(0, downloader.clearStale(maxAgeMs = 0L))
    }

    @Test
    fun downloadCreatesAMissingDestinationDirectory() = runBlocking {
        server.respondWith { it.reply(200, "hello".toByteArray(StandardCharsets.UTF_8)) }
        val nested = File(downloader.downloadDir(), "nested/deeper/file.bin")

        downloader.download(
            server.url("/a"), Sha256.of("hello".toByteArray()), 5L, nested
        ).getOrThrow()

        assertEquals("hello", nested.readText())
    }
}

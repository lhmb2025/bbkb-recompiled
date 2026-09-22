package dev.bbkb.ime.core.distribution

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * [ManifestSource] against a real HTTP server, with the clock and the connectivity check
 * injected so freshness and offline behaviour are decided rather than waited for.
 *
 * Two properties are load-bearing and every case here exists to pin one of them:
 *
 *  - **provenance is never hidden** — a manifest that came off disk says so, and one that was
 *    just parsed off the wire says that;
 *  - **a bad response never costs you a good cache** — the cache is the only offline answer to
 *    "what packs exist", so a 500 or a truncated body must leave it alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ManifestSourceTest {

    private lateinit var context: Context
    private lateinit var server: LocalHttpServer

    private var now = 1_700_000_000_000L
    private var online = true
    private var url = ""

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        server = LocalHttpServer()
        now = 1_700_000_000_000L
        online = true
        url = server.url("/dist/manifest.json")
        cacheFile().delete()
        metaFile().delete()
    }

    @After
    fun tearDown() {
        server.close()
        cacheFile().delete()
        metaFile().delete()
    }

    private fun cacheFile(): File = DistributionConfig.manifestCacheFile(context)
    private fun metaFile(): File = DistributionConfig.manifestMetaFile(context)

    private fun source(cacheMaxAgeMs: Long = DistributionConfig.CACHE_MAX_AGE_MS) = ManifestSource(
        context = context,
        manifestUrlProvider = { url },
        isOnline = { online },
        clock = { now },
        cacheMaxAgeMs = cacheMaxAgeMs,
        connectTimeoutMs = 4_000,
        readTimeoutMs = 4_000,
    )

    private fun manifestJson(versionCode: Int = 1430, packVersion: String = "1902.01") = """
        {"schema": 1, "generated": "2026-09-22T00:00:00Z",
         "app": {"release": {"versionCode": $versionCode, "versionName": "5.0.0-beta.18",
                             "url": "https://example.test/bbkb.apk", "sha256": "ab",
                             "size": 12345678, "minSdk": 23}},
         "packs": {"version": "$packVersion", "baseUrl": "https://example.test/p/",
                   "items": [{"locale": "af", "name": "Afrikaans", "file": "af.ldb",
                              "sha256": "cd", "size": 4400000}]}}
    """.trimIndent()

    private fun serveManifest(json: String = manifestJson(), etag: String? = null) {
        server.respondWith { exchange ->
            val headers = if (etag == null) emptyMap() else mapOf("ETag" to etag)
            exchange.replyText(200, json, headers)
        }
    }

    private val sixHours = DistributionConfig.CACHE_MAX_AGE_MS

    // ── Fetching ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun fetchesParsesAndStampsFreshProvenance() = runBlocking {
        serveManifest()

        val manifest = source().fetch(force = false).getOrThrow()

        assertEquals(1, manifest.schema)
        assertEquals(1430, manifest.appFor("release")!!.versionCode)
        assertEquals("Afrikaans", manifest.packFor("af")!!.name)
        assertFalse("just parsed off the wire is not from the cache", manifest.fromCache)
        assertEquals(now, manifest.fetchedAt)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun writesTheCacheAndItsMetadataUnderFilesDistribution() = runBlocking {
        serveManifest(etag = "\"v1\"")

        source().fetch(force = false).getOrThrow()

        assertEquals(File(context.filesDir, "distribution/manifest.json"), cacheFile())
        assertTrue(cacheFile().isFile)
        assertTrue(metaFile().isFile)
        val meta = metaFile().readText()
        assertTrue("the metadata records the ETag: $meta", meta.contains("v1"))
        assertTrue("and which URL produced it: $meta", meta.contains("/dist/manifest.json"))
    }

    @Test
    fun sendsTheBbkbUserAgent() = runBlocking {
        serveManifest()

        source().fetch(force = false).getOrThrow()

        val agent = server.userAgentHeaders().single()
        assertNotNull(agent)
        assertTrue("got $agent", agent!!.startsWith("BBKB/"))
        assertTrue("got $agent", agent.contains(context.packageName))
    }

    @Test
    fun followsARedirectToTheManifest() = runBlocking {
        server.respondWith { exchange ->
            if (exchange.path.endsWith("manifest.json")) {
                exchange.redirectTo(302, server.crossHostUrl("/moved.json"))
            } else {
                exchange.replyText(200, manifestJson())
            }
        }

        val manifest = source().fetch(force = false).getOrThrow()

        assertEquals(1430, manifest.appFor("release")!!.versionCode)
        assertEquals(2, server.requestCount)
    }

    // ── Cache freshness ───────────────────────────────────────────────────────────────────────

    @Test
    fun aCacheYoungerThanSixHoursIsServedWithoutAnyNetworkCall() = runBlocking {
        serveManifest()
        val source = source()
        source.fetch(force = false).getOrThrow()
        server.resetCounters()

        now += sixHours - 1
        val manifest = source.fetch(force = false).getOrThrow()

        assertEquals("nothing may reach the network", 0, server.requestCount)
        assertTrue("and it must admit it came from the cache", manifest.fromCache)
        assertEquals("with the time it was really fetched", 1_700_000_000_000L, manifest.fetchedAt)
        assertEquals(1430, manifest.appFor("release")!!.versionCode)
    }

    @Test
    fun aCacheOlderThanSixHoursGoesBackToTheNetwork() = runBlocking {
        serveManifest()
        val source = source()
        source.fetch(force = false).getOrThrow()
        server.resetCounters()
        serveManifest(manifestJson(versionCode = 1440))

        now += sixHours
        val manifest = source.fetch(force = false).getOrThrow()

        assertEquals(1, server.requestCount)
        assertFalse(manifest.fromCache)
        assertEquals(1440, manifest.appFor("release")!!.versionCode)
        assertEquals(now, manifest.fetchedAt)
    }

    @Test
    fun forceSkipsTheFreshnessShortcut() = runBlocking {
        serveManifest()
        val source = source()
        source.fetch(force = false).getOrThrow()
        server.resetCounters()
        serveManifest(manifestJson(versionCode = 1441))

        val manifest = source.fetch(force = true).getOrThrow()

        assertEquals(1, server.requestCount)
        assertEquals(1441, manifest.appFor("release")!!.versionCode)
        assertFalse(manifest.fromCache)
    }

    @Test
    fun hasFreshCacheTracksTheSameWindow() = runBlocking {
        serveManifest()
        val source = source()
        assertFalse("nothing cached yet", source.hasFreshCache())

        source.fetch(force = false).getOrThrow()
        assertTrue(source.hasFreshCache())

        now += sixHours
        assertFalse(source.hasFreshCache())
    }

    @Test
    fun cachedReadsTheCacheWithoutTouchingTheNetwork() = runBlocking {
        serveManifest()
        val source = source()
        source.fetch(force = false).getOrThrow()
        server.resetCounters()

        val cached = source.cached()!!

        assertEquals(0, server.requestCount)
        assertTrue(cached.fromCache)
        assertEquals(1_700_000_000_000L, cached.fetchedAt)
        assertEquals(1430, cached.appFor("release")!!.versionCode)
    }

    @Test
    fun clearCacheRemovesBothFiles() = runBlocking {
        serveManifest()
        val source = source()
        source.fetch(force = false).getOrThrow()

        source.clearCache()

        assertFalse(cacheFile().exists())
        assertFalse(metaFile().exists())
        assertNull(source.cached())
    }

    // ── ETag ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun revalidatesWithIfNoneMatchAndHonoursA304() = runBlocking {
        serveManifest(etag = "\"v1\"")
        val source = source()
        source.fetch(force = false).getOrThrow()
        server.resetCounters()
        server.respondWith { exchange ->
            assertEquals("\"v1\"", exchange.header("If-None-Match"))
            exchange.notModified("\"v1\"")
        }

        now += sixHours
        val manifest = source.fetch(force = true).getOrThrow()

        assertEquals(1, server.requestCount)
        assertEquals(listOf("\"v1\""), server.ifNoneMatchHeaders())
        assertTrue("a 304 means the bytes came off disk", manifest.fromCache)
        assertEquals("but they were re-validated just now", now, manifest.fetchedAt)
        assertEquals(1430, manifest.appFor("release")!!.versionCode)
    }

    @Test
    fun a304RestartsTheFreshnessWindow() = runBlocking {
        serveManifest(etag = "\"v1\"")
        val source = source()
        source.fetch(force = false).getOrThrow()
        now += sixHours
        server.respondWith { it.notModified("\"v1\"") }
        source.fetch(force = true).getOrThrow()
        server.resetCounters()

        // Re-validated at `now`, so an ordinary fetch a moment later must not go out again.
        now += 1_000
        val manifest = source.fetch(force = false).getOrThrow()

        assertEquals(0, server.requestCount)
        assertTrue(manifest.fromCache)
    }

    @Test
    fun noIfNoneMatchIsSentWhenTheServerNeverGaveAnEtag() = runBlocking {
        serveManifest(etag = null)
        val source = source()
        source.fetch(force = false).getOrThrow()
        server.resetCounters()
        serveManifest(etag = null)

        source.fetch(force = true).getOrThrow()

        assertEquals(listOf<String?>(null), server.ifNoneMatchHeaders())
    }

    @Test
    fun a304WithAnUnreadableCacheRefetchesTheWholeThing() = runBlocking {
        // The ETag we sent was stale state: the server says "unchanged" but what we hold cannot
        // be read, so there is nothing to be unchanged from. Asking again without the header is
        // the only way out of that corner.
        //
        // Note the cache file is corrupted, not deleted: the stored metadata is deliberately
        // ignored when the cache file is missing, so a deleted cache sends no ETag in the first
        // place (see aDeletedCacheSendsNoEtagAtAll). A file that is present but unparseable is
        // the state that actually reaches this branch.
        serveManifest(etag = "\"v1\"")
        val source = source()
        source.fetch(force = false).getOrThrow()
        cacheFile().writeText("{ truncated half-written jso")
        server.resetCounters()
        server.respondWith { exchange ->
            if (exchange.header("If-None-Match") != null) {
                exchange.notModified("\"v1\"")
            } else {
                exchange.replyText(200, manifestJson(versionCode = 1450), mapOf("ETag" to "\"v2\""))
            }
        }

        val manifest = source.fetch(force = true).getOrThrow()

        assertEquals(2, server.requestCount)
        assertEquals(1450, manifest.appFor("release")!!.versionCode)
        assertFalse(manifest.fromCache)
    }

    @Test
    fun aDeletedCacheSendsNoEtagAtAll() = runBlocking {
        // Negative control for the test above, and the reason it corrupts rather than deletes:
        // the stored ETag is only ever sent alongside a cache file that still exists, so losing
        // the cache cannot produce a 304 the app has nothing to satisfy.
        serveManifest(etag = "\"v1\"")
        val source = source()
        source.fetch(force = false).getOrThrow()
        cacheFile().delete()
        server.resetCounters()
        serveManifest(manifestJson(versionCode = 1451), etag = "\"v2\"")

        val manifest = source.fetch(force = true).getOrThrow()

        assertEquals(1, server.requestCount)
        assertEquals(listOf<String?>(null), server.ifNoneMatchHeaders())
        assertEquals(1451, manifest.appFor("release")!!.versionCode)
    }

    // ── Offline ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun offlineServesTheCacheAtAnyAge() = runBlocking {
        serveManifest()
        val source = source()
        source.fetch(force = false).getOrThrow()
        server.resetCounters()

        online = false
        now += 30L * 24 * 60 * 60 * 1000  // a month later
        val manifest = source.fetch(force = true).getOrThrow()

        assertEquals("no point dialling a dead network", 0, server.requestCount)
        assertTrue(manifest.fromCache)
        assertEquals("and the age is reported honestly", 1_700_000_000_000L, manifest.fetchedAt)
    }

    @Test
    fun offlineWithNothingCachedFailsAsOffline() = runBlocking {
        online = false

        val failure = source().fetch(force = false).exceptionOrNull()

        assertTrue("got $failure", failure is OfflineException)
        assertEquals(0, server.requestCount)
    }

    // ── A bad response never costs a good cache ───────────────────────────────────────────────

    @Test
    fun anHttpErrorIsReportedAndNotPapieredOverWithTheCache() = runBlocking {
        // The caller asked to go to the network; it deserves to hear that it failed. `cached()`
        // stays available for a caller that wants the fallback explicitly.
        serveManifest()
        val source = source()
        source.fetch(force = false).getOrThrow()
        server.respondWith { it.replyText(500, "boom") }

        now += sixHours
        val failure = source.fetch(force = true).exceptionOrNull()

        assertTrue("got $failure", failure is HttpStatusException)
        assertEquals(500, (failure as HttpStatusException).status)
        assertNotNull("the cache is untouched and still explicitly readable", source.cached())
        assertEquals(1430, source.cached()!!.appFor("release")!!.versionCode)
    }

    @Test
    fun aMalformedResponseDoesNotClobberTheCache() = runBlocking {
        serveManifest()
        val source = source()
        source.fetch(force = false).getOrThrow()
        server.respondWith { it.replyText(200, "this is not json") }

        val failure = source.fetch(force = true).exceptionOrNull()

        assertTrue("got $failure", failure is ManifestFormatException)
        assertEquals(1430, source.cached()!!.appFor("release")!!.versionCode)
    }

    @Test
    fun aSchemaTooNewIsRejectedAndDoesNotClobberTheCache() = runBlocking {
        serveManifest()
        val source = source()
        source.fetch(force = false).getOrThrow()
        server.respondWith { it.replyText(200, manifestJson().replace("\"schema\": 1", "\"schema\": 2")) }

        val failure = source.fetch(force = true).exceptionOrNull()

        assertTrue("got $failure", failure is UnsupportedSchemaException)
        assertEquals(2, (failure as UnsupportedSchemaException).schema)
        assertEquals(1430, source.cached()!!.appFor("release")!!.versionCode)
    }

    @Test
    fun aCacheThisBuildCannotParseIsTreatedAsNoCache() = runBlocking {
        // Guards a lock-out: a cache file from a newer schema must not make every offline call
        // fail forever.
        serveManifest()
        val source = source()
        source.fetch(force = false).getOrThrow()
        cacheFile().writeText(manifestJson().replace("\"schema\": 1", "\"schema\": 7"))

        online = false
        val failure = source.fetch(force = false).exceptionOrNull()

        assertTrue("got $failure", failure is OfflineException)
        assertNull(source.cached())
    }

    @Test
    fun aRefusedConnectionIsATransportFailure() = runBlocking {
        url = "http://127.0.0.1:${LocalHttpServer.closedPort()}/dist/manifest.json"

        val failure = source().fetch(force = false).exceptionOrNull()

        assertTrue("got $failure", failure is TransportException)
    }

    @Test
    fun aNonHttpManifestUrlIsRefusedBeforeAnyRequest() = runBlocking {
        url = "file:///tmp/manifest.json"

        val failure = source().fetch(force = false).exceptionOrNull()

        assertTrue("got $failure", failure is InsecureUrlException)
    }

    // ── The cache is keyed by URL ─────────────────────────────────────────────────────────────

    @Test
    fun pointingAtADifferentUrlInvalidatesTheCache() = runBlocking {
        // Flipping the debug manifest-URL override must not serve a local test manifest as
        // though it had come from the production URL, or the reverse.
        serveManifest()
        val source = source()
        source.fetch(force = false).getOrThrow()
        server.resetCounters()
        serveManifest(manifestJson(versionCode = 1460))

        url = server.url("/other/manifest.json")
        val manifest = source.fetch(force = false).getOrThrow()

        assertEquals("the fresh cache for the old URL must not be reused", 1, server.requestCount)
        assertEquals(listOf("/other/manifest.json"), server.requestedPaths())
        assertFalse(manifest.fromCache)
        assertEquals(1460, manifest.appFor("release")!!.versionCode)
    }

    @Test
    fun cachedIsNullForAUrlThatNeverProducedIt() = runBlocking {
        serveManifest()
        source().fetch(force = false).getOrThrow()

        url = server.url("/somewhere/else.json")

        assertNull(source().cached())
        assertFalse(source().hasFreshCache())
    }
}

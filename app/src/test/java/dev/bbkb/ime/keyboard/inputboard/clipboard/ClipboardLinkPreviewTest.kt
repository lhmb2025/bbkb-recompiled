package dev.bbkb.ime.keyboard.inputboard.clipboard

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * The clipboard board's link-preview stack, minus the network:
 *  - [UrlUtils]: which rows are treated as images, how a bare host becomes a URL, and the
 *    https-only guard every fetch goes through;
 *  - [OpenGraphMetadata.parse]: which meta tags become a title and a thumbnail;
 *  - [OpenGraphFetcher]'s bounds: the byte cap and deadline on a body read, and the decode
 *    subsampling;
 *  - [ClipboardWebImageProvider]'s bookkeeping, with a fake loader: results keyed by entry id,
 *    failures remembered, nothing fetched while the setting is off, nothing after release.
 *
 * The jsoup connection and the bitmap decode themselves stay uncovered: they need a network and a
 * real `Bitmap`. Plain JVM tests — the provider runs on [Dispatchers.Unconfined], so a fetch
 * completes inside the call that started it unless the fake loader suspends.
 */
class ClipboardLinkPreviewTest {

    // ═══════════════════════════════════════════════════════════════════════
    // UrlUtils.isImageUrl — "this row IS the image, skip the Open Graph fetch"
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun theFourImageExtensionsAreRecognised() {
        assertTrue(UrlUtils.isImageUrl("https://example.com/a.jpg"))
        assertTrue(UrlUtils.isImageUrl("https://example.com/a.png"))
        assertTrue(UrlUtils.isImageUrl("https://example.com/a.gif"))
        assertTrue(UrlUtils.isImageUrl("https://example.com/a.bmp"))
    }

    @Test
    fun theExtensionMatchIsCaseInsensitive() {
        assertTrue(UrlUtils.isImageUrl("https://example.com/A.JPG"))
        assertTrue(UrlUtils.isImageUrl("https://example.com/a.PnG"))
    }

    @Test
    fun theExtensionMustEndTheString() {
        assertFalse("a query string defeats it", UrlUtils.isImageUrl("https://example.com/a.jpg?w=10"))
        assertFalse("a fragment defeats it", UrlUtils.isImageUrl("https://example.com/a.png#x"))
        assertFalse("mid-path does not count", UrlUtils.isImageUrl("https://example.com/a.jpg/b"))
    }

    @Test
    fun otherExtensionsAndBareHostsAreNotImages() {
        assertFalse(UrlUtils.isImageUrl("https://example.com"))
        assertFalse(UrlUtils.isImageUrl("https://example.com/a.jpeg"))
        assertFalse(UrlUtils.isImageUrl("https://example.com/a.webp"))
        assertFalse(UrlUtils.isImageUrl("https://example.com/a.svg"))
    }

    @Test
    fun whitespaceAnywhereDefeatsTheMatch() {
        assertFalse(UrlUtils.isImageUrl("https://example.com/a b.jpg"))
        assertFalse(UrlUtils.isImageUrl(" https://example.com/a.jpg"))
    }

    @Test
    fun aBareFilenameOrRelativePathIsNotAnImageUrl() {
        // A host is required, so pasted text merely ending in .png never reaches the downloader.
        assertFalse(UrlUtils.isImageUrl("holiday.png"))
        assertFalse(UrlUtils.isImageUrl("some/relative/path.gif"))
    }

    @Test
    fun theSchemeIsOptionalButMustBeHttpsWhenPresent() {
        // Rows arrive via Patterns.WEB_URL, which accepts bare hosts; ensureScheme adds https://.
        assertTrue(UrlUtils.isImageUrl("example.com/a.png"))
        assertTrue(UrlUtils.isImageUrl("https://example.com:8443/img/a.gif"))
        assertFalse("previews are https only", UrlUtils.isImageUrl("http://example.com:8080/img/a.gif"))
        assertFalse(UrlUtils.isImageUrl("ftp://example.com/a.png"))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // UrlUtils.ensureScheme — how a bare host reaches the network
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun anAbsoluteUrlIsLeftAlone() {
        assertEquals("https://example.com/x", UrlUtils.ensureScheme("https://example.com/x"))
        assertEquals("http://example.com", UrlUtils.ensureScheme("http://example.com"))
    }

    @Test
    fun aBareHostGetsHttps() {
        assertEquals("https://example.com", UrlUtils.ensureScheme("example.com"))
    }

    @Test
    fun anyStringJavaCanParseAsAUrlIsLeftAlone() {
        // "valid" means java.net.URL accepts it, i.e. the scheme is one the JVM has a handler for —
        // so ftp: and file: survive ensureScheme untouched. The fetchers no longer call ensureScheme
        // directly: they go through httpsUrlOrNull, which refuses these (see below).
        assertEquals("file:///etc/passwd", UrlUtils.ensureScheme("file:///etc/passwd"))
        assertEquals("ftp://example.com/x", UrlUtils.ensureScheme("ftp://example.com/x"))
        assertEquals("mailto:someone@example.com", UrlUtils.ensureScheme("mailto:someone@example.com"))
    }

    @Test
    fun anythingThatCannotBecomeAUrlIsRefusedWithNull() {
        // Unparseable input yields null, so the callers skip the fetch instead of requesting nonsense.
        assertNull(UrlUtils.ensureScheme("not a url at all"))
        assertNull(UrlUtils.ensureScheme("bogus://x"))
        assertNull(UrlUtils.ensureScheme(""))
        assertNull(UrlUtils.ensureScheme(null))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // UrlUtils.httpsUrlOrNull — the only URLs the preview fetchers may request
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun httpsUrlsAndBareHostsAreFetchable() {
        assertEquals("https://example.com/x", UrlUtils.httpsUrlOrNull("https://example.com/x"))
        assertEquals("HTTPS://example.com/x", UrlUtils.httpsUrlOrNull("HTTPS://example.com/x"))
        assertEquals("https://example.com", UrlUtils.httpsUrlOrNull("example.com"))
    }

    @Test
    fun everyNonHttpsSchemeIsRefused() {
        // og:image is whatever the fetched page says; none of these may reach URL.openStream().
        assertNull(UrlUtils.httpsUrlOrNull("file:///etc/passwd"))
        assertNull(UrlUtils.httpsUrlOrNull("file:///data/data/com.blackberry.keyboard/shared_prefs/x.xml"))
        assertNull(UrlUtils.httpsUrlOrNull("jar:file:///sdcard/a.jar!/a.png"))
        assertNull(UrlUtils.httpsUrlOrNull("ftp://example.com/a.png"))
        assertNull(UrlUtils.httpsUrlOrNull("mailto:someone@example.com"))
        assertNull("cleartext http is refused too", UrlUtils.httpsUrlOrNull("http://example.com/a.png"))
    }

    @Test
    fun whatEnsureSchemeRefusesStaysRefused() {
        assertNull(UrlUtils.httpsUrlOrNull("not a url at all"))
        assertNull(UrlUtils.httpsUrlOrNull(""))
        assertNull(UrlUtils.httpsUrlOrNull(null))
        assertNull("https with no host", UrlUtils.httpsUrlOrNull("https:///a.png"))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // OpenGraphMetadata.parse — which meta tags become a preview
    // ═══════════════════════════════════════════════════════════════════════

    private fun parse(html: String): OpenGraphMetadata =
        OpenGraphMetadata().apply { parse(Jsoup.parse(html, "https://example.com")) }

    @Test
    fun ogTitleAndOgImageAreLifted() {
        val meta = parse(
            """
            <html><head>
              <meta property="og:title" content="The Title">
              <meta property="og:image" content="https://example.com/thumb.png">
            </head><body></body></html>
            """,
        )

        assertEquals("The Title", meta.title)
        assertEquals("https://example.com/thumb.png", meta.imageUrl)
    }

    @Test
    fun aDocumentWithNoOpenGraphTagsLeavesEverythingNull() {
        val meta = parse("<html><head><title>plain</title></head><body>hi</body></html>")

        assertNull(meta.title)
        assertNull(meta.imageUrl)
    }

    @Test
    fun aNullDocumentIsAHarmlessNoOp() {
        val meta = OpenGraphMetadata()

        meta.parse(null)

        assertNull(meta.title)
        assertNull(meta.imageUrl)
    }

    @Test
    fun onlyTheFirstOfEachTagWins() {
        val meta = parse(
            """
            <html><head>
              <meta property="og:title" content="first">
              <meta property="og:title" content="second">
            </head></html>
            """,
        )

        assertEquals("first", meta.title)
    }

    @Test
    fun anOgTitleWithNoOrEmptyContentLeavesTheTitleNull() {
        // A missing or blank content attribute leaves the title null, so the row falls back to the URL.
        assertNull(parse("""<html><head><meta property="og:title"></head></html>""").title)
        assertNull(parse("""<html><head><meta property="og:title" content="  "></head></html>""").title)
    }

    @Test
    fun theTagsMustCarryPropertyNotName() {
        // The selector is `meta[property^=og:]`, so Twitter-card-style `name=` tags are ignored.
        val meta = parse("""<html><head><meta name="og:title" content="ignored"></head></html>""")

        assertNull(meta.title)
    }

    @Test
    fun theUrlAndImageAccessorsAreIndependentOfParsing() {
        val meta = OpenGraphMetadata()
        meta.url = "https://example.com/page"
        meta.imageUrl = "https://example.com/i.png"

        assertEquals("https://example.com/page", meta.url)
        assertEquals("https://example.com/i.png", meta.imageUrl)
        assertNull("no bitmap until one is downloaded", meta.image)
    }

    @Test
    fun parsingDoesNotOverwriteAnImageUrlSetByTheImageFastPath() {
        // CHARACTERISED: the fast path in ClipboardWebImageProvider.loadPreview sets imageUrl from
        // the row text and never parses, but if parse() ever ran afterwards on a document with no
        // og:image the existing value would survive — parse only writes when the tag is present.
        val meta = OpenGraphMetadata()
        meta.imageUrl = "https://example.com/direct.png"

        meta.parse(Jsoup.parse("<html><head></head></html>", "https://example.com"))

        assertEquals("https://example.com/direct.png", meta.imageUrl)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // OpenGraphFetcher bounds — what a hostile or slow server can make the keyboard do
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun aBodyWithinTheCapIsReadWhole() {
        val body = ByteArray(20_000) { it.toByte() }

        val read = OpenGraphFetcher.readCapped(ByteArrayInputStream(body), 20_000, 5_000)

        assertTrue(body.contentEquals(read))
    }

    @Test
    fun aBodyOverTheCapIsRefusedNotTruncated() {
        val read = OpenGraphFetcher.readCapped(ByteArrayInputStream(ByteArray(20_001)), 20_000, 5_000)

        assertNull(read)
    }

    @Test
    fun aBodyStillArrivingAfterTheDeadlineIsAbandoned() {
        // A server that answers every read just inside the socket timeout: the clock the reader
        // consults moves a second per read, so the 5 s deadline passes on the sixth.
        var clock = 0L
        var reads = 0
        val dripping = object : InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                reads++
                clock += 1_000_000_000L
                b[off] = 1
                return 1
            }
        }

        val read = OpenGraphFetcher.readCapped(dripping, OpenGraphFetcher.MAX_IMAGE_BYTES, 5_000) { clock }

        assertNull(read)
        assertEquals("stopped at the deadline, not at the byte cap", 6, reads)
    }

    @Test
    fun theDocumentedCapsAndTimeoutAreTheOnesInForce() {
        assertEquals(5_000, OpenGraphFetcher.TIMEOUT_MS)
        assertEquals(1024 * 1024, OpenGraphFetcher.MAX_PAGE_BYTES)
        assertEquals(2 * 1024 * 1024, OpenGraphFetcher.MAX_IMAGE_BYTES)
    }

    @Test
    fun decodeSubsamplingStopsAboveTheTarget() {
        assertEquals("already small", 1, OpenGraphFetcher.sampleSizeFor(100, 100, 147, 147))
        assertEquals(1, OpenGraphFetcher.sampleSizeFor(200, 200, 147, 147))
        assertEquals(2, OpenGraphFetcher.sampleSizeFor(300, 300, 147, 147))
        assertEquals("a huge image decodes small", 64, OpenGraphFetcher.sampleSizeFor(10_000, 10_000, 147, 147))
        assertEquals("the shorter side decides", 2, OpenGraphFetcher.sampleSizeFor(4_000, 300, 147, 147))
        assertEquals("a zero target never loops", 1, OpenGraphFetcher.sampleSizeFor(4_000, 4_000, 0, 0))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ClipboardWebImageProvider — results by entry id, failures cached
    // ═══════════════════════════════════════════════════════════════════════

    private val requested = mutableListOf<String>()
    private val ready = mutableListOf<Long>()

    private fun provider(result: (String) -> OpenGraphMetadata?): ClipboardWebImageProvider =
        ClipboardWebImageProvider({ url -> requested += url; result(url) }, Dispatchers.Unconfined).apply {
            isEnabled = true
            listener = ClipboardWebImageProvider.Listener { ready += it }
        }

    private fun preview(url: String, title: String?): OpenGraphMetadata? =
        OpenGraphMetadata().apply { this.url = url; parse(Jsoup.parse(
            if (title == null) "<html></html>" else "<html><head><meta property=\"og:title\" content=\"$title\"></head></html>"
        )) }.takeIf { it.title != null }

    @Test
    fun aFinishedPreviewIsFiledUnderItsEntryAndAnnounced() {
        val p = provider { url -> preview(url, "Title") }

        p.request(7, "https://example.com")

        assertEquals(listOf(7L), ready)
        assertEquals("Title", p.previewFor(7)?.title)
        assertNull("nothing for another entry", p.previewFor(8))
    }

    @Test
    fun aFailureIsRememberedSoTheLinkIsNotFetchedAgain() {
        val p = provider { null }

        p.request(7, "https://dead.example.com")
        p.request(7, "https://dead.example.com")

        assertEquals("fetched once", 1, requested.size)
        assertTrue(p.hasResultFor(7))
        assertNull("but there is nothing to show", p.previewFor(7))
        assertTrue("and nobody is told about a failure", ready.isEmpty())
    }

    @Test
    fun aLoaderThatThrowsCountsAsAFailure() {
        val p = provider { throw IllegalStateException("boom") }

        p.request(7, "https://example.com")

        assertTrue(p.hasResultFor(7))
        assertNull(p.previewFor(7))
    }

    @Test
    fun anEntryIsNotFetchedTwiceWhileItsFirstFetchIsInFlight() {
        val gate = CompletableDeferred<OpenGraphMetadata?>()
        val p = ClipboardWebImageProvider({ url -> requested += url; gate.await() }, Dispatchers.Unconfined)
        p.isEnabled = true
        p.listener = ClipboardWebImageProvider.Listener { ready += it }

        p.request(7, "https://example.com")
        p.request(7, "https://example.com")
        assertTrue(p.isFetching(7))
        gate.complete(preview("https://example.com", "Late"))

        assertEquals(1, requested.size)
        assertFalse(p.isFetching(7))
        assertEquals(listOf(7L), ready)
    }

    @Test
    fun nothingIsFetchedWhilePreviewsAreOff() {
        val p = provider { url -> preview(url, "Title") }
        p.isEnabled = false

        p.request(7, "https://example.com")

        assertTrue(requested.isEmpty())
        assertNull(p.previewFor(7))
    }

    @Test
    fun turningPreviewsOffForgetsEveryResult() {
        val p = provider { url -> preview(url, "Title") }
        p.request(7, "https://example.com")

        p.isEnabled = false
        p.isEnabled = true

        assertFalse(p.hasResultFor(7))
        p.request(7, "https://example.com")
        assertEquals("fetched afresh", 2, requested.size)
    }

    @Test
    fun retainOnlyDropsDeletedEntriesAndCancelsTheirFetches() {
        val gate = CompletableDeferred<OpenGraphMetadata?>()
        val p = ClipboardWebImageProvider({ url ->
            if (url.contains("slow")) gate.await() else preview(url, "Kept")
        }, Dispatchers.Unconfined)
        p.isEnabled = true
        p.listener = ClipboardWebImageProvider.Listener { ready += it }
        p.request(1, "https://kept.example.com")
        p.request(2, "https://gone.example.com")
        p.request(3, "https://slow.example.com")

        p.retainOnly(setOf(1L))
        gate.complete(preview("https://slow.example.com", "Too late"))

        assertTrue(p.hasResultFor(1))
        assertFalse(p.hasResultFor(2))
        assertFalse("cancelled, so never filed", p.hasResultFor(3))
        assertFalse(p.isFetching(3))
        assertEquals(listOf(1L, 2L), ready)
    }

    @Test
    fun aReleasedProviderFetchesNothingAndTellsNobody() {
        val p = provider { url -> preview(url, "Title") }
        val listenerBefore = p.listener

        p.release()
        p.request(7, "https://example.com")

        assertTrue(requested.isEmpty())
        assertNull(p.listener)
        assertTrue(listenerBefore != null)
    }

    @Test
    fun theSameMetadataInstanceIsServedOnEveryBind() {
        val p = provider { url -> preview(url, "Title") }
        p.request(7, "https://example.com")

        assertSame(p.previewFor(7), p.previewFor(7))
    }
}

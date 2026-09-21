package dev.bbkb.ime.keyboard.inputboard.clipboard

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterisation tests for the pure half of the clipboard board's link-preview stack:
 * [UrlUtils] (which rows are treated as images, how a bare host is turned into a URL) and
 * [OpenGraphMetadata.parse] (which meta tags become a title and a thumbnail).
 *
 * These are the two decisions that determine what a URL row looks like. The rest of the stack —
 * the jsoup connection, the bitmap download and scale, the cache and its bitmap recycling — is
 * network and `Bitmap`-bound and is deliberately **not** covered here; that gap is reported.
 *
 * As everywhere in this wave, these pin **what the code does today**. Assertions recording
 * behaviour that is plainly wrong are marked `CHARACTERISED BUG:`. Plain JVM tests: neither class
 * touches the Android framework (`OpenGraphMetadata`'s only Android type is the `Bitmap` field,
 * which `parse` never reads).
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
}

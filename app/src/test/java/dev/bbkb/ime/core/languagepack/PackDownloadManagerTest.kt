package dev.bbkb.ime.core.languagepack

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.distribution.Downloader
import dev.bbkb.ime.core.distribution.IntegrityException
import dev.bbkb.ime.core.distribution.LocalHttpServer
import dev.bbkb.ime.core.distribution.PackEntry
import dev.bbkb.ime.core.distribution.Packs
import dev.bbkb.ime.core.distribution.Sha256
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * [PackDownloadManager] against a real HTTP server on a real socket — see [LocalHttpServer] for
 * why a mock would not be worth writing.
 *
 * The invariants: a pack that reports installed **is** on disk; a pack whose bytes did not match
 * the catalogue leaves nothing behind; a cancelled download leaves nothing behind *and* does not
 * report a failure; and two requests are two downloads in order, not two at once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PackDownloadManagerTest {

    private lateinit var context: Context
    private lateinit var server: LocalHttpServer
    private lateinit var downloader: Downloader
    private lateinit var manager: PackDownloadManager
    private lateinit var scope: CoroutineScope
    private val posted = mutableListOf<String>()

    private val welshBytes = PackFixtures.ldbBytes(seed = 3, size = 40_000)
    private val afrikaansBytes = PackFixtures.ldbBytes(seed = 9, size = 30_000)

    private fun welsh(sha: String = Sha256.of(welshBytes)): PackEntry = PackFixtures.entry(
        locale = "cy", file = "welsh.ldb", sha256 = sha, size = welshBytes.size.toLong(), name = "Welsh",
    )

    private fun afrikaans(): PackEntry = PackFixtures.entry(
        locale = "af",
        file = "afrikaans.ldb",
        sha256 = Sha256.of(afrikaansBytes),
        size = afrikaansBytes.size.toLong(),
        name = "Afrikaans",
    )

    private fun packs(vararg entries: PackEntry): Packs =
        PackFixtures.packsOf(server.url("/packs/"), *entries)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        server = LocalHttpServer()
        downloader = Downloader(context, connectTimeoutMs = 4_000, readTimeoutMs = 4_000)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        posted.clear()
        File(context.noBackupFilesDir, "nuance").deleteRecursively()
        File(context.noBackupFilesDir, "lang_variants").deleteRecursively()
        PackFixtures.customRegistry(context).delete()
        PackFixtures.variantRegistry(context).delete()
        downloader.downloadDir().listFiles()?.forEach { it.delete() }
        manager = PackDownloadManager(
            context = context,
            downloader = downloader,
            installer = PackInstallService(
                context,
                PackFixtures.FakeSubtypes(builtIn = setOf("cy", "af", "de")),
            ) { action -> posted += action },
            scope = scope,
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
        downloader.downloadDir().listFiles()?.forEach { it.delete() }
    }

    /** The first state for [locale] that satisfies [predicate]. Fails the test on a timeout. */
    private suspend fun awaitState(locale: String, predicate: (PackState?) -> Boolean): PackState? =
        withTimeout(TIMEOUT_MS) {
            manager.states.map { it[locale] }.first(predicate)
        }

    // ── Success ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun downloadsVerifiesAndInstallsUnderTheManifestLocale() = runBlocking<Unit> {
        server.respondWith { it.reply(200, welshBytes) }

        manager.download(packs(welsh()), welsh())
        val state = awaitState("cy") { it is PackState.Installed }

        assertEquals(PackState.Installed(PackState.Origin.CUSTOM), state)
        val dir = PackFixtures.packDir(context, "cy")
        assertTrue("the pack is not on disk", File(dir, "cy.ldb").isFile)
        assertTrue(File(dir, "version.txt").isFile)
        // The catalogue's version, not the picker's placeholder.
        assertEquals("1902.01", File(dir, "version.txt").readText())
        // Installed under the locale, NOT under the asset file name.
        assertFalse(PackFixtures.packDir(context, "welsh").exists())
        assertTrue(
            "registry entry missing",
            PackFixtures.customRegistry(context).readText().contains("\"language\":\"cy\"")
        )
        assertEquals(listOf("language_pack_changed"), posted)

        // The download is a copy and its job is done; several megabytes per pack must not sit in
        // the cache waiting for clearStale.
        assertFalse(File(downloader.downloadDir(), "welsh.ldb").exists())
        assertFalse(File(downloader.downloadDir(), "welsh.ldb.tmp").exists())
    }

    @Test
    fun progressIsReportedAgainstTheCatalogueSize() = runBlocking<Unit> {
        server.respondWith { it.reply(200, welshBytes) }

        manager.download(packs(welsh()), welsh())
        val downloading = awaitState("cy") {
            it is PackState.Downloading && it.bytes > 0L
        } as PackState.Downloading

        assertEquals(welshBytes.size.toLong(), downloading.totalBytes)
        assertTrue(downloading.progress > 0f)
        awaitState("cy") { it is PackState.Installed }
        Unit
    }

    @Test
    fun aVariantPackInstallsIntoItsBaseLanguagesSlot() = runBlocking<Unit> {
        val swiss = PackFixtures.entry(
            locale = "de_CH",
            file = "swiss.ldb",
            sha256 = Sha256.of(welshBytes),
            size = welshBytes.size.toLong(),
            name = "German (Switzerland)",
            group = "de",
        )
        server.respondWith { it.reply(200, welshBytes) }

        manager.download(packs(swiss), swiss)
        val state = awaitState("de_CH") { it is PackState.Installed }

        assertEquals(PackState.Installed(PackState.Origin.VARIANT_ACTIVE), state)
        assertTrue(PackFixtures.parkedVariant(context, "de", "de_CH").isFile)
        assertTrue(File(PackFixtures.packDir(context, "de"), "de.ldb").isFile)
        assertFalse(PackFixtures.packDir(context, "de_CH").exists())
    }

    // ── Failure ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun aShaMismatchFailsAndInstallsNothing() = runBlocking<Unit> {
        server.respondWith { it.reply(200, welshBytes) }
        val lying = welsh(sha = "0".repeat(64))

        manager.download(packs(lying), lying)
        val state = awaitState("cy") { it is PackState.Failed } as PackState.Failed

        assertTrue("the typed error must survive: ${state.error}", state.error is IntegrityException)
        assertEquals("sha256", (state.error as IntegrityException).kind)
        assertFalse("a pack that failed verification must not be installed", PackFixtures.packDir(context, "cy").exists())
        assertFalse(PackFixtures.customRegistry(context).isFile)
        assertTrue("nothing was installed, so nothing changed", posted.isEmpty())
        assertFalse(File(downloader.downloadDir(), "welsh.ldb").exists())
    }

    @Test
    fun anHttpErrorFailsWithItsStatusAndInstallsNothing() = runBlocking<Unit> {
        server.respondWith { it.replyText(404, "no such asset") }

        manager.download(packs(welsh()), welsh())
        val state = awaitState("cy") { it is PackState.Failed } as PackState.Failed

        assertTrue(state.error.message!!.contains("404"))
        assertFalse(PackFixtures.packDir(context, "cy").exists())
    }

    @Test
    fun clearingAFailureReturnsThePackToTheCatalogueSAnswer() = runBlocking<Unit> {
        server.respondWith { it.replyText(500, "boom") }
        manager.download(packs(welsh()), welsh())
        awaitState("cy") { it is PackState.Failed }

        manager.clearFailure("cy")

        assertEquals(null, manager.states.value["cy"])
    }

    // ── Cancellation ──────────────────────────────────────────────────────────────────────────

    @Test
    fun cancellingLeavesNothingInstalledAndReportsNoFailure() = runBlocking<Unit> {
        server.respondWith { it.replySlowly(total = 400_000, chunkSize = 1_024, pauseMs = 20L) }
        val big = PackFixtures.entry(
            locale = "cy", file = "welsh.ldb", sha256 = Sha256.of(welshBytes), size = 400_000L, name = "Welsh",
        )

        manager.download(packs(big), big)
        awaitState("cy") { it is PackState.Downloading && it.bytes > 0L }
        manager.cancel("cy")

        // Cancellation is the user changing their mind, not an error: the row goes back to
        // offering a download rather than showing a failure.
        val gone = withTimeout(TIMEOUT_MS) { manager.states.first { !it.containsKey("cy") } }
        assertFalse(gone.containsKey("cy"))
        assertFalse(PackFixtures.packDir(context, "cy").exists())
        assertFalse(PackFixtures.customRegistry(context).isFile)
        assertTrue(posted.isEmpty())
        assertFalse(manager.isBusy())
    }

    // ── The queue ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun twoRequestsAreTwoDownloadsInOrderNotTwoAtOnce() = runBlocking<Unit> {
        server.respondWith { exchange ->
            when {
                exchange.path.endsWith("welsh.ldb") -> exchange.reply(200, welshBytes)
                exchange.path.endsWith("afrikaans.ldb") -> exchange.reply(200, afrikaansBytes)
                else -> exchange.replyText(404, "?")
            }
        }
        val catalogue = packs(welsh(), afrikaans())

        manager.download(catalogue, welsh())
        manager.download(catalogue, afrikaans())

        // Both are "requested" from the moment the button was pressed - a queued row that snapped
        // back to Download until the first finished would read as a button that did nothing.
        assertTrue(manager.states.value["cy"] is PackState.Downloading)
        assertTrue(manager.states.value["af"] is PackState.Downloading)

        awaitState("cy") { it is PackState.Installed }
        awaitState("af") { it is PackState.Installed }

        assertTrue(File(PackFixtures.packDir(context, "cy"), "cy.ldb").isFile)
        assertTrue(File(PackFixtures.packDir(context, "af"), "af.ldb").isFile)
        // One at a time, in the order they were asked for.
        assertEquals(listOf("/packs/welsh.ldb", "/packs/afrikaans.ldb"), server.requestedPaths())
        assertFalse(manager.isBusy())
    }

    @Test
    fun askingTwiceForTheSamePackDoesNotDownloadItTwice() = runBlocking<Unit> {
        server.respondWith { it.reply(200, welshBytes) }

        manager.download(packs(welsh()), welsh())
        manager.download(packs(welsh()), welsh())
        awaitState("cy") { it is PackState.Installed }

        assertEquals(1, server.requestCount)
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
    }
}

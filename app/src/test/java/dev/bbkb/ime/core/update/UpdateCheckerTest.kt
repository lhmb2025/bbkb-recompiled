package dev.bbkb.ime.core.update

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.distribution.DistributionConfig
import dev.bbkb.ime.core.distribution.HttpStatusException
import dev.bbkb.ime.core.distribution.LocalHttpServer
import dev.bbkb.ime.core.distribution.ManifestSource
import dev.bbkb.ime.core.distribution.OfflineException
import dev.bbkb.ime.core.settings.PrefsManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [UpdateChecker] against a real manifest served by [LocalHttpServer], with the clock, the
 * connectivity flag and the channel injected.
 *
 * The one rule worth stating up front: **there is no debug channel**. The published manifest's
 * `app` section carries `release` only, so a debug build has nothing to compare itself against
 * and must say so as a fact — [UpdateStatus.NoChannel] — without spending a network call finding
 * out. Half the cases here are about that and about the debug-only override that gets round it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class UpdateCheckerTest {

    private lateinit var context: Context
    private lateinit var server: LocalHttpServer
    private lateinit var prefs: SharedPreferences

    private var now = 1_700_000_000_000L
    private var online = true
    private var channel = "release"
    private var body = MANIFEST

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = PrefsManager.getPrefs(context)
        prefs.edit().clear().commit()
        server = LocalHttpServer()
        server.respondWith { exchange -> exchange.replyText(200, body) }
        now = 1_700_000_000_000L
        online = true
        channel = "release"
        body = MANIFEST
        clearCache()
    }

    @After
    fun tearDown() {
        server.close()
        clearCache()
        prefs.edit().clear().commit()
    }

    private fun clearCache() {
        DistributionConfig.manifestCacheFile(context).delete()
        DistributionConfig.manifestMetaFile(context).delete()
    }

    private fun checker(installed: Int = 1400): UpdateChecker = UpdateChecker(
        context = context,
        manifestSource = ManifestSource(
            context = context,
            manifestUrlProvider = { server.url("/dist/manifest.json") },
            isOnline = { online },
            clock = { now },
            connectTimeoutMs = 4_000,
            readTimeoutMs = 4_000,
        ),
        prefs = prefs,
        channelProvider = { channel },
        currentVersionCode = installed,
        currentVersionName = "5.0.0-beta.17",
        deviceSdk = 33,
        clock = { now },
    )

    // ── the channel rule ───────────────────────────────────────────────────────

    @Test
    fun aDebugBuildWithNoOverrideHasNoChannelAndMakesNoRequest() {
        channel = "debug"
        val status = runBlocking { checker().check(force = true) }
        assertEquals(UpdateStatus.NoChannel, status)
        assertEquals("a debug build must not spend a request finding this out", 0, server.requestCount)
    }

    @Test
    fun aDebugBuildWithTheChannelOverrideSeesTheReleaseEntry() {
        // pref_distribution_channel_override = "release": the preview path, and the reason a
        // release APK installs beside the debug build rather than over it.
        channel = "release"
        val status = runBlocking { checker().check(force = true) }
        assertTrue("expected Available, got $status", status is UpdateStatus.Available)
        assertEquals(1430, (status as UpdateStatus.Available).build.versionCode)
    }

    @Test
    fun aChannelTheManifestDoesNotCarryIsNoChannelToo() {
        channel = "canary"
        val status = runBlocking { checker().check(force = true) }
        assertEquals(UpdateStatus.NoChannel, status)
        assertEquals("the manifest still had to be read", 1, server.requestCount)
    }

    // ── the comparison ─────────────────────────────────────────────────────────

    @Test
    fun aStrictlyNewerVersionIsAvailable() {
        val status = runBlocking { checker(installed = 1429).check(force = true) }
        status as UpdateStatus.Available
        assertEquals(1430, status.build.versionCode)
        assertEquals("5.0.0-beta.18", status.build.versionName)
        assertEquals(now, status.checkedAt)
    }

    @Test
    fun theSameVersionIsUpToDate() {
        val status = runBlocking { checker(installed = 1430).check(force = true) }
        status as UpdateStatus.UpToDate
        assertEquals(1430, status.current)
        assertFalse("a freshly fetched manifest is not from the cache", status.fromCache)
    }

    @Test
    fun aNewerInstalledVersionIsUpToDate() {
        // A sideloaded build ahead of the channel: nothing to offer, and certainly not a
        // downgrade.
        val status = runBlocking { checker(installed = 1500).check(force = true) }
        assertTrue("expected UpToDate, got $status", status is UpdateStatus.UpToDate)
    }

    @Test
    fun aVersionThisAndroidCannotInstallIsUpToDate() {
        val strict = UpdateChecker(
            context = context,
            manifestSource = ManifestSource(
                context = context,
                manifestUrlProvider = { server.url("/dist/manifest.json") },
                isOnline = { online },
                clock = { now },
            ),
            prefs = prefs,
            channelProvider = { channel },
            currentVersionCode = 1400,
            deviceSdk = 22, // the manifest entry's minSdk is 23
            clock = { now },
        )
        val status = runBlocking { strict.check(force = true) }
        assertTrue("expected UpToDate, got $status", status is UpdateStatus.UpToDate)
        assertNull("and nothing on record to offer", strict.lastKnownAvailable())
    }

    // ── failure keeps what the last good check found ───────────────────────────

    @Test
    fun aFailedCheckKeepsTheLastKnownUpdate() {
        val checker = checker(installed = 1400)
        val first = runBlocking { checker.check(force = true) }
        assertTrue(first is UpdateStatus.Available)

        server.respondWith { exchange -> exchange.replyText(500, "nope") }
        val second = runBlocking { checker.check(force = true) }
        second as UpdateStatus.Failed
        assertTrue("expected an HTTP failure, got ${second.error}", second.error is HttpStatusException)
        assertEquals(
            "the card the user was looking at must survive a failed refresh",
            1430, second.lastKnown?.build?.versionCode
        )
    }

    @Test
    fun offlineWithNothingCachedFailsWithNoLastKnown() {
        online = false
        val status = runBlocking { checker().check(force = true) }
        status as UpdateStatus.Failed
        assertTrue("expected OfflineException, got ${status.error}", status.error is OfflineException)
        assertNull(status.lastKnown)
    }

    // ── persistence: the UI renders without a network call ────────────────────

    @Test
    fun theOutcomeIsPersistedAndRebuiltFromPreferences() {
        val checker = checker(installed = 1400)
        runBlocking { checker.check(force = true) }

        assertEquals(now, prefs.getLong(UpdateChecker.PREF_LAST_CHECK_MS, 0L))
        assertEquals(1430, prefs.getInt(UpdateChecker.PREF_AVAILABLE_VERSION_CODE, 0))
        assertEquals(
            "5.0.0-beta.18",
            prefs.getString(UpdateChecker.PREF_AVAILABLE_VERSION_NAME, null)
        )

        // A second checker, no network of any kind: same answer, off disk.
        val cold = UpdateChecker(
            context = context,
            prefs = prefs,
            channelProvider = { "release" },
            currentVersionCode = 1400,
            deviceSdk = 33,
            clock = { now },
        )
        val outcome = cold.lastOutcome()
        outcome as UpdateStatus.Available
        assertEquals(1430, outcome.build.versionCode)
        assertEquals(12_345_678L, outcome.build.size)
        assertEquals("Faster swipe decoding.", outcome.build.notes)
    }

    @Test
    fun anUpToDateCheckClearsTheUpdateOnRecord() {
        val checker = checker(installed = 1400)
        runBlocking { checker.check(force = true) }
        assertEquals(1430, prefs.getInt(UpdateChecker.PREF_AVAILABLE_VERSION_CODE, 0))

        // The user installs it: the same manifest now reads as up to date, and the banner the
        // main menu draws off these keys has to go away.
        val installed = checker(installed = 1430)
        runBlocking { installed.check(force = true) }
        assertEquals(0, prefs.getInt(UpdateChecker.PREF_AVAILABLE_VERSION_CODE, 0))
        assertNull(installed.lastKnownAvailable())
    }

    @Test
    fun lastOutcomeIsNullOnAFreshInstallAndNoChannelOnADebugBuild() {
        assertNull(checker().lastOutcome())
        channel = "debug"
        assertEquals(UpdateStatus.NoChannel, checker().lastOutcome())
    }

    // ── seen-version suppression ──────────────────────────────────────────────

    @Test
    fun aVersionAlreadySeenIsNotWorthNotifyingAgain() {
        val checker = checker(installed = 1400)
        val status = runBlocking { checker.check(force = true) }
        status as UpdateStatus.Available

        assertTrue("the first sighting is news", checker.shouldNotify(status))
        checker.markSeen(status.build.versionCode)
        assertFalse("the same version twice is not", checker.shouldNotify(status))
        assertEquals(1430, checker.seenVersionCode())

        // A newer one still is.
        val newer = UpdateStatus.Available(status.build.copy(versionCode = 1431), now)
        assertTrue(checker.shouldNotify(newer))
    }

    @Test
    fun markSeenNeverMovesBackwards() {
        val checker = checker()
        checker.markSeen(1430)
        checker.markSeen(1200)
        assertEquals(1430, checker.seenVersionCode())
    }

    @Test
    fun nothingButAnAvailableUpdateIsWorthNotifying() {
        val checker = checker()
        assertFalse(checker.shouldNotify(UpdateStatus.NoChannel))
        assertFalse(checker.shouldNotify(UpdateStatus.UpToDate(1430, now, false)))
        assertFalse(checker.shouldNotify(UpdateStatus.Failed(OfflineException(), null)))
    }

    // ── the channel predicate itself ──────────────────────────────────────────

    @Test
    fun onlyTheDebugBuildTypeIsAnUnpublishedChannel() {
        assertTrue(UpdateChecker.channelIsPublished("release"))
        assertTrue(UpdateChecker.channelIsPublished("canary"))
        assertFalse(UpdateChecker.channelIsPublished("debug"))
        assertFalse(UpdateChecker.channelIsPublished(""))
    }

    @Test
    fun aCachedManifestSaysSo() {
        val checker = checker(installed = 1430)
        runBlocking { checker.check(force = true) }
        server.resetCounters()

        // Within the cache window and not forced: answered off disk, and it admits it.
        val second = runBlocking { checker.check(force = false) }
        second as UpdateStatus.UpToDate
        assertTrue("a cached answer must not pass itself off as fresh", second.fromCache)
        assertEquals(0, server.requestCount)
    }

    private companion object {
        /** Schema 1, `app` with a `release` entry only — the shape that is actually published. */
        val MANIFEST = """
            {
              "schema": 1,
              "generated": "2026-09-22T00:00:00Z",
              "app": {
                "release": {
                  "versionCode": 1430,
                  "versionName": "5.0.0-beta.18",
                  "url": "https://example.invalid/bbkb-5.0.0-beta.18.apk",
                  "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "size": 12345678,
                  "minSdk": 23,
                  "notes": "Faster swipe decoding."
                }
              }
            }
        """.trimIndent()
    }
}

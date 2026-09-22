package dev.bbkb.ime.core.distribution

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.BuildConfig
import dev.bbkb.ime.core.settings.PrefsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The two debug-only preferences, and the paths everything else in the package depends on.
 *
 * These tests run on the **debug** variant, so `BuildConfig.DEBUG` is true and the override
 * branches are the live ones. The release behaviour — both keys inert, always the real URL and
 * the real build type — is a `BuildConfig.DEBUG` check placed first in each method so R8 can
 * delete the lookup outright; it cannot be exercised from a debug unit test, which is why the
 * check is a single `if` at the top of each function rather than anything conditional deeper in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class DistributionConfigTest {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = PrefsManager.getPrefs(context)
        prefs.edit().clear().commit()
    }

    private fun put(key: String, value: String?) {
        prefs.edit().putString(key, value).commit()
    }

    // ── The manifest URL ──────────────────────────────────────────────────────────────────────

    @Test
    fun theDefaultUrlIsThePublishedManifest() {
        assertEquals(
            "https://raw.githubusercontent.com/lhmb2025/bbkb-recompiled/main/dist/manifest.json",
            DistributionConfig.DEFAULT_MANIFEST_URL
        )
        assertEquals(DistributionConfig.DEFAULT_MANIFEST_URL, DistributionConfig.manifestUrl(context))
    }

    @Test
    fun thePreferenceKeysAreTheAgreedNewOnes() {
        // Named here so a rename cannot happen quietly: the settings UI the update agent builds
        // writes these exact strings.
        assertEquals("pref_distribution_manifest_url", DistributionConfig.PREF_MANIFEST_URL)
        assertEquals("pref_distribution_channel_override", DistributionConfig.PREF_CHANNEL_OVERRIDE)
    }

    @Test
    fun aDebugOverrideRedirectsTheFetchAtALocalServer() {
        put(DistributionConfig.PREF_MANIFEST_URL, "http://10.0.2.2:8000/manifest.json")

        assertEquals("http://10.0.2.2:8000/manifest.json", DistributionConfig.manifestUrl(context))
    }

    @Test
    fun anHttpsOverrideIsHonouredToo() {
        put(DistributionConfig.PREF_MANIFEST_URL, "https://staging.test/dist/manifest.json")

        assertEquals("https://staging.test/dist/manifest.json", DistributionConfig.manifestUrl(context))
    }

    @Test
    fun surroundingWhitespaceIsTrimmed() {
        put(DistributionConfig.PREF_MANIFEST_URL, "  http://10.0.2.2:8000/manifest.json\n")

        assertEquals("http://10.0.2.2:8000/manifest.json", DistributionConfig.manifestUrl(context))
    }

    @Test
    fun anUnusableOverrideDegradesToTheDefaultRatherThanFailingEveryFetch() {
        for (bad in listOf("", "   ", "10.0.2.2:8000", "file:///tmp/manifest.json", "not a url")) {
            put(DistributionConfig.PREF_MANIFEST_URL, bad)
            assertEquals(
                "override <$bad> should have been ignored",
                DistributionConfig.DEFAULT_MANIFEST_URL,
                DistributionConfig.manifestUrl(context)
            )
        }
    }

    // ── The channel ───────────────────────────────────────────────────────────────────────────

    @Test
    fun theChannelIsTheBuildTypeByDefault() {
        assertEquals(BuildConfig.BUILD_TYPE, DistributionConfig.channel(context))
        assertEquals("these tests run on the debug variant", "debug", DistributionConfig.channel(context))
    }

    @Test
    fun theDebugOverrideSelectsAnotherChannel() {
        // Why this exists: the published manifest has a `release` channel only, so a debug daily
        // driver would otherwise never have an update flow to exercise.
        put(DistributionConfig.PREF_CHANNEL_OVERRIDE, "release")

        assertEquals("release", DistributionConfig.channel(context))
    }

    @Test
    fun anEmptyChannelOverrideMeansNoOverride() {
        for (blank in listOf("", "   ")) {
            put(DistributionConfig.PREF_CHANNEL_OVERRIDE, blank)
            assertEquals(BuildConfig.BUILD_TYPE, DistributionConfig.channel(context))
        }
    }

    @Test
    fun anOverriddenChannelResolvesAgainstTheManifestLikeAnyOther() {
        // The whole point: with the override set, a release-only manifest yields an update on a
        // debug build; without it, it yields nothing at all.
        val releaseOnly = DistributionManifestParser.parse(
            """
            {"schema": 1, "app": {"release": {"versionCode": 99999, "versionName": "n",
             "url": "https://e.test/a.apk", "sha256": "ab", "size": 1, "minSdk": 23}}}
            """.trimIndent()
        )

        assertEquals(null, releaseOnly.appFor(DistributionConfig.channel(context)))

        put(DistributionConfig.PREF_CHANNEL_OVERRIDE, "release")
        val build = releaseOnly.appFor(DistributionConfig.channel(context))
        assertEquals(99999, build!!.versionCode)
        assertTrue(build.isNewerThan(BuildConfig.VERSION_CODE))
    }

    // ── Everything else ───────────────────────────────────────────────────────────────────────

    @Test
    fun theUserAgentNamesTheAppAndItsPackage() {
        assertEquals(
            "BBKB/${BuildConfig.VERSION_NAME} (${context.packageName})",
            DistributionConfig.userAgent(context)
        )
    }

    @Test
    fun cachedManifestStateLivesInDurableStorageAndDownloadsInTheCache() {
        // The split matters: a cache wipe may cost a half-finished download, but it must not
        // cost the catalogue, which is the only offline answer to "what packs exist".
        assertEquals(File(context.filesDir, "distribution"), DistributionConfig.stateDir(context))
        assertEquals(
            File(context.filesDir, "distribution/manifest.json"),
            DistributionConfig.manifestCacheFile(context)
        )
        assertEquals(
            File(context.filesDir, "distribution/manifest.meta.json"),
            DistributionConfig.manifestMetaFile(context)
        )
        assertEquals(File(context.cacheDir, "downloads"), DistributionConfig.downloadDir(context))
        assertTrue(DistributionConfig.stateDir(context).isDirectory)
        assertTrue(DistributionConfig.downloadDir(context).isDirectory)
    }

    @Test
    fun theCacheWindowIsSixHoursAndTheTimeoutTenSeconds() {
        assertEquals(6L * 60 * 60 * 1000, DistributionConfig.CACHE_MAX_AGE_MS)
        assertEquals(10_000, DistributionConfig.TIMEOUT_MS)
    }
}

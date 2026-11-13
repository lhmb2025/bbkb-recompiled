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
 * Pins the two debug-only override preferences and the package's file paths. Runs on the debug
 * variant; the release behaviour (overrides inert) is a leading `BuildConfig.DEBUG` check that R8
 * strips and that a debug unit test cannot exercise.
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
        // The settings UI writes these exact strings, so a rename must fail here.
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

    @Test
    fun theChannelIsTheBuildTypeByDefault() {
        assertEquals(BuildConfig.BUILD_TYPE, DistributionConfig.channel(context))
        assertEquals("these tests run on the debug variant", "debug", DistributionConfig.channel(context))
    }

    @Test
    fun theDebugOverrideSelectsAnotherChannel() {
        // The published manifest has a `release` channel only, so a debug build needs this
        // override to exercise the update flow.
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
        // With the override a release-only manifest yields an update on debug; without it, nothing.
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

    @Test
    fun theUserAgentNamesTheAppAndItsPackage() {
        assertEquals(
            "BBKB/${BuildConfig.VERSION_NAME} (${context.packageName})",
            DistributionConfig.userAgent(context)
        )
    }

    @Test
    fun cachedManifestStateLivesInDurableStorageAndDownloadsInTheCache() {
        // A cache wipe may cost a half-finished download but not the catalogue, the only offline
        // answer to "what packs exist".
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

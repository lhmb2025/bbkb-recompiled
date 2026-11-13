package dev.bbkb.ime.core.update

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import dev.bbkb.ime.core.distribution.AppBuild
import dev.bbkb.ime.core.distribution.DistributionConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File

/**
 * [ApkInstaller]'s intent and housekeeping. A `file://` URI, a missing read grant, a wrong
 * authority or a `paths` xml not covering `cacheDir/downloads/` all fail silently on a phone; the
 * real `FileProvider` resolves against the real manifest here, so they fail this test instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApkInstallerTest {

    private lateinit var context: Context
    private lateinit var installer: ApkInstaller

    private val build = AppBuild(
        versionCode = 1430,
        versionName = "5.0.0-beta.18",
        url = "https://example.invalid/bbkb.apk",
        sha256 = "aa".repeat(32),
        size = 1234L,
        minSdk = 23,
        notes = null,
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        installer = ApkInstaller(context)
        downloadDir().listFiles()?.forEach { it.delete() }
        forgetCachedFileProviderRoots()
    }

    /**
     * Test-only: `FileProvider` caches its parsed roots statically, but Robolectric gives each test
     * a new data directory, so a stale cache fails with "Failed to find configured root".
     */
    private fun forgetCachedFileProviderRoots() {
        for (field in FileProvider::class.java.declaredFields) {
            if (!Map::class.java.isAssignableFrom(field.type)) continue
            try {
                field.isAccessible = true
                (field.get(null) as? MutableMap<*, *>)?.clear()
            } catch (ignored: ReflectiveOperationException) {
                // An androidx that caches differently: the tests still pass or fail loudly.
            }
        }
    }

    @After
    fun tearDown() {
        downloadDir().listFiles()?.forEach { it.delete() }
    }

    private fun downloadDir(): File = DistributionConfig.downloadDir(context)

    private fun writeApk(name: String): File =
        File(downloadDir(), name).apply { writeText("not really an apk") }

    @Test
    fun theFileNameIsVersionStampedAndPathSafe() {
        assertEquals("bbkb-5.0.0-beta.18-1430.apk", ApkInstaller.fileNameFor(build))
        assertEquals(
            "a version name with a slash in it must not become a path",
            "bbkb-5.0_0-1430.apk",
            ApkInstaller.fileNameFor(build.copy(versionName = "5.0/0"))
        )
    }

    @Test
    fun theDestinationIsInTheDownloadsCacheDirectory() {
        val destination = installer.destinationFor(build)
        assertEquals(downloadDir().absolutePath, destination.parentFile?.absolutePath)
        assertEquals("bbkb-5.0.0-beta.18-1430.apk", destination.name)
    }

    @Test
    fun anApkThatIsNotThereIsNotOffered() {
        assertNull(installer.downloadedApk(build))
        writeApk(ApkInstaller.fileNameFor(build))
        assertTrue(installer.downloadedApk(build) != null)
    }

    @Test
    fun theInstallIntentIsAContentUriWithTheRightMimeAndFlags() {
        val apk = writeApk(ApkInstaller.fileNameFor(build))
        val intent = installer.installIntentFor(apk)

        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(ApkInstaller.MIME_APK, intent.type)
        assertEquals("application/vnd.android.package-archive", intent.type)

        val uri = intent.data
        assertTrue("expected a content:// URI, got $uri", uri != null)
        assertEquals("content", uri!!.scheme)
        assertEquals(
            "the authority must be the FileProvider the manifest already declares",
            context.packageName + ".fileprovider",
            uri.authority
        )
        assertEquals(installer.authority(), uri.authority)
        // cacheDir/downloads/ is served by the <cache-path name="downloads"> entry in file_paths.
        assertTrue(
            "the URI path must come out of the downloads cache path: ${uri.path}",
            uri.path!!.contains("downloads")
        )

        assertTrue(
            "without GRANT_READ_URI_PERMISSION the installer cannot read the file",
            intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
        )
        assertTrue(
            "without NEW_TASK it cannot be started from a service or a receiver",
            intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0
        )
    }

    @Test
    fun theUnknownSourcesIntentNamesThisPackage() {
        val intent = installer.unknownSourcesIntent()
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, intent.action)
        assertEquals("package:${context.packageName}", intent.data.toString())
    }

    @Test
    fun startingTheInstallerWithNoFileFails() {
        assertFalse(installer.startInstall(File(downloadDir(), "gone.apk")))
    }

    @Test
    fun startingTheInstallerLaunchesTheViewIntent() {
        val apk = writeApk(ApkInstaller.fileNameFor(build))
        // A platform installer that answers the intent, registered with the shadow package
        // manager. Without one the start throws ActivityNotFoundException, which is the case
        // below - and the reason startInstall returns a Boolean rather than assuming.
        val installerApp = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "com.android.packageinstaller"
                name = "com.android.packageinstaller.InstallStart"
            }
        }
        Shadows.shadowOf(context.packageManager)
            .addResolveInfoForIntent(installer.installIntentFor(apk), installerApp)

        assertTrue(installer.startInstall(apk))
        val started = Shadows.shadowOf(
            ApplicationProvider.getApplicationContext<Application>()
        ).nextStartedActivity
        assertTrue("no activity was started", started != null)
        assertEquals(Intent.ACTION_VIEW, started!!.action)
        assertEquals(ApkInstaller.MIME_APK, started.type)
    }

    @Test
    fun pruningDropsOlderApksAndLeavesTheOneToKeep() {
        val keep = writeApk("bbkb-5.0.0-beta.18-1430.apk")
        val older = writeApk("bbkb-5.0.0-beta.17-1429.apk")
        val partial = writeApk("bbkb-5.0.0-beta.17-1429.apk.tmp")
        // A language pack sharing the same cache directory: not ours, not touched.
        val pack = writeApk("Blackberry_1305_r1-2_AFlsUN_xt9_ALM3.ldb")

        val deleted = installer.pruneSupersededApks(keep = keep)

        assertTrue(keep.isFile)
        assertFalse(older.isFile)
        assertFalse(partial.isFile)
        assertTrue("a language-pack download must never be collateral", pack.isFile)
        assertEquals(2, deleted)
    }

    @Test
    fun downloadingRefusesAnApkWithNoExpectedHash() {
        // The manifest is unsigned: the SHA-256 is one of only two integrity legs there are, so
        // an entry without one is not installable at any price.
        val result = kotlinx.coroutines.runBlocking {
            installer.download(build.copy(sha256 = "  "))
        }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun canRequestInstallsIsWhatThePackageManagerSays() {
        // Robolectric's default is false; the screen turns that into "explain, then send them to
        // Settings" rather than launching an installer that would be refused.
        assertEquals(
            context.packageManager.canRequestPackageInstalls(),
            installer.canRequestInstalls()
        )
    }
}

package dev.bbkb.ime.core.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import dev.bbkb.ime.core.distribution.AppBuild
import dev.bbkb.ime.core.distribution.Downloader
import dev.bbkb.ime.core.shared.Logger
import java.io.File

/**
 * Downloads a published APK and hands it to the system installer; only ever from an Updates-screen
 * button, and the platform dialog has the last word. [download] verifies size and SHA-256 before the
 * file appears; the install intent carries a [FileProvider] URI (`cache-path` "downloads"); on API 26+
 * the user must also allow this app as an install source ([canRequestInstalls]). A release APK
 * installs beside a debug build (different id and key), never over it.
 */
class ApkInstaller @JvmOverloads constructor(
    private val context: Context,
    private val downloader: Downloader = Downloader(context),
) {
    /** Version-stamped, so a newer download never collides with an uninstalled one and pruning can recognise it. */
    fun destinationFor(build: AppBuild): File = downloader.destinationFor(fileNameFor(build))

    /** The already-downloaded, already-verified APK for [build], or `null` if it is not there. */
    fun downloadedApk(build: AppBuild): File? = destinationFor(build).takeIf { it.isFile }

    /**
     * @param onProgress bytes and total, called on the IO dispatcher
     * @return the verified file, or a `DistributionException` (`IntegrityException`: hash mismatch, partial file deleted)
     */
    suspend fun download(
        build: AppBuild,
        onProgress: (bytes: Long, total: Long) -> Unit = { _, _ -> },
    ): Result<File> {
        if (build.sha256.isBlank()) {
            // The manifest is unsigned, so the hash is one of only two integrity legs.
            return Result.failure(
                IllegalArgumentException("refusing to download an APK with no expected SHA-256")
            )
        }
        val destination = destinationFor(build)
        val result = downloader.download(
            url = build.url,
            expectedSha256 = build.sha256,
            expectedSize = build.size,
            destination = destination,
            onProgress = onProgress,
        )
        if (result.isSuccess) pruneSupersededApks(keep = destination)
        return result
    }

    /**
     * Deletes earlier APKs of ours (matching [fileNameFor]) except [keep], never the language packs
     * sharing the directory; also clears stale `.tmp` leftovers via [Downloader.clearStale].
     * Blocking file I/O. Returns how many files were deleted.
     */
    fun pruneSupersededApks(keep: File?): Int {
        var deleted = downloader.clearStale(STALE_AGE_MS)
        val files = downloader.downloadDir().listFiles() ?: return deleted
        for (file in files) {
            if (!file.isFile) continue
            if (keep != null && file.absolutePath == keep.absolutePath) continue
            val name = file.name
            val ours = name.startsWith(NAME_PREFIX) &&
                (name.endsWith(".apk") || name.endsWith(".apk.tmp"))
            if (ours && file.delete()) deleted++
        }
        return deleted
    }

    fun canRequestInstalls(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** The install-sources page, opened on this app's own entry. */
    fun unknownSourcesIntent(): Intent {
        return Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** `ACTION_VIEW` on a [FileProvider] URI with the read grant and NEW_TASK (both required). */
    fun installIntentFor(apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, authority(), apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, MIME_APK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** False when the file is gone or no installer can start; the caller then offers the download again. */
    fun startInstall(apk: File): Boolean {
        if (!apk.isFile) return false
        return try {
            context.startActivity(installIntentFor(apk))
            true
        } catch (failed: RuntimeException) {
            // ActivityNotFoundException, or a FileProvider authority/paths mismatch.
            Logger.info(TAG, "Could not start the installer: ${failed.message}")
            false
        }
    }

    /** `${applicationId}.fileprovider`, the provider already declared in the manifest. */
    fun authority(): String = context.packageName + AUTHORITY_SUFFIX

    companion object {

        private const val TAG = "ApkInstaller"

        const val AUTHORITY_SUFFIX: String = ".fileprovider"

        const val MIME_APK: String = "application/vnd.android.package-archive"

        /** Prefix of every file this class downloads, so it can recognise its own leftovers. */
        const val NAME_PREFIX: String = "bbkb-"

        /** Grace period before an abandoned download in `cacheDir/downloads/` is pruned. */
        const val STALE_AGE_MS: Long = 14L * 24L * 60L * 60L * 1000L

        /** `bbkb-<versionName>-<versionCode>.apk`, with anything path-unsafe flattened. */
        @JvmStatic
        fun fileNameFor(build: AppBuild): String {
            val name = build.versionName.map { c ->
                if (c.isLetterOrDigit() || c == '.' || c == '-' || c == '_') c else '_'
            }.joinToString("")
            return "$NAME_PREFIX$name-${build.versionCode}.apk"
        }
    }
}

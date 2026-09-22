package dev.bbkb.ime.core.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import dev.bbkb.ime.core.distribution.AppBuild
import dev.bbkb.ime.core.distribution.Downloader
import dev.bbkb.ime.core.shared.Logger
import java.io.File

/**
 * Downloads a published APK and hands it to the system installer.
 *
 * Both halves are user-initiated, always: this class is only ever called from a button on the
 * Updates screen. It does not check for updates, does not decide when to download, and cannot
 * install anything by itself — the last step is the platform's own installer dialog, which the
 * user confirms.
 *
 * ## The three things that make an APK install work
 *
 *  1. **A verified file.** [download] goes through
 *     [dev.bbkb.ime.core.distribution.Downloader], which checks the size and the manifest's
 *     SHA-256 *before* the file appears at its destination. A file that exists here is a file
 *     that matched. Nothing in this class will install an APK with no expected hash.
 *  2. **A content URI.** `file://` URIs have been refused since API 24, so the intent carries a
 *     [FileProvider] URI from the provider the app already declares
 *     (`${applicationId}.fileprovider`), with `FLAG_GRANT_READ_URI_PERMISSION`. That provider's
 *     `paths` xml gained a `<cache-path name="downloads" path="downloads/">` entry for exactly
 *     this — the download directory is `cacheDir/downloads/`.
 *  3. **Permission to ask.** `REQUEST_INSTALL_PACKAGES` in the manifest is what lets the app
 *     *offer* an install; on API 26+ the user must additionally have allowed this app as an
 *     install source. [canRequestInstalls] reports that, and [unknownSourcesIntent] is where to
 *     send them — explain first, then launch it, then let them press Install again.
 *
 * ## A release APK never replaces a debug build
 *
 * The debug build's `applicationId` is `dev.bbkb.ime.debug` and it is signed with the debug key,
 * so a release APK installs *beside* it as a second app. That is the only sane outcome (Android
 * would refuse the update anyway, on both the id and the signature) and it is why the debug
 * channel override is a testing aid rather than an update path.
 */
class ApkInstaller @JvmOverloads constructor(
    private val context: Context,
    private val downloader: Downloader = Downloader(context),
) {

    /**
     * Where [build] lands. Version-stamped, so a newer download never collides with the file the
     * user has not installed yet, and so [pruneSupersededApks] can recognise its own files.
     */
    fun destinationFor(build: AppBuild): File = downloader.destinationFor(fileNameFor(build))

    /** The already-downloaded, already-verified APK for [build], or `null` if it is not there. */
    fun downloadedApk(build: AppBuild): File? = destinationFor(build).takeIf { it.isFile }

    /**
     * Download [build] to [destinationFor] and verify it.
     *
     * @param onProgress bytes-so-far and total, called **on the IO dispatcher** — marshal to the
     *   main thread yourself.
     * @return the verified file, or a `DistributionException` failure. `IntegrityException` means
     *   the bytes did not match the manifest and the partial file has already been deleted.
     */
    suspend fun download(
        build: AppBuild,
        onProgress: (bytes: Long, total: Long) -> Unit = { _, _ -> },
    ): Result<File> {
        if (build.sha256.isBlank()) {
            // Refusing rather than installing an unverified APK: the manifest is unsigned, so the
            // hash is one of only two integrity legs there are.
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
     * Delete APKs this class downloaded earlier, keeping [keep] — "keep the downloaded APK until
     * a newer one replaces it". Only files matching [fileNameFor]'s shape are touched, so the
     * language-pack downloads that share `cacheDir/downloads/` are never collateral; genuinely
     * abandoned files in that directory are [Downloader.clearStale]'s business, and this calls it
     * with a fortnight's grace for the orphaned `.tmp` of a download that died with the process.
     *
     * Blocking file I/O — off the main thread.
     *
     * @return how many files were deleted.
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

    /**
     * Whether the user has allowed this app to offer app installs. Always true below API 26,
     * where `REQUEST_INSTALL_PACKAGES` in the manifest was the whole of it.
     */
    fun canRequestInstalls(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return context.packageManager.canRequestPackageInstalls()
    }

    /**
     * The settings page where the user allows this app to install apps. Carries this package, so
     * it opens on our own entry rather than the whole list.
     */
    fun unknownSourcesIntent(): Intent {
        val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES
        } else {
            Settings.ACTION_SECURITY_SETTINGS
        }
        return Intent(action)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * The install intent for an already-verified [apk]: `ACTION_VIEW` on a [FileProvider] URI,
     * the package-archive mime type, and the two flags without which the installer either cannot
     * read the file or cannot be started from a non-activity context.
     */
    fun installIntentFor(apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, authority(), apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, MIME_APK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Launch the system installer for [apk].
     *
     * @return false when the file is gone or nothing can handle the intent; the caller then
     *   offers the download again rather than reporting an install failure.
     */
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

    /** `${applicationId}.fileprovider` — the provider already declared in the manifest. */
    fun authority(): String = context.packageName + AUTHORITY_SUFFIX

    companion object {

        private const val TAG = "ApkInstaller"

        /** Suffix of the manifest's existing `FileProvider` authority. */
        const val AUTHORITY_SUFFIX: String = ".fileprovider"

        /** What the platform installer answers to. */
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

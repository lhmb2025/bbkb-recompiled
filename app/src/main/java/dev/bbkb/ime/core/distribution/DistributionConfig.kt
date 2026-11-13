package dev.bbkb.ime.core.distribution

import android.content.Context
import dev.bbkb.ime.BuildConfig
import dev.bbkb.ime.core.settings.PrefsManager
import java.io.File

/**
 * Manifest URL, channel and download locations. Both preference overrides are debug-only and
 * [BuildConfig.DEBUG] is checked first, so R8 removes them from release builds: the manifest is
 * unsigned, so a release build must never be pointable at another host.
 */
object DistributionConfig {
    /** HTTPS, no auth, no signature. */
    const val DEFAULT_MANIFEST_URL: String =
        "https://raw.githubusercontent.com/lhmb2025/bbkb-recompiled/main/dist/manifest.json"

    /**
     * Debug-only manifest URL override (full URL, e.g. `http://10.0.2.2:8000/manifest.json` for a
     * host server seen from the emulator). Cleartext is blocked at targetSdk 36, so plain HTTP needs
     * `android:usesCleartextTraffic="true"` in the debug manifest; otherwise use an HTTPS tunnel.
     */
    const val PREF_MANIFEST_URL: String = "pref_distribution_manifest_url"

    /**
     * Debug-only `app` channel override. Set to `"release"` to exercise the update flow on a debug
     * build; the result installs side by side (different applicationId and signer), not as an update.
     */
    const val PREF_CHANNEL_OVERRIDE: String = "pref_distribution_channel_override"

    const val CACHE_MAX_AGE_MS: Long = 6L * 60L * 60L * 1000L

    const val TIMEOUT_MS: Int = 10_000

    /** The debug override if it is an `http(s)` URL (a typo falls back), else [DEFAULT_MANIFEST_URL]. */
    fun manifestUrl(context: Context): String {
        if (!BuildConfig.DEBUG) return DEFAULT_MANIFEST_URL
        val override = try {
            PrefsManager.getPrefs(context).getString(PREF_MANIFEST_URL, null)
        } catch (unavailable: RuntimeException) {
            // Unreadable in direct-boot / early startup; the default is always correct.
            null
        }
        val trimmed = override?.trim().orEmpty()
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            DEFAULT_MANIFEST_URL
        }
    }

    /**
     * The `app` channel for [DistributionManifest.appFor]: the build type. The published manifest
     * has `release` only, so a debug build finds nothing, which means "no update channel", not an error.
     */
    fun channel(context: Context): String {
        if (!BuildConfig.DEBUG) return BuildConfig.BUILD_TYPE
        val override = try {
            PrefsManager.getPrefs(context).getString(PREF_CHANNEL_OVERRIDE, null)
        } catch (unavailable: RuntimeException) {
            null
        }
        return override?.trim().orEmpty().ifEmpty { BuildConfig.BUILD_TYPE }
    }

    fun userAgent(context: Context): String =
        "BBKB/${BuildConfig.VERSION_NAME} (${context.packageName})"

    /** Durable storage for the cached manifest; survives a cache wipe as the offline pack list. */
    fun stateDir(context: Context): File =
        File(context.filesDir, "distribution").apply { mkdirs() }

    fun manifestCacheFile(context: Context): File = File(stateDir(context), "manifest.json")

    /** ETag and fetch time for [manifestCacheFile]. */
    fun manifestMetaFile(context: Context): File = File(stateDir(context), "manifest.meta.json")

    /** Under cacheDir on purpose: downloads are reclaimable and `Downloader.clearStale` prunes them. */
    fun downloadDir(context: Context): File =
        File(context.cacheDir, "downloads").apply { mkdirs() }
}

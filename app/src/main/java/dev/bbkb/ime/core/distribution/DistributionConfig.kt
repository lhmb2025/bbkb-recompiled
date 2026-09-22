package dev.bbkb.ime.core.distribution

import android.content.Context
import dev.bbkb.ime.BuildConfig
import dev.bbkb.ime.core.settings.PrefsManager
import java.io.File

/**
 * Where the app looks for its update manifest, which channel it reads out of it, and where the
 * files it downloads land.
 *
 * Both preference keys below are **debug-build-only**. On a release build every read here is a
 * constant: [BuildConfig.DEBUG] is checked *first*, so R8 can delete the preference lookups
 * entirely and no release build can be pointed at a manifest or a channel other than the real
 * one. That is a deliberate security property, not a convenience — the manifest is unsigned, so
 * "which host am I trusting" is one of the only two integrity legs there are.
 */
object DistributionConfig {

    /** The published manifest. HTTPS, no auth, no signature — see the package documentation. */
    const val DEFAULT_MANIFEST_URL: String =
        "https://raw.githubusercontent.com/lhmb2025/bbkb-recompiled/main/dist/manifest.json"

    /**
     * Debug-only override for [DEFAULT_MANIFEST_URL]. A string preference; empty or unset means
     * "use the default". Ignored entirely on release builds.
     *
     * ### Pointing the emulator at a local server
     *
     * ```
     * # 1. serve a manifest from the host
     * cd /tmp/dist && python3 -m http.server 8000     # serves /tmp/dist/manifest.json
     *
     * # 2. point the debug build at it (10.0.2.2 is the emulator's route to the host loopback)
     * adb -s emulator-5554 shell run-as dev.bbkb.ime.debug \
     *   sh -c 'am broadcast ...'   # or set it from the Debug settings screen
     * ```
     *
     * The value to store is the full URL of the manifest file, e.g.
     * `http://10.0.2.2:8000/manifest.json`. Seeding it by hand follows the same
     * `run-as`-plus-preferences-XML recipe the emulator VKB notes use for
     * `debug_force_vkb_mode`; the settings UI for it belongs to the update-UI agent.
     *
     * **Cleartext caveat.** The app targets API 36, where cleartext HTTP is blocked by default,
     * so a plain `http://` local server will fail with a `NetworkSecurityPolicy` error until the
     * debug variant opts in. This layer deliberately does not change the manifest (its only
     * manifest additions are the two permissions), so whoever wants a plain-HTTP local server
     * adds one attribute to `app/src/debug/AndroidManifest.xml`:
     *
     * ```xml
     * <application android:usesCleartextTraffic="true" tools:replace="android:usesCleartextTraffic"/>
     * ```
     *
     * Until then, point the override at an HTTPS URL (an `ngrok`/`caddy` tunnel in front of the
     * local server works, and so does any other HTTPS host serving the same JSON).
     */
    const val PREF_MANIFEST_URL: String = "pref_distribution_manifest_url"

    /**
     * Debug-only override for which `app` channel is read out of the manifest. A string
     * preference; empty or unset means "use [BuildConfig.BUILD_TYPE]". Ignored on release
     * builds.
     *
     * It exists because the published manifest carries a `release` channel only (see
     * [channel]), so on a debug daily driver `appFor("debug")` is always `null` and the update
     * UI has nothing to show. Setting this key to `"release"` lets the whole flow — prompt,
     * download, hash check, installer hand-off — be exercised against the real manifest.
     *
     * **What the resulting install does.** The fetched APK is the release build: applicationId
     * `dev.bbkb.ime`, signed with the release key. A debug build is `dev.bbkb.ime.debug`, signed
     * with the throwaway debug key. Android keys installs by applicationId *and* refuses a
     * signer change, so installing it does **not** update or replace the debug build — the two
     * sit side by side, each with its own IME entry, preferences and language packs. That is
     * the expected outcome when exercising the flow this way; it is not an update.
     */
    const val PREF_CHANNEL_OVERRIDE: String = "pref_distribution_channel_override"

    /** How long a cached manifest is served without going to the network. */
    const val CACHE_MAX_AGE_MS: Long = 6L * 60L * 60L * 1000L

    /** Connect and read timeout for every HTTP call in this package. */
    const val TIMEOUT_MS: Int = 10_000

    /**
     * The manifest URL to fetch: the debug override when this is a debug build and the
     * preference holds a usable `http(s)` URL, otherwise [DEFAULT_MANIFEST_URL].
     *
     * A stored value that is not an `http://` or `https://` URL is ignored rather than
     * honoured, so a typo degrades to the default instead of failing every fetch.
     */
    fun manifestUrl(context: Context): String {
        if (!BuildConfig.DEBUG) return DEFAULT_MANIFEST_URL
        val override = try {
            PrefsManager.getPrefs(context).getString(PREF_MANIFEST_URL, null)
        } catch (unavailable: RuntimeException) {
            // Preferences can be unreadable in direct-boot / early-startup states. The default
            // URL is always a correct answer, so never let this throw into a fetch.
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
     * The `app` channel to read out of the manifest — pair it with
     * [DistributionManifest.appFor]:
     *
     * ```kotlin
     * val update = manifest.appFor(DistributionConfig.channel(context))
     *     ?.takeIf { it.isNewerThan(BuildConfig.VERSION_CODE) }
     * ```
     *
     * **The channel rule.** The manifest keys `app` by Gradle build type and the published
     * manifest carries `release` only; there is no debug channel. So on a release build this is
     * `"release"` and the lookup finds it, and on a debug build this is `"debug"` and the lookup
     * finds nothing — which means exactly "no update channel for this build" and must be shown
     * as such, not as an error. [PREF_CHANNEL_OVERRIDE] is the debug-only way out of that for
     * testing; see its documentation for why the resulting install sits side by side rather
     * than updating anything.
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

    /** `User-Agent` sent by every request in this package, e.g. `BBKB/5.0.0-beta.18 (dev.bbkb.ime.debug)`. */
    fun userAgent(context: Context): String =
        "BBKB/${BuildConfig.VERSION_NAME} (${context.packageName})"

    /**
     * `files/distribution/` — durable app storage. Holds the cached manifest and its metadata
     * and survives a cache wipe, because a cached manifest is the app's only offline answer to
     * "what packs exist".
     */
    fun stateDir(context: Context): File =
        File(context.filesDir, "distribution").apply { mkdirs() }

    /** `files/distribution/manifest.json` — the last manifest that parsed successfully. */
    fun manifestCacheFile(context: Context): File = File(stateDir(context), "manifest.json")

    /** `files/distribution/manifest.meta.json` — the ETag and fetch time for the above. */
    fun manifestMetaFile(context: Context): File = File(stateDir(context), "manifest.meta.json")

    /**
     * `cacheDir/downloads/` — where [Downloader] puts APKs and `.ldb` files. Deliberately under
     * the cache directory: a half-finished or already-installed download is reclaimable, and
     * `Downloader.clearStale` prunes it.
     */
    fun downloadDir(context: Context): File =
        File(context.cacheDir, "downloads").apply { mkdirs() }
}

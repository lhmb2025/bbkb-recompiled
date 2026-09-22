package dev.bbkb.ime.core.update

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import dev.bbkb.ime.BuildConfig
import dev.bbkb.ime.core.distribution.AppBuild
import dev.bbkb.ime.core.distribution.DistributionConfig
import dev.bbkb.ime.core.distribution.ManifestSource
import dev.bbkb.ime.core.settings.PrefsManager

/**
 * The answer to "is there a newer BBKB than this one?".
 *
 * Four outcomes, and [NoChannel] is as ordinary as the others: the published manifest carries a
 * `release` entry only, so a debug build has nothing to compare itself against. That is a state,
 * not an error, and the UI says so rather than showing a failure.
 */
sealed class UpdateStatus {

    /**
     * This build has no channel in the manifest — a debug build without
     * `pref_distribution_channel_override`. Nothing was fetched: the answer is known before any
     * network call, which is why a debug build never burns a connection on this.
     */
    object NoChannel : UpdateStatus()

    /**
     * The channel exists and names a build that is not newer than [current] (or is newer but
     * needs a newer Android than this device runs).
     *
     * @param fromCache the manifest came off disk rather than the network, so [checkedAt] is
     *   when it was *fetched*, not when it was read.
     */
    data class UpToDate(val current: Int, val checkedAt: Long, val fromCache: Boolean) :
        UpdateStatus()

    /** A strictly newer build this device can install. */
    data class Available(val build: AppBuild, val checkedAt: Long) : UpdateStatus()

    /**
     * The check itself failed.
     *
     * @param error always a `DistributionException` when it came out of the fetch.
     * @param lastKnown the update the last successful check found, if one is still on record, so
     *   a failed refresh does not wipe the card the user was looking at.
     */
    data class Failed(val error: Throwable, val lastKnown: Available?) : UpdateStatus()
}

/**
 * Runs the update check and remembers its outcome.
 *
 * ## What is persisted, and why
 *
 * Everything the Updates screen and the main-menu banner need in order to render is written to
 * preferences, so opening settings shows the last answer without a network call and without a
 * spinner. [lastOutcome] rebuilds an [UpdateStatus] out of those keys; [lastKnownAvailable]
 * rebuilds just the [AppBuild], which is also what [UpdateStatus.Failed.lastKnown] carries.
 *
 * [PREF_SEEN_VERSION_CODE] is separate from all of that and answers one question only: has the
 * user already been told about this version? The daily job notifies at most once per version;
 * [markSeen] is what closes that off, and it is called only after a notification actually went
 * out (a missing `POST_NOTIFICATIONS` grant must not silently consume the one notification the
 * user would have got once they grant it).
 *
 * ## Threading
 *
 * [check] is a suspend function; all of its work happens inside `ManifestSource.fetch`, which
 * is on `Dispatchers.IO`. The preference writes it makes are `apply()`, so they do not block.
 * The non-suspending readers ([lastOutcome], [lastKnownAvailable]) touch only the already-loaded
 * preferences and are safe from the main thread.
 */
class UpdateChecker @JvmOverloads constructor(
    private val context: Context,
    private val manifestSource: ManifestSource = ManifestSource(context),
    private val prefs: SharedPreferences = PrefsManager.getPrefs(context),
    private val channelProvider: () -> String = { DistributionConfig.channel(context) },
    private val currentVersionCode: Int = BuildConfig.VERSION_CODE,
    private val currentVersionName: String = BuildConfig.VERSION_NAME,
    private val deviceSdk: Int = Build.VERSION.SDK_INT,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /** The build type the manifest is asked about — `release`, or a debug override. */
    val channel: String get() = channelProvider()

    /** Whether [channel] is one the published manifest can actually carry. */
    val hasChannel: Boolean get() = channelIsPublished(channel)

    /** The version this code is, for the "installed version" row. */
    val installedVersion: Pair<String, Int> get() = currentVersionName to currentVersionCode

    /**
     * Check, and record the outcome.
     *
     * @param force user-initiated: ask the server even if the cached manifest is still fresh.
     *   Leave it `false` for the daily background check.
     */
    suspend fun check(force: Boolean): UpdateStatus {
        val channel = channelProvider()
        if (!channelIsPublished(channel)) {
            // Decided without a fetch: see UpdateStatus.NoChannel.
            clearAvailable()
            return UpdateStatus.NoChannel
        }

        val manifest = manifestSource.fetch(force).getOrElse { failure ->
            return UpdateStatus.Failed(failure, lastKnownAvailable())
        }

        val checkedAt = manifest.fetchedAt.takeIf { it > 0L } ?: clock()
        prefs.edit().putLong(PREF_LAST_CHECK_MS, checkedAt).apply()

        val entry = manifest.appFor(channel)
        if (entry == null) {
            // The manifest is readable and simply has no entry for us — the same user-facing
            // state as a debug build, reached the long way round.
            clearAvailable()
            return UpdateStatus.NoChannel
        }

        val installable = entry.isNewerThan(currentVersionCode) && entry.isInstallableOn(deviceSdk)
        return if (installable) {
            persistAvailable(entry)
            UpdateStatus.Available(entry, checkedAt)
        } else {
            clearAvailable()
            UpdateStatus.UpToDate(currentVersionCode, checkedAt, manifest.fromCache)
        }
    }

    /**
     * The last outcome, rebuilt from preferences — what the UI renders before (and instead of)
     * a network call. `null` when no check has ever completed on this install.
     */
    fun lastOutcome(): UpdateStatus? {
        if (!hasChannel) return UpdateStatus.NoChannel
        lastKnownAvailable()?.let { return it }
        val checkedAt = lastCheckedAt()
        return if (checkedAt > 0L) {
            UpdateStatus.UpToDate(currentVersionCode, checkedAt, fromCache = true)
        } else {
            null
        }
    }

    /** The update the last successful check found, or `null` if it found none. */
    fun lastKnownAvailable(): UpdateStatus.Available? {
        val versionCode = prefs.getInt(PREF_AVAILABLE_VERSION_CODE, 0)
        if (versionCode <= currentVersionCode) return null
        val url = prefs.getString(PREF_AVAILABLE_URL, null) ?: return null
        val build = AppBuild(
            versionCode = versionCode,
            versionName = prefs.getString(PREF_AVAILABLE_VERSION_NAME, "").orEmpty(),
            url = url,
            sha256 = prefs.getString(PREF_AVAILABLE_SHA256, "").orEmpty(),
            size = prefs.getLong(PREF_AVAILABLE_SIZE, 0L),
            minSdk = prefs.getInt(PREF_AVAILABLE_MIN_SDK, 0),
            notes = prefs.getString(PREF_AVAILABLE_NOTES, null)?.takeIf { it.isNotBlank() },
        )
        if (!build.isInstallableOn(deviceSdk)) return null
        return UpdateStatus.Available(build, lastCheckedAt())
    }

    /** When the last completed check happened, or 0. */
    fun lastCheckedAt(): Long = prefs.getLong(PREF_LAST_CHECK_MS, 0L)

    /** The highest version the user has already been notified about. */
    fun seenVersionCode(): Int = prefs.getInt(PREF_SEEN_VERSION_CODE, 0)

    /** Whether [status] is an update the user has not been told about yet. */
    fun shouldNotify(status: UpdateStatus): Boolean =
        status is UpdateStatus.Available && status.build.versionCode > seenVersionCode()

    /** Record that the user has been told about [versionCode]; never moves backwards. */
    fun markSeen(versionCode: Int) {
        if (versionCode > seenVersionCode()) {
            prefs.edit().putInt(PREF_SEEN_VERSION_CODE, versionCode).apply()
        }
    }

    private fun persistAvailable(build: AppBuild) {
        prefs.edit()
            .putInt(PREF_AVAILABLE_VERSION_CODE, build.versionCode)
            .putString(PREF_AVAILABLE_VERSION_NAME, build.versionName)
            .putString(PREF_AVAILABLE_URL, build.url)
            .putString(PREF_AVAILABLE_SHA256, build.sha256)
            .putLong(PREF_AVAILABLE_SIZE, build.size)
            .putInt(PREF_AVAILABLE_MIN_SDK, build.minSdk)
            .putString(PREF_AVAILABLE_NOTES, build.notes.orEmpty())
            .apply()
    }

    private fun clearAvailable() {
        if (prefs.getInt(PREF_AVAILABLE_VERSION_CODE, 0) == 0) return
        prefs.edit()
            .remove(PREF_AVAILABLE_VERSION_CODE)
            .remove(PREF_AVAILABLE_VERSION_NAME)
            .remove(PREF_AVAILABLE_URL)
            .remove(PREF_AVAILABLE_SHA256)
            .remove(PREF_AVAILABLE_SIZE)
            .remove(PREF_AVAILABLE_MIN_SDK)
            .remove(PREF_AVAILABLE_NOTES)
            .apply()
    }

    companion object {

        /**
         * The Gradle build type of a debug build. The published manifest's `app` section carries
         * `release` only — deliberately, there is no debug channel — so a channel equal to this
         * is a channel the manifest cannot have an entry for.
         */
        const val UNPUBLISHED_CHANNEL: String = "debug"

        /** When the last completed check ran, epoch millis. */
        const val PREF_LAST_CHECK_MS: String = "pref_update_last_check_ms"

        /** `versionCode` of the update on record, or absent/0 for "none". */
        const val PREF_AVAILABLE_VERSION_CODE: String = "pref_update_available_version_code"

        /** `versionName` of the update on record. */
        const val PREF_AVAILABLE_VERSION_NAME: String = "pref_update_available_version_name"

        /** Download URL of the update on record. */
        const val PREF_AVAILABLE_URL: String = "pref_update_available_url"

        /** Expected SHA-256 of the update on record — the half of integrity that is not HTTPS. */
        const val PREF_AVAILABLE_SHA256: String = "pref_update_available_sha256"

        /** Size in bytes of the update on record; also the progress denominator. */
        const val PREF_AVAILABLE_SIZE: String = "pref_update_available_size"

        /** `minSdk` of the update on record. */
        const val PREF_AVAILABLE_MIN_SDK: String = "pref_update_available_min_sdk"

        /** Release notes of the update on record. */
        const val PREF_AVAILABLE_NOTES: String = "pref_update_available_notes"

        /** Highest version the user has been notified about; suppresses a repeat notification. */
        const val PREF_SEEN_VERSION_CODE: String = "pref_update_seen_version_code"

        /**
         * Whether a channel string is one the published manifest can carry. `release` yes,
         * `debug` no; a debug build that sets `pref_distribution_channel_override` to something
         * else is taken at its word, which is the point of the override.
         */
        @JvmStatic
        fun channelIsPublished(channel: String): Boolean =
            channel.isNotBlank() && channel != UNPUBLISHED_CHANNEL

        /** Whether *this* build has a channel to check at all. */
        @JvmStatic
        fun hasUpdateChannel(context: Context): Boolean =
            channelIsPublished(DistributionConfig.channel(context))
    }
}

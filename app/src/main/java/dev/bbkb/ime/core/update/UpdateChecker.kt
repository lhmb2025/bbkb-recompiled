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
 * The answer to "is there a newer BBKB?". [NoChannel] is an ordinary state, not an error: the
 * manifest carries a `release` entry only, so a debug build has nothing to compare against.
 */
sealed class UpdateStatus {
    /** No channel in the manifest (a debug build without the override); decided with no network call. */
    object NoChannel : UpdateStatus()

    /**
     * Nothing newer, or newer but needs a newer Android.
     *
     * @param fromCache the manifest came off disk, so [checkedAt] is when it was fetched
     */
    data class UpToDate(val current: Int, val checkedAt: Long, val fromCache: Boolean) :
        UpdateStatus()

    /** A strictly newer build this device can install. */
    data class Available(val build: AppBuild, val checkedAt: Long) : UpdateStatus()

    /**
     * @param error a `DistributionException` when it came out of the fetch
     * @param lastKnown the last found update, so a failed refresh keeps the card on screen
     */
    data class Failed(val error: Throwable, val lastKnown: Available?) : UpdateStatus()
}

/**
 * Runs the update check and persists its outcome, so the Updates screen and the main-menu banner
 * render the last answer with no network call. [PREF_SEEN_VERSION_CODE] tracks the one
 * notification per version; [markSeen] is called only after one actually went out, so a missing
 * `POST_NOTIFICATIONS` grant does not consume it. [check] does its work in `ManifestSource.fetch`
 * (on `Dispatchers.IO`); the other readers only touch loaded prefs and are main-thread safe.
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
    /** The build type the manifest is asked about: `release`, or a debug override. */
    val channel: String get() = channelProvider()

    /** Whether [channel] is one the published manifest can actually carry. */
    val hasChannel: Boolean get() = channelIsPublished(channel)

    val installedVersion: Pair<String, Int> get() = currentVersionName to currentVersionCode

    /** @param force user-initiated: skip a still-fresh cached manifest (false for the daily check) */
    suspend fun check(force: Boolean): UpdateStatus {
        val channel = channelProvider()
        if (!channelIsPublished(channel)) {
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
            // Readable manifest with no entry for us: same user-facing state as a debug build.
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

    /** The last outcome rebuilt from preferences; null when no check has ever completed. */
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
        /** The debug build type; the manifest deliberately has no debug channel. */
        const val UNPUBLISHED_CHANNEL: String = "debug"

        /** When the last completed check ran, epoch millis. */
        const val PREF_LAST_CHECK_MS: String = "pref_update_last_check_ms"

        /** `versionCode` of the update on record, or absent/0 for "none". */
        const val PREF_AVAILABLE_VERSION_CODE: String = "pref_update_available_version_code"

        const val PREF_AVAILABLE_VERSION_NAME: String = "pref_update_available_version_name"

        const val PREF_AVAILABLE_URL: String = "pref_update_available_url"

        /** Expected SHA-256 of the update on record: the integrity half that is not HTTPS. */
        const val PREF_AVAILABLE_SHA256: String = "pref_update_available_sha256"

        /** Size in bytes of the update on record; also the progress denominator. */
        const val PREF_AVAILABLE_SIZE: String = "pref_update_available_size"

        const val PREF_AVAILABLE_MIN_SDK: String = "pref_update_available_min_sdk"

        const val PREF_AVAILABLE_NOTES: String = "pref_update_available_notes"

        /** Highest version the user has been notified about; suppresses a repeat notification. */
        const val PREF_SEEN_VERSION_CODE: String = "pref_update_seen_version_code"

        /** `release` yes, `debug` no; any other override value is taken at its word. */
        @JvmStatic
        fun channelIsPublished(channel: String): Boolean =
            channel.isNotBlank() && channel != UNPUBLISHED_CHANNEL

        @JvmStatic
        fun hasUpdateChannel(context: Context): Boolean =
            channelIsPublished(DistributionConfig.channel(context))
    }
}

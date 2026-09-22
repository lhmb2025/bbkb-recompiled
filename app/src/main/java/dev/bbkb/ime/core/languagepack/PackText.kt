package dev.bbkb.ime.core.languagepack

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import dev.bbkb.ime.R
import dev.bbkb.ime.core.distribution.HttpStatusException
import dev.bbkb.ime.core.distribution.IntegrityException
import dev.bbkb.ime.core.distribution.ManifestFormatException
import dev.bbkb.ime.core.distribution.OfflineException
import dev.bbkb.ime.core.distribution.UnsupportedSchemaException

/**
 * The words the pack UI puts on screen for a failure, a size and a catalogue date.
 *
 * Kept out of the composable so the mapping from the foundation's sealed exception types to
 * user-facing copy exists once. The point of those types is that nothing has to parse a message
 * string, and this is the one place that consumes them.
 */
object PackText {

    /**
     * A short, honest sentence for [error], and one the user can act on.
     *
     * Four outcomes, because there are four different things the user can do about them: wait for
     * a connection, try again, worry (the bytes did not match what was promised), or update the
     * app. Everything else — a transport error, an HTTP status, a failed install — is "it did not
     * work, try again", which is the truth and the only useful action.
     */
    fun errorMessage(context: Context, error: Throwable?): String = when (error) {
        is OfflineException -> context.getString(R.string.language_packs_error_offline)
        is IntegrityException -> context.getString(R.string.language_packs_error_verification)
        is UnsupportedSchemaException -> context.getString(R.string.language_packs_error_app_too_old)
        is ManifestFormatException -> context.getString(R.string.language_packs_error_app_too_old)
        is HttpStatusException -> context.getString(R.string.language_packs_error_network)
        else -> context.getString(R.string.language_packs_error_network)
    }

    /** `4.4 MB`, in the user's own locale and units. */
    fun size(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

    /**
     * "Catalogue from 3 Mar (offline)" — shown only when the catalogue came off disk, so the user
     * can tell a stale list from a fresh one instead of wondering why a language is missing.
     */
    fun catalogueAsOf(context: Context, fetchedAt: Long): String {
        val when_ = if (fetchedAt > 0L) {
            DateUtils.getRelativeTimeSpanString(
                fetchedAt,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
            ).toString()
        } else {
            ""
        }
        return context.getString(R.string.language_packs_catalog_offline, when_)
    }
}

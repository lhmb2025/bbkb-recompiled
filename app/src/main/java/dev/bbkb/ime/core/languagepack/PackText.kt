package dev.bbkb.ime.core.languagepack

import android.content.Context
import android.text.format.Formatter
import dev.bbkb.ime.R
import dev.bbkb.ime.core.distribution.HttpStatusException
import dev.bbkb.ime.core.distribution.IntegrityException
import dev.bbkb.ime.core.distribution.ManifestFormatException
import dev.bbkb.ime.core.distribution.OfflineException
import dev.bbkb.ime.core.distribution.UnsupportedSchemaException

/** Pack UI text for failures and sizes: the one place that maps the distribution exceptions to copy. */
object PackText {
    /** One message per thing the user can do: reconnect, retry, distrust the file, or update the app. */
    fun errorMessage(context: Context, error: Throwable?): String = when (error) {
        is OfflineException -> context.getString(R.string.language_packs_error_offline)
        is IntegrityException -> context.getString(R.string.language_packs_error_verification)
        is UnsupportedSchemaException -> context.getString(R.string.language_packs_error_app_too_old)
        is ManifestFormatException -> context.getString(R.string.language_packs_error_app_too_old)
        is HttpStatusException -> context.getString(R.string.language_packs_error_network)
        else -> context.getString(R.string.language_packs_error_network)
    }

    fun size(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)
}

package dev.bbkb.ime.keyboard.inputboard.clipboard

import android.content.Context
import dev.bbkb.ime.core.settings.PrefsManager

/** The clipboard board's preferences. The settings search index anchors on the keys: do not rename them. */
object ClipboardPrefs {
    /** Off stops capturing and wipes the stored history. */
    const val KEY_HISTORY_ENABLED = "pref_clipboard_history_enabled"
    const val DEFAULT_HISTORY_ENABLED = true

    /** How long an unpinned clip is kept: one of the `RETENTION_*` values. */
    const val KEY_RETENTION = "pref_clipboard_retention"
    const val RETENTION_NEVER = "never"
    const val RETENTION_1H = "1h"
    const val RETENTION_24H = "24h"
    const val RETENTION_7D = "7d"
    const val DEFAULT_RETENTION = RETENTION_1H

    /** Off by default: it uses the network. */
    const val KEY_LINK_PREVIEWS = "pref_clipboard_link_previews"
    const val DEFAULT_LINK_PREVIEWS = false

    /** The retention of [RETENTION_NEVER]. */
    const val NO_EXPIRY = Long.MAX_VALUE

    private const val HOUR_MS = 60L * 60L * 1000L

    /** An unknown value falls back to the default. */
    @JvmStatic
    fun retentionMillis(value: String?): Long = when (value) {
        RETENTION_NEVER -> NO_EXPIRY
        RETENTION_1H -> HOUR_MS
        RETENTION_24H -> 24 * HOUR_MS
        RETENTION_7D -> 7 * 24 * HOUR_MS
        else -> HOUR_MS
    }

    @JvmStatic
    fun isHistoryEnabled(context: Context): Boolean =
        readBoolean(context, KEY_HISTORY_ENABLED, DEFAULT_HISTORY_ENABLED)

    @JvmStatic
    fun retentionMillis(context: Context): Long =
        retentionMillis(readString(context, KEY_RETENTION, DEFAULT_RETENTION))

    @JvmStatic
    fun isLinkPreviewsEnabled(context: Context): Boolean =
        readBoolean(context, KEY_LINK_PREVIEWS, DEFAULT_LINK_PREVIEWS)

    // Credential-protected prefs throw before unlock; the defaults stand in until then.
    private fun readBoolean(context: Context, key: String, default: Boolean): Boolean = try {
        PrefsManager.getPrefs(context).getBoolean(key, default)
    } catch (e: IllegalStateException) {
        default
    } catch (e: ClassCastException) {
        default
    }

    private fun readString(context: Context, key: String, default: String): String = try {
        PrefsManager.getPrefs(context).getString(key, default) ?: default
    } catch (e: IllegalStateException) {
        default
    } catch (e: ClassCastException) {
        default
    }
}

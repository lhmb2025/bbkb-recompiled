package dev.bbkb.ime.keyboard.inputboard.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.os.Build
import android.os.PersistableBundle

/**
 * One immutable clipboard history row (safe to hand to the IO thread and `DiffUtil`). Text only:
 * [toClipData] rebuilds a plain-text clip on demand, since a `ClipData` can carry URIs and intents
 * that must not persist.
 *
 * @property id unique within the history and the RecyclerView stable id, never reused while shown
 * @property label the source's label, kept verbatim: it carries the Password Keeper protocol (`bb.pk`)
 * @property createdAtMs when last copied (a re-copy refreshes it); retention is measured from here
 * @property pinnedAtMs when pinned, 0 while unpinned; orders the pinned section
 */
data class ClipEntry(
    val id: Long,
    val text: String,
    val label: String?,
    val mime: String,
    val createdAtMs: Long,
    val isPinned: Boolean = false,
    val pinnedAtMs: Long = 0L,
) {

    fun withId(newId: Long): ClipEntry = copy(id = newId)

    fun withCreatedAt(timeMs: Long): ClipEntry = copy(createdAtMs = timeMs)

    fun pinnedAt(timeMs: Long): ClipEntry = copy(isPinned = true, pinnedAtMs = timeMs)

    /** Rejoins the recent section as if just copied, with a full retention window. */
    fun unpinnedAt(timeMs: Long): ClipEntry =
        copy(isPinned = false, pinnedAtMs = 0L, createdAtMs = timeMs)

    /** Pull future timestamps back to [nowMs], so a clock set back cannot extend retention or misorder pins. */
    fun clampedTo(nowMs: Long): ClipEntry =
        if (createdAtMs <= nowMs && pinnedAtMs <= nowMs) this
        else copy(createdAtMs = minOf(createdAtMs, nowMs), pinnedAtMs = minOf(pinnedAtMs, nowMs))

    fun hasLabel(expected: String): Boolean = expected == label

    /** [sensitive] marks it [ClipDescription.EXTRA_IS_SENSITIVE] (API 33+) so the system's copy confirmation hides it. */
    fun toClipData(sensitive: Boolean): ClipData {
        val clip = ClipData.newPlainText(label ?: "", text)
        if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        return clip
    }

    companion object {
        /** A row for [clip] (its first item's text), or null when it has no text. */
        @JvmStatic
        fun fromClipData(id: Long, clip: ClipData, nowMs: Long): ClipEntry? {
            val text = ClipboardItem.getTextFromClipData(clip) ?: return null
            val description = clip.description
            val mime = if (description != null && description.mimeTypeCount > 0) {
                description.getMimeType(0)
            } else {
                ClipDescription.MIMETYPE_TEXT_PLAIN
            }
            return ClipEntry(id, text, description?.label?.toString(), mime, nowMs)
        }
    }
}

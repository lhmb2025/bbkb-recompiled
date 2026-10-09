package dev.bbkb.ime.keyboard.inputboard.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.os.Build
import android.os.PersistableBundle

/**
 * One row of the clipboard history: the copied text and what the board needs to order, expire and
 * label it.
 *
 * Immutable, so a list of these can be handed to the store's IO thread and to `DiffUtil` as it is.
 * Every change makes a new instance through one of the `with…` functions, which Java callers use in
 * place of the data-class `copy`.
 *
 * The history keeps text only. [ClipData] is rebuilt by [toClipData] at the one moment it is
 * needed, when the user copies a row back to the system clipboard, rather than held for every row:
 * a `ClipData` can carry URIs and intents the board never shows and must not persist.
 *
 * @property id unique within the history; also the RecyclerView stable id, so it is never reused
 *   for a different clip while the board is up.
 * @property label the source's [ClipDescription] label. It carries the Password Keeper protocol
 *   (`bb.pk` / `bb.pk.clear`), so it is kept verbatim.
 * @property mime the source's first MIME type. Only text is kept, so [toClipData] always offers
 *   plain text back; the type is kept so a row can tell what kind of clip it came from.
 * @property createdAtMs when the clip was last copied (a re-copy refreshes it). Retention is
 *   measured from here.
 * @property pinnedAtMs when the row was pinned; 0 while unpinned. Orders the pinned section.
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

    /** An unpinned row rejoins the recent section as if just copied, with a full retention window. */
    fun unpinnedAt(timeMs: Long): ClipEntry =
        copy(isPinned = false, pinnedAtMs = 0L, createdAtMs = timeMs)

    /**
     * Pull a timestamp that lies in the future back to [nowMs]. After the clock is set back, such a
     * row would otherwise outlive its retention, or sort above rows pinned after it.
     */
    fun clampedTo(nowMs: Long): ClipEntry =
        if (createdAtMs <= nowMs && pinnedAtMs <= nowMs) this
        else copy(createdAtMs = minOf(createdAtMs, nowMs), pinnedAtMs = minOf(pinnedAtMs, nowMs))

    fun hasLabel(expected: String): Boolean = expected == label

    /**
     * The clip to put back on the system clipboard. [sensitive] marks it
     * [ClipDescription.EXTRA_IS_SENSITIVE] on API 33+, so the system's copy confirmation hides it.
     */
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

        /**
         * A row for [clip], or null when it has no text. The first item's text is what the board
         * pastes, exactly as before the rewrite; an HTML or URI clip keeps its plain-text side.
         */
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

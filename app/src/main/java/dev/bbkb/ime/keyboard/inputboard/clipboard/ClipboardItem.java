package dev.bbkb.ime.keyboard.inputboard.clipboard;

import android.content.ClipData;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import dev.bbkb.ime.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One row of the clipboard board's list: a section header or a clip.
 *
 * <p>Value-typed, because {@link ClipboardAdapter} is a {@code ListAdapter}: DiffUtil decides
 * whether a row is the same row by {@link #getStableId()} and whether it needs rebinding by
 * {@link #equals}. A clip's stable id is its {@link ClipEntry#getId()} (always positive); the two
 * headers have fixed negative ids, so neither can collide with a clip.
 */
public final class ClipboardItem {

    static final int TYPE_HEADER = 0;

    static final int TYPE_CLIP = 1;

    static final long HEADER_ID_PINNED = -1L;

    static final long HEADER_ID_RECENT = -2L;

    final int viewType;

    /** The clip, for {@link #TYPE_CLIP}; null for a header. */
    @Nullable
    final ClipEntry entry;

    /** The header's title, for {@link #TYPE_HEADER}; 0 for a clip. */
    @StringRes
    final int headerTitle;

    private final long stableId;

    private ClipboardItem(int viewType, @Nullable ClipEntry entry, @StringRes int headerTitle,
            long stableId) {
        this.viewType = viewType;
        this.entry = entry;
        this.headerTitle = headerTitle;
        this.stableId = stableId;
    }

    static ClipboardItem clip(@NonNull ClipEntry entry) {
        return new ClipboardItem(TYPE_CLIP, entry, 0, entry.getId());
    }

    static ClipboardItem header(@StringRes int title, long id) {
        return new ClipboardItem(TYPE_HEADER, null, title, id);
    }

    long getStableId() {
        return this.stableId;
    }

    /**
     * The rows for a history: with nothing pinned, the clips alone, as the board always showed
     * them; with pinned clips, a "Pinned" section and then a "Recent" one (its header only when
     * there is something under it).
     */
    static List<ClipboardItem> build(List<ClipEntry> pinned, List<ClipEntry> recent) {
        List<ClipboardItem> items = new ArrayList<>(pinned.size() + recent.size() + 2);
        if (!pinned.isEmpty()) {
            items.add(header(R.string.clipboard_section_pinned, HEADER_ID_PINNED));
            for (ClipEntry entry : pinned) {
                items.add(clip(entry));
            }
            if (!recent.isEmpty()) {
                items.add(header(R.string.clipboard_section_recent, HEADER_ID_RECENT));
            }
        }
        for (ClipEntry entry : recent) {
            items.add(clip(entry));
        }
        return items;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof ClipboardItem)) {
            return false;
        }
        ClipboardItem other = (ClipboardItem) obj;
        return this.viewType == other.viewType && this.stableId == other.stableId
                && this.headerTitle == other.headerTitle && Objects.equals(this.entry, other.entry);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.viewType, this.stableId, this.headerTitle, this.entry);
    }

    /**
     * The text of a clip's first item, or null if {@code clipData} is null, has no items, or its
     * first item has no text. Shared with the FCC board, which reads the live clip through it.
     */
    public static String getTextFromClipData(ClipData clipData) {
        ClipData.Item itemAt;
        CharSequence text;
        if (clipData == null || clipData.getItemCount() == 0
                || (itemAt = clipData.getItemAt(0)) == null || (text = itemAt.getText()) == null) {
            return null;
        }
        return String.valueOf(text);
    }
}

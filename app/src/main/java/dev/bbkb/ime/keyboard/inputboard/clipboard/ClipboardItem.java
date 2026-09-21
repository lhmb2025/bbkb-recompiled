package dev.bbkb.ime.keyboard.inputboard.clipboard;

import android.content.ClipData;

/**
 * Represents a single clipboard entry in the history.
 * 
 * <p>This is a lightweight wrapper around {@link ClipboardHistoryManager.ClipEntry} that provides
 * a clean API for the clipboard UI layer. It encapsulates the clipboard data and metadata
 * needed for display in the RecyclerView.
 * 
 * <p><b>Lifecycle:</b>
 * <ol>
 *   <li>Created when clipboard history is loaded or updated</li>
 *   <li>Bound to a ViewHolder for display</li>
 *   <li>Removed when user deletes the item or history is cleared</li>
 * </ol>
 * 
 * <p><b>Thread Safety:</b> This class is not thread-safe. Instances should only be
 * accessed from the main/UI thread.
 * 
 * @see ClipboardHistoryManager
 * @see ClipboardAdapter
 * @see ClipboardViewHolder
 */
public class ClipboardItem {

    /**
     * Item type identifier. Currently always 0 (text/generic type).
     * Reserved for future use if different item types need different rendering.
     */
    final int itemType;

    /**
     * Reference to the underlying clipboard history entry.
     * Contains the actual ClipData and metadata (timestamp, work profile flag, etc.)
     */
    final ClipboardHistoryManager.ClipEntry historyItem;

    /**
     * Create a new ClipboardItem wrapping a history entry.
     * 
     * @param historyItem The clipboard history entry to wrap (must not be null)
     * @throws NullPointerException if historyItem is null
     */
    ClipboardItem(ClipboardHistoryManager.ClipEntry historyItem) {
        this.historyItem = historyItem;
        this.itemType = 0;
    }

    /**
     * Extract text content from ClipData.
     * 
     * <p>This utility method safely extracts the first text item from ClipData,
     * handling null values and empty clips gracefully.
     * 
     * <p><b>Usage:</b>
     * <pre>{@code
     * ClipData clipData = clipboard.getPrimaryClip();
     * String text = ClipboardItem.getTextFromClipData(clipData);
     * if (text != null) {
     *     // Use the text
     * }
     * }</pre>
     * 
     * @param clipData The ClipData to extract text from (may be null)
     * @return The text content, or null if:
     *         <ul>
     *           <li>clipData is null</li>
     *           <li>clipData has no items</li>
     *           <li>First item has no text content</li>
     *         </ul>
     */
    public static String getTextFromClipData(ClipData clipData) {
        ClipData.Item itemAt;
        CharSequence text;
        if (clipData == null || (itemAt = clipData.getItemAt(0)) == null || (text = itemAt.getText()) == null) {
            return null;
        }
        return String.valueOf(text);
    }

    /**
     * Get the text representation of this clipboard item.
     * 
     * <p>This is the text that will be displayed in the UI and pasted when
     * the user selects this item.
     * 
     * @return The text content of this item, or null if the item has no text
     */
    @Override
    public String toString() {
        return getTextFromClipData(this.historyItem.mClipData);
    }
}

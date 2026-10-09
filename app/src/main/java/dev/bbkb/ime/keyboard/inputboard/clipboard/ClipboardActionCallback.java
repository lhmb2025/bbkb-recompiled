package dev.bbkb.ime.keyboard.inputboard.clipboard;

/**
 * What the clipboard list asks its owner to do with a row.
 *
 * <p>The adapter turns touches into these calls — a tap is {@link #onPasteClip}, everything else
 * comes from the row's menu (long-press the row, or tap its overflow button) — and
 * {@link ClipboardView} carries them out against {@link ClipboardHistoryManager}. The menu's
 * "Show full text" never reaches here: expanding a row is the list's own business.
 *
 * <p>All methods are called on the main thread.
 */
interface ClipboardActionCallback {

    /** Tap: insert the row's text into the field. The system clipboard is left as it is. */
    void onPasteClip(ClipEntry entry);

    /** Menu "Copy": make the row the system clipboard's current clip. */
    void onCopyClip(ClipEntry entry);

    /** Menu "Pin" ({@code pin} true) or "Unpin" (false). */
    void onPinClip(ClipEntry entry, boolean pin);

    /** Menu "Share": hand the row's text to the system share sheet. The row stays. */
    void onShareClip(ClipEntry entry);

    /** Menu "Delete": remove the row from the history. */
    void onDeleteClip(ClipEntry entry);
}

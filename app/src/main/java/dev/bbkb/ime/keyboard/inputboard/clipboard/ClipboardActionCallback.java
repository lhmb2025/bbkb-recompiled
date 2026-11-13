package dev.bbkb.ime.keyboard.inputboard.clipboard;

/**
 * Row actions the clipboard list asks its owner to perform: a tap pastes, the rest come from the
 * row menu ("Show full text" stays in the list). Main thread.
 */
interface ClipboardActionCallback {
    /** Insert the row's text into the field; the system clipboard is left as it is. */
    void onPasteClip(ClipEntry entry);

    void onCopyClip(ClipEntry entry);

    void onPinClip(ClipEntry entry, boolean pin);

    void onShareClip(ClipEntry entry);

    void onDeleteClip(ClipEntry entry);
}

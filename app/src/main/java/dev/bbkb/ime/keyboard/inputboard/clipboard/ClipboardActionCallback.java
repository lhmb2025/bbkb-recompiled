package dev.bbkb.ime.keyboard.inputboard.clipboard;

/**
 * Callback interface for clipboard item actions.
 * 
 * <p>This interface defines the contract between the clipboard UI (adapter/viewholder)
 * and the clipboard logic (ClipboardView). It follows the delegation pattern to maintain
 * clean separation of concerns:
 * <ul>
 *   <li>UI components (adapter, viewholder) handle display and user interactions</li>
 *   <li>Logic components (ClipboardView) handle business logic and state management</li>
 * </ul>
 * 
 * <p><b>Implementation:</b> ClipboardView implements this interface and registers itself
 * as the callback via {@link ClipboardAdapter#setActionCallback(ClipboardActionCallback)}.
 * 
 * <p><b>Thread Safety:</b> All methods are called on the main/UI thread.
 * 
 * @see ClipboardView
 * @see ClipboardAdapter
 * @see ClipboardViewHolder
 */
interface ClipboardActionCallback {
    
    /**
     * Called when the user requests to delete a clipboard item.
     * 
     * <p>This is triggered when:
     * <ul>
     *   <li>User swipes left to reveal the delete button</li>
     *   <li>User taps the delete button</li>
     * </ul>
     * 
     * <p><b>Expected behavior:</b>
     * <ol>
     *   <li>Remove the item from clipboard history</li>
     *   <li>Update the primary clip if needed</li>
     *   <li>Notify the adapter to refresh the UI</li>
     *   <li>Update empty state visibility</li>
     * </ol>
     * 
     * @param item The clipboard item to delete (must not be null)
     * @throws NullPointerException if item is null
     */
    void onDeleteClip(ClipboardItem item);

    /**
     * Called when the user requests to share a clipboard item.
     * 
     * <p>This is triggered when:
     * <ul>
     *   <li>User swipes right to reveal the share button</li>
     *   <li>User taps the share button</li>
     * </ul>
     * 
     * <p><b>Expected behavior:</b>
     * <ol>
     *   <li>Extract text content from the clipboard item</li>
     *   <li>Create a share Intent with ACTION_SEND</li>
     *   <li>Launch the system share chooser</li>
     * </ol>
     * 
     * <p><b>Note:</b> The share action does not remove the item from clipboard history.
     * 
     * @param item The clipboard item to share (must not be null)
     * @throws NullPointerException if item is null
     */
    void onShareClip(ClipboardItem item);

    /**
     * Called when the user taps a clipboard item to paste its content.
     * 
     * <p>This is triggered when:
     * <ul>
     *   <li>User taps on an unrevealed clipboard item</li>
     *   <li>User taps the text/image area of a clipboard item</li>
     * </ul>
     * 
     * <p><b>Expected behavior:</b>
     * <ol>
     *   <li>Set the item as the system's primary clip</li>
     *   <li>Commit the text to the current input field</li>
     *   <li>Close/hide the clipboard view</li>
     *   <li>Return focus to the input field</li>
     * </ol>
     * 
     * @param item The clipboard item to paste (must not be null)
     * @throws NullPointerException if item is null
     */
    void onPasteClip(ClipboardItem item);
}

package dev.bbkb.ime.keyboard.inputboard.emoji;

/**
 * The emoji board's search query, pure state. {@link Origin#TYPED_IN_BOARD}: hardware keys typed
 * with the board open; the tab row becomes a search field and a pick keeps the query.
 * {@link Origin#FROM_EDITOR}: the composing word when opened from the on-screen keyboard with
 * Dynamic Search on; results show on Recents and a pick replaces the word. {@link #generation()}
 * is bumped on every change so a search finishing for an older query can be dropped.
 */
public final class EmojiSearchQuery {

    public enum Origin { NONE, TYPED_IN_BOARD, FROM_EDITOR }

    /** A query shorter than this shows the field (when typed) but searches nothing. */
    public static final int MIN_SEARCH_LENGTH = 2;

    private final StringBuilder text = new StringBuilder();
    private Origin origin = Origin.NONE;
    private int generation = 0;
    private int categoryBeforeSearch = -1;

    public Origin origin() {
        return origin;
    }

    public boolean isTypedInBoard() {
        return origin == Origin.TYPED_IN_BOARD;
    }

    public boolean isFromEditor() {
        return origin == Origin.FROM_EDITOR;
    }

    public String text() {
        return text.toString();
    }

    public int length() {
        return text.length();
    }

    public boolean isEmpty() {
        return text.length() == 0;
    }

    /** Whether the query is long enough to search on. */
    public boolean isSearchable() {
        return text.length() >= MIN_SEARCH_LENGTH;
    }

    public int generation() {
        return generation;
    }

    /** The category that was current when a typed query began, or -1. */
    public int categoryBeforeSearch() {
        return categoryBeforeSearch;
    }

    /**
     * Appends typed characters; a query not typed in the board starts over as a typed one.
     *
     * @param currentCategoryId the category current now, remembered so the board can return to it
     * @return true when this call started a typed query (the search field should appear)
     */
    public boolean append(CharSequence chars, int currentCategoryId) {
        boolean started = origin != Origin.TYPED_IN_BOARD;
        if (started) {
            text.setLength(0);
            origin = Origin.TYPED_IN_BOARD;
            categoryBeforeSearch = currentCategoryId;
        }
        text.append(chars);
        generation++;
        return started;
    }

    /**
     * Removes the last code point of a typed query.
     *
     * @return false when there is no typed query or it is already empty, so the caller passes the
     *         key through to the editor
     */
    public boolean backspace() {
        if (origin != Origin.TYPED_IN_BOARD || text.length() == 0) {
            return false;
        }
        int codePoint = text.codePointBefore(text.length());
        text.setLength(text.length() - Character.charCount(codePoint));
        generation++;
        return true;
    }

    /** Replaces the query with the editor's composing word; null or empty is not searchable. */

    public void setFromEditor(String composing) {
        text.setLength(0);
        if (composing != null) {
            text.append(composing);
        }
        origin = Origin.FROM_EDITOR;
        categoryBeforeSearch = -1;
        generation++;
    }

    /**
     * Ends the query of either origin.
     *
     * @return the category that was current when a typed query began, or -1
     */
    public int clear() {
        int restore = categoryBeforeSearch;
        text.setLength(0);
        origin = Origin.NONE;
        categoryBeforeSearch = -1;
        generation++;
        return restore;
    }
}

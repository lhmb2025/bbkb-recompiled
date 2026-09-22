package dev.bbkb.ime.core.textinput.composing;

/**
 * Holds all mutable state associated with a recorrection session — where the user taps a
 * previously committed word to re-open it for editing.
 *
 * <p>Extracted from {@link ComposingTextTracker} so that recorrection state has a single,
 * clearly scoped owner. {@link ComposingTextTracker} keeps a package-private instance and
 * delegates all recorrection getters/setters to this class.</p>
 */
class RecorrectionState {

    private int mCursorPos = -1;
    private String mOriginalWord;
    private String mCurrentWord;

    /**
     * Resets all recorrection tracking state. Called when a recorrection session ends or is
     * abandoned.
     */
    synchronized void reset() {
        mCursorPos = -1;
        mOriginalWord = null;
        mCurrentWord = null;
    }

    /**
     * Records the start cursor position and original word for a new recorrection session.
     *
     * @param cursorPos    cursor position at which recorrection begins (chars before cursor)
     * @param originalWord the original word text before any edits
     */
    synchronized void setInfo(int cursorPos, String originalWord) {
        mCursorPos = cursorPos;
        mOriginalWord = originalWord;
    }

    /**
     * Snapshots the current composing text when {@code eventIndex} matches the recorrection
     * cursor position. Called after each key event during an active recorrection session.
     *
     * @param composingText current composing text string
     * @param eventIndex    current event index from {@link ComposingTextTracker}
     */
    synchronized void updateSnapshot(String composingText, int eventIndex) {
        if (mCursorPos != -1 && mOriginalWord != null && mCursorPos == eventIndex) {
            mCurrentWord = composingText;
        }
    }

    /**
     * Returns {@code true} if the current-word snapshot differs from the original word.
     */
    synchronized boolean hasChanged() {
        if (mCurrentWord != null) {
            return !mCurrentWord.equals(mOriginalWord);
        }
        return false;
    }

    /**
     * Returns the cursor position recorded when recorrection began, or {@code -1}.
     *
     * <p>TI-30: the three getters below were unsynchronised while the mutators are synchronised on
     * this object's monitor. Their callers in {@link ComposingTextTracker} hold the TRACKER's
     * monitor - a different lock - so the publication edge the setters establish was not the one
     * the readers acquired. The spell-checker really does drive a second
     * {@link ComposingTextTracker} on an IPC thread in this process, so pick one lock and use it
     * on both sides.
     */
    synchronized int getCursorPosition() {
        return mCursorPos;
    }

    /** Returns the original word text before any edits, or {@code null}. */
    synchronized String getOriginalWord() {
        return mOriginalWord;
    }

    /** Returns the most recent composing-text snapshot during recorrection, or {@code null}. */
    synchronized String getCurrentWord() {
        return mCurrentWord;
    }
}

package dev.bbkb.ime.core.textinput.composing;

/**
 * State of a recorrection session (the user taps a committed word to re-open it). Owned and
 * delegated to by {@link ComposingTextTracker}.
 */
class RecorrectionState {

    private int mCursorPos = -1;
    private String mOriginalWord;
    private String mCurrentWord;

    synchronized void reset() {
        mCursorPos = -1;
        mOriginalWord = null;
        mCurrentWord = null;
    }

    /** @param cursorPos cursor position at which recorrection begins (chars before cursor) */
    synchronized void setInfo(int cursorPos, String originalWord) {
        mCursorPos = cursorPos;
        mOriginalWord = originalWord;
    }

    /** Snapshots the composing text when {@code eventIndex} matches the recorrection cursor position. */
    synchronized void updateSnapshot(String composingText, int eventIndex) {
        if (mCursorPos != -1 && mOriginalWord != null && mCursorPos == eventIndex) {
            mCurrentWord = composingText;
        }
    }

    synchronized boolean hasChanged() {
        if (mCurrentWord != null) {
            return !mCurrentWord.equals(mOriginalWord);
        }
        return false;
    }

    /**
     * Returns the cursor position recorded when recorrection began, or {@code -1}. The getters are
     * synchronized on this object, not the tracker: the spell checker drives a second tracker on an
     * IPC thread, so readers and writers must share this monitor.
     */
    synchronized int getCursorPosition() {
        return mCursorPos;
    }

    synchronized String getOriginalWord() {
        return mOriginalWord;
    }

    synchronized String getCurrentWord() {
        return mCurrentWord;
    }
}

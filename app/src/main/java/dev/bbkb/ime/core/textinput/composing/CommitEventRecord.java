package dev.bbkb.ime.core.textinput.composing;

import android.text.TextUtils;

import dev.bbkb.ime.core.textinput.composing.TouchPointerCoordTracker;
import dev.bbkb.ime.core.suggestion.PrevWordsInfo;
import dev.bbkb.ime.core.locale.LocaleUtils;



public final class CommitEventRecord {

    public static final CommitEventRecord IDLE = new CommitEventRecord(null, "", "", "", null, 0, CommitType.UNSET, false);

    public final String typedWord;

    public final CharSequence committedWord;

    public final String wordSeparator;

    public final PrevWordsInfo prevWordsInfo;

    public final int shiftStateAtCommit;

    /** {@code null} when no source tracker was supplied (avoids ~1.1 KB per commit). */
    public final TouchPointerCoordTracker coordinateTracker;

    public final CommitType commitType;

    public final boolean skipLearning;

    private boolean revertEligible;

    
    public enum CommitType {
        UNSET,
        USER_TYPED_WORD,
        MANUAL_PICK,
        DECIDED_WORD,
        CANCEL_AUTO_CORRECT,
        BATCH_INPUT_WORD
    }

    /**
     * @param touchPointerCoordTracker the touch coordinates of the word, copied (may be null)
     * @param typedWord the typed word
     * @param wordSeparator the separator committed after the word
     * @param shiftStateAtCommit the shift state at commit time
     * @param skipLearning true to skip learning this word
     */
    public CommitEventRecord(TouchPointerCoordTracker touchPointerCoordTracker, String typedWord, CharSequence charSequence, String wordSeparator, PrevWordsInfo prevWordsInfo, int shiftStateAtCommit, CommitType commitType, boolean skipLearning) {
        if (touchPointerCoordTracker != null) {
            this.coordinateTracker = new TouchPointerCoordTracker(48);
            this.coordinateTracker.copyFrom(touchPointerCoordTracker);
        } else {
            this.coordinateTracker = null;
        }
        this.typedWord = typedWord;
        this.committedWord = charSequence;
        this.wordSeparator = wordSeparator;
        this.revertEligible = true;
        this.skipLearning = skipLearning;
        this.prevWordsInfo = prevWordsInfo;
        this.shiftStateAtCommit = shiftStateAtCommit;
        this.commitType = commitType;
    }

    /** No-op on the shared {@link #IDLE} singleton, which InputLogic also calls this on. */
    public void disableRevert() {
        if (this == IDLE) {
            return;
        }
        this.revertEligible = false;
    }

    public boolean isRevertEligible() {
        return (!this.revertEligible || TextUtils.isEmpty(this.committedWord) || isTypedSameAsCommitted() || LocaleUtils.isCurrentSubtypeChinese() || LocaleUtils.isCurrentSubtypeJapanese()) ? false : true;
    }

    private boolean isTypedSameAsCommitted() {
        return TextUtils.equals(this.typedWord, this.committedWord);
    }

    public boolean isBatchInput() {
        return this.commitType == CommitType.BATCH_INPUT_WORD;
    }
}

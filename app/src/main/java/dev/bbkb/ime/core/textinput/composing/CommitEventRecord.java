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

    /**
     * TI-21: allocated only when the constructor is actually given coordinates to copy. A
     * {@code TouchPointerCoordTracker(48)} builds six {@code IntArrayList(48)} backing arrays
     * (~1.1 KB), and a CommitEventRecord is built on every word commit - including the
     * {@link #IDLE} singleton and every record constructed with a {@code null} source, where
     * nothing was ever copied in. {@code null} when no source tracker was supplied.
     */
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

    public CommitEventRecord(TouchPointerCoordTracker c0697m, String str, CharSequence charSequence, String str2, PrevWordsInfo prevWordsInfo, int i, CommitType commitTypeVar, boolean z) {
        if (c0697m != null) {
            this.coordinateTracker = new TouchPointerCoordTracker(48);
            this.coordinateTracker.copyFrom(c0697m);
        } else {
            this.coordinateTracker = null;
        }
        this.typedWord = str;
        this.committedWord = charSequence;
        this.wordSeparator = str2;
        this.revertEligible = true;
        this.skipLearning = z;
        this.prevWordsInfo = prevWordsInfo;
        this.shiftStateAtCommit = i;
        this.commitType = commitTypeVar;
    }

    /**
     * TI-29: {@link #IDLE} is a process-wide shared singleton that {@code InputLogic} assigns to
     * {@code mEventDispatcher} and then calls this on, permanently mutating it. Harmless today
     * only because {@link #isRevertEligible()} already short-circuits on the empty
     * {@code committedWord}; guard it so the singleton stays immutable in fact and not by luck.
     */
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

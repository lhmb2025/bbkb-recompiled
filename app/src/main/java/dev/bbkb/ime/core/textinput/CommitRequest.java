package dev.bbkb.ime.core.textinput;

import dev.bbkb.ime.core.keyevent.InputSource;
import dev.bbkb.ime.core.suggestion.SuggestedWords;
import dev.bbkb.ime.core.textinput.composing.CommitEventRecord;

/**
 * An immutable, fully resolved description of one commit, executed by
 * {@link CommitController#execute}. {@link Reason} is why (it drives the optional stages:
 * {@code CorrectionInfo}, engine-marker tail delete); {@link Protocol} is how the payload reaches
 * the editor. {@link #text} is the final payload, markers already stripped.
 */
final class CommitRequest {

    /** Why this commit is happening. Drives the optional stages; never the editor protocol. */
    enum Reason {
        /** The typed word, verbatim. */
        TYPED,
        /**
         * The engine's candidate won the auto-correct gate (a correction or a kind-7 macro).
         * Adds the {@code CorrectionInfo} round trip and the engine-marker tail delete.
         */
        AUTO_CORRECT,
        PICK,
        /** A CJK / Japanese prediction-strip pick. */
        PREDICTION_PICK,
        /** The top single-word candidate of a swipe trace. */
        GESTURE,
        /** Voice-recognised text, or a palette payload arriving through the text-input path. */
        VOICE,
        /** One code point with no word semantics (a separator, a newline, an accent). */
        CHARACTER,
        /** Whatever the multi-tap highlight was holding when it was flushed. */
        TOUCH_TEXT,
    }

    /**
     * WORD: span-decorated word, optional separator, revert record, learn. PREDICTION_WORD: CJK
     * pick, no span, different learning call, composing region re-established. RAW: one
     * {@code commitText} and nothing else.
     */
    enum Protocol { WORD, PREDICTION_WORD, RAW }

    final Reason reason;
    final Protocol protocol;

    /** The final payload. Never null except for {@link Protocol#PREDICTION_WORD}. */
    final String text;

    /** The picked entry, for {@link Protocol#PREDICTION_WORD} and for learning. May be null. */
    final SuggestedWords.SuggestedWordInfo pickedInfo;

    final CommitEventRecord.CommitType commitType;

    /** Never null; {@code ""} means "no separator". */
    final String trailingSeparator;

    /**
     * Append {@link #trailingSeparator} even when it is not a separator code point. Only the
     * software text-input path sets this.
     */
    final boolean forceSeparator;

    final boolean withSuggestionSpan;

    /** False only where the caller has already learned. */
    final boolean learn;

    /** The composing word before this commit, for the auto-correct {@code CorrectionInfo}. */
    final String typedWordBefore;

    /**
     * The engine's candidate with markers. A trailing {@code %B} means one more character is
     * deleted after the commit. Null except for {@link Reason#AUTO_CORRECT}.
     */
    final String rawCandidate;

    final InputSource source;

    private CommitRequest(Reason reason, Protocol protocol, String text,
            SuggestedWords.SuggestedWordInfo pickedInfo, CommitEventRecord.CommitType commitType,
            String trailingSeparator, boolean forceSeparator, boolean withSuggestionSpan,
            boolean learn, String typedWordBefore, String rawCandidate, InputSource source) {
        this.reason = reason;
        this.protocol = protocol;
        this.text = text;
        this.pickedInfo = pickedInfo;
        this.commitType = commitType;
        this.trailingSeparator = trailingSeparator == null ? "" : trailingSeparator;
        this.forceSeparator = forceSeparator;
        this.withSuggestionSpan = withSuggestionSpan;
        this.learn = learn;
        this.typedWordBefore = typedWordBefore;
        this.rawCandidate = rawCandidate;
        this.source = source;
    }

    /** The composing word, committed verbatim. */
    static CommitRequest typedWord(String word, String separator, InputSource source) {
        return new CommitRequest(Reason.TYPED, Protocol.WORD, word, null,
                CommitEventRecord.CommitType.USER_TYPED_WORD, separator,
                /* forceSeparator = */ false, /* withSuggestionSpan = */ true, /* learn = */ true,
                word, null, source);
    }

    /** The auto-correct winner; {@code text} is marker-stripped, {@code rawCandidate} is not. */
    static CommitRequest autoCorrected(String text, String rawCandidate, String typedWordBefore,
            String separator, boolean forceSeparator, InputSource source) {
        return new CommitRequest(Reason.AUTO_CORRECT, Protocol.WORD, text, null,
                CommitEventRecord.CommitType.DECIDED_WORD, separator, forceSeparator,
                /* withSuggestionSpan = */ true, /* learn = */ true,
                typedWordBefore, rawCandidate, source);
    }

    /** A suggestion-strip tap, Latin-script path. */
    static CommitRequest manualPick(String word, String separator, InputSource source) {
        return new CommitRequest(Reason.PICK, Protocol.WORD, word, null,
                CommitEventRecord.CommitType.MANUAL_PICK, separator,
                /* forceSeparator = */ false, /* withSuggestionSpan = */ true, /* learn = */ true,
                null, null, source);
    }

    /** A CJK / Japanese strip pick, or the CJK auto-correct's first candidate. */
    static CommitRequest predictionPick(SuggestedWords.SuggestedWordInfo info,
            CommitEventRecord.CommitType commitType, String separator, InputSource source) {
        return new CommitRequest(Reason.PREDICTION_PICK, Protocol.PREDICTION_WORD, null, info,
                commitType, separator, /* forceSeparator = */ false,
                /* withSuggestionSpan = */ false, /* learn = */ true, null, null, source);
    }

    /** The chosen single word of a swipe trace. */
    static CommitRequest gesture(String word, String separator, InputSource source) {
        return new CommitRequest(Reason.GESTURE, Protocol.WORD, word, null,
                CommitEventRecord.CommitType.BATCH_INPUT_WORD, separator,
                /* forceSeparator = */ false, /* withSuggestionSpan = */ true, /* learn = */ true,
                null, null, source);
    }

    /** One code point, written as a bare {@code commitText}. */
    static CommitRequest character(String text, InputSource source) {
        return new CommitRequest(Reason.CHARACTER, Protocol.RAW, text, null,
                CommitEventRecord.CommitType.UNSET, "", /* forceSeparator = */ false,
                /* withSuggestionSpan = */ false, /* learn = */ false, null, null, source);
    }

    /**
     * Voice text or a palette payload, as a bare {@code commitText}. Only used when the payload
     * was not already written as a separator ({@link CommitResult#separatorCommitted}).
     */
    static CommitRequest voiceText(String text, InputSource source) {
        return new CommitRequest(Reason.VOICE, Protocol.RAW, text, null,
                CommitEventRecord.CommitType.UNSET, "", /* forceSeparator = */ false,
                /* withSuggestionSpan = */ false, /* learn = */ false, null, null, source);
    }

    /** A flushed multi-tap payload, written as a bare {@code commitText}. */
    static CommitRequest touchText(String text, InputSource source) {
        return new CommitRequest(Reason.TOUCH_TEXT, Protocol.RAW, text, null,
                CommitEventRecord.CommitType.UNSET, "", /* forceSeparator = */ false,
                /* withSuggestionSpan = */ false, /* learn = */ false, null, null, source);
    }

    /**
     * A generic word commit with no auto-correct bookkeeping: {@code rawCandidate} and
     * {@code typedWordBefore} are null, so the correction stages stay inert even for
     * {@code DECIDED_WORD}. Only {@link #autoCorrected} gets the {@code CorrectionInfo}.
     */
    static CommitRequest word(String text, CommitEventRecord.CommitType commitType,
            String separator, boolean forceSeparator, InputSource source) {
        return new CommitRequest(reasonFor(commitType), Protocol.WORD, text, null, commitType,
                separator, forceSeparator, /* withSuggestionSpan = */ true, /* learn = */ true,
                null, null, source);
    }

    private static Reason reasonFor(CommitEventRecord.CommitType commitType) {
        if (commitType == null) {
            return Reason.TYPED;
        }
        switch (commitType) {
            case MANUAL_PICK:
                return Reason.PICK;
            case BATCH_INPUT_WORD:
                return Reason.GESTURE;
            case DECIDED_WORD:
                return Reason.AUTO_CORRECT;
            case USER_TYPED_WORD:
            case CANCEL_AUTO_CORRECT:
            case UNSET:
            default:
                return Reason.TYPED;
        }
    }

    /** A copy with a different payload, used after marker stripping. */
    CommitRequest withText(String newText) {
        return new CommitRequest(reason, protocol, newText, pickedInfo, commitType,
                trailingSeparator, forceSeparator, withSuggestionSpan, learn, typedWordBefore,
                rawCandidate, source);
    }

    @Override
    public String toString() {
        return "CommitRequest{" + reason + "/" + protocol + " text='" + text
                + "' sep='" + trailingSeparator + "'" + (forceSeparator ? " forced" : "")
                + (withSuggestionSpan ? " span" : "") + (learn ? " learn" : "")
                + " type=" + commitType + " src=" + source + "}";
    }
}

package dev.bbkb.ime.core.textinput;

import dev.bbkb.ime.core.keyevent.InputSource;
import dev.bbkb.ime.core.suggestion.SuggestedWords;
import dev.bbkb.ime.core.textinput.composing.CommitEventRecord;

/**
 * An immutable, fully-resolved description of ONE commit: what text goes into the editor, why,
 * with what trailing separator, whether it carries a {@code SuggestionSpan}, and whether the
 * learning subsystem records it.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Committing used to be reachable through ten separate methods on {@link CommitController},
 * each of which decided <em>what</em> to commit and then wrote to the editor itself. The
 * decisions were duplicated (the auto-correct gate is applied at two independent sites), the
 * differences between the paths were implicit in which method you happened to call, and a
 * caller could not tell what the pipeline had done — {@code commitVoiceInput} double-committed
 * an emoji for exactly that reason (audit SS-1).
 *
 * <p>Now each of those methods only <em>resolves</em> a request and hands it to the single
 * {@link CommitController#execute} path. The differences that used to be spread across ten
 * method bodies are the fields below, so they can be read, compared and tested in one place.
 *
 * <h2>Reason vs. Protocol</h2>
 *
 * <p>{@link Reason} is <em>why</em> the commit is happening — it is the caller's intent, and it
 * is what drives the optional stages (issuing a {@link android.view.inputmethod.CorrectionInfo},
 * the engine-marker tail delete). {@link Protocol} is <em>how</em> the payload reaches the
 * editor, and there are exactly three, because the editor sees three genuinely different call
 * sequences:
 *
 * <ul>
 *   <li>{@link Protocol#WORD} — span-decorated word, optional separator, revert record, learn.
 *       The ordinary Latin-script commit.</li>
 *   <li>{@link Protocol#PREDICTION_WORD} — the CJK / Japanese strip pick: raw text with no span,
 *       a different learning call, and the composing region re-established afterwards.</li>
 *   <li>{@link Protocol#RAW} — one {@code commitText} and nothing else: a lone code point, or
 *       whatever the multi-tap highlight was holding.</li>
 * </ul>
 *
 * <p>Several reasons map to the same protocol ({@code TYPED}, {@code AUTO_CORRECT},
 * {@code PICK}, {@code GESTURE} are all {@code WORD}); that is the point — it is why they can
 * share one execution path.
 *
 * <h2>Invariants</h2>
 *
 * <p>{@link #text} is the FINAL payload: engine markers already stripped, correction already
 * chosen. Resolving is the adapter's job, not the pipeline's. {@link #trailingSeparator} is
 * never null (use {@code ""}); whether it is actually written is decided by the append gate in
 * {@link CommitController#execute}, which is why {@link CommitResult#separatorCommitted} exists.
 */
final class CommitRequest {

    /** Why this commit is happening. Drives the optional stages; never the editor protocol. */
    enum Reason {
        /** The word the user typed, verbatim — no correction was applied. */
        TYPED,
        /**
         * The engine's candidate won the auto-correct gate (an ordinary spelling correction, or
         * a kind-7 macro expansion). Adds the {@code CorrectionInfo} round trip and the
         * engine-marker tail delete.
         */
        AUTO_CORRECT,
        /** The user tapped a word on the suggestion strip. */
        PICK,
        /** A CJK / Japanese prediction-strip pick — see {@link Protocol#PREDICTION_WORD}. */
        PREDICTION_PICK,
        /** The top single-word candidate of a swipe (batch) trace. */
        GESTURE,
        /** Voice-recognised text, or a palette payload arriving through the text-input path. */
        VOICE,
        /** One code point with no word semantics (a separator, a newline, an accent). */
        CHARACTER,
        /** Whatever the multi-tap highlight was holding when it was flushed. */
        TOUCH_TEXT,
    }

    /** How the payload reaches the editor. Exactly three call sequences exist. */
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
     * Append {@link #trailingSeparator} even when it is not a word-separator code point. Only
     * the software text-input path sets this (it is how a letter payload gets appended at all).
     */
    final boolean forceSeparator;

    /** Attach a {@code SuggestionSpan} to the committed text. False for the CJK pick path. */
    final boolean withSuggestionSpan;

    /** Notify the learning subsystem. False only where the caller has already learned. */
    final boolean learn;

    /**
     * The tracker's composing word as it stood BEFORE this commit. Needed by
     * {@link Reason#AUTO_CORRECT} to decide whether the text actually changed and to build the
     * {@code CorrectionInfo}. Null when no word was composing.
     */
    final String typedWordBefore;

    /**
     * The candidate as the engine handed it over, markers and all. {@link Reason#AUTO_CORRECT}
     * needs it to spot a trailing {@code %B}, whose presence means one more character has to be
     * deleted after the commit. Null for every other reason.
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

    /**
     * The auto-correct gate's winner. {@code text} is already marker-stripped;
     * {@code rawCandidate} is not, and {@code typedWordBefore} is what the user actually typed —
     * both are needed to decide whether a {@code CorrectionInfo} is owed.
     */
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
     * A voice-recognition result, or a palette payload arriving through the text-input path,
     * written as a bare {@code commitText}.
     *
     * <p>Only reached when the pipeline did NOT already write the payload as some other commit's
     * trailing separator — see {@link CommitResult#separatorCommitted} and audit SS-1.
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
     * The generic word commit: explicit text and commit type, no auto-correct bookkeeping.
     *
     * <p>{@link #reason} is derived from {@code commitType} so the request still says why it
     * exists, but {@link #rawCandidate} and {@link #typedWordBefore} are both null — which is what
     * keeps {@code CommitController.executeWord}'s correction stages inert here. A
     * {@code DECIDED_WORD} built this way therefore behaves exactly as the old
     * {@code commitWordExtended} did: it writes the word and the separator and nothing else. Only
     * {@link #autoCorrected} asks for the {@code CorrectionInfo} round trip.
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

    /** A copy of this request with a different payload — used after marker stripping. */
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

package dev.bbkb.ime.core.textinput.controller;

import static dev.bbkb.ime.core.shared.StringHelper.*;

import dev.bbkb.ime.core.keyevent.InputEvent;
import dev.bbkb.ime.core.settings.util.SettingsValues;

import java.util.ArrayList;



/**
 * Analyzes keystroke patterns to determine whether automatic space insertion,
 * backspace deletion, or "dumb mode" suppression should accompany a key event.
 *
 * <p>This is the smart-punctuation engine used by the dynamic learning subsystem.
 * For each incoming key event it inspects the composing context (the text immediately
 * before the cursor) and the new code point, then decides whether to synthesize
 * additional {@link InputEvent}s — specifically:
 * <ul>
 *   <li><b>Insert space</b> — when a character that is usually preceded by a space
 *       (e.g. an opening bracket) is typed without one.</li>
 *   <li><b>Backspace</b> — when a sentence separator (e.g. '.') is typed after
 *       trailing whitespace that should be removed first.</li>
 *   <li><b>Dumb mode</b> — suppresses smart-punctuation for the remainder of the
 *       composing session when a URL or email pattern is detected, or when a
 *       symbol follows a gesture sequence.</li>
 * </ul>
 *
 * <p>The result is returned as a {@link Result} value object containing the
 * (possibly prepended) {@link InputEvent} chain and flags indicating whether a
 * backspace was injected, whether extra events were prepended, and whether a
 * trailing auto-space should follow.
 */
public final class SmartPunctuationAnalyzer {

    private static final String TAG = "SmartPunctuationAnalyzer";

    /** {@code true} when dumb mode is active — smart-punctuation is suppressed. */
    private boolean dumbMode = false;

    /**
     * Internal decision codes produced by {@link #decideActions} for each step of the
     * synthetic event sequence to prepend before the typed character.
     */
    private enum Action {
        /** Delete the trailing whitespace before the cursor. */
        BACKSPACE,
        /** Insert an auto-space before the typed character. */
        INS_SPACE,
        /** Insert the typed character itself (focus event). */
        INS_FOCUS,
        /** Disable smart-punctuation for the rest of the session. */
        DUMB_MODE
    }

    /**
     * Immutable result from {@link #analyze}.
     *
     * <p>Contains the (possibly prepended) input event chain and three boolean flags:
     * <ul>
     *   <li>{@link #backspaceInjected} — {@code true} if a backspace was injected</li>
     *   <li>{@link #eventsPrepended} — {@code true} if any synthetic events were prepended</li>
     *   <li>{@link #appendAutoSpace} — {@code true} if a trailing auto-space should follow</li>
     * </ul>
     */
    public static final class Result {

        /** The input event (or head of a synthetic event chain) to process. */
        public final InputEvent event;

        /** {@code true} if a backspace event was injected before the typed character. */
        public final boolean backspaceInjected;

        /** {@code true} if any synthetic events were prepended to the event chain. */
        public final boolean eventsPrepended;

        /** {@code true} if a trailing auto-space should be appended after commit. */
        public final boolean appendAutoSpace;

        private Result(InputEvent event, boolean z, boolean z2, boolean z3) {
            this.event = event;
            this.backspaceInjected = z;
            this.eventsPrepended = z2;
            this.appendAutoSpace = z3;
        }
    }

    /**
     * Returns {@code true} if dumb mode is currently active. When dumb mode is on,
     * smart-punctuation (auto-space and backspace injection) is suppressed.
     */
    public boolean isDumbMode() {
        return this.dumbMode;
    }

    /**
     * Clears dumb mode, re-enabling smart-punctuation analysis for subsequent events.
     * Called at the start of a new composing session.
     */
    public void clearDumbMode() {
        this.dumbMode = false;
    }

    /**
     * Analyzes the given key event in context and returns a {@link Result} describing
     * any synthetic events to inject and flags that alter post-commit behavior.
     *
     * <p>The method examines the composing context ({@code str}) and the new code point
     * ({@code event.mCodePoint}) and applies the following rules in order:
     * <ol>
     *   <li>If the event is not a key-press, returns the event unmodified.</li>
     *   <li>If the context already ends in a space and auto-space is not active,
     *       returns the event unmodified.</li>
     *   <li>If {@link #decideActions} identifies {@code DUMB_MODE}, sets dumb mode and
     *       returns the event unmodified.</li>
     *   <li>Otherwise, prepends {@code BACKSPACE} and/or {@code INS_SPACE} synthetic
     *       events as directed, then appends any pending linked events from the
     *       original chain.</li>
     * </ol>
     *
     * @param str    text immediately before the cursor (composing context)
     * @param event the incoming key event to analyze
     * @param z      {@code true} if auto-space is currently active
     * @param settingsValues current settings values (spacing, punctuation rules)
     * @param z2     {@code true} if the previous commit was a gesture
     * @return a {@link Result} containing the event chain and behavior flags
     */
    public Result analyze(String str, InputEvent event, boolean z, SettingsValues settingsValues, boolean z2) {
        boolean z3;
        boolean z4;
        InputEvent chainedEvent;
        boolean z5;
        boolean z6;
        boolean z7 = settingsValues.spacingAndPunctuation.currentLanguageHasSpaces;
        if (!event.isKeyPressEvent()) {
            return new Result(event, false, false, false);
        }
        int length = str.length();
        if (length > 0 && str.charAt(length - 1) == ' ' && !z) {
            return new Result(event, false, false, false);
        }
        Action[] actions = decideActions(str, event.mCodePoint, settingsValues, event);
        int length2 = actions.length - 1;
        // TI-33: was Arrays.asList(actions).contains(...) - a second per-keystroke allocation to
        // scan at most four elements.
        if (containsAction(actions, Action.DUMB_MODE)) {
            this.dumbMode = true;
            return new Result(event, false, false, false);
        }
        boolean z8 = false;
        if (length2 > 0 || (length2 == 0 && actions[0] != Action.INS_FOCUS)) {
            if (actions[length2] != Action.INS_SPACE) {
                z3 = false;
            } else if (!z || z2) {
                length2--;
                z3 = false;
            } else {
                z3 = true;
            }
            InputEvent c0914aM5909a = event;
            int i = length2;
            // The loop walks the action list BACKWARDS, so the first event it creates is the
            // chain's TAIL and must terminate the chain (next == null); every later-created
            // event is prepended in front of what has been built so far. `chainStarted` is
            // exactly "an event has already been created", i.e. "the tail slot is taken".
            //
            // TI-2: this used to be spelled `i == length2` - "am I the LAST action?" - which is
            // only the same question while every action actually emits an event. The INS_SPACE
            // arm does not: in a language without spaces (`!z7`) it breaks without creating
            // anything, so the tail slot was still open while `i == length2` had already gone
            // false. The following INS_FOCUS therefore kept the ORIGINAL event as its tail and
            // the typed character was emitted twice - typing '.' in a CJK/Thai locale with
            // auto-space active produced "..". Tracking the flag instead of the index is
            // identical for every has-spaces locale and correct for the no-spaces one.
            boolean chainStarted = false;
            boolean z10 = false;
            while (i >= 0) {
                switch (actions[i]) {
                    case BACKSPACE:
                        z6 = z3;
                        long jM5921b = event.getTimestamp();
                        if (!chainStarted) {
                            c0914aM5909a = null;
                        }
                        c0914aM5909a = InputEvent.createHardwareKeyEvent(-1, -5, jM5921b, c0914aM5909a);
                        chainStarted = true;
                        z10 = true;
                        break;
                    case INS_SPACE:
                        z6 = z3;
                        if (!z7) {
                            break;
                        } else {
                            long jM5921b2 = event.getTimestamp();
                            if (!chainStarted) {
                                c0914aM5909a = null;
                            }
                            c0914aM5909a = InputEvent.createHardwareKeyEvent(32, 0, jM5921b2, c0914aM5909a);
                            chainStarted = true;
                            break;
                        }
                    case INS_FOCUS:
                        int i2 = event.mCodePoint;
                        z6 = z3;
                        long jM5921b3 = event.getTimestamp();
                        if (!chainStarted) {
                            c0914aM5909a = null;
                        }
                        c0914aM5909a = InputEvent.createHardwareKeyEvent(i2, 0, jM5921b3, c0914aM5909a);
                        chainStarted = true;
                        break;
                    default:
                        z6 = z3;
                        break;
                }
                i--;
                z3 = z6;
            }
            boolean z11 = z3;
            if (event.mNextEvent != null) {
                z4 = z11;
                chainedEvent = InputEvent.appendEventToChain(c0914aM5909a, event.mNextEvent);
                z8 = chainStarted;
                z5 = z10;
            } else {
                z4 = z11;
                chainedEvent = c0914aM5909a;
                z8 = chainStarted;
                z5 = z10;
            }
        } else {
            chainedEvent = event;
            z5 = false;
            z4 = false;
        }
        return new Result(chainedEvent, z5, z8, z4);
    }

    /**
     * Determines the sequence of synthetic actions ({@link Action}) to apply before the
     * typed character, based on the composing context and the new code point.
     *
     * <p>Rules applied in order:
     * <ul>
     *   <li>If the event has a linked next event and dumb mode is off, and the next
     *       code point is a symbol: emit {@code INS_FOCUS} then {@code DUMB_MODE}.</li>
     *   <li>If the new code point is usually preceded by a space and the context does
     *       not already end in whitespace: emit {@code INS_SPACE}.</li>
     *   <li>If the new code point is a sentence separator and the context ends in
     *       non-newline whitespace: emit {@code BACKSPACE}.</li>
     *   <li>Always emit {@code INS_FOCUS} (the character itself).</li>
     *   <li>If the combined context looks like a URL: emit {@code DUMB_MODE}.</li>
     *   <li>If the new code point is a sentence separator (and no URL/email match):
     *       emit a trailing {@code INS_SPACE}.</li>
     * </ul>
     *
     * @param str    composing context (text before cursor)
     * @param i      code point of the key being typed
     * @param settingsValues current settings values
     * @param event the incoming input event
     * @return array of action codes to execute in order
     */
    private static boolean containsAction(Action[] actions, Action action) {
        for (int k = 0; k < actions.length; k++) {
            if (actions[k] == action) {
                return true;
            }
        }
        return false;
    }

    private Action[] decideActions(String str, int i, SettingsValues settingsValues, InputEvent event) {
        // TI-33: was a raw (non-generic) ArrayList. The action set is bounded by construction -
        // at most two before INS_FOCUS and one after - so a fixed array with a count avoids the
        // per-keystroke list and its toArray copy.
        ArrayList<Action> arrayList = new ArrayList<>(4);
        boolean zM5184f = settingsValues.isPrecededBySpaceFrench(i);
        boolean zM5186g = settingsValues.isUsuallyFollowedBySpace(i);
        
        // Fixed control flow based on Smali implementation
        if (event.mNextEvent != null && !this.dumbMode) {
            if (isSymbolType(event.mNextEvent.mCodePoint)) {
                arrayList.add(Action.INS_FOCUS);
                arrayList.add(Action.DUMB_MODE);
            }
        } else {
            // Handle space insertion logic.
            //
            // Both guards below ask the same question - "is there a character before the cursor,
            // and is it whitespace?" - and both used to ask it with `str.length() - 1`, which is
            // the INDEX of that character, not the count of characters. Comparing an index
            // against 0 with `> 0` or `== 0` therefore mis-stated the boundary in three ways:
            //
            //  TI-9b: the French clause read `len - 1 == 0 || (len - 1 > 0 && !isWhitespace)`.
            //         The first half means "the context is exactly ONE character" and, unlike the
            //         second half, never looks at that character - so " " + '!' inserted a second
            //         space and produced "  !". The second half means "at least TWO characters",
            //         so a one-character context that is NOT whitespace ("A" + '!') got no space
            //         at all. Both halves collapse into the single test the rule actually wants:
            //         there is a preceding character and it is not whitespace.
            //         Deliberately NOT `len == 0 || ...`: an empty context must stay space-free,
            //         because the French rule separates '!' '?' '«' from the word in front of
            //         them and at the start of a field there is no such word - a leading space in
            //         an empty field would be a new bug, not a fix.
            //  TI-9a: the backspace clause read `len - 1 > 0`, i.e. "at least TWO characters",
            //         where all it needs is a character to inspect (`len > 0`). A context that is
            //         exactly one space - the cursor sitting after a lone auto-space at the very
            //         start of the field - never got that space eaten, so the field ended up
            //         " ." where "." was intended. Length-1 contexts now behave exactly like
            //         longer ones.
            if (zM5184f && str.length() > 0 && !Character.isWhitespace(str.charAt(str.length() - 1))) {
                arrayList.add(Action.INS_SPACE);
            } else if (zM5186g && str.length() > 0 && Character.isWhitespace(str.charAt(str.length() - 1)) && str.charAt(str.length() - 1) != '\n') {
                arrayList.add(Action.BACKSPACE);
            }
            
            // Always add focus insertion
            arrayList.add(Action.INS_FOCUS);
            
            // Handle string validation and mode selection
            // TI-3: `str + i` with an int code point performs integer-to-decimal conversion -
            // typing '.' after "example" produced "example46", so looksLikeEmail/looksLikeURL
            // below never saw the character the user actually typed and DUMB_MODE was never armed
            // for URLs and email addresses.
            String combinedStr = str + new String(Character.toChars(i));
            if (!looksLikeEmail((CharSequence) combinedStr)) {
                if (looksLikeURL((CharSequence) combinedStr)) {
                    arrayList.add(Action.DUMB_MODE);
                } else if (zM5186g) {
                    arrayList.add(Action.INS_SPACE);
                }
            }
        }
        return arrayList.toArray(new Action[0]);
    }
}

package dev.bbkb.ime.core.textinput.controller;

import static dev.bbkb.ime.core.shared.StringHelper.*;

import dev.bbkb.ime.core.keyevent.InputEvent;
import dev.bbkb.ime.core.settings.util.SettingsValues;

import java.util.ArrayList;



/**
 * Smart punctuation: decides whether a typed character should be preceded by an injected
 * space (French-style "!" "?" "«") or a backspace (eating whitespace before a separator),
 * and arms "dumb mode" (no smart punctuation for the rest of the session) when the context
 * looks like a URL or email, or a symbol follows a gesture.
 */
public final class SmartPunctuationAnalyzer {

    private static final String TAG = "SmartPunctuationAnalyzer";

    private boolean dumbMode = false;

    /** Synthetic steps around the typed character (INS_FOCUS is the character itself). */
    private enum Action {
        BACKSPACE,
        INS_SPACE,
        INS_FOCUS,
        DUMB_MODE
    }

    /** Result of {@link #analyze}: the event chain to process plus post-commit flags. */
    public static final class Result {
        /** Head of the (possibly prepended) event chain. */
        public final InputEvent event;

        public final boolean backspaceInjected;

        public final boolean eventsPrepended;

        /** A trailing auto-space should be appended after commit. */
        public final boolean appendAutoSpace;

        /**
         * @param z a backspace was injected
         * @param z2 events were prepended to the chain
         * @param z3 a trailing auto-space should be appended
         */
        private Result(InputEvent event, boolean z, boolean z2, boolean z3) {
            this.event = event;
            this.backspaceInjected = z;
            this.eventsPrepended = z2;
            this.appendAutoSpace = z3;
        }
    }

    public boolean isDumbMode() {
        return this.dumbMode;
    }

    /** Called at the start of a new composing session. */
    public void clearDumbMode() {
        this.dumbMode = false;
    }

    /**
     * @param str text immediately before the cursor
     * @param z   auto-space is currently active
     * @param z2  the previous commit was a gesture
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
            // Walks backwards: the first event created is the chain's tail (next == null). Track
            // "tail taken" with a flag, not `i == length2`: INS_SPACE emits nothing without spaces.
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

    private static boolean containsAction(Action[] actions, Action action) {
        for (int k = 0; k < actions.length; k++) {
            if (actions[k] == action) {
                return true;
            }
        }
        return false;
    }

    /**
     * Ordered actions for typing code point {@code i} after context {@code str}.
     * @param str the text before the cursor
     * @param i the code point being typed
     */
    private Action[] decideActions(String str, int i, SettingsValues settingsValues, InputEvent event) {
        ArrayList<Action> arrayList = new ArrayList<>(4);
        boolean zM5184f = settingsValues.isPrecededBySpaceFrench(i);
        boolean zM5186g = settingsValues.isUsuallyFollowedBySpace(i);

        if (event.mNextEvent != null && !this.dumbMode) {
            if (isSymbolType(event.mNextEvent.mCodePoint)) {
                arrayList.add(Action.INS_FOCUS);
                arrayList.add(Action.DUMB_MODE);
            }
        } else {
            // Both need a preceding character (len > 0). An empty context gets no French space:
            // there is no word at the start of a field to separate the mark from.
            if (zM5184f && str.length() > 0 && !Character.isWhitespace(str.charAt(str.length() - 1))) {
                arrayList.add(Action.INS_SPACE);
            } else if (zM5186g && str.length() > 0 && Character.isWhitespace(str.charAt(str.length() - 1)) && str.charAt(str.length() - 1) != '\n') {
                arrayList.add(Action.BACKSPACE);
            }

            arrayList.add(Action.INS_FOCUS);

            // Not `str + i`: that appends the int's decimal digits, not the character.
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

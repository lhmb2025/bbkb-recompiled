package dev.bbkb.ime.core.textinput.controller;

import dev.bbkb.ime.core.keyevent.InputEvent;
import dev.bbkb.ime.core.keyevent.InputEventContext;
import dev.bbkb.ime.core.keyevent.InputSource;
import dev.bbkb.ime.core.settings.util.SettingsValues;
import dev.bbkb.ime.core.textinput.connection.RichInputConnection;

import java.util.Locale;

/**
 * Punctuation special cases: double-space period, space/punctuation swapping, space stripping
 * after suggestion picks, and auto-space after completions. Owns the last-space timestamp.
 */
public class PunctuationController {
    /** Implemented by {@code InputLogic} to avoid a circular dependency. */
    public interface CommitCharacterCallback {
        void commitCharacter(SettingsValues settings, int codePoint, InputSource source);
    }

    private final RichInputConnection mRichInputConnection;
    private final CommitCharacterCallback mCommitCharacter;

    private long mLastTimestamp;

    public PunctuationController(RichInputConnection richInputConnection, CommitCharacterCallback commitCharacter) {
        this.mRichInputConnection = richInputConnection;
        this.mCommitCharacter = commitCharacter;
    }

    public void recordSpaceTimestamp(InputEventContext context) {
        this.mLastTimestamp = context.timestamp;
    }

    public void resetSpaceTimestamp() {
        this.mLastTimestamp = 0L;
    }

    /** True while still within the double-space period window since the last space. */
    public boolean isDoubleSpacePeriodTimeout(InputEventContext context) {
        return context.timestamp - this.mLastTimestamp < context.settingsValues.doubleSpacePeriodTimeoutMs;
    }

    /**
     * @param codePoint the code point before the space
     */
    private static boolean isDoubleSpacePeriodCandidate(int codePoint, InputEventContext context) {
        Locale locale = context.settingsValues.locale;
        if ((locale != null ? locale.getLanguage() : "").equals("th")) {
            return false;
        }
        int type = Character.getType(codePoint);
        return Character.isLetterOrDigit(codePoint) || codePoint == 39 || codePoint == 34 || codePoint == 41 || codePoint == 93 || codePoint == 125 || codePoint == 62 || codePoint == 43 || codePoint == 37 || type == 28 || type == 6 || type == 8;
    }

    /** Replaces the trailing space with separator + space on a quick second space. */
    public boolean handleDoubleSpacePeriod(InputEvent event, InputEventContext context, boolean justCommitted) {
        CharSequence textBefore;
        int length;
        boolean useDoubleSpace = context.settingsValues.useDoubleSpacePeriod;
        boolean isSpace = 32 == event.mCodePoint;
        boolean isTimeout = isDoubleSpacePeriodTimeout(context);
        boolean supports = context.settingsValues.spacingAndPunctuation.supportsDoubleSpacePeriod;
        if (!useDoubleSpace || !isSpace || !isTimeout || !supports || (textBefore = this.mRichInputConnection.getTextBeforeCursor(3, 0)) == null || (length = textBefore.length()) < 2 || textBefore.charAt(length - 1) != ' ') {
            return false;
        }
        int deleteCount = (justCommitted && textBefore.charAt(length + (-2)) == ' ') ? 2 : 1;
        // Safe only because charAt(length - 1) is a space: with length == 2 the surrogate
        // branch (index length - 3 == -1) cannot be taken. Revisit if that precondition changes.
        if (!isDoubleSpacePeriodCandidate((Character.isSurrogatePair(textBefore.charAt(0), textBefore.charAt(1)) || (deleteCount == 2 && textBefore.length() == 3)) ? Character.codePointAt(textBefore, length - 3) : textBefore.charAt(length - 2), context)) {
            return false;
        }
        resetSpaceTimestamp();
        this.mRichInputConnection.deleteSurroundingText(deleteCount, 0);
        this.mRichInputConnection.commitText(context.settingsValues.spacingAndPunctuation.getSentenceSeparatorAndSpace(), 1);
        context.setUiUpdateMode(1);
        context.setShouldUpdateSuggestions();
        return true;
    }

    /** Turns "word |" + punctuation into "word. |". */
    public boolean trySwapPunctuation(InputEvent event, InputEventContext context) {
        if (32 != this.mRichInputConnection.getCodePointBeforeCursor()) {
            return false;
        }
        this.mRichInputConnection.deleteSurroundingText(1, 0);
        this.mRichInputConnection.commitText(((Object) event.getOutputText()) + " ", 1);
        context.setUiUpdateMode(1);
        return true;
    }

    /**
     * Whether the auto-space before this character should be stripped (separator after a
     * suggestion pick). Some cases delete the trailing space directly and return false.
     */
    public boolean shouldStripSpace(InputEvent event, InputEventContext context) {
        int i = event.mCodePoint;
        boolean isSuggestionPicked = event.isSuggestionPicked();
        if (10 == i && 2 == context.commitType) {
            this.mRichInputConnection.deleteTrailingSpace();
            return false;
        }
        if ((3 != context.commitType && 2 != context.commitType) || !isSuggestionPicked || context.settingsValues.isUsuallyPrecededBySpace(i)) {
            return false;
        }
        if (context.settingsValues.isUsuallyFollowedBySpace(i)) {
            return true;
        }
        this.mRichInputConnection.deleteTrailingSpace();
        return false;
    }

    /** Only for app-specified completions in a spaced language, and not after a URL. */
    public void appendAutoSpace(SettingsValues settings, InputSource source) {
        if (settings.isApplicationSpecifiedCompletionsOn() && settings.spacingAndPunctuation.currentLanguageHasSpaces && !this.mRichInputConnection.looksLikeURL()) {
            this.mCommitCharacter.commitCharacter(settings, 32, source);
        }
    }
}

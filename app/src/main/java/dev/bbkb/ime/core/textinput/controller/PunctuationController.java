package dev.bbkb.ime.core.textinput.controller;

import dev.bbkb.ime.core.keyevent.InputEvent;
import dev.bbkb.ime.core.keyevent.InputEventContext;
import dev.bbkb.ime.core.keyevent.InputSource;
import dev.bbkb.ime.core.settings.util.SettingsValues;
import dev.bbkb.ime.core.textinput.connection.RichInputConnection;

import java.util.Locale;

/**
 * Handles punctuation special-case behaviors: double-space-to-period substitution,
 * space-before-punctuation swapping, space stripping for suggestion-strip picks,
 * and auto-space appending after completions.
 *
 * <p>Extracted from {@code InputLogic} in Phase 4.1 of the text editing reorganization.
 * Owns the last-space timestamp state used for double-space-period detection.</p>
 */
public class PunctuationController {

    /**
     * Callback for committing a single character to the editor.
     * Provided by {@code InputLogic} to avoid circular dependencies.
     */
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

    /**
     * Records the timestamp of the current event as the last-space timestamp, enabling
     * double-space-to-period detection on the following space press.
     *
     * @param context the current input event context whose timestamp is recorded
     */
    public void recordSpaceTimestamp(InputEventContext context) {
        this.mLastTimestamp = context.timestamp;
    }

    /**
     * Clears the last-space timestamp, disabling double-space-to-period detection until
     * the next space is typed.
     */
    public void resetSpaceTimestamp() {
        this.mLastTimestamp = 0L;
    }

    /**
     * Returns {@code true} if the time elapsed since the last space press is within the
     * double-space-to-period timeout window.
     *
     * @param context the current input event context (provides timestamp and settings)
     * @return {@code true} if a double-space-to-period substitution is still eligible
     */
    public boolean isDoubleSpacePeriodTimeout(InputEventContext context) {
        return context.timestamp - this.mLastTimestamp < context.settingsValues.doubleSpacePeriodTimeoutMs;
    }

    private static boolean isDoubleSpacePeriodCandidate(int i, InputEventContext context) {
        Locale locale = context.settingsValues.locale;
        if ((locale != null ? locale.getLanguage() : "").equals("th")) {
            return false;
        }
        int type = Character.getType(i);
        return Character.isLetterOrDigit(i) || i == 39 || i == 34 || i == 41 || i == 93 || i == 125 || i == 62 || i == 43 || i == 37 || type == 28 || type == 6 || type == 8;
    }

    /**
     * Attempts to replace the last two characters (space + word-char) with a sentence
     * separator + space when the user types two spaces in quick succession.
     *
     * @param event         the current input event (expected to be a space)
     * @param context       the current input event context
     * @param justCommitted {@code true} if a word was just committed immediately before this event
     * @return {@code true} if the substitution was performed
     */
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
        // Fragile but currently safe: when length == 2, charAt(1) is the trailing space
        // (checked above) and a space can never be a low surrogate, so the surrogate-pair
        // branch (which would index length - 3 == -1) cannot be taken. Keep that guard in
        // mind if the "ends with space" precondition above ever changes.
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

    /**
     * Swaps a trailing space with the given punctuation character when the space comes
     * immediately before the punctuation in the text buffer.
     *
     * @param event   the punctuation input event to swap into place
     * @param context the current input event context
     * @return {@code true} if the swap was performed
     */
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
     * Determines whether the trailing auto-space should be stripped before the given
     * character is inserted (e.g., for sentence-separator punctuation after a suggestion pick).
     *
     * @param event   the input event being processed
     * @param context the current input event context
     * @return {@code true} if the preceding space should be stripped before inserting this character
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

    /**
     * Appends a space character to the editor when the current field uses application-specified
     * completions, the language uses spaces, and the text does not end in a URL.
     *
     * @param settings current settings values
     * @param source   the input source for the space event
     */
    public void appendAutoSpace(SettingsValues settings, InputSource source) {
        if (settings.isApplicationSpecifiedCompletionsOn() && settings.spacingAndPunctuation.currentLanguageHasSpaces && !this.mRichInputConnection.looksLikeURL()) {
            this.mCommitCharacter.commitCharacter(settings, 32, source);
        }
    }
}

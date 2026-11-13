package dev.bbkb.ime.core.textinput;

import static dev.bbkb.ime.core.shared.StringHelper.*;

import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.SuggestionSpan;
import android.util.Log;

import dev.bbkb.ime.BuildConfig;

import dev.bbkb.ime.core.engine.Dictionary;
import dev.bbkb.ime.core.engine.DictionaryLoader;
import dev.bbkb.ime.core.engine.EngineThreads;
import dev.bbkb.ime.core.keyevent.InputEventContext;
import dev.bbkb.ime.core.settings.util.SettingsValues;
import dev.bbkb.ime.core.suggestion.SuggestedWords;
import dev.bbkb.ime.core.suggestion.SuggestionEngine;
import dev.bbkb.ime.core.suggestion.SuggestionUpdater;
import dev.bbkb.ime.core.textinput.composing.CommitEventRecord;
import dev.bbkb.ime.core.locale.LocaleUtils;
import com.blackberry.nuanceshim.NuanceSDK;

import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Pattern;
import dev.bbkb.ime.core.textinput.connection.CursorWordRange;

/** Re-opening a committed word for editing, and reverting an auto-correction to the typed word. */
class RecorrectionController {
    /** Bounds the post-revert validity query; the engine thread only stalls during a language load. */
    private static final long RECORRECTION_VALIDITY_TIMEOUT_MS = 100L;

    private static final Pattern PUNCTUATION = Pattern.compile("\\p{Punct}");

    private final InputLogic mInputLogic;

    RecorrectionController(InputLogic inputLogic) {
        this.mInputLogic = inputLogic;
    }

    /**
     * Re-opens the word at the cursor as composing text. No-op mid-word, in CJK/Japanese, or
     * when the editor does not support suggestions.
     * @param includeWordAtCursor include the tapped word itself as the first suggestion
     * @param settingsValues current settings values
     * @param currentKeyboardScriptId symbol page order / shift state used for suggestion context
     */
    public void performRecorrection(SettingsValues settingsValues, final boolean includeWordAtCursor, int currentKeyboardScriptId) {
        CursorWordRange c0875apM5829b = null;
        int iM5646a = -1;

        boolean z2 = mInputLogic.mShouldAppendSpace;
        mInputLogic.mShouldAppendSpace = false;

        if (mInputLogic.mIme.isCursorModeEnabled()) {
            return;
        }

        if (settingsValues.isSupportedAndroidApp() || !settingsValues.shouldShowLxxButton || mInputLogic.mSuggestionRequestQueue.isSuggestionsEnabled() || mInputLogic.mRichInputConnection.hasSelection() || mInputLogic.mRichInputConnection.getCursorStart() < 0) {
            mInputLogic.mSuggestionStripListener.setNeutralSuggestionStrip();
            return;
        }

        int iM5853q = mInputLogic.mRichInputConnection.getCursorStart();

        if (z2 || !mInputLogic.mRichInputConnection.hasWordBeforeCursor(settingsValues.spacingAndPunctuation) || ((LocaleUtils.isCurrentSubtypeChinese() && !LocaleUtils.isCurrentSubtypePinyin()) || LocaleUtils.isCurrentSubtypeJapanese())) {
            mInputLogic.mComposingTracker.setShiftState(mInputLogic.getShiftState(settingsValues, mInputLogic.mIme.getSymbolPageProvider().getSymbolPageOrder()));
            mInputLogic.mIme.suggestionUpdater.requestDelayed(SuggestionUpdater.Reason.AFTER_CURSOR_MOVE);
            mInputLogic.updateNuanceContext(mInputLogic.mRichInputConnection.getTextContextBefore(), mInputLogic.mComposingTracker.getRecorrectionCursorPosition(), mInputLogic.mComposingTracker.getRecorrectionOriginalWord());
            return;
        }

        if (mInputLogic.mEventDispatcher.isBatchInput()) {
            c0875apM5829b = mInputLogic.mRichInputConnection.getWordAtCursor(mInputLogic.mEventDispatcher.committedWord, settingsValues.spacingAndPunctuation, currentKeyboardScriptId);
        } else {
            c0875apM5829b = mInputLogic.mRichInputConnection.getWordRangeAtCursor(settingsValues.spacingAndPunctuation, currentKeyboardScriptId);
        }

        if (c0875apM5829b == null) {
            return;
        }

        if (c0875apM5829b.length() <= 0) {
            mInputLogic.mIme.setNeutralSuggestionStrip();
            return;
        }

        if (!c0875apM5829b.hasUrlSpans && (iM5646a = c0875apM5829b.getNumberOfCharsInWordBeforeCursor()) <= iM5853q) {
            ArrayList<SuggestedWords.SuggestedWordInfo> arrayList = new ArrayList<>();
            String string = c0875apM5829b.word.toString();

            if (includeWordAtCursor) {
                arrayList.add(new SuggestedWords.SuggestedWordInfo(string, SuggestedWords.getMaxSuggestionCount() + 1, 0, Dictionary.DICTIONARY_USER_TYPED, -1, -1, null));
            }

            if (LocaleUtils.isCurrentSubtypePinyin() && !isValidPinyinWord(settingsValues, string)) {
                mInputLogic.mComposingTracker.setShiftState(0);
                mInputLogic.mIme.suggestionUpdater.requestDelayed(SuggestionUpdater.Reason.AFTER_CURSOR_MOVE);
                return;
            }

            if (!isValidRecorrectionWord(settingsValues, string)) {
                mInputLogic.mSuggestionStripListener.setNeutralSuggestionStrip();
                return;
            }

            SuggestionSpan[] suggestionSpanArr = c0875apM5829b.getSuggestionSpansAtWord();
            int length = suggestionSpanArr.length;
            int suggestionCount = 0;

            for (int spanIndex = 0; spanIndex < length; spanIndex++) {
                String[] suggestions = suggestionSpanArr[spanIndex].getSuggestions();
                int suggestionsLength = suggestions.length;

                for (int suggestionIndex = 0; suggestionIndex < suggestionsLength; suggestionIndex++) {
                    String suggestion = suggestions[suggestionIndex];
                    suggestionCount++;

                    if (!TextUtils.equals(suggestion, string)) {
                        arrayList.add(new SuggestedWords.SuggestedWordInfo(suggestion, SuggestedWords.getMaxSuggestionCount() - suggestionCount, 9, Dictionary.DICTIONARY_RESUMED, -1, -1, null));
                    }
                }
            }

            int[] codePoints = toCodePointArray((CharSequence) string);

            int deletionLength = iM5853q - iM5646a;
            if (BuildConfig.DEBUG) Log.d("TEXT_EDIT_DEBUG", "performRecorrection: word='" + string + "' cursor=" + iM5853q + " wordStartOffset=" + iM5646a + " wordStartAbsolute=" + deletionLength + " regionEnd=" + (iM5853q + c0875apM5829b.getNumberOfCharsInWordAfterCursor()) + " charsAfterCursorInWord=" + c0875apM5829b.getNumberOfCharsInWordAfterCursor());
            mInputLogic.mComposingTracker.setEventIndex(deletionLength);
            mInputLogic.mComposingTracker.setComposingFromCodePoints(codePoints, mInputLogic.mIme.getKeyCoordinates(codePoints));
            mInputLogic.mComposingTracker.setComposingCursorPosition(string.codePointCount(0, iM5646a));
            mInputLogic.mRichInputConnection.setComposingRegion(deletionLength, iM5853q + c0875apM5829b.getNumberOfCharsInWordAfterCursor());
            mInputLogic.updateNuanceContext(mInputLogic.mRichInputConnection.getTextContextBefore() + " " + string, deletionLength, string);

            if (arrayList.size() > 0) {
                mInputLogic.mSuggestionRequestQueue.sendToWorkerThread(0, new SuggestionEngine.SuggestionCallback() {
                    /**
                     * @param suggestedWords the suggestions for the recorrection word
                     */
                    @Override
                    public void onSuggestionsReady(SuggestedWords suggestedWords) {
                        if (suggestedWords.size() > 1 && !includeWordAtCursor && !LocaleUtils.isCurrentSubtypeJapanese()) {
                            suggestedWords = suggestedWords.getSuggestedWordsForDisplay();
                        }
                        mInputLogic.mIsAutoCorrectActive = false;
                        mInputLogic.mIme.uiUpdateHandler.postShowSuggestionStrip(suggestedWords);
                    }
                });
                return;
            }

            SuggestedWords c0666ac = new SuggestedWords(arrayList, string, false, false, 5);
            mInputLogic.mIsAutoCorrectActive = false;
            mInputLogic.mIme.uiUpdateHandler.postShowSuggestionStrip(c0666ac);
        }
    }

    /**
     * @param dropWhitespaceSeparator true for a keyboard backspace: a whitespace separator is
     *        deleted, not restored, so the typed word comes back composing (else the next space
     *        leaves two). Swipe-to-delete passes false and keeps it.
     * @param inputEventContext the current input event context
     * @param settingsValues current settings values
     */
    void revertAutoCorrection(InputEventContext inputEventContext, SettingsValues settingsValues, boolean dropWhitespaceSeparator) {
        String str;
        int i;
        CharSequence charSequence;
        String str2 = mInputLogic.mEventDispatcher.typedWord;
        String string = str2 != null ? str2.toString() : "";
        boolean zMatches = string.length() > 0 ? PUNCTUATION.matcher(string.substring(string.length() - 1)).matches() : false;
        CharSequence charSequence2 = mInputLogic.mEventDispatcher.committedWord;
        String string2 = charSequence2.toString();
        int length = charSequence2.length();
        String str3 = mInputLogic.mEventDispatcher.wordSeparator;
        Locale locale = inputEventContext.settingsValues.locale;
        if (1 == inputEventContext.commitType) {
            str3 = ". ";
        }
        int length2 = str3.length();
        boolean z = length2 > 0;
        CharSequence charSequenceM5815a = mInputLogic.mRichInputConnection.getTextBeforeCursor(NuanceSDK.MAX_CONTEXT_LENGTH, 0);
        int length3 = charSequenceM5815a.length();
        int i2 = 0;
        while (length3 > 0) {
            int iCodePointBefore = Character.codePointBefore(charSequenceM5815a, length3);
            if (!Character.isWhitespace(iCodePointBefore)) {
                charSequence = charSequenceM5815a;
                // Whole code point, not a (char) cast that would yield a lone surrogate.
                if (iCodePointBefore != 10 && (!PUNCTUATION.matcher(new String(Character.toChars(iCodePointBefore))).matches() || zMatches)) {
                    break;
                }
            } else {
                charSequence = charSequenceM5815a;
            }
            int charCount = Character.charCount(iCodePointBefore);
            i2 += charCount;
            length3 -= charCount;
            charSequenceM5815a = charSequence;
        }
        CharSequence charSequenceM5815a2 = mInputLogic.mRichInputConnection.getTextBeforeCursor(length2, 0);
        if (charSequenceM5815a2 == null || !TextUtils.equals(str3, charSequenceM5815a2.toString())) {
            z = false;
        }
        if (z && dropWhitespaceSeparator && TextUtils.getTrimmedLength(str3) == 0) {
            z = false;
        }
        mInputLogic.mRichInputConnection.deleteSurroundingText(length + i2, 0);
        if (!TextUtils.isEmpty(charSequence2)) {
            mInputLogic.mDictionaryLoader.unlearnWord(string2);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(str2.toString());
        sb.append(z ? str3 : "");
        String string3 = sb.toString();
        SpannableString spannableString = new SpannableString(string3);
        if (charSequence2 instanceof SpannableString) {
            SpannableString spannableString2 = (SpannableString) charSequence2;
            Object[] spans = spannableString2.getSpans(0, charSequence2.length(), Object.class);
            int length4 = spannableString.length() - 1;
            ArrayList arrayList = new ArrayList();
            arrayList.add(string2);
            int length5 = spans.length;
            int i3 = 0;
            while (i3 < length5) {
                Object obj = spans[i3];
                Object[] objArr = spans;
                if (obj instanceof SuggestionSpan) {
                    SuggestionSpan suggestionSpan = (SuggestionSpan) obj;
                    i = length5;
                    if (suggestionSpan.getLocale().equals(locale.toString())) {
                        String[] suggestions = suggestionSpan.getSuggestions();
                        int length6 = suggestions.length;
                        int i4 = 0;
                        while (i4 < length6) {
                            int i5 = length6;
                            String str4 = suggestions[i4];
                            if (!str4.equals(string2)) {
                                arrayList.add(str4);
                            }
                            i4++;
                            length6 = i5;
                        }
                    }
                } else {
                    i = length5;
                    spannableString.setSpan(obj, 0, length4, spannableString2.getSpanFlags(obj));
                }
                i3++;
                spans = objArr;
                length5 = i;
            }
            spannableString.setSpan(new SuggestionSpan(locale, (String[]) arrayList.toArray(new String[arrayList.size()]), 0), 0, length4, 0);
        }
        boolean zM4409a = shouldShowAddToDictionary(mInputLogic.mEventDispatcher, settingsValues);
        mInputLogic.mSuggestionEngine.setSkipNuanceCheck(true);
        if (!z) {
            int[] iArrM5474a = toCodePointArray((CharSequence) string3);
            mInputLogic.mComposingTracker.setEventIndex(mInputLogic.mRichInputConnection.getCursorStart());
            mInputLogic.mComposingTracker.setComposingFromCodePoints(iArrM5474a, mInputLogic.mIme.getKeyCoordinates(iArrM5474a));
            String strM5858v = mInputLogic.mRichInputConnection.getTextContextBefore();
            if (strM5858v == null) {
                str = "";
            } else {
                str = strM5858v + string2;
            }
            mInputLogic.updateNuanceContext(str, mInputLogic.mRichInputConnection.getCursorStart(), string2);
            mInputLogic.mComposingTracker.updateRecorrectionSnapshot();
            if (zM4409a) {
                mInputLogic.setComposingTextWithHighlight(spannableString, 1, settingsValues.textHighlightColorForAddToDictionary, 0, string.length());
            } else {
                mInputLogic.setComposingTextInternal(spannableString, 1);
            }
        } else if (zM4409a) {
            mInputLogic.mRichInputConnection.commitTextWithHighlight(spannableString, 1, settingsValues.textHighlightColorForAddToDictionary, string.length());
        } else {
            mInputLogic.mRichInputConnection.commitText(spannableString, 1);
        }
        int i6 = mInputLogic.mEventDispatcher.shiftStateAtCommit;
        mInputLogic.mEventDispatcher = CommitEventRecord.IDLE;
        if (zM4409a) {
            if (!hasLineBreakCharacter(str3)) {
                mInputLogic.mMoreKeysController.showCommitIndicator(string, i6, mInputLogic.mRichInputConnection.getCursorStart(), mInputLogic.mRichInputConnection.getCursorEnd());
            }
            mInputLogic.mSuggestionStripListener.showAddToDictionaryHint(string);
            return;
        }
        inputEventContext.setShouldUpdateSuggestions();
    }

    /**
     * @param commitEventRecord the last commit record
     * @param settingsValues current settings values
     */
    private boolean shouldShowAddToDictionary(CommitEventRecord commitEventRecord, SettingsValues settingsValues) {
        // An incognito field offers no add-to-dictionary: the word would outlive the session.
        if (mInputLogic.mIme.getDynamicLearningManager().isNoPersonalizedLearning()) {
            return false;
        }
        if (mInputLogic.mRichInputConnection.isMonitoringCursorUpdates() && settingsValues.showUiToAcceptTypedWord && !TextUtils.isEmpty(commitEventRecord.typedWord) && !TextUtils.equals(commitEventRecord.typedWord, commitEventRecord.committedWord)) {
            // Bounded engine query. On a timeout the word counts as known (no add-word hint).
            final String typedWord = commitEventRecord.typedWord;
            final DictionaryLoader loader = mInputLogic.mDictionaryLoader;
            return !EngineThreads.query(() -> loader.isWordValid(typedWord, true),
                    RECORRECTION_VALIDITY_TIMEOUT_MS, Boolean.TRUE);
        }
        return false;
    }

    /**
     * @param settingsValues current settings values
     * @param word the candidate word
     */
    private static boolean isValidRecorrectionWord(SettingsValues settingsValues, String word) {
        if (word.isEmpty()) {
            return false;
        }
        int iCodePointAt = word.codePointAt(0);
        return (!settingsValues.isWordCodePoint(iCodePointAt) || 39 == iCodePointAt || 45 == iCodePointAt) ? false : true;
    }

    /**
     * @param settingsValues current settings values
     * @param word the candidate word
     */
    private static boolean isValidPinyinWord(SettingsValues settingsValues, String word) {
        if (word.isEmpty()) {
            return false;
        }
        int iCodePointAt = word.codePointAt(0);
        return (!settingsValues.isLetterOrDigit(iCodePointAt) || 39 == iCodePointAt || 45 == iCodePointAt || Character.isWhitespace(word.codePointAt(word.length() - 1))) ? false : true;
    }
}

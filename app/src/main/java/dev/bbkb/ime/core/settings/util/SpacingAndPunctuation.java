package dev.bbkb.ime.core.settings.util;

import static dev.bbkb.ime.core.shared.StringHelper.*;

import android.content.res.Resources;

import dev.bbkb.ime.R;
import dev.bbkb.ime.core.suggestion.PunctuationSuggestions;
import dev.bbkb.ime.core.locale.LocaleUtils;
import dev.bbkb.ime.keyboard.internal.KeySpecParser;

import java.util.Arrays;
import java.util.Locale;

/**
 * Defines the spacing and punctuation rules for the current input language. It holds arrays of
 * characters for word separators, connectors, and symbols that require specific spacing behavior.
 */



public final class SpacingAndPunctuation {

    public final int[] wordSeparators;

    public final int[] notSeparatorsPinyin;

    public final int[] notSeparatorsCangjie;

    public final int[] notSeparatorsStroke;

    public final int[] notSeparatorsZhuyin;

    public final int[] swipeDeleteDelimiters;

    public final PunctuationSuggestions suggestedPunctuations;

    public final String sentenceSeparatorAndSpace;

    public final boolean currentLanguageHasSpaces;

    public final boolean supportsDoubleSpacePeriod;

    public final boolean usesAmericanTypography;

    public final boolean usesGermanRules;

    private final int[] symbolsPrecededBySpace;

    private final int[] symbolsPrecededBySpaceFrench = toSortedCodePointArray("!?«");

    private final int[] symbolsFollowedBySpace;

    private final int[] symbolsClusteringTogether;

    private final int[] wordConnectors;

    private final int sentenceSeparatorChar;

    /** U+0964 DEVANAGARI DANDA - the sentence separator Hindi uses in place of '.'. */
    private static final int DEVANAGARI_DANDA = 0x0964;

    public SpacingAndPunctuation(Resources resources) {
        this.symbolsPrecededBySpace = toSortedCodePointArray(resources.getString(R.string.symbols_preceded_by_space));
        this.symbolsFollowedBySpace = toSortedCodePointArray(resources.getString(R.string.symbols_followed_by_space));
        this.symbolsClusteringTogether = toSortedCodePointArray(resources.getString(R.string.symbols_clustering_together));
        this.wordConnectors = toSortedCodePointArray(resources.getString(R.string.symbols_word_connectors));
        this.wordSeparators = toSortedCodePointArray(resources.getString(R.string.symbols_word_separators));
        this.notSeparatorsPinyin = toSortedCodePointArray(resources.getString(R.string.symbols_not_word_separators_pinyin));
        this.notSeparatorsCangjie = toSortedCodePointArray(resources.getString(R.string.symbols_not_word_separators_cangjie));
        this.notSeparatorsStroke = toSortedCodePointArray(resources.getString(R.string.symbols_not_word_separators_stroke));
        this.notSeparatorsZhuyin = toSortedCodePointArray(resources.getString(R.string.symbols_not_word_separators_zhuyin));
        this.swipeDeleteDelimiters = toSortedCodePointArray(resources.getString(R.string.symbols_swipe_delete_delimiters));
        this.sentenceSeparatorChar = resources.getInteger(R.integer.sentence_separator);
        this.sentenceSeparatorAndSpace = new String(new int[]{this.sentenceSeparatorChar, ' '}, 0, 2);
        this.currentLanguageHasSpaces = resources.getBoolean(R.bool.current_language_has_spaces);
        this.supportsDoubleSpacePeriod = resources.getBoolean(R.bool.current_language_supports_double_space_period);
        Locale locale = LocaleUtils.getConfigurationLocale(resources);
        this.usesAmericanTypography = Locale.ENGLISH.getLanguage().equals(locale.getLanguage());
        this.usesGermanRules = Locale.GERMAN.getLanguage().equals(locale.getLanguage());
        this.suggestedPunctuations = PunctuationSuggestions.newPunctuationSuggestions(KeySpecParser.splitKeySpecs(resources.getString(R.string.suggested_punctuations)));
    }


    public SpacingAndPunctuation(SpacingAndPunctuation c0806f, int[] iArr) {
        this.symbolsPrecededBySpace = c0806f.symbolsPrecededBySpace;
        this.symbolsFollowedBySpace = c0806f.symbolsFollowedBySpace;
        this.symbolsClusteringTogether = c0806f.symbolsClusteringTogether;
        this.wordConnectors = c0806f.wordConnectors;
        this.wordSeparators = iArr;
        this.notSeparatorsPinyin = c0806f.notSeparatorsPinyin;
        this.notSeparatorsCangjie = c0806f.notSeparatorsCangjie;
        this.notSeparatorsStroke = c0806f.notSeparatorsStroke;
        this.notSeparatorsZhuyin = c0806f.notSeparatorsZhuyin;
        this.swipeDeleteDelimiters = c0806f.swipeDeleteDelimiters;
        this.suggestedPunctuations = c0806f.suggestedPunctuations;
        this.sentenceSeparatorChar = c0806f.sentenceSeparatorChar;
        this.sentenceSeparatorAndSpace = c0806f.sentenceSeparatorAndSpace;
        this.currentLanguageHasSpaces = c0806f.currentLanguageHasSpaces;
        this.supportsDoubleSpacePeriod = c0806f.supportsDoubleSpacePeriod;
        this.usesAmericanTypography = c0806f.usesAmericanTypography;
        this.usesGermanRules = c0806f.usesGermanRules;
    }

    public boolean isWordSeparator(int i) {
        return Arrays.binarySearch(this.wordSeparators, i) >= 0;
    }

    public boolean isNotWordSeparatorForLocale(int i, Locale locale) {
        int iBinarySearch;
        if (LocaleUtils.isChinesePinyin(locale)) {
            iBinarySearch = Arrays.binarySearch(this.notSeparatorsPinyin, i);
        } else if (LocaleUtils.isChineseCangjie(locale)) {
            iBinarySearch = Arrays.binarySearch(this.notSeparatorsCangjie, i);
        } else if (LocaleUtils.isChineseStroke(locale)) {
            iBinarySearch = Arrays.binarySearch(this.notSeparatorsStroke, i);
        } else {
            iBinarySearch = LocaleUtils.isChineseZhuyin(locale) ? Arrays.binarySearch(this.notSeparatorsZhuyin, i) : -1;
        }
        return iBinarySearch >= 0;
    }

    public boolean isSwipeDeleteDelimiter(int i) {
        return Arrays.binarySearch(this.swipeDeleteDelimiters, i) >= 0;
    }

    public boolean isWordConnector(int i) {
        return Arrays.binarySearch(this.wordConnectors, i) >= 0;
    }

    public boolean isLetterOrConnector(int i) {
        return Character.isLetter(i) || isWordConnector(i);
    }

    public boolean isPrecededBySpace(int i) {
        return Arrays.binarySearch(this.symbolsPrecededBySpace, i) >= 0;
    }

    public boolean isPrecededBySpaceFrench(int i) {
        return LocaleUtils.isCurrentSubtypeNonCanadianFrench() && Arrays.binarySearch(this.symbolsPrecededBySpaceFrench, i) >= 0;
    }

    public boolean isFollowedBySpace(int i) {
        return Arrays.binarySearch(this.symbolsFollowedBySpace, i) >= 0;
    }

    public boolean clustersWithSymbols(int i) {
        return Arrays.binarySearch(this.symbolsClusteringTogether, i) >= 0;
    }

    public boolean isSentenceSeparator(int i) {
        return i == this.sentenceSeparatorChar;
    }

    public String getSentenceSeparatorAndSpace() {
        if (LocaleUtils.isCurrentSubtypeHindi()) {
            return new String(new int[]{DEVANAGARI_DANDA, ' '}, 0, 2);
        }
        return this.sentenceSeparatorAndSpace;
    }

    public String dumpSettings() {
        StringBuilder sb = new StringBuilder();
        sb.append("mSortedSymbolsPrecededBySpace = ");
        sb.append("" + Arrays.toString(this.symbolsPrecededBySpace));
        sb.append("\n   mSortedSymbolsFollowedBySpace = ");
        sb.append("" + Arrays.toString(this.symbolsFollowedBySpace));
        sb.append("\n   mSortedWordConnectors = ");
        sb.append("" + Arrays.toString(this.wordConnectors));
        sb.append("\n   mSortedWordSeparators = ");
        sb.append("" + Arrays.toString(this.wordSeparators));
        sb.append("\n   mSortedNotWordSeparatorsPinyin = ");
        sb.append("" + Arrays.toString(this.notSeparatorsPinyin));
        sb.append("\n   mSortedNotWordSeparatorsCangjie = ");
        sb.append("" + Arrays.toString(this.notSeparatorsCangjie));
        sb.append("\n   mSortedNotWordSeparatorsStroke = ");
        sb.append("" + Arrays.toString(this.notSeparatorsStroke));
        sb.append("\n   mSortedNotWordSeparatorsZhuyin = ");
        sb.append("" + Arrays.toString(this.notSeparatorsZhuyin));
        sb.append("\n   mSwipeDeleteDelimiters = ");
        sb.append("" + Arrays.toString(this.swipeDeleteDelimiters));
        sb.append("\n   mSuggestPuncList = ");
        sb.append("" + this.suggestedPunctuations);
        sb.append("\n   mSentenceSeparator = ");
        sb.append("" + this.sentenceSeparatorChar);
        sb.append("\n   mSentenceSeparatorAndSpace = ");
        sb.append("" + this.sentenceSeparatorAndSpace);
        sb.append("\n   mCurrentLanguageHasSpaces = ");
        sb.append("" + this.currentLanguageHasSpaces);
        sb.append("\n   mCurrentLanguageSupportsDoubleSpacePeriod = ");
        sb.append("" + this.supportsDoubleSpacePeriod);
        sb.append("\n   mUsesAmericanTypography = ");
        sb.append("" + this.usesAmericanTypography);
        sb.append("\n   mUsesGermanRules = ");
        sb.append("" + this.usesGermanRules);
        return sb.toString();
    }
}

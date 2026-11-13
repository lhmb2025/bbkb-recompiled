package dev.bbkb.ime.core.settings.util;

import static dev.bbkb.ime.core.shared.StringHelper.*;

import android.content.res.Configuration;
import android.content.res.Resources;

import dev.bbkb.ime.R;
import dev.bbkb.ime.core.suggestion.PunctuationSuggestions;
import dev.bbkb.ime.core.locale.LocaleUtils;
import dev.bbkb.ime.keyboard.internal.KeySpecParser;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Spacing and punctuation rules for the current input language: separators, connectors, spacing sets. */



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

    /** U+0964 DEVANAGARI DANDA, the sentence separator Hindi uses in place of '.'. */
    private static final int DEVANAGARI_DANDA = 0x0964;

    /** Parsed sets per resources configuration (which carries the keyboard locale); a few at most. */
    private static final int CACHE_SIZE = 4;

    private static final LinkedHashMap<Configuration, SpacingAndPunctuation> sCache =
            new LinkedHashMap<Configuration, SpacingAndPunctuation>(CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Configuration, SpacingAndPunctuation> eldest) {
                    return size() > CACHE_SIZE;
                }
            };

    /** The rules for {@code resources}, parsed once per configuration (all come from resources); immutable. */
    public static SpacingAndPunctuation forResources(Resources resources) {
        final Configuration key = new Configuration(resources.getConfiguration());
        synchronized (sCache) {
            final SpacingAndPunctuation cached = sCache.get(key);
            if (cached != null) {
                return cached;
            }
        }
        final SpacingAndPunctuation parsed = new SpacingAndPunctuation(resources);
        synchronized (sCache) {
            sCache.put(key, parsed);
        }
        return parsed;
    }

    /** For tests. */
    static void clearCache() {
        synchronized (sCache) {
            sCache.clear();
        }
    }

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

    /**
     * @param model the rules to copy
     * @param overrideWordSeparators the sorted word separators to use instead
     */
    public SpacingAndPunctuation(SpacingAndPunctuation model, int[] overrideWordSeparators) {
        this.symbolsPrecededBySpace = model.symbolsPrecededBySpace;
        this.symbolsFollowedBySpace = model.symbolsFollowedBySpace;
        this.symbolsClusteringTogether = model.symbolsClusteringTogether;
        this.wordConnectors = model.wordConnectors;
        this.wordSeparators = overrideWordSeparators;
        this.notSeparatorsPinyin = model.notSeparatorsPinyin;
        this.notSeparatorsCangjie = model.notSeparatorsCangjie;
        this.notSeparatorsStroke = model.notSeparatorsStroke;
        this.notSeparatorsZhuyin = model.notSeparatorsZhuyin;
        this.swipeDeleteDelimiters = model.swipeDeleteDelimiters;
        this.suggestedPunctuations = model.suggestedPunctuations;
        this.sentenceSeparatorChar = model.sentenceSeparatorChar;
        this.sentenceSeparatorAndSpace = model.sentenceSeparatorAndSpace;
        this.currentLanguageHasSpaces = model.currentLanguageHasSpaces;
        this.supportsDoubleSpacePeriod = model.supportsDoubleSpacePeriod;
        this.usesAmericanTypography = model.usesAmericanTypography;
        this.usesGermanRules = model.usesGermanRules;
    }

    /** @param code a code point */
    public boolean isWordSeparator(int code) {
        return Arrays.binarySearch(this.wordSeparators, code) >= 0;
    }

    /** @param code a code point */
    public boolean isNotWordSeparatorForLocale(int code, Locale locale) {
        int iBinarySearch;
        if (LocaleUtils.isChinesePinyin(locale)) {
            iBinarySearch = Arrays.binarySearch(this.notSeparatorsPinyin, code);
        } else if (LocaleUtils.isChineseCangjie(locale)) {
            iBinarySearch = Arrays.binarySearch(this.notSeparatorsCangjie, code);
        } else if (LocaleUtils.isChineseStroke(locale)) {
            iBinarySearch = Arrays.binarySearch(this.notSeparatorsStroke, code);
        } else {
            iBinarySearch = LocaleUtils.isChineseZhuyin(locale) ? Arrays.binarySearch(this.notSeparatorsZhuyin, code) : -1;
        }
        return iBinarySearch >= 0;
    }

    /** @param code a code point */
    public boolean isSwipeDeleteDelimiter(int code) {
        return Arrays.binarySearch(this.swipeDeleteDelimiters, code) >= 0;
    }

    /** @param code a code point */
    public boolean isWordConnector(int code) {
        return Arrays.binarySearch(this.wordConnectors, code) >= 0;
    }

    /** @param code a code point */
    public boolean isLetterOrConnector(int code) {
        return Character.isLetter(code) || isWordConnector(code);
    }

    /** @param code a code point */
    public boolean isPrecededBySpace(int code) {
        return Arrays.binarySearch(this.symbolsPrecededBySpace, code) >= 0;
    }

    /** @param code a code point */
    public boolean isPrecededBySpaceFrench(int code) {
        return LocaleUtils.isCurrentSubtypeNonCanadianFrench() && Arrays.binarySearch(this.symbolsPrecededBySpaceFrench, code) >= 0;
    }

    /** @param code a code point */
    public boolean isFollowedBySpace(int code) {
        return Arrays.binarySearch(this.symbolsFollowedBySpace, code) >= 0;
    }

    /** @param code a code point */
    public boolean clustersWithSymbols(int code) {
        return Arrays.binarySearch(this.symbolsClusteringTogether, code) >= 0;
    }

    /** @param code a code point */
    public boolean isSentenceSeparator(int code) {
        return code == this.sentenceSeparatorChar;
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

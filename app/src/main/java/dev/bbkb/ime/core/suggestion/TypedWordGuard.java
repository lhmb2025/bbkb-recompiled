package dev.bbkb.ime.core.suggestion;

import com.blackberry.nuanceshim.Xt9KdbVariant;

import java.text.Normalizer;
import java.util.Locale;

/**
 * A word the dictionary knows is never replaced on space, even when the learned model ranks a
 * correction above it. {@link SuggestionEngine} asks: the typed word is valid, may this correction
 * still replace it? Only for case or diacritics (paris to Paris), or inserted apostrophes when the
 * contraction is more common in the English LDB table (cant to can't, but not well to we'll; a tie
 * keeps the typed word). Other languages, or a spelling the table lacks, let the engine's pick stand.
 */
public final class TypedWordGuard {

    /** Source of zipf*10 word frequencies, 0 = unknown. Replaceable for tests. */
    public interface Frequency {
        int zipf10(String lowercaseWord);
    }

    private static final Frequency ENGINE_TABLE = Xt9KdbVariant::wordZipf10;

    private static volatile Frequency sFrequency = ENGINE_TABLE;

    private TypedWordGuard() {
    }

    /** Tests only: null restores the engine table. */
    public static void setFrequencyForTest(Frequency frequency) {
        sFrequency = frequency != null ? frequency : ENGINE_TABLE;
    }

    /**
     * True when {@code correction} may replace the valid typed word {@code typed}: the same
     * letters once case, diacritics and apostrophes are set aside, and if apostrophes were
     * inserted, the more common spelling where the table knows both.
     */
    public static boolean allowsCorrection(String typed, String correction, Locale locale) {
        if (typed == null || correction == null || typed.isEmpty() || correction.isEmpty()) {
            return false;
        }
        Locale cmp = locale != null ? locale : Locale.ROOT;
        String typedFolded = fold(typed, cmp);
        if (typedFolded.isEmpty() || !typedFolded.equals(fold(correction, cmp))) {
            return false;   // different letters: a replacement, which the rule forbids
        }
        if (countApostrophes(correction) <= countApostrophes(typed)) {
            return true;    // case or diacritics only
        }
        if (!"en".equals(cmp.getLanguage())) {
            return true;    // the frequency table is English
        }
        int typedZipf = sFrequency.zipf10(plainApostrophes(typed).toLowerCase(cmp));
        int correctionZipf = sFrequency.zipf10(plainApostrophes(correction).toLowerCase(cmp));
        if (typedZipf == 0 || correctionZipf == 0) {
            return true;    // one spelling unknown to the table: nothing to weigh
        }
        return correctionZipf > typedZipf;
    }

    /** Lower-case letters only: no case, no combining marks, no apostrophes. */
    static String fold(String s, Locale locale) {
        String decomposed = Normalizer.normalize(s, Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);
            if (isApostrophe(c)) continue;
            int type = Character.getType(c);
            if (type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
                    || type == Character.ENCLOSING_MARK) continue;
            sb.append(c);
        }
        return sb.toString().toLowerCase(locale);
    }

    private static int countApostrophes(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (isApostrophe(s.charAt(i))) n++;
        }
        return n;
    }

    /** The table stores ASCII apostrophes; typed text may carry the typographic one. */
    private static String plainApostrophes(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(isApostrophe(c) ? '\'' : c);
        }
        return sb.toString();
    }

    private static boolean isApostrophe(char c) {
        return c == '\'' || c == '\u2019' || c == '\u02BC';
    }
}

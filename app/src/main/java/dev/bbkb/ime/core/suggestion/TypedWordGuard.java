package dev.bbkb.ime.core.suggestion;

import com.blackberry.nuanceshim.Xt9KdbVariant;

import java.text.Normalizer;
import java.util.Locale;

/**
 * The rule that a word the dictionary knows is never replaced on space ("autocorrect should not
 * correct if the typed word matches an XT9 result" — owner, 2026-10-12).
 *
 * <p>The engine's ranking can put a correction above an exact, valid typed word once the learned
 * model has boosted that correction: about → bout and this → his on the owner's KEY2, doing →
 * Doping and dnt over don't before that (four poisoning events, see the DLM-poisoning notes). The
 * commit decision in {@link SuggestionEngine} follows the ranking, so it needs a validity check of
 * its own. This class answers one question for it: given that the typed word IS valid, may the
 * proposed correction still replace it?
 *
 * <p>Only when the correction is the same word in different dress:
 * <ul>
 *   <li>case or diacritics only (cafe → café, paris → Paris);</li>
 *   <li>apostrophes inserted (cant → can't, wont → won't) — and then only when the contracted
 *       form is the more common one. "well" and "were" outrank "we'll" and "we're" in the LDB,
 *       so they stay as typed; "cant" and "wont" are not dictionary words at all, so the table
 *       knows only the contraction and the correction goes through. A tie protects the typed
 *       word (its → it's). Frequencies come from the owned decoder's English LDB table; for
 *       other languages, or when either spelling is missing from the table, an apostrophe-only
 *       correction is allowed and the engine's pick stands.</li>
 * </ul>
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

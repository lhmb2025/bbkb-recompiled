package dev.bbkb.ime.core.subtypeswitcher;

import android.text.TextUtils;



public class SubtypeItem implements Comparable<SubtypeItem> {

    public final CharSequence displayName;

    public final int subtypeIndex;

    private final boolean matchesSystemLocale;

    private final boolean matchesSystemLanguage;

    public SubtypeItem(CharSequence charSequence, int i, String str, String str2) {
        this.displayName = charSequence;
        this.subtypeIndex = i;
        if (TextUtils.isEmpty(str) || str2 == null) {
            this.matchesSystemLocale = false;
            this.matchesSystemLanguage = false;
        } else {
            this.matchesSystemLocale = str.equals(str2);
            // str2 is a locale string; guard the language-prefix take so a malformed one-character
            // locale cannot throw here.
            this.matchesSystemLanguage = this.matchesSystemLocale
                    || (str2.length() >= 2 && str.startsWith(str2.substring(0, 2)));
        }
    }

    /** 0 = matches the system locale, 1 = matches the system language, 2 = neither. */
    private int systemMatchRank() {
        if (this.matchesSystemLocale) {
            return 0;
        }
        return this.matchesSystemLanguage ? 1 : 2;
    }

    /**
     * The old form returned -1 from both sides when two items shared the system locale (e.g. an
     * en_US qwerty subtype next to an en_US azerty additional subtype), which is an ordinary
     * configuration for the language switcher and makes TimSort throw
     * "Comparison method violates its general contract!". Rank first, then break ties by name.
     */
    @Override // java.lang.Comparable
    public int compareTo(SubtypeItem c0730c) {
        if (TextUtils.equals(this.displayName, c0730c.displayName) || this.subtypeIndex == c0730c.subtypeIndex) {
            return 0;
        }
        final int rankDifference = Integer.compare(systemMatchRank(), c0730c.systemMatchRank());
        if (rankDifference != 0) {
            return rankDifference;
        }
        if (TextUtils.isEmpty(this.displayName)) {
            return TextUtils.isEmpty(c0730c.displayName) ? 0 : 1;
        }
        if (TextUtils.isEmpty(c0730c.displayName)) {
            return -1;
        }
        return this.displayName.toString().compareTo(c0730c.displayName.toString());
    }
}

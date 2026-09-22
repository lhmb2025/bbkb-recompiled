package dev.bbkb.ime.core.locale.multilanguage;

import android.util.Pair;

import dev.bbkb.ime.core.locale.ResourceLocaleUtils;

import java.text.Collator;
import java.util.Comparator;



public class LocaleItem extends Pair<String, String> implements Comparable<LocaleItem> {

    /**
     * Built once: Collator.getInstance() used to run per comparison, i.e. O(n log n) collator
     * constructions per sort, and this comparator backs the TreeMap in MultiLanguageRepository.
     */
    private static final Collator DISPLAY_NAME_COLLATOR = Collator.getInstance();

    public static final Comparator<LocaleItem> DISPLAY_NAME_COMPARATOR = new Comparator<LocaleItem>() {
        @Override
        public int compare(LocaleItem c0711f, LocaleItem c0711f2) {
            synchronized (DISPLAY_NAME_COLLATOR) {
                return DISPLAY_NAME_COLLATOR.compare(c0711f.toString(), c0711f2.toString());
            }
        }
    };

    public LocaleItem(String str, String str2) {
        super(str, str2);
    }

    public LocaleItem(String str) {
        this(str, ResourceLocaleUtils.getSubtypeLocaleDisplayName(str));
    }

    @Override // android.util.Pair
    public String toString() {
        return (String) this.second;
    }

    @Override // java.lang.Comparable
    public int compareTo(LocaleItem c0711f) {
        return ((String) this.first).compareTo((String) c0711f.first);
    }
}

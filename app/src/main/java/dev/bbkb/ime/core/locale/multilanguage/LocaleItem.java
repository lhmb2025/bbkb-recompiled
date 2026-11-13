package dev.bbkb.ime.core.locale.multilanguage;

import android.util.Pair;

import dev.bbkb.ime.core.locale.ResourceLocaleUtils;

import java.text.Collator;
import java.util.Comparator;



public class LocaleItem extends Pair<String, String> implements Comparable<LocaleItem> {
    /** Built once and shared (synchronized): this comparator backs the TreeMap in MultiLanguageRepository. */
    private static final Collator DISPLAY_NAME_COLLATOR = Collator.getInstance();

    public static final Comparator<LocaleItem> DISPLAY_NAME_COMPARATOR = new Comparator<LocaleItem>() {
        /**
         * @param lhs first item, compared by display name
         * @param c0711f2 second item
         */
        @Override
        public int compare(LocaleItem lhs, LocaleItem c0711f2) {
            synchronized (DISPLAY_NAME_COLLATOR) {
                return DISPLAY_NAME_COLLATOR.compare(lhs.toString(), c0711f2.toString());
            }
        }
    };

    /**
     * @param localeString locale id, e.g. "en_US"
     * @param displayName display name
     */
    public LocaleItem(String localeString, String displayName) {
        super(localeString, displayName);
    }

    /** @param localeString locale id; the display name is looked up */
    public LocaleItem(String localeString) {
        this(localeString, ResourceLocaleUtils.getSubtypeLocaleDisplayName(localeString));
    }

    @Override // android.util.Pair
    public String toString() {
        return (String) this.second;
    }

    /** @param other the item to compare by locale id */
    @Override // java.lang.Comparable
    public int compareTo(LocaleItem other) {
        return ((String) this.first).compareTo((String) other.first);
    }
}

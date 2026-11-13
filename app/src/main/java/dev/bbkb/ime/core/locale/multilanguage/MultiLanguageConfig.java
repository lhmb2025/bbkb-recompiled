package dev.bbkb.ime.core.locale.multilanguage;

import android.view.inputmethod.InputMethodSubtype;

import dev.bbkb.ime.core.subtypeswitcher.SubtypeFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;



public class MultiLanguageConfig implements Comparable<MultiLanguageConfig> {

    private static final String TAG = "MultiLanguageConfig";

    private final LocaleItem primaryLocale;

    private final ArrayList<LocaleItem> supportingLocales;

    private final String keyboardLayoutSet;

    private String serializedForm;

    public MultiLanguageConfig(LocaleItem primaryLocale, ArrayList<LocaleItem> arrayList, String keyboardLayoutSet) {
        this.primaryLocale = primaryLocale;
        this.supportingLocales = arrayList;
        Collections.sort(this.supportingLocales);
        this.keyboardLayoutSet = keyboardLayoutSet;
        buildSerializedForm();
    }

    private void buildSerializedForm() {
        StringBuilder sb = new StringBuilder(this.primaryLocale.toString());
        Iterator<LocaleItem> it = this.supportingLocales.iterator();
        while (it.hasNext()) {
            LocaleItem next = it.next();
            sb.append("/");
            sb.append(next.toString());
        }
        this.serializedForm = sb.toString();
    }

    public LocaleItem getPrimaryLocale() {
        return this.primaryLocale;
    }

    public ArrayList<LocaleItem> getSupportingLocales() {
        return this.supportingLocales;
    }

    public String getKeyboardLayoutSet() {
        return this.keyboardLayoutSet;
    }

    public InputMethodSubtype toSubtype() {
        ArrayList arrayList = new ArrayList();
        Iterator<LocaleItem> it = this.supportingLocales.iterator();
        while (it.hasNext()) {
            arrayList.add(it.next().first);
        }
        return SubtypeFactory.createLanguageSubtype((String) this.primaryLocale.first, arrayList, this.keyboardLayoutSet,
                MultiLanguageRepository.isLatinScript((String) this.primaryLocale.first));
    }

    public String toString() {
        return this.serializedForm;
    }

    @Override // java.lang.Comparable
    public int compareTo(MultiLanguageConfig other) {
        int iCompareTo = this.primaryLocale.compareTo(other.primaryLocale);
        if (iCompareTo == 0) {
            iCompareTo = this.supportingLocales.size() - other.supportingLocales.size();
        }
        for (int i = 0; i < this.supportingLocales.size() && iCompareTo == 0; i++) {
            iCompareTo = this.supportingLocales.get(i).compareTo(other.supportingLocales.get(i));
        }
        return iCompareTo;
    }
}

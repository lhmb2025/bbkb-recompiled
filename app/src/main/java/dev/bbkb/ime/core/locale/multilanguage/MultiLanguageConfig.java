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

    public MultiLanguageConfig(LocaleItem c0711f, ArrayList<LocaleItem> arrayList, String str) {
        this.primaryLocale = c0711f;
        this.supportingLocales = arrayList;
        Collections.sort(this.supportingLocales);
        this.keyboardLayoutSet = str;
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
        return SubtypeFactory.createLanguageSubtype((String) this.primaryLocale.first, arrayList, this.keyboardLayoutSet);
    }

    public String toString() {
        return this.serializedForm;
    }

    @Override // java.lang.Comparable
    public int compareTo(MultiLanguageConfig c0710e) {
        int iCompareTo = this.primaryLocale.compareTo(c0710e.primaryLocale);
        if (iCompareTo == 0) {
            iCompareTo = this.supportingLocales.size() - c0710e.supportingLocales.size();
        }
        for (int i = 0; i < this.supportingLocales.size() && iCompareTo == 0; i++) {
            iCompareTo = this.supportingLocales.get(i).compareTo(c0710e.supportingLocales.get(i));
        }
        return iCompareTo;
    }
}

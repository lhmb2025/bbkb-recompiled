package dev.bbkb.ime.core.locale;

import android.content.Context;
import android.view.inputmethod.InputMethodSubtype;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import java.util.Objects;



public final class SubtypeDisplayNameHelper {

    private final HashMap<String, String> labelByLocalesKey;

    private final String separator;

    private final String defaultSpaceKeyIcon;

    public SubtypeDisplayNameHelper(Context context) {
        Objects.requireNonNull(context, "context");
        this.labelByLocalesKey = new HashMap<>();
        this.separator = " - ";
        this.defaultSpaceKeyIcon = "!icon/space_key";
        buildLabelMap();
    }

    public final void buildLabelMap() {
        String displayLanguage;
        List<InputMethodSubtype> listM4860a = RichInputMethodManager.getInstance().getEnabledSubtypesOfThisIme();
        ArrayList<Locale> arrayList = new ArrayList<>();
        for (InputMethodSubtype inputMethodSubtype : listM4860a) {
            if (!inputMethodSubtype.isAuxiliary()) {
                Locale localeM5625c = ResourceLocaleUtils.getSubtypeLocale(inputMethodSubtype);
                Set<Locale> setM5634h = ResourceLocaleUtils.getAdditionalLocales(inputMethodSubtype);
                arrayList.add(localeM5625c);
                if (setM5634h != null) {
                    arrayList.addAll(setM5634h);
                    StringBuilder joined = new StringBuilder();
                    for (Locale each : arrayList) {
                        joined.append(this.separator).append(each.getLanguage());
                    }
                    // Every entry is prefixed with the separator; drop the leading one.
                    displayLanguage = joined.substring(this.separator.length());
                } else {
                    displayLanguage = localeM5625c.getDisplayLanguage(localeM5625c);
                }
                this.labelByLocalesKey.put(Arrays.toString(arrayList.toArray()), displayLanguage);
                arrayList.clear();
            }
        }
    }

    public final String getSpaceKeyLabel(Locale locale, Set<Locale> set) {
        Objects.requireNonNull(locale, "locale");
        if (this.labelByLocalesKey.keySet().size() <= 1 || ResourceLocaleUtils.isNoLanguage(locale)) {
            return this.defaultSpaceKeyIcon;
        }
        ArrayList<Locale> arrayList = new ArrayList<>();
        arrayList.add(locale);
        if (set != null) {
            arrayList.addAll(set);
        }
        String str = this.labelByLocalesKey.get(Arrays.toString(arrayList.toArray()));
        return str != null ? str : this.defaultSpaceKeyIcon;
    }
}

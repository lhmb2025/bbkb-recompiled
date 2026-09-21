package dev.bbkb.ime.keyboard.internal;

import android.content.Context;
import android.content.res.Resources;
import android.text.TextUtils;

import dev.bbkb.ime.core.shared.RunInLocale;
import dev.bbkb.ime.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;



public final class KeyboardTextsSet {

    static final String[] RESOURCE_NAMES = {"label_go_key", "label_send_key", "label_next_key", "label_done_key", "label_search_key", "label_previous_key", "label_pause_key", "label_wait_key"};

    /**
     * Parallel to {@link #RESOURCE_NAMES}. These were resolved by name with
     * {@code Resources.getIdentifier}, which is slow and — worse — invisible to the resource
     * shrinker: a shrunk-away label returned id 0 and {@code getString(0)} threw at
     * keyboard-build time, taking out every keyboard rather than one label. The array is
     * fixed at compile time, so there was never a dynamic requirement.
     */
    private static final int[] RESOURCE_IDS = {R.string.label_go_key, R.string.label_send_key, R.string.label_next_key, R.string.label_done_key, R.string.label_search_key, R.string.label_previous_key, R.string.label_pause_key, R.string.label_wait_key};

    private String[] mTextsTable;

    private ArrayList<String[]> mAdditionalTextsTables = new ArrayList<>();

    private HashMap<String, String> mResourceNameToValueMap = new HashMap<>();

    public void setLocale(Locale locale, Context context) throws Resources.NotFoundException {
        setLocales(locale, null, context);
    }

    public void setLocales(Locale locale, Set<Locale> set, Context context) throws Resources.NotFoundException {
        this.mTextsTable = KeyboardTextSet.getTextsTable(locale);
        this.mAdditionalTextsTables.clear();
        if (set != null) {
            Iterator<Locale> it = set.iterator();
            while (it.hasNext()) {
                this.mAdditionalTextsTables.add(KeyboardTextSet.getTextsTable(it.next()));
            }
        }
        final Resources resources = context.getResources();
        RunInLocale<Void> loader = new RunInLocale<Void>() {
            @Override // dev.bbkb.ime.core.shareds.RunInLocale
            public Void job(Resources resources2) {
                KeyboardTextsSet.this.loadStringResourcesInternal(resources);
                return null;
            }
        };
        if ("zz".equals(locale.toString())) {
            locale = null;
        }
        loader.runInLocale(resources, locale);
    }

    public boolean hasAdditionalTexts() {
        return !this.mAdditionalTextsTables.isEmpty();
    }


    void loadStringResourcesInternal(Resources resources) {
        for (int i = 0; i < RESOURCE_NAMES.length; i++) {
            this.mResourceNameToValueMap.put(RESOURCE_NAMES[i], resources.getString(RESOURCE_IDS[i]));
        }
    }

    public String getText(String str) {
        return getTextInternal(str, this.mTextsTable);
    }

    public String getTextInternal(String str, String[] strArr) {
        String str2 = this.mResourceNameToValueMap.get(str);
        return str2 != null ? str2 : KeyboardTextSet.getTextInternal(str, strArr);
    }

    private static int searchTextNameEnd(String str, int i) {
        int length = str.length();
        while (i < length) {
            char cCharAt = str.charAt(i);
            if ((cCharAt < 'a' || cCharAt > 'z') && cCharAt != '_' && (cCharAt < '0' || cCharAt > '9')) {
                return i;
            }
            i++;
        }
        return length;
    }

    public String getResolvedText(String str) {
        return resolveTextReferences(str, false);
    }

    public String resolveTextReferences(String str, boolean z) {
        if (TextUtils.isEmpty(str)) {
            return null;
        }
        String strM7256b = resolveTextReferenceInTable(str, this.mTextsTable);
        if (!z) {
            return strM7256b;
        }
        StringBuilder sb = strM7256b != null ? new StringBuilder(strM7256b) : null;
        Iterator<String[]> it = this.mAdditionalTextsTables.iterator();
        while (it.hasNext()) {
            String strM7256b2 = resolveTextReferenceInTable(str, it.next());
            if (strM7256b2 != null) {
                if (sb == null) {
                    sb = new StringBuilder();
                }
                sb.append(',');
                sb.append(strM7256b2);
            }
        }
        return sb != null ? sb.toString() : strM7256b;
    }

    private String resolveTextReferenceInTable(String str, String[] strArr) {
        StringBuilder sb;
        String string = str;
        int i = 0;
        do {
            i++;
            if (i >= 10) {
                throw new RuntimeException("Too many !text/name indirection: " + string);
            }
            int length = string.length();
            if (length < 6) {
                break;
            }
            sb = null;
            int i2 = 0;
            while (i2 < length) {
                char cCharAt = string.charAt(i2);
                if (string.startsWith("!text/", i2)) {
                    if (sb == null) {
                        sb = new StringBuilder(string.substring(0, i2));
                    }
                    int i3 = i2 + 6;
                    int iM7255a = searchTextNameEnd(string, i3);
                    sb.append(getTextInternal(string.substring(i3, iM7255a), strArr));
                    i2 = iM7255a - 1;
                } else if (cCharAt == '\\') {
                    if (sb != null) {
                        sb.append(string.substring(i2, Math.min(i2 + 2, length)));
                    }
                    i2++;
                } else if (sb != null) {
                    sb.append(cCharAt);
                }
                i2++;
            }
            if (sb != null) {
                string = sb.toString();
            }
        } while (sb != null);
        if (TextUtils.isEmpty(string)) {
            return null;
        }
        return string;
    }
}
